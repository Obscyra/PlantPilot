package com.plantpilot.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.plantpilot.data.ConnectionStateHelper
import com.plantpilot.model.Plant
import com.plantpilot.ui.components.ConnectionStatusChip
import com.plantpilot.ui.components.PulsingDot
import com.plantpilot.ui.theme.*
import com.plantpilot.viewmodel.PlantPilotViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CAL_SCALE_MIN = 0
private const val CAL_SCALE_MAX = 4095
private const val TERMINAL_MAX_LINES = 10

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    viewModel: PlantPilotViewModel,
    onBack: () -> Unit,
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val canSendCommands = ConnectionStateHelper.canSendCommands(connectionState)
    val canDisplayLastKnownData = ConnectionStateHelper.canDisplayLastKnownData(connectionState)
    val displayConnectionState by viewModel.displayConnectionState.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val existingCalibration by viewModel.sensorCalibration.collectAsState()
    val plants by viewModel.plants.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.commandBlockedEvents.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    // Request the realtime 3s sensor stream while the screen is open.
    LaunchedEffect(Unit) {
        viewModel.requestSensorReading()
        viewModel.setCalibrationStreaming(true)
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.setCalibrationStreaming(false) }
    }

    // Rolling raw-value terminal lines.
    val terminalLines = remember { mutableStateListOf<String>() }
    val terminalScroll = rememberScrollState()
    LaunchedEffect(telemetry) {
        val raw = telemetry?.raw_soil
        if (raw != null) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val row = raw.mapIndexed { index, value ->
                "S${index + 1}: ${if (value >= 0) value.toString().padStart(4, ' ') else "----"}"
            }.joinToString("  |  ")
            terminalLines.add(0, "[$time] [ADC]  $row")
            while (terminalLines.size > TERMINAL_MAX_LINES) terminalLines.removeAt(terminalLines.size - 1)
        }
    }
    LaunchedEffect(terminalLines.size) {
        terminalScroll.animateScrollTo(0)
    }

    var selectedSensor by remember { mutableIntStateOf(1) }
    var currentStep by remember(selectedSensor) { mutableIntStateOf(1) }
    var isTerminalExpanded by remember { mutableStateOf(false) }

    // Draft map: temporary dry/wet edits per sensor, keyed by sensor index (1–4).
    val draftCalibration = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }

    var dryValue by remember(selectedSensor) {
        mutableIntStateOf(
            draftCalibration[selectedSensor]?.first
                ?: existingCalibration[selectedSensor]?.first
                ?: 4095
        )
    }
    var wetValue by remember(selectedSensor) {
        mutableIntStateOf(
            draftCalibration[selectedSensor]?.second
                ?: existingCalibration[selectedSensor]?.second
                ?: 1400
        )
    }

    fun saveDraftForCurrentSensor() {
        draftCalibration[selectedSensor] = dryValue to wetValue
    }

    val hasUnsavedChanges by remember {
        derivedStateOf {
            draftCalibration.any { (sensor, pair) ->
                val saved = existingCalibration[sensor]
                saved?.first != pair.first || saved?.second != pair.second
            }
        }
    }

    var showSaveDialog by remember { mutableStateOf(false) }

    BackHandler {
        if (hasUnsavedChanges) {
            showSaveDialog = true
        } else {
            onBack()
        }
    }

    val liveReading = telemetry?.raw_soil?.getOrNull(selectedSensor - 1)

    val percent = if (liveReading != null && dryValue > wetValue) {
        ((dryValue - liveReading).toFloat() / (dryValue - wetValue).toFloat()).coerceIn(0f, 1f)
    } else 0f

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Sensor Calibration",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (hasUnsavedChanges) showSaveDialog = true else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    ConnectionStatusChip(
                        connectionState = displayConnectionState,
                        onClick = {}
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // --- Scrollable Sensor Selection Bar with Plant Names ---
            SensorSelectorRow(
                selectedSensor = selectedSensor,
                plants = plants,
                draftCalibration = draftCalibration,
                existingCalibration = existingCalibration,
                onSensorSelected = { sensor ->
                    saveDraftForCurrentSensor()
                    selectedSensor = sensor
                    dryValue = draftCalibration[sensor]?.first
                        ?: existingCalibration[sensor]?.first
                        ?: 4095
                    wetValue = draftCalibration[sensor]?.second
                        ?: existingCalibration[sensor]?.second
                        ?: 1400
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // --- Step Progress Bar Indicator ---
            WizardStepIndicator(
                currentStep = currentStep,
                onStepClick = { step -> currentStep = step }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // --- Collapsible Telemetry Log ---
            CollapsibleTerminal(
                isExpanded = isTerminalExpanded,
                onToggleExpand = { isTerminalExpanded = !isTerminalExpanded },
                terminalLines = terminalLines,
                terminalScroll = terminalScroll,
                canSendCommands = canSendCommands
            )

            Spacer(modifier = Modifier.height(16.dp))

            // --- Animated Wizard Step Cards ---
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally { width -> width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> -width } + fadeOut()
                    } else {
                        slideInHorizontally { width -> -width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> width } + fadeOut()
                    }
                },
                label = "WizardStepTransition"
            ) { step ->
                val currentPlantName = plants.find { it.motorNumber == selectedSensor }?.name
                when (step) {
                    1 -> StepDryCalibrationCard(
                        sensorIndex = selectedSensor,
                        plantName = currentPlantName,
                        liveReading = liveReading,
                        canSendCommands = canSendCommands,
                        canDisplayLastKnownData = canDisplayLastKnownData,
                        dryValue = dryValue,
                        onDryValueChange = {
                            dryValue = it
                            saveDraftForCurrentSensor()
                        },
                        onCaptureDry = {
                            liveReading?.let {
                                dryValue = it
                                saveDraftForCurrentSensor()
                            }
                            currentStep = 2
                        },
                        onNextStep = { currentStep = 2 }
                    )
                    2 -> StepWetCalibrationCard(
                        sensorIndex = selectedSensor,
                        plantName = currentPlantName,
                        liveReading = liveReading,
                        canSendCommands = canSendCommands,
                        canDisplayLastKnownData = canDisplayLastKnownData,
                        wetValue = wetValue,
                        onWetValueChange = {
                            wetValue = it
                            saveDraftForCurrentSensor()
                        },
                        onCaptureWet = {
                            liveReading?.let {
                                wetValue = it
                                saveDraftForCurrentSensor()
                            }
                            currentStep = 3
                        },
                        onPreviousStep = { currentStep = 1 },
                        onNextStep = { currentStep = 3 }
                    )
                    3 -> StepReviewAndVerifyCard(
                        sensorIndex = selectedSensor,
                        plantName = currentPlantName,
                        liveReading = liveReading,
                        dryValue = dryValue,
                        wetValue = wetValue,
                        percent = percent,
                        canSendCommands = canSendCommands,
                        hasUnsavedChanges = hasUnsavedChanges,
                        onRecalibrate = { currentStep = 1 },
                        onSaveCalibration = {
                            saveDraftForCurrentSensor()
                            val toSaveCal = draftCalibration.toMap()
                            var allSuccess = true
                            var remaining = toSaveCal.size
                            toSaveCal.forEach { (sensor, pair) ->
                                viewModel.calibrateSensor(sensor, pair.first, pair.second) { success ->
                                    if (!success) allSuccess = false
                                    remaining--
                                    if (remaining == 0) {
                                        scope.launch {
                                            if (allSuccess) {
                                                draftCalibration.clear()
                                            }
                                            snackbarHostState.showSnackbar(
                                                message = if (allSuccess) {
                                                    "All sensors calibrated"
                                                } else {
                                                    "Some calibrations failed — device unreachable"
                                                },
                                                duration = SnackbarDuration.Long
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // Save confirmation dialog on exit
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Unsaved Calibration") },
            text = { Text("You have unsaved calibration data. Save to device before leaving?") },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            confirmButton = {
                TextButton(onClick = {
                    showSaveDialog = false
                    saveDraftForCurrentSensor()
                    val toSaveCal = draftCalibration.toMap()
                    var allSuccess = true
                    var remaining = toSaveCal.size
                    toSaveCal.forEach { (sensor, pair) ->
                        viewModel.calibrateSensor(sensor, pair.first, pair.second) { success ->
                            if (!success) allSuccess = false
                            remaining--
                            if (remaining == 0) {
                                scope.launch {
                                    if (allSuccess) {
                                        draftCalibration.clear()
                                    }
                                    snackbarHostState.showSnackbar(
                                        message = if (allSuccess) "All sensors calibrated" else "Some calibrations failed",
                                        duration = SnackbarDuration.Long
                                    )
                                }
                                onBack()
                            }
                        }
                    }
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSaveDialog = false
                    draftCalibration.clear()
                    onBack()
                }) {
                    Text("Discard")
                }
            }
        )
    }
}

@Composable
private fun SensorSelectorRow(
    selectedSensor: Int,
    plants: List<Plant>,
    draftCalibration: Map<Int, Pair<Int, Int>>,
    existingCalibration: Map<Int, Pair<Int, Int>>,
    onSensorSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (sensor in 1..4) {
            val isSelected = selectedSensor == sensor
            val plant = plants.find { it.motorNumber == sensor }
            val plantName = plant?.name

            val hasDraft = draftCalibration[sensor]?.let { draft ->
                val saved = existingCalibration[sensor]
                saved?.first != draft.first || saved?.second != draft.second
            } ?: false

            val containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }

            val contentColor = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }

            val borderColor = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.Transparent
            }

            Surface(
                color = containerColor,
                contentColor = contentColor,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier.clickable { onSensorSelected(sensor) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasDraft) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    Column {
                        Text(
                            text = "Sensor $sensor",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (!plantName.isNullOrBlank()) {
                            Text(
                                text = plantName,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                color = contentColor.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardStepIndicator(
    currentStep: Int,
    onStepClick: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val steps = listOf("1. Dry Air", "2. Wet Water", "3. Verify")
        steps.forEachIndexed { index, title ->
            val stepNumber = index + 1
            val isActive = currentStep == stepNumber
            val isCompleted = currentStep > stepNumber

            val bgColor = when {
                isActive -> MaterialTheme.colorScheme.primaryContainer
                isCompleted -> MaterialTheme.colorScheme.surfaceContainerHighest
                else -> Color.Transparent
            }

            val textColor = when {
                isActive -> MaterialTheme.colorScheme.onPrimaryContainer
                isCompleted -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bgColor)
                    .clickable { onStepClick(stepNumber) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isCompleted) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = textColor,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun CollapsibleTerminal(
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    terminalLines: List<String>,
    terminalScroll: androidx.compose.foundation.ScrollState,
    canSendCommands: Boolean
) {
    Surface(
        color = TerminalBackground,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, TerminalBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Terminal,
                    contentDescription = null,
                    tint = TerminalGreen,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Raw ADC Stream",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = TerminalText
                )
                Spacer(modifier = Modifier.width(6.dp))
                PulsingDot(isVisible = canSendCommands)
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = TerminalText,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (isExpanded) {
                HorizontalDivider(color = TerminalBorder)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 80.dp)
                        .verticalScroll(state = terminalScroll)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    if (terminalLines.isEmpty()) {
                        Text(
                            text = if (canSendCommands) "Waiting for telemetry..." else "Device offline",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TerminalPlaceholder
                        )
                    } else {
                        terminalLines.forEach { line ->
                            com.plantpilot.ui.components.LogLine(line)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepDryCalibrationCard(
    sensorIndex: Int,
    plantName: String?,
    liveReading: Int?,
    canSendCommands: Boolean,
    canDisplayLastKnownData: Boolean,
    dryValue: Int,
    onDryValueChange: (Int) -> Unit,
    onCaptureDry: () -> Unit,
    onNextStep: () -> Unit
) {
    ElevatedCard(
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.WbSunny,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Step 1: Dry Value (Air)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (!plantName.isNullOrBlank()) "Sensor $sensorIndex • $plantName" else "Sensor $sensorIndex",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Keep sensor in open air. Ensure the probe tips are clean and dry.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Hero Live Reading Card
            LiveReadingHeroCard(
                sensorIndex = sensorIndex,
                plantName = plantName,
                liveReading = liveReading,
                canDisplayLastKnownData = canDisplayLastKnownData
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Main CTA: Capture Dry Value
            Button(
                onClick = onCaptureDry,
                enabled = canSendCommands && liveReading != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(Icons.Default.WbSunny, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (liveReading != null) "Capture Dry Value ($liveReading)" else "Waiting for reading...",
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Manual fine-tune row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = dryValue.toString(),
                    onValueChange = { onDryValueChange(it.toIntOrNull() ?: dryValue) },
                    label = { Text("Manual Dry ADC") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                TextButton(onClick = onNextStep) {
                    Text("Skip to Step 2")
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun StepWetCalibrationCard(
    sensorIndex: Int,
    plantName: String?,
    liveReading: Int?,
    canSendCommands: Boolean,
    canDisplayLastKnownData: Boolean,
    wetValue: Int,
    onWetValueChange: (Int) -> Unit,
    onCaptureWet: () -> Unit,
    onPreviousStep: () -> Unit,
    onNextStep: () -> Unit
) {
    ElevatedCard(
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.WaterDrop,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Step 2: Wet Value (Water)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (!plantName.isNullOrBlank()) "Sensor $sensorIndex • $plantName" else "Sensor $sensorIndex",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Submerge sensor probe in water up to the maximum depth line.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            LiveReadingHeroCard(
                sensorIndex = sensorIndex,
                plantName = plantName,
                liveReading = liveReading,
                canDisplayLastKnownData = canDisplayLastKnownData
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onCaptureWet,
                enabled = canSendCommands && liveReading != null,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(Icons.Default.WaterDrop, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (liveReading != null) "Capture Wet Value ($liveReading)" else "Waiting for reading...",
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = wetValue.toString(),
                    onValueChange = { onWetValueChange(it.toIntOrNull() ?: wetValue) },
                    label = { Text("Manual Wet ADC") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onPreviousStep) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Step 1 (Air)")
                }
                TextButton(onClick = onNextStep) {
                    Text("Skip to Step 3")
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun StepReviewAndVerifyCard(
    sensorIndex: Int,
    plantName: String?,
    liveReading: Int?,
    dryValue: Int,
    wetValue: Int,
    percent: Float,
    canSendCommands: Boolean,
    hasUnsavedChanges: Boolean,
    onRecalibrate: () -> Unit,
    onSaveCalibration: () -> Unit
) {
    ElevatedCard(
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Step 3: Verify & Save",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (!plantName.isNullOrBlank()) "Sensor $sensorIndex • $plantName" else "Sensor $sensorIndex",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Calibration bounds summary card
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Dry Threshold", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$dryValue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = CalScaleEdge)
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Wet Threshold", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$wetValue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = CalScaleWet)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Estimated moisture percentage preview & scale
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Live Moisture Response",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (liveReading != null && dryValue > wetValue) "${(percent * 100).toInt()}%" else "--",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (percent > 0.3f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            CalibrationScale(
                liveReading = liveReading ?: dryValue,
                dryValue = dryValue,
                wetValue = wetValue
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Dip the sensor probe into soil or water to test live responsiveness before saving.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onRecalibrate,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Start Over")
                }

                Button(
                    onClick = onSaveCalibration,
                    enabled = hasUnsavedChanges && canSendCommands,
                    modifier = Modifier.weight(1.2f)
                ) {
                    Text("Save Calibration", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun LiveReadingHeroCard(
    sensorIndex: Int,
    plantName: String?,
    liveReading: Int?,
    canDisplayLastKnownData: Boolean
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(isVisible = liveReading != null && canDisplayLastKnownData)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (canDisplayLastKnownData) "Live ADC Stream" else "Device Offline",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = liveReading?.toString() ?: "--",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = if (liveReading != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (liveReading != null) {
                    if (!plantName.isNullOrBlank()) "Sensor $sensorIndex ($plantName) raw value" else "Sensor $sensorIndex raw value"
                } else "Waiting for telemetry...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CalibrationScale(
    liveReading: Int,
    dryValue: Int,
    wetValue: Int
) {
    val range = (CAL_SCALE_MAX - CAL_SCALE_MIN).toFloat().coerceAtLeast(1f)
    fun fractionOf(value: Int): Float =
        ((value - CAL_SCALE_MIN) / range).coerceIn(0f, 1f)

    Column {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
        ) {
            val trackWidth = maxWidth
            val trackHeight = 10.dp
            val markerSize = 14.dp

            // Track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(RoundedCornerShape(trackHeight / 2))
                    .background(
                        Brush.horizontalGradient(
                            listOf(CalScaleWet, CalScaleMid, CalScaleDry, CalScaleEdge)
                        )
                    )
            )

            // Wet marker
            Marker(
                fraction = fractionOf(wetValue),
                trackWidth = trackWidth,
                markerSize = markerSize,
                trackHeight = trackHeight,
                color = CalScaleWet,
                label = "Wet",
                labelColor = CalScaleWet
            )
            // Dry marker
            Marker(
                fraction = fractionOf(dryValue),
                trackWidth = trackWidth,
                markerSize = markerSize,
                trackHeight = trackHeight,
                color = CalScaleEdge,
                label = "Dry",
                labelColor = CalScaleEdge
            )
            // Live reading needle
            Box(
                modifier = Modifier
                    .offset(
                        x = (fractionOf(liveReading) * trackWidth.value).dp - 1.5.dp,
                        y = -4.dp
                    )
                    .size(width = 3.dp, height = trackHeight + 8.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(CalNeedle)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("$CAL_SCALE_MIN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$CAL_SCALE_MAX", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Marker(
    fraction: Float,
    trackWidth: Dp,
    markerSize: Dp,
    trackHeight: Dp,
    color: Color,
    label: String,
    labelColor: Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.offset(
            x = (fraction * trackWidth.value).dp - markerSize / 2
        )
    ) {
        Box(
            modifier = Modifier
                .size(markerSize)
                .offset(y = (trackHeight - markerSize) / 2)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = labelColor
        )
    }
}
