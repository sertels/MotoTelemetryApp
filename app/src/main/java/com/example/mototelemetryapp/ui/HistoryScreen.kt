package com.example.mototelemetryapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mototelemetryapp.R
import com.example.mototelemetryapp.data.TelemetryRecord
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun HistoryScreen(records: List<TelemetryRecord>) {
    val points = remember(records) {
        records.map { LatLng(it.latitude, it.longitude) }
            .filter { it.latitude != 0.0 && it.longitude != 0.0 }
    }

    val cameraPositionState = rememberCameraPositionState {
        position = if (points.isNotEmpty()) {
            CameraPosition.fromLatLngZoom(points.first(), 15f)
        } else {
            CameraPosition.fromLatLngZoom(LatLng(41.0082, 28.9784), 10f) // İstanbul default
        }
    }

    val totalKm = remember(points) { routeDistanceKm(points) }
    val durationMin = remember(records) {
        if (records.size >= 2) (records.last().timestamp - records.first().timestamp) / 60000f else 0f
    }
    val avgKmh = if (durationMin > 0f) totalKm / (durationMin / 60f) else 0f

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState
        ) {
            if (points.isNotEmpty()) {
                Polyline(
                    points = points,
                    color = Color.Red,
                    width = 10f
                )
            }
        }

        if (points.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(14.dp)
                    .fillMaxWidth()
                    .background(Color(0xCC121212), RoundedCornerShape(12.dp))
                    .padding(vertical = 10.dp, horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                RouteStat(value = "%.1f".format(totalKm), label = stringResource(R.string.km_short))
                RouteStat(value = "${durationMin.toInt()}", label = stringResource(R.string.min_short))
                RouteStat(value = "${avgKmh.toInt()}", label = stringResource(R.string.avg_kmh))
            }
        }
    }
}

@Composable
private fun RouteStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(text = label, color = Color(0xFF808080), fontSize = 9.sp, letterSpacing = 0.5.sp)
    }
}

private fun routeDistanceKm(points: List<LatLng>): Float {
    if (points.size < 2) return 0f
    var total = 0.0
    for (i in 1 until points.size) {
        total += haversineKm(points[i - 1], points[i])
    }
    return total.toFloat()
}

private fun haversineKm(a: LatLng, b: LatLng): Double {
    val earthRadiusKm = 6371.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
    return 2 * earthRadiusKm * atan2(sqrt(h), sqrt(1 - h))
}
