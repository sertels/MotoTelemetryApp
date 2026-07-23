package com.example.mototelemetryapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

class OrientationManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Raw roll before the mount-tilt offset is applied.
    private var rawRoll = 0f
    private var rollOffset = prefs.getFloat(KEY_ROLL_OFFSET, 0f)

    private val _leanAngle = MutableStateFlow(0f)
    val leanAngle = _leanAngle.asStateFlow()

    private val _gForce = MutableStateFlow(0f)
    val gForce = _gForce.asStateFlow()

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    // Zeroes the lean angle to the phone's current mount tilt; persists across restarts.
    fun calibrate() {
        rollOffset = rawRoll
        prefs.edit().putFloat(KEY_ROLL_OFFSET, rollOffset).apply()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)

                // Roll (Z ekseni etrafında dönüş) -> Radyanı dereceye çeviriyoruz
                // Bu değer motorun sağa/sola yatışını temsil eder.
                rawRoll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                _leanAngle.value = normalizeAngle(rawRoll - rollOffset)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                
                // Toplam ivme (G cinsinden)
                val gravity = SensorManager.GRAVITY_EARTH
                val totalAccel = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                _gForce.value = totalAccel / gravity
            }
        }
    }

    private fun normalizeAngle(angle: Float): Float {
        var a = angle
        while (a > 180f) a -= 360f
        while (a < -180f) a += 360f
        return a
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val PREFS_NAME = "orientation_prefs"
        private const val KEY_ROLL_OFFSET = "roll_offset"
    }
}
