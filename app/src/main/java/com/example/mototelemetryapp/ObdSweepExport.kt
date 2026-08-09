package com.example.mototelemetryapp

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// A "look for more signals" sweep's results only ever lived in memory before this (gone the
// moment the dialog closed or the app died - see the 2026-07-31 report of a 160-probe sweep
// with nothing left to look at afterwards). This writes the full raw results to a file in the
// same directory DiagnosticLog already shares via FileProvider, so they can be sent off the
// phone the same way the diagnostic log is.
object ObdSweepExport {

    private val fileTimestampFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)

    // Prefixed onto every exported file so a capture pulled off the phone weeks later (e.g. into
    // extra_info/) still says which app build produced it - the filename timestamp alone doesn't.
    private fun versionHeader(context: Context): String {
        val (versionName, versionCode) = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName to info.longVersionCode
        } catch (_: Exception) {
            "?" to -1L
        }
        return "# App v$versionName ($versionCode)\n"
    }

    // kind distinguishes the three sweep entry points sharing this one dialog/export path
    // (standard PID discovery, manufacturer DID sweep, the risky extended-session probe) -
    // without it every exported file was named "obd_sweep_<timestamp>.txt" regardless of which
    // one produced it, so two sweeps run minutes apart were indistinguishable once off the phone.
    // Public (not just shareIntent's private helper) for the same reason saveCanFrames is -
    // results should exist on disk the moment a sweep finishes, not only if the rider remembers
    // to tap Share afterward.
    // Called once by the auto-save right after a sweep finishes, and again if the rider then
    // taps Share on the same in-memory results - without the dedup below that second call wrote
    // a byte-identical file under a new timestamp every time, e.g. 2026-08-09's extended session
    // probe landing on disk twice two seconds apart.
    fun save(context: Context, results: List<ObdSweepEntry>, kind: String): File? {
        if (results.isEmpty()) return null
        val dir = File(context.applicationContext.filesDir, "diagnostics").apply { mkdirs() }
        val body = "# Sweep type: $kind\n" +
            results.joinToString("\n") { "${it.header}/${it.did}: ${it.response}" }
        findLatestWithBody(dir, "obd_sweep_${kind}_", body)?.let { return it }
        val file = File(dir, "obd_sweep_${kind}_${fileTimestampFormat.format(Date())}.txt")
        file.writeText(versionHeader(context) + body)
        return file
    }

    // Content match ignores the version header line so re-running the exact same capture right
    // after a version bump still dedups instead of writing a near-identical file.
    private fun findLatestWithBody(dir: File, filenamePrefix: String, body: String): File? {
        return dir.listFiles { f -> f.name.startsWith(filenamePrefix) }
            ?.maxByOrNull { it.lastModified() }
            ?.takeIf { it.readText().substringAfter("\n") == body }
    }

    fun shareIntent(context: Context, results: List<ObdSweepEntry>, kind: String): Intent? {
        val file = save(context, results, kind) ?: return null
        return shareFile(context, file)
    }

    // Same reasoning as the sweep results above, for a raw ATMA capture (BluetoothOBDManager.
    // startCanMonitor) - one line per captured CAN frame, so it can be shared/diffed off-phone
    // to spot which ID changed while the bike was tilted. Public (not just shareCanFramesIntent's
    // private helper) so a capture can be persisted the moment it finishes, independent of
    // whether the rider ever taps "Share" - a real capture was lost entirely, 2026-08-08, when
    // the rider closed the CAN monitor dialog straight after a finished run instead of sharing
    // it first, since until now saving only ever happened as a side effect of sharing.
    fun saveCanFrames(context: Context, frames: List<String>): File? {
        if (frames.isEmpty()) return null
        val dir = File(context.applicationContext.filesDir, "diagnostics").apply { mkdirs() }
        val body = frames.joinToString("\n")
        findLatestWithBody(dir, "can_monitor_", body)?.let { return it }
        val file = File(dir, "can_monitor_${fileTimestampFormat.format(Date())}.txt")
        file.writeText(versionHeader(context) + body)
        return file
    }

    fun shareCanFramesIntent(context: Context, frames: List<String>): Intent? {
        val file = saveCanFrames(context, frames) ?: return null
        return shareFile(context, file)
    }

    private fun shareFile(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
