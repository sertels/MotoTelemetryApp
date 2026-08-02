package com.example.mototelemetryapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.flow.Flow
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottomAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStartAxis
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
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

        // LazyColumn, not a plain Column+verticalScroll: each chart below is a fairly heavy
        // Vico CartesianChartHost. With a plain Column all 4 are composed/measured/drawn at once
        // regardless of scroll position, which overloaded the main thread badly enough on a real
        // ~1hr ride that the first chart's data line still hadn't appeared 5+ seconds after
        // opening the screen, and scrolling itself was janky (confirmed via a timestamped
        // adb screenshot burst, 2026-08-02). Lazy items mean only the on-screen chart(s) pay
        // that cost.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
        ) {
            item {
                Column {
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
                }
            }
            sessionDetailCharts(records = records)
        }
    }
}

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

// One LazyColumn item per chart (see the LazyColumn comment in AnalysisScreen) so each chart's
// composition/layout/draw cost is only paid when it's actually scrolled into view.
private fun LazyListScope.sessionDetailCharts(records: List<TelemetryRecord>) {
    if (records.isEmpty()) return

    val chartModifier = Modifier.fillMaxWidth().padding(16.dp)

    item {
        TelemetryLineChart(
            records = records,
            legend = stringResource(R.string.chart_legend),
            seriesSelectors = listOf({ it.speed.toFloat() }, { it.rpm.toFloat() / 100f }),
            seriesColors = listOf(Color.White, TelemetryAccent),
            modifier = chartModifier
        )
    }
    // Phone-sourced (leanAnglePhone/gForceLat/gForceLon), not OBD-sourced - these got a real
    // noise-smoothing fix (EMA + rate-of-change gating in OrientationManager) on 2026-08-01
    // and are trustworthy for a timeline. Bike-sourced fields (gear, lean-bike, odometer) were
    // still failing to read on a real ride as of that date, so they aren't charted yet.
    item {
        TelemetryLineChart(
            records = records,
            legend = stringResource(R.string.chart_legend_lean),
            seriesSelectors = listOf({ it.leanAnglePhone }),
            seriesColors = listOf(Color.White),
            modifier = chartModifier
        )
    }
    item {
        TelemetryLineChart(
            records = records,
            legend = stringResource(R.string.chart_legend_gforce),
            seriesSelectors = listOf({ it.gForceLat }, { it.gForceLon }),
            seriesColors = listOf(Color.White, TelemetryAccent),
            modifier = chartModifier
        )
    }
    // GPS-sourced, not OBD - reliable for the same reason distance/route already use GPS
    // elsewhere in this screen rather than the bike's odometer.
    item {
        TelemetryLineChart(
            records = records,
            legend = stringResource(R.string.chart_legend_altitude),
            seriesSelectors = listOf({ it.altitude.toFloat() }),
            seriesColors = listOf(Color.White),
            modifier = chartModifier
        )
    }
}

// Shared by every chart in SessionDetailView - downsample-by-stride, real-elapsed-minutes-as-x
// (rounded to avoid Vico's x-precision crash), and Zoom.Content are all load-bearing fixes from
// getting the original Speed/RPM chart working on a real ~1hr ride, see the TARGET_CHART_POINTS
// comment above.
@Composable
private fun TelemetryLineChart(
    records: List<TelemetryRecord>,
    legend: String,
    seriesSelectors: List<(TelemetryRecord) -> Float>,
    seriesColors: List<Color>,
    modifier: Modifier = Modifier
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    var showError by remember { mutableStateOf(false) }

    val chartData = remember(records) {
        val stride = (records.size / TARGET_CHART_POINTS).coerceAtLeast(1)
        val startTimestamp = records.first().timestamp
        val sampled = records.filterIndexed { index, _ -> index % stride == 0 }
        // Whole elapsed seconds, not fractional minutes: a rounded-but-still-Double x value (the
        // previous approach) carries binary floating-point noise (12.35 stored as
        // 12.349999999999998), which Vico's GCD-based tick computation treats as "too precise"
        // and throws on - and that throw happens inside Vico's own internal update coroutine
        // (CartesianChartModelProducer's collectAsState collector), not inside the runTransaction
        // block below, so the try/catch there can't catch it. Integer seconds have zero decimal
        // places, so there's nothing for that check to trip on.
        val xValues = sampled.map { record ->
            ((record.timestamp - startTimestamp) / 1000L).toDouble()
        }
        val seriesValues = seriesSelectors.map { selector -> sampled.map(selector) }
        xValues to seriesValues
    }

    LaunchedEffect(chartData) {
        try {
            modelProducer.runTransaction {
                lineSeries {
                    val (xValues, seriesValues) = chartData
                    seriesValues.forEach { values -> series(xValues, values) }
                }
            }
            showError = false
        } catch (e: Exception) {
            android.util.Log.e("AnalysisScreen", "Error updating chart: ${e.message}", e)
            showError = true
        }
    }

    Column(modifier = modifier) {
        if (showError) {
            Text(
                text = stringResource(R.string.chart_error),
                color = Color.Red,
                fontSize = 14.sp
            )
        } else {
            Text(text = legend, color = Color.White, fontSize = 14.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (!showError) {
            val elapsedTimeFormatter = remember {
                CartesianValueFormatter { value, _, _ ->
                    val totalSeconds = value.roundToInt().coerceAtLeast(0)
                    "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
                }
            }
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(
                        // Vico's own default palette is three shades of gray in dark theme -
                        // it never actually matched the "White/Blue" legend text, which is why
                        // both lines looked identically gray on a real device (2026-08-01).
                        lineProvider = LineCartesianLayer.LineProvider.series(
                            seriesColors.map { color ->
                                rememberLine(LineCartesianLayer.LineFill.single(fill(color)))
                            }
                        )
                    ),
                    startAxis = rememberStartAxis(),
                    bottomAxis = rememberBottomAxis(valueFormatter = elapsedTimeFormatter),
                ),
                modelProducer = modelProducer,
                // Content-fit so the whole (downsampled) ride is visible without scrolling by
                // default - pinch-zoom still works from there for a closer look at one section.
                zoomState = rememberVicoZoomState(initialZoom = Zoom.Content),
                // Vico's default diff/entrance animation draws the line growing in over ~500ms -
                // harmless alone, but with the main thread already under load from four charts
                // (see the LazyColumn comment above) each animation frame was itself taking
                // hundreds of ms, stretching what should be sub-second into 5+ seconds before a
                // line was visible at all (confirmed via a timestamped screenshot burst,
                // 2026-08-02). Disabling it makes the chart paint once, fully formed.
                animationSpec = null,
                runInitialAnimation = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            )
        }
    }
}
