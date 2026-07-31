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

    private fun save(context: Context, results: List<ObdSweepEntry>): File? {
        if (results.isEmpty()) return null
        val dir = File(context.applicationContext.filesDir, "diagnostics").apply { mkdirs() }
        val file = File(dir, "obd_sweep_${fileTimestampFormat.format(Date())}.txt")
        file.writeText(results.joinToString("\n") { "${it.header}/${it.did}: ${it.response}" })
        return file
    }

    fun shareIntent(context: Context, results: List<ObdSweepEntry>): Intent? {
        val file = save(context, results) ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
