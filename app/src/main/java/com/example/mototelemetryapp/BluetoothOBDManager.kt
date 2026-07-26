package com.example.mototelemetryapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class ObdSweepEntry(val header: String, val did: String, val response: String)

class BluetoothOBDManager(private val context: Context) {

    private val tag = "BluetoothOBDManager"
    private val obdUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _obdData = MutableStateFlow<Map<String, Int>>(emptyMap())
    val obdData = _obdData.asStateFlow()

    // Common Bluetooth SPP adapter names in the wild (ELM327 clones ship under many brands),
    // used only as a first-connect fallback before the user has picked a device explicitly.
    private val knownAdapterNameHints = listOf("OBD", "ELM327", "ELM", "VLINK", "V-LINK", "ICAR", "VGATE", "OBDLINK")

    // Serializes every request/response round trip so the debug sweep can't interleave
    // its own ATSH/DID commands with the regular polling loop on the same serial socket.
    private val ioMutex = Mutex()

    private val _sweepRunning = MutableStateFlow(false)
    val sweepRunning = _sweepRunning.asStateFlow()

    private val _sweepResults = MutableStateFlow<List<ObdSweepEntry>>(emptyList())
    val sweepResults = _sweepResults.asStateFlow()

    // Check-engine (MIL) status and any stored DTCs, refreshed periodically by the data loop.
    private val _milOn = MutableStateFlow(false)
    val milOn = _milOn.asStateFlow()

    private val _dtcCodes = MutableStateFlow<List<String>>(emptyList())
    val dtcCodes = _dtcCodes.asStateFlow()

    private var dataLoopTick = 0

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        return adapter.bondedDevices.toList()
    }

    fun getPreferredDeviceAddress(): String? = prefs.getString(KEY_DEVICE_ADDRESS, null)

    fun setPreferredDevice(address: String) {
        prefs.edit().putString(KEY_DEVICE_ADDRESS, address).apply()
    }

    // Connects to the explicitly chosen device and remembers it for future rides.
    suspend fun connectToDevice(address: String): Boolean {
        setPreferredDevice(address)
        return connect()
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter

        if (adapter == null || !adapter.isEnabled) {
            Log.e(tag, "Bluetooth kapalı veya desteklenmiyor.")
            return@withContext false
        }

        val pairedDevices: Set<BluetoothDevice> = adapter.bondedDevices
        val preferredAddress = getPreferredDeviceAddress()
        val obdDevice = when {
            preferredAddress != null -> pairedDevices.find { it.address == preferredAddress }
            else -> pairedDevices.find { device -> knownAdapterNameHints.any { device.name?.contains(it, ignoreCase = true) == true } }
        }

        if (obdDevice == null) {
            Log.e(tag, "Eşleşmiş OBD2 cihazı bulunamadı.")
            return@withContext false
        }

        try {
            socket = obdDevice.createRfcommSocketToServiceRecord(obdUuid)
            socket?.connect()
            outputStream = socket?.outputStream
            inputStream = socket?.inputStream

            if (initELM327()) {
                _isConnected.value = true
                Log.d(tag, "OBD2 Bağlantısı Başarılı.")
                startDataLoop()
                return@withContext true
            }
        } catch (e: IOException) {
            Log.e(tag, "Bağlantı hatası: ${e.message}")
            disconnect()
        }
        return@withContext false
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
        } catch (e: IOException) {
            Log.e(tag, "Komut gönderme hatası: ${e.message}")
        }
    }

    private fun readResponse(): String {
        val buffer = ByteArray(1024)
        var bytes: Int
        val response = StringBuilder()
        try {
            // Basit bir okuma mantığı - ELM327 yanıtı '>' ile bitirir
            val startTime = System.currentTimeMillis()
            val timeout = 2000 // 2 second timeout
            
            while (true) {
                if (System.currentTimeMillis() - startTime > timeout) {
                    Log.w(tag, "Read response timeout")
                    break
                }
                
                bytes = inputStream?.read(buffer) ?: -1
                if (bytes == -1) break
                val part = String(buffer, 0, bytes)
                response.append(part)
                if (part.contains(">")) break
            }
        } catch (e: IOException) {
            Log.e(tag, "Okuma hatası: ${e.message}")
        }
        return response.toString().trim().replace(">", "")
    }

    private suspend fun startDataLoop() {
        withContext(Dispatchers.IO) {
            while (_isConnected.value) {
                try {
                    _obdData.value = ioMutex.withLock { pollOnce() }

                    // DTCs don't change fast enough to justify checking every 100ms cycle
                    // alongside the live gauges, so this only runs every DTC_POLL_INTERVAL_TICKS.
                    dataLoopTick++
                    if (dataLoopTick % DTC_POLL_INTERVAL_TICKS == 0) {
                        ioMutex.withLock { pollDtcStatus() }
                    }

                    delay(100.milliseconds)
                } catch (e: Exception) {
                    Log.e(tag, "Error in data loop: ${e.message}", e)
                    delay(500.milliseconds) // Wait before retrying
                }
            }
        }
    }

    // Must only run while ioMutex is held. Mode 01 PID 01 gives the MIL (check-engine light)
    // status and stored-DTC count cheaply; Mode 03 (the actual codes) is only requested when
    // that count says there's something to fetch.
    private suspend fun pollDtcStatus() {
        sendCommand("ATSH7E0")
        delay(50.milliseconds)

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

    // One full read cycle across both known headers. Must only run while ioMutex is held.
    private suspend fun pollOnce(): Map<String, Int> {
        // --- Engine Data (Header 7E0) ---
        sendCommand("ATSH7E0")
        delay(50.milliseconds)

        sendCommand("010C")
        val rpm = parseRPM(readResponse())

        sendCommand("010D")
        val speed = parseSpeed(readResponse())

        sendCommand("2243F7")
        val gear = parseGear(readResponse())

        sendCommand("0111")
        val throttle = parseThrottle(readResponse())

        // --- ABS/IMU Data (Header 7E1) ---
        sendCommand("ATSH7E1")
        delay(50.milliseconds)

        sendCommand("222B05")
        val brakeFront = parseBrake(readResponse(), "622B05")

        sendCommand("222B06")
        val brakeRear = parseBrake(readResponse(), "622B06")

        sendCommand("22D10D")
        val leanBike = parseLeanBike(readResponse())

        // --- Coolant & Odometer (Standard & UDS) ---
        sendCommand("0105")
        val coolant = parseCoolant(readResponse())

        sendCommand("222503")
        val odometer = parseOdometer(readResponse())

        // --- Fuel Data ---
        sendCommand("015E")
        val fuelRate = parseFuelRate(readResponse())

        sendCommand("012F")
        val fuelLevel = parseFuelLevel(readResponse())

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
            "FUEL_RATE" to (fuelRate * 100).toInt(), // Scale for Map
            "FUEL_LEVEL" to fuelLevel
        )
    }

    // Sweeps a range of CAN diagnostic headers and UDS "read data by identifier" (service 22)
    // DIDs, so an unknown value (e.g. fuel level, which isn't exposed via any standard PID on
    // this ECU) can be hunted for by hand: run this, then compare which (header, DID) pairs
    // return a plausible value against a known real-world state (e.g. right after a fill-up).
    suspend fun sweepHeadersAndDids(
        headers: List<String> = DEFAULT_SWEEP_HEADERS,
        dids: List<String> = DEFAULT_SWEEP_DIDS
    ) {
        if (!_isConnected.value) return
        _sweepRunning.value = true
        _sweepResults.value = emptyList()
        try {
            withContext(Dispatchers.IO) {
                ioMutex.withLock {
                    for (header in headers) {
                        sendCommand("ATSH$header")
                        delay(50.milliseconds)
                        readResponse()

                        for (did in dids) {
                            sendCommand("22$did")
                            delay(80.milliseconds)
                            val response = readResponse()
                            _sweepResults.value = _sweepResults.value + ObdSweepEntry(header, did, response)
                        }
                    }
                    // Restore the header the normal polling loop expects.
                    sendCommand("ATSH7E0")
                    delay(50.milliseconds)
                    readResponse()
                }
            }
        } finally {
            _sweepRunning.value = false
        }
    }

    private fun parseCoolant(response: String): Int {
        // 41 05 XX -> XX - 40
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("4105")) {
                val hex = clean.substringAfter("4105").take(2)
                Integer.parseInt(hex, 16) - 40
            } else 0
        } catch (_: Exception) { 0 }
    }

    private fun parseOdometer(response: String): Long {
        // 62 25 03 AA BB CC DD -> (AA*2^24 + BB*2^16 + CC*256 + DD)
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("622503")) {
                val hex = clean.substringAfter("622503").take(8)
                java.lang.Long.parseLong(hex, 16)
            } else 0L
        } catch (_: Exception) { 0L }
    }

    private fun parseFuelRate(response: String): Float {
        // 41 5E AA BB -> (AA*256 + BB) / 20
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("415E")) {
                val hex = clean.substringAfter("415E").take(4)
                Integer.parseInt(hex, 16) / 20f
            } else 0f
        } catch (_: Exception) { 0f }
    }

    private fun parseFuelLevel(response: String): Int {
        // 41 2F XX -> XX * 100 / 255
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("412F")) {
                val hex = clean.substringAfter("412F").take(2)
                (Integer.parseInt(hex, 16) * 100) / 255
            } else 0
        } catch (_: Exception) { 0 }
    }

    internal fun parseLeanBike(response: String): Int {
        // 62 D1 0D XX YY -> Signed 16-bit integer
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("62D10D")) {
                val hex = clean.substringAfter("62D10D").take(4)
                val raw = Integer.parseInt(hex, 16).toShort().toInt()
                // Genelde 0.1 çarpanı ile dereceye çevrilir
                (raw * 0.1).toInt()
            } else 0
        } catch (_: Exception) { 0 }
    }

    internal fun parseRPM(response: String): Int {
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("410C")) {
                val hex = clean.substringAfter("410C").take(4)
                Integer.parseInt(hex, 16) / 4
            } else 0
        } catch (_: Exception) { 0 }
    }

    internal fun parseSpeed(response: String): Int {
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("410D")) {
                val hex = clean.substringAfter("410D").take(2)
                Integer.parseInt(hex, 16)
            } else 0
        } catch (_: Exception) { 0 }
    }

    internal fun parseGear(response: String): Int {
        // 62 43 F7 XX -> XX is the gear
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("6243F7")) {
                val hex = clean.substringAfter("6243F7").take(2)
                val rawGear = Integer.parseInt(hex, 16)
                // BMW BMS-O genelde 0=N, 1-6=Gears. Bazı durumlarda 15=N.
                if (rawGear == 15) 0 else rawGear
            } else 0
        } catch (_: Exception) { 0 }
    }

    internal fun parseThrottle(response: String): Int {
        // 41 11 XX -> XX * 100 / 255
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("4111")) {
                val hex = clean.substringAfter("4111").take(2)
                (Integer.parseInt(hex, 16) * 100) / 255
            } else 0
        } catch (_: Exception) { 0 }
    }

    // 41 01 XX ... -> bit 7 of XX is the MIL (check-engine light) state, bits 6-0 are the
    // stored-DTC count.
    internal fun parseMonitorStatus(response: String): Pair<Boolean, Int> {
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("4101")) {
                val statusByte = Integer.parseInt(clean.substringAfter("4101").take(2), 16)
                ((statusByte and 0x80) != 0) to (statusByte and 0x7F)
            } else {
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
        // Response format: 62 [PID] [Value]
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains(prefix)) {
                val hex = clean.substringAfter(prefix).take(2)
                // BMW fren basıncı için genelde bar cinsinden veri döner.
                // Basit bir ölçekleme yapıyoruz.
                Integer.parseInt(hex, 16)
            } else 0
        } catch (_: Exception) { 0 }
    }

    fun disconnect() {
        try {
            _isConnected.value = false
            socket?.close()
            _milOn.value = false
            _dtcCodes.value = emptyList()
            Log.d(tag, "OBD2 Bağlantısı kesildi.")
        } catch (e: IOException) {
            Log.e(tag, "Kapatma hatası: ${e.message}")
        }
    }

    companion object {
        private const val PREFS_NAME = "obd_prefs"
        private const val KEY_DEVICE_ADDRESS = "device_address"
        private const val DTC_POLL_INTERVAL_TICKS = 50 // ~5s at the 100ms data-loop cadence

        // Standard 11-bit CAN diagnostic request headers (7E0-7E7); 7E0/7E1 are the two
        // already known to answer (engine ECU / ABS-IMU), the rest are unconfirmed.
        val DEFAULT_SWEEP_HEADERS = (0x7E0..0x7E7).map { it.toString(16).uppercase() }

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
