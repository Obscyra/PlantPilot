package com.plantpilot.viewmodel

import com.plantpilot.data.SettingsManager
import com.plantpilot.model.AppSettings
import com.plantpilot.model.DeviceState
import com.plantpilot.model.Plant
import com.plantpilot.network.DeviceMotorConfig
import com.plantpilot.network.DeviceStatusResponse
import com.plantpilot.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single entry point for every telemetry frame (live WebSocket push and the
 * persisted snapshot hydrated at startup). Updates tank/SSID state, plant
 * moisture, syncs per-plant config from the device when newer, and persists
 * a debounced snapshot so a fully closed app reopens with last-known data.
 */
class TelemetryProcessor(
    private val deviceStateFlow: MutableStateFlow<DeviceState>,
    private val plantsFlow: MutableStateFlow<List<Plant>>,
    private val settings: StateFlow<AppSettings>,
    private val scope: CoroutineScope,
    private val settingsManager: SettingsManager,
    private val notificationHelper: NotificationHelper,
    private val applyDeviceConfig: (DeviceMotorConfig) -> Boolean,
    private val persistPlants: () -> Unit,
) {
    // Low water reminder: notify at most once per hour while low
    private var lastLowWaterNotifiedAt = 0L
    private val LOW_WATER_NOTIFY_INTERVAL_MS = 60 * 60 * 1000L

    // Persistent telemetry snapshot is rewritten at most every 2s so a closed
    // app reopens with last-known data without hammering DataStore.
    private var lastSnapshotSavedAt = 0L
    private val SNAPSHOT_SAVE_INTERVAL_MS = 2000L

    fun applyTelemetry(status: DeviceStatusResponse) {
        // The firmware currently hardcodes water_level=100 and never decrements
        // it, so it can't drive the displayed tank level. Until a real tank
        // sensor is wired, derive the discrete level from the app-tracked
        // estimate (estimatedWaterMl).
        val capacity = deviceStateFlow.value.tankCapacityMl
        val tankLevel = mlToLevel(deviceStateFlow.value.estimatedWaterMl, capacity)
        
        deviceStateFlow.update { current ->
            current.copy(
                waterTankLevel = tankLevel,
                wifiSsid = status.wifi_ssid ?: current.wifiSsid,
                queuedPumps = status.queued ?: listOf(false, false, false, false)
            )
        }

        // Hourly low-water reminder
        if (settings.value.notificationsLowWater &&
            tankLevel <= deviceStateFlow.value.lowWaterThreshold
        ) {
            val now = System.currentTimeMillis()
            if (now - lastLowWaterNotifiedAt >= LOW_WATER_NOTIFY_INTERVAL_MS) {
                lastLowWaterNotifiedAt = now
                notificationHelper.showLowWaterAlert()
            }
        }

        // Update plants with high-precision App-side moisture calculation from raw ADC
        plantsFlow.update { currentList ->
            currentList.map { plant ->
                val rawAdc = status.raw_soil?.getOrNull(plant.motorNumber - 1)
                val moisture = if (rawAdc != null && plant.dryCalibration > 0 && plant.wetCalibration > 0) {
                    val dry = plant.dryCalibration
                    val wet = plant.wetCalibration
                    val range = (wet - dry).toFloat()
                    if (range != 0f) {
                        ((rawAdc - dry) / range * 100f).toInt().coerceIn(0, 100)
                    } else {
                        status.soil.getOrNull(plant.motorNumber - 1) ?: plant.currentMoisture
                    }
                } else {
                    status.soil.getOrNull(plant.motorNumber - 1) ?: plant.currentMoisture
                }
                plant.copy(currentMoisture = moisture)
            }
        }

        // Keep per-plant config in sync from the live stream (device-newer only).
        var configChanged = false
        status.motors?.forEach { dev ->
            if (applyDeviceConfig(dev)) configChanged = true
        }
        if (configChanged) persistPlants()

        // Debounced persistent snapshot (≤1 write / 2s).
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSnapshotSavedAt >= SNAPSHOT_SAVE_INTERVAL_MS) {
            lastSnapshotSavedAt = nowMs
            scope.launch { settingsManager.saveTelemetrySnapshot(status) }
        }
    }

    private fun mlToLevel(ml: Int, capacity: Int): Int {
        if (capacity <= 0) return 0
        val pct = (ml.toFloat() / capacity * 100).toInt().coerceIn(0, 100)
        return when {
            pct <= 10 -> 0
            pct <= 35 -> 1
            pct <= 60 -> 2
            pct <= 85 -> 3
            else -> 4
        }
    }
}