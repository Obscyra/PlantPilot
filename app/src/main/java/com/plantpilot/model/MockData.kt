package com.plantpilot.model

import java.util.UUID

object MockData {

    fun generatePlants(): List<Plant> = listOf(
        Plant(
            id = "plant_1",
            name = "Money Plant",
            motorNumber = 1,
            wateringMode = WateringMode.OFF,
            waterAmountMl = 50,
            moistureThreshold = 30,
            minIntervalHours = 6,
            currentMoisture = 50,
            lastWateredTimestamp = 0,
            schedules = emptyList(),
            dryCalibration = 4095,
            wetCalibration = 1400,
            configVersion = 1
        ),
        Plant(
            id = "plant_2",
            name = "Rose",
            motorNumber = 2,
            wateringMode = WateringMode.OFF,
            waterAmountMl = 50,
            moistureThreshold = 25,
            minIntervalHours = 12,
            currentMoisture = 50,
            lastWateredTimestamp = 0,
            schedules = emptyList(),
            dryCalibration = 4095,
            wetCalibration = 1500,
            configVersion = 1
        ),
        Plant(
            id = "plant_3",
            name = "Aloe Vera",
            motorNumber = 3,
            wateringMode = WateringMode.OFF,
            waterAmountMl = 50,
            moistureThreshold = 35,
            minIntervalHours = 8,
            currentMoisture = 50,
            lastWateredTimestamp = 0,
            schedules = emptyList(),
            dryCalibration = 4095,
            wetCalibration = 1350,
            configVersion = 1
        ),
        Plant(
            id = "plant_4",
            name = "Snake Plant",
            motorNumber = 4,
            wateringMode = WateringMode.OFF,
            waterAmountMl = 50,
            moistureThreshold = 20,
            minIntervalHours = 24,
            currentMoisture = 50,
            lastWateredTimestamp = 0,
            schedules = emptyList(),
            dryCalibration = 4095,
            wetCalibration = 1600,
            configVersion = 1
        )
    )

    fun generateHistory(plants: List<Plant>): List<WateringEvent> {
        return emptyList()
    }

    fun defaultDeviceState() = DeviceState(
        isConnected = false,
        deviceName = "PlantPilot-PilotCore",
        wifiSsid = "Neural Net",
        deviceIp = "plantpilot.local",
        waterTankLevel = 0,
        waterTankSensorValue = 0,
        tankCapacityMl = 5000,
        lowWaterThreshold = 1,
        estimatedWaterMl = 5000,
        queuedPumps = listOf(false, false, false, false)
    )

    fun defaultSettings() = AppSettings(
        notificationsLowWater = true,
        notificationsWateringCompleted = true,
        notificationsScheduleReminders = false,
        useMetricUnits = true,
        use24HourFormat = false,
        pumpFlowRateMlPerSec = 10
    )
}
