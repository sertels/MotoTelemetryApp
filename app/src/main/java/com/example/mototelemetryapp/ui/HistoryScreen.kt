package com.example.mototelemetryapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mototelemetryapp.R
import com.example.mototelemetryapp.data.TelemetryRecord
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// A route line doesn't need 5 points/second density - GPS points along a road are highly
// correlated, so decimating for the drawn Polyline doesn't visibly change its shape. Distance is
// still computed from every point (see RouteBasis below), just not drawn from every point.
private const val ROUTE_TARGET_POINTS = 1500

private class RouteBasis(
    val drawPoints: List<LatLng>,
    val bounds: LatLngBounds?,
    val totalKm: Float
)

@Composable
fun HistoryScreen(records: List<TelemetryRecord>) {
    // Mapping/filtering every record (up to ~18k on a real ~1hr ride) plus a haversine sum over
    // all of them was running synchronously in composition - the same "unmeasured main-thread
    // work over the full record count" pattern that caused jank on the Analysis charts
    // (2026-08-02). Doing it once here, off the main thread, avoids repeating that mistake.
    val routeBasis by produceState<RouteBasis?>(initialValue = null, records) {
        value = withContext(Dispatchers.Default) {
            val allPoints = records.map { LatLng(it.latitude, it.longitude) }
                .filter { it.latitude != 0.0 && it.longitude != 0.0 }
            if (allPoints.isEmpty()) {
                RouteBasis(emptyList(), null, 0f)
            } else {
                val stride = (allPoints.size / ROUTE_TARGET_POINTS).coerceAtLeast(1)
                val drawPoints = allPoints.filterIndexed { index, _ -> index % stride == 0 }
                val bounds = if (allPoints.size < 2) {
                    null
                } else {
                    val builder = LatLngBounds.Builder()
                    allPoints.forEach { builder.include(it) }
                    builder.build()
                }
                RouteBasis(drawPoints, bounds, routeDistanceKm(allPoints))
            }
        }
    }
    val points = routeBasis?.drawPoints ?: emptyList()

    val cameraPositionState = rememberCameraPositionState {
        position = if (points.isNotEmpty()) {
            CameraPosition.fromLatLngZoom(points.first(), 15f)
        } else {
            CameraPosition.fromLatLngZoom(LatLng(41.0082, 28.9784), 10f) // İstanbul default
        }
    }
    var isMapLoaded by remember { mutableStateOf(false) }

    // Fitting bounds needs the map's actual pixel size, which isn't available until it's
    // finished laying out - onMapLoaded below is what unblocks this.
    LaunchedEffect(routeBasis, isMapLoaded) {
        val bounds = routeBasis?.bounds
        if (isMapLoaded && bounds != null) {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 120))
        }
    }

    val totalKm = routeBasis?.totalKm ?: 0f
    val durationMin = remember(records) {
        if (records.size >= 2) (records.last().timestamp - records.first().timestamp) / 60000f else 0f
    }
    val avgKmh = if (durationMin > 0f) totalKm / (durationMin / 60f) else 0f

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            onMapLoaded = { isMapLoaded = true }
        ) {
            if (points.isNotEmpty()) {
                Polyline(
                    points = points,
                    color = Color(0xFFFF1744),
                    width = 10f
                )
                MarkerComposable(state = rememberUpdatedMarkerState(position = points.first())) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00B4FF))
                            .border(2.dp, Color(0xFF121212), CircleShape)
                    )
                }
                MarkerComposable(state = rememberUpdatedMarkerState(position = points.last())) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(2.dp, Color(0xFF121212), CircleShape)
                    )
                }
            }
        }

        if (points.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(14.dp)
                    .fillMaxWidth()
                    .background(Color(0xCC121212), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(12.dp))
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
