package com.plantpilot.viewmodel

import com.plantpilot.data.SettingsManager
import com.plantpilot.model.Plant
import com.plantpilot.model.WateringMode
import com.plantpilot.model.WateringSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns plant CRUD, watering-mode/schedule edits and their persistence.
 * Config-dirty flagging is delegated out so the sync layer can react.
 */
class PlantConfigManager(
    private val plantsFlow: MutableStateFlow<List<Plant>>,
    private val scope: CoroutineScope,
    private val settingsManager: SettingsManager,
    private val markConfigDirty: (autoSync: Boolean) -> Unit,
) {
    fun updatePlant(plantId: String, autoSync: Boolean = false, update: (Plant) -> Plant) {
        plantsFlow.update { currentList ->
            currentList.map {
                if (it.id == plantId) {
                    val updated = update(it)
                    if (updated != it) {
                        updated.copy(
                            configVersion = updated.configVersion + 1,
                            lastUpdated = System.currentTimeMillis()
                        )
                    } else it
                } else it
            }
        }
        markConfigDirty(autoSync)
        persistPlants()
    }

    fun updateWateringMode(plantId: String, mode: WateringMode) {
        updatePlant(plantId, autoSync = true) { plant ->
            plant.copy(
                wateringMode = mode,
                moistureThreshold = if (mode == WateringMode.AUTOMATIC && plant.moistureThreshold == 0) 10 else plant.moistureThreshold
            )
        }
    }

    fun updateWateringAmount(plantId: String, amountMl: Int) {
        updatePlant(plantId, autoSync = true) { it.copy(waterAmountMl = amountMl.coerceIn(10, 100)) }
    }

    fun updatePlantName(plantId: String, name: String) {
        updatePlant(plantId, autoSync = true) { it.copy(name = name) }
    }

    fun updateMoistureThreshold(plantId: String, threshold: Int) {
        updatePlant(plantId, autoSync = true) { it.copy(moistureThreshold = threshold) }
    }

    fun updateMinInterval(plantId: String, hours: Int) {
        updatePlant(plantId, autoSync = true) { it.copy(minIntervalHours = hours) }
    }

    fun deletePlant(plantId: String) {
        plantsFlow.update { current -> current.filter { it.id != plantId } }
        markConfigDirty(true)
        persistPlants()
    }

    fun addSchedule(plantId: String, schedule: WateringSchedule) {
        updatePlant(plantId, autoSync = true) { plant ->
            plant.copy(
                schedules = plant.schedules + schedule,
                wateringMode = if (plant.wateringMode == WateringMode.OFF) WateringMode.SCHEDULED else plant.wateringMode
            )
        }
    }

    fun removeSchedule(plantId: String, scheduleId: String) {
        updatePlant(plantId, autoSync = true) { plant ->
            val remainingSchedules = plant.schedules.filter { s -> s.id != scheduleId }
            plant.copy(
                schedules = remainingSchedules,
                wateringMode = if (remainingSchedules.isEmpty() && plant.wateringMode == WateringMode.SCHEDULED) {
                    WateringMode.OFF
                } else {
                    plant.wateringMode
                }
            )
        }
    }

    fun updateSchedule(plantId: String, schedule: WateringSchedule) {
        updatePlant(plantId, autoSync = true) { plant ->
            plant.copy(
                schedules = plant.schedules.map { s -> if (s.id == schedule.id) schedule else s },
                wateringMode = if (plant.wateringMode == WateringMode.OFF) WateringMode.SCHEDULED else plant.wateringMode
            )
        }
    }

    fun persistPlants() {
        scope.launch {
            settingsManager.savePlants(plantsFlow.value)
        }
    }
}