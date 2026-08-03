package com.plantpilot.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.plantpilot.PlantPilotApp
import com.plantpilot.data.ConnectionState
import com.plantpilot.data.ConnectionStateHelper
import com.plantpilot.data.SettingsManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PumpTestViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as PlantPilotApp).hardwareConnection
    private val settingsManager = SettingsManager(application)

    val connectionState = repository.connectionState
    val canSendCommands: Boolean
        get() = ConnectionStateHelper.canSendCommands(connectionState.value)
    val canDisplayLastKnownData: Boolean
        get() = ConnectionStateHelper.canDisplayLastKnownData(connectionState.value)

    // Delayed-reveal variant for display-layer consumers only (same semantics as
    // PlantPilotViewModel.displayConnectionState — see ConnectionStateHelper).
    val displayConnectionState: StateFlow<ConnectionState> =
        ConnectionStateHelper.debouncedConnectionState(repository.connectionState, viewModelScope)

    val pumpStates = repository.pumpStates
    val telemetry = repository.telemetry

    // Currently unused from UI: all canSendCommands-guarded calls in this ViewModel
    // (togglePump, turnAllPumps, refreshStatus) are only reachable via disabled buttons,
    // so the snackbar never fires. Kept for forward-compatibility if a non-button code
    // path (e.g. LaunchedEffect, onResume) is added later.
    private val _commandBlockedEvents = Channel<String>(Channel.BUFFERED)
    val commandBlockedEvents = _commandBlockedEvents.receiveAsFlow()
    
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _initialIp = MutableStateFlow("plantpilot.local")
    val initialIp: StateFlow<String> = _initialIp.asStateFlow()

    // Rate-limit local toggles to prevent double-taps/jitter from flooding the repo
    private val lastToggleTime = mutableMapOf<Int, Long>()
    private var lastAllToggleTime = 0L

    init {
        viewModelScope.launch {
            repository.logs.collect { log ->
                _logs.value = listOf(log) + _logs.value.take(49)
            }
        }

        viewModelScope.launch {
            var wasConnecting = false
            repository.connectionState.collect { state ->
                val connecting = state == ConnectionState.Connecting
                if (connecting && !wasConnecting) {
                    _logs.value = emptyList()
                }
                wasConnecting = connecting
            }
        }

        viewModelScope.launch {
            settingsManager.deviceStateFlow.collect { state ->
                if (state.ip.isNotBlank()) {
                    _initialIp.value = state.ip
                }
            }
        }
    }

    fun connect(ip: String) {
        val host = ip.trim()
            .removePrefix("ws://")
            .removePrefix("wss://")
            .removePrefix("http://")
            .removePrefix("https://")
            .removeSuffix("/ws")
            .removeSuffix("/")
        repository.connect("ws://$host/ws")
    }

    fun disconnect() {
        repository.disconnect()
    }

    fun togglePump(pumpId: Int, turnOn: Boolean) {
        if (!canSendCommands) {
            _commandBlockedEvents.trySend("Can't control pumps — device offline")
            return
        }
        val now = System.currentTimeMillis()
        val lastTime = lastToggleTime[pumpId] ?: 0L
        if (now - lastTime < 120) return // Ignore rapid accidental double-taps
        lastToggleTime[pumpId] = now

        val letter = when (pumpId) { 1 -> "A"; 2 -> "B"; 3 -> "C"; 4 -> "D"; else -> "$pumpId" }
        val cmd = "PUMP${letter}_${if (turnOn) "ON" else "OFF"}"
        repository.sendCommand(cmd)
    }

    fun turnAllPumps(turnOn: Boolean) {
        if (!canSendCommands) {
            _commandBlockedEvents.trySend("Can't control pumps — device offline")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastAllToggleTime < 400) return // Higher debounce for "All" commands
        lastAllToggleTime = now

        val cmd = "PUMP_ALL_${if (turnOn) "ON" else "OFF"}"
        repository.sendCommand(cmd)
    }

    fun refreshStatus() {
        if (!canSendCommands) {
            _commandBlockedEvents.trySend("Can't refresh — device offline")
            return
        }
        repository.sendCommand("STATUS")
    }
}
