package com.example.mototelemetryapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class ObdSweepEntry(val header: String, val did: String, val response: String)

data class PidMapping(val header: String, val command: String, val signal: String)

// dataLoopScope must outlive whatever UI coroutine (e.g. viewModelScope) triggers connect() -
// pass the owning Service's own scope, not one tied to an Activity/ViewModel, or the poll loop
// gets cancelled the moment that UI component is destroyed even though the ride keeps recording.
class BluetoothOBDManager(
    private val context: Context,
    private val dataLoopScope: CoroutineScope
) : ObdSource {

    private val tag = "BluetoothOBDManager"

    override val isSimulated = false
    private val obdUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private val _isConnected = MutableStateFlow(false)
    override val isConnected = _isConnected.asStateFlow()

    private val _obdData = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val obdData = _obdData.asStateFlow()

    private val _pidStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    override val pidStatus = _pidStatus.asStateFlow()

    // Keyed the same as PidMapping.command (the request, e.g. "222503") regardless of whether
    // the underlying parse function matches on that or on the positive-response prefix - callers
    // always pass the request-side key so the Bike Info table can join on CONFIRMED_PID_MAP directly.
    private fun markPidStatus(command: String, ok: Boolean) {
        _pidStatus.value = _pidStatus.value + (command to ok)
    }

    private val _canMonitorRunning = MutableStateFlow(false)
    override val canMonitorRunning = _canMonitorRunning.asStateFlow()

    private val _canMonitorFrames = MutableStateFlow<List<String>>(emptyList())
    override val canMonitorFrames = _canMonitorFrames.asStateFlow()

    // Common Bluetooth SPP adapter names in the wild (ELM327 clones ship under many brands),
    // used only as a first-connect fallback before the user has picked a device explicitly.
    private val knownAdapterNameHints = listOf("OBD", "ELM327", "ELM", "VLINK", "V-LINK", "ICAR", "VGATE", "OBDLINK")

    // Serializes every request/response round trip so the debug sweep can't interleave
    // its own ATSH/DID commands with the regular polling loop on the same serial socket.
    private val ioMutex = Mutex()

    private val _sweepRunning = MutableStateFlow(false)
    override val sweepRunning = _sweepRunning.asStateFlow()

    private val _sweepResults = MutableStateFlow<List<ObdSweepEntry>>(emptyList())
    override val sweepResults = _sweepResults.asStateFlow()

    // (completed probes, total probes) for the currently running sweep, so the UI can show
    // real progress instead of an indefinite spinner.
    private val _sweepProgress = MutableStateFlow(0 to 0)
    override val sweepProgress = _sweepProgress.asStateFlow()

    // Check-engine (MIL) status and any stored DTCs, refreshed periodically by the data loop.
    private val _milOn = MutableStateFlow(false)
    override val milOn = _milOn.asStateFlow()

    private val _dtcCodes = MutableStateFlow<List<String>>(emptyList())
    override val dtcCodes = _dtcCodes.asStateFlow()

    private var dataLoopTick = 0
    private var dataLoopJob: Job? = null

    // sendCommand()/readResponse() both swallow IOException internally (a single unsupported
    // PID shouldn't kill the whole loop), so a dead socket - e.g. the ELM327 losing power when
    // the rider kills the ignition - never threw where startDataLoop()'s catch could see it and
    // flip _isConnected. The badge stayed "Connected" indefinitely after the bike was switched
    // off (reported on a real ride, 2026-08-01). A write failure only happens when the RFCOMM
    // link itself is actually broken (unlike a read timeout, which legitimately happens for an
    // unconfirmed PID), so counting consecutive write failures is an unambiguous dead-link signal.
    private var consecutiveWriteFailures = 0

    // Tracks the ELM327's currently active diagnostic header so redundant ATSH commands can
    // be skipped - each one costs a settle delay plus a round trip, and pollOnce() was
    // resending the same header it was already on every single cycle.
    private var currentHeader: String? = null

    // One-shot per connection: the first time a PID/DID's response doesn't match what parsing
    // expects, the raw bytes get written to the persistent log so a mid-ride data gap (e.g. gear
    // silently reading 0 all ride, 2026-07-31) leaves real evidence instead of just an ambiguous
    // zero in the recorded data. Keyed by DID/PID and capped at one line each so a consistently
    // unsupported command can't flood the log across a whole ride.
    private val loggedUnexpectedResponses = mutableSetOf<String>()
    private var lastLoggedGear: Int? = null

    private fun logUnexpectedResponseOnce(did: String, response: String) {
        if (loggedUnexpectedResponses.add(did)) {
            DiagnosticLog.w(tag, "Beklenmeyen cevap [$did]: \"$response\"")
        }
    }

    private suspend fun setHeader(header: String) {
        if (currentHeader == header) return
        sendCommand("ATSH$header")
        delay(50.milliseconds)
        readResponse() // drain the "OK" reply so it can't be mistaken for the next command's response
        currentHeader = header
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        return adapter.bondedDevices.toList()
    }

    override fun getPreferredDeviceAddress(): String? = prefs.getString(KEY_DEVICE_ADDRESS, null)

    fun setPreferredDevice(address: String) {
        prefs.edit().putString(KEY_DEVICE_ADDRESS, address).apply()
    }

    // Connects to the explicitly chosen device and remembers it for future rides.
    override suspend fun connectToDevice(address: String): Boolean {
        setPreferredDevice(address)
        return connect()
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter

        if (adapter == null || !adapter.isEnabled) {
            DiagnosticLog.e(tag, "Bluetooth kapalı veya desteklenmiyor.")
            return@withContext false
        }

        val pairedDevices: Set<BluetoothDevice> = adapter.bondedDevices
        val preferredAddress = getPreferredDeviceAddress()
        val obdDevice = when {
            preferredAddress != null -> pairedDevices.find { it.address == preferredAddress }
            else -> pairedDevices.find { device -> knownAdapterNameHints.any { device.name?.contains(it, ignoreCase = true) == true } }
        }

        if (obdDevice == null) {
            DiagnosticLog.e(tag, "Eşleşmiş OBD2 cihazı bulunamadı.")
            return@withContext false
        }

        // A leftover discovery scan (e.g. from the phone's own Bluetooth settings screen
        // used to pair the adapter) is a common cause of SPP connect() silently failing.
        adapter.cancelDiscovery()

        // The RFCOMM handshake on these cheap ELM327 clones is flaky right when the adapter has
        // just powered on with the ignition - a single failed attempt used to permanently doom
        // the whole ride to phone-sensors-only (confirmed via a real ride log, 2026-07-31: two
        // back-to-back failures at engine start, zero retry, "continuing with phone sensors
        // only" for the rest of the ride). A few quick retries here covers that automatically
        // instead of relying on the rider noticing and re-tapping Connect several times.
        repeat(CONNECT_MAX_ATTEMPTS) { attempt ->
            // Closes any socket left over from the previous attempt (or a previous connect()
            // call) before opening a new one, so retrying doesn't leak the old RFCOMM channel.
            closeSocketQuietly()

            try {
                socket = obdDevice.createRfcommSocketToServiceRecord(obdUuid)
                socket?.connect()
                outputStream = socket?.outputStream
                inputStream = socket?.inputStream

                // ATZ (sent by initELM327 below) resets the adapter, so any header set on a
                // previous connection is no longer valid.
                currentHeader = null

                if (initELM327()) {
                    _isConnected.value = true
                    loggedUnexpectedResponses.clear()
                    consecutiveWriteFailures = 0
                    if (preferredAddress == null) {
                        // First-time connect via the name-hint heuristic - remember it too,
                        // not just explicit picks from connectToDevice().
                        setPreferredDevice(obdDevice.address)
                    }
                    Log.d(tag, "OBD2 Bağlantısı Başarılı.")
                    // Launched on dataLoopScope (not awaited here) so the poll loop's lifetime is
                    // tied to the owning Service, not to whichever coroutine called connect().
                    dataLoopJob?.cancel()
                    dataLoopJob = dataLoopScope.launch { startDataLoop() }
                    return@withContext true
                }
            } catch (e: IOException) {
                DiagnosticLog.e(tag, "Bağlantı hatası (deneme ${attempt + 1}/$CONNECT_MAX_ATTEMPTS): ${e.message}")
                disconnect()
            } catch (e: SecurityException) {
                // A missing permission won't fix itself between attempts - retrying is pointless.
                DiagnosticLog.e(tag, "Bluetooth izni eksik: ${e.message}")
                disconnect()
                return@withContext false
            }
            if (attempt < CONNECT_MAX_ATTEMPTS - 1) delay(CONNECT_RETRY_DELAY_MS.milliseconds)
        }
        return@withContext false
    }

    private fun closeSocketQuietly() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        outputStream = null
        inputStream = null
    }

    private suspend fun initELM327(): Boolean {
        val commands = listOf("ATZ", "ATE0", "ATL0", "ATSP0")
        for (cmd in commands) {
            sendCommand(cmd)
            val response = readResponse()
            Log.d(tag, "Command: $cmd, Response: $response")
            delay(200.milliseconds)
        }
        return true
    }

    private fun sendCommand(cmd: String) {
        try {
            outputStream?.write((cmd + "\r").toByteArray())
            outputStream?.flush()
            consecutiveWriteFailures = 0
        } catch (e: IOException) {
            consecutiveWriteFailures++
            DiagnosticLog.e(tag, "Komut gönderme hatası: ${e.message}")
        }
    }

    // ATMA (Monitor All) is passive/read-only - the adapter never transmits anything to the bus,
    // it just relays whatever's already flowing, so unlike trySecuritySessionProbe() this carries
    // no risk to the ECU. Used to find which raw CAN ID carries lean angle: per external advice
    // (2026-08-01), a cornering-ABS/TCS bike has an IMU broadcasting roll/pitch/yaw continuously
    // at 50-100Hz rather than answering request/response queries, which would explain why the
    // UDS DID this app currently polls for it (22D10D) has never been reliably confirmed. Method:
    // capture a window while someone tilts the bike side to side, then compare frames by eye (or
    // share the log) for the one CAN ID whose bytes move with the tilt.
    //
    // Monitor mode and the normal request/response poll loop cannot share the serial link at the
    // same time, so this pauses dataLoopJob for the capture window and always restarts it after.
    override suspend fun startCanMonitor(durationSeconds: Int): List<String> {
        if (!_isConnected.value) return emptyList()
        val wasPolling = dataLoopJob != null
        dataLoopJob?.cancel()
        dataLoopJob = null
        _canMonitorRunning.value = true
        _canMonitorFrames.value = emptyList()
        val frames = mutableListOf<String>()
        try {
            withContext(Dispatchers.IO) {
                ioMutex.withLock {
                    // ATH1 (headers on) so each captured line is prefixed with its CAN ID - without
                    // it the adapter streams bare data bytes with no way to tell which ID any of
                    // them came from, making the whole point of this capture (find the one ID that
                    // changes with lean) impossible. Confirmed missing on a real capture, 2026-08-01
                    // (frames with no leading ID at all). Existing parse functions all match via
                    // .contains() on the expected substring, so leaving headers on afterward would
                    // be harmless too, but restoring ATH0 keeps the normal poll loop's behavior
                    // exactly as it was before this ran.
                    try {
                        outputStream?.write("ATH1\r".toByteArray())
                        outputStream?.flush()
                    } catch (_: IOException) {
                    }
                    delay(100.milliseconds)
                    try {
                        val drain = ByteArray(256)
                        inputStream?.read(drain)
                    } catch (_: IOException) {
                    }

                    fun sendAtma() {
                        try {
                            outputStream?.write("ATMA\r".toByteArray())
                            outputStream?.flush()
                        } catch (_: IOException) {
                        }
                    }
                    sendAtma()
                    val deadline = System.currentTimeMillis() + durationSeconds * 1000L
                    val buffer = ByteArray(1024)
                    val lineBuilder = StringBuilder()
                    while (System.currentTimeMillis() < deadline) {
                        // read() blocks with no built-in timeout (same caveat as readResponse()),
                        // and on a quiet bus (e.g. ignition on/engine off, few modules broadcasting)
                        // ATMA can go tens of seconds without producing a single byte - without this
                        // timeout the loop never gets to re-check the deadline, so an 8s capture
                        // silently ran 90+ seconds and killed the Bluetooth socket on a real test,
                        // 2026-08-01. withTimeoutOrNull can't actually interrupt the blocked read
                        // call underneath, but it does let this loop stop waiting on it and finish
                        // on time; the abandoned read is harmless since the socket gets closed by
                        // the stop-monitor sequence right after anyway.
                        val bytes = try {
                            withTimeoutOrNull(500.milliseconds) { inputStream?.read(buffer) } ?: continue
                        } catch (_: IOException) {
                            break
                        }
                        if (bytes == -1) break
                        val chunk = String(buffer, 0, bytes)
                        for (c in chunk) {
                            if (c == '\r' || c == '\n') {
                                val line = lineBuilder.toString().trim()
                                if (line.isNotEmpty()) {
                                    frames.add(line)
                                    // A long capture (the whole point of supporting durations
                                    // beyond a few seconds) can gather thousands of frames at
                                    // 50-100Hz - copying the full list into the StateFlow on every
                                    // single one would mean thousands of list copies/recompositions
                                    // per second. Live frame count only needs to look responsive,
                                    // not update on literally every byte.
                                    val isBufferFull = line.contains("BUFFER", ignoreCase = true) && line.contains("FULL", ignoreCase = true)
                                    if (frames.size % 20 == 0 || isBufferFull) {
                                        _canMonitorFrames.value = frames.toList()
                                    }
                                    // On a busy bus the adapter's internal message buffer fills
                                    // faster than it can be drained over the Bluetooth serial
                                    // link and it auto-stops, returning to the '>' prompt on its
                                    // own (confirmed on a real capture, 2026-08-01 - stopped
                                    // after only a few seconds with the engine running). For a
                                    // longer requested duration, just start it again rather than
                                    // ending the whole capture early - a few seconds of gap is a
                                    // reasonable trade for still covering the full window asked
                                    // for.
                                    if (isBufferFull && System.currentTimeMillis() < deadline) sendAtma()
                                }
                                lineBuilder.clear()
                            } else {
                                lineBuilder.append(c)
                            }
                        }
                    }
                    _canMonitorFrames.value = frames.toList() // final flush past the throttled updates above

                    // Sending any character stops monitor mode and returns the adapter to the
                    // '>' prompt (ELM327 datasheet) - drain the echo/prompt afterward so it
                    // doesn't get misread as the start of the next real command's response.
                    try {
                        outputStream?.write(" ".toByteArray())
                        outputStream?.flush()
                    } catch (_: IOException) {
                    }
                    delay(200.milliseconds)
                    try {
                        withTimeoutOrNull(500.milliseconds) {
                            val drain = ByteArray(256)
                            while (true) {
                                val n = inputStream?.read(drain) ?: break
                                if (n <= 0) break
                            }
                        }
                    } catch (_: IOException) {
                    }

                    // Restore ATH0 so the normal poll loop resumes exactly as before this ran.
                    try {
                        outputStream?.write("ATH0\r".toByteArray())
                        outputStream?.flush()
                    } catch (_: IOException) {
                    }
                    delay(100.milliseconds)
                    try {
                        val drain2 = ByteArray(256)
                        inputStream?.read(drain2)
                    } catch (_: IOException) {
                    }
                }
            }
        } finally {
            _canMonitorRunning.value = false
            if (wasPolling) {
                dataLoopJob = dataLoopScope.launch { startDataLoop() }
            }
        }
        return frames
    }

    private suspend fun readResponse(): String {
        val buffer = ByteArray(1024)
        val response = StringBuilder()
        try {
            // inputStream.read() blocks with no socket-level timeout, so an ECU that never
            // answers (e.g. an unconfirmed sweep header) would otherwise hang this call
            // forever instead of the intended 2s - withTimeoutOrNull enforces it for real.
            withTimeoutOrNull(2000.milliseconds) {
                while (true) {
                    val bytes = inputStream?.read(buffer) ?: -1
                    if (bytes == -1) break
                    val part = String(buffer, 0, bytes)
                    response.append(part)
                    if (part.contains(">")) break
                }
            } ?: DiagnosticLog.w(tag, "Read response timeout")
        } catch (e: IOException) {
            DiagnosticLog.e(tag, "Okuma hatası: ${e.message}")
        }
        return response.toString().trim().replace(">", "")
    }

    private suspend fun startDataLoop() {
        withContext(Dispatchers.IO) {
            while (_isConnected.value) {
                try {
                    // Merge, not replace: pollSlowPids() below only runs every
                    // DTC_POLL_INTERVAL_TICKS, so overwriting the whole map every 100ms cycle
                    // would make its keys flicker back to missing between slow-tier refreshes.
                    _obdData.value = _obdData.value + ioMutex.withLock { pollOnce() }

                    if (consecutiveWriteFailures >= MAX_CONSECUTIVE_WRITE_FAILURES) {
                        DiagnosticLog.e(tag, "Bağlantı koptu (art arda $consecutiveWriteFailures yazma hatası) - bağlantı kesiliyor.")
                        disconnect()
                        break
                    }

                    // DTCs, and everything pollSlowPids() covers, don't change fast enough to
                    // justify the extra ~19 commands' round-trip time on every 100ms cycle
                    // alongside the live gauges - that would noticeably slow down RPM/speed
                    // responsiveness for values a rider never needs at 10Hz (fuel trims, O2
                    // sensor readings, catalyst temp, etc.). Both run at this same slower cadence.
                    dataLoopTick++
                    if (dataLoopTick % DTC_POLL_INTERVAL_TICKS == 0) {
                        ioMutex.withLock { pollDtcStatus() }
                        _obdData.value = _obdData.value + ioMutex.withLock { pollSlowPids() }
                    }

                    delay(100.milliseconds)
                } catch (e: Exception) {
                    DiagnosticLog.e(tag, "Error in data loop: ${e.message}", e)
                    delay(500.milliseconds) // Wait before retrying
                }
            }
        }
    }

    // Must only run while ioMutex is held. Mode 01 PID 01 gives the MIL (check-engine light)
    // status and stored-DTC count cheaply; Mode 03 (the actual codes) is only requested when
    // that count says there's something to fetch.
    private suspend fun pollDtcStatus() {
        setHeader("7E0")

        sendCommand("0101")
        val (milOn, dtcCount) = parseMonitorStatus(readResponse())
        _milOn.value = milOn

        if (milOn || dtcCount > 0) {
            sendCommand("03")
            _dtcCodes.value = parseDtcCodes(readResponse())
        } else {
            _dtcCodes.value = emptyList()
        }
    }

    // Mode 04: erases stored DTCs, turns off the MIL, and resets the ECU's readiness monitors.
    // If the underlying fault is still active it can set a new code within the next drive cycle.
    override suspend fun clearDtcs(): Boolean = withContext(Dispatchers.IO) {
        if (!_isConnected.value) return@withContext false

        ioMutex.withLock {
            setHeader("7E0")

            sendCommand("04")
            val response = readResponse().replace(" ", "")
            val success = response.contains("44")

            if (success) {
                _milOn.value = false
                _dtcCodes.value = emptyList()
            }
            success
        }
    }

    // One full read cycle across both known headers. Must only run while ioMutex is held.
    //
    // All 7E0 reads are grouped before the 7E1 ones (and vice versa next cycle) so this only
    // ever crosses the header boundary once per call instead of three times - setHeader()
    // itself also skips the command entirely when the adapter's already on the right header.
    private suspend fun pollOnce(): Map<String, Int> {
        // --- Engine, Coolant, Odometer & Fuel (Header 7E0) ---
        setHeader("7E0")

        sendCommand("010C")
        val rpm = parseRPM(readResponse())

        sendCommand("010D")
        val speed = parseSpeed(readResponse())

        sendCommand("2243F7")
        val gear = parseGear(readResponse())

        sendCommand("0111")
        val throttle = parseThrottle(readResponse())

        sendCommand("0105")
        val coolant = parseCoolant(readResponse())

        sendCommand("222503")
        val odometer = parseOdometer(readResponse())

        // Standard mode 01 PID (not the manufacturer 222503 DID above) - resets whenever DTCs
        // are cleared, so it is NOT a real lifetime odometer, just a fallback so the Bike Info
        // screen has one honestly-labeled real number instead of only a stuck-at-0 "odometer"
        // (222503 answers "7F 22 31"/unsupported on this ECU - confirmed via a real ride,
        // 2026-08-01). The user's other OBD app already reads this successfully.
        sendCommand("0131")
        val distanceSinceClear = parseDistanceSinceClear(readResponse())

        sendCommand("015E")
        val fuelRate = parseFuelRate(readResponse())

        sendCommand("012F")
        val fuelLevel = parseFuelLevel(readResponse())

        // Standard mode 01 PIDs the Bike Info screen already had UI slots for but no real data
        // ("Battery voltage, intake temp ... not mapped yet") - found working on this exact ECU
        // via the user's other OBD app's sensor dump (extra_info/car_all_sensors_*.csv, PID 66
        // "ECU voltage" 13.853V and PID 15 "Intake Air Temperature" 71°C), 2026-08-01.
        sendCommand("0142")
        val batteryVolts = parseBatteryVoltage(readResponse())

        sendCommand("010F")
        val intakeTemp = parseIntakeTemp(readResponse())

        // Two more standard PIDs confirmed answering on this ECU (car_all_sensors_*.csv PID 4
        // "Calculated Load Value" 29.0%, PID 70 "Ambient Air Temperature" 24.0°C), 2026-08-01.
        sendCommand("0104")
        val engineLoad = parseEngineLoad(readResponse())

        sendCommand("0146")
        val ambientTemp = parseAmbientTemp(readResponse())

        // --- ABS/IMU Data (Header 7E1) ---
        setHeader("7E1")

        sendCommand("222B05")
        val brakeFront = parseBrake(readResponse(), "622B05")

        sendCommand("222B06")
        val brakeRear = parseBrake(readResponse(), "622B06")

        sendCommand("22D10D")
        val leanBike = parseLeanBike(readResponse())

        return mapOf(
            "RPM" to rpm,
            "SPEED" to speed,
            "GEAR" to gear,
            "THROTTLE" to throttle,
            "BRAKE_FRONT" to brakeFront,
            "BRAKE_REAR" to brakeRear,
            "LEAN_BIKE" to leanBike,
            "COOLANT" to coolant,
            "ODOMETER" to odometer.toInt(), // Lossy for Map, but we'll use a better way later
            "DIST_SINCE_CLEAR" to distanceSinceClear,
            "FUEL_RATE" to (fuelRate * 100).toInt(), // Scale for Map
            "FUEL_LEVEL" to fuelLevel,
            "BATTERY" to batteryVolts, // Tenths of a volt (138 = 13.8V) - matches the existing /10f in MainActivity
            "INTAKE_TEMP" to intakeTemp,
            "ENGINE_LOAD" to engineLoad,
            "AMBIENT_TEMP" to ambientTemp
        )
    }

    // Every remaining standard mode 01 PID the full support scan (sweepStandardPidSupport, PID
    // 00/20/40...) found this ECU answers, that pollOnce() doesn't already cover - deliberately
    // kept out of the hot 100ms loop (see startDataLoop) since none of these need live-gauge
    // responsiveness. User asked to grab everything the scan found, 2026-08-01. Skips the PID
    // support bitmasks themselves (0120/0140/0160/0180 - meta, not sensor data), a handful of
    // enum/bitmask PIDs that would need bespoke decoding for little payoff on a bike (0103, 0112,
    // 0113, 011C, 0130), 012E (EVAP purge - no such system on a motorcycle), and 0187 (outside
    // the standard PID table, no documented meaning to decode against).
    private suspend fun pollSlowPids(): Map<String, Int> {
        setHeader("7E0")

        sendCommand("010B")
        val mapKpa = parseSimpleByte(readResponse(), "010B", "410B")

        sendCommand("010E")
        val timingAdvance = parseTimingAdvance(readResponse())

        sendCommand("011F")
        val runTimeSec = parseTwoByte(readResponse(), "011F", "411F")

        sendCommand("0121")
        val distanceMilOn = parseTwoByte(readResponse(), "0121", "4121")

        sendCommand("013C")
        val catalystTempB1 = parseCatalystTemp(readResponse(), "013C", "413C")

        sendCommand("013D")
        val catalystTempB2 = parseCatalystTemp(readResponse(), "013D", "413D")

        sendCommand("0106")
        val stFuelTrimB1 = parseFuelTrim(readResponse(), "0106", "4106")

        sendCommand("0107")
        val ltFuelTrimB1 = parseFuelTrim(readResponse(), "0107", "4107")

        sendCommand("0108")
        val stFuelTrimB2 = parseFuelTrim(readResponse(), "0108", "4108")

        sendCommand("0109")
        val ltFuelTrimB2 = parseFuelTrim(readResponse(), "0109", "4109")

        sendCommand("0114")
        val (o2B1S1Voltage, o2B1S1Trim) = parseO2Sensor(readResponse(), "0114", "4114")

        sendCommand("0118")
        val (o2B2S1Voltage, o2B2S1Trim) = parseO2Sensor(readResponse(), "0118", "4118")

        sendCommand("0143")
        val absoluteLoad = parseTwoByteScaled(readResponse(), "0143", "4143")

        sendCommand("0144")
        val equivalenceRatioPct = parseEquivalenceRatio(readResponse(), "0144", "4144")

        sendCommand("0145")
        val relativeThrottle = parseSimplePercent(readResponse(), "0145", "4145")

        sendCommand("0147")
        val throttlePosB = parseSimplePercent(readResponse(), "0147", "4147")

        sendCommand("0149")
        val pedalPosD = parseSimplePercent(readResponse(), "0149", "4149")

        sendCommand("014A")
        val pedalPosE = parseSimplePercent(readResponse(), "014A", "414A")

        sendCommand("014C")
        val commandedThrottleActuator = parseSimplePercent(readResponse(), "014C", "414C")

        return mapOf(
            "MAP_KPA" to mapKpa,
            "TIMING_ADVANCE" to timingAdvance,
            "ENGINE_RUNTIME_SEC" to runTimeSec,
            "DIST_MIL_ON" to distanceMilOn,
            "CATALYST_TEMP_B1" to catalystTempB1,
            "CATALYST_TEMP_B2" to catalystTempB2,
            "FUEL_TRIM_ST_B1" to stFuelTrimB1,
            "FUEL_TRIM_LT_B1" to ltFuelTrimB1,
            "FUEL_TRIM_ST_B2" to stFuelTrimB2,
            "FUEL_TRIM_LT_B2" to ltFuelTrimB2,
            "O2_B1S1_MV" to o2B1S1Voltage,
            "O2_B1S1_TRIM" to o2B1S1Trim,
            "O2_B2S1_MV" to o2B2S1Voltage,
            "O2_B2S1_TRIM" to o2B2S1Trim,
            "ABSOLUTE_LOAD" to absoluteLoad,
            "EQUIVALENCE_RATIO_PCT" to equivalenceRatioPct,
            "RELATIVE_THROTTLE" to relativeThrottle,
            "THROTTLE_POS_B" to throttlePosB,
            "PEDAL_POS_D" to pedalPosD,
            "PEDAL_POS_E" to pedalPosE,
            "COMMANDED_THROTTLE_ACTUATOR" to commandedThrottleActuator
        )
    }

    // Sweeps a range of CAN diagnostic headers and UDS "read data by identifier" (service 22)
    // DIDs, so an unknown value (e.g. fuel level, which isn't exposed via any standard PID on
    // this ECU) can be hunted for by hand: run this, then compare which (header, DID) pairs
    // return a plausible value against a known real-world state (e.g. right after a fill-up).
    override suspend fun sweepHeadersAndDids(
        headers: List<String>,
        dids: List<String>
    ) {
        if (!_isConnected.value) return
        _sweepRunning.value = true
        _sweepResults.value = emptyList()
        val total = headers.size * dids.size
        var completed = 0
        _sweepProgress.value = 0 to total
        try {
            withContext(Dispatchers.IO) {
                ioMutex.withLock {
                    for (header in headers) {
                        setHeader(header)

                        for (did in dids) {
                            sendCommand("22$did")
                            delay(80.milliseconds)
                            val response = readResponse()
                            _sweepResults.value = _sweepResults.value + ObdSweepEntry(header, did, response)
                            completed++
                            _sweepProgress.value = completed to total
                        }
                    }
                    // Restore the header the normal polling loop expects.
                    setHeader("7E0")
                }
            }
        } finally {
            _sweepRunning.value = false
            _sweepProgress.value = 0 to 0
        }
    }

    // Mode 01 PID 00 (and 20/40/60...) is the official OBD-II way to ask "which standard PIDs do
    // you support" - a 4-byte bitmask covering the next 32 PIDs, with its own last bit saying
    // whether to keep going. Every OBD-II-compliant ECU must answer this, unlike the
    // manufacturer UDS DIDs sweepHeadersAndDids() probes - so this is a completely safe way to
    // find out for certain whether e.g. fuel rate/level (015E/012F, "NO DATA" on this ECU) are
    // genuinely unsupported rather than guessed-wrong, without relying on a third-party app's
    // dump or trial and error. Reuses the same sweepRunning/sweepResults/sweepProgress flows as
    // the DID sweep so the existing dialog UI needs no changes.
    override suspend fun sweepStandardPidSupport() {
        if (!_isConnected.value) return
        _sweepRunning.value = true
        _sweepResults.value = emptyList()
        try {
            withContext(Dispatchers.IO) {
                ioMutex.withLock {
                    setHeader("7E0")
                    val supported = mutableListOf<Int>()
                    var base = 0x00
                    var blocksQueried = 0
                    while (blocksQueried < 6) { // caps at PID 0xC0 - far past what this ECU needs
                        val baseHex = base.toString(16).uppercase().padStart(2, '0')
                        sendCommand("01$baseHex")
                        delay(80.milliseconds)
                        val response = readResponse().replace(" ", "")
                        blocksQueried++
                        _sweepProgress.value = blocksQueried to 6
                        val prefix = "41$baseHex"
                        if (!response.contains(prefix)) break
                        val hex = response.substringAfter(prefix).take(8)
                        if (hex.length < 8) break
                        val mask = java.lang.Long.parseLong(hex, 16)
                        for (bit in 0 until 32) {
                            // bit 31 (MSB) = PID base+01 ... bit 0 (LSB) = PID base+20
                            if ((mask shr bit) and 1L == 1L) supported.add(base + (32 - bit))
                        }
                        // The LSB doubles as "next block (PID base+20 itself) is supported" - the
                        // standard signal for whether continuing is worth it.
                        if ((mask and 1L) == 0L) break
                        base += 0x20
                    }
                    setHeader("7E0")
                    _sweepResults.value = supported.sorted().map { pid ->
                        ObdSweepEntry("7E0", "01" + pid.toString(16).uppercase().padStart(2, '0'), "SUPPORTED")
                    }
                }
            }
        } finally {
            _sweepRunning.value = false
            _sweepProgress.value = 0 to 0
        }
    }

    // EXPLICITLY RISKY - unlike every other command in this file, this changes the ECU's
    // diagnostic session state rather than just reading data. UDS DiagnosticSessionControl
    // (10 03, Extended Diagnostic Session) can change how an ECU behaves (e.g. suspend normal
    // broadcasts) and this app already has one real incident of a blind UDS probe causing an
    // actual engine stall/dash blackout mid-ride (2026-07-26, see sweepHeadersAndDids's header
    // restriction). User-requested after external advice about getting past "7F 22 31"
    // (requestOutOfRange) on 43FE/43FF, 2026-08-01 - deliberately NOT part of the regular sweep
    // and never auto-invoked; the UI only offers it behind an explicit "engine must be off"
    // confirmation, for a single manual test. Always sends 10 01 (back to Default Session)
    // before finishing, whatever the outcome, so the ECU is never left in an unusual mode.
    override suspend fun trySecuritySessionProbe() {
        if (!_isConnected.value) return
        _sweepRunning.value = true
        _sweepResults.value = emptyList()
        _sweepProgress.value = 0 to 2
        try {
            withContext(Dispatchers.IO) {
                ioMutex.withLock {
                    setHeader("7E0")

                    sendCommand("1003")
                    delay(80.milliseconds)
                    val sessionResponse = readResponse()
                    _sweepResults.value = _sweepResults.value + ObdSweepEntry("7E0", "1003", sessionResponse)
                    _sweepProgress.value = 1 to 2

                    // Only bother re-probing if the ECU actually granted the extended session
                    // (50 03) - a negative response (e.g. 7F 10 33, security access needed) means
                    // there's nothing more to try here without a seed/key exchange this app does
                    // not implement.
                    if (sessionResponse.replace(" ", "").contains("5003")) {
                        for (did in listOf("43FE", "43FF")) {
                            sendCommand("22$did")
                            delay(80.milliseconds)
                            _sweepResults.value = _sweepResults.value + ObdSweepEntry("7E0", did, readResponse())
                        }
                    }
                    _sweepProgress.value = 2 to 2

                    sendCommand("1001")
                    delay(80.milliseconds)
                    readResponse()
                    setHeader("7E0")
                }
            }
        } finally {
            _sweepRunning.value = false
            _sweepProgress.value = 0 to 0
        }
    }

    private fun parseCoolant(response: String): Int {
        // 41 05 XX -> XX - 40
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("4105")) {
                val hex = clean.substringAfter("4105").take(2)
                markPidStatus("0105", true)
                Integer.parseInt(hex, 16) - 40
            } else {
                markPidStatus("0105", false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    private fun parseBatteryVoltage(response: String): Int {
        // 41 42 AA BB -> (AA*256+BB)/1000 volts. Returned as tenths of a volt (138 = 13.8V) to
        // match the /10f already applied where this is consumed (MainActivity.kt).
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("4142")) {
                val hex = clean.substringAfter("4142").take(4)
                markPidStatus("0142", true)
                (Integer.parseInt(hex, 16) / 100.0).roundToInt()
            } else {
                markPidStatus("0142", false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    private fun parseIntakeTemp(response: String): Int {
        // 41 0F XX -> XX - 40
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("410F")) {
                val hex = clean.substringAfter("410F").take(2)
                markPidStatus("010F", true)
                Integer.parseInt(hex, 16) - 40
            } else {
                markPidStatus("010F", false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    private fun parseEngineLoad(response: String): Int {
        // 41 04 XX -> XX * 100 / 255
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("4104")) {
                val hex = clean.substringAfter("4104").take(2)
                markPidStatus("0104", true)
                (Integer.parseInt(hex, 16) * 100) / 255
            } else {
                markPidStatus("0104", false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    private fun parseAmbientTemp(response: String): Int {
        // 41 46 XX -> XX - 40
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("4146")) {
                val hex = clean.substringAfter("4146").take(2)
                markPidStatus("0146", true)
                Integer.parseInt(hex, 16) - 40
            } else {
                markPidStatus("0146", false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    // --- Generic mode 01 decoders shared by pollSlowPids()'s ~19 PIDs (010B/010E/011F/0121/
    // 013C/013D/0106-0109/0114/0118/0143/0144/0145/0147/0149/014A/014C) - all standard SAE J1979
    // formulas, none manufacturer-specific like the 22xx UDS DIDs elsewhere in this file. `prefix`
    // is the request command (matches CONFIRMED_PID_MAP.command for markPidStatus/the sensor
    // map), `match` is the expected positive-response prefix ("41" + PID hex).

    // 1 byte, direct value 0-255 (e.g. 010B Intake Manifold Pressure, kPa).
    private fun parseSimpleByte(response: String, prefix: String, match: String): Int {
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains(match)) {
                val hex = clean.substringAfter(match).take(2)
                markPidStatus(prefix, true)
                Integer.parseInt(hex, 16)
            } else {
                markPidStatus(prefix, false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    // 1 byte, A*100/255 (e.g. 0145 relative throttle, 0149/014A pedal position).
    private fun parseSimplePercent(response: String, prefix: String, match: String): Int {
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains(match)) {
                val hex = clean.substringAfter(match).take(2)
                markPidStatus(prefix, true)
                (Integer.parseInt(hex, 16) * 100) / 255
            } else {
                markPidStatus(prefix, false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    // 2 bytes, direct value A*256+B (e.g. 011F engine run time seconds, 0121 distance w/ MIL on km).
    private fun parseTwoByte(response: String, prefix: String, match: String): Int {
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains(match)) {
                val hex = clean.substringAfter(match).take(4)
                markPidStatus(prefix, true)
                Integer.parseInt(hex, 16)
            } else {
                markPidStatus(prefix, false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    // 2 bytes, (A*256+B)*100/255 (e.g. 0143 absolute load value, %).
    private fun parseTwoByteScaled(response: String, prefix: String, match: String): Int {
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains(match)) {
                val hex = clean.substringAfter(match).take(4)
                markPidStatus(prefix, true)
                (Integer.parseInt(hex, 16) * 100) / 255
            } else {
                markPidStatus(prefix, false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    // 010E Timing Advance: 1 byte, A/2 - 64, degrees (signed - can be negative pre-TDC).
    private fun parseTimingAdvance(response: String): Int {
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("410E")) {
                val hex = clean.substringAfter("410E").take(2)
                markPidStatus("010E", true)
                (Integer.parseInt(hex, 16) / 2) - 64
            } else {
                markPidStatus("010E", false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    // 013C/013D Catalyst Temperature: 2 bytes, ((A*256+B)/10) - 40, °C.
    private fun parseCatalystTemp(response: String, prefix: String, match: String): Int {
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains(match)) {
                val hex = clean.substringAfter(match).take(4)
                markPidStatus(prefix, true)
                (Integer.parseInt(hex, 16) / 10) - 40
            } else {
                markPidStatus(prefix, false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    // 0106-0109 Fuel Trims: 1 byte, (A-128)*100/128, % (negative = trimming leaner).
    private fun parseFuelTrim(response: String, prefix: String, match: String): Int {
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains(match)) {
                val hex = clean.substringAfter(match).take(2)
                markPidStatus(prefix, true)
                ((Integer.parseInt(hex, 16) - 128) * 100) / 128
            } else {
                markPidStatus(prefix, false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    // 0114/0118 O2 Sensor: 2 bytes - A = voltage (A*5 mV, 0-1275mV range), B = short-term fuel
    // trim ((B-128)*100/128 %, or "not used" if 0xFF - not specially handled here, an unused
    // sensor's trim value just isn't meaningful and the UI has no slot for it yet anyway).
    private fun parseO2Sensor(response: String, prefix: String, match: String): Pair<Int, Int> {
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains(match)) {
                val hex = clean.substringAfter(match).take(4)
                markPidStatus(prefix, true)
                val a = Integer.parseInt(hex.take(2), 16)
                val b = Integer.parseInt(hex.substring(2, 4), 16)
                (a * 5) to (((b - 128) * 100) / 128)
            } else {
                markPidStatus(prefix, false)
                0 to 0
            }
        } catch (_: Exception) { 0 to 0 }
    }

    // 0144 Commanded Equivalence Ratio: 2 bytes, (A*256+B)/32768, stored as ratio*100 (Int) since
    // this feeds a Map<String, Int> - e.g. 100 means a ratio of 1.00 (stoichiometric).
    private fun parseEquivalenceRatio(response: String, prefix: String, match: String): Int {
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains(match)) {
                val hex = clean.substringAfter(match).take(4)
                markPidStatus(prefix, true)
                (Integer.parseInt(hex, 16) * 100) / 32768
            } else {
                markPidStatus(prefix, false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    private fun parseOdometer(response: String): Long {
        // 62 25 03 AA BB CC DD -> (AA*2^24 + BB*2^16 + CC*256 + DD)
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("622503")) {
                val hex = clean.substringAfter("622503").take(8)
                markPidStatus("222503", true)
                java.lang.Long.parseLong(hex, 16)
            } else {
                logUnexpectedResponseOnce("222503", response)
                markPidStatus("222503", false)
                0L
            }
        } catch (_: Exception) { 0L }
    }

    private fun parseDistanceSinceClear(response: String): Int {
        // 41 31 AA BB -> AA*256 + BB, km
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("4131")) {
                val hex = clean.substringAfter("4131").take(4)
                markPidStatus("0131", true)
                Integer.parseInt(hex, 16)
            } else {
                logUnexpectedResponseOnce("0131", response)
                markPidStatus("0131", false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    private fun parseFuelRate(response: String): Float {
        // 41 5E AA BB -> (AA*256 + BB) / 20
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("415E")) {
                val hex = clean.substringAfter("415E").take(4)
                markPidStatus("015E", true)
                Integer.parseInt(hex, 16) / 20f
            } else {
                logUnexpectedResponseOnce("015E", response)
                markPidStatus("015E", false)
                0f
            }
        } catch (_: Exception) { 0f }
    }

    private fun parseFuelLevel(response: String): Int {
        // 41 2F XX -> XX * 100 / 255
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("412F")) {
                val hex = clean.substringAfter("412F").take(2)
                markPidStatus("012F", true)
                (Integer.parseInt(hex, 16) * 100) / 255
            } else {
                logUnexpectedResponseOnce("012F", response)
                markPidStatus("012F", false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    internal fun parseLeanBike(response: String): Int {
        // 62 D1 0D XX YY -> Signed 16-bit integer
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("62D10D")) {
                val hex = clean.substringAfter("62D10D").take(4)
                val raw = Integer.parseInt(hex, 16).toShort().toInt()
                markPidStatus("22D10D", true)
                // Genelde 0.1 çarpanı ile dereceye çevrilir
                (raw * 0.1).toInt()
            } else {
                logUnexpectedResponseOnce("22D10D", response)
                markPidStatus("22D10D", false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    internal fun parseRPM(response: String): Int {
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("410C")) {
                val hex = clean.substringAfter("410C").take(4)
                markPidStatus("010C", true)
                Integer.parseInt(hex, 16) / 4
            } else {
                markPidStatus("010C", false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    internal fun parseSpeed(response: String): Int {
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("410D")) {
                val hex = clean.substringAfter("410D").take(2)
                markPidStatus("010D", true)
                Integer.parseInt(hex, 16)
            } else {
                markPidStatus("010D", false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    internal fun parseGear(response: String): Int {
        // 62 43 F7 XX -> XX is the gear
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("6243F7")) {
                val hex = clean.substringAfter("6243F7").take(2)
                val rawGear = Integer.parseInt(hex, 16)
                // BMW BMS-O genelde 0=N, 1-6=Gears. Bazı durumlarda 15=N. Only ever verified
                // against this ECU's real Neutral byte - a rider reported shifting into 1st/2nd
                // without the app's gear readout changing (2026-08-01), so this mapping may not
                // hold for non-neutral values. Logged on every real change (not just parse
                // failures) so the next real shift leaves the actual raw byte in the diagnostic
                // log instead of us having to guess at the encoding again.
                val gear = if (rawGear == 15) 0 else rawGear
                markPidStatus("2243F7", true)
                // A logging hiccup must never corrupt the actual parsed gear - this whole
                // function's own catch-all below would otherwise turn a perfectly good read
                // into a silent 0 ("N"), which a unit test caught immediately.
                if (gear != lastLoggedGear) {
                    try {
                        DiagnosticLog.w(tag, "Vites değişti: ham=0x$hex -> $gear (\"$response\")")
                    } catch (_: Exception) {
                    }
                    lastLoggedGear = gear
                }
                gear
            } else {
                logUnexpectedResponseOnce("2243F7", response)
                markPidStatus("2243F7", false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    internal fun parseThrottle(response: String): Int {
        // 41 11 XX -> XX * 100 / 255
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("4111")) {
                val hex = clean.substringAfter("4111").take(2)
                markPidStatus("0111", true)
                (Integer.parseInt(hex, 16) * 100) / 255
            } else {
                markPidStatus("0111", false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    // 41 01 XX ... -> bit 7 of XX is the MIL (check-engine light) state, bits 6-0 are the
    // stored-DTC count.
    internal fun parseMonitorStatus(response: String): Pair<Boolean, Int> {
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("4101")) {
                val statusByte = Integer.parseInt(clean.substringAfter("4101").take(2), 16)
                markPidStatus("0101", true)
                ((statusByte and 0x80) != 0) to (statusByte and 0x7F)
            } else {
                markPidStatus("0101", false)
                false to 0
            }
        } catch (_: Exception) {
            false to 0
        }
    }

    // 43 AABB CCDD ... -> each 2-byte pair is one DTC, encoded per SAE J2012: top 2 bits of the
    // first byte select the letter (P/C/B/U), remaining bits form the 4-digit code.
    internal fun parseDtcCodes(response: String): List<String> {
        return try {
            val clean = response.replace(" ", "")
            if (!clean.contains("43")) return emptyList()
            val bytes = clean.substringAfter("43")
            bytes.chunked(4)
                .filter { it.length == 4 && it != "0000" }
                .map { decodeDtc(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decodeDtc(hex4: String): String {
        val byte1 = hex4.substring(0, 2).toInt(16)
        val byte2 = hex4.substring(2, 4).toInt(16)
        val letter = when ((byte1 shr 6) and 0x03) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            else -> 'U'
        }
        val digit1 = (byte1 shr 4) and 0x03
        val digit2 = (byte1 and 0x0F).toString(16).uppercase()
        val digit3 = ((byte2 shr 4) and 0x0F).toString(16).uppercase()
        val digit4 = (byte2 and 0x0F).toString(16).uppercase()
        return "$letter$digit1$digit2$digit3$digit4"
    }

    internal fun parseBrake(response: String, prefix: String): Int {
        // Response format: 62 [PID] [Value]. requestDid mirrors prefix but with the request
        // service byte (22) instead of the positive-response one (62), matching how
        // PidMapping.command/CONFIRMED_PID_MAP key brakes ("222B05"/"222B06").
        val requestDid = "2" + prefix.drop(1)
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains(prefix)) {
                val hex = clean.substringAfter(prefix).take(2)
                markPidStatus(requestDid, true)
                // BMW fren basıncı için genelde bar cinsinden veri döner.
                // Basit bir ölçekleme yapıyoruz.
                Integer.parseInt(hex, 16)
            } else {
                logUnexpectedResponseOnce(prefix, response)
                markPidStatus(requestDid, false)
                0
            }
        } catch (_: Exception) { 0 }
    }

    override fun disconnect() {
        try {
            _isConnected.value = false
            dataLoopJob?.cancel()
            dataLoopJob = null
            socket?.close()
            _milOn.value = false
            _dtcCodes.value = emptyList()
            _pidStatus.value = emptyMap()
            currentHeader = null
            Log.d(tag, "OBD2 Bağlantısı kesildi.")
        } catch (e: IOException) {
            DiagnosticLog.e(tag, "Kapatma hatası: ${e.message}")
        }
    }

    companion object {
        private const val PREFS_NAME = "obd_prefs"
        private const val KEY_DEVICE_ADDRESS = "device_address"
        private const val DTC_POLL_INTERVAL_TICKS = 50 // ~5s at the 100ms data-loop cadence
        private const val CONNECT_MAX_ATTEMPTS = 4
        private const val CONNECT_RETRY_DELAY_MS = 1200L
        // Each pollOnce() cycle makes 11 sendCommand() calls, so this trips within roughly one
        // cycle of the link actually dying rather than needing many seconds of stale "Connected".
        private const val MAX_CONSECUTIVE_WRITE_FAILURES = 8

        // For callers (e.g. ObdAutoStartReceiver) that just need to peek at the stored device
        // address without constructing a full manager instance (sockets, mutexes, a data-loop
        // scope) that they'll never actually use.
        fun getPreferredDeviceAddress(context: Context): String? =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DEVICE_ADDRESS, null)

        // Mirrors exactly what pollOnce() below requests - the confirmed, working PIDs, kept
        // here for the Bike Info screen's reference table rather than duplicating this list
        // in the UI layer.
        val CONFIRMED_PID_MAP = listOf(
            PidMapping("7E0", "010C", "RPM"),
            PidMapping("7E0", "010D", "Speed"),
            PidMapping("7E0", "2243F7", "Gear"),
            PidMapping("7E0", "0111", "Throttle"),
            PidMapping("7E1", "222B05", "Brake front"),
            PidMapping("7E1", "222B06", "Brake rear"),
            PidMapping("7E1", "22D10D", "Lean angle (bike)"),
            PidMapping("7E0", "0105", "Coolant temp"),
            PidMapping("7E0", "222503", "Odometer"),
            PidMapping("7E0", "0131", "Distance since DTC clear"),
            PidMapping("7E0", "015E", "Fuel rate"),
            PidMapping("7E0", "012F", "Fuel level"),
            PidMapping("7E0", "0142", "Battery voltage"),
            PidMapping("7E0", "010F", "Intake air temp"),
            PidMapping("7E0", "0104", "Engine load"),
            PidMapping("7E0", "0146", "Ambient air temp"),
            PidMapping("7E0", "010B", "Intake manifold pressure"),
            PidMapping("7E0", "010E", "Timing advance"),
            PidMapping("7E0", "011F", "Engine run time"),
            PidMapping("7E0", "0121", "Distance with MIL on"),
            PidMapping("7E0", "013C", "Catalyst temp B1S1"),
            PidMapping("7E0", "013D", "Catalyst temp B2S1"),
            PidMapping("7E0", "0106", "Short fuel trim B1"),
            PidMapping("7E0", "0107", "Long fuel trim B1"),
            PidMapping("7E0", "0108", "Short fuel trim B2"),
            PidMapping("7E0", "0109", "Long fuel trim B2"),
            PidMapping("7E0", "0114", "O2 sensor B1S1"),
            PidMapping("7E0", "0118", "O2 sensor B2S1"),
            PidMapping("7E0", "0143", "Absolute load"),
            PidMapping("7E0", "0144", "Commanded equivalence ratio"),
            PidMapping("7E0", "0145", "Relative throttle position"),
            PidMapping("7E0", "0147", "Throttle position B"),
            PidMapping("7E0", "0149", "Pedal position D"),
            PidMapping("7E0", "014A", "Pedal position E"),
            PidMapping("7E0", "014C", "Commanded throttle actuator"),
            PidMapping("7E0", "0101", "MIL status / DTC count"),
            PidMapping("7E0", "03", "Stored DTCs")
        )

        // Only the two headers already confirmed to answer (engine ECU 7E0 / ABS-IMU 7E1).
        // 7E2-7E7 are unconfirmed CAN addresses that could belong to any other ECU on the
        // bus (cluster, immobilizer, etc.) - probing those with unsolicited UDS requests is
        // what's suspected of causing the bike's dash/start-stop hiccup during a sweep, so
        // they're deliberately excluded from the default and would need to be opted into
        // explicitly with full awareness of the risk.
        val DEFAULT_SWEEP_HEADERS = listOf("7E0", "7E1")

        // DID neighborhoods around the manufacturer PIDs already confirmed to work
        // (222503 odometer, 22D10D lean, 222B05/222B06 brakes, 2243F7 gear) - UDS DID
        // tables are commonly allocated in contiguous blocks per subsystem, so a value
        // like fuel level is more likely to sit near one of these than at a random address.
        val DEFAULT_SWEEP_DIDS: List<String> =
            hexRange(0x2500, 0x250F) + hexRange(0xD100, 0xD11F) +
            hexRange(0x2B00, 0x2B0F) + hexRange(0x43F0, 0x43FF)

        private fun hexRange(start: Int, end: Int): List<String> =
            (start..end).map { it.toString(16).uppercase().padStart(4, '0') }
    }
}
