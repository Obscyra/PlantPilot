package com.plantpilot.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.plantpilot.data.PlantPilotRepository
import com.plantpilot.data.SettingsManager
import com.plantpilot.model.AppSettings
import com.plantpilot.model.DayOfWeek
import com.plantpilot.model.Plant
import com.plantpilot.model.WateringMode
import com.plantpilot.model.WateringSchedule
import com.plantpilot.network.DeviceConfigResponse
import com.plantpilot.network.DeviceMotorConfig
import com.plantpilot.network.MotorConfig
import com.plantpilot.network.SyncRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Two-way config synchronization with the ESP32: pulls the device's config and
 * pushes ours, applying whichever side is newer per motor. Also owns the
 * config-dirty flag that gates syncing and the is-syncing spinner state.
 */
class SyncCoordinator(
    private val plants: MutableStateFlow<List<Plant>>,
    private val sensorCalibration: MutableStateFlow<Map<Int, Pair<Int, Int>>>,
    private val sensorFlowRate: MutableStateFlow<Map<Int, Int>>,
    private val settings: StateFlow<AppSettings>,
    private val scope: CoroutineScope,
    private val repository: PlantPilotRepository,
    private val settingsManager: SettingsManager,
    private val historyManager: HistoryManager,
    private val canSendCommands: () -> Boolean,
    private val getWaterLevelPct: () -> Int?,
    private val persistPlants: () -> Unit,
) {
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _isConfigDirty = MutableStateFlow(false)
    val isConfigDirty: StateFlow<Boolean> = _isConfigDirty.asStateFlow()

    private var autoSyncJob: kotlinx.coroutines.Job? = null

    fun markConfigDirty(autoSync: Boolean = true) {
        autoSyncJob?.cancel()
        if (autoSync && canSendCommands()) {
            autoSyncJob = scope.launch {
                delay(500.milliseconds)
                _isConfigDirty.value = true
                syncConfigWithDevice()
            }
        } else {
            _isConfigDirty.value = true
        }
    }

    suspend fun syncConfigWithDevice(silent: Boolean = false, force: Boolean = false) {
        if (_isSyncing.value || (!_isConfigDirty.value && !force)) return
        val startTime = System.currentTimeMillis()
        _isSyncing.value = true

        val motorConfigs = plants.value.map { plant ->
            MotorConfig(
                id = plant.motorNumber,
                name = plant.name,
                mode = when (plant.wateringMode) {
                    WateringMode.OFF -> "off"
                    WateringMode.AUTOMATIC -> "auto"
                    WateringMode.SCHEDULED -> "scheduled"
                },
                amount_ml = plant.waterAmountMl,
                threshold = plant.moistureThreshold,
                min_interval_hours = plant.minIntervalHours,
                last_watered = plant.lastWateredTimestamp / 1000,
                version = plant.configVersion,
                last_modified = plant.lastUpdated / 1000,
                ml_per_sec = plant.mlPerSec,
                max_runtime_minutes = settings.value.maxRuntimeMinutes,
                stop_on_disconnect = false, // Preserve per-motor setting if default
                schedules = plant.schedules
            )
        }

        val request = SyncRequest(
            epoch = System.currentTimeMillis() / 1000,
            motors = motorConfigs,
            water_level = getWaterLevelPct()
        )

        val result = repository.sync(request)

        if (result.isSuccess) {
            _isConfigDirty.value = false

            // Process any missed history events from the device
            result.getOrNull()?.history?.forEach { devEvent ->
                historyManager.processOfflineEvent(devEvent)
            }
        } else {
            _isConfigDirty.value = true
        }

        // Guarantee that "Updating..." chip is visible for at least 500ms
        val elapsed = System.currentTimeMillis() - startTime
        val remaining = (500L - elapsed).coerceAtLeast(0)
        if (remaining > 0) {
            delay(remaining.milliseconds)
        }
        _isSyncing.value = false
    }

    /**
     * Two-way config sync. Pulls the ESP32's current config with last-modified
     * timestamps and, per motor, whichever side is more recent wins. Then pushes
     * our (possibly updated) config; the firmware independently ignores anything
     * that isn't newer than what it already stores.
     */
    suspend fun performTwoWaySync() {
        val result: Result<DeviceConfigResponse> = repository.fetchDeviceConfig()
        if (result.isSuccess) {
            val configResp = result.getOrThrow()
            configResp.sensor_cadence_sec?.let { cadence ->
                if (cadence != settings.value.sensorCadenceSec) {
                    scope.launch {
                        settingsManager.saveAppSettings(settings.value.copy(sensorCadenceSec = cadence))
                    }
                }
            }
            val deviceMotors = configResp.motors
            // Keep the calibration UI in sync with what the device stores.
            val calibration = deviceMotors.mapNotNull { dev ->
                val dry = dev.calibration_dry
                val wet = dev.calibration_wet
                if (dry != null && wet != null) dev.id to (dry to wet) else null
            }.toMap()
            if (calibration.isNotEmpty()) sensorCalibration.value = calibration
            val flowRates = deviceMotors.mapNotNull { dev ->
                dev.ml_per_sec?.let { dev.id to it }
            }.toMap()
            if (flowRates.isNotEmpty()) sensorFlowRate.value = flowRates
            var changed = false
            deviceMotors.forEach { dev ->
                if (applyDeviceConfig(dev)) changed = true
            }
            if (changed) persistPlants()
        }
        // Force sync on reconnect so offline history entries are retrieved from the device
        syncConfigWithDevice(silent = true, force = true)
    }

    /**
     * Applies a device motor config to the matching plant, but only when the
     * device is newer (last_modified epoch seconds vs plant.lastUpdated epoch
     * millis). This keeps both sides in sync without ever clobbering edits the
     * user just made in the app (those bump lastUpdated and win the next push).
     */
    fun applyDeviceConfig(dev: DeviceMotorConfig): Boolean {
        val plant = plants.value.find { it.motorNumber == dev.id } ?: return false
        if (dev.last_modified * 1000L <= plant.lastUpdated) return false
        val devLastWateredMs = dev.last_watered?.takeIf { it > 0 }?.let { s -> s * 1000L } ?: 0L
        plants.update { currentList ->
            currentList.map {
                if (it.id == plant.id) {
                    it.copy(
                        wateringMode = devModeToMode(dev.mode),
                        waterAmountMl = dev.amount_ml,
                        moistureThreshold = dev.threshold ?: it.moistureThreshold,
                        minIntervalHours = dev.min_interval_hours ?: it.minIntervalHours,
                        lastWateredTimestamp = maxOf(it.lastWateredTimestamp, devLastWateredMs),
                        // Soft-merge schedules: match by HH:MM to preserve IDs and selected days.
                        schedules = dev.schedules.map { devSched ->
                            val existing = it.schedules.find { s -> s.hour == devSched.hour && s.minute == devSched.minute }
                            existing ?: WateringSchedule(
                                id = UUID.randomUUID().toString(),
                                hour = devSched.hour,
                                minute = devSched.minute,
                                daysOfWeek = DayOfWeek.entries.toSet()
                            )
                        },
                        configVersion = dev.version,
                        lastUpdated = dev.last_modified * 1000,
                        mlPerSec = dev.ml_per_sec ?: it.mlPerSec
                    )
                } else it
            }
        }
        dev.max_runtime_minutes?.let { maxRun ->
            if (maxRun != settings.value.maxRuntimeMinutes) {
                scope.launch {
                    settingsManager.saveAppSettings(settings.value.copy(maxRuntimeMinutes = maxRun))
                }
            }
        }
        return true
    }

    private fun devModeToMode(mode: String): WateringMode = when (mode) {
        "auto" -> WateringMode.AUTOMATIC
        "scheduled" -> WateringMode.SCHEDULED
        else -> WateringMode.OFF
    }
}