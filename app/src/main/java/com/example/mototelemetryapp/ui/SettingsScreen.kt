package com.example.mototelemetryapp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mototelemetryapp.BuildConfig
import com.example.mototelemetryapp.MAX_RIDE_GRACE_PERIOD_MINUTES
import com.example.mototelemetryapp.MIN_RIDE_GRACE_PERIOD_MINUTES
import com.example.mototelemetryapp.R
import com.example.mototelemetryapp.ui.theme.TelemetryAccent
import com.example.mototelemetryapp.ui.theme.TelemetryOnSurface
import com.example.mototelemetryapp.ui.theme.TelemetryOnSurfaceMuted

// Single home for every app setting. New settings should be added here as another
// SettingsToggleRow/SettingsStepperRow (or a new section), not scattered across other screens.
@Composable
fun SettingsScreen(
    autoStartOnObdConnect: Boolean,
    onAutoStartToggleChange: (Boolean) -> Unit,
    rideGracePeriodMinutes: Int,
    onRideGracePeriodChange: (Int) -> Unit,
    batteryOptExempt: Boolean,
    onRequestBatteryOptExemption: () -> Unit,
    currentLocaleTag: String,
    onLanguageChange: (String) -> Unit,
    onShareDiagnosticLog: () -> Unit,
    onUploadDiagnosticsToDrive: () -> Unit,
    diagnosticsUploadStatus: String? = null,
    showDebugSection: Boolean = false,
    simulateObd: Boolean = false,
    onSimulateObdChange: (Boolean) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(24.dp))

        SettingsSectionLabel(stringResource(R.string.settings_section_tracking))
        Spacer(modifier = Modifier.height(8.dp))
        SettingsToggleRow(
            label = stringResource(R.string.auto_start_on_obd_connect),
            checked = autoStartOnObdConnect,
            onCheckedChange = onAutoStartToggleChange
        )
        if (autoStartOnObdConnect) {
            Spacer(modifier = Modifier.height(8.dp))
            SettingsStepperRow(
                label = stringResource(R.string.ride_grace_period, rideGracePeriodMinutes),
                value = rideGracePeriodMinutes,
                onValueChange = onRideGracePeriodChange,
                min = MIN_RIDE_GRACE_PERIOD_MINUTES,
                max = MAX_RIDE_GRACE_PERIOD_MINUTES
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
        SettingsSectionLabel(stringResource(R.string.settings_section_battery))
        Spacer(modifier = Modifier.height(8.dp))
        if (!batteryOptExempt) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF241C0A), RoundedCornerShape(10.dp))
                    .clickable(onClick = onRequestBatteryOptExemption)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.battery_optimization_hint), color = Color(0xFFFFC107), fontSize = 11.sp)
            }
        } else {
            Text(
                text = stringResource(R.string.battery_optimization_exempt),
                color = TelemetryOnSurfaceMuted,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
        SettingsSectionLabel(stringResource(R.string.settings_section_language))
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Language, contentDescription = null, tint = TelemetryOnSurfaceMuted)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.select_language), color = TelemetryOnSurfaceMuted)
        }
        Row {
            TextButton(onClick = { onLanguageChange("en") }) {
                Text(
                    "English",
                    color = if (currentLocaleTag == "en") TelemetryAccent else TelemetryOnSurfaceMuted,
                    fontWeight = FontWeight.SemiBold
                )
            }
            TextButton(onClick = { onLanguageChange("tr") }) {
                Text(
                    "Türkçe",
                    color = if (currentLocaleTag == "tr") TelemetryAccent else TelemetryOnSurfaceMuted,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
        SettingsSectionLabel(stringResource(R.string.settings_section_diagnostics))
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161616), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF232323), RoundedCornerShape(10.dp))
                .clickable(onClick = onShareDiagnosticLog)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Default.BugReport, contentDescription = null, tint = TelemetryOnSurfaceMuted, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.share_diagnostic_log), color = TelemetryOnSurface, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.share_diagnostic_log_hint),
            color = TelemetryOnSurfaceMuted,
            fontSize = 10.sp
        )

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161616), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF232323), RoundedCornerShape(10.dp))
                .clickable(onClick = onUploadDiagnosticsToDrive)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Default.BugReport, contentDescription = null, tint = TelemetryOnSurfaceMuted, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.upload_diagnostics_to_drive), color = TelemetryOnSurface, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = diagnosticsUploadStatus ?: stringResource(R.string.upload_diagnostics_to_drive_hint),
            color = if (diagnosticsUploadStatus != null) TelemetryAccent else TelemetryOnSurfaceMuted,
            fontSize = 10.sp
        )

        // Debug builds only (see BuildConfig.DEBUG at the call site) - the simulator is hard-gated
        // in isRideSimulationEnabled() too, so a release build ignores the stored value regardless.
        if (showDebugSection) {
            Spacer(modifier = Modifier.height(28.dp))
            SettingsSectionLabel(stringResource(R.string.settings_section_debug))
            Spacer(modifier = Modifier.height(8.dp))
            SettingsToggleRow(
                label = stringResource(R.string.simulate_obd),
                checked = simulateObd,
                onCheckedChange = onSimulateObdChange
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.simulate_obd_hint),
                color = TelemetryOnSurfaceMuted,
                fontSize = 10.sp
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            color = Color(0xFF4A4A4A),
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        color = Color(0xFF5A5A5A),
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp
    )
}

@Composable
private fun SettingsToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161616), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF232323), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = TelemetryOnSurface,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
        MinimalSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MinimalSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val trackColor by animateColorAsState(if (checked) TelemetryAccent else Color(0xFF333333), label = "trackColor")
    val knobOffset by animateDpAsState(if (checked) 18.dp else 2.dp, label = "knobOffset")
    Box(
        modifier = Modifier
            .size(width = 38.dp, height = 22.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(trackColor)
            .clickable { onCheckedChange(!checked) }
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp, start = knobOffset)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
private fun SettingsStepperRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    min: Int,
    max: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161616), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF232323), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = TelemetryOnSurface,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onValueChange((value - 1).coerceAtLeast(min)) },
                enabled = value > min
            ) {
                Text(text = "−", color = TelemetryAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "$value",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.widthIn(min = 24.dp),
                textAlign = TextAlign.Center
            )
            IconButton(
                onClick = { onValueChange((value + 1).coerceAtMost(max)) },
                enabled = value < max
            ) {
                Text(text = "+", color = TelemetryAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
