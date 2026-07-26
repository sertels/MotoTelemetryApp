package com.example.mototelemetryapp.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class RestoreMode { REPLACE, MERGE }

private const val TAG = "BackupRestore"

// Reads a downloaded backup .db file directly via raw SQLite (it's a standalone copy of the
// same schema, not the live Room-managed database) and writes its contents into the current
// database through the normal DAO, either wiping local data first (REPLACE) or skipping
// sessions that already exist locally by start time (MERGE).
suspend fun restoreFromBackupFile(context: Context, backupFile: File, mode: RestoreMode): Boolean =
    withContext(Dispatchers.IO) {
        var backupDb: SQLiteDatabase? = null
        try {
            backupDb = SQLiteDatabase.openDatabase(backupFile.path, null, SQLiteDatabase.OPEN_READONLY)
            val backupSessions = readSessions(backupDb)
            val recordsBySessionId = readRecordsBySessionId(backupDb)

            val dao = AppDatabase.getDatabase(context).telemetryDao()

            if (mode == RestoreMode.REPLACE) {
                dao.deleteAllSessions()
            }

            val existingStartTimes = if (mode == RestoreMode.MERGE) {
                dao.getAllSessionStartTimes().toMutableSet()
            } else {
                mutableSetOf()
            }

            for ((oldSessionId, session) in backupSessions) {
                if (mode == RestoreMode.MERGE && session.startTime in existingStartTimes) {
                    continue
                }

                val newSessionId = dao.insertSession(session.copy(id = 0))
                existingStartTimes += session.startTime

                val records = recordsBySessionId[oldSessionId].orEmpty()
                    .map { it.copy(id = 0, sessionId = newSessionId) }
                if (records.isNotEmpty()) {
                    dao.insertRecords(records)
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed: ${e.message}", e)
            false
        } finally {
            backupDb?.close()
            backupFile.delete()
        }
    }

private fun readSessions(db: SQLiteDatabase): Map<Long, Session> {
    val sessions = mutableMapOf<Long, Session>()
    db.rawQuery("SELECT * FROM sessions", null).use { cursor ->
        while (cursor.moveToNext()) {
            val id = cursor.getLong("id")
            sessions[id] = Session(
                id = id,
                name = cursor.getString("name"),
                startTime = cursor.getLong("startTime"),
                endTime = if (cursor.isNull(cursor.getColumnIndexOrThrow("endTime"))) null else cursor.getLong("endTime"),
                totalDistanceBikeKm = cursor.getFloat("totalDistanceBikeKm"),
                totalDistanceGpsKm = cursor.getFloat("totalDistanceGpsKm"),
                maxSpeed = cursor.getInt("maxSpeed"),
                maxLeanLeft = cursor.getFloat("maxLeanLeft"),
                maxLeanRight = cursor.getFloat("maxLeanRight"),
                maxCoolantTemp = cursor.getInt("maxCoolantTemp"),
                startOdometer = cursor.getLong("startOdometer"),
                endOdometer = cursor.getLong("endOdometer"),
                totalFuelLiters = cursor.getFloat("totalFuelLiters"),
                avgFuelConsumption = cursor.getFloat("avgFuelConsumption")
            )
        }
    }
    return sessions
}

private fun readRecordsBySessionId(db: SQLiteDatabase): Map<Long, List<TelemetryRecord>> {
    val records = mutableMapOf<Long, MutableList<TelemetryRecord>>()
    db.rawQuery("SELECT * FROM telemetry_records", null).use { cursor ->
        while (cursor.moveToNext()) {
            val sessionId = cursor.getLong("sessionId")
            val record = TelemetryRecord(
                sessionId = sessionId,
                timestamp = cursor.getLong("timestamp"),
                speed = cursor.getInt("speed"),
                rpm = cursor.getInt("rpm"),
                gear = cursor.getInt("gear"),
                throttle = cursor.getInt("throttle"),
                brakeFront = cursor.getInt("brakeFront"),
                brakeRear = cursor.getInt("brakeRear"),
                leanAnglePhone = cursor.getFloat("leanAnglePhone"),
                leanAngleBike = cursor.getFloat("leanAngleBike"),
                gForce = cursor.getFloat("gForce"),
                fuelRate = cursor.getFloat("fuelRate"),
                fuelLevel = cursor.getInt("fuelLevel"),
                coolantTemp = cursor.getInt("coolantTemp"),
                altitude = cursor.getDouble("altitude"),
                latitude = cursor.getDouble("latitude"),
                longitude = cursor.getDouble("longitude")
            )
            records.getOrPut(sessionId) { mutableListOf() }.add(record)
        }
    }
    return records
}

private fun Cursor.getLong(column: String) = getLong(getColumnIndexOrThrow(column))
private fun Cursor.getInt(column: String) = getInt(getColumnIndexOrThrow(column))
private fun Cursor.getFloat(column: String) = getFloat(getColumnIndexOrThrow(column))
private fun Cursor.getDouble(column: String) = getDouble(getColumnIndexOrThrow(column))
private fun Cursor.getString(column: String): String = getString(getColumnIndexOrThrow(column))
