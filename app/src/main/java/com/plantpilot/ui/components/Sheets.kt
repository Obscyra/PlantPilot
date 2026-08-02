package com.plantpilot.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.plantpilot.model.*
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleBottomSheet(
    existingSchedule: WateringSchedule?,
    use24HourFormat: Boolean,
    onDismiss: () -> Unit,
    onSave: (WateringSchedule) -> Unit,
    onDelete: ((WateringSchedule) -> Unit)? = null
) {
    val timePickerState = rememberTimePickerState(
        initialHour = existingSchedule?.hour ?: 8,
        initialMinute = existingSchedule?.minute ?: 0,
        is24Hour = use24HourFormat
    )

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        ScheduleSheetContent(
            existingSchedule = existingSchedule,
            timePickerState = timePickerState,
            onDismiss = onDismiss,
            onSave = onSave,
            onDelete = onDelete
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSheetContent(
    existingSchedule: WateringSchedule?,
    timePickerState: TimePickerState,
    onDismiss: () -> Unit,
    onSave: (WateringSchedule) -> Unit,
    onDelete: ((WateringSchedule) -> Unit)? = null
) {
    var showDial by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (existingSchedule != null) "Edit Schedule" else "Add Schedule",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = String.format(Locale.getDefault(), "%02d:%02d", timePickerState.hour, timePickerState.minute),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            IconButton(onClick = { showDial = !showDial }) {
                Icon(
                    imageVector = if (showDial) Icons.Default.Keyboard else Icons.Default.Schedule,
                    contentDescription = "Toggle Picker Mode"
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (showDial) {
            TimePicker(state = timePickerState)
        } else {
            TimeInput(state = timePickerState)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (existingSchedule != null && onDelete != null) {
                TextButton(
                    onClick = {
                        onDelete(existingSchedule)
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            } else {
                Spacer(modifier = Modifier.width(8.dp))
            }

            Row {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val schedule = WateringSchedule(
                            id = existingSchedule?.id ?: UUID.randomUUID().toString(),
                            hour = timePickerState.hour,
                            minute = timePickerState.minute,
                            daysOfWeek = existingSchedule?.daysOfWeek ?: DayOfWeek.entries.toSet()
                        )
                        onSave(schedule)
                        onDismiss()
                    }
                ) {
                    Text("Save")
                }
            }
        }
    }
}

private const val CAL_SCALE_MIN = 0
private const val CAL_SCALE_MAX = 4095

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationBottomSheet(
    onDismiss: () -> Unit,
    onSave: (sensorId: Int, dryValue: Int, wetValue: Int) -> Unit,
    liveReadings: Map<Int, Int> = emptyMap(),
    existingCalibration: Map<Int, Pair<Int, Int>> = emptyMap(),
    isConnected: Boolean = true,
    onStreamingChange: (Boolean) -> Unit = {}
) {
    var selectedSensor by remember { mutableIntStateOf(1) }
    val initial = existingCalibration[selectedSensor]
    var dryValue by remember(selectedSensor) { mutableIntStateOf(initial?.first ?: 4095) }
    var wetValue by remember(selectedSensor) { mutableIntStateOf(initial?.second ?: 1400) }

    // Real raw ADC reading from live telemetry (raw_soil, sensor index + 1).
    val liveReading = liveReadings[selectedSensor]

    val percent = if (liveReading != null) {
        ((dryValue - liveReading).toFloat() / (dryValue - wetValue).toFloat()).coerceIn(0f, 1f)
    } else 0f

    // Request the realtime 1s sensor stream while the sheet is open and stop it
    // as soon as the sheet leaves composition (dismissed).
    LaunchedEffect(isConnected) {
        if (isConnected) onStreamingChange(true)
    }
    DisposableEffect(Unit) {
        onDispose { onStreamingChange(false) }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Sensor Calibration",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Select a sensor, read its live raw value, then map that reading to dry and wet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

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

            // Live reading card
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulsingDot(isVisible = liveReading != null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isConnected) "Live Reading" else "Device Offline",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = liveReading?.toString() ?: if (isConnected) "Waiting for telemetry..." else "--",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (liveReading != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Sensor $selectedSensor raw value",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Moisture preview for the current reading
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
                    text = if (liveReading != null) "${(percent * 100).toInt()}%" else "--",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (percent > 0.3f) Color(0xFF2E7D32) else Color(0xFFFBC02D)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Scale with markers
            CalibrationScale(
                liveReading = liveReading ?: dryValue,
                dryValue = dryValue,
                wetValue = wetValue
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Map buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { liveReading?.let { wetValue = it } },
                    enabled = liveReading != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.WaterDrop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Set as Wet")
                }
                OutlinedButton(
                    onClick = { liveReading?.let { dryValue = it } },
                    enabled = liveReading != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.WbSunny, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Set as Dry")
                }
            }
            Text(
                text = "Tip: with the sensor in open air tap 'Set as Dry'. Submerge it in water and tap 'Set as Wet'.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Fine-tune values
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

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        onSave(selectedSensor, dryValue, wetValue)
                        onDismiss()
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
                .height(28.dp)
        ) {
            val trackWidth = maxWidth
            val trackHeight = 10.dp
            val markerSize = 14.dp
            val halfMarker = markerSize / 2

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
                labelColor = Color(0xFF2196F3),
                isDry = false
            )
            // Dry marker
            Marker(
                fraction = fractionOf(dryValue),
                trackWidth = trackWidth,
                markerSize = markerSize,
                trackHeight = trackHeight,
                color = Color(0xFF8D6E63),
                label = "Dry",
                labelColor = Color(0xFF8D6E63),
                isDry = true
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
    isDry: Boolean
) {
    Column(
        horizontalAlignment = if (isDry) Alignment.End else Alignment.Start
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = labelColor
        )
        Box(
            modifier = Modifier
                .offset(
                    x = (fraction * trackWidth.value).dp - markerSize / 2,
                    y = 1.dp
                )
                .size(markerSize)
                .clip(CircleShape)
                .background(color)
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

@Composable
private fun PulsingDot(isVisible: Boolean) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    if (isVisible) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color(0xFF2E7D32).copy(alpha = alpha))
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}
