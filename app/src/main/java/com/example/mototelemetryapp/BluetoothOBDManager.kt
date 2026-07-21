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
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothOBDManager(private val context: Context) {

    private val TAG = "BluetoothOBDManager"
    private val OBD_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _obdData = MutableStateFlow<Map<String, Int>>(emptyMap())
    val obdData = _obdData.asStateFlow()

    @SuppressLint("MissingPermission")
    suspend fun connect(deviceName: String = "OBDII"): Boolean = withContext(Dispatchers.IO) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter

        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth kapalı veya desteklenmiyor.")
            return@withContext false
        }

        val pairedDevices: Set<BluetoothDevice> = adapter.bondedDevices
        val obdDevice = pairedDevices.find { it.name.contains(deviceName, ignoreCase = true) }

        if (obdDevice == null) {
            Log.e(TAG, "Eşleşmiş OBD2 cihazı bulunamadı.")
            return@withContext false
        }

        try {
            socket = obdDevice.createRfcommSocketToServiceRecord(OBD_UUID)
            socket?.connect()
            outputStream = socket?.outputStream
            inputStream = socket?.inputStream
            
            if (initELM327()) {
                _isConnected.value = true
                Log.d(TAG, "OBD2 Bağlantısı Başarılı.")
                startDataLoop()
                return@withContext true
            }
        } catch (e: IOException) {
            Log.e(TAG, "Bağlantı hatası: ${e.message}")
            disconnect()
        }
        return@withContext false
    }

    private suspend fun initELM327(): Boolean {
        val commands = listOf("ATZ", "ATE0", "ATL0", "ATSP0")
        for (cmd in commands) {
            sendCommand(cmd)
            val response = readResponse()
            Log.d(TAG, "Command: $cmd, Response: $response")
            delay(200)
        }
        return true
    }

    private fun sendCommand(cmd: String) {
        try {
            outputStream?.write((cmd + "\r").toByteArray())
            outputStream?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Komut gönderme hatası: ${e.message}")
        }
    }

    private fun readResponse(): String {
        val buffer = ByteArray(1024)
        var bytes: Int
        val response = StringBuilder()
        try {
            // Basit bir okuma mantığı - ELM327 yanıtı '>' ile bitirir
            while (true) {
                bytes = inputStream?.read(buffer) ?: -1
                if (bytes == -1) break
                val part = String(buffer, 0, bytes)
                response.append(part)
                if (part.contains(">")) break
            }
        } catch (e: IOException) {
            Log.e(TAG, "Okuma hatası: ${e.message}")
        }
        return response.toString().trim().replace(">", "")
    }

    private suspend fun startDataLoop() {
        withContext(Dispatchers.IO) {
            while (_isConnected.value) {
                // --- Engine Data (Header 7E0) ---
                sendCommand("ATSH7E0")
                delay(50)
                
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
                delay(50)

                sendCommand("222B05")
                val brakeFront = parseBrake(readResponse(), "622B05")

                sendCommand("222B06")
                val brakeRear = parseBrake(readResponse(), "622B06")
                
                sendCommand("22D10D")
                val leanBike = parseLeanBike(readResponse())

                _obdData.value = mapOf(
                    "RPM" to rpm,
                    "SPEED" to speed,
                    "GEAR" to gear,
                    "THROTTLE" to throttle,
                    "BRAKE_FRONT" to brakeFront,
                    "BRAKE_REAR" to brakeRear,
                    "LEAN_BIKE" to leanBike
                )
                
                delay(100)
            }
        }
    }

    private fun parseLeanBike(response: String): Int {
        // 62 D1 0D XX YY -> Signed 16-bit integer
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("62D10D")) {
                val hex = clean.substringAfter("62D10D").take(4)
                val raw = Integer.parseInt(hex, 16).toShort().toInt()
                // Genelde 0.1 çarpanı ile dereceye çevrilir
                (raw * 0.1).toInt()
            } else 0
        } catch (e: Exception) { 0 }
    }

    private fun parseRPM(response: String): Int {
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("410C")) {
                val hex = clean.substringAfter("410C").take(4)
                Integer.parseInt(hex, 16) / 4
            } else 0
        } catch (e: Exception) { 0 }
    }

    private fun parseSpeed(response: String): Int {
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("410D")) {
                val hex = clean.substringAfter("410D").take(2)
                Integer.parseInt(hex, 16)
            } else 0
        } catch (e: Exception) { 0 }
    }

    private fun parseGear(response: String): Int {
        // 62 43 F7 XX -> XX is the gear
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("6243F7")) {
                val hex = clean.substringAfter("6243F7").take(2)
                val rawGear = Integer.parseInt(hex, 16)
                // BMW BMS-O genelde 0=N, 1-6=Gears. Bazı durumlarda 15=N.
                if (rawGear == 15) 0 else rawGear
            } else 0
        } catch (e: Exception) { 0 }
    }

    private fun parseThrottle(response: String): Int {
        // 41 11 XX -> XX * 100 / 255
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains("4111")) {
                val hex = clean.substringAfter("4111").take(2)
                (Integer.parseInt(hex, 16) * 100) / 255
            } else 0
        } catch (e: Exception) { 0 }
    }

    private fun parseBrake(response: String, prefix: String): Int {
        // Response format: 62 [PID] [Value]
        return try {
            val clean = response.replace(" ", "")
            if (clean.contains(prefix)) {
                val hex = clean.substringAfter(prefix).take(2)
                // BMW fren basıncı için genelde bar cinsinden veri döner.
                // Basit bir ölçekleme yapıyoruz.
                Integer.parseInt(hex, 16)
            } else 0
        } catch (e: Exception) { 0 }
    }

    fun disconnect() {
        try {
            _isConnected.value = false
            socket?.close()
            Log.d(TAG, "OBD2 Bağlantısı kesildi.")
        } catch (e: IOException) {
            Log.e(TAG, "Kapatma hatası: ${e.message}")
        }
    }
}
