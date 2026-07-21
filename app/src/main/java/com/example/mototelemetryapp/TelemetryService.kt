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
import com.example.mototelemetryapp.data.TelemetryRecord
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TelemetryService : Service() {

    private val CHANNEL_ID = "TelemetryServiceChannel"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private lateinit var bluetoothOBDManager: BluetoothOBDManager
    private lateinit var orientationManager: OrientationManager
    private lateinit var db: AppDatabase
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    
    private var lastLocation: Location? = null

    // Canlı veriyi UI'ya aktarmak için Flow
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
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
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
                    lastLocation = locationResult.lastLocation
                }
            },
            mainLooper
        )
    }

    private fun startTelemetryTracking() {
        serviceScope.launch {
            // OBD2 Bağlantısını başlat
            val connected = bluetoothOBDManager.connect("OBDII")
            if (connected) {
                // Kayıt Döngüsü (Her 200ms'de bir - 5Hz)
                while (true) {
                    val obdData = bluetoothOBDManager.obdData.value
                    val lean = orientationManager.leanAngle.value
                    val gForce = orientationManager.gForce.value
                    
                    val record = TelemetryRecord(
                        timestamp = System.currentTimeMillis(),
                        speed = obdData["SPEED"] ?: 0,
                        rpm = obdData["RPM"] ?: 0,
                        gear = obdData["GEAR"] ?: 0,
                        throttle = obdData["THROTTLE"] ?: 0,
                        brakeFront = obdData["BRAKE_FRONT"] ?: 0,
                        brakeRear = obdData["BRAKE_REAR"] ?: 0,
                        leanAnglePhone = lean,
                        leanAngleBike = (obdData["LEAN_BIKE"] ?: 0).toFloat(),
                        gForce = gForce,
                        latitude = lastLocation?.latitude ?: 0.0,
                        longitude = lastLocation?.longitude ?: 0.0
                    )
                    
                    _currentTelemetry.value = record
                    db.telemetryDao().insertRecord(record)
                    Log.d("TelemetryService", "Kayıt: Hız=${record.speed}, Vites=${record.gear}, Yatış(P)=${record.leanAnglePhone}, Yatış(B)=${record.leanAngleBike}")
                    
                    delay(200)
                }
            } else {
                Log.e("TelemetryService", "OBD2 bağlantısı kurulamadı.")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothOBDManager.disconnect()
        orientationManager.stop()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Motosiklet Takip Servisi",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
