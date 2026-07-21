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
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.mototelemetryapp.data.AppDatabase
import com.example.mototelemetryapp.data.Session
import com.example.mototelemetryapp.data.TelemetryRecord
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

class TelemetryService : Service() {

    private val channelId = "TelemetryServiceChannel"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private lateinit var bluetoothOBDManager: BluetoothOBDManager
    private lateinit var orientationManager: OrientationManager
    private lateinit var db: AppDatabase
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_content))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

        startForeground(1, notification)

        startLocationUpdates()
        orientationManager.start()
        startTelemetryTracking()

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .build()

        fusedLocationClient.requestLocationUpdates(
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
            // 1. Create a new Session
            val startTime = System.currentTimeMillis()
            val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(startTime))
            val session = Session(
                name = "Ride - $dateStr",
                startTime = startTime
            )
            currentSessionId = db.telemetryDao().insertSession(session)

            // 2. Start OBD2 Connection
            val connected = bluetoothOBDManager.connect("OBDII")
            if (connected) {
                // Initial Odometer
                startOdometer = (bluetoothOBDManager.obdData.value["ODOMETER"] ?: 0).toLong()

                while (true) {
                    val obdData = bluetoothOBDManager.obdData.value
                    val leanPhone = orientationManager.leanAngle.value
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
                        gForce = orientationManager.gForce.value,
                        fuelRate = fuelRate,
                        fuelLevel = obdData["FUEL_LEVEL"] ?: 0,
                        coolantTemp = coolant,
                        altitude = lastLocation?.altitude ?: 0.0,
                        latitude = lastLocation?.latitude ?: 0.0,
                        longitude = lastLocation?.longitude ?: 0.0
                    )
                    
                    _currentTelemetry.value = record
                    db.telemetryDao().insertRecord(record)
                    delay(200)
                }
            } else {
                Log.e("TelemetryService", "OBD2 connection failed.")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.launch {
            val endOdometer = (bluetoothOBDManager.obdData.value["ODOMETER"] ?: startOdometer.toInt()).toLong()
            val finalSession = db.telemetryDao().getSessionById(currentSessionId)?.copy(
                endTime = System.currentTimeMillis(),
                totalDistanceGpsKm = totalGpsDistanceMeters / 1000f,
                totalDistanceBikeKm = if (endOdometer > startOdometer) (endOdometer - startOdometer).toFloat() else 0f,
                maxSpeed = maxSpeed,
                maxLeanLeft = maxLeanLeft,
                maxLeanRight = maxLeanRight,
                maxCoolantTemp = maxCoolantTemp,
                totalFuelLiters = totalFuelConsumedLiters
            )
            finalSession?.let { db.telemetryDao().updateSession(it) }
            
            bluetoothOBDManager.disconnect()
            orientationManager.stop()
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
