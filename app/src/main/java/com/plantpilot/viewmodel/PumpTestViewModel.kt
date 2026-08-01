package com.plantpilot.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.plantpilot.data.HardwareRepository
import com.plantpilot.data.SettingsManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PumpTestViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = HardwareRepository
    private val settingsManager = SettingsManager(application)
    
    val isConnected = repository.isConnected
    val isConnecting = repository.isConnecting
    val pumpStates = repository.pumpStates
    val telemetry = repository.telemetry
    
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _initialIp = MutableStateFlow("plantpilot.local")
    val initialIp: StateFlow<String> = _initialIp.asStateFlow()

    init {
        viewModelScope.launch {
            repository.logs.collect { log ->
                _logs.value = listOf(log) + _logs.value.take(49)
            }
        }

        viewModelScope.launch {
            var wasConnecting = false
            repository.isConnecting.collect { connecting ->
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
        val letter = when (pumpId) { 1 -> "A"; 2 -> "B"; 3 -> "C"; 4 -> "D"; else -> "$pumpId" }
        val cmd = "PUMP${letter}_${if (turnOn) "ON" else "OFF"}"
        repository.sendCommand(cmd)
    }

    fun turnAllPumps(turnOn: Boolean) {
        val cmd = "PUMP_ALL_${if (turnOn) "ON" else "OFF"}"
        repository.sendCommand(cmd)
    }

    fun refreshStatus() {
        repository.sendCommand("STATUS")
    }
}
