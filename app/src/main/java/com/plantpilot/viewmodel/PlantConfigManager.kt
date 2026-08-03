package com.plantpilot.viewmodel

import com.plantpilot.data.SettingsManager
import com.plantpilot.model.Plant
import com.plantpilot.model.WateringMode
import com.plantpilot.model.WateringSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Owns plant CRUD, watering-mode/schedule edits and their persistence.
 * Config-dirty flagging is delegated out so the sync layer can react.
 */
class PlantConfigManager(
    private val plants: MutableStateFlow<List<Plant>>,
    private val scope: CoroutineScope,
    private val settingsManager: SettingsManager,
    private val markConfigDirty: (autoSync: Boolean) -> Unit,
) {
    fun updatePlant(plantId: String, update: (Plant) -> Plant) {
        var changed = false
        val updatedList = plants.value.map {
            if (it.id == plantId) {
                val updated = update(it)
                if (updated != it) {
                    changed = true
                    updated.copy(
                        configVersion = updated.configVersion + 1,
                        lastUpdated = System.currentTimeMillis()
                    )
                } else it
            } else it
        }
        if (changed) {
            plants.value = updatedList
            markConfigDirty(false)
            persistPlants()
        }
    }

    fun updateWateringMode(plantId: String, mode: WateringMode) {
        updatePlant(plantId) { plant ->
            plant.copy(
                wateringMode = mode,
                moistureThreshold = if (mode == WateringMode.AUTOMATIC && plant.moistureThreshold == 0) 10 else plant.moistureThreshold
            )
        }
        markConfigDirty(true)
    }

    fun updateWateringAmount(plantId: String, amountMl: Int) {
        updatePlant(plantId) { it.copy(waterAmountMl = amountMl) }
        markConfigDirty(true)
    }

    fun updatePlantName(plantId: String, name: String) {
        updatePlant(plantId) { it.copy(name = name) }
        markConfigDirty(true)
    }

    fun updateMoistureThreshold(plantId: String, threshold: Int) {
        updatePlant(plantId) { it.copy(moistureThreshold = threshold) }
        markConfigDirty(true)
    }

    fun updateMinInterval(plantId: String, hours: Int) {
        updatePlant(plantId) { it.copy(minIntervalHours = hours) }
        markConfigDirty(true)
    }

    fun deletePlant(plantId: String) {
        plants.value = plants.value.filter { it.id != plantId }
        markConfigDirty(true)
        persistPlants()
    }

    fun addSchedule(plantId: String, schedule: WateringSchedule) {
        updatePlant(plantId) { plant ->
            plant.copy(
                schedules = plant.schedules + schedule,
                wateringMode = if (plant.wateringMode == WateringMode.OFF) WateringMode.SCHEDULED else plant.wateringMode
            )
        }
        markConfigDirty(true)
    }

    fun removeSchedule(plantId: String, scheduleId: String) {
        updatePlant(plantId) { plant ->
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
        markConfigDirty(true)
    }

    fun updateSchedule(plantId: String, schedule: WateringSchedule) {
        updatePlant(plantId) { plant ->
            plant.copy(
                schedules = plant.schedules.map { s -> if (s.id == schedule.id) schedule else s },
                wateringMode = if (plant.wateringMode == WateringMode.OFF) WateringMode.SCHEDULED else plant.wateringMode
            )
        }
        markConfigDirty(true)
    }

    fun persistPlants() {
        scope.launch {
            settingsManager.savePlants(plants.value)
        }
    }
}