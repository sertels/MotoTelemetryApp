package com.example.mototelemetryapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

class OrientationManager(context: Context) : SensorEventListener, OrientationSource {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    // Linear acceleration has gravity already subtracted out, unlike TYPE_ACCELEROMETER - needed
    // so lateral/longitudinal G reads ~0 at a standstill instead of ~1g from gravity alone.
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // getOrientation()'s roll/pitch are defined relative to the phone's natural (portrait)
    // orientation, not however it's physically mounted - on a landscape bike mount, roll and
    // pitch swap meaning unless the rotation matrix is remapped to match. Read from
    // WindowManager (not Context.getDisplay(), which throws on a plain Service context) so this
    // self-adapts to whatever orientation the phone is actually mounted in.
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Raw roll before the mount-tilt offset is applied.
    private var rawRoll = 0f
    private var rollOffset = prefs.getFloat(KEY_ROLL_OFFSET, 0f)
    // event.timestamp (nanoseconds) of the last accepted roll sample, so the plausibility gate
    // below can work in real degrees/sec instead of assuming a fixed sensor interval.
    private var lastRollTimestampNs = 0L

    // Exponentially-smoothed G readings - see the low-pass comment in onSensorChanged.
    private var smoothedGForceLat = 0f
    private var smoothedGForceLon = 0f

    private val _leanAngle = MutableStateFlow(0f)
    override val leanAngle = _leanAngle.asStateFlow()

    private val _gForceLat = MutableStateFlow(0f)
    override val gForceLat = _gForceLat.asStateFlow()

    private val _gForceLon = MutableStateFlow(0f)
    override val gForceLon = _gForceLon.asStateFlow()

    override fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun stop() {
        sensorManager.unregisterListener(this)
    }

    // Zeroes the lean angle to the phone's current mount tilt; persists across restarts.
    override fun calibrate() {
        rollOffset = rawRoll
        prefs.edit().putFloat(KEY_ROLL_OFFSET, rollOffset).apply()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                val remappedMatrix = FloatArray(9)
                @Suppress("DEPRECATION")
                when (windowManager.defaultDisplay.rotation) {
                    Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                        rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remappedMatrix
                    )
                    Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                        rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remappedMatrix
                    )
                    Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                        rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remappedMatrix
                    )
                    else -> rotationMatrix.copyInto(remappedMatrix)
                }

                val orientation = FloatArray(3)
                SensorManager.getOrientation(remappedMatrix, orientation)

                val pitchDeg = Math.toDegrees(orientation[1].toDouble())

                // Roll only represents bike lean while the phone sits roughly upright.
                // Near pitch = +/-90 deg (phone lying flat, e.g. on a car seat) the
                // azimuth/roll Euler decomposition hits a gimbal-lock singularity and
                // roll swings wildly for tiny movements, so readings in that zone are
                // dropped rather than recorded as if they were a real lean angle.
                if (abs(pitchDeg) < GIMBAL_LOCK_PITCH_THRESHOLD_DEG) {
                    val candidateRoll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                    val elapsedSeconds = if (lastRollTimestampNs == 0L) {
                        0f
                    } else {
                        (event.timestamp - lastRollTimestampNs) / 1_000_000_000f
                    }
                    lastRollTimestampNs = event.timestamp

                    // A phone bouncing on its bike mount produces single-sample spikes no real
                    // lean change could match (seen hitting +-170deg mid-ride on ordinary street
                    // riding, 2026-07-31) - reject a sample outright if it implies an angular
                    // rate beyond anything riding could actually produce, rather than record (or
                    // smooth in) the glitch. First sample after (re)start always passes since
                    // there's no prior timestamp to measure a rate against.
                    val impliedRateDegPerSec = if (elapsedSeconds > 0f) {
                        abs(normalizeAngle(candidateRoll - rawRoll)) / elapsedSeconds
                    } else {
                        0f
                    }
                    if (impliedRateDegPerSec <= MAX_ROLL_RATE_DEG_PER_SEC) {
                        val candidateLean = normalizeAngle(candidateRoll - rollOffset)
                        // The rate gate above only catches a glitch that jumps in a single
                        // sample; one spread across 2-3 samples (each under the rate threshold on
                        // its own) still sails through it and shows up as a spike to ~170deg on
                        // the Analysis lean chart (seen on a real ride, 2026-08-02) - a value no
                        // street motorcycle lean ever approaches. Gate on the absolute result too,
                        // with generous headroom above any real achievable lean.
                        if (abs(candidateLean) <= MAX_PLAUSIBLE_LEAN_DEG) {
                            rawRoll = candidateRoll
                            _leanAngle.value = candidateLean
                        }
                    }
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val x = event.values[0]
                val y = event.values[1]

                // Same screen-rotation correction as the lean angle above: the accelerometer's
                // axes are fixed to the device, not the screen, so a landscape-mounted phone
                // needs its X/Y swapped to match the bike's lateral/forward axes. Assumes the
                // phone's natural (portrait) top edge points toward the front of the bike.
                @Suppress("DEPRECATION")
                val (bikeX, bikeY) = when (windowManager.defaultDisplay.rotation) {
                    Surface.ROTATION_90 -> y to -x
                    Surface.ROTATION_180 -> -x to -y
                    Surface.ROTATION_270 -> -y to x
                    else -> x to y
                }

                val gravity = SensorManager.GRAVITY_EARTH
                val rawGForceLat = bikeX / gravity
                val rawGForceLon = bikeY / gravity

                // A phone on a vibrating bike mount reports single-sample G spikes well past
                // anything a real corner or stop produces (seen hitting 2.5g lateral / 4.9g
                // longitudinal mid-ride on ordinary street riding, 2026-07-31) - a light
                // exponential low-pass smooths that mount vibration out while still tracking
                // genuine, sustained accelerations at riding speed.
                smoothedGForceLat += G_FORCE_SMOOTHING_ALPHA * (rawGForceLat - smoothedGForceLat)
                smoothedGForceLon += G_FORCE_SMOOTHING_ALPHA * (rawGForceLon - smoothedGForceLon)
                _gForceLat.value = smoothedGForceLat
                _gForceLon.value = smoothedGForceLon
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
        private const val GIMBAL_LOCK_PITCH_THRESHOLD_DEG = 65.0
        // Generous headroom above any real lean-change rate a motorcycle could produce, so this
        // only ever catches sensor glitches, never a genuinely fast flick into a corner.
        private const val MAX_ROLL_RATE_DEG_PER_SEC = 400f
        // MotoGP-level track lean tops out around 65-69deg; this is a street bike app, so 70deg
        // is already generous headroom above anything a real ride could produce.
        private const val MAX_PLAUSIBLE_LEAN_DEG = 70f
        private const val G_FORCE_SMOOTHING_ALPHA = 0.25f
    }
}
