package com.example.mototelemetryapp.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "telemetry_records",
    foreignKeys = [
        ForeignKey(
            entity = Session::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class TelemetryRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,
    val speed: Int,
    val rpm: Int,
    val gear: Int,
    val throttle: Int,
    val brakeFront: Int,
    val brakeRear: Int,
    val leanAnglePhone: Float,
    val leanAngleBike: Float,
    val gForce: Float,
    val fuelRate: Float, // Liters per hour
    val fuelLevel: Int, // Percentage
    val coolantTemp: Int, // Celsius
    val altitude: Double,
    val latitude: Double,
    val longitude: Double
)
