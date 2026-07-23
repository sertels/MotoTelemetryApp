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
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AnalysisScreen(
    sessions: List<Session>,
    onRenameSession: (Session, String) -> Unit,
    onDeleteSession: (Session) -> Unit,
    getRecords: (Long) -> kotlinx.coroutines.flow.Flow<List<TelemetryRecord>>
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
                items(sessions) { session ->
                    SessionCard(
                        session = session,
                        onClick = { selectedSession = session },
                        onRename = { newName -> onRenameSession(session, newName) },
                        onDelete = { onDeleteSession(session) },
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
    getRecords: (Long) -> Flow<List<TelemetryRecord>>
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(session.name) }
    val records by getRecords(session.id).collectAsState(initial = emptyList())
    
    val dateStr = remember(session.startTime) {
        SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(session.startTime))
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
                    IconButton(onClick = { showEditDialog = true }) {
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
                SpeedSparkline(records = records)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem(label = stringResource(R.string.stat_bike), value = "%.1f km".format(session.totalDistanceBikeKm))
                StatItem(label = stringResource(R.string.stat_gps), value = "%.1f km".format(session.totalDistanceGpsKm))
                StatItem(label = stringResource(R.string.fuel), value = "%.2f L".format(session.totalFuelLiters))
                StatItem(label = stringResource(R.string.stat_max_lean), value = "%.0f°".format(maxOf(session.maxLeanLeft, session.maxLeanRight)))
            }
        }
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
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

@Composable
fun SessionDetailView(records: List<TelemetryRecord>) {
    if (records.isEmpty()) return

    val modelProducer = remember { CartesianChartModelProducer() }
    var showError by remember { mutableStateOf(false) }

    LaunchedEffect(records) {
        try {
            modelProducer.runTransaction {
                lineSeries {
                    series(records.map { it.speed.toFloat() })
                    series(records.map { it.rpm.toFloat() / 100f })
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
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(),
                    startAxis = rememberStartAxis(),
                    bottomAxis = rememberBottomAxis(),
                ),
                modelProducer = modelProducer,
                modifier = Modifier.fillMaxWidth().height(200.dp)
            )
        }
    }
}
