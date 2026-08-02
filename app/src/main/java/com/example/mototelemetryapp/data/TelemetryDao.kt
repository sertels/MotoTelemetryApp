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

    // Sessions left behind when the service was killed before onDestroy() could finalize them.
    @Query("SELECT * FROM sessions WHERE endTime IS NULL")
    suspend fun getOpenSessions(): List<Session>

    @Query("SELECT startTime FROM sessions")
    suspend fun getAllSessionStartTimes(): List<Long>

    // Telemetry
    @Insert
    suspend fun insertRecord(record: TelemetryRecord)

    @Insert
    suspend fun insertRecords(records: List<TelemetryRecord>)

    @Query("SELECT * FROM telemetry_records WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getRecordsForSession(sessionId: Long): Flow<List<TelemetryRecord>>

    // Lighter than getRecordsForSession for the Analysis session list, where every visible
    // SessionCard needs only these two things (a sparkline + a max-RPM stat) but was fetching and
    // mapping every column of every row (up to ~18k on a real ride) just to get them - see the
    // 2026-08-02 perf pass on AnalysisScreen/HistoryScreen.
    @Query("SELECT speed FROM telemetry_records WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getSpeedsForSession(sessionId: Long): Flow<List<Int>>

    @Query("SELECT MAX(rpm) FROM telemetry_records WHERE sessionId = :sessionId")
    fun getMaxRpmForSession(sessionId: Long): Flow<Int?>

    @Query("SELECT * FROM telemetry_records WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getRecordsForSessionOnce(sessionId: Long): List<TelemetryRecord>

    @Query("SELECT * FROM telemetry_records WHERE sessionId = (SELECT id FROM sessions ORDER BY startTime DESC LIMIT 1) ORDER BY timestamp ASC")
    fun getLatestSessionRecords(): Flow<List<TelemetryRecord>>

    @Query("SELECT * FROM telemetry_records ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastRecord(): TelemetryRecord?

    @Query("SELECT SUM(totalDistanceGpsKm) FROM sessions")
    suspend fun getTotalDistanceKm(): Float?

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()
}
