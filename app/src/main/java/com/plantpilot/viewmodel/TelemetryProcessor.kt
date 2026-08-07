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
        val capacity = deviceStateFlow.value.tankCapacityMl.coerceAtLeast(100)
        val currentEstimatedMl = deviceStateFlow.value.estimatedWaterMl
        val isDemo = settings.value.demoMode || status.demo_mode == true
        val hardwareDiscrete = if (settings.value.useHardwareWaterSensor || isDemo) {
            status.water_level_raw ?: (if (status.water_level >= 0) status.water_level / 25 else null)
        } else {
            null // Completely ignore unattached hardware NPN sensor pin noise!
        }

        val (tankLevel, reconciledMl) = calculateWaterTankState(
            hardwareLevelDiscrete = hardwareDiscrete,
            currentEstimatedMl = currentEstimatedMl,
            capacityMl = capacity,
            isDemoMode = isDemo
        )

        deviceStateFlow.update { current ->
            current.copy(
                waterTankLevel = tankLevel,
                estimatedWaterMl = reconciledMl,
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

    private var zeroReadBufferCount = 0

    private fun calculateWaterTankState(
        hardwareLevelDiscrete: Int?,
        currentEstimatedMl: Int,
        capacityMl: Int,
        isDemoMode: Boolean
    ): Pair<Int, Int> {
        if (hardwareLevelDiscrete == null || hardwareLevelDiscrete < 0) {
            val level = mlToLevel(currentEstimatedMl, capacityMl)
            return Pair(level, currentEstimatedMl)
        }

        val rawDiscrete = hardwareLevelDiscrete.coerceIn(0, 4)

        if (isDemoMode) {
            // DEMO MODE: Pure 3s direct hardware sensor read.
            // Bypasses all history volume subtraction math.
            val demoMl = (capacityMl * (rawDiscrete * 0.25f)).toInt()
            return Pair(rawDiscrete, demoMl)
        }

        // NORMAL MODE: Hybrid model with Hardware Sensor Top Priority.
        // Hardware sensor level changes immediately anchor tank volume bounds.
        // History volume subtraction operates smoothly within the hardware sensor's bounds.
        if (rawDiscrete == 0) {
            return Pair(0, 0)
        }

        val (minFraction, maxFraction, midFraction) = when (rawDiscrete) {
            4 -> Triple(0.75f, 1.00f, 1.000f) // Level 4 (Full): 100%
            3 -> Triple(0.50f, 0.75f, 0.750f) // Level 3: 75%
            2 -> Triple(0.25f, 0.50f, 0.500f) // Level 2: 50%
            1 -> Triple(0.05f, 0.25f, 0.250f) // Level 1: 25%
            else -> Triple(0.00f, 0.00f, 0.000f)
        }

        val minAllowedMl = (capacityMl * minFraction).toInt()
        val maxAllowedMl = (capacityMl * maxFraction).toInt()
        val midpointMl = (capacityMl * midFraction).toInt()

        // If tracked history volume is outside hardware sensor boundaries (e.g. sensor level change or refill),
        // HARDWARE SENSOR WINS with top priority and snaps volume to the midpoint of the hardware level range.
        val reconciledMl = if (currentEstimatedMl < minAllowedMl || currentEstimatedMl > maxAllowedMl) {
            midpointMl
        } else {
            currentEstimatedMl // Smooth history subtraction within sensor level band
        }

        return Pair(rawDiscrete, reconciledMl)
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