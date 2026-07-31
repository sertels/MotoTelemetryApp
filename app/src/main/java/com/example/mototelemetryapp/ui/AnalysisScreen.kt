package com.example.mototelemetryapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
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
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
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
            SessionDetailView(records = records)
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

@Composable
fun SessionDetailView(records: List<TelemetryRecord>) {
    if (records.isEmpty()) return

    val modelProducer = remember { CartesianChartModelProducer() }
    var showError by remember { mutableStateOf(false) }

    // Downsampled by stride (not just capped) so the whole ride is represented, not just its
    // first TARGET_CHART_POINTS records - and real elapsed minutes (not raw record index) is
    // used for x, so the axis actually means something the rider can read at a glance. Rounded
    // to 2 decimal places (~0.6s resolution, plenty for this) - Vico's GCD-based point spacing
    // rejects x values with more than 4 decimal places, which raw millisecond-derived minutes
    // almost always have (crashed on a real ride's data, 2026-08-01).
    val chartPoints = remember(records) {
        val stride = (records.size / TARGET_CHART_POINTS).coerceAtLeast(1)
        val startTimestamp = records.first().timestamp
        records.filterIndexed { index, _ -> index % stride == 0 }
            .map { record ->
                val elapsedMinutes = ((record.timestamp - startTimestamp) / 60_000.0 * 100).roundToInt() / 100.0
                Triple(elapsedMinutes, record.speed.toFloat(), record.rpm.toFloat() / 100f)
            }
    }

    LaunchedEffect(chartPoints) {
        try {
            modelProducer.runTransaction {
                lineSeries {
                    val elapsedMinutes = chartPoints.map { it.first }
                    series(elapsedMinutes, chartPoints.map { it.second })
                    series(elapsedMinutes, chartPoints.map { it.third })
                }
            }
            showError = false
        } catch (e: Exception) {
            android.util.Log.e("AnalysisScreen", "Error updating chart: ${e.message}", e)
            showError = true
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        if (showError) {
            Text(
                text = stringResource(R.string.chart_error),
                color = Color.Red,
                fontSize = 14.sp
            )
        } else {
            Text(text = stringResource(R.string.chart_legend), color = Color.White, fontSize = 14.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (!showError) {
            val elapsedTimeFormatter = remember {
                CartesianValueFormatter { value, _, _ ->
                    val totalSeconds = (value * 60).roundToInt().coerceAtLeast(0)
                    "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
                }
            }
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(),
                    startAxis = rememberStartAxis(),
                    bottomAxis = rememberBottomAxis(valueFormatter = elapsedTimeFormatter),
                ),
                modelProducer = modelProducer,
                // Content-fit so the whole (downsampled) ride is visible without scrolling by
                // default - pinch-zoom still works from there for a closer look at one section.
                zoomState = rememberVicoZoomState(initialZoom = Zoom.Content),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            )
        }
    }
}
