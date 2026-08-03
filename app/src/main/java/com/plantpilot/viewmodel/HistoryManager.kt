package com.plantpilot.viewmodel

import com.plantpilot.data.SettingsManager
import com.plantpilot.model.Plant
import com.plantpilot.model.TriggerType
import com.plantpilot.model.WateringEvent
import com.plantpilot.network.DeviceWateringEvent
import com.plantpilot.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Tracks watering history: appends new events (live WebSocket pushes or events
 * synced back from the device), dedupes, persists and notifies. Also completes
 * the coordinator's pending-watering waiters when a finishing event arrives.
 */
class HistoryManager(
    private val history: MutableStateFlow<List<WateringEvent>>,
    private val plants: MutableStateFlow<List<Plant>>,
    private val scope: CoroutineScope,
    private val settingsManager: SettingsManager,
    private val notificationHelper: NotificationHelper,
    private val onWateringFinished: (motorNumber: Int) -> Unit,
) {
    fun addHistoryEvent(event: WateringEvent) {
        val updatedHistory = (listOf(event) + history.value).take(50)
        history.value = updatedHistory
        scope.launch {
            settingsManager.saveHistory(updatedHistory)
        }

        // Also update the plant's last watered timestamp
        plants.value = plants.value.map {
            if (it.id == event.plantId) {
                it.copy(
                    lastWateredTimestamp = event.timestamp,
                    currentMoisture = event.moistureAfter ?: it.currentMoisture
                )
            } else it
        }

        // Complete any pending watering deferred for this motor
        onWateringFinished(event.motorNumber)

        // Show notification if it was a finished event we just received
        val plant = plants.value.find { it.id == event.plantId }
        if (plant != null) {
            notificationHelper.showWateringFinished(plant.name)
        }
    }

    fun processOfflineEvent(event: DeviceWateringEvent) {
        val plant = plants.value.find { it.motorNumber == event.motor } ?: return
        val timestamp = event.epoch * 1000L

        // Check if we already have this event in history (simple deduplication by timestamp/motor)
        if (history.value.any { it.motorNumber == event.motor && it.timestamp == timestamp }) return

        val newEvent = WateringEvent(
            id = UUID.randomUUID().toString(),
            plantId = plant.id,
            plantName = plant.name,
            motorNumber = plant.motorNumber,
            amountMl = event.amount_ml,
            triggerType = when (event.trigger) {
                "scheduled" -> TriggerType.SCHEDULED
                "auto" -> TriggerType.AUTOMATIC
                else -> TriggerType.MANUAL
            },
            timestamp = timestamp,
            moistureBefore = null,
            moistureAfter = event.soil_after
        )
        addHistoryEvent(newEvent)

        // Update the plant's UI state if this is the newest watering we've seen
        if (timestamp > plant.lastWateredTimestamp) {
            plants.value = plants.value.map { p ->
                if (p.id == plant.id) p.copy(lastWateredTimestamp = timestamp) else p
            }
        }
    }
}