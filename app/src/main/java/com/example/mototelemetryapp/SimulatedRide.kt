package com.example.mototelemetryapp

import kotlin.math.tan

// One scripted ride, shared by every simulated source.
//
// Deliberately a single shared clock rather than a script per source: the OBD simulator supplies
// lean and speed while the orientation simulator supplies G-force, and those have to agree with
// each other or the dashboard shows a bike cranked over at 40 degrees pulling no lateral G. Both
// read the same frame at the same instant, so the numbers stay physically consistent.
object SimulatedRide {

    data class Frame(
        val speedKmh: Float,
        val throttlePct: Float,
        val brakeFrontPct: Float,
        val brakeRearPct: Float,
        val leanDeg: Float
    )

    // Set on first access, i.e. whenever the first simulated source starts.
    private val startedAtMs = System.currentTimeMillis()

    fun elapsedSeconds(): Float = (System.currentTimeMillis() - startedAtMs) / 1000f

    fun frameNow(): Frame = frameAt(elapsedSeconds())

    fun frameAt(elapsedSec: Float): Frame = interpolate(elapsedSec.mod(LOOP_SECONDS))

    // Lateral G implied by the lean angle. A bike leans until the resultant of gravity and
    // cornering force runs through the tyre contact patches, which puts lateral acceleration at
    // g * tan(lean) - so a 40 degree lean is about 0.84g. Derived rather than scripted separately
    // precisely so the G bar can never disagree with the lean gauge.
    fun lateralG(leanDeg: Float): Float = tan(Math.toRadians(leanDeg.toDouble())).toFloat()

    // Yaw rate of a bike in a coordinated turn: the same lateral acceleration that sets the lean
    // angle, divided by forward speed. Lets the simulated GPS track be drawn by the lean the
    // gauges are already showing, instead of a route scripted separately that could contradict it.
    // Caller must keep speed away from zero - the relation diverges as the bike stops.
    fun turnRateRadPerSec(leanDeg: Float, speedMps: Float): Double =
        (EARTH_G * lateralG(leanDeg)) / speedMps.toDouble()

    // Longitudinal G by differentiating scripted speed. Central difference over a small window,
    // which smooths the corners where the piecewise-linear script changes gradient abruptly.
    fun longitudinalG(elapsedSec: Float): Float {
        val dt = 0.35f
        val vAhead = frameAt(elapsedSec + dt).speedKmh / 3.6f // m/s
        val vBehind = frameAt(elapsedSec - dt).speedKmh / 3.6f
        val accel = (vAhead - vBehind) / (2f * dt)
        return accel / EARTH_G
    }

    private fun interpolate(t: Float): Frame {
        val next = SCRIPT.indexOfFirst { it.first > t }.let { if (it == -1) SCRIPT.lastIndex else it }
        val prev = (next - 1).coerceAtLeast(0)
        val (fromT, from) = SCRIPT[prev]
        val (toT, to) = SCRIPT[next]
        val span = toT - fromT
        if (span <= 0f) return from
        val f = ((t - fromT) / span).coerceIn(0f, 1f)
        return Frame(
            speedKmh = lerp(from.speedKmh, to.speedKmh, f),
            throttlePct = lerp(from.throttlePct, to.throttlePct, f),
            brakeFrontPct = lerp(from.brakeFrontPct, to.brakeFrontPct, f),
            brakeRearPct = lerp(from.brakeRearPct, to.brakeRearPct, f),
            leanDeg = lerp(from.leanDeg, to.leanDeg, f)
        )
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    const val LOOP_SECONDS = 60f
    private const val EARTH_G = 9.81f

    // One minute of riding: pull away, work up through the gears, brake into a left-hander, pick
    // it up, then a faster right, then back down to a stop. Interpolated between points, so keep
    // them coarse - this is meant to look plausible, not to be a real telemetry trace.
    private val SCRIPT: List<Pair<Float, Frame>> = listOf(
        0f to Frame(0f, 0f, 0f, 0f, 0f),
        4f to Frame(0f, 0f, 0f, 0f, 0f),
        10f to Frame(60f, 65f, 0f, 0f, 0f),
        16f to Frame(95f, 55f, 0f, 0f, -5f),
        22f to Frame(70f, 10f, 35f, 20f, -32f),
        28f to Frame(55f, 25f, 0f, 0f, -42f),
        34f to Frame(80f, 60f, 0f, 0f, -10f),
        40f to Frame(104f, 45f, 0f, 0f, 8f),
        46f to Frame(65f, 5f, 45f, 25f, 30f),
        52f to Frame(50f, 30f, 0f, 0f, 38f),
        58f to Frame(20f, 0f, 30f, 15f, 5f),
        60f to Frame(0f, 0f, 0f, 0f, 0f)
    )
}
