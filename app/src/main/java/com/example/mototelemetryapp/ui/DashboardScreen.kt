package com.example.mototelemetryapp.ui

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mototelemetryapp.LeanSource
import com.example.mototelemetryapp.ObdSweepEntry
import com.example.mototelemetryapp.R
import com.example.mototelemetryapp.data.TelemetryRecord
import com.example.mototelemetryapp.ui.theme.TelemetryAccent
import com.example.mototelemetryapp.ui.theme.TelemetryOnSurfaceMuted
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val CardBorder = Color(0xFF262626)
private val CardGradient = Brush.verticalGradient(listOf(Color(0xFF1C1C1C), Color(0xFF161616)))
private val BarTrack = Color(0xFF2B2B2B)
private val BadgeBg = Color(0xFF1A1A1A)

@Composable
fun DashboardScreen(
    data: TelemetryRecord?,
    leanSource: LeanSource,
    onToggleSource: () -> Unit,
    onCalibrate: () -> Unit,
    obdConnected: Boolean = false,
    onFetchObdDevices: () -> List<Pair<String, String>> = { emptyList() },
    onConnectObd: (String) -> Unit = {},
    obdSweepRunning: Boolean = false,
    obdSweepResults: List<ObdSweepEntry> = emptyList(),
    obdSweepProgress: Pair<Int, Int> = 0 to 0,
    onRunObdSweep: () -> Unit = {},
    onCancelObdSweep: () -> Unit = {},
    onDisconnectObd: () -> Unit = {},
    obdConnectError: String? = null,
    obdMilOn: Boolean = false,
    obdDtcCodes: List<String> = emptyList(),
    onClearObdDtcs: () -> Unit = {}
) {
    val currentLean = if (leanSource == LeanSource.PHONE) data?.leanAnglePhone else data?.leanAngleBike
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val backgroundBrush = Brush.radialGradient(
        colors = listOf(Color(0xFF181818), Color(0xFF0F0F0F)),
        radius = 900f
    )

    if (isLandscape) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LiveIndicator()
                GpsCoordsText(data)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.CenterVertically)) {
                ObdStatusBadge(
                    connected = obdConnected,
                    onFetchDevices = onFetchObdDevices,
                    onConnect = onConnectObd,
                    sweepRunning = obdSweepRunning,
                    sweepResults = obdSweepResults,
                    sweepProgress = obdSweepProgress,
                    onRunSweep = onRunObdSweep,
                    onCancelSweep = onCancelObdSweep,
                    onDisconnect = onDisconnectObd
                )
                if (obdMilOn) {
                    Spacer(modifier = Modifier.height(6.dp))
                    CheckEngineBadge(dtcCodes = obdDtcCodes, onClearDtcs = onClearObdDtcs)
                }
                if (obdConnectError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    ObdConnectErrorPill(obdConnectError)
                }
                Spacer(modifier = Modifier.height(4.dp))
                SpeedRpmCard(data, isLandscape = true)
            }
            Column(
                modifier = Modifier.align(Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                GearReadout(data, fontSize = 40.sp)
            }
            LeanGauge(
                currentLean = currentLean,
                leanSource = leanSource,
                onToggleSource = onToggleSource,
                onCalibrate = onCalibrate,
                circleSize = 160.dp,
                canvasSize = 128.dp,
                overlayReadout = true,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
            BarsCard(
                data,
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically)
            )
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LiveIndicator()
                GpsCoordsText(data)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (obdMilOn) {
                        CheckEngineBadge(dtcCodes = obdDtcCodes, onClearDtcs = onClearObdDtcs)
                    }
                    ObdStatusBadge(
                        connected = obdConnected,
                        onFetchDevices = onFetchObdDevices,
                        onConnect = onConnectObd,
                        sweepRunning = obdSweepRunning,
                        sweepResults = obdSweepResults,
                        sweepProgress = obdSweepProgress,
                        onRunSweep = onRunObdSweep,
                        onCancelSweep = onCancelObdSweep,
                        onDisconnect = onDisconnectObd
                    )
                }
            }
            if (obdConnectError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                ObdConnectErrorPill(obdConnectError)
            }
            Spacer(modifier = Modifier.height(10.dp))
            SpeedGearRpmCard(data)
            Spacer(modifier = Modifier.height(22.dp))
            LeanGauge(
                currentLean = currentLean,
                leanSource = leanSource,
                onToggleSource = onToggleSource,
                onCalibrate = onCalibrate,
                circleSize = 184.dp,
                canvasSize = 150.dp,
                overlayReadout = false
            )
            Spacer(modifier = Modifier.height(16.dp))
            BarsCard(data, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun ObdConnectErrorPill(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color(0xFF2A1414), RoundedCornerShape(100.dp))
            .border(1.dp, Color(0xFF5A2020), RoundedCornerShape(100.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = message, color = Color(0xFFFF8A80), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GpsCoordsText(data: TelemetryRecord?, modifier: Modifier = Modifier) {
    val text = if (data != null && (data.latitude != 0.0 || data.longitude != 0.0)) {
        "%.4f, %.4f".format(data.latitude, data.longitude)
    } else "--"
    Text(
        text = text,
        color = TelemetryOnSurfaceMuted,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.3.sp,
        modifier = modifier
    )
}

@Composable
fun LiveIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(TelemetryAccent)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = "LIVE", color = TelemetryOnSurfaceMuted, fontSize = 10.sp, letterSpacing = 1.sp)
    }
}

@Composable
fun ObdStatusBadge(
    connected: Boolean,
    onFetchDevices: () -> List<Pair<String, String>>,
    onConnect: (String) -> Unit,
    sweepRunning: Boolean = false,
    sweepResults: List<ObdSweepEntry> = emptyList(),
    sweepProgress: Pair<Int, Int> = 0 to 0,
    onRunSweep: () -> Unit = {},
    onCancelSweep: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var showSweepDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .background(BadgeBg, RoundedCornerShape(100.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(100.dp))
                .clickable {
                    devices = onFetchDevices()
                    menuExpanded = true
                }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (connected) TelemetryAccent else Color(0xFF5A5A5A))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(if (connected) R.string.obd_connected else R.string.obd_disconnected),
                color = if (connected) Color.White else TelemetryOnSurfaceMuted,
                fontSize = 10.sp,
                letterSpacing = 0.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            if (devices.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.obd_no_paired_devices)) },
                    onClick = { menuExpanded = false },
                    enabled = false
                )
            } else {
                devices.forEach { (name, address) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onConnect(address)
                            menuExpanded = false
                        }
                    )
                }
            }
            if (connected) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.obd_run_sweep)) },
                    onClick = {
                        menuExpanded = false
                        showSweepDialog = true
                        onRunSweep()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.obd_disconnect)) },
                    onClick = {
                        menuExpanded = false
                        onDisconnect()
                    }
                )
            }
        }
    }

    if (showSweepDialog) {
        ObdSweepDialog(
            running = sweepRunning,
            results = sweepResults,
            progress = sweepProgress,
            onCancel = onCancelSweep,
            onDismiss = { showSweepDialog = false }
        )
    }
}

@Composable
fun ObdSweepDialog(
    running: Boolean,
    results: List<ObdSweepEntry>,
    progress: Pair<Int, Int> = 0 to 0,
    onCancel: () -> Unit = {},
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (running) onCancel() else onDismiss() },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { if (running) onCancel() else onDismiss() }) {
                Text(if (running) stringResource(R.string.cancel) else stringResource(R.string.obd_sweep_close))
            }
        },
        title = {
            Text(
                if (running) stringResource(R.string.obd_sweep_running) else stringResource(R.string.obd_sweep_done),
                color = Color.White
            )
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if (running) {
                    val (completed, total) = progress
                    if (total > 0) {
                        Text(
                            stringResource(R.string.obd_sweep_progress, completed, total),
                            color = TelemetryAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
                if (running && results.isEmpty()) {
                    Text(
                        stringResource(R.string.obd_sweep_hint),
                        color = TelemetryOnSurfaceMuted,
                        fontSize = 12.sp
                    )
                }
                val interesting = results.filter { it.response.replace(" ", "").startsWith("62") }
                if (interesting.isNotEmpty()) {
                    Text(
                        "${interesting.size} / ${results.size}",
                        color = TelemetryAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                interesting.forEach { entry ->
                    Row(modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(
                            "${entry.header}/${entry.did}",
                            color = TelemetryOnSurfaceMuted,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.width(90.dp)
                        )
                        Text(
                            entry.response,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }
        },
        containerColor = Color(0xFF1C1C1C)
    )
}

@Composable
fun CheckEngineBadge(
    dtcCodes: List<String>,
    onClearDtcs: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .background(Color(0xFF2A1414), RoundedCornerShape(100.dp))
            .border(1.dp, Color(0xFF5A2020), RoundedCornerShape(100.dp))
            .clickable { showDialog = true }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Color(0xFFFF5252))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.obd_check_engine),
            color = Color(0xFFFF8A80),
            fontSize = 10.sp,
            letterSpacing = 0.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }

    if (showDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showClearConfirm = true }) {
                    Text(stringResource(R.string.obd_clear_dtcs), color = Color(0xFFFF8A80))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.obd_dtc_title), color = Color.White) },
            text = {
                Column {
                    if (dtcCodes.isEmpty()) {
                        Text(
                            stringResource(R.string.obd_dtc_none_stored),
                            color = TelemetryOnSurfaceMuted,
                            fontSize = 12.sp
                        )
                    } else {
                        dtcCodes.forEach { code ->
                            Text(
                                code,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 3.dp)
                            )
                        }
                    }
                }
            },
            containerColor = Color(0xFF1C1C1C)
        )
    }

    if (showClearConfirm) {
        ClearDtcsConfirmDialog(
            onDismiss = { showClearConfirm = false },
            onConfirm = {
                onClearDtcs()
                showClearConfirm = false
                showDialog = false
            }
        )
    }
}

@Composable
fun ClearDtcsConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.obd_clear_dtcs), color = Color(0xFFFF8A80))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { Text(stringResource(R.string.obd_clear_dtcs_title), color = Color.White) },
        text = {
            Text(
                stringResource(R.string.obd_clear_dtcs_message),
                color = TelemetryOnSurfaceMuted,
                fontSize = 12.sp
            )
        },
        containerColor = Color(0xFF1C1C1C)
    )
}

@Composable
fun SpeedGearRpmCard(data: TelemetryRecord?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp), clip = false)
            .background(CardGradient, RoundedCornerShape(20.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = stringResource(R.string.speed), color = TelemetryOnSurfaceMuted, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
            Text(text = "${data?.speed ?: 0}", color = Color.White, fontSize = 58.sp, fontWeight = FontWeight.Bold)
            Text(text = stringResource(R.string.unit_kmh), color = TelemetryOnSurfaceMuted, fontSize = 13.sp)
        }
        VerticalDivider()
        GearReadout(data, fontSize = 68.sp)
        VerticalDivider()
        Column(horizontalAlignment = Alignment.End) {
            Text(text = stringResource(R.string.rpm), color = TelemetryOnSurfaceMuted, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
            Text(text = "${data?.rpm ?: 0}", color = TelemetryAccent, fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun SpeedRpmCard(data: TelemetryRecord?, isLandscape: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(CardGradient, RoundedCornerShape(16.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .widthIn(min = 120.dp)
    ) {
        Text(text = stringResource(R.string.speed), color = TelemetryOnSurfaceMuted, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
        Text(text = "${data?.speed ?: 0}", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
        Text(text = stringResource(R.string.unit_kmh), color = TelemetryOnSurfaceMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        Text(text = stringResource(R.string.rpm), color = TelemetryOnSurfaceMuted, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
        Text(text = "${data?.rpm ?: 0}", color = TelemetryAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun GearReadout(data: TelemetryRecord?, fontSize: androidx.compose.ui.unit.TextUnit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (data?.gear == 0) "N" else "${data?.gear ?: 0}",
            color = if (data?.gear == 0) TelemetryAccent else Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.ExtraBold
        )
        Text(text = stringResource(R.string.gear), color = TelemetryOnSurfaceMuted, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun VerticalDivider() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(CardBorder)
    )
}

@Composable
fun LeanGauge(
    currentLean: Float?,
    leanSource: LeanSource,
    onToggleSource: () -> Unit,
    onCalibrate: () -> Unit,
    circleSize: Dp,
    canvasSize: Dp,
    overlayReadout: Boolean,
    modifier: Modifier = Modifier
) {
    val lean = (currentLean ?: 0f).coerceIn(-60f, 60f)
    val tickAngles = (-60..60 step 10).toList()
    val labelAngles = listOf(-60, -30, 0, 30, 60)

    Box(
        modifier = modifier
            .size(circleSize)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFF1C1C1C), Color(0xFF141414))))
            .border(1.dp, CardBorder, CircleShape)
            .clickable { onToggleSource() },
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.size(canvasSize)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f
                val tickOuter = radius * 0.98f
                val tickInnerMinor = radius * 0.86f
                val tickInnerMajor = radius * 0.80f

                drawCircle(color = Color(0xFF262626), radius = radius * 0.92f, style = Stroke(width = 1.5.dp.toPx()))

                tickAngles.forEach { angleDeg ->
                    val isMajor = angleDeg % 30 == 0
                    val isLimit = angleDeg == -60 || angleDeg == 60
                    val rad = Math.toRadians((-90 + angleDeg).toDouble())
                    val cosA = cos(rad).toFloat()
                    val sinA = sin(rad).toFloat()
                    val innerR = if (isMajor) tickInnerMajor else tickInnerMinor
                    drawLine(
                        color = if (isLimit) Color(0xFFFF1744) else if (isMajor) Color(0xFFAAAAAA) else Color(0xFF444444),
                        start = Offset(center.x + innerR * cosA, center.y + innerR * sinA),
                        end = Offset(center.x + tickOuter * cosA, center.y + tickOuter * sinA),
                        strokeWidth = if (isMajor) 2.5.dp.toPx() else 1.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                if (abs(lean) > 0.5f) {
                    drawArc(
                        color = Color(0xFF00B4FF),
                        startAngle = -90f,
                        sweepAngle = lean,
                        useCenter = false,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                        topLeft = Offset(center.x - tickInnerMajor, center.y - tickInnerMajor),
                        size = Size(tickInnerMajor * 2, tickInnerMajor * 2)
                    )
                }
            }
            labelAngles.forEach { angleDeg ->
                val rad = Math.toRadians((-90 + angleDeg).toDouble())
                val bx = (cos(rad) * 0.62).toFloat()
                val by = (sin(rad) * 0.62).toFloat()
                Text(
                    text = "${abs(angleDeg)}",
                    color = Color(0xFF8A8A8A),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(BiasAlignment(bx, by))
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .rotate(lean)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawLine(
                        color = Color(0xFFFF1744),
                        start = center.copy(y = center.y - size.height * 0.38f),
                        end = center.copy(y = center.y + size.height * 0.38f),
                        strokeWidth = 7.dp.toPx()
                    )
                    drawCircle(color = Color(0xFFE8E8E8), radius = 5.dp.toPx())
                }
            }
        }
        Icon(
            imageVector = Icons.Default.SwapHoriz,
            contentDescription = null,
            tint = Color.Gray.copy(alpha = 0.45f),
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(16.dp)
        )
        if (overlayReadout) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = circleSize * 0.1f)) {
                LeanReadoutBadge(currentLean, leanSource, onCalibrate, compact = true)
            }
        }
    }
    if (!overlayReadout) {
        Spacer(modifier = Modifier.height(12.dp))
        LeanReadoutBadge(currentLean, leanSource, onCalibrate, compact = false)
    }
}

@Composable
fun LeanReadoutBadge(currentLean: Float?, leanSource: LeanSource, onCalibrate: () -> Unit, compact: Boolean) {
    Row(
        modifier = Modifier
            .background(BadgeBg, RoundedCornerShape(100.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(100.dp))
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 3.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${currentLean?.toInt() ?: 0}°",
            color = Color.White,
            fontSize = if (compact) 13.sp else 19.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = if (leanSource == LeanSource.PHONE) Icons.Default.PhoneAndroid else Icons.Default.TwoWheeler,
            contentDescription = if (leanSource == LeanSource.PHONE) {
                stringResource(R.string.lean_source_phone)
            } else {
                stringResource(R.string.lean_source_bike)
            },
            tint = TelemetryOnSurfaceMuted,
            modifier = Modifier.size(if (compact) 14.dp else 18.dp)
        )
        if (leanSource == LeanSource.PHONE) {
            Spacer(modifier = Modifier.width(if (compact) 2.dp else 4.dp))
            IconButton(onClick = onCalibrate, modifier = Modifier.size(if (compact) 20.dp else 26.dp)) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.calibrate_angle),
                    tint = TelemetryOnSurfaceMuted,
                    modifier = Modifier.size(if (compact) 13.dp else 16.dp)
                )
            }
        }
    }
}

@Composable
fun BarsCard(data: TelemetryRecord?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xFF161616), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF232323), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GForceBar(label = stringResource(R.string.g_force_lat), value = data?.gForceLat ?: 0f)
        GForceBar(label = stringResource(R.string.g_force_lon), value = data?.gForceLon ?: 0f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF232323))
        )
        BarIndicator(label = stringResource(R.string.throttle), value = (data?.throttle ?: 0) / 100f, color = Color(0xFFFFE600))
        BarIndicator(label = stringResource(R.string.brake_front), value = (data?.brakeFront ?: 0) / 100f, color = Color(0xFFFF1744))
        BarIndicator(label = stringResource(R.string.brake_rear), value = (data?.brakeRear ?: 0) / 100f, color = Color(0xFFFF00FF))
    }
}

@Composable
fun GForceBar(label: String, value: Float, maxG: Float = 1.2f, segments: Int = 9) {
    val litCount = ((abs(value) / maxG).coerceIn(0f, 1f) * segments).toInt()
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, color = TelemetryOnSurfaceMuted, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
            Text(text = "%.2f g".format(value), color = Color(0xFFCCCCCC), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(5.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(segments) { i ->
                val lit = i < litCount
                val color = when {
                    !lit -> Color(0xFF2B2B2B)
                    i >= 7 -> Color(0xFFFF1744)
                    i >= 5 -> Color(0xFFFFE600)
                    else -> Color(0xFF00E676)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .background(color, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

@Composable
fun BarIndicator(label: String, value: Float, color: Color) {
    val animatedValue by animateFloatAsState(targetValue = value.coerceIn(0f, 1f))
    val percent = (value.coerceIn(0f, 1f) * 100).toInt()

    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, color = TelemetryOnSurfaceMuted, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
            Text(text = "$percent%", color = Color(0xFFCCCCCC), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(BarTrack, RoundedCornerShape(100.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedValue)
                    .fillMaxHeight()
                    .background(color, RoundedCornerShape(100.dp))
            )
        }
    }
}
