package com.example.mototelemetryapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "telemetry_records")
data class TelemetryRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    val latitude: Double,
    val longitude: Double
)
