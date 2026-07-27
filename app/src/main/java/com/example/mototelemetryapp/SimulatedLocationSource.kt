package com.example.mototelemetryapp

import android.location.Location
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds

// Stand-in for GPS, riding the same SimulatedRide as everything else.
//
// An emulator parks you at a fixed default fix, so recorded distance, the route on the History
// map, and every distance-derived stat stay at zero however lively the gauges look. This walks a
// position around instead.
//
// The heading is turned by the bike's own lean rather than by a scripted route: a bike in a
// coordinated turn yaws at g*tan(lean)/v radians per second, so the same lean angle driving the
// gauge and the lateral-G bar also draws the corners in the track. Speed comes from the ride
// script. That means the GPS distance and the OBD speedometer agree, which they would not if the
// route were scripted independently.
class SimulatedLocationSource(private val loopScope: CoroutineScope) : LocationSource {

    private var loopJob: Job? = null

    private var latitude = START_LATITUDE
    private var longitude = START_LONGITUDE
    private var headingRad = 0.0

    override fun start(onLocation: (Location) -> Unit) {
        loopJob?.cancel()
        loopJob = loopScope.launch {
            while (true) {
                val frame = SimulatedRide.frameNow()
                val speedMps = frame.speedKmh / 3.6f

                advance(speedMps, frame.leanDeg, TICK_MS / 1000.0)
                onLocation(buildLocation(speedMps))

                delay(TICK_MS.milliseconds)
            }
        }
    }

    override fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    private fun advance(speedMps: Float, leanDeg: Float, dtSec: Double) {
        // Below a walking pace the coordinated-turn relation divides by ~zero and the heading
        // would spin wildly, so a stationary bike simply holds its heading.
        if (speedMps > MIN_TURN_SPEED_MPS) {
            headingRad += SimulatedRide.turnRateRadPerSec(leanDeg, speedMps) * dtSec
        }

        val distanceM = speedMps * dtSec
        // Flat-earth step: over the tens of metres covered per tick this is far below GPS noise,
        // and it avoids dragging in a geodesy library for a debug simulator.
        val northM = distanceM * cos(headingRad)
        val eastM = distanceM * sin(headingRad)

        latitude += northM / METERS_PER_DEGREE_LAT
        longitude += eastM / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(latitude)))
    }

    private fun buildLocation(speedMps: Float): Location =
        Location(PROVIDER).apply {
            latitude = this@SimulatedLocationSource.latitude
            longitude = this@SimulatedLocationSource.longitude
            altitude = START_ALTITUDE_M
            speed = speedMps
            bearing = Math.toDegrees(headingRad).toFloat().mod(360f)
            // Comfortably inside the service's MAX_GPS_ACCURACY_METERS filter, or none of these
            // fixes would count toward distance.
            accuracy = SIMULATED_ACCURACY_M
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

    private companion object {
        const val PROVIDER = "simulated"
        const val TICK_MS = 1000L // matches the real LocationRequest interval

        // Somewhere unremarkable outside Istanbul - far enough from the emulator's own default
        // fix that a simulated track is never confused for a real one.
        const val START_LATITUDE = 41.0450
        const val START_LONGITUDE = 29.0330
        const val START_ALTITUDE_M = 120.0

        const val METERS_PER_DEGREE_LAT = 111_320.0
        const val MIN_TURN_SPEED_MPS = 1.5f
        const val SIMULATED_ACCURACY_M = 4f
    }
}
