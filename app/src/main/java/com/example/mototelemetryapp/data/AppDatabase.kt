package com.example.mototelemetryapp.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import java.io.IOException

// DB_VERSION below must be kept in sync with this annotation's version - Room's @Database
// annotation isn't retained at runtime, so it can't be read back reflectively to compare
// against the on-disk version.
@Database(entities = [Session::class, TelemetryRecord::class], version = 8, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun telemetryDao(): TelemetryDao

    companion object {
        private const val DB_NAME = "telemetry_database"
        private const val DB_VERSION = 8
        private const val TAG = "AppDatabase"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                backupBeforeDestructiveMigrationIfNeeded(context)
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                .fallbackToDestructiveMigration() // Geliştirme aşamasında şema değişiminde tabloyu silip tekrar oluşturur
                .build()
                INSTANCE = instance
                instance
            }
        }

        // There are no real Migration objects for any past version bump, so a schema change on
        // an existing install just wipes all ride history with no warning. We can't retroactively
        // write correct migrations for versions we have no schema history for, but we can at
        // least snapshot the raw db file before Room drops it, so a ride history isn't
        // unrecoverably gone - just needs manual recovery from app-private storage.
        private fun backupBeforeDestructiveMigrationIfNeeded(context: Context) {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists()) return

            val onDiskVersion = try {
                SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
            } catch (e: Exception) {
                Log.e(TAG, "Could not read existing db version before migration check", e)
                return
            }
            if (onDiskVersion == DB_VERSION) return

            try {
                val backupDir = File(context.filesDir, "db_backups").apply { mkdirs() }
                val backupFile = File(backupDir, "${DB_NAME}_v${onDiskVersion}_${System.currentTimeMillis()}.db")
                dbFile.copyTo(backupFile, overwrite = true)
                Log.i(TAG, "Backed up v$onDiskVersion database to $backupFile before destructive migration")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to back up database before destructive migration", e)
            }
        }

        // Forces any writes still sitting in the WAL file into the main .db file - needed before
        // copying the db file directly (e.g. for a backup), since WAL is Room's default journal mode.
        fun checkpoint(context: Context) {
            getDatabase(context).openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        }
    }
}
