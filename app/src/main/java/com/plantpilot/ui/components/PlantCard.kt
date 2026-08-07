package com.plantpilot.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.plantpilot.R
import com.plantpilot.model.Plant
import com.plantpilot.model.WateringMode
import com.plantpilot.ui.theme.CardGlassBorderMuted
import com.plantpilot.ui.theme.StatusOptimal
import com.plantpilot.ui.theme.StatusWarning
import com.plantpilot.ui.theme.StatusWatering
import com.plantpilot.util.TimeUtils
import com.plantpilot.util.bounceClick

import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

@Composable
fun PlantCard(
    plant: Plant,
    use24HourFormat: Boolean,
    waterEnabled: Boolean = true,
    onWaterNow: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDry = plant.currentMoisture <= plant.moistureThreshold
    val accentColor = if (isDry) StatusWarning else StatusOptimal

    Card(
        modifier = modifier
            .fillMaxWidth()
            .drawWithCache {
                val barWidth = 6.dp.toPx()
                val cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx())
                onDrawWithContent {
                    drawContent()
                    drawRoundRect(
                        color = accentColor,
                        topLeft = Offset.Zero,
                        size = Size(barWidth, size.height),
                        cornerRadius = cornerRadius
                    )
                }
            }
            .bounceClick { onClick() },
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, CardGlassBorderMuted),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, top = 18.dp, end = 18.dp, bottom = 18.dp)
        ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = plant.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Motor ${plant.motorNumber} • ${if (plant.lastWateredTimestamp > 0) "Last watered " + TimeUtils.getRelativeTimeString(plant.lastWateredTimestamp) else "Never watered"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_moisture),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = accentColor.copy(alpha = 0.9f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        MoistureRing(
                            moisture = plant.currentMoisture,
                            size = 48.dp,
                            strokeWidth = 4.dp
                        ) {
                            Text(
                                text = "${plant.currentMoisture}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val modeText = when (plant.wateringMode) {
                        WateringMode.AUTOMATIC -> "Auto · ${plant.moistureThreshold}%"
                        WateringMode.OFF -> "Manual Off"
                        else -> {
                            if (plant.schedules.isEmpty()) "No Schedule"
                            else plant.schedules.joinToString(", ") {
                                TimeUtils.formatTime(it.hour, it.minute, use24HourFormat)
                            }
                        }
                    }

                    val (chipBg, chipFg) = when (plant.wateringMode) {
                        WateringMode.AUTOMATIC -> Pair(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                        WateringMode.SCHEDULED -> Pair(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                        else -> Pair(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = chipBg,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.WaterDrop,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = chipFg
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = modeText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = chipFg
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onWaterNow,
                    enabled = waterEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .bounceClick(enabled = waterEnabled),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(
                        imageVector = Icons.Default.WaterDrop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Water Now",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
