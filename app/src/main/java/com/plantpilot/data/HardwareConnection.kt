package com.plantpilot.data

import com.plantpilot.network.DeviceStatusResponse
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for the live ESP32 WebSocket connection. Implemented
 * by [HardwareRepository] and owned by [com.plantpilot.PlantPilotApp] so the
 * ViewModels, the sync service and the UI share one connection instead of each
 * owning global state.
 */
interface HardwareConnection {
    val connectionState: StateFlow<ConnectionState>
    val telemetry: StateFlow<DeviceStatusResponse?>
    val pumpStates: StateFlow<Map<Int, Boolean>>
    val hardwareEvents: SharedFlow<HardwareEvent>
    val logs: SharedFlow<String>

    fun connect(url: String)
    fun disconnect()
    fun sendCommand(command: String)
    fun setStreamCadence(seconds: Int)
    fun isConnected(): Boolean

    /** Tells the ESP32 to stop pushing telemetry frames (app backgrounded). */
    fun pauseTelemetry()

    /** Tells the ESP32 to resume pushing telemetry frames (app foregrounded). */
    fun resumeTelemetry()

    /** Marks that a watering is in flight so a brief link hiccup during the
     *  relay cycle doesn't flip the UI to "Reconnecting". */
    fun setWateringInProgress(active: Boolean)

    /** Emits a user-action log entry (e.g. "Settings: Flow rate → 11 ml/s"). */
    fun logUserAction(message: String)
}
