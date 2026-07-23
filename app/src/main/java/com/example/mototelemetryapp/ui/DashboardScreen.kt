package com.example.mototelemetryapp.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mototelemetryapp.LeanSource
import com.example.mototelemetryapp.R
import com.example.mototelemetryapp.data.TelemetryRecord

@Composable
fun DashboardScreen(
    data: TelemetryRecord?,
    leanSource: LeanSource,
    onToggleSource: () -> Unit,
    onCalibrate: () -> Unit
) {
    val currentLean = if (leanSource == LeanSource.PHONE) data?.leanAnglePhone else data?.leanAngleBike
    val leanLabel = if (leanSource == LeanSource.PHONE) "TEL" else "MOTO"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212)) // Koyu tema
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Hız ve Devir Alanı
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = stringResource(R.string.speed), color = Color.Gray, fontSize = 12.sp)
                Text(
                    text = "${data?.speed ?: 0}",
                    color = Color.White,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(text = stringResource(R.string.unit_kmh), color = Color.Gray, fontSize = 14.sp)
            }

            // Vites Göstergesi
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = stringResource(R.string.gear), color = Color.Gray, fontSize = 12.sp)
                Text(
                    text = if (data?.gear == 0) "N" else "${data?.gear ?: 0}",
                    color = if (data?.gear == 0) Color(0xFF00E676) else Color.White,
                    fontSize = 80.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(text = stringResource(R.string.rpm), color = Color.Gray, fontSize = 12.sp)
                Text(
                    text = "${data?.rpm ?: 0}",
                    color = Color(0xFF00E676),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Yatış Açısı Görseli
        Box(
            modifier = Modifier
                .size(200.dp)
                .rotate(currentLean ?: 0f)
                .clickable { onToggleSource() },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(150.dp)) {
                drawCircle(
                    color = Color.DarkGray,
                    style = Stroke(width = 4.dp.toPx())
                )
                // Motosiklet "Yatış" çizgisi
                drawLine(
                    color = Color.Red,
                    start = center.copy(y = center.y - 60.dp.toPx()),
                    end = center.copy(y = center.y + 60.dp.toPx()),
                    strokeWidth = 8.dp.toPx()
                )
            }
            // Kaynak Göstergesi
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = null,
                tint = Color.Gray.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            )
        }
        
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "${currentLean?.toInt() ?: 0}°",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = leanLabel,
                color = Color.Gray,
                fontSize = 12.sp
            )
            if (leanSource == LeanSource.PHONE) {
                Spacer(modifier = Modifier.width(16.dp))
                IconButton(onClick = onCalibrate, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.calibrate_angle),
                        tint = Color.Gray
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Gaz ve Fren Barları
        Column(modifier = Modifier.fillMaxWidth()) {
            // Gaz
            BarIndicator(label = stringResource(R.string.throttle), value = (data?.throttle ?: 0) / 100f, color = Color.Yellow)
            Spacer(modifier = Modifier.height(8.dp))
            // Ön Fren
            BarIndicator(label = stringResource(R.string.brake_front), value = (data?.brakeFront ?: 0) / 100f, color = Color.Red)
            Spacer(modifier = Modifier.height(8.dp))
            // Arka Fren
            BarIndicator(label = stringResource(R.string.brake_rear), value = (data?.brakeRear ?: 0) / 100f, color = Color.Magenta)
        }
    }
}

@Composable
fun BarIndicator(label: String, value: Float, color: Color) {
    val animatedValue by animateFloatAsState(targetValue = value.coerceIn(0f, 1f))
    
    Column {
        Text(text = label, color = Color.Gray, fontSize = 10.sp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .background(Color.DarkGray, RoundedCornerShape(4.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedValue)
                    .fillMaxHeight()
                    .background(color, RoundedCornerShape(4.dp))
            )
        }
    }
}
