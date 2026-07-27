package com.example.mototelemetryapp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

// A stand-in for a real ELM327 adapter, reading its values from the shared SimulatedRide.
//
// This fakes the *data*, not the transport: it produces the same PID map pollOnce() would, at the
// same cadence, so every consumer above it (TelemetryService, the ViewModel, every screen) runs
// its normal code path. Nothing here touches Bluetooth, which is what makes it work on an
// emulator - but it also means the real connect/parse/sweep logic is NOT under test when this is
// active. It exists to exercise the UI against moving, plausible values.
class SimulatedObdSource(private val dataLoopScope: CoroutineScope) : ObdSource {

    private val tag = "SimulatedObdSource"

    private val _isConnected = MutableStateFlow(false)
    override val isConnected = _isConnected.asStateFlow()

    private val _obdData = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val obdData = _obdData.asStateFlow()

    private val _sweepRunning = MutableStateFlow(false)
    override val sweepRunning = _sweepRunning.asStateFlow()

    private val _sweepResults = MutableStateFlow<List<ObdSweepEntry>>(emptyList())
    override val sweepResults = _sweepResults.asStateFlow()

    private val _sweepProgress = MutableStateFlow(0 to 0)
    override val sweepProgress = _sweepProgress.asStateFlow()

    private val _milOn = MutableStateFlow(false)
    override val milOn = _milOn.asStateFlow()

    private val _dtcCodes = MutableStateFlow<List<String>>(emptyList())
    override val dtcCodes = _dtcCodes.asStateFlow()

    override val isSimulated = true

    private var dataLoopJob: Job? = null

    // Cleared DTCs stay cleared until the next connect, so the Clear button's effect is visible
    // rather than being immediately undone by the script re-raising the fault.
    private var dtcsClearedThisRun = false

    private var odometerKm = START_ODOMETER_KM
    private var fuelLevelPct = START_FUEL_LEVEL_PCT

    override fun getPairedDeviceEntries(): List<Pair<String, String>> =
        listOf(SIMULATED_DEVICE_NAME to SIMULATED_DEVICE_ADDRESS)

    override fun getPreferredDeviceAddress(): String = SIMULATED_DEVICE_ADDRESS

    override suspend fun connectToDevice(address: String): Boolean = connect()

    override suspend fun connect(): Boolean {
        // A real adapter takes a moment to answer ATZ and negotiate a protocol; keeping a short
        // delay here means the UI's connecting state gets exercised instead of snapping to
        // connected instantly.
        delay(CONNECT_DELAY_MS.milliseconds)
        dtcsClearedThisRun = false
        _isConnected.value = true
        dataLoopJob?.cancel()
        dataLoopJob = dataLoopScope.launch { runRideLoop() }
        Log.d(tag, "Simulated OBD connected")
        return true
    }

    override fun disconnect() {
        _isConnected.value = false
        dataLoopJob?.cancel()
        dataLoopJob = null
        _milOn.value = false
        _dtcCodes.value = emptyList()
        _obdData.value = emptyMap()
        Log.d(tag, "Simulated OBD disconnected")
    }

    override suspend fun clearDtcs(): Boolean {
        if (!_isConnected.value) return false
        dtcsClearedThisRun = true
        _milOn.value = false
        _dtcCodes.value = emptyList()
        return true
    }

    // Mimics the shape of a real sweep - same progress reporting, same cadence - answering with
    // plausible responses on the DIDs this bike is actually known to expose and NO DATA elsewhere,
    // so the sweep dialog can be exercised without a bike attached.
    override suspend fun sweepHeadersAndDids(headers: List<String>, dids: List<String>) {
        if (!_isConnected.value) return
        _sweepRunning.value = true
        _sweepResults.value = emptyList()
        val total = headers.size * dids.size
        var completed = 0
        _sweepProgress.value = 0 to total
        try {
            for (header in headers) {
                for (did in dids) {
                    delay(SWEEP_STEP_MS.milliseconds)
                    _sweepResults.value = _sweepResults.value + ObdSweepEntry(header, did, sweepResponseFor(header, did))
                    completed++
                    _sweepProgress.value = completed to total
                }
            }
        } finally {
            _sweepRunning.value = false
            _sweepProgress.value = 0 to 0
        }
    }

    private fun sweepResponseFor(header: String, did: String): String {
        val known = BluetoothOBDManager.CONFIRMED_PID_MAP.any {
            it.header == header && it.command.equals("22$did", ignoreCase = true)
        }
        if (!known) return "NO DATA"
        val payload = _obdData.value.values.firstOrNull() ?: 0
        return "62 ${did.chunked(2).joinToString(" ")} ${(payload and 0xFF).toString(16).uppercase().padStart(2, '0')}"
    }

    private suspend fun runRideLoop() {
        val startedAt = System.currentTimeMillis()
        while (_isConnected.value) {
            val connectedForSec = (System.currentTimeMillis() - startedAt) / 1000f
            val frame = SimulatedRide.frameNow()

            // Distance/fuel integrate over real elapsed time so the odometer and fuel gauge drift
            // the way they would on a ride, rather than being pinned to the loop position.
            val speedKmh = frame.speedKmh
            odometerKm += speedKmh * (TICK_MS / 3_600_000f)
            val fuelRateLh = fuelRateFor(speedKmh, frame.throttlePct)
            fuelLevelPct -= (fuelRateLh * (TICK_MS / 3_600_000f)) / TANK_LITERS * 100f
            if (fuelLevelPct < 5f) fuelLevelPct = START_FUEL_LEVEL_PCT

            val gear = gearFor(speedKmh)

            _obdData.value = mapOf(
                "RPM" to rpmFor(speedKmh, gear, frame.throttlePct),
                "SPEED" to speedKmh.roundToInt(),
                "GEAR" to gear,
                "THROTTLE" to frame.throttlePct.roundToInt(),
                "BRAKE_FRONT" to frame.brakeFrontPct.roundToInt(),
                "BRAKE_REAR" to frame.brakeRearPct.roundToInt(),
                "LEAN_BIKE" to frame.leanDeg.roundToInt(),
                "COOLANT" to coolantFor(connectedForSec),
                "ODOMETER" to odometerKm.roundToInt(),
                "FUEL_RATE" to (fuelRateLh * 100).roundToInt(), // scaled x100, as pollOnce() does
                "FUEL_LEVEL" to fuelLevelPct.roundToInt()
            )

            // Raise a fault partway through so the check-engine badge and the Clear DTCs flow are
            // reachable without a real ECU - unless the user has already cleared it this run.
            if (!dtcsClearedThisRun && connectedForSec > MIL_ONSET_SECONDS && !_milOn.value) {
                _milOn.value = true
                _dtcCodes.value = SIMULATED_DTCS
            }

            delay(TICK_MS.milliseconds)
        }
    }

    // Neutral below walking pace, then roughly the shift points of a 900cc twin.
    private fun gearFor(speedKmh: Float): Int = when {
        speedKmh < 3f -> 0
        speedKmh < 25f -> 1
        speedKmh < 45f -> 2
        speedKmh < 65f -> 3
        speedKmh < 90f -> 4
        speedKmh < 120f -> 5
        else -> 6
    }

    private fun rpmFor(speedKmh: Float, gear: Int, throttlePct: Float): Int {
        if (gear == 0) return (IDLE_RPM + throttlePct * 30f).roundToInt()
        val perGear = floatArrayOf(0f, 155f, 95f, 68f, 52f, 41f, 34f) // rpm per km/h, by gear
        val base = speedKmh * perGear[gear]
        return (base + throttlePct * 8f).roundToInt().coerceIn(IDLE_RPM, REDLINE_RPM)
    }

    // Warms from cold to thermostat temperature over the first couple of minutes, then hovers.
    private fun coolantFor(elapsedSec: Float): Int {
        val warmed = COLD_COOLANT_C + (RUNNING_COOLANT_C - COLD_COOLANT_C) *
            (elapsedSec / WARMUP_SECONDS).coerceIn(0f, 1f)
        val ripple = if (elapsedSec > WARMUP_SECONDS) (abs((elapsedSec % 20f) - 10f) - 5f) * 0.6f else 0f
        return (warmed + ripple).roundToInt()
    }

    private fun fuelRateFor(speedKmh: Float, throttlePct: Float): Float =
        (0.8f + throttlePct * 0.09f + speedKmh * 0.012f).coerceIn(0.6f, 22f)

    companion object {
        const val SIMULATED_DEVICE_NAME = "Simulated OBD (no bike)"
        const val SIMULATED_DEVICE_ADDRESS = "00:00:00:00:00:00"

        private const val TICK_MS = 100L // matches BluetoothOBDManager's data-loop cadence
        private const val CONNECT_DELAY_MS = 600L
        private const val SWEEP_STEP_MS = 15L

        private const val IDLE_RPM = 1150
        private const val REDLINE_RPM = 9500
        private const val COLD_COOLANT_C = 24f
        private const val RUNNING_COOLANT_C = 88f
        private const val WARMUP_SECONDS = 120f
        private const val START_ODOMETER_KM = 24_580f
        private const val START_FUEL_LEVEL_PCT = 78f
        private const val TANK_LITERS = 16.5f
        private const val MIL_ONSET_SECONDS = 45f
        // Oxygen-sensor and lean-mixture codes: common, harmless-looking, and clearly synthetic
        // enough that nobody mistakes them for a real diagnosis of this bike.
        private val SIMULATED_DTCS = listOf("P0133", "P0171")

    }
}
