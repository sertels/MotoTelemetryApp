package com.example.mototelemetryapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onLanguageChange: (String) -> Unit
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

        Spacer(modifier = Modifier.height(32.dp))
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = TelemetryAccent)
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
