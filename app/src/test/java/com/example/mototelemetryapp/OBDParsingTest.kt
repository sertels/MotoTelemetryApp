package com.example.mototelemetryapp

import android.content.Context
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
        obdManager = BluetoothOBDManager(mockContext)
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
}
