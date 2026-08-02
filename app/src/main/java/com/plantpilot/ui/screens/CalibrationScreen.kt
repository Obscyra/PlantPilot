package com.plantpilot.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.plantpilot.viewmodel.PlantPilotViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CAL_SCALE_MIN = 0
private const val CAL_SCALE_MAX = 4095
private const val TERMINAL_MAX_LINES = 20

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    viewModel: PlantPilotViewModel,
    onBack: () -> Unit,
) {
    val isConnected by viewModel.isConnected.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val existingCalibration by viewModel.sensorCalibration.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Request the realtime 1s sensor stream while the screen is open, kick off an
    // immediate read, and stop the stream as soon as the screen leaves composition.
    LaunchedEffect(Unit) {
        viewModel.requestSensorReading()
        viewModel.setCalibrationStreaming(true)
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.setCalibrationStreaming(false) }
    }

    // Rolling raw-value terminal: append a line per telemetry tick, keep the last
    // N, and auto-scroll to the bottom.
    val terminalLines = remember { mutableStateListOf<String>() }
    val terminalScroll = rememberScrollState()
    LaunchedEffect(telemetry) {
        val raw = telemetry?.raw_soil
        if (raw != null) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val row = raw.mapIndexed { index, value ->
                "S${index + 1}:${if (value >= 0) value.toString() else "----"}"
            }.joinToString("  ")
            terminalLines.add("$time  $row")
            while (terminalLines.size > TERMINAL_MAX_LINES) terminalLines.removeAt(0)
        }
    }
    LaunchedEffect(terminalLines.size) {
        terminalScroll.animateScrollTo(Int.MAX_VALUE)
    }

    var selectedSensor by remember { mutableIntStateOf(1) }
    val initial = existingCalibration[selectedSensor]
    var dryValue by remember(selectedSensor) { mutableIntStateOf(initial?.first ?: 4095) }
    var wetValue by remember(selectedSensor) { mutableIntStateOf(initial?.second ?: 1400) }

    // Live raw ADC reading for the selected sensor (raw_soil, sensor index + 1).
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Status header
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(isVisible = isConnected)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isConnected) "Live Reading" else "Device Offline",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isConnected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "1s stream",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Raw sensor value terminal — fills remaining screen height.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0F1115))
                    .verticalScroll(state = terminalScroll)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (terminalLines.isEmpty()) {
                    Text(
                        text = if (isConnected) "Waiting for telemetry..." else "Device offline",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFF666A70)
                    )
                } else {
                    terminalLines.forEach { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = Color(0xFF9EFFB0)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sensor selector
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                for (sensor in 1..4) {
                    SegmentedButton(
                        selected = selectedSensor == sensor,
                        onClick = {
                            selectedSensor = sensor
                            val cal = existingCalibration[sensor]
                            dryValue = cal?.first ?: 4095
                            wetValue = cal?.second ?: 1400
                        },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = sensor - 1,
                            count = 4
                        )
                    ) {
                        Text("Sensor $sensor")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Live reading card for the selected sensor — fixed height so the
            // big number / placeholder never shifts the surrounding layout.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulsingDot(isVisible = liveReading != null && isConnected)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isConnected) "Live Reading" else "Device Offline",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = liveReading?.toString() ?: "--",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        color = if (liveReading != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (liveReading != null) "Sensor $selectedSensor raw value" else "Waiting for telemetry...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Estimated moisture preview
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Estimated moisture",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (liveReading != null && dryValue > wetValue) "${(percent * 100).toInt()}%" else "--",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (percent > 0.3f) Color(0xFF2E7D32) else Color(0xFFFBC02D)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Scale with markers (labels below the track, aligned to each marker)
            CalibrationScale(
                liveReading = liveReading ?: dryValue,
                dryValue = dryValue,
                wetValue = wetValue
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Map buttons — Dry left, Wet right (mirrors the value boxes below)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { liveReading?.let { dryValue = it } },
                    enabled = liveReading != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.WbSunny, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Set as Dry")
                }
                OutlinedButton(
                    onClick = { liveReading?.let { wetValue = it } },
                    enabled = liveReading != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.WaterDrop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Set as Wet")
                }
            }
            Text(
                text = "Tip: with the sensor in open air tap 'Set as Dry'. Submerge it in water and tap 'Set as Wet'.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Fine-tune values — Dry left, Wet right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CalibrationValueField(
                    label = "Dry Value",
                    value = dryValue,
                    onValueChange = { dryValue = it },
                    modifier = Modifier.weight(1f)
                )
                CalibrationValueField(
                    label = "Wet Value",
                    value = wetValue,
                    onValueChange = { wetValue = it },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        viewModel.calibrateSensor(selectedSensor, dryValue, wetValue) { success ->
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = if (success) {
                                        "Sensor $selectedSensor calibrated (Dry: $dryValue, Wet: $wetValue)"
                                    } else {
                                        "Calibration failed — device unreachable"
                                    },
                                    duration = SnackbarDuration.Long
                                )
                            }
                        }
                    },
                    enabled = dryValue > wetValue && isConnected
                ) {
                    Text("Save Calibration")
                }
            }
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
                            listOf(
                                Color(0xFF2196F3),
                                Color(0xFF66BB6A),
                                Color(0xFFEF9A2C),
                                Color(0xFF8D6E63)
                            )
                        )
                    )
            )

            // Wet marker
            Marker(
                fraction = fractionOf(wetValue),
                trackWidth = trackWidth,
                markerSize = markerSize,
                trackHeight = trackHeight,
                color = Color(0xFF2196F3),
                label = "Wet",
                labelColor = Color(0xFF2196F3)
            )
            // Dry marker
            Marker(
                fraction = fractionOf(dryValue),
                trackWidth = trackWidth,
                markerSize = markerSize,
                trackHeight = trackHeight,
                color = Color(0xFF8D6E63),
                label = "Dry",
                labelColor = Color(0xFF8D6E63)
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
                    .background(Color(0xFFFFFFFF))
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
    // Column offset so the dot sits at `fraction` on the track; the label renders
    // below the track (no overlap) and centered on its marker.
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

@Composable
private fun CalibrationValueField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { onValueChange(it.toIntOrNull() ?: value) },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier
    )
}
