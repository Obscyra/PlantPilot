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
import kotlin.math.sin

@Composable
fun WaterTankIndicator(
    tankCapacityMl: Int,
    estimatedWaterMl: Int,
    modifier: Modifier = Modifier
) {
    val clampedLevel = mlToLevel(estimatedWaterMl.coerceAtLeast(0), tankCapacityMl)
    val approxMl = estimatedWaterMl.coerceAtLeast(0)
    val isLow = clampedLevel <= 1
    val fillFraction = if (tankCapacityMl > 0) {
        (estimatedWaterMl.coerceIn(0, tankCapacityMl).toFloat() / tankCapacityMl).coerceIn(0f, 1f)
    } else 0f

    val animatedFill = remember { Animatable(0f) }
    LaunchedEffect(estimatedWaterMl, tankCapacityMl) {
        animatedFill.animateTo(
            targetValue = fillFraction,
            animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing)
        )
    }

    // Slow, gentle wave animation
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )
    // Second wave layer for depth — slower and offset
    val wavePhase2 by infiniteTransition.animateFloat(
        initialValue = Math.PI.toFloat(),
        targetValue = 3f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase2"
    )

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
                val left = strokeWidth / 2
                val right = size.width - strokeWidth / 2
                val top = strokeWidth / 2
                val bottom = size.height - strokeWidth / 2
                val tankWidth = right - left
                val tankHeight = bottom - top
                val cornerRadius = 16.dp.toPx()

                val tankPath = Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left, top, right, bottom,
                            CornerRadius(cornerRadius)
                        )
                    )
                }

                val fillPercentage = animatedFill.value
                val fillHeight = tankHeight * fillPercentage
                val waterTop = bottom - fillHeight

                if (fillHeight > 0) {
                    val waveAmplitude = 3.dp.toPx()
                    val steps = 60

                    clipPath(tankPath) {
                        // Back wave — slow, gentle, subtle
                        val backWave = Path().apply {
                            moveTo(left, bottom)
                            for (i in 0..steps) {
                                val frac = i.toFloat() / steps
                                val x = left + tankWidth * frac
                                val y = waterTop + sin(wavePhase2 + frac * 6f) * waveAmplitude * 0.6f
                                if (i == 0) lineTo(x, y) else lineTo(x, y)
                            }
                            lineTo(right, bottom)
                            close()
                        }
                        drawPath(
                            path = backWave,
                            color = fillColor.copy(alpha = 0.2f)
                        )

                        // Front wave — main surface
                        val frontWave = Path().apply {
                            moveTo(left, bottom)
                            for (i in 0..steps) {
                                val frac = i.toFloat() / steps
                                val x = left + tankWidth * frac
                                val y = waterTop + sin(wavePhase + frac * 8f) * waveAmplitude
                                lineTo(x, y)
                            }
                            lineTo(right, bottom)
                            close()
                        }
                        drawPath(
                            path = frontWave,
                            brush = Brush.verticalGradient(
                                colors = listOf(fillColor.copy(alpha = 0.45f), fillColor),
                                startY = waterTop,
                                endY = bottom
                            )
                        )

                        // Subtle highlight near surface
                        val highlightPath = Path().apply {
                            val hlHeight = 5.dp.toPx()
                            moveTo(left, waterTop)
                            for (i in 0..steps) {
                                val frac = i.toFloat() / steps
                                val x = left + tankWidth * frac
                                val y = waterTop + sin(wavePhase + frac * 8f) * waveAmplitude
                                lineTo(x, y)
                            }
                            for (i in steps downTo 0) {
                                val frac = i.toFloat() / steps
                                val x = left + tankWidth * frac
                                val y = waterTop + hlHeight + sin(wavePhase + frac * 8f) * waveAmplitude
                                lineTo(x, y)
                            }
                            close()
                        }
                        drawPath(
                            path = highlightPath,
                            color = Color.White.copy(alpha = 0.12f)
                        )
                    }
                }

                // Tank outline
                drawPath(
                    path = tankPath,
                    color = Color.Gray.copy(alpha = 0.4f),
                    style = Stroke(strokeWidth)
                )

                // Cap/Handle
                drawRoundRect(
                    color = Color.Gray.copy(alpha = 0.5f),
                    topLeft = Offset(size.width / 2 - 20.dp.toPx(), -strokeWidth / 2),
                    size = Size(40.dp.toPx(), 8.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }
        }
    }
}

private fun mlToLevel(ml: Int, capacity: Int): Int {
    if (capacity <= 0) return 0
    val pct = (ml.toFloat() / capacity * 100).toInt().coerceIn(0, 100)
    return when {
        pct <= 10 -> 0
        pct <= 35 -> 1
        pct <= 60 -> 2
        pct <= 85 -> 3
        else -> 4
    }
}

@Preview(showBackground = true)
@Composable
fun WaterTankIndicatorPreview() {
    PlantPilotTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            WaterTankIndicator(
                tankCapacityMl = 2000,
                estimatedWaterMl = 1500
            )
            Spacer(modifier = Modifier.height(16.dp))
            WaterTankIndicator(
                tankCapacityMl = 2000,
                estimatedWaterMl = 500
            )
        }
    }
}
