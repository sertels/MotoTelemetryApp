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
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

// Fixes below which GPS jitter (rather than real movement) dominates distanceTo() deltas.
private const val MAX_GPS_ACCURACY_METERS = 20f
private const val MIN_MOVING_SPEED_MPS = 1f // ~3.6 km/h

class TelemetryService : Service() {

    private val channelId = "TelemetryServiceChannel"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Either the real Bluetooth adapter or the debug simulator - chosen once in onCreate() so
    // everything downstream is unaware of which one it got.
    private var bluetoothOBDManager: ObdSource? = null
    private var orientationManager: OrientationSource? = null
    private var db: AppDatabase? = null
    private var locationSource: LocationSource? = null
    
    private var lastLocation: Location? = null
    private var currentSessionId: Long = -1
    private var totalGpsDistanceMeters: Float = 0f
    private var startOdometer: Long = 0
    private var maxSpeed: Int = 0
    // StateFlows (not plain vars) so the lean gauge can show "max this ride" live rather than
    // only after the session is saved. Reset at the top of every startTelemetryTracking() call -
    // this service instance can be started and stopped multiple times, and these previously never
    // reset between rides.
    private val _maxLeanLeft = MutableStateFlow(0f)
    val maxLeanLeft = _maxLeanLeft.asStateFlow()
    private val _maxLeanRight = MutableStateFlow(0f)
    val maxLeanRight = _maxLeanRight.asStateFlow()
    // Which source actually produced the current max on each side, since it can be either one -
    // see the phone fallback in the telemetry loop below. Lets the UI color-code Max L/Max R the
    // same way it already does for live OBD-sourced readouts (e.g. RPM), so it's never ambiguous
    // whether a given max came from the bike's own sensor or the phone's.
    private val _maxLeanLeftSource = MutableStateFlow(LeanSource.BIKE)
    val maxLeanLeftSource = _maxLeanLeftSource.asStateFlow()
    private val _maxLeanRightSource = MutableStateFlow(LeanSource.BIKE)
    val maxLeanRightSource = _maxLeanRightSource.asStateFlow()
    private var maxCoolantTemp: Int = 0
    // Peak magnitude reached this ride in each axis, independent of each other (a hard stop and a
    // hard corner are different kinds of "riding hard" and shouldn't be collapsed into one
    // combined-magnitude number). StateFlows for the same live-on-Panel reason as maxLeanLeft/Right.
    private val _maxGForceLat = MutableStateFlow(0f)
    val maxGForceLat = _maxGForceLat.asStateFlow()
    private val _maxGForceLon = MutableStateFlow(0f)
    val maxGForceLon = _maxGForceLon.asStateFlow()
    private var totalFuelConsumedLiters: Float = 0f
    // A StateFlow (not a plain read-once Boolean) so DashboardViewModel can observe it directly
    // instead of racing: onStartCommand() and bindService()'s onServiceConnected() are both
    // dispatched asynchronously with no ordering guarantee between them, so a one-time read at
    // bind time could catch this still false immediately after Start was pressed.
    private val _trackingStarted = MutableStateFlow(false)
    val isTrackingActive = _trackingStarted.asStateFlow()

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
        bluetoothOBDManager?.getPairedDeviceEntries() ?: emptyList()

    // Lets the UI label the status badge as synthetic, so no screenshot is ambiguous about
    // whether it came from a real bike.
    val obdSimulated get() = bluetoothOBDManager?.isSimulated == true

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

    // Per-PID last-response-matched-expected flag, so the Bike Info sensor map can show which
    // "confirmed" PIDs are actually answering right now instead of just listing them statically.
    val obdPidStatus get() = bluetoothOBDManager?.pidStatus

    suspend fun clearObdDtcs(): Boolean = bluetoothOBDManager?.clearDtcs() ?: false

    suspend fun runObdSweep() {
        bluetoothOBDManager?.sweepHeadersAndDids()
    }

    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.init(this)
        createNotificationChannel()

        // Initialize components
        // One switch drives both halves: simulating the bike's PIDs but reading real (dead, on an
        // emulator) phone sensors would give a dashboard cornering at 40 degrees while both G bars
        // sit at zero. See SimulatedRide, which keeps the two consistent.
        val simulate = isRideSimulationEnabled(this)
        if (simulate) {
            DiagnosticLog.w("TelemetryService", "Ride simulation enabled - telemetry below is synthetic.")
        }
        bluetoothOBDManager = if (simulate) {
            SimulatedObdSource(serviceScope)
        } else {
            BluetoothOBDManager(this, serviceScope)
        }
        orientationManager = if (simulate) {
            SimulatedOrientationSource(serviceScope)
        } else {
            OrientationManager(this)
        }
        locationSource = if (simulate) {
            SimulatedLocationSource(serviceScope)
        } else {
            FusedLocationSource(this, mainLooper)
        }
        db = AppDatabase.getDatabase(this)

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
        if (!_trackingStarted.value) {
            _trackingStarted.value = true
            startLocationUpdates()
            orientationManager?.start()
            startTelemetryTracking()
        }

        return START_STICKY
    }

    private fun startLocationUpdates() {
        locationSource?.start { newLocation ->
            // Without this, ordinary GPS jitter while stationary (e.g. sitting at a red
            // light during the OBD grace period) keeps accruing phantom distance, since
            // distanceTo() was summed for every fix with no accuracy/motion floor.
            val isAccurateEnough = newLocation.accuracy <= MAX_GPS_ACCURACY_METERS
            val isMoving = !newLocation.hasSpeed() || newLocation.speed >= MIN_MOVING_SPEED_MPS

            lastLocation?.let {
                if (isAccurateEnough && isMoving) {
                    totalGpsDistanceMeters += it.distanceTo(newLocation)
                }
            }

            lastLocation = newLocation
        }
    }

    private fun startTelemetryTracking() {
        serviceScope.launch {
            try {
                val database = db ?: run {
                    DiagnosticLog.e("TelemetryService", "Database not initialized")
                    return@launch
                }
                
                val obdManager = bluetoothOBDManager ?: run {
                    DiagnosticLog.e("TelemetryService", "BluetoothOBDManager not initialized")
                    return@launch
                }
                
                val orientManager = orientationManager ?: run {
                    DiagnosticLog.e("TelemetryService", "OrientationManager not initialized")
                    return@launch
                }

                // Finalize any session left with endTime == null by a previous run that
                // was killed before onDestroy() could run (e.g. backgrounded + OS/battery kill).
                recoverOrphanedSessions(database)

                // This service instance can be started and stopped more than once (Start/Stop from
                // the UI without killing the process), so the aggregates from a previous ride must
                // not leak into this one.
                totalGpsDistanceMeters = 0f
                maxSpeed = 0
                _maxLeanLeft.value = 0f
                _maxLeanRight.value = 0f
                _maxLeanLeftSource.value = LeanSource.BIKE
                _maxLeanRightSource.value = LeanSource.BIKE
                maxCoolantTemp = 0
                _maxGForceLat.value = 0f
                _maxGForceLon.value = 0f
                totalFuelConsumedLiters = 0f
                lastLocation = null

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
                    DiagnosticLog.e("TelemetryService", "OBD2 connection failed; continuing with phone sensors only.")
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
                        // LEAN_BIKE (OBD DID 22D10D) reads back exactly 0 both when the bike is
                        // genuinely upright and when that DID just isn't answering - there's no
                        // way to tell those apart from the value alone (confirmed via a real
                        // ride, 2026-07-31: this DID silently failed for the whole ride despite
                        // OBD being connected and other PIDs working fine, so Max L/Max R on
                        // Panel just showed 0/0 all ride). Fall back to the phone's own lean
                        // angle whenever the bike reads exactly 0, rather than record nothing.
                        val leanBikeAvailable = leanBike != 0f
                        val leanForMax = if (leanBikeAvailable) leanBike else leanPhone
                        val leanForMaxSource = if (leanBikeAvailable) LeanSource.BIKE else LeanSource.PHONE
                        if (leanForMax < 0) {
                            if (-leanForMax > _maxLeanLeft.value) {
                                _maxLeanLeft.value = -leanForMax
                                _maxLeanLeftSource.value = leanForMaxSource
                            }
                        } else {
                            if (leanForMax > _maxLeanRight.value) {
                                _maxLeanRight.value = leanForMax
                                _maxLeanRightSource.value = leanForMaxSource
                            }
                        }
                        _maxGForceLat.value = max(_maxGForceLat.value, abs(orientManager.gForceLat.value))
                        _maxGForceLon.value = max(_maxGForceLon.value, abs(orientManager.gForceLon.value))

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
                            gForceLat = orientManager.gForceLat.value,
                            gForceLon = orientManager.gForceLon.value,
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
                        DiagnosticLog.e("TelemetryService", "Error in telemetry loop: ${e.message}", e)
                        delay(1000.milliseconds) // Wait before retrying
                    }
                }
            } catch (e: Exception) {
                DiagnosticLog.e("TelemetryService", "Fatal error in startTelemetryTracking: ${e.message}", e)
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
            var recoveredMaxGForceLat = 0f
            var recoveredMaxGForceLon = 0f
            var recoveredFuelLiters = 0f

            for (r in records) {
                recoveredMaxSpeed = max(recoveredMaxSpeed, r.speed)
                recoveredMaxCoolant = max(recoveredMaxCoolant, r.coolantTemp)
                if (r.leanAngleBike < 0) {
                    recoveredMaxLeanLeft = max(recoveredMaxLeanLeft, -r.leanAngleBike)
                } else {
                    recoveredMaxLeanRight = max(recoveredMaxLeanRight, r.leanAngleBike)
                }
                recoveredMaxGForceLat = max(recoveredMaxGForceLat, abs(r.gForceLat))
                recoveredMaxGForceLon = max(recoveredMaxGForceLon, abs(r.gForceLon))
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
                    maxGForceLat = recoveredMaxGForceLat,
                    maxGForceLon = recoveredMaxGForceLon,
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
                DiagnosticLog.w("TelemetryService", "OBD link receiver already unregistered")
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
                            maxLeanLeft = _maxLeanLeft.value,
                            maxLeanRight = _maxLeanRight.value,
                            maxCoolantTemp = maxCoolantTemp,
                            maxGForceLat = _maxGForceLat.value,
                            maxGForceLon = _maxGForceLon.value,
                            totalFuelLiters = totalFuelConsumedLiters
                        )
                        finalSession?.let { 
                            database.telemetryDao().updateSession(it)
                        }
                    } catch (e: Exception) {
                        DiagnosticLog.e("TelemetryService", "Error updating session: ${e.message}", e)
                    }
                }
            }
            
            obdManager?.disconnect()
            orientationManager?.stop()
            // The fused client's callback outlived the service before this - requestLocationUpdates
            // was never paired with a removeLocationUpdates anywhere.
            locationSource?.stop()
        } catch (e: Exception) {
            DiagnosticLog.e("TelemetryService", "Error in onDestroy: ${e.message}", e)
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
