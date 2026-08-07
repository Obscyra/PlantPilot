package com.plantpilot.model

import kotlinx.serialization.Serializable

@Serializable
enum class WateringMode {
    OFF,
    SCHEDULED,
    AUTOMATIC
}

@Serializable
enum class TriggerType {
    MANUAL,
    SCHEDULED,
    AUTOMATIC
}

@Serializable
data class WateringSchedule(
    val id: String,
    val hour: Int,
    val minute: Int,
    val daysOfWeek: Set<DayOfWeek>
)

@Serializable
enum class DayOfWeek(val shortName: String) {
    MON("Mon"),
    TUE("Tue"),
    WED("Wed"),
    THU("Thu"),
    FRI("Fri"),
    SAT("Sat"),
    SUN("Sun")
}

@Serializable
data class Plant(
    val id: String,
    val name: String,
    val motorNumber: Int,
    val wateringMode: WateringMode,
    val waterAmountMl: Int,
    val moistureThreshold: Int,
    val minIntervalHours: Int,
    val currentMoisture: Int,
    val lastWateredTimestamp: Long,
    val schedules: List<WateringSchedule>,
    val dryCalibration: Int,
    val wetCalibration: Int,
    val configVersion: Int = 1,
    val lastUpdated: Long = 0, // epoch millis, used for two-way sync with the ESP32
    val mlPerSec: Int = 10, // Per-pump flow rate (ml/sec)
    val maxRuntimeMinutes: Int = 1 // Max minutes pump can run (0 = no limit)
)

@Serializable
data class WateringEvent(
    val id: String,
    val plantId: String,
    val plantName: String,
    val motorNumber: Int,
    val amountMl: Int,
    val triggerType: TriggerType,
    val timestamp: Long,
    val moistureBefore: Int?,
    val moistureAfter: Int?
)

data class DeviceState(
    val isConnected: Boolean,
    val deviceName: String,
    val wifiSsid: String,
    val deviceIp: String,          // Added for manual IP override
    val waterTankLevel: Int,       // 0-4 (4 discrete levels)
    val waterTankSensorValue: Int, // mock sensor reading 0-1023
    val tankCapacityMl: Int,
    val lowWaterThreshold: Int,    // 0-4 level threshold
    val estimatedWaterMl: Int = 0, // App-tracked remaining water, full on fresh install
    val queuedPumps: List<Boolean> = listOf(false, false, false, false)
)

data class AppSettings(
    val notificationsLowWater: Boolean,
    val notificationsWateringCompleted: Boolean,
    val notificationsScheduleReminders: Boolean,
    val useMetricUnits: Boolean,
    val use24HourFormat: Boolean,
    val maxRuntimeMinutes: Int = 1,
    val sensorCadenceSec: Int = 12,
    val pumpFlowRateMlPerSec: Int = 10,
    val demoMode: Boolean = false,
    val lowWaterBuzzCadenceMin: Int = 15,
    val useHardwareWaterSensor: Boolean = true
)
