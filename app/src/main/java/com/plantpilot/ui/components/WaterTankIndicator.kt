package com.plantpilot.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.sp
import com.plantpilot.ui.theme.PlantPilotTheme
import kotlinx.coroutines.launch
import kotlin.math.sin

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

@Composable
fun WaterTankIndicator(
    tankCapacityMl: Int,
    estimatedWaterMl: Int,
    isDemoMode: Boolean = false,
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
    val waveSurgeAnim = remember { Animatable(1f) }

    // Trigger reactive splash surge and spring bounce whenever fillFraction changes
    LaunchedEffect(fillFraction) {
        if (!initialAnimated) {
            animatedFill.snapTo(0f)
            animatedFill.animateTo(
                targetValue = fillFraction,
                animationSpec = spring(
                    stiffness = Spring.StiffnessMediumLow,
                    dampingRatio = Spring.DampingRatioMediumBouncy
                )
            )
            initialAnimated = true
        } else {
            // Trigger temporary liquid splash wave surge only when water is present
            if (fillFraction > 0f) {
                launch {
                    waveSurgeAnim.snapTo(2.5f)
                    waveSurgeAnim.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing)
                    )
                }
            } else {
                waveSurgeAnim.snapTo(1f)
            }
            // Spring bounce liquid height
            animatedFill.animateTo(
                targetValue = fillFraction,
                animationSpec = spring(
                    stiffness = Spring.StiffnessLow,
                    dampingRatio = Spring.DampingRatioMediumBouncy
                )
            )
        }
    }

    // Slow, gentle wave animation
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val wavePhaseState = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )
    val wavePhase2State = infiniteTransition.animateFloat(
        initialValue = Math.PI.toFloat(),
        targetValue = 3f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase2"
    )

    val targetFillColor = when {
        clampedLevel <= 1 -> MaterialTheme.colorScheme.error
        clampedLevel == 2 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    val fillColor by animateColorAsState(
        targetValue = targetFillColor,
        animationSpec = tween(durationMillis = 600),
        label = "fillColor"
    )

    val pct = if (tankCapacityMl > 0) ((approxMl.toFloat() / tankCapacityMl) * 100).toInt().coerceIn(0, 100) else 0

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            // Header Row: Water Drop Icon + Title + Status Badges
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(fillColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.WaterDrop,
                        contentDescription = null,
                        tint = fillColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Text(
                    text = "Water Tank",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Status Badge Pills
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val (badgeText, badgeBg, badgeFg) = when (clampedLevel) {
                    4 -> Triple("Full • 100%", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    3 -> Triple("Optimal • 75%", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                    2 -> Triple("Moderate • 50%", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                    1 -> Triple("Low • 25%", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    else -> Triple("Empty • 0%", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeBg
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = badgeFg,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                if (isDemoMode) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "Demo Mode",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Hero Metric: Big volume readout + Percentage pill
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "$approxMl",
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp),
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = "ml",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                Spacer(modifier = Modifier.width(4.dp))

                Surface(
                    shape = CircleShape,
                    color = fillColor.copy(alpha = 0.2f),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Text(
                        text = "$pct%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = fillColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Sleek Progress Bar Indicator
            LinearProgressIndicator(
                progress = { animatedFill.value },
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = fillColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )

            AnimatedVisibility(
                visible = isLow,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (clampedLevel == 0) "Tank Empty — Refill Needed" else "Low Water — Refill Needed",
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
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .width(90.dp)
                .height(140.dp)
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
                val waveAmplitude = 4.dp.toPx() * waveSurgeAnim.value
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

                    // 6. Precision Glass Graduation Ticks for 4 Probes (L1, L2, L3, L4)
                for (level in 1..4) {
                    val levelFraction = when (level) {
                        4 -> 0.90f // Level 4 sits inside top glass shoulder where full water touches
                        3 -> 0.69f
                        2 -> 0.48f
                        1 -> 0.26f
                        else -> 0f
                    }
                    val tickY = bottom - (tankHeight * levelFraction)
                    val isPassed = fillPercentage >= (level * 0.25f - 0.05f)
                    
                    // Crisp Black Probe Bars: Pure Black (0xFF000000) for active probes, Translucent Charcoal for dry
                    val tickColor = if (isPassed) Color(0xFF000000).copy(alpha = 0.95f) else Color(0xFF000000).copy(alpha = 0.35f)
                    val barWidth = if (isDemoMode) 12.dp.toPx() else 8.dp.toPx()

                    // White contrast underlay rim so black bars pop sharply over dark background and water
                    drawLine(
                        color = Color.White.copy(alpha = if (isPassed) 0.5f else 0.2f),
                        start = Offset(right - barWidth - 1.dp.toPx(), tickY + 0.5.dp.toPx()),
                        end = Offset(right - 1.dp.toPx(), tickY + 0.5.dp.toPx()),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    // Main crisp black tick mark
                    drawLine(
                        color = tickColor,
                        start = Offset(right - barWidth, tickY),
                        end = Offset(right - 2.dp.toPx(), tickY),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    if (isDemoMode) {
                        val shadowPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.argb(200, 255, 255, 255)
                            textSize = 10.dp.toPx()
                            isFakeBoldText = true
                            textAlign = android.graphics.Paint.Align.RIGHT
                        }
                        val textPaint = android.graphics.Paint().apply {
                            color = if (isPassed) android.graphics.Color.rgb(0, 0, 0) else android.graphics.Color.argb(110, 0, 0, 0)
                            textSize = 10.dp.toPx()
                            isFakeBoldText = true
                            textAlign = android.graphics.Paint.Align.RIGHT
                        }
                        
                        // White text shadow outline for contrast
                        drawContext.canvas.nativeCanvas.drawText(
                            "L$level",
                            right - barWidth - 3.dp.toPx(),
                            tickY + 4.dp.toPx(),
                            shadowPaint
                        )
                        // Main crisp black text label
                        drawContext.canvas.nativeCanvas.drawText(
                            "L$level",
                            right - barWidth - 4.dp.toPx(),
                            tickY + 3.5.dp.toPx(),
                            textPaint
                        )
                    }
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
