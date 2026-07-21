package com.example.mototelemetryapp.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mototelemetryapp.data.TelemetryRecord

@Composable
fun DashboardScreen(data: TelemetryRecord?) {
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
                Text(text = "HIZ", color = Color.Gray, fontSize = 12.sp)
                Text(
                    text = "${data?.speed ?: 0}",
                    color = Color.White,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(text = "km/h", color = Color.Gray, fontSize = 14.sp)
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(text = "DEVİR", color = Color.Gray, fontSize = 12.sp)
                Text(
                    text = "${data?.rpm ?: 0}",
                    color = Color(0xFF00E676),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Yatış Açısı Görseli (Basit bir motosiklet silüeti gibi eğilen çizgi)
        Box(
            modifier = Modifier
                .size(200.dp)
                .rotate(data?.leanAngle ?: 0f),
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
        }
        Text(
            text = "${data?.leanAngle?.toInt() ?: 0}°",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.weight(1f))

        // Gaz ve Fren Barları
        Column(modifier = Modifier.fillMaxWidth()) {
            // Gaz
            BarIndicator(label = "GAZ", value = (data?.throttle ?: 0) / 100f, color = Color.Yellow)
            Spacer(modifier = Modifier.height(8.dp))
            // Ön Fren
            BarIndicator(label = "ÖN FREN", value = (data?.brakeFront ?: 0) / 100f, color = Color.Red)
            Spacer(modifier = Modifier.height(8.dp))
            // Arka Fren
            BarIndicator(label = "ARKA FREN", value = (data?.brakeRear ?: 0) / 100f, color = Color.Magenta)
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
