package com.example.mototelemetryapp.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TelemetryDao {
    // Sessions
    @Insert
    suspend fun insertSession(session: Session): Long

    @Update
    suspend fun updateSession(session: Session)

    // Cascades to that session's telemetry_records via the FK's onDelete = CASCADE.
    @Delete
    suspend fun deleteSession(session: Session)

    @Query("SELECT * FROM sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<Session>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: Long): Session?

    // Telemetry
    @Insert
    suspend fun insertRecord(record: TelemetryRecord)

    @Query("SELECT * FROM telemetry_records WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getRecordsForSession(sessionId: Long): Flow<List<TelemetryRecord>>

    @Query("SELECT * FROM telemetry_records WHERE sessionId = (SELECT id FROM sessions ORDER BY startTime DESC LIMIT 1) ORDER BY timestamp ASC")
    fun getLatestSessionRecords(): Flow<List<TelemetryRecord>>

    @Query("SELECT * FROM telemetry_records ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastRecord(): TelemetryRecord?

    @Query("SELECT SUM(totalDistanceGpsKm) FROM sessions")
    suspend fun getTotalDistanceKm(): Float?

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()
}
