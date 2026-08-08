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

    companion object {
        // Visible-folder name in the user's own My Drive, distinct from the hidden appDataFolder
        // the ride-DB backup below uses. appDataFolder is invisible to anything but this app
        // itself (not even browsable at drive.google.com), which defeats the point of a remote
        // diagnostics handoff - the whole reason to upload these files is so they can be read
        // from somewhere that isn't this phone.
        private const val DIAGNOSTICS_FOLDER_NAME = "MotoTelemetryApp Diagnostics"

        // Diagnostic.log is a rolling file DiagnosticLog keeps appending to for as long as the
        // app runs, so re-uploading it each time is meaningful - it should overwrite, not
        // duplicate. Every other file here (CAN monitor captures, OBD sweep exports) is a
        // one-shot timestamped export that never changes once written.
        private const val ROLLING_LOG_NAME = "diagnostic.log"
    }

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

    // drive.file scope only ever sees files/folders this app itself created, so searching by
    // name+mimeType here is safe from colliding with an unrelated folder of the same name - and
    // necessary so repeated uploads land in one folder instead of a new one each time.
    private suspend fun findOrCreateDiagnosticsFolder(service: Drive): String? = withContext(Dispatchers.IO) {
        try {
            val existing = service.files().list()
                .setQ("name = '$DIAGNOSTICS_FOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
                .setSpaces("drive")
                .setFields("files(id)")
                .execute()
            existing.files?.firstOrNull()?.id?.let { return@withContext it }

            val metadata = com.google.api.services.drive.model.File()
            metadata.name = DIAGNOSTICS_FOLDER_NAME
            metadata.mimeType = "application/vnd.google-apps.folder"
            service.files().create(metadata).setFields("id").execute().id
        } catch (e: Exception) {
            Log.e(tag, "Failed to find/create diagnostics folder: ${e.message}", e)
            null
        }
    }

    // Uploads whatever's currently in the diagnostics dir (diagnostic.log, CAN monitor captures,
    // OBD sweep exports - whatever ObdSweepExport/DiagnosticLog have written) to a visible Drive
    // folder, so a real-world test can be reviewed remotely without the phone ever being plugged
    // into the machine doing the reviewing. Re-uploading everything on every call would just
    // duplicate every already-uploaded capture (Drive doesn't enforce unique names within a
    // folder, unlike a filesystem) - checks what's already there by name first, skips timestamped
    // exports that are already up, and updates (not duplicates) the rolling log in place. Returns
    // how many files were actually sent so the caller can tell "nothing new" from "upload failed".
    suspend fun uploadDiagnostics(accessToken: String, files: List<File>): Int = withContext(Dispatchers.IO) {
        if (files.isEmpty()) return@withContext 0
        try {
            val service = driveService(accessToken)
            val folderId = findOrCreateDiagnosticsFolder(service) ?: return@withContext 0

            val existingByName = service.files().list()
                .setQ("'$folderId' in parents and trashed = false")
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute()
                .files.orEmpty()
                .associate { it.name to it.id }

            var uploaded = 0
            for (file in files) {
                val existingId = existingByName[file.name]
                if (existingId != null && file.name != ROLLING_LOG_NAME) continue // already up, immutable
                try {
                    val content = FileContent("text/plain", file)
                    if (existingId != null) {
                        service.files().update(existingId, com.google.api.services.drive.model.File(), content).execute()
                    } else {
                        val metadata = com.google.api.services.drive.model.File()
                        metadata.name = file.name
                        metadata.parents = Collections.singletonList(folderId)
                        service.files().create(metadata, content).execute()
                    }
                    uploaded++
                } catch (e: Exception) {
                    Log.e(tag, "Failed to upload ${file.name}: ${e.message}", e)
                }
            }
            uploaded
        } catch (e: Exception) {
            Log.e(tag, "Diagnostics upload failed: ${e.message}", e)
            0
        }
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
