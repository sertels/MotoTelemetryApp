package com.example.mototelemetryapp

import android.accounts.Account
import android.content.Context
import android.util.Log
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections

class GoogleDriveManager(private val context: Context) {

    private val tag = "GoogleDriveManager"

    suspend fun uploadDatabase(account: Account): Boolean = withContext(Dispatchers.IO) {
        try {
            val credential = GoogleAccountCredential.usingOAuth2(
                context, Collections.singleton(DriveScopes.DRIVE_APPDATA)
            )
            credential.selectedAccount = account

            val googleDriveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory(),
                credential
            ).setApplicationName("Moto Telemetry App").build()

            // Room database file
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
            Log.e(tag, "Backup error: ${e.message}")
            false
        }
    }
}
