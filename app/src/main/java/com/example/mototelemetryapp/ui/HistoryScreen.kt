package com.example.mototelemetryapp.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.mototelemetryapp.data.TelemetryRecord
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState

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
}
