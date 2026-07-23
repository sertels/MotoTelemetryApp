package com.example.mototelemetryapp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // Initialize components
        bluetoothOBDManager = BluetoothOBDManager(this)
        orientationManager = OrientationManager(this)
        db = AppDatabase.getDatabase(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
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

        startLocationUpdates()
        orientationManager?.start()
        startTelemetryTracking()

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
                
                // 1. Create a new Session
                val startTime = System.currentTimeMillis()
                val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(startTime))
                val session = Session(
                    name = "Ride - $dateStr",
                    startTime = startTime
                )
                currentSessionId = database.telemetryDao().insertSession(session)

                // 2. Start OBD2 Connection (best-effort; phone sensors keep streaming even if this fails)
                val connected = obdManager.connect("OBDII")
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

    override fun onDestroy() {
        super.onDestroy()
        
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
