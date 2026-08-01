package com.plantpilot.data

import com.plantpilot.network.DeviceStatusResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

@Serializable
sealed class HardwareEvent {
    @Serializable
    data class WateringFinished(
        val motor: Int,
        val amount_ml: Int,
        val trigger: String,
        val epoch: Long,
        val soil_after: Int? = null
    ) : HardwareEvent()
}

object HardwareRepository {
    private const val INITIAL_RETRY_DELAY_MS = 2000L
    private const val MAX_RETRY_DELAY_MS = 30000L
    private const val HEARTBEAT_INTERVAL_MS = 5000L

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var webSocket: WebSocket? = null
    private var currentUrl: String? = null
    private var userInitiatedDisconnect = false
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var retryDelayMs = INITIAL_RETRY_DELAY_MS
    private var lastMessageTime = 0L
    private var lastHeartbeatSentTime = 0L

    private val _logs = MutableSharedFlow<String>(replay = 50, extraBufferCapacity = 50)
    val logs: SharedFlow<String> = _logs

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting

    private val _telemetry = MutableStateFlow<DeviceStatusResponse?>(null)
    val telemetry: StateFlow<DeviceStatusResponse?> = _telemetry

    private val _hardwareEvents = MutableSharedFlow<HardwareEvent>(extraBufferCapacity = 10)
    val hardwareEvents: SharedFlow<HardwareEvent> = _hardwareEvents

    private val json = Json { ignoreUnknownKeys = true }

    private val _pumpStates = MutableStateFlow(mapOf(
        1 to false,
        2 to false,
        3 to false,
        4 to false
    ))
    val pumpStates: StateFlow<Map<Int, Boolean>> = _pumpStates

    fun connect(url: String) {
        if (url == currentUrl && _isConnected.value) return
        currentUrl = url
        userInitiatedDisconnect = false
        cancelReconnect()
        stopHeartbeat()
        webSocket?.close(1000, "Opening new connection")
        webSocket = null
        clearLogs()
        _isConnecting.value = true
        openSocket()
    }

    private fun openSocket() {
        val url = currentUrl ?: return
        val request = Request.Builder().url(url).build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            // Ignore callbacks from stale sockets (e.g. after reconnect), so the
            // old socket's close events don't wipe state set by the new connection.
            private fun isCurrent(ws: WebSocket): Boolean = ws === webSocket

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(webSocket)) return
                _isConnecting.value = false
                _isConnected.value = true
                lastMessageTime = System.currentTimeMillis()
                lastHeartbeatSentTime = 0L
                retryDelayMs = INITIAL_RETRY_DELAY_MS
                addLog("System: Connected to ESP32")
                sendCommand("STATUS")
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent(webSocket)) return
                // If we receive a message, we must be connected
                lastMessageTime = System.currentTimeMillis()
                lastHeartbeatSentTime = 0L
                if (!_isConnected.value) _isConnected.value = true

                addLog("ESP32: $text")
                parseResponse(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent(webSocket)) return
                webSocket.close(1000, null)
                onConnectionLost("System: Connection Closing ($reason)")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent(webSocket)) return
                onConnectionLost("System: Connection Closed")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrent(webSocket)) return
                onConnectionLost("Error: ${t.message}")
            }
        })
        webSocket = socket
    }

    private fun onConnectionLost(message: String) {
        _isConnected.value = false
        _isConnecting.value = false
        _telemetry.value = null
        resetPumpStates()
        stopHeartbeat()
        addLog(message)
        if (!userInitiatedDisconnect) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        cancelReconnect()
        val delayMs = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
        _isConnecting.value = true
        addLog("System: Retrying connection in ${delayMs / 1000}s...")
        reconnectJob = scope.launch {
            delay(delayMs)
            openSocket()
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!_isConnected.value) continue
                val now = System.currentTimeMillis()
                val idle = now - lastMessageTime
                if (idle >= HEARTBEAT_INTERVAL_MS) {
                    if (lastHeartbeatSentTime != 0L && lastMessageTime < lastHeartbeatSentTime) {
                        // We knocked before and got no reply — assume the ESP32 is gone
                        webSocket?.close(1000, "ESP32 not responding")
                    } else {
                        sendCommand("STATUS")
                        lastHeartbeatSentTime = now
                    }
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun disconnect() {
        userInitiatedDisconnect = true
        cancelReconnect()
        stopHeartbeat()
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _isConnected.value = false
        _isConnecting.value = false
        _telemetry.value = null
        resetPumpStates()
        addLog("System: Disconnected")
    }

    fun sendCommand(command: String) {
        addLog("App: Sent $command")
        try {
            webSocket?.send(command)
        } catch (e: Exception) {
            // Socket may already be closed/aborted (e.g. during heartbeat probe)
            addLog("Error: ${e.message}")
        }
    }

    private fun addLog(message: String) {
        _logs.tryEmit(message)
    }

    private fun resetPumpStates() {
        _pumpStates.value = mapOf(1 to false, 2 to false, 3 to false, 4 to false)
    }

    private fun clearLogs() {
        _logs.resetReplayCache()
    }

    private fun parseResponse(text: String) {
        if (text.startsWith("{")) {
            try {
                val element = json.parseToJsonElement(text)
                val type = element.jsonObject["type"]?.jsonPrimitive?.content
                
                if (type == "telemetry") {
                    val response = json.decodeFromJsonElement<DeviceStatusResponse>(element)
                    _telemetry.value = response
                    // Don't sync pump states from telemetry — it overwrites local
                    // toggles before the ESP32 confirms. States are synced via
                    // OK responses and STATUS replies instead.
                } else if (type == "ok") {
                    // Command acknowledgment with actual pump states from firmware.
                    // Array is 0-indexed (index 0 = Pump 1), map keys are 1-indexed.
                    element.jsonObject["pumps"]?.jsonArray?.let { arr ->
                        val newStates = _pumpStates.value.toMutableMap()
                        arr.forEachIndexed { index, elem ->
                            val pumpId = index + 1
                            if (pumpId in 1..4) newStates[pumpId] = elem.jsonPrimitive.boolean
                        }
                        _pumpStates.value = newStates
                    }
                } else if (type == "watering_finished") {
                    val event = json.decodeFromJsonElement<HardwareEvent.WateringFinished>(element)
                    _hardwareEvents.tryEmit(event)
                    
                    // Update pump state to OFF when finished
                    val newStates = _pumpStates.value.toMutableMap()
                    newStates[event.motor] = false
                    _pumpStates.value = newStates
                }
            } catch (e: Exception) {
                addLog("Error parsing JSON: ${e.message}")
            }
        } else if (text.startsWith("Pump")) {
            val lines = text.split("\n")
            val newStates = _pumpStates.value.toMutableMap()
            lines.forEach { line ->
                val parts = line.split(": ")
                if (parts.size == 2) {
                    val pumpNum = parts[0].removePrefix("Pump").toIntOrNull()
                    val state = parts[1] == "ON"
                    if (pumpNum != null) {
                        newStates[pumpNum] = state
                    }
                }
            }
            _pumpStates.value = newStates
        } else if (text.startsWith("OK: PUMP")) {
            if (text == "OK: PUMP_ALL_ON") {
                _pumpStates.value = mapOf(1 to true, 2 to true, 3 to true, 4 to true)
            } else if (text == "OK: PUMP_ALL_OFF") {
                _pumpStates.value = mapOf(1 to false, 2 to false, 3 to false, 4 to false)
            } else {
                val parts = text.split("_")
                if (parts.size == 2) {
                    val pumpLetter = parts[0].removePrefix("OK: PUMP")
                    val pumpNum = when (pumpLetter) {
                        "1", "A" -> 1; "2", "B" -> 2; "3", "C" -> 3; "4", "D" -> 4
                        else -> null
                    }
                    val state = parts[1] == "ON"
                    if (pumpNum != null) {
                        val newStates = _pumpStates.value.toMutableMap()
                        newStates[pumpNum] = state
                        _pumpStates.value = newStates
                    }
                }
            }
        }
    }
}
