package com.plantpilot.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.plantpilot.model.AppSettings
import com.plantpilot.model.DeviceState
import com.plantpilot.model.Plant
import com.plantpilot.model.WateringEvent
import com.plantpilot.network.DeviceStatusResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val DEFAULT_IP: String = "plantpilot.local"

        val DEVICE_NAME: Preferences.Key<String> = stringPreferencesKey("device_name")
        val DEVICE_IP: Preferences.Key<String> = stringPreferencesKey("device_ip")
        val WIFI_SSID: Preferences.Key<String> = stringPreferencesKey("wifi_ssid")
        val TANK_CAPACITY: Preferences.Key<Int> = intPreferencesKey("tank_capacity")
        val ESTIMATED_WATER_ML: Preferences.Key<Int> = intPreferencesKey("estimated_water_ml")
        val LOW_WATER_THRESHOLD: Preferences.Key<Int> = intPreferencesKey("low_water_threshold")
        val LOW_WATER_ALERT: Preferences.Key<Boolean> = booleanPreferencesKey("low_water_alert")
        val USE_METRIC: Preferences.Key<Boolean> = booleanPreferencesKey("use_metric")
        val USE_24H: Preferences.Key<Boolean> = booleanPreferencesKey("use_24h")
        val WATERING_HISTORY: Preferences.Key<String> = stringPreferencesKey("watering_history")
        val ONBOARDING_COMPLETED: Preferences.Key<Boolean> = booleanPreferencesKey("onboarding_completed")
        val PLANTS: Preferences.Key<String> = stringPreferencesKey("plants_config")
        val TELEMETRY_SNAPSHOT: Preferences.Key<String> = stringPreferencesKey("telemetry_snapshot")
        val MAX_RUNTIME: Preferences.Key<Int> = intPreferencesKey("max_runtime_minutes")
        val SENSOR_CADENCE: Preferences.Key<Int> = intPreferencesKey("sensor_cadence_sec")
        val PUMP_FLOW_RATE: Preferences.Key<Int> = intPreferencesKey("pump_flow_rate_ml_per_sec")
        val PLANTS_MIGRATED_V2: Preferences.Key<Boolean> = booleanPreferencesKey("plants_migrated_v2")
        val DEMO_MODE: Preferences.Key<Boolean> = booleanPreferencesKey("demo_mode")
        val LOW_WATER_BUZZ_CADENCE: Preferences.Key<Int> = intPreferencesKey("low_water_buzz_cadence_min")
        val USE_HARDWARE_WATER_SENSOR: Preferences.Key<Boolean> = booleanPreferencesKey("use_hardware_water_sensor")
    }

    val deviceStateFlow: Flow<PartialDeviceState> = context.dataStore.data.map { prefs ->
        PartialDeviceState(
            name = prefs[DEVICE_NAME] ?: "PlantPilot-PilotCore",
            ip = prefs[DEVICE_IP]?.takeIf { it.isNotBlank() } ?: DEFAULT_IP,
            ssid = prefs[WIFI_SSID] ?: "Not Set", // Changed from Neural Net
            capacity = prefs[TANK_CAPACITY] ?: 5000,
            threshold = prefs[LOW_WATER_THRESHOLD] ?: 1,
            estimatedWaterMl = prefs[ESTIMATED_WATER_ML]
        )
    }

    val appSettingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            notificationsLowWater = prefs[LOW_WATER_ALERT] ?: true,
            notificationsWateringCompleted = false, // Deprecated but kept for model compatibility
            notificationsScheduleReminders = false, // Deprecated
            useMetricUnits = prefs[USE_METRIC] ?: true,
            use24HourFormat = prefs[USE_24H] ?: false,
            maxRuntimeMinutes = prefs[MAX_RUNTIME] ?: 1,
            sensorCadenceSec = prefs[SENSOR_CADENCE] ?: 12,
            pumpFlowRateMlPerSec = prefs[PUMP_FLOW_RATE] ?: 10,
            demoMode = prefs[DEMO_MODE] ?: false,
            lowWaterBuzzCadenceMin = prefs[LOW_WATER_BUZZ_CADENCE] ?: 15,
            useHardwareWaterSensor = prefs[USE_HARDWARE_WATER_SENSOR] ?: true
        )
    }

    val onboardingCompletedFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ONBOARDING_COMPLETED] ?: false
    }

    val historyFlow: Flow<List<WateringEvent>> = context.dataStore.data.map { prefs ->
        val jsonString = prefs[WATERING_HISTORY] ?: "[]"
        try {
            json.decodeFromString<List<WateringEvent>>(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Persisted plant list. Returns null until the user has actually saved a
     * config (so a fresh install falls back to MockData), then the exact saved
     * list — including an explicitly empty one.
     */
    val plantsFlow: Flow<List<Plant>?> = context.dataStore.data.map { prefs ->
        val raw = prefs[PLANTS] ?: return@map null
        try {
            json.decodeFromString<List<Plant>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Last known telemetry snapshot (moisture, tank level, RSSI, full motor
     * config). Saved by the app while it runs so a fully closed app reopens
     * with up-to-date data before the first live message arrives.
     */
    val lastTelemetryFlow: Flow<DeviceStatusResponse?> = context.dataStore.data.map { prefs ->
        val raw = prefs[TELEMETRY_SNAPSHOT] ?: return@map null
        try {
            json.decodeFromString<DeviceStatusResponse>(raw)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveDeviceState(state: DeviceState) {
        context.dataStore.edit { prefs ->
            prefs[DEVICE_NAME] = state.deviceName
            prefs[DEVICE_IP] = state.deviceIp.ifBlank { DEFAULT_IP }
            prefs[WIFI_SSID] = state.wifiSsid
            prefs[TANK_CAPACITY] = state.tankCapacityMl
            prefs[LOW_WATER_THRESHOLD] = state.lowWaterThreshold
            prefs[ESTIMATED_WATER_ML] = state.estimatedWaterMl
        }
    }

    suspend fun saveAppSettings(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[LOW_WATER_ALERT] = settings.notificationsLowWater
            prefs[USE_METRIC] = settings.useMetricUnits
            prefs[USE_24H] = settings.use24HourFormat
            prefs[MAX_RUNTIME] = settings.maxRuntimeMinutes
            prefs[SENSOR_CADENCE] = settings.sensorCadenceSec
            prefs[PUMP_FLOW_RATE] = settings.pumpFlowRateMlPerSec
            prefs[DEMO_MODE] = settings.demoMode
            prefs[LOW_WATER_BUZZ_CADENCE] = settings.lowWaterBuzzCadenceMin
            prefs[USE_HARDWARE_WATER_SENSOR] = settings.useHardwareWaterSensor
        }
    }

    suspend fun saveOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ONBOARDING_COMPLETED] = completed
        }
    }

    suspend fun saveTelemetrySnapshot(status: DeviceStatusResponse) {
        context.dataStore.edit { prefs ->
            prefs[TELEMETRY_SNAPSHOT] = json.encodeToString(status)
        }
    }

    suspend fun saveHistory(history: List<WateringEvent>) {
        context.dataStore.edit { prefs ->
            val jsonString = json.encodeToString(history.take(50)) // Keep last 50 events
            prefs[WATERING_HISTORY] = jsonString
        }
    }

    val plantsMigratedV2Flow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PLANTS_MIGRATED_V2] ?: false
    }

    suspend fun savePlants(plants: List<Plant>) {
        context.dataStore.edit { prefs ->
            prefs[PLANTS] = json.encodeToString(plants)
        }
    }

    suspend fun savePlantsMigratedV2(migrated: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PLANTS_MIGRATED_V2] = migrated
        }
    }

    data class PartialDeviceState(
        val name: String,
        val ip: String,
        val ssid: String,
        val capacity: Int,
        val threshold: Int,
        val estimatedWaterMl: Int?
    )
}
