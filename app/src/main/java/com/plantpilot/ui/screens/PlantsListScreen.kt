package com.plantpilot.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.plantpilot.R
import com.plantpilot.model.Plant
import com.plantpilot.model.WateringMode
import com.plantpilot.model.WateringSchedule
import com.plantpilot.ui.components.ConnectionStatusChip
import com.plantpilot.ui.components.DemoModeBanner
import com.plantpilot.ui.components.MoistureRing
import com.plantpilot.ui.components.ScheduleBottomSheet
import com.plantpilot.util.TimeUtils
import com.plantpilot.viewmodel.PlantPilotViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantsListScreen(
    viewModel: PlantPilotViewModel,
    onStatusChipClick: () -> Unit,
    onPlantClick: (String) -> Unit
) {
    val plants by viewModel.plants.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var showScheduleSheet by remember { mutableStateOf(false) }
    var targetPlantId by remember { mutableStateOf("") }
    var editingSchedule by remember { mutableStateOf<WateringSchedule?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "My Plants",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    ConnectionStatusChip(
                        viewModel = viewModel,
                        onClick = onStatusChipClick
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            )
        }
    ) { paddingValues ->
        if (plants.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.LocalFlorist,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No plants configured",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Connect your PilotCore to set up plants",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (settings.demoMode) {
                    item(key = "demo_mode_banner") {
                        DemoModeBanner(onTurnOff = { viewModel.setDemoMode(false) })
                    }
                }

                items(plants, key = { it.id }) { plant ->
                    PlantListItemCard(
                        plant = plant,
                        use24HourFormat = settings.use24HourFormat,
                        onPlantClick = onPlantClick,
                        onWateringModeChange = { id, mode ->
                            viewModel.updateWateringMode(id, mode)
                        },
                        onWateringAmountChange = { id, amount ->
                            viewModel.updateWateringAmount(id, amount)
                        },
                        onScheduleClick = { id, schedule ->
                            targetPlantId = id
                            editingSchedule = schedule
                            showScheduleSheet = true
                        }
                    )
                }
            }
        }
    }

    if (showScheduleSheet) {
        ScheduleBottomSheet(
            existingSchedule = editingSchedule,
            use24HourFormat = settings.use24HourFormat,
            onDismiss = {
                showScheduleSheet = false
                editingSchedule = null
            },
            onSave = { schedule ->
                if (editingSchedule != null) {
                    viewModel.updateSchedule(targetPlantId, schedule)
                } else {
                    viewModel.addSchedule(targetPlantId, schedule)
                }
                showScheduleSheet = false
                editingSchedule = null
            }
        ) { schedule ->
            viewModel.removeSchedule(targetPlantId, schedule.id)
            showScheduleSheet = false
            editingSchedule = null
        }
    }
}

@Composable
private fun PlantListItemCard(
    plant: Plant,
    use24HourFormat: Boolean,
    onPlantClick: (String) -> Unit,
    onWateringModeChange: (String, WateringMode) -> Unit,
    onWateringAmountChange: (String, Int) -> Unit,
    onScheduleClick: (String, WateringSchedule?) -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        onClick = { onPlantClick(plant.id) },
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.LocalFlorist,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = plant.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Motor ${plant.motorNumber}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Clean Moisture Ring Status Indicator
                MoistureRing(
                    moisture = plant.currentMoisture,
                    size = 42.dp,
                    strokeWidth = 3.5.dp,
                    animate = false
                ) {
                    Text(
                        text = "${plant.currentMoisture}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 14.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )

            // Configuration Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_moisture),
                        contentDescription = null,
                        tint = if (plant.wateringMode == WateringMode.AUTOMATIC) 
                            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Automatic Watering",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (plant.wateringMode == WateringMode.AUTOMATIC)
                            MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = plant.wateringMode == WateringMode.AUTOMATIC,
                    onCheckedChange = { isAuto ->
                        onWateringModeChange(
                            plant.id,
                            if (isAuto) WateringMode.AUTOMATIC else WateringMode.SCHEDULED
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            when (plant.wateringMode) {
                WateringMode.AUTOMATIC -> {
                    Text(
                        text = "Triggers when moisture drops below ${plant.moistureThreshold}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                WateringMode.SCHEDULED -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Watering Times:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            plant.schedules.forEach { schedule ->
                                key(schedule.id) {
                                    AssistChip(
                                        onClick = { onScheduleClick(plant.id, schedule) },
                                        label = {
                                            Text(
                                                text = TimeUtils.formatTime(
                                                    schedule.hour,
                                                    schedule.minute,
                                                    use24HourFormat
                                                ),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.AccessTime, null, Modifier.size(14.dp))
                                        }
                                    )
                                }
                            }

                            FilterChip(
                                selected = false,
                                onClick = { onScheduleClick(plant.id, null) },
                                label = { Text("Add", style = MaterialTheme.typography.labelSmall) },
                                leadingIcon = {
                                    Icon(Icons.Default.Add, null, Modifier.size(14.dp))
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }
                }
                WateringMode.OFF -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SuggestionChip(
                            onClick = { },
                            label = { Text("Disabled", style = MaterialTheme.typography.labelSmall) },
                            icon = { Icon(Icons.Default.Block, null, Modifier.size(14.dp)) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                iconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Add a schedule to enable",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Water Amount
            Column {
                var editableWaterAmount by remember(plant.waterAmountMl) { mutableFloatStateOf(plant.waterAmountMl.coerceIn(10, 100).toFloat()) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Water Amount",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${editableWaterAmount.toInt()} ml",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = editableWaterAmount,
                    onValueChange = {
                        editableWaterAmount = it
                    },
                    onValueChangeFinished = {
                        onWateringAmountChange(plant.id, editableWaterAmount.toInt())
                    },
                    valueRange = 10f..100f,
                    steps = 8
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("10 ml", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("100 ml", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
