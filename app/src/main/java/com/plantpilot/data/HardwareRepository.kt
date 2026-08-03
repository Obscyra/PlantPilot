@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.serialization.Serializable

sealed class ConnectionState {
    object Connected : ConnectionState()
    object Connecting : ConnectionState()
    object Reconnecting : ConnectionState()
    object Disconnected : ConnectionState()
    object Failed : ConnectionState()
}

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

class HardwareRepository : HardwareConnection {
    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 5000L
        // Number of consecutive unanswered heartbeat probes before we assume the
        // ESP32 is gone. Tolerates a slow-but-alive link instead of force-closing.
        private const val MAX_MISSED_PROBES = 3
        // Grace period before flipping to "Disconnected" in the UI. Brief WebSocket
        // drops (app backgrounding, network hiccup) are invisible if the link
        // recovers within this window.
        private const val DISCONNECT_DEBOUNCE_MS = 10000L
        // Watering briefly glitches the link (relay switching shares the power
        // rail with the ESP32), so tolerate a longer silence while a watering is
        // in flight rather than flashing "Reconnecting" mid-watering.
        private const val WATERING_DEBOUNCE_MS = 30000L

        // Exponential backoff: base 2s, multiplier 1.7x, cap 30s, ±20% jitter.
        private const val BACKOFF_BASE_MS = 2000L
        private const val BACKOFF_MULTIPLIER = 1.7
        private const val BACKOFF_MAX_MS = 30000L
        private const val BACKOFF_JITTER = 0.2
        // After this many consecutive failures, stop auto-retrying.
        private const val MAX_RETRY_ATTEMPTS = 8
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        // App-side WebSocket keepalive: sends a control ping and expects a pong,
        // so NAT/firewall timeouts can't silently drop an idle-but-alive socket.
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var webSocket: WebSocket? = null
    
    // Command queue to prevent flooding the ESP32
    private val commandChannel = Channel<String>(Channel.BUFFERED)

    init {
        scope.launch {
            for (command in commandChannel) {
                // Physical gap between commands (60ms) to prevent buffer overflow on ESP32
                // and give the network stack room to breathe.
                sendPhysicalCommand(command)
                delay(60)
            }
        }
    }

    private var currentUrl: String? = null
    private var userInitiatedDisconnect = false
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var disconnectDebounceJob: Job? = null
    private var lastMessageTime = 0L
    private var lastHeartbeatSentTime = 0L
    private var missedProbes = 0
    private var consecutiveFailures = 0
    private var wateringInProgress = false

    // Tracks when we last sent a pump command to prevent server "ok" messages
    // (which contain the state of all pumps) from clobbering a newer local
    // optimistic toggle before it reaches the ESP32.
    private val lastPumpCommandTime = mutableMapOf<Int, Long>()

    // Telemetry cadence the ESP32 should stream at while we're connected.
    // Applied immediately when the socket is up, otherwise queued and sent on
    // the next open.
    private var requestedCadenceSec = -1

    private val _logs = MutableSharedFlow<String>(replay = 50, extraBufferCapacity = 50)
    override val logs: SharedFlow<String> = _logs

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _telemetry = MutableStateFlow<DeviceStatusResponse?>(null)
    override val telemetry: StateFlow<DeviceStatusResponse?> = _telemetry

    private val _hardwareEvents = MutableSharedFlow<HardwareEvent>(extraBufferCapacity = 10)
    override val hardwareEvents: SharedFlow<HardwareEvent> = _hardwareEvents

    private val json = Json { ignoreUnknownKeys = true }

    private val _pumpStates = MutableStateFlow(mapOf(
        1 to false,
        2 to false,
        3 to false,
        4 to false
    ))
    override val pumpStates: StateFlow<Map<Int, Boolean>> = _pumpStates

    /** True when a live WebSocket is open (Connected or mid-reconnect). */
    override fun isConnected(): Boolean {
        val s = _connectionState.value
        return s == ConnectionState.Connected || s == ConnectionState.Reconnecting ||
            s == ConnectionState.Connecting
    }

    override fun connect(url: String) {
        if (url == currentUrl && _connectionState.value == ConnectionState.Connected) return
        currentUrl = url
        userInitiatedDisconnect = false
        consecutiveFailures = 0
        cancelReconnect()
        disconnectDebounceJob?.cancel()
        disconnectDebounceJob = null
        stopHeartbeat()
        webSocket?.close(1000, "Opening new connection")
        webSocket = null
        clearLogs()
        _connectionState.value = ConnectionState.Connecting
        openSocket()
    }

    private fun openSocket() {
        val url = currentUrl ?: return
        if (_connectionState.value != ConnectionState.Connecting) {
            _connectionState.value = ConnectionState.Reconnecting
        }
        val request = Request.Builder().url(url).build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            // Ignore callbacks from stale sockets (e.g. after reconnect), so the
            // old socket's close events don't wipe state set by the new connection.
            private fun isCurrent(ws: WebSocket): Boolean = ws === webSocket

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(webSocket)) return
                disconnectDebounceJob?.cancel()
                disconnectDebounceJob = null
                consecutiveFailures = 0
                _connectionState.value = ConnectionState.Connected
                lastMessageTime = System.currentTimeMillis()
                lastHeartbeatSentTime = 0L
                missedProbes = 0
                addLog("System: Connected to ESP32")
                // Apply the app's desired telemetry cadence once the socket is up.
                if (requestedCadenceSec > 0) {
                    sendCommand("SYNC_MODE $requestedCadenceSec")
                }
                sendCommand("STATUS")
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent(webSocket)) return
                // If we receive a message, we must be connected
                lastMessageTime = System.currentTimeMillis()
                lastHeartbeatSentTime = 0L
                missedProbes = 0
                if (_connectionState.value != ConnectionState.Connected) _connectionState.value = ConnectionState.Connected

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
        stopHeartbeat()
        addLog(message)
        if (disconnectDebounceJob?.isActive == true) return
        disconnectDebounceJob = scope.launch {
            // A watering in flight gets extra grace before we declare a lost
            // link, so relay-induced glitches don't flash "Reconnecting".
            val debounceMs = if (wateringInProgress) WATERING_DEBOUNCE_MS else DISCONNECT_DEBOUNCE_MS
            delay(debounceMs)
            _telemetry.value = null
            resetPumpStates()
            if (userInitiatedDisconnect) {
                _connectionState.value = ConnectionState.Disconnected
            } else {
                consecutiveFailures++
                // Never permanently give up: the app must reconnect on its own
                // while backgrounded (the foreground service keeps this process
                // alive). After the fast backoff is exhausted, keep retrying at
                // the capped interval so a transient network/Doze drop recovers
                // without the user reopening the app. Only an explicit
                // disconnect() (user or full close) stops retrying.
                if (consecutiveFailures >= MAX_RETRY_ATTEMPTS) {
                    _connectionState.value = ConnectionState.Failed
                    addLog("System: Connection lost after $MAX_RETRY_ATTEMPTS attempts. Still retrying in the background...")
                } else {
                    _connectionState.value = ConnectionState.Reconnecting
                }
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        cancelReconnect()
        val delayMs = computeBackoffDelay()
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

    private fun computeBackoffDelay(): Long {
        val exponential = BACKOFF_BASE_MS * BACKOFF_MULTIPLIER.pow(consecutiveFailures.toDouble())
        val capped = exponential.coerceAtMost(BACKOFF_MAX_MS.toDouble())
        val jitterRange = capped * BACKOFF_JITTER
        val jitter = Random.nextDouble(-jitterRange, jitterRange)
        return (capped + jitter).toLong().coerceAtLeast(0)
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (_connectionState.value != ConnectionState.Connected && _connectionState.value != ConnectionState.Reconnecting) continue
                val now = System.currentTimeMillis()
                val idle = now - lastMessageTime
                if (idle >= HEARTBEAT_INTERVAL_MS) {
                    val probeOutstanding = lastHeartbeatSentTime != 0L && lastMessageTime < lastHeartbeatSentTime
                    if (probeOutstanding && missedProbes >= MAX_MISSED_PROBES) {
                        // The ESP32 hasn't replied for several probes — assume gone.
                        missedProbes = 0
                        webSocket?.close(1000, "ESP32 not responding")
                    } else {
                        if (probeOutstanding) missedProbes++
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

    override fun disconnect() {
        userInitiatedDisconnect = true
        consecutiveFailures = 0
        cancelReconnect()
        disconnectDebounceJob?.cancel()
        disconnectDebounceJob = null
        stopHeartbeat()
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
        _telemetry.value = null
        resetPumpStates()
        addLog("System: Disconnected")
    }

    override fun sendCommand(command: String) {
        // Optimistic UI: Update local pump states immediately when a pump command
        // is sent, so the switches feel responsive.
        when {
            command == "PUMP_ALL_ON" -> {
                val now = System.currentTimeMillis()
                (1..4).forEach { lastPumpCommandTime[it] = now }
                updateAllPumpStates(true)
            }
            command == "PUMP_ALL_OFF" -> {
                val now = System.currentTimeMillis()
                (1..4).forEach { lastPumpCommandTime[it] = now }
                updateAllPumpStates(false)
            }
            command.startsWith("PUMP") -> {
                val parts = command.split("_")
                if (parts.size == 2) {
                    val pumpLetter = parts[0].removePrefix("PUMP")
                    val pumpNum = when (pumpLetter) {
                        "1", "A" -> 1; "2", "B" -> 2; "3", "C" -> 3; "4", "D" -> 4
                        else -> null
                    }
                    val state = parts[1] == "ON"
                    if (pumpNum != null) {
                        lastPumpCommandTime[pumpNum] = System.currentTimeMillis()
                        updatePumpState(pumpNum, state)
                    }
                }
            }
        }
        
        // Enqueue for staggered transmission
        scope.launch { commandChannel.send(command) }
    }

    private fun sendPhysicalCommand(command: String) {
        addLog("App: Sent $command")
        try {
            webSocket?.send(command)
        } catch (e: Exception) {
            addLog("Error: ${e.message}")
        }
    }

    fun updatePumpState(pumpId: Int, isOn: Boolean) {
        if (_pumpStates.value[pumpId] == isOn) return
        val newStates = _pumpStates.value.toMutableMap()
        newStates[pumpId] = isOn
        _pumpStates.value = newStates
    }

    fun updateAllPumpStates(isOn: Boolean) {
        val allMatches = _pumpStates.value.values.all { it == isOn }
        if (allMatches) return
        _pumpStates.value = mapOf(1 to isOn, 2 to isOn, 3 to isOn, 4 to isOn)
    }

    /**
     * Tells the ESP32 how fast to stream telemetry (seconds between pushes).
     * Foreground = 1s, background = 3s. Queued if the socket isn't open yet.
     */
    override fun setStreamCadence(seconds: Int) {
        requestedCadenceSec = seconds
        val cmd = "SYNC_MODE $seconds"
        if (webSocket != null && _connectionState.value == ConnectionState.Connected) {
            sendCommand(cmd)
        }
    }

    override fun setWateringInProgress(active: Boolean) {
        wateringInProgress = active
    }

    private fun addLog(message: String) {
        _logs.tryEmit(message)
    }

    private fun resetPumpStates() {
        lastPumpCommandTime.clear()
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
                    // Detect an ESP32 reboot: a freshly-rebooted device reports a
                    // small uptime after we previously saw a much larger one. This
                    // distinguishes a real device reset from a mere socket drop.
                    val prevUptime = _telemetry.value?.uptime_sec ?: 0L
                    _telemetry.value = response
                    val uptime = response.uptime_sec ?: 0L
                    if (prevUptime > 0 && uptime < prevUptime - 60L) {
                        addLog("System: ESP32 rebooted (uptime reset ${prevUptime}s -> ${uptime}s)")
                    }
                    // Don't sync pump states from telemetry — it overwrites local
                    // toggles before the ESP32 confirms. States are synced via
                    // OK responses and STATUS replies instead.
                } else if (type == "ok") {
                    // Command acknowledgment with actual pump states from firmware.
                    // Only update the local state if we haven't sent a command
                    // recently, otherwise the optimistic toggle wins until the
                    // server "catches up".
                    element.jsonObject["pumps"]?.jsonArray?.let { arr ->
                        val newStates = _pumpStates.value.toMutableMap()
                        val now = System.currentTimeMillis()
                        arr.forEachIndexed { index, elem ->
                            val pumpId = index + 1
                            if (pumpId in 1..4) {
                                val lastSent = lastPumpCommandTime[pumpId] ?: 0L
                                if (now - lastSent > 1200L) { // 1.2s grace period
                                    newStates[pumpId] = elem.jsonPrimitive.boolean
                                }
                            }
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
            val now = System.currentTimeMillis()
            lines.forEach { line ->
                val parts = line.split(": ")
                if (parts.size == 2) {
                    val pumpNum = parts[0].removePrefix("Pump").toIntOrNull()
                    val state = parts[1] == "ON"
                    if (pumpNum != null && pumpNum in 1..4) {
                        val lastSent = lastPumpCommandTime[pumpNum] ?: 0L
                        if (now - lastSent > 1200L) {
                            newStates[pumpNum] = state
                        }
                    }
                }
            }
            _pumpStates.value = newStates
        } else if (text.startsWith("OK: PUMP")) {
            val now = System.currentTimeMillis()
            if (text == "OK: PUMP_ALL_ON") {
                (1..4).forEach { lastPumpCommandTime[it] = 0L } // Clear so it applies
                _pumpStates.value = mapOf(1 to true, 2 to true, 3 to true, 4 to true)
            } else if (text == "OK: PUMP_ALL_OFF") {
                (1..4).forEach { lastPumpCommandTime[it] = 0L }
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
                        val lastSent = lastPumpCommandTime[pumpNum] ?: 0L
                        if (now - lastSent > 1200L) {
                            val newStates = _pumpStates.value.toMutableMap()
                            newStates[pumpNum] = state
                            _pumpStates.value = newStates
                        }
                    }
                }
            }
        }
    }
}
