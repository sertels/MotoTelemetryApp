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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mototelemetryapp.LeanSource
import com.example.mototelemetryapp.R
import com.example.mototelemetryapp.data.TelemetryRecord
import com.example.mototelemetryapp.ui.theme.TelemetryAccent
import com.example.mototelemetryapp.ui.theme.TelemetryOnSurfaceMuted

private val CardBorder = Color(0xFF262626)
private val CardGradient = Brush.verticalGradient(listOf(Color(0xFF1C1C1C), Color(0xFF161616)))
private val BarTrack = Color(0xFF2B2B2B)
private val BadgeBg = Color(0xFF1A1A1A)

@Composable
fun DashboardScreen(
    data: TelemetryRecord?,
    leanSource: LeanSource,
    onToggleSource: () -> Unit,
    onCalibrate: () -> Unit
) {
    val currentLean = if (leanSource == LeanSource.PHONE) data?.leanAnglePhone else data?.leanAngleBike
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val backgroundBrush = Brush.radialGradient(
        colors = listOf(Color(0xFF181818), Color(0xFF0F0F0F)),
        radius = 900f
    )

    if (isLandscape) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SpeedRpmCard(data, isLandscape = true, modifier = Modifier.align(Alignment.CenterVertically))
            Column(
                modifier = Modifier.align(Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                GearReadout(data, fontSize = 44.sp)
            }
            LeanGauge(
                currentLean = currentLean,
                leanSource = leanSource,
                onToggleSource = onToggleSource,
                onCalibrate = onCalibrate,
                circleSize = 190.dp,
                canvasSize = 150.dp,
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
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LiveIndicator()
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
fun LiveIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .widthIn(min = 120.dp)
    ) {
        Text(text = stringResource(R.string.speed), color = TelemetryOnSurfaceMuted, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
        Text(text = "${data?.speed ?: 0}", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Text(text = stringResource(R.string.unit_kmh), color = TelemetryOnSurfaceMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
        Text(text = stringResource(R.string.rpm), color = TelemetryOnSurfaceMuted, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
        Text(text = "${data?.rpm ?: 0}", color = TelemetryAccent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
    Box(
        modifier = modifier
            .size(circleSize)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFF1C1C1C), Color(0xFF141414))))
            .border(1.dp, CardBorder, CircleShape)
            .clickable { onToggleSource() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(canvasSize)
                .rotate(currentLean ?: 0f)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(
                    color = Color(0xFF3A3A3A),
                    startAngle = 160f,
                    sweepAngle = 220f,
                    useCenter = false,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 6.dp.toPx()))
                    )
                )
                drawCircle(
                    color = Color(0xFF2A2A2A),
                    style = Stroke(width = 3.dp.toPx())
                )
                drawLine(
                    color = Color(0xFFFF1744),
                    start = center.copy(y = center.y - size.height * 0.38f),
                    end = center.copy(y = center.y + size.height * 0.38f),
                    strokeWidth = 7.dp.toPx()
                )
                drawCircle(color = Color(0xFFE8E8E8), radius = 5.dp.toPx())
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BarIndicator(label = stringResource(R.string.throttle), value = (data?.throttle ?: 0) / 100f, color = Color(0xFFFFE600))
        BarIndicator(label = stringResource(R.string.brake_front), value = (data?.brakeFront ?: 0) / 100f, color = Color(0xFFFF1744))
        BarIndicator(label = stringResource(R.string.brake_rear), value = (data?.brakeRear ?: 0) / 100f, color = Color(0xFFFF00FF))
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
