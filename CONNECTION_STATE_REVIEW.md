# ConnectionState Refactor — Code Review (v2)

## 1. Full sealed class definition

```kotlin
sealed class ConnectionState {
    object Connected : ConnectionState()
    object Connecting : ConnectionState()
    object Reconnecting : ConnectionState()
    object Disconnected : ConnectionState()
    object Failed : ConnectionState()
}
```

Defined at `app/src/main/java/com/plantpilot/data/HardwareRepository.kt:27-33`.

## 2. Backoff calculation code

```kotlin
// Constants
private const val BACKOFF_BASE_MS = 2000L
private const val BACKOFF_MULTIPLIER = 1.7
private const val BACKOFF_MAX_MS = 30000L
private const val BACKOFF_JITTER = 0.2
private const val MAX_RETRY_ATTEMPTS = 8

// Counter
private var consecutiveFailures = 0

// Calculation
private fun computeBackoffDelay(): Long {
    val exponential = BACKOFF_BASE_MS * BACKOFF_MULTIPLIER.pow(consecutiveFailures.toFloat())
    val capped = exponential.coerceAtMost(BACKOFF_MAX_MS.toDouble())
    val jitterRange = capped * BACKOFF_JITTER
    val jitter = Random.nextDouble(-jitterRange, jitterRange)
    return (capped + jitter).toLong().coerceAtLeast(0)
}
```

## 3. Failed state — full transition logic

### What sets Failed

```kotlin
private fun onConnectionLost(message: String) {
    stopHeartbeat()
    addLog(message)
    if (disconnectPending) return
    disconnectPending = true
    disconnectDebounceJob = scope.launch {
        delay(DISCONNECT_DEBOUNCE_MS)
        _telemetry.value = null
        resetPumpStates()
        if (userInitiatedDisconnect) {
            _connectionState.value = ConnectionState.Disconnected
        } else {
            consecutiveFailures++
            if (consecutiveFailures >= MAX_RETRY_ATTEMPTS) {
                _connectionState.value = ConnectionState.Failed
                addLog("System: Connection failed after $MAX_RETRY_ATTEMPTS attempts. Tap to retry.")
            } else {
                _connectionState.value = ConnectionState.Reconnecting
                scheduleReconnect()
            }
        }
    }
}
```

### What clears Failed

Only `connect()` clears the failure counter and transitions out of Failed:

```kotlin
fun connect(url: String) {
    if (url == currentUrl && _connectionState.value == ConnectionState.Connected) return
    currentUrl = url
    userInitiatedDisconnect = false
    consecutiveFailures = 0          // <-- reset
    cancelReconnect()
    disconnectDebounceJob?.cancel()
    disconnectDebounceJob = null
    disconnectPending = false
    stopHeartbeat()
    webSocket?.close(1000, "Opening new connection")
    webSocket = null
    clearLogs()
    _connectionState.value = ConnectionState.Connecting
    openSocket()
}
```

`disconnect()` also resets the counter (for clean slate on next manual connect):

```kotlin
fun disconnect() {
    userInitiatedDisconnect = true
    consecutiveFailures = 0
    cancelReconnect()
    ...
    _connectionState.value = ConnectionState.Disconnected
    ...
}
```

### Confirm: nothing else resets the failure counter

- `onOpen()` resets `consecutiveFailures = 0` on success — correct, this is the "we reconnected" path.
- No other code path touches `consecutiveFailures`.
- The UI "Retry" button calls `connect()` via `DeviceConnectionDialog.onConnect`, which resets the counter.

## 4. HardwareRepository.kt — full state-transition logic

### `connect()`

```kotlin
fun connect(url: String) {
    if (url == currentUrl && _connectionState.value == ConnectionState.Connected) return
    currentUrl = url
    userInitiatedDisconnect = false
    consecutiveFailures = 0
    cancelReconnect()
    disconnectDebounceJob?.cancel()
    disconnectDebounceJob = null
    disconnectPending = false
    stopHeartbeat()
    webSocket?.close(1000, "Opening new connection")
    webSocket = null
    clearLogs()
    _connectionState.value = ConnectionState.Connecting
    openSocket()
}
```

### `openSocket()`

```kotlin
private fun openSocket() {
    val url = currentUrl ?: return
    if (_connectionState.value != ConnectionState.Connecting) {
        _connectionState.value = ConnectionState.Reconnecting
    }
    val request = Request.Builder().url(url).build()
    val socket = client.newWebSocket(request, object : WebSocketListener() {
        private fun isCurrent(ws: WebSocket): Boolean = ws === webSocket

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(webSocket)) return
            disconnectDebounceJob?.cancel()
            disconnectDebounceJob = null
            disconnectPending = false
            consecutiveFailures = 0
            _connectionState.value = ConnectionState.Connected
            lastMessageTime = System.currentTimeMillis()
            lastHeartbeatSentTime = 0L
            missedProbes = 0
            addLog("System: Connected to ESP32")
            if (requestedCadenceSec > 0) {
                sendCommand("SYNC_MODE $requestedCadenceSec")
            }
            sendCommand("STATUS")
            startHeartbeat()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(webSocket)) return
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
```

### `onConnectionLost()` + debounce

```kotlin
private fun onConnectionLost(message: String) {
    stopHeartbeat()
    addLog(message)
    if (disconnectPending) return
    disconnectPending = true
    disconnectDebounceJob = scope.launch {
        delay(DISCONNECT_DEBOUNCE_MS)
        _telemetry.value = null
        resetPumpStates()
        if (userInitiatedDisconnect) {
            _connectionState.value = ConnectionState.Disconnected
        } else {
            consecutiveFailures++
            if (consecutiveFailures >= MAX_RETRY_ATTEMPTS) {
                _connectionState.value = ConnectionState.Failed
                addLog("System: Connection failed after $MAX_RETRY_ATTEMPTS attempts. Tap to retry.")
            } else {
                _connectionState.value = ConnectionState.Reconnecting
                scheduleReconnect()
            }
        }
    }
}
```

### `scheduleReconnect()`

```kotlin
private fun scheduleReconnect() {
    cancelReconnect()
    val delayMs = computeBackoffDelay()
    addLog("System: Retrying connection in ${delayMs / 1000}s...")
    reconnectJob = scope.launch {
        delay(delayMs)
        openSocket()
    }
}
```

### `disconnect()`

```kotlin
fun disconnect() {
    userInitiatedDisconnect = true
    consecutiveFailures = 0
    cancelReconnect()
    disconnectDebounceJob?.cancel()
    disconnectDebounceJob = null
    disconnectPending = false
    stopHeartbeat()
    webSocket?.close(1000, "User disconnect")
    webSocket = null
    _connectionState.value = ConnectionState.Disconnected
    _telemetry.value = null
    resetPumpStates()
    addLog("System: Disconnected")
}
```

## 5. CommonComponents.kt — chip rendering

### Base chip `when` expression (verbatim)

```kotlin
@Composable
fun ConnectionStatusChip(
    connectionState: ConnectionState,
    modifier: Modifier = Modifier,
    isSyncing: Boolean = false,
    isConfigDirty: Boolean = false,
    onClick: () -> Unit
) {
    val label: String
    val icon: ImageVector?
    val containerColor: Color
    val contentColor: Color
    var showSpinner = false
    when (connectionState) {
        is ConnectionState.Connected -> {
            if (isSyncing) {
                label = "Updating..."
                icon = null
                showSpinner = true
                containerColor = MaterialTheme.colorScheme.surfaceVariant
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                label = if (isConfigDirty) "Update" else "Connected"
                icon = if (isConfigDirty) Icons.Default.CloudUpload else Icons.Default.Wifi
                containerColor = MaterialTheme.colorScheme.primaryContainer
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            }
        }
        is ConnectionState.Connecting -> {
            label = "Connecting..."
            icon = null
            showSpinner = true
            containerColor = MaterialTheme.colorScheme.surfaceVariant
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
        is ConnectionState.Reconnecting -> {
            label = "Reconnecting..."
            icon = null
            showSpinner = true
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        }
        is ConnectionState.Failed -> {
            label = "Offline"
            icon = Icons.Default.WifiOff
            containerColor = MaterialTheme.colorScheme.errorContainer
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        }
        is ConnectionState.Disconnected -> {
            label = "Disconnected"
            icon = Icons.Default.WifiOff
            containerColor = MaterialTheme.colorScheme.surfaceVariant
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    // ... Surface + Row rendering
}
```

### Convenience wrapper (verbatim)

```kotlin
@Composable
fun ConnectionStatusChip(
    viewModel: PlantPilotViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.connectionState.collectAsState()
    val isSyncing = viewModel.isSyncing
    val isConfigDirty = viewModel.isConfigDirty
    ConnectionStatusChip(
        connectionState = state,
        isSyncing = isSyncing,
        isConfigDirty = isConfigDirty,
        modifier = modifier,
        onClick = onClick
    )
}
```

### DeviceConnectionDialog (verbatim)

```kotlin
@Composable
fun DeviceConnectionDialog(
    connectionState: ConnectionState,
    deviceIp: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val isConnected = connectionState == ConnectionState.Connected
    val isConnecting = connectionState == ConnectionState.Connecting
    val isReconnecting = connectionState == ConnectionState.Reconnecting
    val isFailed = connectionState == ConnectionState.Failed

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PilotCore Connection", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        isConnecting || isReconnecting -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp), strokeWidth = 2.dp
                        )
                        isConnected -> Icon(
                            Icons.Default.Wifi, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        else -> Icon(
                            Icons.Default.WifiOff, contentDescription = null,
                            tint = if (isFailed) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        when {
                            isConnecting -> "Connecting to PilotCore..."
                            isReconnecting -> "Reconnecting to PilotCore..."
                            isConnected -> "Connected to PilotCore"
                            isFailed -> "Connection failed — device unreachable"
                            else -> "Not connected to PilotCore"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (deviceIp.isNotBlank()) {
                    Text("Device IP: $deviceIp", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            if (isConnecting || isReconnecting) {
                TextButton(onClick = onDismiss, enabled = false) { Text("Connecting...") }
            } else if (isConnected) {
                Button(onClick = { onDisconnect(); onDismiss() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )) {
                    Icon(Icons.Default.WifiOff, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Disconnect")
                }
            } else {
                Button(onClick = { onConnect(); onDismiss() }) {
                    Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isFailed) "Retry" else "Connect")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
```

## 6. ViewModel split — canSendCommands / canDisplayLastKnownData

### PlantPilotViewModel.kt (verbatim)

```kotlin
val connectionState = hardwareRepository.connectionState

/** Strict: only true when WebSocket is confirmed alive. Guards outbound commands. */
val canSendCommands: Boolean
    get() = connectionState.value == ConnectionState.Connected

/** Permissive: true when Connected or Reconnecting. Fine for displaying
 *  last-known data (telemetry, pump states) — the data isn't stale-looking,
 *  just not live. */
val canDisplayLastKnownData: Boolean
    get() {
        val s = connectionState.value
        return s == ConnectionState.Connected || s == ConnectionState.Reconnecting
    }

/** One-shot event channel for snackbar messages when commands are blocked. */
private val _commandBlockedEvents = Channel<String>(Channel.BUFFERED)
val commandBlockedEvents = _commandBlockedEvents.receiveAsFlow()
```

### PumpTestViewModel.kt (verbatim)

```kotlin
val connectionState = repository.connectionState
val canSendCommands: Boolean
    get() = connectionState.value == ConnectionState.Connected
val canDisplayLastKnownData: Boolean
    get() {
        val s = connectionState.value
        return s == ConnectionState.Connected || s == ConnectionState.Reconnecting
    }

private val _commandBlockedEvents = Channel<String>(Channel.BUFFERED)
val commandBlockedEvents = _commandBlockedEvents.receiveAsFlow()
```

## 7. Call-site mapping table

| File | Old pattern | Now uses | Why |
|---|---|---|---|
| **PlantPilotViewModel** | | | |
| Periodic poll (`if (!isConnected)`) | collapsed | `!canDisplayLastKnownData` | Reconnect is already running; only re-check when truly offline |
| `refreshData()` | collapsed | `!canDisplayLastKnownData` | HTTP check + possible reconnect |
| `checkConnection()` | collapsed | `!canDisplayLastKnownData` | HTTP check + possible reconnect |
| `onAppResumed()` | collapsed | `canDisplayLastKnownData` | Sync if device was recently alive |
| `calibrateSensor()` | collapsed | `!canSendCommands` + snackbar | Sends CALIBRATE command |
| `setCalibrationStreaming()` | collapsed | `!canSendCommands` + snackbar | Sends CAL_STREAM_ON/OFF |
| `requestSensorReading()` | collapsed | `!canSendCommands` + snackbar | Sends READ_SENSORS |
| `waterPlant()` | collapsed | `!canSendCommands` + snackbar | HTTP WATER_NOW request |
| `markConfigDirty(autoSync)` | collapsed | `canSendCommands` | May call syncConfigWithDevice |
| `resetDeviceConfig()` | collapsed | `!canSendCommands` + snackbar | Sends RESET_CONFIG |
| `toggleDeviceConnection()` | collapsed | `canDisplayLastKnownData` | Decides disconnect vs refresh |
| **PumpTestViewModel** | | | |
| `togglePump()` | collapsed | `!canSendCommands` + snackbar | Sends PUMPx_ON/OFF |
| `turnAllPumps()` | collapsed | `!canSendCommands` + snackbar | Sends PUMP_ALL_ON/OFF |
| `refreshStatus()` | collapsed | `!canSendCommands` + snackbar | Sends STATUS |
| **PlantDetailScreen** | | | |
| "Water Now" button `enabled` | collapsed | `canSendCommands` | Sends WATER_NOW |
| Water dialog guard | collapsed | `canSendCommands` | Sends WATER_NOW |
| **HomeScreen** | | | |
| `waterEnabled` on plant card | collapsed | `canSendCommands` | Sends WATER_NOW |
| **CalibrationScreen** | | | |
| Save button `enabled` | collapsed | `canSendCommands` | Sends CALIBRATE |
| PulsingDot, "Live Reading" | collapsed | `canDisplayLastKnownData` | Passive display |
| **PumpTestingScreen** | | | |
| All pump toggle `enabled` | collapsed | `canSendCommands` | Sends PUMPx_ON/OFF |
| "Waiting for pump events..." | collapsed | `canDisplayLastKnownData` | Passive display |
| **SerialOutputScreen** | | | |
| Refresh button `enabled` | collapsed | `canSendCommands` | Sends STATUS |
| PulsingDot | collapsed | `canDisplayLastKnownData` | Passive display |
| **SettingsScreen** | | | |
| Reset button `enabled`/`onClick` | collapsed | `canSendCommands` | Sends RESET_CONFIG |
| WiFi SSID, telemetry display | collapsed | `canDisplayLastKnownData` | Passive display |
| "Offline" label, dimmed colors | collapsed | `!canDisplayLastKnownData` | Visual state |

## 8. Snackbar feedback — blocked command code

### ViewModel side (PlantPilotViewModel, representative)

```kotlin
fun calibrateSensor(sensorId: Int, dry: Int, wet: Int, mlPerSec: Int? = null, onResult: ((Boolean) -> Unit)? = null) {
    viewModelScope.launch {
        if (!canSendCommands) {
            _commandBlockedEvents.trySend("Can't calibrate — device offline")
            onResult?.invoke(false)
            return@launch
        }
        // ... actual command
    }
}

fun setCalibrationStreaming(enabled: Boolean) {
    if (!canSendCommands) {
        _commandBlockedEvents.trySend("Can't stream sensor data — device offline")
        return
    }
    // ... actual command
}

fun requestSensorReading() {
    if (!canSendCommands) {
        _commandBlockedEvents.trySend("Can't read sensors — device offline")
        return
    }
    // ... actual command
}

suspend fun waterPlant(plantId: String): Boolean {
    if (!canSendCommands) {
        _commandBlockedEvents.trySend("Can't water — device offline")
        return false
    }
    // ... actual command
}

fun resetDeviceConfig(onComplete: (Boolean) -> Unit) {
    if (!canSendCommands) {
        _commandBlockedEvents.trySend("Can't reset — device offline")
        onComplete(false)
        return
    }
    // ... actual command
}
```

### PumpTestViewModel side

```kotlin
fun togglePump(pumpId: Int, turnOn: Boolean) {
    if (!canSendCommands) {
        _commandBlockedEvents.trySend("Can't control pumps — device offline")
        return
    }
    // ... actual command
}

fun turnAllPumps(turnOn: Boolean) {
    if (!canSendCommands) {
        _commandBlockedEvents.trySend("Can't control pumps — device offline")
        return
    }
    // ... actual command
}

fun refreshStatus() {
    if (!canSendCommands) {
        _commandBlockedEvents.trySend("Can't refresh — device offline")
        return
    }
    repository.sendCommand("STATUS")
}
```

### Screen side — snackbar collection (representative: PlantDetailScreen)

```kotlin
val snackbarHostState = remember { SnackbarHostState() }
val scope = rememberCoroutineScope()

LaunchedEffect(Unit) {
    viewModel.commandBlockedEvents.collect { message ->
        snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
    }
}
```

Same pattern in: SettingsScreen, HomeScreen, CalibrationScreen. PumpTestingScreen and SerialOutputScreen have no snackbar infrastructure (buttons are disabled, snackbar is redundant there).

## 9. Grep verification

```
app/src/main/java/com/plantpilot/MainActivity.kt:119:            if (viewModel.isConfigDirty && viewModel.canSendCommands) {
app/src/main/java/com/plantpilot/model/MockData.kt:75:        isConnected = false,
app/src/main/java/com/plantpilot/model/PlantModels.kt:72:    val isConnected: Boolean,
app/src/main/java/com/plantpilot/ui/components/CommonComponents.kt:145:    val isConnected = connectionState == ConnectionState.Connected
app/src/main/java/com/plantpilot/ui/components/CommonComponents.kt:146:    val isConnecting = connectionState == ConnectionState.Connecting
app/src/main/java/com/plantpilot/ui/components/CommonComponents.kt:162:                        isConnecting || isReconnecting -> CircularProgressIndicator(
app/src/main/java/com/plantpilot/ui/components/CommonComponents.kt:166:                        isConnected -> Icon(
app/src/main/java/com/plantpilot/ui/components/CommonComponents.kt:180:                            isConnecting -> "Connecting to PilotCore..."
app/src/main/java/com/plantpilot/ui/components/CommonComponents.kt:182:                            isConnected -> "Connected to PilotCore"
app/src/main/java/com/plantpilot/ui/components/CommonComponents.kt:199:            if (isConnecting || isReconnecting) {
app/src/main/java/com/plantpilot/ui/components/CommonComponents.kt:203:            } else if (isConnected) {
app/src/main/java/com/plantpilot/ui/screens/CalibrationScreen.kt:59:    val canSendCommands = connectionState == com.plantpilot.data.ConnectionState.Connected
app/src/main/java/com/plantpilot/ui/screens/CalibrationScreen.kt:60:    val canDisplayLastKnownData = connectionState == com.plantpilot.data.ConnectionState.Connected || connectionState == com.plantpilot.data.ConnectionState.Reconnecting
app/src/main/java/com/plantpilot/ui/screens/CalibrationScreen.kt:204:                PulsingDot(isVisible = canDisplayLastKnownData)
app/src/main/java/com/plantpilot/ui/screens/CalibrationScreen.kt:207:                    text = if (canDisplayLastKnownData) "Live Reading" else "Device Offline",
app/src/main/java/com/plantpilot/ui/screens/CalibrationScreen.kt:210:                    color = if (canDisplayLastKnownData) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
app/src/main/java/com/plantpilot/ui/screens/CalibrationScreen.kt:235:                        text = if (canDisplayLastKnownData) "Waiting for telemetry..." else "Device offline",
app/src/main/java/com/plantpilot/ui/screens/CalibrationScreen.kt:321:                        PulsingDot(isVisible = liveReading != null && canDisplayLastKnownData)
app/src/main/java/com/plantpilot/ui/screens/CalibrationScreen.kt:324:                            text = if (canDisplayLastKnownData) "Live Reading" else "Device Offline",
app/src/main/java/com/plantpilot/ui/screens/CalibrationScreen.kt:497:                    enabled = hasUnsavedChanges && canSendCommands
app/src/main/java/com/plantpilot/ui/screens/HomeScreen.kt:33:    val canSendCommands = connectionState == com.plantpilot.data.ConnectionState.Connected
app/src/main/java/com/plantpilot/ui/screens/HomeScreen.kt:34:    val canDisplayLastKnownData = connectionState == com.plantpilot.data.ConnectionState.Connected || connectionState == com.plantpilot.data.ConnectionState.Reconnecting
app/src/main/java/com/plantpilot/ui/screens/HomeScreen.kt:126:                            waterEnabled = canSendCommands,
app/src/main/java/com/plantpilot/ui/screens/PlantDetailScreen.kt:36:    val canSendCommands = connectionState == com.plantpilot.data.ConnectionState.Connected
app/src/main/java/com/plantpilot/ui/screens/PlantDetailScreen.kt:37:    val canDisplayLastKnownData = connectionState == com.plantpilot.data.ConnectionState.Connected || connectionState == com.plantpilot.data.ConnectionState.Reconnecting
app/src/main/java/com/plantpilot/ui/screens/PlantDetailScreen.kt:428:                enabled = canSendCommands
app/src/main/java/com/plantpilot/ui/screens/PlantDetailScreen.kt:470:                if (canSendCommands) {
app/src/main/java/com/plantpilot/ui/screens/PumpTestingScreen.kt:39:    val canSendCommands = connectionState == com.plantpilot.data.ConnectionState.Connected
app/src/main/java/com/plantpilot/ui/screens/PumpTestingScreen.kt:40:    val canDisplayLastKnownData = connectionState == com.plantpilot.data.ConnectionState.Connected || connectionState == com.plantpilot.data.ConnectionState.Reconnecting
app/src/main/java/com/plantpilot/ui/screens/PumpTestingScreen.kt:96:                DataActivityIndicator(isActive = canDisplayLastKnownData || connectionState == com.plantpilot.data.ConnectionState.Connecting, isConnecting = connectionState == com.plantpilot.data.ConnectionState.Connecting)
app/src/main/java/com/plantpilot/ui/screens/PumpTestingScreen.kt:114:                        enabled = canSendCommands,
app/src/main/java/com/plantpilot/ui/screens/PumpTestingScreen.kt:124:                    enabled = canSendCommands,
app/src/main/java/com/plantpilot/ui/screens/PumpTestingScreen.kt:131:                    isOn = pumpStates[1] ?: false, enabled = canSendCommands,
app/src/main/java/com/plantpilot/ui/screens/PumpTestingScreen.kt:136:                    isOn = pumpStates[2] ?: false, enabled = canSendCommands,
app/src/main/java/com/plantpilot/ui/screens/PumpTestingScreen.kt:141:                    isOn = pumpStates[3] ?: false, enabled = canSendCommands,
app/src/main/java/com/plantpilot/ui/screens/PumpTestingScreen.kt:146:                    isOn = pumpStates[4] ?: false, enabled = canSendCommands,
app/src/main/java/com/plantpilot/ui/screens/PumpTestingScreen.kt:184:                        text = if (canDisplayLastKnownData) "Waiting for pump events..." else "Device offline",
app/src/main/java/com/plantpilot/ui/screens/PumpTestingScreen.kt:327:fun DataActivityIndicator(isActive: Boolean, isConnecting: Boolean = false) {
app/src/main/java/com/plantpilot/ui/screens/PumpTestingScreen.kt:361:                if (isConnecting) "Connecting..." else "Data active",
app/src/main/java/com/plantpilot/ui/screens/SerialOutputScreen.kt:32:    val canSendCommands = connectionState == com.plantpilot.data.ConnectionState.Connected
app/src/main/java/com/plantpilot/ui/screens/SerialOutputScreen.kt:33:    val canDisplayLastKnownData = connectionState == com.plantpilot.data.ConnectionState.Connected || connectionState == com.plantpilot.data.ConnectionState.Reconnecting
app/src/main/java/com/plantpilot/ui/screens/SerialOutputScreen.kt:84:                PulsingDot(isVisible = canDisplayLastKnownData)
app/src/main/java/com/plantpilot/ui/screens/SerialOutputScreen.kt:86:                TextButton(onClick = { viewModel.refreshStatus() }, enabled = canSendCommands) {
app/src/main/java/com/plantpilot/ui/screens/SettingsScreen.kt:43:    val canSendCommands = connectionState == com.plantpilot.data.ConnectionState.Connected
app/src/main/java/com/plantpilot/ui/screens/SettingsScreen.kt:44:    val canDisplayLastKnownData = connectionState == com.plantpilot.data.ConnectionState.Connected || connectionState == com.plantpilot.data.ConnectionState.Reconnecting
app/src/main/java/com/plantpilot/ui/screens/SettingsScreen.kt:166:                    val liveSsid = telemetry?.wifi_ssid?.takeIf { canDisplayLastKnownData }
app/src/main/java/com/plantpilot/ui/screens/SettingsScreen.kt:167:                        ?: if (canDisplayLastKnownData) "Connecting..." else ""
app/src/main/java/com/plantpilot/ui/screens/SettingsScreen.kt:175:                            Text(if (canDisplayLastKnownData) "Waiting for telemetry..." else "Device offline")
app/src/main/java/com/plantpilot/ui/screens/SettingsScreen.kt:182:                    if (canDisplayLastKnownData) {
app/src/main/java/com/plantpilot/ui/screens/SettingsScreen.kt:603:                        onClick = { if (canSendCommands) showResetDialog = true },
app/src/main/java/com/plantpilot/ui/screens/SettingsScreen.kt:606:                        enabled = canSendCommands
app/src/main/java/com/plantpilot/ui/screens/SettingsScreen.kt:616:                                    .background(if (canDisplayLastKnownData) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant),
app/src/main/java/com/plantpilot/ui/screens/SettingsScreen.kt:623:                                    tint = if (canDisplayLastKnownData) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
app/src/main/java/com/plantpilot/ui/screens/SettingsScreen.kt:632:                                    color = if (canDisplayLastKnownData) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
app/src/main/java/com/plantpilot/ui/screens/SettingsScreen.kt:641:                            if (!canDisplayLastKnownData) {
app/src/main/java/com/plantpilot/viewmodel/PlantPilotViewModel.kt:57:    val canSendCommands: Boolean
app/src/main/java/com/plantpilot/viewmodel/PlantPilotViewModel.kt:63:    val canDisplayLastKnownData: Boolean
app/src/main/java/com/plantpilot/viewmodel/PlantPilotViewModel.kt:140:                _deviceState.value = _deviceState.value.copy(isConnected = reachable)
app/src/main/java/com/plantpilot/viewmodel/PlantPilotViewModel.kt:235:                if (!canDisplayLastKnownData) {
app/src/main/java/com/plantpilot/viewmodel/PlantPilotViewModel.kt:273:                if (!canDisplayLastKnownData) connectToDevice()
app/src/main/java/com/plantpilot/viewmodel/PlantPilotViewModel.kt:288:            if (ok && !canDisplayLastKnownData) {
app/src/main/java/com/plantpilot/viewmodel/PlantPilotViewModel.kt:301:                if (canDisplayLastKnownData) {
app/src/main/java/com/plantpilot/viewmodel/PlantPilotViewModel.kt:470:            if (!canSendCommands) {
app/src/main/java/com/plantpilot/viewmodel/PlantPilotViewModel.kt:492:        if (!canSendCommands) {
app/src/main/java/com/plantpilot/viewmodel/PlantPilotViewModel.kt:501:        if (!canSendCommands) {
app/src/main/java/com/plantpilot/viewmodel/PlantPilotViewModel.kt:511:        if (!canSendCommands) {
app/src/main/java/com/plantpilot/viewmodel/PlantPilotViewModel.kt:628:        if (autoSync && canSendCommands) {
app/src/main/java/com/plantpilot/viewmodel/PlantPilotViewModel.kt:742:        if (!canSendCommands) {
app/src/main/java/com/plantpilot/viewmodel/PlantPilotViewModel.kt:770:        if (canDisplayLastKnownData) {
app/src/main/java/com/plantpilot/viewmodel/PumpTestViewModel.kt:18:    val canSendCommands: Boolean
app/src/main/java/com/plantpilot/viewmodel/PumpTestViewModel.kt:20:    val canDisplayLastKnownData: Boolean
app/src/main/java/com/plantpilot/viewmodel/PumpTestViewModel.kt:84:        if (!canSendCommands) {
app/src/main/java/com/plantpilot/viewmodel/PumpTestViewModel.kt:99:        if (!canSendCommands) {
app/src/main/java/com/plantpilot/viewmodel/PumpTestViewModel.kt:112:        if (!canSendCommands) {
```

**Interpretation:** Remaining `isConnected`/`isConnecting` references are all legitimate:
- `PlantModels.kt:72` — `DeviceState` data class field definition
- `MockData.kt:75` — `DeviceState` default initialization
- `PlantPilotViewModel.kt:140` — setting `DeviceState.isConnected` field
- `CommonComponents.kt:145,146,162,166,180,182,199,203` — local variables inside `DeviceConnectionDialog` derived from `connectionState`
- `PumpTestingScreen.kt:327,361` — `DataActivityIndicator` parameter and its internal logic

All command guards now use `canSendCommands`. All display guards use `canDisplayLastKnownData`. No collapsed `isConnected = Connected || Reconnecting` pattern remains in any guard or chip rendering.

---

## Follow-up diffs

### 1. DataActivityIndicator — Reconnecting state added

**PumpTestingScreen.kt — component signature (verbatim):**

```kotlin
@Composable
fun DataActivityIndicator(isActive: Boolean, isConnecting: Boolean = false, isReconnecting: Boolean = false) {
```

**PumpTestingScreen.kt — label logic (verbatim):**

```kotlin
            Text(
                when {
                    isConnecting -> "Connecting..."
                    isReconnecting -> "Reconnecting..."
                    else -> "Data active"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
```

**PumpTestingScreen.kt — call site (verbatim):**

```kotlin
DataActivityIndicator(
    isActive = canDisplayLastKnownData || connectionState == com.plantpilot.data.ConnectionState.Connecting,
    isConnecting = connectionState == com.plantpilot.data.ConnectionState.Connecting,
    isReconnecting = connectionState == com.plantpilot.data.ConnectionState.Reconnecting
)
```

### 2. PumpTestViewModel — _commandBlockedEvents comment

**PumpTestViewModel.kt (verbatim):**

```kotlin
    // Currently unused from UI: all canSendCommands-guarded calls in this ViewModel
    // (togglePump, turnAllPumps, refreshStatus) are only reachable via disabled buttons,
    // so the snackbar never fires. Kept for forward-compatibility if a non-button code
    // path (e.g. LaunchedEffect, onResume) is added later.
    private val _commandBlockedEvents = Channel<String>(Channel.BUFFERED)
    val commandBlockedEvents = _commandBlockedEvents.receiveAsFlow()
```

### 3. Chip onClick wired through on all screens

**PumpTestingScreen.kt — function signature (verbatim):**

```kotlin
fun PumpTestingScreen(
    viewModel: PumpTestViewModel = viewModel(),
    onBack: () -> Unit,
    onStatusChipClick: () -> Unit = {}
) {
```

**PumpTestingScreen.kt — chip call site (verbatim):**

```kotlin
ConnectionStatusChip(
    connectionState = connectionState,
    onClick = onStatusChipClick
)
```

**SerialOutputScreen.kt — function signature (verbatim):**

```kotlin
fun SerialOutputScreen(
    viewModel: PumpTestViewModel = viewModel(),
    onBack: () -> Unit,
    onStatusChipClick: () -> Unit = {}
) {
```

**SerialOutputScreen.kt — chip call site (verbatim):**

```kotlin
ConnectionStatusChip(
    connectionState = connectionState,
    onClick = onStatusChipClick
)
```

**MainActivity.kt — navigation wiring (verbatim):**

```kotlin
composable(Screen.SerialOutput.route) {
    val pumpViewModel: PumpTestViewModel = viewModel()
    SerialOutputScreen(
        viewModel = pumpViewModel,
        onBack = { navController.popBackStack() },
        onStatusChipClick = onStatusChipClick
    )
}

composable(Screen.PumpTest.route) {
    val pumpViewModel: PumpTestViewModel = viewModel()
    PumpTestingScreen(
        viewModel = pumpViewModel,
        onBack = { navController.popBackStack() },
        onStatusChipClick = onStatusChipClick
    )
}
```

**Confirm:** `onClick` is always wired through regardless of `connectionState`. Tapping the chip while `Failed` opens `DeviceConnectionDialog` (via `onStatusChipClick` → `showConnectionDialog = true` in `MainActivity`), where the user can hit "Retry" (which calls `connect()`). No screen disables or conditionally skips the chip's `onClick`.

---

## Delayed-reveal Reconnecting state

Short-lived reconnects (which resolve in <500ms once the retry actually fires) caused the "Reconnecting" chip/label to flash on screen as visual jank. The fix adds a separate `displayConnectionState` that delays the Reconnecting emission by 500 ms.

### PlantPilotViewModel.kt (verbatim, new code between canDisplayLastKnownData and telemetry)

```kotlin
    // Delayed-reveal variant of connectionState for display-layer consumers only.
    // Reconnecting is held back for 500 ms so short-lived reconnects (which
    // resolve almost instantly on retry) never flash a "Reconnecting" label.
    // Command guards (canSendCommands / canDisplayLastKnownData) must continue
    // reading the real connectionState directly — never this.
    private val _displayConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val displayConnectionState: StateFlow<ConnectionState> = _displayConnectionState.asStateFlow()
    private var reconnectRevealJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            connectionState.collect { real ->
                if (real == ConnectionState.Reconnecting) {
                    reconnectRevealJob?.cancel()
                    reconnectRevealJob = launch {
                        delay(500)
                        _displayConnectionState.value = ConnectionState.Reconnecting
                    }
                } else {
                    reconnectRevealJob?.cancel()
                    reconnectRevealJob = null
                    _displayConnectionState.value = real
                }
            }
        }
    }
```

### PumpTestViewModel.kt (verbatim, identical logic)

```kotlin
    // Delayed-reveal variant for display-layer consumers only (same semantics as
    // PlantPilotViewModel.displayConnectionState — see that file for rationale).
    private val _displayConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val displayConnectionState: StateFlow<ConnectionState> = _displayConnectionState.asStateFlow()
    private var reconnectRevealJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            connectionState.collect { real ->
                if (real == ConnectionState.Reconnecting) {
                    reconnectRevealJob?.cancel()
                    reconnectRevealJob = launch {
                        delay(500)
                        _displayConnectionState.value = ConnectionState.Reconnecting
                    }
                } else {
                    reconnectRevealJob?.cancel()
                    reconnectRevealJob = null
                    _displayConnectionState.value = real
                }
            }
        }
    }
```

### Visual consumers changed to displayConnectionState

**CommonComponents.kt — ViewModel overload (verbatim):**

```kotlin
    val state by viewModel.displayConnectionState.collectAsState()
```

**MainActivity.kt — dialog state (verbatim):**

```kotlin
    val dialogConnState by viewModel.displayConnectionState.collectAsState()
```

**PumpTestingScreen.kt — additional collect + chip + DataActivityIndicator (verbatim):**

```kotlin
    val displayConnectionState by viewModel.displayConnectionState.collectAsState()
    // ...
    ConnectionStatusChip(
        connectionState = displayConnectionState,
        onClick = onStatusChipClick
    )
    // ...
    DataActivityIndicator(
        isActive = canDisplayLastKnownData || displayConnectionState == com.plantpilot.data.ConnectionState.Connecting,
        isConnecting = displayConnectionState == com.plantpilot.data.ConnectionState.Connecting,
        isReconnecting = displayConnectionState == com.plantpilot.data.ConnectionState.Reconnecting
    )
```

**CalibrationScreen.kt — additional collect + chip (verbatim):**

```kotlin
    val displayConnectionState by viewModel.displayConnectionState.collectAsState()
    // ...
    ConnectionStatusChip(
        connectionState = displayConnectionState,
        onClick = {}
    )
```

**SerialOutputScreen.kt — additional collect + chip (verbatim):**

```kotlin
    val displayConnectionState by viewModel.displayConnectionState.collectAsState()
    // ...
    ConnectionStatusChip(
        connectionState = displayConnectionState,
        onClick = onStatusChipClick
    )
```

### Command guards — UNCHANGED

All `canSendCommands` and `canDisplayLastKnownData` definitions remain exactly as before, reading from the real `connectionState` directly:

- **PlantPilotViewModel.kt** — `canSendCommands` reads `connectionState.value`, `canDisplayLastKnownData` reads `connectionState.value`
- **PumpTestViewModel.kt** — identical
- **All screen-local vals** — `canSendCommands = connectionState == ...`, `canDisplayLastKnownData = connectionState == ...`

No guard was changed. The `_commandBlockedEvents` channel, snackbar wiring, and all `enabled = canSendCommands` buttons remain untouched.

### 500 ms justification

The 10 s disconnect debounce in `HardwareRepository.onConnectionLost()` already absorbs sub-10 s blips before `Reconnecting` is even entered. The 500 ms delayed reveal handles the remaining case: the retry fires, the WebSocket handshake completes in <500 ms, and `Reconnecting` → `Connected` happens before the delay elapses — so `displayConnectionState` never emits `Reconnecting` at all. 500 ms is long enough to filter out sub-second retries (which are common on local networks) while short enough to feel responsive when a real multi-second reconnect is needed. A longer delay (e.g. 1 s) would make genuine reconnects feel sluggish; shorter (e.g. 200 ms) would miss the fastest local retries.
