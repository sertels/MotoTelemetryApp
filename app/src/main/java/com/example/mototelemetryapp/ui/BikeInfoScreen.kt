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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import com.example.mototelemetryapp.DtcDescriptions
import com.example.mototelemetryapp.ObdSweepEntry
import com.example.mototelemetryapp.R
import com.example.mototelemetryapp.ui.theme.TelemetryAccent
import com.example.mototelemetryapp.ui.theme.TelemetryOnSurfaceMuted

private val CardBg = Color(0xFF161616)
private val CardBorderSoft = Color(0xFF202020)
private const val COOLANT_HOT_THRESHOLD_C = 100
private const val MIN_SPEED_FOR_ECONOMY_KMH = 5

@Composable
fun BikeInfoScreen(
    obdConnected: Boolean,
    // Per-command (keys match CONFIRMED_PID_MAP.command) live status: true = last response
    // matched what parsing expected, false = it didn't (negative/NO DATA/garbage), missing =
    // not queried yet this connection. Lets the sensor map below show what's *actually* working
    // right now instead of just listing commands as if all were equally "confirmed" - user
    // feedback after odometer/fuel turned out to be silently broken despite being listed, 2026-08-01.
    pidStatus: Map<String, Boolean>,
    odometerKm: Int?,
    // Standard mode 01 PID 0131, NOT the real lifetime odometer above (that DID answers
    // unsupported on this ECU) - shown separately and honestly labeled rather than silently
    // swapped in as "the odometer", since this one resets whenever DTCs are cleared.
    distanceSinceClearKm: Int?,
    serviceRemainingKm: Int?,
    coolantC: Int?,
    fuelLevelPct: Int?,
    fuelRateLph: Float?,
    speedKmh: Int?,
    batteryVolts: Float?,
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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = stringResource(R.string.bike_info),
            color = Color.White,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold
        )

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
            if (serviceRemainingKm != null) {
                Text(
                    text = "${stringResource(R.string.service)} · %,d ${stringResource(R.string.km_left)}".format(serviceRemainingKm),
                    color = TelemetryOnSurfaceMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (distanceSinceClearKm != null) {
                Text(
                    text = stringResource(R.string.bike_info_distance_since_clear, distanceSinceClearKm),
                    color = TelemetryOnSurfaceMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        SectionLabel(stringResource(R.string.bike_info_engine_health))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val coolant = coolantC ?: 0
                StatCard(
                    label = stringResource(R.string.bike_info_coolant),
                    value = "$coolant",
                    unit = "°C",
                    hot = coolant >= COOLANT_HOT_THRESHOLD_C,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = stringResource(R.string.bike_info_fuel_level),
                    value = "${fuelLevelPct ?: 0}",
                    unit = "%",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val l100km = if (fuelRateLph != null && fuelRateLph > 0f && speedKmh != null && speedKmh >= MIN_SPEED_FOR_ECONOMY_KMH) {
                    (fuelRateLph / speedKmh) * 100f
                } else null
                StatCard(
                    label = stringResource(R.string.bike_info_fuel_rate),
                    value = "%.1f".format(fuelRateLph ?: 0f),
                    unit = "L/h",
                    secondaryText = l100km?.let { "%.1f L/100km".format(it) },
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = stringResource(R.string.bike_info_battery),
                    // Still "--" on a real bike: no battery-voltage PID has been confirmed on this
                    // ECU yet (that's what the sweep below is for). Only the simulator fills it.
                    value = batteryVolts?.let { "%.1f".format(it) } ?: "--",
                    unit = "V",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        SectionLabel(stringResource(R.string.bike_info_diagnostics))
        DiagnosticsCard(obdConnected = obdConnected, obdMilOn = obdMilOn, dtcCodes = obdDtcCodes, onClearDtcs = onClearObdDtcs)

        SectionLabel(stringResource(R.string.bike_info_sensor_map))
        PidMapTable(pidStatus = pidStatus)

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
private fun StatCard(label: String, value: String, unit: String, hot: Boolean = false, secondaryText: String? = null, modifier: Modifier = Modifier) {
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
        if (secondaryText != null) {
            Text(
                text = secondaryText,
                color = TelemetryOnSurfaceMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

// A bare "P0133" tells a rider nothing, so each code carries a one-line summary and expands on tap
// for the fuller explanation - which is too long to sit on the row without pushing the codes
// themselves off the screen.
@Composable
private fun DtcRow(code: String) {
    var expanded by remember { mutableStateOf(false) }
    val description = remember(code) { DtcDescriptions.describe(code) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x38000000), RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = code,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.dtc_collapse else R.string.dtc_expand
                ),
                tint = Color(0xFFFF8A80),
                modifier = Modifier.size(18.dp)
            )
        }
        Text(
            text = description.summary,
            color = Color(0xFFE0B4B0),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 3.dp)
        )
        if (expanded) {
            Text(
                text = description.detail,
                color = TelemetryOnSurfaceMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            // Said plainly rather than implied, so a structural guess is never mistaken for a
            // real diagnosis of this specific fault.
            if (!description.isKnownCode) {
                Text(
                    text = stringResource(R.string.dtc_not_in_database),
                    color = Color(0xFF8A8A8A),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
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
                DtcRow(code)
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
private fun PidMapTable(pidStatus: Map<String, Boolean>) {
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
                Text(
                    text = mapping.signal,
                    color = Color(0xFFCCCCCC),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                // null (not queried yet this connection) reads as a neutral dot rather than a
                // false accusation of failure - only an actual mismatched/negative response
                // (false) or a real successful parse (true) get a color.
                val ok = pidStatus[mapping.command]
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            when (ok) {
                                true -> TelemetryAccent
                                false -> Color(0xFFE05C5C)
                                null -> Color(0xFF3A3A3A)
                            }
                        )
                )
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
