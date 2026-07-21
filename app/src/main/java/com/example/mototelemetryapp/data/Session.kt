package com.example.mototelemetryapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startTime: Long,
    val endTime: Long? = null,
    val totalDistanceBikeKm: Float = 0f,
    val totalDistanceGpsKm: Float = 0f,
    val maxSpeed: Int = 0,
    val maxLeanLeft: Float = 0f,
    val maxLeanRight: Float = 0f,
    val maxCoolantTemp: Int = 0,
    val startOdometer: Long = 0,
    val endOdometer: Long = 0,
    val totalFuelLiters: Float = 0f,
    val avgFuelConsumption: Float = 0f
)
