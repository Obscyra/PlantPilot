package com.plantpilot.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.plantpilot.ui.theme.PlantPilotTheme
import com.plantpilot.ui.theme.NeonGreen
import com.plantpilot.ui.theme.ErrorDark

@Composable
fun WaterTankIndicator(
    level: Int,
    tankCapacityMl: Int,
    sensorValue: Int,
    modifier: Modifier = Modifier
) {
    val clampedLevel = level.coerceIn(0, 4)
    val approxMl = clampedLevel * tankCapacityMl / 4
    val isLow = clampedLevel <= 1

    val animatedLevel = remember { Animatable(0f) }
    LaunchedEffect(clampedLevel) {
        animatedLevel.animateTo(
            targetValue = clampedLevel.toFloat(),
            animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing)
        )
    }

    val fillColor = when {
        clampedLevel <= 1 -> MaterialTheme.colorScheme.error
        clampedLevel == 2 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = "Water Tank",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "~${approxMl} ml",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isLow) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Low Water — Please Refill",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(modifier = Modifier.width(24.dp))

        Box(
            modifier = Modifier
                .width(130.dp)
                .height(160.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 4.dp.toPx()
                val tankWidth = size.width - strokeWidth
                val tankHeight = size.height - strokeWidth
                val cornerRadius = 16.dp.toPx()
                
                // Draw water fill (background)
                val fillPercentage = animatedLevel.value / 4f
                val fillHeight = tankHeight * fillPercentage
                
                if (fillHeight > 0) {
                    val fillPath = Path().apply {
                        moveTo(strokeWidth / 2, size.height - strokeWidth / 2)
                        lineTo(size.width - strokeWidth / 2, size.height - strokeWidth / 2)
                        lineTo(size.width - strokeWidth / 2, size.height - fillHeight)
                        
                        // Waves
                        val waveWidth = tankWidth / 4
                        for (i in 0..4) {
                            val x = size.width - strokeWidth / 2 - i * waveWidth
                            val y = size.height - fillHeight + (if (i % 2 == 0) -4.dp.toPx() else 4.dp.toPx())
                            lineTo(x, y)
                        }
                        close()
                    }
                    
                    clipPath(Path().apply {
                        addRoundRect(
                            androidx.compose.ui.geometry.RoundRect(
                                strokeWidth / 2, strokeWidth / 2,
                                size.width - strokeWidth / 2, size.height - strokeWidth / 2,
                                CornerRadius(cornerRadius)
                            )
                        )
                    }) {
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(fillColor.copy(alpha = 0.6f), fillColor),
                                startY = size.height - fillHeight,
                                endY = size.height
                            )
                        )
                    }
                }

                // Tank outline
                drawRoundRect(
                    color = Color.Gray.copy(alpha = 0.4f),
                    topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                    size = Size(tankWidth, tankHeight),
                    cornerRadius = CornerRadius(cornerRadius),
                    style = Stroke(strokeWidth)
                )
                
                // Cap/Handle design
                drawRoundRect(
                    color = Color.Gray.copy(alpha = 0.5f),
                    topLeft = Offset(size.width / 2 - 20.dp.toPx(), - strokeWidth / 2),
                    size = Size(40.dp.toPx(), 8.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun WaterTankIndicatorPreview() {
    PlantPilotTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            WaterTankIndicator(
                level = 3,
                tankCapacityMl = 2000,
                sensorValue = 750
            )
            Spacer(modifier = Modifier.height(16.dp))
            WaterTankIndicator(
                level = 1,
                tankCapacityMl = 2000,
                sensorValue = 250
            )
        }
    }
}
