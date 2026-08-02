package com.example.mototelemetryapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mototelemetryapp.R
import com.example.mototelemetryapp.data.Session
import com.example.mototelemetryapp.data.TelemetryRecord
import com.example.mototelemetryapp.ui.theme.TelemetryAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

@Composable
fun AnalysisScreen(
    sessions: List<Session>,
    onRenameSession: (Session, String) -> Unit,
    onDeleteSession: (Session) -> Unit,
    getRecords: (Long) -> kotlinx.coroutines.flow.Flow<List<TelemetryRecord>>,
    onViewRoute: (Long) -> Unit
) {
    var selectedSession by remember { mutableStateOf<Session?>(null) }
    
    if (selectedSession == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.analysis),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(sessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        onClick = { selectedSession = session },
                        onRename = { newName -> onRenameSession(session, newName) },
                        onDelete = { onDeleteSession(session) },
                        onViewRoute = { onViewRoute(session.id) },
                        getRecords = getRecords
                    )
                }
            }
        }
    } else {
        val records by getRecords(selectedSession!!.id).collectAsState(initial = emptyList())

        // Downsampling touches every record in the ride (up to ~18k on a real ~1hr ride).
        // Computing it once here, off the main thread, instead of redundantly inside each of the
        // 4 charts below cuts that cost 4x and keeps it out of the composition/scroll path -
        // doing it synchronously per-chart was still showing up as scroll jank even after the
        // charts were split into LazyColumn items (confirmed via dumpsys gfxinfo, 2026-08-02).
        val chartBasis by produceState<ChartBasis?>(initialValue = null, records) {
            value = if (records.size < 2) null else withContext(Dispatchers.Default) {
                val stride = (records.size / TARGET_CHART_POINTS).coerceAtLeast(1)
                val startTimestamp = records.first().timestamp
                val sampled = records.filterIndexed { index, _ -> index % stride == 0 }
                // Whole elapsed seconds, not fractional minutes: a rounded-but-still-Double x
                // value carries binary floating-point noise (12.35 stored as
                // 12.349999999999998), which Vico's GCD-based tick computation treats as "too
                // precise" and throws on - and that throw happens inside Vico's own internal
                // update coroutine, not inside TelemetryLineChart's runTransaction, so a
                // try/catch there can't catch it. Integer seconds have zero decimal places, so
                // there's nothing for that check to trip on.
                val xValues = sampled.map { record ->
                    ((record.timestamp - startTimestamp) / 1000L).toDouble()
                }
                ChartBasis(sampled, xValues)
            }
        }

        // A LazyColumn here (tried 2026-08-02) still recomposes/redraws a chart from scratch
        // every time it scrolls back into view, since off-screen lazy items get disposed - that
        // showed up as jank exactly while scrolling. Once the two real costs were fixed (the
        // dense default axis guideline redrawn every frame, and each chart redundantly
        // downsampling the full ~18k-record ride), a plain Column pays the render cost for all
        // 4 charts once, up front, and scrolling afterward just translates already-drawn layers -
        // nothing left to recompute mid-scroll.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
                .verticalScroll(rememberScrollState())
        ) {
            TextButton(onClick = { selectedSession = null }) {
                Text(stringResource(R.string.back_to_sessions), color = TelemetryAccent)
            }
            Text(
                text = selectedSession!!.name,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )
            chartBasis?.let { basis -> SessionDetailCharts(basis) }
        }
    }
}

private data class ChartBasis(val sampled: List<TelemetryRecord>, val xValues: List<Double>)

@Composable
fun SessionCard(
    session: Session,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onViewRoute: () -> Unit,
    getRecords: (Long) -> Flow<List<TelemetryRecord>>
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(session.name) }
    val records by getRecords(session.id).collectAsState(initial = emptyList())
    
    val dateStr = remember(session.startTime) {
        SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(session.startTime))
    }

    // GPS distance (not bike/OBD) since that's the one reliably populated - see the 2026-07-31
    // diagnosis of the OBD odometer DID silently failing.
    val avgSpeedKmh = remember(session) {
        val endTime = session.endTime
        if (endTime != null && endTime > session.startTime && session.totalDistanceGpsKm > 0f) {
            session.totalDistanceGpsKm / ((endTime - session.startTime) / 3_600_000f)
        } else {
            0f
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = session.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(text = dateStr, color = Color.Gray, fontSize = 12.sp)
                }
                Row {
                    IconButton(onClick = onViewRoute) {
                        Icon(Icons.Default.Map, contentDescription = stringResource(R.string.view_route), tint = Color.Gray)
                    }
                    IconButton(onClick = {
                        newName = session.name
                        showEditDialog = true
                    }) {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = Color.Gray)
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete_session),
                            tint = Color.Gray
                        )
                    }
                }
            }
            
            if (records.size >= 2) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = stringResource(R.string.speed), color = Color.Gray, fontSize = 10.sp)
                Spacer(modifier = Modifier.height(4.dp))
                SpeedSparkline(records = records)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem(label = stringResource(R.string.stat_bike), value = "%.1f km".format(session.totalDistanceBikeKm))
                StatItem(label = stringResource(R.string.stat_gps), value = "%.1f km".format(session.totalDistanceGpsKm))
                StatItem(label = stringResource(R.string.fuel), value = "%.2f L".format(session.totalFuelLiters))
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                StatItem(label = stringResource(R.string.stat_max_speed), value = "${session.maxSpeed} km/h")
                StatItem(label = stringResource(R.string.stat_avg_speed), value = "${avgSpeedKmh.toInt()} km/h")
                // Not stored on Session (no schema change needed) - derived straight from this
                // card's own records, same as the sparkline above.
                StatItem(label = stringResource(R.string.stat_max_rpm), value = "${records.maxOfOrNull { it.rpm } ?: 0}")
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Left/right lean and lateral/longitudinal G each kept as separate stats rather than
            // combined into one magnitude - direction is part of what happened (a hard corner and
            // a hard stop, or a left sweeper and a right one, aren't the same kind of "hard").
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                StatItem(label = stringResource(R.string.stat_max_lean_left), value = "%.0f°".format(session.maxLeanLeft))
                StatItem(label = stringResource(R.string.stat_max_lean_right), value = "%.0f°".format(session.maxLeanRight))
                StatItem(label = stringResource(R.string.stat_max_g_lat), value = "%.2fg".format(session.maxGForceLat))
                StatItem(label = stringResource(R.string.stat_max_g_lon), value = "%.2fg".format(session.maxGForceLon))
            }
        }
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = {
                showEditDialog = false
                newName = session.name
            },
            title = { Text(stringResource(R.string.rename_ride)) },
            text = {
                TextField(value = newName, onValueChange = { newName = it })
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(newName)
                    showEditDialog = false
                }) { Text(stringResource(R.string.save)) }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_ride_title)) },
            text = { Text(stringResource(R.string.delete_ride_message, session.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
fun SpeedSparkline(records: List<TelemetryRecord>) {
    val maxSpeed = (records.maxOfOrNull { it.speed } ?: 0).coerceAtLeast(1)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
    ) {
        val stepX = size.width / (records.size - 1)
        val path = androidx.compose.ui.graphics.Path()
        records.forEachIndexed { index, record ->
            val x = index * stepX
            val y = size.height - (record.speed / maxSpeed.toFloat()) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = TelemetryAccent, style = Stroke(width = 1.5.dp.toPx()))
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column {
        Text(text = label, color = Color.Gray, fontSize = 10.sp)
        Text(text = value, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

// Vico's default zoom picks whichever is *more* zoomed in of a fixed "static" spacing and a
// fit-to-content spacing - fine for a chart with a few dozen points, but for a real ~1hr ride
// (~17500 records at the ~200ms recording cadence) that meant the chart opened showing only the
// first couple of seconds of idle-at-zero data, with the rest reachable only by manually
// scrolling through thousands of points (confirmed unusable on a real ride, 2026-08-01). Capping
// the plotted points and forcing a fit-to-content zoom below is what actually fixes that.
private const val TARGET_CHART_POINTS = 400

@Composable
private fun SessionDetailCharts(basis: ChartBasis) {
    val chartModifier = Modifier.fillMaxWidth().padding(16.dp)

    TelemetryLineChart(
        basis = basis,
        legend = stringResource(R.string.chart_legend),
        seriesSelectors = listOf({ it.speed.toFloat() }, { it.rpm.toFloat() / 100f }),
        seriesColors = listOf(Color.White, TelemetryAccent),
        modifier = chartModifier
    )
    // Phone-sourced (leanAnglePhone/gForceLat/gForceLon), not OBD-sourced - these got a real
    // noise-smoothing fix (EMA + rate-of-change gating in OrientationManager) on 2026-08-01
    // and are trustworthy for a timeline. Bike-sourced fields (gear, lean-bike, odometer) were
    // still failing to read on a real ride as of that date, so they aren't charted yet.
    TelemetryLineChart(
        basis = basis,
        legend = stringResource(R.string.chart_legend_lean),
        seriesSelectors = listOf({ it.leanAnglePhone }),
        seriesColors = listOf(Color.White),
        modifier = chartModifier
    )
    TelemetryLineChart(
        basis = basis,
        legend = stringResource(R.string.chart_legend_gforce),
        seriesSelectors = listOf({ it.gForceLat }, { it.gForceLon }),
        seriesColors = listOf(Color.White, TelemetryAccent),
        modifier = chartModifier,
        valueFormatter = { "%.2f".format(it) }
    )
    // GPS-sourced, not OBD - reliable for the same reason distance/route already use GPS
    // elsewhere in this screen rather than the bike's odometer.
    TelemetryLineChart(
        basis = basis,
        legend = stringResource(R.string.chart_legend_altitude),
        seriesSelectors = listOf({ it.altitude.toFloat() }),
        seriesColors = listOf(Color.White),
        modifier = chartModifier
    )
}

// Vico (2.0.0-alpha.28) cost us three separate perf/correctness bugs on this screen in one day -
// an x-precision crash, a default axis guideline redrawn every frame, and per-chart work repeated
// on every scroll-triggered recomposition - and even after fixing all three, per-frame cost during
// scroll never got below ~40-85ms (dumpsys gfxinfo, 2026-08-01/02). Given the acceptance bar is
// sub-1s render and zero scroll stutter, a hand-drawn Canvas chart (same technique as
// SpeedSparkline above) removes the whole library's measurement/axis/guideline machinery: one
// Path per series, three gridlines, a handful of Text labels. No pinch-zoom, but the fit-to-content
// view already shows the whole (downsampled) ride at once.
@Composable
private fun TelemetryLineChart(
    basis: ChartBasis,
    legend: String,
    seriesSelectors: List<(TelemetryRecord) -> Float>,
    seriesColors: List<Color>,
    modifier: Modifier = Modifier,
    valueFormatter: (Float) -> String = { "%.0f".format(it) }
) {
    // basis.sampled/xValues are already downsampled to TARGET_CHART_POINTS (see AnalysisScreen),
    // so this per-series mapping is trivial (≤400 items).
    val seriesValues = remember(basis, seriesSelectors) {
        seriesSelectors.map { selector -> basis.sampled.map(selector) }
    }
    val yMin: Float
    val yMax: Float
    remember(seriesValues) {
        val all = seriesValues.asSequence().flatten()
        val rawMin = all.minOrNull() ?: 0f
        val rawMax = all.maxOrNull() ?: 1f
        // Keep a 0 baseline visible for all-positive series (speed/rpm/altitude); use the real
        // range for series that cross zero (lean angle, g-force).
        val min = if (rawMin > 0f) 0f else rawMin
        val max = (if (rawMax < 0f) 0f else rawMax).coerceAtLeast(min + 0.01f)
        min to max
    }.let { (min, max) -> yMin = min; yMax = max }
    val xMax = (basis.xValues.lastOrNull() ?: 1.0).coerceAtLeast(1.0)

    Column(modifier = modifier) {
        Text(text = legend, color = Color.White, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxHeight().width(36.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(valueFormatter(yMax), color = Color.Gray, fontSize = 9.sp)
                Text(valueFormatter((yMax + yMin) / 2f), color = Color.Gray, fontSize = 9.sp)
                Text(valueFormatter(yMin), color = Color.Gray, fontSize = 9.sp)
            }
            Column(modifier = Modifier.fillMaxHeight().weight(1f)) {
                Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val range = (yMax - yMin).takeIf { it > 0f } ?: 1f
                    val gridColor = Color.White.copy(alpha = 0.12f)
                    listOf(0f, 0.5f, 1f).forEach { frac ->
                        val y = size.height * (1f - frac)
                        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    }
                    seriesValues.forEachIndexed { seriesIndex, values ->
                        val path = androidx.compose.ui.graphics.Path()
                        values.forEachIndexed { i, v ->
                            val x = (basis.xValues[i] / xMax).toFloat() * size.width
                            val y = size.height - ((v - yMin) / range) * size.height
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path = path, color = seriesColors[seriesIndex], style = Stroke(width = 1.5.dp.toPx()))
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatElapsed(0.0), color = Color.Gray, fontSize = 9.sp)
                    Text(formatElapsed(xMax / 2), color = Color.Gray, fontSize = 9.sp)
                    Text(formatElapsed(xMax), color = Color.Gray, fontSize = 9.sp)
                }
            }
        }
    }
}

private fun formatElapsed(seconds: Double): String {
    val totalSeconds = seconds.roundToInt().coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
