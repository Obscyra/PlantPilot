package com.plantpilot

import android.app.Application
import com.plantpilot.data.HardwareConnection
import com.plantpilot.data.HardwareRepository
import com.plantpilot.data.PlantPilotRepository

/**
 * Owns the app-wide singletons so the ViewModels, the background sync service
 * and the UI share one WebSocket connection and one REST repository instead of
 * each holding global state. ViewModels and services access these via
 * `(application as PlantPilotApp)`.
 */
class PlantPilotApp : Application() {
    val hardwareConnection: HardwareConnection by lazy { HardwareRepository() }
    val repository: PlantPilotRepository by lazy { PlantPilotRepository() }
}