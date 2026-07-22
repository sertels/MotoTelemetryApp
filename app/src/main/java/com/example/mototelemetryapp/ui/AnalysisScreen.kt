package com.example.mototelemetryapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mototelemetryapp.R
import com.example.mototelemetryapp.data.Session
import com.example.mototelemetryapp.data.TelemetryRecord
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
                        onRename = { newName -> onRenameSession(session, newName) }
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
                Text("< Back to Sessions", color = Color(0xFF00E676))
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
fun SessionCard(session: Session, onClick: () -> Unit, onRename: (String) -> Unit) {
    var showEditDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(session.name) }
    
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
                IconButton(onClick = { showEditDialog = true }) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = Color.Gray)
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem(label = "BIKE", value = "%.1f km".format(session.totalDistanceBikeKm))
                StatItem(label = "GPS", value = "%.1f km".format(session.totalDistanceGpsKm))
                StatItem(label = "FUEL", value = "%.2f L".format(session.totalFuelLiters))
                StatItem(label = "MAX LEAN", value = "%.0f°".format(maxOf(session.maxLeanLeft, session.maxLeanRight)))
            }
        }
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Rename Ride") },
            text = {
                TextField(value = newName, onValueChange = { newName = it })
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(newName)
                    showEditDialog = false
                }) { Text("Save") }
            }
        )
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
                text = "Error loading chart data",
                color = Color.Red,
                fontSize = 14.sp
            )
        } else {
            Text(text = "Speed (White) & RPM/100 (Green)", color = Color.White, fontSize = 14.sp)
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
