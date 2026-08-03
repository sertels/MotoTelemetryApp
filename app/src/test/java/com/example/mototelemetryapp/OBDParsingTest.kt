package com.example.mototelemetryapp

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

class OBDParsingTest {

    @Mock
    private lateinit var mockContext: Context

    private lateinit var obdManager: BluetoothOBDManager

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        // These tests only exercise the pure parse* helpers, so the data-loop scope is never
        // started - any scope will do.
        obdManager = BluetoothOBDManager(mockContext, CoroutineScope(Dispatchers.Unconfined))
    }

    @Test
    fun testRPMParsing() {
        // Example response: "41 0C 1A F8" -> (1A*256 + F8) / 4 = 1726
        val response = "41 0C 1A F8"
        val rpm = obdManager.parseRPM(response)
        assertEquals(1726, rpm)
    }

    @Test
    fun testSpeedParsing() {
        // Example response: "41 0D 32" -> 32 hex = 50 km/h
        val response = "41 0D 32"
        val speed = obdManager.parseSpeed(response)
        assertEquals(50, speed)
    }

    @Test
    fun testGearParsing() {
        // Example response: "62 43 F7 03" -> Gear 3
        val response = "62 43 F7 03"
        val gear = obdManager.parseGear(response)
        assertEquals(3, gear)
        
        // Neutral test: "62 43 F7 0F" (15 = Neutral in some BMWs)
        assertEquals(0, obdManager.parseGear("62 43 F7 0F"))
    }

    @Test
    fun testThrottleParsing() {
        // Example response: "41 11 7F" -> approx 50%
        val response = "41 11 7F"
        val throttle = obdManager.parseThrottle(response)
        assertEquals(49, throttle) // (127 * 100) / 255 = 49.8 -> 49
    }

    @Test
    fun testBrakeParsing() {
        // Example response: "62 2B 05 1E" -> 30 bar
        val response = "62 2B 05 1E"
        val brake = obdManager.parseBrake(response, "622B05")
        assertEquals(30, brake)
    }

    @Test
    fun testLeanBikeParsing() {
        // Example response: "62 D1 0D 01 F4" -> 500 * 0.1 = 50 degrees
        val response = "62 D1 0D 01 F4"
        val lean = obdManager.parseLeanBike(response)
        assertEquals(50, lean)
    }

    // --- Mode 03 stored DTCs. On CAN the first data byte after 43 is the DTC COUNT, not part
    // of the first code - the regression this guards against decoded "43 01 01 23" as P0101.

    @Test
    fun testDtcParsingSingleCode() {
        assertEquals(listOf("P0123"), obdManager.parseDtcCodes("43 01 01 23"))
    }

    @Test
    fun testDtcParsingTwoCodes() {
        assertEquals(listOf("P0143", "P0196"), obdManager.parseDtcCodes("43 02 01 43 01 96"))
    }

    @Test
    fun testDtcParsingLetterPrefixes() {
        // Top 2 bits of the first byte pick the letter: 01xx xxxx = C, 10 = B, 11 = U.
        assertEquals(
            listOf("C0300", "B1301", "U0107"),
            obdManager.parseDtcCodes("43 03 43 00 93 01 C1 07")
        )
    }

    @Test
    fun testDtcParsingNoCodes() {
        assertEquals(emptyList<String>(), obdManager.parseDtcCodes("43 00"))
        assertEquals(emptyList<String>(), obdManager.parseDtcCodes("NO DATA"))
        assertEquals(emptyList<String>(), obdManager.parseDtcCodes(""))
    }

    @Test
    fun testDtcParsingMultiFrame() {
        // 3+ DTCs arrive as an ISO-TP multi-frame reply: a 3-hex-digit total-length line, then
        // "N:"-indexed data lines. Count byte says 4, trailing 00 00 is padding.
        val response = "00A\r0: 43 04 01 43 01 96\r1: 02 34 02 44 00 00"
        assertEquals(
            listOf("P0143", "P0196", "P0234", "P0244"),
            obdManager.parseDtcCodes(response)
        )
    }

    // --- Mode 01 PID 01: bit 7 = MIL (check-engine light), bits 6-0 = stored-DTC count.

    @Test
    fun testMonitorStatusMilOn() {
        val (milOn, count) = obdManager.parseMonitorStatus("41 01 82 07 65 04")
        assertEquals(true, milOn)
        assertEquals(2, count)
    }

    @Test
    fun testMonitorStatusMilOff() {
        val (milOn, count) = obdManager.parseMonitorStatus("41 01 00 07 65 04")
        assertEquals(false, milOn)
        assertEquals(0, count)
    }
}
