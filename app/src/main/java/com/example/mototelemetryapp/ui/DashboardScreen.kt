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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

private val CardBorder = Color(0xFF262626)
private val CardGradient = Brush.verticalGradient(listOf(Color(0xFF1C1C1C), Color(0xFF161616)))
private val BarTrack = Color(0xFF2B2B2B)
private val BadgeBg = Color(0xFF1A1A1A)

// Amber, deliberately not the accent colour used for a healthy real connection.
val ObdSimulatedAccent = Color(0xFFFFB300)

@Composable
fun DashboardScreen(
    data: TelemetryRecord?,
    leanSource: LeanSource,
    onToggleSource: () -> Unit,
    onCalibrate: () -> Unit,
    maxLeanLeft: Float = 0f,
    maxLeanRight: Float = 0f,
    maxGForceLat: Float = 0f,
    maxGForceLon: Float = 0f
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
                .padding(horizontal = 18.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LiveIndicator()
                GpsCoordsText(data)
            }
            // The status row above is pinned to the top; everything else shares the rest of the
            // screen's height and is centered in it, rather than clumping at the top with a dead
            // gap below - landscape has plenty of vertical room this cluster wasn't using.
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                // fillMaxWidth (not wrap-content centered) so BarsCard's weight(1f) below has
                // actual leftover width to consume - SpeedGearRpmCard and the gauge are fixed
                // width, everything past them belongs to the bars.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Intrinsic (not a guessed fixed dp) so it takes only what its three columns
                    // actually need - a hardcoded width here previously starved BarsCard of room,
                    // which made its labels wrap and blow its height past the row's.
                    SpeedGearRpmCard(
                        data,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.width(IntrinsicSize.Min)
                    )
                    LeanGauge(
                        currentLean = currentLean,
                        leanSource = leanSource,
                        onToggleSource = onToggleSource,
                        onCalibrate = onCalibrate,
                        maxLeanLeft = maxLeanLeft,
                        maxLeanRight = maxLeanRight,
                        circleSize = 170.dp
                    )
                    BarsCard(data, maxGForceLat = maxGForceLat, maxGForceLon = maxGForceLon, compact = true, modifier = Modifier.weight(1f))
                }
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
            Spacer(modifier = Modifier.height(10.dp))
            SpeedGearRpmCard(data)
            Spacer(modifier = Modifier.height(10.dp))
            LeanGauge(
                currentLean = currentLean,
                leanSource = leanSource,
                onToggleSource = onToggleSource,
                onCalibrate = onCalibrate,
                maxLeanLeft = maxLeanLeft,
                maxLeanRight = maxLeanRight,
                circleSize = 150.dp
            )
            Spacer(modifier = Modifier.height(8.dp))
            BarsCard(data, maxGForceLat = maxGForceLat, maxGForceLon = maxGForceLon, modifier = Modifier.fillMaxWidth())
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
    simulated: Boolean = false,
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
            // Simulated data gets its own amber treatment rather than the usual accent green, so
            // a glance (or a screenshot) can never mistake invented telemetry for a real bike.
            val dotColor = when {
                connected && simulated -> ObdSimulatedAccent
                connected -> TelemetryAccent
                else -> Color(0xFF5A5A5A)
            }
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(
                    when {
                        connected && simulated -> R.string.obd_simulated
                        connected -> R.string.obd_connected
                        else -> R.string.obd_disconnected
                    }
                ),
                color = when {
                    connected && simulated -> ObdSimulatedAccent
                    connected -> Color.White
                    else -> TelemetryOnSurfaceMuted
                },
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
fun SpeedGearRpmCard(
    data: TelemetryRecord?,
    // SpaceBetween spreads content to the card's full width, which is right for portrait's
    // fillMaxWidth card - but landscape sizes this card with IntrinsicSize.Min, where the Row's
    // width equals its own minimum, and SpaceBetween's "spread across the extra space" has none
    // left to work with and degenerates to zero gaps between columns. spacedBy guarantees a gap
    // regardless of how the width was determined, so the landscape caller passes that instead.
    horizontalArrangement: Arrangement.Horizontal = Arrangement.SpaceBetween,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp), clip = false)
            .background(CardGradient, RoundedCornerShape(20.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 18.dp)
            // This Row sits inside a verticalScroll Column, which measures children with
            // unbounded height - so VerticalDivider's fillMaxHeight(fraction) was resolving
            // against infinity and collapsing to 0dp, not just being low-contrast. Height =
            // IntrinsicSize.Min gives the Row (and so the divider) an actual bounded height to
            // be a fraction of.
            .height(IntrinsicSize.Min),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            // Every Text here (labels included) is single-line/no-wrap: this card is sized via
            // IntrinsicSize.Min in landscape (see LeanGauge's caller), which shrinks the column to
            // whichever of label/value has the narrowest natural width. With "0" as the value
            // (e.g. OBD disconnected) that's narrower than "SPEED"/"RPM", and an unguarded label
            // would get dragged down to that width and wrap mid-word.
            Text(text = stringResource(R.string.speed), color = TelemetryOnSurfaceMuted, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            Text(text = "${data?.speed ?: 0}", color = Color.White, fontSize = 58.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            Text(text = stringResource(R.string.unit_kmh), color = TelemetryOnSurfaceMuted, fontSize = 13.sp, maxLines = 1, softWrap = false)
        }
        VerticalDivider()
        GearReadout(data, fontSize = 68.sp)
        VerticalDivider()
        Column(horizontalAlignment = Alignment.End) {
            Text(text = stringResource(R.string.rpm), color = TelemetryOnSurfaceMuted, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            Text(text = "${data?.rpm ?: 0}", color = TelemetryAccent, fontSize = 27.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun GearReadout(data: TelemetryRecord?, fontSize: androidx.compose.ui.unit.TextUnit) {
    // Label above the value, matching how SPEED and RPM are laid out either side of this - it
    // was below before, which read as a different kind of stat in the middle of two consistent
    // ones.
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = stringResource(R.string.gear), color = TelemetryOnSurfaceMuted, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        Text(
            text = if (data?.gear == 0) "N" else "${data?.gear ?: 0}",
            color = if (data?.gear == 0) TelemetryAccent else Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
fun VerticalDivider() {
    // Shortened and lightened from a full-height 1dp line at CardBorder (0xFF262626) against a
    // ~0xFF1C1C1C/0x161616 card - close enough in value to read as no separator at all.
    Box(
        modifier = Modifier
            .fillMaxHeight(0.62f)
            .width(1.dp)
            .background(Color(0xFF3D3D3D))
    )
}

// Ring gauge: a full-circle track with a colored progress arc from top toward the current lean
// (clamped to +-LEAN_LIMIT_DEG, same "bike is basically on the floor past this" reasoning as the
// old needle dial's tick range) and a big center readout, styled after extra_info/center.png.
// Side labels show the max lean reached this ride, in each direction, next to the ring.
private const val LEAN_LIMIT_DEG = 60f

@Composable
fun LeanGauge(
    currentLean: Float?,
    leanSource: LeanSource,
    onToggleSource: () -> Unit,
    onCalibrate: () -> Unit,
    maxLeanLeft: Float,
    maxLeanRight: Float,
    circleSize: Dp,
    modifier: Modifier = Modifier
) {
    val lean = (currentLean ?: 0f).coerceIn(-LEAN_LIMIT_DEG, LEAN_LIMIT_DEG)
    val nearLimit = abs(currentLean ?: 0f) >= LEAN_LIMIT_DEG - 3f
    val arcColor = if (nearLimit) Color(0xFFFF1744) else Color(0xFF00B4FF)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MaxLeanLabel(maxLeanLeft, stringResource(R.string.max_lean_left))
        Box(
            modifier = Modifier
                .size(circleSize)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF1C1C1C), Color(0xFF141414))))
                .border(1.dp, CardBorder, CircleShape)
                .clickable { onToggleSource() },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                val strokeWidth = 7.dp.toPx()
                val ringRadius = size.minDimension / 2f - strokeWidth / 2f
                drawCircle(color = Color(0xFF262626), radius = ringRadius, style = Stroke(width = strokeWidth))
                if (abs(lean) > 0.5f) {
                    drawArc(
                        color = arcColor,
                        startAngle = -90f,
                        sweepAngle = lean,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                        topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                        size = Size(ringRadius * 2, ringRadius * 2)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${abs(currentLean?.toInt() ?: 0)}°",
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                LeanSourceRow(leanSource, onCalibrate)
            }
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = null,
                tint = Color.Gray.copy(alpha = 0.45f),
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(14.dp)
            )
        }
        MaxLeanLabel(maxLeanRight, stringResource(R.string.max_lean_right))
    }
}

@Composable
private fun MaxLeanLabel(value: Float, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = TelemetryOnSurfaceMuted, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = "${value.toInt()}°", color = Color(0xFFCCCCCC), fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LeanSourceRow(leanSource: LeanSource, onCalibrate: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (leanSource == LeanSource.PHONE) Icons.Default.PhoneAndroid else Icons.Default.TwoWheeler,
            contentDescription = if (leanSource == LeanSource.PHONE) {
                stringResource(R.string.lean_source_phone)
            } else {
                stringResource(R.string.lean_source_bike)
            },
            tint = TelemetryOnSurfaceMuted,
            modifier = Modifier.size(13.dp)
        )
        if (leanSource == LeanSource.PHONE) {
            IconButton(onClick = onCalibrate, modifier = Modifier.size(20.dp)) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.calibrate_angle),
                    tint = TelemetryOnSurfaceMuted,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
fun BarsCard(
    data: TelemetryRecord?,
    maxGForceLat: Float? = null,
    maxGForceLon: Float? = null,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF161616), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF232323), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = if (compact) 6.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp)
    ) {
        GForceBar(label = stringResource(R.string.g_force_lat), value = data?.gForceLat ?: 0f, maxValue = maxGForceLat, color = TelemetryAccent, compact = compact)
        GForceBar(label = stringResource(R.string.g_force_lon), value = data?.gForceLon ?: 0f, maxValue = maxGForceLon, color = Color(0xFFFF9100), compact = compact)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF232323))
        )
        BarIndicator(label = stringResource(R.string.throttle), value = (data?.throttle ?: 0) / 100f, color = Color(0xFFFFE600), compact = compact)
        BarIndicator(label = stringResource(R.string.brake_front), value = (data?.brakeFront ?: 0) / 100f, color = Color(0xFFFF1744), compact = compact)
        BarIndicator(label = stringResource(R.string.brake_rear), value = (data?.brakeRear ?: 0) / 100f, color = Color(0xFFFF00FF), compact = compact)
    }
}

@Composable
fun GForceBar(label: String, value: Float, maxValue: Float? = null, color: Color, maxG: Float = 1.2f, compact: Boolean = false) {
    val animatedValue by animateFloatAsState(targetValue = (abs(value) / maxG).coerceIn(0f, 1f))
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, color = TelemetryOnSurfaceMuted, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (maxValue != null) {
                    Text(text = "max %.2fg".format(maxValue), color = TelemetryOnSurfaceMuted, fontSize = if (compact) 8.sp else 9.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(text = "%.2f g".format(value), color = Color(0xFFCCCCCC), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(modifier = Modifier.height(if (compact) 3.dp else 5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 6.dp else 8.dp)
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

@Composable
fun BarIndicator(label: String, value: Float, color: Color, compact: Boolean = false) {
    val animatedValue by animateFloatAsState(targetValue = value.coerceIn(0f, 1f))
    val percent = (value.coerceIn(0f, 1f) * 100).toInt()

    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, color = TelemetryOnSurfaceMuted, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
            Text(text = "$percent%", color = Color(0xFFCCCCCC), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(if (compact) 3.dp else 5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 6.dp else 8.dp)
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
