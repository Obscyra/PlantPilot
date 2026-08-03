package com.plantpilot.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.plantpilot.R
import com.plantpilot.model.*
import com.plantpilot.ui.components.*
import com.plantpilot.viewmodel.PlantPilotViewModel
import com.plantpilot.util.TimeUtils
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlantDetailScreen(
    viewModel: PlantPilotViewModel,
    plantId: String,
    onBack: () -> Unit
) {
    val plants by viewModel.plants.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val canSendCommands = connectionState == com.plantpilot.data.ConnectionState.Connected
    val canDisplayLastKnownData = connectionState == com.plantpilot.data.ConnectionState.Connected || connectionState == com.plantpilot.data.ConnectionState.Reconnecting
    val plant = plants.find { it.id == plantId }

    if (plant == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Plant not found", color = MaterialTheme.colorScheme.onSurface)
        }
        return
    }

    var editableName by remember(plant.name) { mutableStateOf(plant.name) }
    var editableWaterAmount by remember(plant.waterAmountMl) { mutableFloatStateOf(plant.waterAmountMl.toFloat()) }
    var editableThreshold by remember(plant.moistureThreshold) { mutableFloatStateOf(plant.moistureThreshold.toFloat()) }
    var editableMinInterval by remember(plant.minIntervalHours) { mutableFloatStateOf(plant.minIntervalHours.toFloat()) }

    var showScheduleSheet by remember { mutableStateOf(false) }
    var editingSchedule by remember { mutableStateOf<WateringSchedule?>(null) }
    var showWaterNowDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isWatering by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.commandBlockedEvents.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(plant.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Scrollable form area — fills remaining space
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            // Plant name
            OutlinedTextField(
                value = editableName,
                onValueChange = {
                    editableName = it
                    viewModel.updatePlantName(plantId, it)
                },
                label = { Text("Plant Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Motor indicator
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Assigned to Motor ${plant.motorNumber}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Water amount slider
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Water Amount",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${editableWaterAmount.toInt()} ml",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Slider(
                        value = editableWaterAmount,
                        onValueChange = {
                            editableWaterAmount = it
                            viewModel.updateWateringAmount(plantId, it.toInt())
                        },
                        valueRange = 20f..200f,
                        steps = 8,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("20 ml", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("200 ml", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Mode selector
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Watering Mode",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = plant.wateringMode == WateringMode.OFF,
                            onClick = {
                                viewModel.updateWateringMode(plantId, WateringMode.OFF)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                        ) {
                            Text("Off")
                        }
                        SegmentedButton(
                            selected = plant.wateringMode == WateringMode.SCHEDULED,
                            onClick = {
                                viewModel.updateWateringMode(plantId, WateringMode.SCHEDULED)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                        ) {
                            Text("Scheduled")
                        }
                        SegmentedButton(
                            selected = plant.wateringMode == WateringMode.AUTOMATIC,
                            onClick = {
                                viewModel.updateWateringMode(plantId, WateringMode.AUTOMATIC)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                            icon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_moisture),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        ) {
                            Text("Automatic")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (plant.wateringMode == WateringMode.OFF) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Watering is disabled. Schedules and automatic triggers will not run.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    when (plant.wateringMode) {
                        WateringMode.SCHEDULED -> {
                            // Schedule list
                            if (plant.schedules.isNotEmpty()) {
                                Text(
                                    text = "Schedules",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                plant.schedules.forEach { schedule ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = TimeUtils.formatTime(
                                                        schedule.hour,
                                                        schedule.minute,
                                                        settings.use24HourFormat
                                                    ),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                            IconButton(onClick = {
                                                editingSchedule = schedule
                                                showScheduleSheet = true
                                            }) {
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = "Edit",
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            IconButton(onClick = {
                                                viewModel.removeSchedule(plantId, schedule.id)
                                            }) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete",
                                                    modifier = Modifier.size(20.dp),
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                            }

                            TextButton(onClick = {
                                editingSchedule = null
                                showScheduleSheet = true
                            }) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add Schedule")
                            }
                        }

                        WateringMode.AUTOMATIC -> {
                            // Moisture threshold
                            Text(
                                text = "Water when below ${editableThreshold.toInt()}%",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Slider(
                                value = editableThreshold,
                                onValueChange = {
                                    editableThreshold = it
                                    viewModel.updateMoistureThreshold(plantId, it.toInt())
                                },
                                valueRange = 10f..60f,
                                steps = 9,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Current moisture
                            Text(
                                text = "Current Moisture",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MoistureRing(
                                    moisture = plant.currentMoisture,
                                    size = 48.dp,
                                    strokeWidth = 4.dp
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_moisture),
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Moisture",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${plant.currentMoisture}%",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Min interval
                            Text(
                                text = "Min interval: ${editableMinInterval.toInt()} hours",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Slider(
                                value = editableMinInterval,
                                onValueChange = {
                                    editableMinInterval = it
                                    viewModel.updateMinInterval(plantId, it.toInt())
                                },
                                valueRange = 2f..24f,
                                steps = 10,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        WateringMode.OFF -> {
                            // No additional settings for OFF mode
                        }
                    }
                }
            }
            }

            // Action buttons — fixed at bottom, never scrolls
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { showWaterNowDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                enabled = canSendCommands
            ) {
                Icon(imageVector = Icons.Default.WaterDrop, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Water Now")
            }
        }
    }

    // Schedule bottom sheet
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
                    viewModel.updateSchedule(plantId, schedule)
                } else {
                    viewModel.addSchedule(plantId, schedule)
                }
                showScheduleSheet = false
                editingSchedule = null
            },
            onDelete = { schedule ->
                viewModel.removeSchedule(plantId, schedule.id)
            }
        )
    }

    // Water now confirmation
    if (showWaterNowDialog) {
        ConfirmDialog(
            title = "Water Now",
            message = "Start watering ${plant.name} with ${plant.waterAmountMl}ml?",
            confirmText = "Water",
            onConfirm = {
                showWaterNowDialog = false
                // Guard against a connection dropping while the dialog was open.
                if (canSendCommands) {
                    isWatering = true
                    scope.launch {
                        // Fire the actual watering in the background
                        scope.launch { viewModel.waterPlant(plantId) }
                        // Animation lasts exactly 3 seconds regardless of watering duration
                        kotlinx.coroutines.delay(3000)
                        isWatering = false
                    }
                }
            },
            onDismiss = { showWaterNowDialog = false }
        )
    }

    // Delete confirmation
    if (showDeleteDialog) {
        ConfirmDialog(
            title = "Delete Plant",
            message = "Are you sure you want to remove ${plant.name}? This cannot be undone.",
            confirmText = "Delete",
            onConfirm = {
                viewModel.deletePlant(plantId)
                onBack()
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    // Watering overlay
    if (isWatering) {
        WateringOverlay(plantName = plant.name)
    }
}
