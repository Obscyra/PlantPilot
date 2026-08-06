package com.plantpilot.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.plantpilot.R
import java.util.Locale
import com.plantpilot.model.Plant
import com.plantpilot.model.WateringMode
import com.plantpilot.model.WateringSchedule
import com.plantpilot.ui.components.ConnectionStatusChip
import com.plantpilot.ui.components.DemoModeBanner
import com.plantpilot.ui.components.MoistureRing
import com.plantpilot.ui.components.ScheduleBottomSheet
import com.plantpilot.viewmodel.PlantPilotViewModel
import com.plantpilot.util.bounceClick
import com.plantpilot.util.TimeUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantsListScreen(
    viewModel: PlantPilotViewModel,
    onStatusChipClick: () -> Unit,
    onPlantClick: (String) -> Unit
) {
    val plants by viewModel.plants.collectAsState()
    val settings by viewModel.settings.collectAsState()
    var showRenameDialog by remember { mutableStateOf(false) }
    var renamingPlant by remember { mutableStateOf<Plant?>(null) }
    var renameText by remember { mutableStateOf("") }

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
                    // Unified live status chip: shows Updating/Connected/Connecting/
                    // Disconnected and (syncing) replaces the old "Update" button.
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
                        imageVector = Icons.Default.WaterDrop,
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
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .animateItem()
                            .bounceClick { onPlantClick(plant.id) },
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp)
                        ) {
                            // Header Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    modifier = Modifier.size(56.dp),
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.WaterDrop,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = plant.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Motor ${plant.motorNumber}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_moisture),
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    MoistureRing(
                                        moisture = plant.currentMoisture,
                                        size = 38.dp,
                                        strokeWidth = 3.dp,
                                        animate = false
                                    ) {
                                        Text(
                                            text = "${plant.currentMoisture}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                IconButton(
                                    onClick = {
                                        renamingPlant = plant
                                        renameText = plant.name
                                        showRenameDialog = true
                                    },
                                    modifier = Modifier.bounceClick()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Rename",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
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
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Automatic Watering",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (plant.wateringMode == WateringMode.AUTOMATIC)
                                            MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = plant.wateringMode == WateringMode.AUTOMATIC,
                                    onCheckedChange = { isAuto ->
                                        viewModel.updateWateringMode(
                                            plant.id,
                                            if (isAuto) WateringMode.AUTOMATIC else WateringMode.SCHEDULED
                                        )
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            when (plant.wateringMode) {
                                WateringMode.AUTOMATIC -> {
                                    Text(
                                        text = "Will trigger when moisture level is ${plant.moistureThreshold}%",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                WateringMode.SCHEDULED -> {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            text = "Watering Times:",
                                            style = MaterialTheme.typography.bodyLarge,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                        
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            plant.schedules.forEach { schedule ->
                                                AssistChip(
                                                    onClick = { 
                                                        targetPlantId = plant.id
                                                        editingSchedule = schedule
                                                        showScheduleSheet = true
                                                    },
                                                    label = {
                                                        Text(
                                                            text = TimeUtils.formatTime(
                                                                schedule.hour,
                                                                schedule.minute,
                                                                settings.use24HourFormat
                                                            )
                                                        )
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.AccessTime, null, Modifier.size(16.dp))
                                                    }
                                                )
                                            }

                                            // Add schedule chip
                                            FilterChip(
                                                selected = false,
                                                onClick = {
                                                    targetPlantId = plant.id
                                                    editingSchedule = null
                                                    showScheduleSheet = true
                                                },
                                                label = { Text("Add") },
                                                leadingIcon = {
                                                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                                                },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                                ),
                                                modifier = Modifier.bounceClick()
                                            )
                                        }
                                    }
                                }
                                WateringMode.OFF -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        SuggestionChip(
                                            onClick = { },
                                            label = { Text("Mode: Disabled") },
                                            icon = { Icon(Icons.Default.Block, null, Modifier.size(16.dp)) },
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

                            Spacer(modifier = Modifier.height(16.dp))

                            // Water Amount
                            Column {
                                var editableWaterAmount by remember(plant.waterAmountMl) { mutableFloatStateOf(plant.waterAmountMl.coerceIn(10, 100).toFloat()) }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Water Amount",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = "${editableWaterAmount.toInt()} ml",
                                        style = MaterialTheme.typography.bodyLarge,
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
                                        viewModel.updateWateringAmount(plant.id, editableWaterAmount.toInt())
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
            }
        }
    }

    if (showRenameDialog && (renamingPlant != null)) {
        AlertDialog(
            onDismissRequest = {
                showRenameDialog = false
                renamingPlant = null
            },
            title = { Text("Rename Plant") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Plant Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) {
                        viewModel.updatePlantName(renamingPlant!!.id, renameText.trim())
                    }
                    showRenameDialog = false
                    renamingPlant = null
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    renamingPlant = null
                }) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
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
