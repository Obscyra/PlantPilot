package com.plantpilot.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

    var initialAnimated by rememberSaveable { mutableStateOf(false) }
    val animatedFill = remember { Animatable(if (initialAnimated) fillFraction else 0f) }

    LaunchedEffect(estimatedWaterMl, tankCapacityMl) {
        if (!initialAnimated) {
            animatedFill.snapTo(0f)
            animatedFill.animateTo(
                targetValue = fillFraction,
                animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing)
            )
            initialAnimated = true
        } else {
            animatedFill.animateTo(
                targetValue = fillFraction,
                animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing)
            )
        }
    }

    // Slow, gentle wave animation
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val wavePhaseState = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )
    // Second wave layer for depth — slower and offset
    val wavePhase2State = infiniteTransition.animateFloat(
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
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Water Tank",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))

            val level1Ml = (tankCapacityMl * 0.25f).toInt()
            val level2Ml = (tankCapacityMl * 0.50f).toInt()
            val level3Ml = (tankCapacityMl * 0.75f).toInt()

            val (levelBadgeText, badgeBg, badgeFg) = when (clampedLevel) {
                4 -> Triple("Full (${tankCapacityMl} ml)", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                3 -> Triple("Level 3 (${level3Ml} ml)", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                2 -> Triple("Level 2 (${level2Ml} ml)", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                1 -> Triple("Level 1 (${level1Ml} ml)", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                else -> Triple("Empty (0 ml)", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            }

            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                color = badgeBg
            ) {
                Text(
                    text = levelBadgeText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = badgeFg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "$approxMl ml",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = "Capacity: $tankCapacityMl ml",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (isLow) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Low Water — Refill",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Box(
            modifier = Modifier
                .width(115.dp)
                .height(145.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val wavePhase = wavePhaseState.value
                val wavePhase2 = wavePhase2State.value
                val strokeWidth = 3.dp.toPx()
                val left = strokeWidth / 2 + 4.dp.toPx()
                val right = size.width - strokeWidth / 2 - 4.dp.toPx()
                val top = strokeWidth / 2 + 10.dp.toPx()
                val bottom = size.height - strokeWidth / 2
                val tankWidth = right - left
                val tankHeight = bottom - top
                val cornerRadius = 18.dp.toPx()

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
                val waveAmplitude = 4.dp.toPx()
                val rawWaterTop = bottom - fillHeight
                val waterTop = rawWaterTop.coerceAtLeast(top + waveAmplitude)

                // 1. Tank Background Container Glow
                drawPath(
                    path = tankPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF161B16),
                            Color(0xFF0F120F)
                        )
                    )
                )

                if (fillHeight > 0) {
                    val steps = 60

                    clipPath(tankPath) {
                        // 2. Back Wave — Cyan/Teal Under-layer
                        val backWave = Path().apply {
                            moveTo(left, bottom)
                            for (i in 0..steps) {
                                val frac = i.toFloat() / steps
                                val x = left + tankWidth * frac
                                val y = waterTop + sin(wavePhase2 + frac * 6.28f) * waveAmplitude * 0.7f
                                if (i == 0) lineTo(x, y) else lineTo(x, y)
                            }
                            lineTo(right, bottom)
                            close()
                        }
                        drawPath(
                            path = backWave,
                            color = fillColor.copy(alpha = 0.35f)
                        )

                        // 3. Main Water Body Gradient
                        val frontWave = Path().apply {
                            moveTo(left, bottom)
                            for (i in 0..steps) {
                                val frac = i.toFloat() / steps
                                val x = left + tankWidth * frac
                                val y = waterTop + sin(wavePhase + frac * 6.28f) * waveAmplitude
                                lineTo(x, y)
                            }
                            lineTo(right, bottom)
                            close()
                        }
                        drawPath(
                            path = frontWave,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    fillColor.copy(alpha = 0.85f),
                                    fillColor.copy(alpha = 0.5f),
                                    fillColor.copy(alpha = 0.25f)
                                ),
                                startY = waterTop,
                                endY = bottom
                            )
                        )

                        // 4. Wave Surface Specular Crest Line (Glow Highlight)
                        val crestPath = Path().apply {
                            for (i in 0..steps) {
                                val frac = i.toFloat() / steps
                                val x = left + tankWidth * frac
                                val y = waterTop + sin(wavePhase + frac * 6.28f) * waveAmplitude
                                if (i == 0) moveTo(x, y) else lineTo(x, y)
                            }
                        }
                        drawPath(
                            path = crestPath,
                            color = Color.White.copy(alpha = 0.6f),
                            style = Stroke(width = 2.dp.toPx())
                        )

                        // 5. Animated Micro-Bubbles rising in water column
                        val bubbleOffsets = listOf(
                            Pair(0.25f, (wavePhase * 40) % (tankHeight * 0.7f)),
                            Pair(0.55f, ((wavePhase + 2.5f) * 35) % (tankHeight * 0.7f)),
                            Pair(0.80f, ((wavePhase + 4.8f) * 45) % (tankHeight * 0.7f))
                        )
                        bubbleOffsets.forEach { (xFrac, yRise) ->
                            val bx = left + tankWidth * xFrac
                            val by = bottom - (yRise % fillHeight)
                            if (by > waterTop + 6.dp.toPx()) {
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.35f),
                                    radius = 2.dp.toPx(),
                                    center = Offset(bx, by)
                                )
                            }
                        }
                    }
                }

                // 6. Hardware Level Graduation Ticks (25%, 50%, 75%)
                for (level in 1..3) {
                    val tickY = bottom - (tankHeight * (level * 0.25f))
                    val isPassed = fillPercentage >= (level * 0.25f)
                    val tickColor = if (isPassed) fillColor.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.25f)
                    
                    drawLine(
                        color = tickColor,
                        start = Offset(right - 10.dp.toPx(), tickY),
                        end = Offset(right - 2.dp.toPx(), tickY),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }

                // 7. Metallic Glass Shell Outline
                drawPath(
                    path = tankPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.4f),
                            Color.White.copy(alpha = 0.15f)
                        )
                    ),
                    style = Stroke(strokeWidth)
                )

                // 8. Glass Surface Diagonal Sheen Glare
                val glarePath = Path().apply {
                    moveTo(left + tankWidth * 0.1f, top + 4.dp.toPx())
                    lineTo(left + tankWidth * 0.5f, top + 4.dp.toPx())
                    lineTo(left + tankWidth * 0.2f, top + tankHeight * 0.4f)
                    lineTo(left + tankWidth * 0.05f, top + tankHeight * 0.4f)
                    close()
                }
                drawPath(
                    path = glarePath,
                    color = Color.White.copy(alpha = 0.07f)
                )

                // 9. Metallic Cap Handle
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF4A554A),
                            Color(0xFF7A8B7A),
                            Color(0xFF4A554A)
                        )
                    ),
                    topLeft = Offset(size.width / 2 - 18.dp.toPx(), top - 6.dp.toPx()),
                    size = Size(36.dp.toPx(), 7.dp.toPx()),
                    cornerRadius = CornerRadius(3.dp.toPx())
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
