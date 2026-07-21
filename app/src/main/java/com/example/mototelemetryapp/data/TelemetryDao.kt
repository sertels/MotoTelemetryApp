package com.example.mototelemetryapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TelemetryDao {
    @Insert
    suspend fun insertRecord(record: TelemetryRecord)

    @Query("SELECT * FROM telemetry_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<TelemetryRecord>>

    @Query("DELETE FROM telemetry_records")
    suspend fun deleteAll()
}
