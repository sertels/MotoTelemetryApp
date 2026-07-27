package com.example.mototelemetryapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.flow.StateFlow

// Everything TelemetryService needs from an OBD adapter, so the real Bluetooth implementation
// and the simulator are interchangeable behind one type. Anything added here has to be
// answerable by both - if only the Bluetooth side can do it, it belongs on BluetoothOBDManager
// directly, not on this interface.
interface ObdSource {

    val isConnected: StateFlow<Boolean>

    // Raw PID map, keyed by the names pollOnce() produces (RPM, SPEED, GEAR, ...).
    val obdData: StateFlow<Map<String, Int>>

    val sweepRunning: StateFlow<Boolean>
    val sweepResults: StateFlow<List<ObdSweepEntry>>
    val sweepProgress: StateFlow<Pair<Int, Int>>

    val milOn: StateFlow<Boolean>
    val dtcCodes: StateFlow<List<String>>

    // True when this source is making its data up. Surfaced all the way to the status badge so
    // a screenshot is never ambiguous about whether it shows a real bike.
    val isSimulated: Boolean

    // (display name, address) pairs - the UI never needs the BluetoothDevice itself, and the
    // simulator has no such object to hand back.
    fun getPairedDeviceEntries(): List<Pair<String, String>>

    fun getPreferredDeviceAddress(): String?

    suspend fun connect(): Boolean

    suspend fun connectToDevice(address: String): Boolean

    fun disconnect()

    suspend fun clearDtcs(): Boolean

    suspend fun sweepHeadersAndDids(
        headers: List<String> = BluetoothOBDManager.DEFAULT_SWEEP_HEADERS,
        dids: List<String> = BluetoothOBDManager.DEFAULT_SWEEP_DIDS
    )
}

// Whether the simulated sources should be used instead of the real Bluetooth adapter and
// phone sensors. Governs both, so the two halves of a telemetry record can never disagree.
//
// Hard-gated on BuildConfig.DEBUG: a release build always talks to the real bike, whatever is
// left in prefs, so a stale toggle can never show a rider invented telemetry.
//
// Within a debug build the stored preference wins if the user has set one; with no preference
// yet it defaults to on when there's nothing an OBD adapter could be reached through. That
// default is deliberately only a default - it stays overridable in both directions, which
// auto-detection alone would not allow.
fun isRideSimulationEnabled(context: Context): Boolean {
    if (!BuildConfig.DEBUG) return false
    val prefs = context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE)
    if (prefs.contains(KEY_SIMULATE_OBD)) return prefs.getBoolean(KEY_SIMULATE_OBD, false)
    return !hasPairedBluetoothDevices(context)
}

// Deliberately checks for *bonded devices*, not merely for an adapter: Android emulators do
// expose a working BluetoothAdapter, so "is there an adapter" is true there and would never
// trigger the simulator. Nothing is ever paired on an emulator, which is the condition that
// actually distinguishes "could reach an OBD dongle" from "could not".
@SuppressLint("MissingPermission")
fun hasPairedBluetoothDevices(context: Context): Boolean {
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        ?: return false
    if (!adapter.isEnabled) return false
    // bondedDevices throws without BLUETOOTH_CONNECT; treat "can't tell" as nothing reachable.
    return try {
        adapter.bondedDevices.isNotEmpty()
    } catch (_: SecurityException) {
        false
    }
}
