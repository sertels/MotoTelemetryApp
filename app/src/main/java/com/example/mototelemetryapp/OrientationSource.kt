package com.example.mototelemetryapp

import kotlinx.coroutines.flow.StateFlow

// Everything TelemetryService needs from the phone's motion sensors, so the real
// OrientationManager and the simulator are interchangeable. Mirrors ObdSource - the two together
// cover both halves of a telemetry record, which is what lets a ride be replayed end to end with
// no bike and no sensors.
interface OrientationSource {

    // Bike lean derived from the phone's roll, in degrees, already zeroed to the mount tilt.
    val leanAngle: StateFlow<Float>

    val gForceLat: StateFlow<Float>
    val gForceLon: StateFlow<Float>

    fun start()

    fun stop()

    // Zeroes the lean angle to the phone's current mount tilt.
    fun calibrate()
}
