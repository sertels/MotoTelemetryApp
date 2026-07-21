package com.example.mototelemetryapp

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

class GoogleDriveManager(private val context: Context) {

    private val TAG = "GoogleDriveManager"

    fun getGoogleSignInClient(): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    suspend fun uploadDatabase(account: GoogleSignInAccount): Boolean = withContext(Dispatchers.IO) {
        try {
            val credential = GoogleAccountCredential.usingOAuth2(
                context, Collections.singleton(DriveScopes.DRIVE_APPDATA)
            )
            credential.selectedAccount = account.account

            val googleDriveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory(),
                credential
            ).setApplicationName("Moto Telemetry App").build()

            // Room veri tabanı dosyası
            val dbFile = context.getDatabasePath("telemetry_database")
            if (!dbFile.exists()) {
                Log.e(TAG, "Veri tabanı dosyası bulunamadı.")
                return@withContext false
            }

            val metadata = com.google.api.services.drive.model.File()
            metadata.name = "telemetry_backup_${System.currentTimeMillis()}.db"
            metadata.parents = Collections.singletonList("appDataFolder")

            val content = FileContent("application/x-sqlite3", dbFile)
            googleDriveService.files().create(metadata, content).execute()
            
            Log.d(TAG, "Yedekleme başarıyla tamamlandı.")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Yedekleme hatası: ${e.message}")
            false
        }
    }
}
