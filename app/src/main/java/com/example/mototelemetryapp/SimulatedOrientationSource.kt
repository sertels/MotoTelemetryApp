package com.example.mototelemetryapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

// Stand-in for the phone's motion sensors, driven by the same SimulatedRide as the OBD simulator.
//
// An emulator reports a dead accelerometer, so lean and both G bars sit at zero however lively the
// rest of the dashboard is. This fills them from the shared ride script, which is also why lateral
// G is derived from the very lean angle the OBD side is reporting rather than scripted separately:
// the two cannot drift out of agreement.
//
// As with SimulatedObdSource, none of the real sensor-fusion code (rotation-matrix remapping,
// gimbal-lock rejection, mount calibration) runs while this is active.
class SimulatedOrientationSource(private val loopScope: CoroutineScope) : OrientationSource {

    private val _leanAngle = MutableStateFlow(0f)
    override val leanAngle = _leanAngle.asStateFlow()

    private val _gForceLat = MutableStateFlow(0f)
    override val gForceLat = _gForceLat.asStateFlow()

    private val _gForceLon = MutableStateFlow(0f)
    override val gForceLon = _gForceLon.asStateFlow()

    private var loopJob: Job? = null

    // Applied to the reported lean the way the real mount-tilt offset is, so tapping Calibrate
    // visibly rezeroes the gauge instead of appearing to do nothing.
    private var leanOffset = 0f

    override fun start() {
        loopJob?.cancel()
        loopJob = loopScope.launch {
            while (true) {
                val elapsed = SimulatedRide.elapsedSeconds()
                val frame = SimulatedRide.frameAt(elapsed)
                _leanAngle.value = frame.leanDeg - leanOffset
                _gForceLat.value = SimulatedRide.lateralG(frame.leanDeg)
                _gForceLon.value = SimulatedRide.longitudinalG(elapsed)
                delay(TICK_MS.milliseconds)
            }
        }
    }

    override fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    override fun calibrate() {
        leanOffset = SimulatedRide.frameNow().leanDeg
    }

    private companion object {
        // The real sensors are registered at SENSOR_DELAY_UI (~60ms); close enough that the
        // gauges animate at a comparable rate.
        const val TICK_MS = 60L
    }
}
