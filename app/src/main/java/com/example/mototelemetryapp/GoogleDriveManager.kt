package com.example.mototelemetryapp

import android.content.Context
import android.util.Log
import com.google.api.client.http.FileContent
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

data class DriveBackupEntry(val fileId: String, val name: String, val createdTimeMillis: Long)

class GoogleDriveManager(private val context: Context) {

    private val tag = "GoogleDriveManager"

    // The Credential Manager / Authorization API sign-in flow already hands us a bearer access
    // token directly - build the Drive client from that instead of the legacy
    // GoogleAccountCredential path, which expects a classic AccountManager-registered account
    // and a separate consent grant that this app never goes through.
    private fun driveService(accessToken: String): Drive {
        val requestInitializer = HttpRequestInitializer { request ->
            request.headers.authorization = "Bearer $accessToken"
        }
        return Drive.Builder(NetHttpTransport(), GsonFactory(), requestInitializer)
            .setApplicationName("Moto Telemetry App")
            .build()
    }

    suspend fun uploadDatabase(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val googleDriveService = driveService(accessToken)

            // Room defaults to WAL journal mode, so recent writes can still be sitting in the
            // -wal file rather than the main .db file; checkpoint first so the backup is complete.
            com.example.mototelemetryapp.data.AppDatabase.checkpoint(context)

            val dbFile = context.getDatabasePath("telemetry_database")
            if (!dbFile.exists()) {
                Log.e(tag, "Database file not found.")
                return@withContext false
            }

            val metadata = com.google.api.services.drive.model.File()
            metadata.name = "telemetry_backup_${System.currentTimeMillis()}.db"
            metadata.parents = Collections.singletonList("appDataFolder")

            val content = FileContent("application/x-sqlite3", dbFile)
            googleDriveService.files().create(metadata, content).execute()

            Log.d(tag, "Backup successfully completed.")
            return@withContext true
        } catch (e: Exception) {
            Log.e(tag, "Backup error: ${e.message}", e)
            false
        }
    }

    // Null means the list couldn't be fetched (distinct from a successful fetch that found
    // zero backups) - the caller needs to tell those two states apart in the UI.
    suspend fun listBackups(accessToken: String): List<DriveBackupEntry>? = withContext(Dispatchers.IO) {
        try {
            val googleDriveService = driveService(accessToken)
            val result = googleDriveService.files().list()
                .setSpaces("appDataFolder")
                .setFields("files(id, name, createdTime)")
                .setOrderBy("createdTime desc")
                .execute()

            result.files.orEmpty().map { file ->
                DriveBackupEntry(
                    fileId = file.id,
                    name = file.name,
                    createdTimeMillis = file.createdTime?.value ?: 0L
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to list backups: ${e.message}", e)
            null
        }
    }

    // Downloads the chosen backup into the app's cache dir so it can be read as a standalone
    // SQLite file without touching the live (currently open) Room database.
    suspend fun downloadBackup(accessToken: String, fileId: String): File? = withContext(Dispatchers.IO) {
        try {
            val googleDriveService = driveService(accessToken)
            val destination = File(context.cacheDir, "restore_$fileId.db")
            destination.outputStream().use { out ->
                googleDriveService.files().get(fileId).executeMediaAndDownloadTo(out)
            }
            destination
        } catch (e: Exception) {
            Log.e(tag, "Failed to download backup: ${e.message}", e)
            null
        }
    }
}
