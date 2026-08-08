package com.example.mototelemetryapp

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// A rolling log file that mirrors the app's own error/warning Log calls, so a real-world ride
// with the bike leaves something behind to look at afterwards - Logcat is gone the moment the
// app or phone restarts, which is no good for "what happened out there" after the fact.
//
// Deliberately mirrors android.util.Log's call shape (tag, msg, [throwable]) so existing call
// sites become a one-line swap rather than a restructure.
object DiagnosticLog {

    private const val LOG_FILE_NAME = "diagnostic.log"
    private const val MAX_BYTES = 1_000_000L // ~1MB; trimmed back to half that once exceeded
    private const val TRIM_TO_BYTES = 500_000L

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    // Idempotent and cheap to call from multiple entry points (Service, Activity) - whichever
    // runs first wins, since the file location never varies within one install.
    fun init(context: Context) {
        if (logFile != null) return
        synchronized(this) {
            if (logFile != null) return
            val dir = File(context.applicationContext.filesDir, "diagnostics").apply { mkdirs() }
            logFile = File(dir, LOG_FILE_NAME)
            // This file is in app-private storage and survives `adb install -r` / Play updates,
            // so a single log can span several app versions with no other way to tell which
            // entries came from which build - stamp one marker per process start.
            val (versionName, versionCode) = try {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                info.versionName to info.longVersionCode
            } catch (_: Exception) {
                "?" to -1L
            }
            append("I", "DiagnosticLog", "=== App started: v$versionName ($versionCode) ===", null)
        }
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        Log.e(tag, msg, throwable)
        append("E", tag, msg, throwable)
    }

    fun w(tag: String, msg: String, throwable: Throwable? = null) {
        Log.w(tag, msg, throwable)
        append("W", tag, msg, throwable)
    }

    private fun append(level: String, tag: String, msg: String, throwable: Throwable?) {
        val file = logFile ?: return
        synchronized(this) {
            try {
                if (file.length() > MAX_BYTES) trim(file)
                file.appendText("${timestampFormat.format(Date())} $level/$tag: $msg\n")
                throwable?.let { file.appendText("${Log.getStackTraceString(it)}\n") }
            } catch (_: Exception) {
                // Logging must never be the thing that crashes the app it's trying to explain.
            }
        }
    }

    // Keeps only the tail of the file - the most recent events are what matter after a ride,
    // and an unbounded file would eventually fill up app-private storage.
    private fun trim(file: File) {
        val bytes = file.readBytes()
        if (bytes.size <= TRIM_TO_BYTES) return
        file.writeBytes(bytes.copyOfRange((bytes.size - TRIM_TO_BYTES).toInt(), bytes.size))
    }

    // Every file DiagnosticLog/ObdSweepExport write - diagnostic.log, CAN monitor captures, OBD
    // sweep exports - all share this one directory, so it doubles as "everything worth uploading
    // after a real-world test" without each caller needing to know every export's naming scheme.
    fun diagnosticsFiles(context: Context): List<File> {
        val dir = File(context.applicationContext.filesDir, "diagnostics")
        return dir.listFiles()?.filter { it.isFile }?.toList().orEmpty()
    }

    // Null if init() hasn't run yet or nothing has been logged - callers should treat that as
    // "nothing to share" rather than an error.
    fun shareIntent(context: Context): Intent? {
        val file = logFile?.takeIf { it.exists() && it.length() > 0 } ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
