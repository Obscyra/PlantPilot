package com.plantpilot.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.plantpilot.ui.theme.NeonGreen
import kotlin.math.sin

@Composable
fun WateringOverlay(
    plantName: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.95f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            WateringScene(modifier = Modifier.size(320.dp))

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "Watering $plantName",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "Giving your plant some love...",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun WateringScene(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "watering_scene")
    
    val tilt by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 45f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tilt"
    )

    val sway by infiniteTransition.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sway"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            
            // Draw Pot
            drawPot(center.x, center.y + 120f)
            
            // Draw Plant
            withTransform({
                translate(center.x, center.y + 80f)
                rotate(sway, pivot = Offset(0f, 40f))
            }) {
                drawPlant()
            }

            // Draw Watering Can
            withTransform({
                translate(center.x + 140f, center.y - 120f)
                rotate(-tilt, pivot = Offset(-50f, 50f))
            }) {
                drawWateringCan()
            }

            // Draw Water Drops (only when tilted)
            if (tilt > 20f) {
                val flowIntensity = (tilt - 20f) / 25f
                drawWaterDrops(
                    startX = center.x + 50f,
                    startY = center.y - 110f,
                    targetX = center.x,
                    targetY = center.y + 60f,
                    intensity = flowIntensity
                )
            }
        }
    }
}

private fun DrawScope.drawPot(x: Float, y: Float) {
    val potWidth = 160f
    val potHeight = 110f
    val path = Path().apply {
        moveTo(x - potWidth / 2, y)
        lineTo(x + potWidth / 2, y)
        lineTo(x + potWidth / 2 - 20f, y + potHeight)
        lineTo(x - potWidth / 2 + 20f, y + potHeight)
        close()
    }
    
    drawPath(
        path = path,
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF8D6E63), Color(0xFF5D4037)),
            start = Offset(x, y),
            end = Offset(x, y + potHeight)
        )
    )
    
    drawRoundRect(
        color = Color(0xFFA1887F),
        topLeft = Offset(x - potWidth / 2 - 8f, y - 8f),
        size = Size(potWidth + 16f, 20f),
        cornerRadius = CornerRadius(6f)
    )
}

private fun DrawScope.drawPlant() {
    val leafColor = NeonGreen
    val darkLeafColor = Color(0xFF4CAF50)
    
    // Stem
    drawRect(
        color = darkLeafColor,
        topLeft = Offset(-6f, 0f),
        size = Size(12f, 60f)
    )

    // Draw multiple leaves
    drawLeaf(Offset(0f, 15f), -40f, 70f, leafColor)
    drawLeaf(Offset(0f, 15f), 40f, 70f, leafColor)
    drawLeaf(Offset(0f, -10f), -60f, 85f, leafColor)
    drawLeaf(Offset(0f, -10f), 60f, 85f, leafColor)
    drawLeaf(Offset(0f, -40f), 0f, 100f, leafColor)
}

private fun DrawScope.drawLeaf(
    offset: Offset,
    angle: Float,
    length: Float,
    color: Color
) {
    withTransform({
        translate(offset.x, offset.y)
        rotate(angle, pivot = Offset.Zero)
    }) {
        val path = Path().apply {
            moveTo(0f, 0f)
            quadraticTo(length / 2, -length / 3, length, 0f)
            quadraticTo(length / 2, length / 3, 0f, 0f)
        }
        drawPath(path, color)
    }
}

private fun DrawScope.drawWateringCan() {
    val canColor = Color(0xFF455A64)
    val lidColor = Color(0xFF263238)
    
    // Body
    drawRoundRect(
        color = canColor,
        topLeft = Offset(-50f, 0f),
        size = Size(100f, 70f),
        cornerRadius = CornerRadius(16f)
    )
    
    // Top Lid
    drawRect(
        color = lidColor,
        topLeft = Offset(-45f, -5f),
        size = Size(90f, 10f)
    )
    
    // Spout
    val spoutPath = Path().apply {
        moveTo(-50f, 50f)
        lineTo(-110f, 15f)
        lineTo(-110f, 0f)
        lineTo(-50f, 40f)
        close()
    }
    drawPath(spoutPath, canColor)
    
    // Handle
    drawArc(
        color = lidColor,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(10f, 10f),
        size = Size(60f, 50f),
        style = Stroke(width = 8f, cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawWaterDrops(
    startX: Float,
    startY: Float,
    targetX: Float,
    targetY: Float,
    intensity: Float
) {
    val dropColor = Color(0xFF81D4FA).copy(alpha = 0.7f)
    val time = (System.currentTimeMillis() % 1000) / 1000f
    
    repeat(12) { i ->
        val offset = (i / 12f + time) % 1f
        val x = startX + (targetX - startX) * offset + sin(offset * 10f) * 15f
        val y = startY + (targetY - startY) * offset
        
        val size = 7f * (1f - offset) * intensity
        if (size > 1f) {
            drawCircle(
                color = dropColor,
                radius = size,
                center = Offset(x, y)
            )
        }
    }
}
