package com.example.mototelemetryapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Session::class, TelemetryRecord::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun telemetryDao(): TelemetryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "telemetry_database"
                )
                .fallbackToDestructiveMigration() // Geliştirme aşamasında şema değişiminde tabloyu silip tekrar oluşturur
                .build()
                INSTANCE = instance
                instance
            }
        }

        // Forces any writes still sitting in the WAL file into the main .db file - needed before
        // copying the db file directly (e.g. for a backup), since WAL is Room's default journal mode.
        fun checkpoint(context: Context) {
            getDatabase(context).openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        }
    }
}
