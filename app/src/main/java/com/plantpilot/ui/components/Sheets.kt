package com.plantpilot.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.plantpilot.model.*
import java.util.Calendar
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
    val now = remember { Calendar.getInstance() }
    val timePickerState = rememberTimePickerState(
        initialHour = existingSchedule?.hour ?: now.get(Calendar.HOUR_OF_DAY),
        initialMinute = existingSchedule?.minute ?: now.get(Calendar.MINUTE),
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
