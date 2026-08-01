package com.plantpilot.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.plantpilot.model.*
import com.plantpilot.ui.components.EmptyState
import com.plantpilot.ui.theme.NeonGreen
import com.plantpilot.viewmodel.PlantPilotViewModel
import com.plantpilot.util.TimeUtils
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: PlantPilotViewModel
) {
    val history by viewModel.history.collectAsState()
    val settings by viewModel.settings.collectAsState()
    var selectedFilter by remember { mutableStateOf("All") }

    val filters = listOf("All", "Motor 1", "Motor 2", "Motor 3", "Motor 4", "Manual", "Scheduled", "Auto")

    val filteredHistory = remember(history, selectedFilter) {
        when (selectedFilter) {
            "All" -> history
            "Motor 1" -> history.filter { it.motorNumber == 1 }
            "Motor 2" -> history.filter { it.motorNumber == 2 }
            "Motor 3" -> history.filter { it.motorNumber == 3 }
            "Motor 4" -> history.filter { it.motorNumber == 4 }
            "Manual" -> history.filter { it.triggerType == TriggerType.MANUAL }
            "Scheduled" -> history.filter { it.triggerType == TriggerType.SCHEDULED }
            "Auto" -> history.filter { it.triggerType == TriggerType.AUTOMATIC }
            else -> history
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "History",
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        }
    ) { paddingValues ->
        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    title = "No watering history",
                    message = "Watering events will appear here once you start using your plants."
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Filter chips
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filters) { filter ->
                            FilterChip(
                                selected = selectedFilter == filter,
                                onClick = { selectedFilter = filter },
                                label = { Text(filter, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                // History items
                items(filteredHistory, key = { it.id }) { event ->
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Trigger type icon
                            Surface(
                                modifier = Modifier.size(40.dp),
                                shape = MaterialTheme.shapes.small,
                                color = when (event.triggerType) {
                                    TriggerType.MANUAL -> MaterialTheme.colorScheme.tertiaryContainer
                                    TriggerType.SCHEDULED -> MaterialTheme.colorScheme.secondaryContainer
                                    TriggerType.AUTOMATIC -> MaterialTheme.colorScheme.primaryContainer
                                }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = when (event.triggerType) {
                                            TriggerType.MANUAL -> Icons.Default.TouchApp
                                            TriggerType.SCHEDULED -> Icons.Default.Schedule
                                            TriggerType.AUTOMATIC -> Icons.Default.AutoAwesome
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = when (event.triggerType) {
                                            TriggerType.MANUAL -> MaterialTheme.colorScheme.onTertiaryContainer
                                            TriggerType.SCHEDULED -> MaterialTheme.colorScheme.onSecondaryContainer
                                            TriggerType.AUTOMATIC -> MaterialTheme.colorScheme.onPrimaryContainer
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = event.plantName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "Motor ${event.motorNumber}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "·",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${event.amountMl}ml",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "·",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = event.triggerType.name.lowercase().replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = TimeUtils.formatTimestamp(event.timestamp, settings.use24HourFormat),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (event.moistureBefore != null && event.moistureAfter != null) {
                                    Text(
                                        text = "${event.moistureBefore}% → ${event.moistureAfter}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
