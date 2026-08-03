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

// Only the columns every schema version ever had (identity + time) are read strictly; every
// other column tolerates being absent, so restoring a backup taken on an older schema degrades
// to defaults for the missing fields instead of failing the entire restore over one column.
private fun readSessions(db: SQLiteDatabase): Map<Long, Session> {
    val sessions = mutableMapOf<Long, Session>()
    db.rawQuery("SELECT * FROM sessions", null).use { cursor ->
        while (cursor.moveToNext()) {
            val id = cursor.getLong("id")
            sessions[id] = Session(
                id = id,
                name = cursor.getString("name"),
                startTime = cursor.getLong("startTime"),
                endTime = cursor.getNullableLongOrDefault("endTime", null),
                totalDistanceBikeKm = cursor.getFloatOrDefault("totalDistanceBikeKm", 0f),
                totalDistanceGpsKm = cursor.getFloatOrDefault("totalDistanceGpsKm", 0f),
                maxSpeed = cursor.getIntOrDefault("maxSpeed", 0),
                maxLeanLeft = cursor.getFloatOrDefault("maxLeanLeft", 0f),
                maxLeanRight = cursor.getFloatOrDefault("maxLeanRight", 0f),
                maxCoolantTemp = cursor.getIntOrDefault("maxCoolantTemp", 0),
                maxGForceLat = cursor.getFloatOrDefault("maxGForceLat", 0f),
                maxGForceLon = cursor.getFloatOrDefault("maxGForceLon", 0f),
                startOdometer = cursor.getLongOrDefault("startOdometer", 0L),
                endOdometer = cursor.getLongOrDefault("endOdometer", 0L),
                totalFuelLiters = cursor.getFloatOrDefault("totalFuelLiters", 0f),
                avgFuelConsumption = cursor.getFloatOrDefault("avgFuelConsumption", 0f)
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
                speed = cursor.getIntOrDefault("speed", 0),
                rpm = cursor.getIntOrDefault("rpm", 0),
                gear = cursor.getIntOrDefault("gear", 0),
                throttle = cursor.getIntOrDefault("throttle", 0),
                brakeFront = cursor.getIntOrDefault("brakeFront", 0),
                brakeRear = cursor.getIntOrDefault("brakeRear", 0),
                leanAnglePhone = cursor.getFloatOrDefault("leanAnglePhone", 0f),
                leanAngleBike = cursor.getFloatOrDefault("leanAngleBike", 0f),
                gForceLat = cursor.getFloatOrDefault("gForceLat", 0f),
                gForceLon = cursor.getFloatOrDefault("gForceLon", 0f),
                fuelRate = cursor.getFloatOrDefault("fuelRate", 0f),
                fuelLevel = cursor.getIntOrDefault("fuelLevel", 0),
                coolantTemp = cursor.getIntOrDefault("coolantTemp", 0),
                altitude = cursor.getDoubleOrDefault("altitude", 0.0),
                latitude = cursor.getDoubleOrDefault("latitude", 0.0),
                longitude = cursor.getDoubleOrDefault("longitude", 0.0)
            )
            records.getOrPut(sessionId) { mutableListOf() }.add(record)
        }
    }
    return records
}

private fun Cursor.getLong(column: String) = getLong(getColumnIndexOrThrow(column))
private fun Cursor.getString(column: String): String = getString(getColumnIndexOrThrow(column))

// For columns added after older backups were already taken - getColumnIndexOrThrow would fail
// the whole restore over one missing column, so these fall back to a default instead.
private fun Cursor.getFloatOrDefault(column: String, default: Float): Float {
    val index = getColumnIndex(column)
    return if (index == -1) default else getFloat(index)
}

private fun Cursor.getIntOrDefault(column: String, default: Int): Int {
    val index = getColumnIndex(column)
    return if (index == -1) default else getInt(index)
}

private fun Cursor.getLongOrDefault(column: String, default: Long): Long {
    val index = getColumnIndex(column)
    return if (index == -1) default else getLong(index)
}

private fun Cursor.getDoubleOrDefault(column: String, default: Double): Double {
    val index = getColumnIndex(column)
    return if (index == -1) default else getDouble(index)
}

// endTime is genuinely nullable in the schema (an unfinalized ride), distinct from the
// column merely being absent in an old backup - both degrade to the default here.
private fun Cursor.getNullableLongOrDefault(column: String, default: Long?): Long? {
    val index = getColumnIndex(column)
    return if (index == -1 || isNull(index)) default else getLong(index)
}
