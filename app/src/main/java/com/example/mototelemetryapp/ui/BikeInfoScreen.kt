package com.example.mototelemetryapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mototelemetryapp.BluetoothOBDManager
import com.example.mototelemetryapp.ObdSweepEntry
import com.example.mototelemetryapp.R
import com.example.mototelemetryapp.data.TelemetryRecord
import com.example.mototelemetryapp.ui.theme.TelemetryAccent
import com.example.mototelemetryapp.ui.theme.TelemetryOnSurfaceMuted

private val CardBg = Color(0xFF161616)
private val CardBorderSoft = Color(0xFF202020)
private const val COOLANT_HOT_THRESHOLD_C = 100

@Composable
fun BikeInfoScreen(
    data: TelemetryRecord?,
    obdConnected: Boolean,
    odometerKm: Int?,
    obdMilOn: Boolean,
    obdDtcCodes: List<String>,
    onClearObdDtcs: () -> Unit,
    obdSweepRunning: Boolean,
    obdSweepResults: List<ObdSweepEntry>,
    obdSweepProgress: Pair<Int, Int> = 0 to 0,
    onRunObdSweep: () -> Unit,
    onCancelObdSweep: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.bike_info),
                color = Color.White,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(100.dp))
                    .border(1.dp, CardBorderSoft, RoundedCornerShape(100.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (obdConnected) TelemetryAccent else Color(0xFF5A5A5A))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(if (obdConnected) R.string.obd_connected else R.string.obd_disconnected),
                    color = if (obdConnected) Color.White else TelemetryOnSurfaceMuted,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardBg, RoundedCornerShape(16.dp))
                .border(1.dp, CardBorderSoft, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(text = stringResource(R.string.bike_info_model), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "LX900-A · 4M96001",
                color = TelemetryOnSurfaceMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.2.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(
                    text = odometerKm?.let { "%,d".format(it) } ?: "--",
                    color = TelemetryAccent,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.bike_info_odometer_label),
                    color = TelemetryOnSurfaceMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }

        SectionLabel(stringResource(R.string.bike_info_engine_health))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val coolant = data?.coolantTemp ?: 0
                StatCard(
                    label = stringResource(R.string.bike_info_coolant),
                    value = "$coolant",
                    unit = "°C",
                    hot = coolant >= COOLANT_HOT_THRESHOLD_C,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = stringResource(R.string.bike_info_fuel_level),
                    value = "${data?.fuelLevel ?: 0}",
                    unit = "%",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(
                    label = stringResource(R.string.bike_info_fuel_rate),
                    value = "%.1f".format(data?.fuelRate ?: 0f),
                    unit = "L/h",
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = stringResource(R.string.bike_info_battery),
                    value = "--",
                    unit = "V",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        SectionLabel(stringResource(R.string.bike_info_diagnostics))
        DiagnosticsCard(obdConnected = obdConnected, obdMilOn = obdMilOn, dtcCodes = obdDtcCodes, onClearDtcs = onClearObdDtcs)

        SectionLabel(stringResource(R.string.bike_info_sensor_map))
        PidMapTable()

        SweepCta(
            running = obdSweepRunning,
            results = obdSweepResults,
            progress = obdSweepProgress,
            onRunSweep = onRunObdSweep,
            onCancelSweep = onCancelObdSweep
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Color(0xFF5A5A5A),
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp
    )
}

@Composable
private fun StatCard(label: String, value: String, unit: String, hot: Boolean = false, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(CardBg, RoundedCornerShape(14.dp))
            .border(1.dp, CardBorderSoft, RoundedCornerShape(14.dp))
            .padding(horizontal = 13.dp, vertical = 12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, color = TelemetryOnSurfaceMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
            if (hot) {
                Text(
                    text = stringResource(R.string.bike_info_high_tag),
                    color = Color(0xFFFFC107),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Color(0xFF241C0A), RoundedCornerShape(100.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 5.dp)) {
            Text(
                text = value,
                color = if (hot) Color(0xFFFFC107) else Color.White,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(text = unit, color = TelemetryOnSurfaceMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

@Composable
private fun DiagnosticsCard(obdConnected: Boolean, obdMilOn: Boolean, dtcCodes: List<String>, onClearDtcs: () -> Unit) {
    var showClearConfirm by remember { mutableStateOf(false) }

    if (!obdConnected) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardBg, RoundedCornerShape(16.dp))
                .border(1.dp, CardBorderSoft, RoundedCornerShape(16.dp))
                .padding(horizontal = 15.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF5A5A5A)))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.bike_info_dtc_unknown), color = TelemetryOnSurfaceMuted, fontSize = 12.sp)
        }
    } else if (obdMilOn) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2A1414), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFF5A2020), RoundedCornerShape(16.dp))
                .padding(horizontal = 15.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFFF5252)))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.bike_info_check_engine_count, dtcCodes.size),
                    color = Color(0xFFFF8A80),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            dtcCodes.forEach { code ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x38000000), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = code,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            OutlinedButton(
                onClick = { showClearConfirm = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A80)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.obd_clear_dtcs), fontWeight = FontWeight.SemiBold)
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardBg, RoundedCornerShape(16.dp))
                .border(1.dp, CardBorderSoft, RoundedCornerShape(16.dp))
                .padding(horizontal = 15.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF29E0A8)))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.bike_info_no_dtc), color = TelemetryOnSurfaceMuted, fontSize = 12.sp)
        }
    }

    if (showClearConfirm) {
        ClearDtcsConfirmDialog(
            onDismiss = { showClearConfirm = false },
            onConfirm = {
                onClearDtcs()
                showClearConfirm = false
            }
        )
    }
}

@Composable
private fun PidMapTable() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(14.dp))
            .border(1.dp, CardBorderSoft, RoundedCornerShape(14.dp))
            .padding(vertical = 4.dp)
    ) {
        BluetoothOBDManager.CONFIRMED_PID_MAP.forEachIndexed { index, mapping ->
            if (index > 0) {
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E1E1E)))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${mapping.header}/${mapping.command}",
                    color = TelemetryOnSurfaceMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(110.dp)
                )
                Text(text = mapping.signal, color = Color(0xFFCCCCCC), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SweepCta(
    running: Boolean,
    results: List<ObdSweepEntry>,
    progress: Pair<Int, Int> = 0 to 0,
    onRunSweep: () -> Unit,
    onCancelSweep: () -> Unit = {}
) {
    var showSweepDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(14.dp))
            .border(1.dp, CardBorderSoft, RoundedCornerShape(14.dp))
            .clickable {
                showSweepDialog = true
                onRunSweep()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.bike_info_sweep_title), color = Color(0xFFCCCCCC), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.bike_info_sweep_hint),
                color = TelemetryOnSurfaceMuted,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = TelemetryAccent,
            modifier = Modifier.size(16.dp)
        )
    }

    if (showSweepDialog) {
        ObdSweepDialog(
            running = running,
            results = results,
            progress = progress,
            onCancel = onCancelSweep,
            onDismiss = { showSweepDialog = false }
        )
    }
}
