package com.example.mototelemetryapp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.mototelemetryapp.data.AppDatabase
import com.example.mototelemetryapp.data.Session
import com.example.mototelemetryapp.data.TelemetryRecord
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class TelemetryService : Service() {

    private val channelId = "TelemetryServiceChannel"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var bluetoothOBDManager: BluetoothOBDManager? = null
    private var orientationManager: OrientationManager? = null
    private var db: AppDatabase? = null
    private var fusedLocationClient: FusedLocationProviderClient? = null
    
    private var lastLocation: Location? = null
    private var currentSessionId: Long = -1
    private var totalGpsDistanceMeters: Float = 0f
    private var startOdometer: Long = 0
    private var maxSpeed: Int = 0
    private var maxLeanLeft: Float = 0f
    private var maxLeanRight: Float = 0f
    private var maxCoolantTemp: Int = 0
    private var totalFuelConsumedLiters: Float = 0f
    private var trackingStarted = false

    // Set while the OBD link is down and we're waiting to see if it comes back before
    // finalizing the ride (see onObdLinkLost/onObdLinkRestored).
    private var graceTimeoutJob: Job? = null
    private var obdLinkReceiver: BroadcastReceiver? = null

    // Live data for UI
    private val _currentTelemetry = MutableStateFlow<TelemetryRecord?>(null)
    val currentTelemetry = _currentTelemetry.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): TelemetryService = this@TelemetryService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun calibrateLeanAngle() {
        orientationManager?.calibrate()
    }

    val obdConnected get() = bluetoothOBDManager?.isConnected

    @SuppressLint("MissingPermission")
    fun getPairedObdDevices(): List<Pair<String, String>> =
        bluetoothOBDManager?.getPairedDevices()?.map { (it.name ?: it.address) to it.address } ?: emptyList()

    suspend fun connectObd(address: String): Boolean =
        bluetoothOBDManager?.connectToDevice(address) ?: false

    fun disconnectObd() {
        bluetoothOBDManager?.disconnect()
    }

    val obdSweepRunning get() = bluetoothOBDManager?.sweepRunning
    val obdSweepResults get() = bluetoothOBDManager?.sweepResults
    val obdSweepProgress get() = bluetoothOBDManager?.sweepProgress

    val obdMilOn get() = bluetoothOBDManager?.milOn
    val obdDtcCodes get() = bluetoothOBDManager?.dtcCodes

    // Raw PID map, for values the Bike Info screen wants (e.g. odometer) that aren't part of
    // the recorded TelemetryRecord shape.
    val obdRawData get() = bluetoothOBDManager?.obdData

    suspend fun clearObdDtcs(): Boolean = bluetoothOBDManager?.clearDtcs() ?: false

    suspend fun runObdSweep() {
        bluetoothOBDManager?.sweepHeadersAndDids()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Initialize components
        bluetoothOBDManager = BluetoothOBDManager(this)
        orientationManager = OrientationManager(this)
        db = AppDatabase.getDatabase(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        registerObdLinkReceiver()
    }

    // Only reacts while auto-start is enabled and the event is for the remembered OBD device -
    // this is a live link-state signal (radio-level connect/disconnect), independent of and
    // more reliable than the SPP polling loop's own isConnected flag.
    private fun registerObdLinkReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val appPrefs = getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE)
                if (!appPrefs.getBoolean(KEY_AUTO_START_ON_OBD_CONNECT, false)) return

                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return

                val preferredAddress = bluetoothOBDManager?.getPreferredDeviceAddress() ?: return
                if (device.address != preferredAddress) return

                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> onObdLinkLost(appPrefs)
                    BluetoothDevice.ACTION_ACL_CONNECTED -> onObdLinkRestored()
                }
            }
        }
        obdLinkReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // Engine (and with it, the OBD adapter's power) just cut out. Drop the stale connection
    // right away so its polling loop stops, then give it a grace window to come back - e.g. a
    // red light or a quick fuel stop - before treating the ride as over.
    private fun onObdLinkLost(appPrefs: android.content.SharedPreferences) {
        graceTimeoutJob?.cancel()
        bluetoothOBDManager?.disconnect()

        val graceMinutes = appPrefs.getInt(KEY_RIDE_GRACE_PERIOD_MINUTES, DEFAULT_RIDE_GRACE_PERIOD_MINUTES)
        graceTimeoutJob = serviceScope.launch {
            delay(graceMinutes.minutes)
            Log.d("TelemetryService", "OBD link not restored within grace period; ending ride.")
            stopSelf()
        }
    }

    // Engine restarted. If this is within the grace window, currentSessionId is untouched so
    // reconnecting just resumes writing records into the same ride.
    private fun onObdLinkRestored() {
        graceTimeoutJob?.cancel()
        graceTimeoutJob = null
        serviceScope.launch { bluetoothOBDManager?.connect() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_content))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

        startForeground(
            1, 
            notification, 
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )

        // onStartCommand can fire again while already tracking (e.g. the OBD auto-start
        // receiver re-triggering on a flaky Bluetooth reconnect) - guard against spinning up
        // a second session/telemetry loop on top of the running one.
        if (!trackingStarted) {
            trackingStarted = true
            startLocationUpdates()
            orientationManager?.start()
            startTelemetryTracking()
        }

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .build()

        fusedLocationClient?.requestLocationUpdates(
            locationRequest,
            object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    val newLocation = locationResult.lastLocation ?: return
                    
                    // Accumulate distance
                    lastLocation?.let {
                        totalGpsDistanceMeters += it.distanceTo(newLocation)
                    }
                    
                    lastLocation = newLocation
                }
            },
            mainLooper
        )
    }

    private fun startTelemetryTracking() {
        serviceScope.launch {
            try {
                val database = db ?: run {
                    Log.e("TelemetryService", "Database not initialized")
                    return@launch
                }
                
                val obdManager = bluetoothOBDManager ?: run {
                    Log.e("TelemetryService", "BluetoothOBDManager not initialized")
                    return@launch
                }
                
                val orientManager = orientationManager ?: run {
                    Log.e("TelemetryService", "OrientationManager not initialized")
                    return@launch
                }

                // Finalize any session left with endTime == null by a previous run that
                // was killed before onDestroy() could run (e.g. backgrounded + OS/battery kill).
                recoverOrphanedSessions(database)

                // 1. Create a new Session
                val startTime = System.currentTimeMillis()
                val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(startTime))
                val session = Session(
                    name = "Ride - $dateStr",
                    startTime = startTime
                )
                currentSessionId = database.telemetryDao().insertSession(session)

                // 2. Start OBD2 Connection (best-effort; phone sensors keep streaming even if this fails)
                val connected = obdManager.connect()
                if (connected) {
                    startOdometer = (obdManager.obdData.value["ODOMETER"] ?: 0).toLong()
                } else {
                    Log.e("TelemetryService", "OBD2 connection failed; continuing with phone sensors only.")
                }

                while (isActive) {
                    try {
                        val obdData = obdManager.obdData.value
                        val leanPhone = orientManager.leanAngle.value
                        val leanBike = (obdData["LEAN_BIKE"] ?: 0).toFloat()
                        val coolant = obdData["COOLANT"] ?: 0
                        val speed = obdData["SPEED"] ?: 0
                        val fuelRate = (obdData["FUEL_RATE"] ?: 0) / 100f

                        // Update aggregates
                        maxSpeed = max(maxSpeed, speed)
                        maxCoolantTemp = max(maxCoolantTemp, coolant)
                        if (leanBike < 0) maxLeanLeft = max(maxLeanLeft, -leanBike) else maxLeanRight = max(maxLeanRight, leanBike)

                        // Integrate fuel consumption (Rate is Liters/Hour, interval is 0.2s)
                        totalFuelConsumedLiters += (fuelRate / 3600f) * 0.2f

                        val record = TelemetryRecord(
                            sessionId = currentSessionId,
                            timestamp = System.currentTimeMillis(),
                            speed = speed,
                            rpm = obdData["RPM"] ?: 0,
                            gear = obdData["GEAR"] ?: 0,
                            throttle = obdData["THROTTLE"] ?: 0,
                            brakeFront = obdData["BRAKE_FRONT"] ?: 0,
                            brakeRear = obdData["BRAKE_REAR"] ?: 0,
                            leanAnglePhone = leanPhone,
                            leanAngleBike = leanBike,
                            gForce = orientManager.gForce.value,
                            fuelRate = fuelRate,
                            fuelLevel = obdData["FUEL_LEVEL"] ?: 0,
                            coolantTemp = coolant,
                            altitude = lastLocation?.altitude ?: 0.0,
                            latitude = lastLocation?.latitude ?: 0.0,
                            longitude = lastLocation?.longitude ?: 0.0
                        )

                        _currentTelemetry.value = record
                        database.telemetryDao().insertRecord(record)
                        delay(200.milliseconds)
                    } catch (e: Exception) {
                        Log.e("TelemetryService", "Error in telemetry loop: ${e.message}", e)
                        delay(1000.milliseconds) // Wait before retrying
                    }
                }
            } catch (e: Exception) {
                Log.e("TelemetryService", "Fatal error in startTelemetryTracking: ${e.message}", e)
            }
        }
    }

    private suspend fun recoverOrphanedSessions(database: AppDatabase) {
        for (session in database.telemetryDao().getOpenSessions()) {
            val records = database.telemetryDao().getRecordsForSessionOnce(session.id)
            if (records.isEmpty()) {
                database.telemetryDao().updateSession(session.copy(endTime = session.startTime))
                continue
            }

            var distanceMeters = 0f
            var prevLocation: Location? = null
            var recoveredMaxSpeed = 0
            var recoveredMaxLeanLeft = 0f
            var recoveredMaxLeanRight = 0f
            var recoveredMaxCoolant = 0
            var recoveredFuelLiters = 0f

            for (r in records) {
                recoveredMaxSpeed = max(recoveredMaxSpeed, r.speed)
                recoveredMaxCoolant = max(recoveredMaxCoolant, r.coolantTemp)
                if (r.leanAngleBike < 0) {
                    recoveredMaxLeanLeft = max(recoveredMaxLeanLeft, -r.leanAngleBike)
                } else {
                    recoveredMaxLeanRight = max(recoveredMaxLeanRight, r.leanAngleBike)
                }
                recoveredFuelLiters += (r.fuelRate / 3600f) * 0.2f

                if (r.latitude != 0.0 || r.longitude != 0.0) {
                    val loc = Location("recovery").apply {
                        latitude = r.latitude
                        longitude = r.longitude
                    }
                    prevLocation?.let { distanceMeters += it.distanceTo(loc) }
                    prevLocation = loc
                }
            }

            database.telemetryDao().updateSession(
                session.copy(
                    endTime = records.last().timestamp,
                    totalDistanceGpsKm = distanceMeters / 1000f,
                    maxSpeed = recoveredMaxSpeed,
                    maxLeanLeft = recoveredMaxLeanLeft,
                    maxLeanRight = recoveredMaxLeanRight,
                    maxCoolantTemp = recoveredMaxCoolant,
                    totalFuelLiters = recoveredFuelLiters
                )
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        graceTimeoutJob?.cancel()
        obdLinkReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.w("TelemetryService", "OBD link receiver already unregistered")
            }
        }

        // Save final session data synchronously
        try {
            val database = db
            val obdManager = bluetoothOBDManager
            
            if (database != null && currentSessionId != -1L) {
                val endOdometer = (obdManager?.obdData?.value?.get("ODOMETER") ?: startOdometer.toInt()).toLong()
                
                runBlocking {
                    try {
                        val finalSession = database.telemetryDao().getSessionById(currentSessionId)?.copy(
                            endTime = System.currentTimeMillis(),
                            totalDistanceGpsKm = totalGpsDistanceMeters / 1000f,
                            totalDistanceBikeKm = if (endOdometer > startOdometer) (endOdometer - startOdometer).toFloat() else 0f,
                            maxSpeed = maxSpeed,
                            maxLeanLeft = maxLeanLeft,
                            maxLeanRight = maxLeanRight,
                            maxCoolantTemp = maxCoolantTemp,
                            totalFuelLiters = totalFuelConsumedLiters
                        )
                        finalSession?.let { 
                            database.telemetryDao().updateSession(it)
                        }
                    } catch (e: Exception) {
                        Log.e("TelemetryService", "Error updating session: ${e.message}", e)
                    }
                }
            }
            
            obdManager?.disconnect()
            orientationManager?.stop()
        } catch (e: Exception) {
            Log.e("TelemetryService", "Error in onDestroy: ${e.message}", e)
        } finally {
            serviceScope.cancel()
        }
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            channelId,
            "Motosiklet Takip Servisi",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(serviceChannel)
    }
}
