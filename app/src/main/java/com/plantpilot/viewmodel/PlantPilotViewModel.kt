package com.plantpilot.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.plantpilot.PlantPilotApp
import com.plantpilot.data.ConnectionState
import com.plantpilot.data.ConnectionStateHelper
import com.plantpilot.data.HardwareEvent
import com.plantpilot.data.NetworkModule
import com.plantpilot.data.SettingsManager
import com.plantpilot.model.*
import com.plantpilot.util.NotificationHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinator for the ESP32 connection, plant config, sync, telemetry, history
 * and watering. The heavy lifting is delegated to focused managers:
 *  - [PlantConfigManager]  plant CRUD + schedules + persistence
 *  - [SyncCoordinator]     two-way config sync with the device
 *  - [HistoryManager]      watering history tracking + notifications
 *  - [TelemetryProcessor]  telemetry projection onto UI state
 */
class PlantPilotViewModel(application: Application) : AndroidViewModel(application) {

    private val notificationHelper = NotificationHelper(application)
    private val settingsManager = SettingsManager(application)
    private val hardwareRepository = (application as PlantPilotApp).hardwareConnection
    private val repository = (application as PlantPilotApp).repository

    private val _plants = MutableStateFlow(MockData.generatePlants())
    val plants: StateFlow<List<Plant>> = _plants.asStateFlow()

    private val _deviceState = MutableStateFlow(MockData.defaultDeviceState())
    val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()

    // Single source of truth for ESP32 connectivity: the live WebSocket state,
    // updated by socket callbacks, heartbeat, lifecycle checks and post-action
    // responses. UI must derive connection state from this flow, never from
    // cached DeviceState / defaults.
    val connectionState = hardwareRepository.connectionState

    /** Strict: only true when WebSocket is confirmed alive. Guards outbound commands. */
    val canSendCommands: Boolean
        get() = ConnectionStateHelper.canSendCommands(connectionState.value)

    /** Permissive: true when Connected or Reconnecting. Fine for displaying
     *  last-known data (telemetry, pump states) — the data isn't stale-looking,
     *  just not live. */
    val canDisplayLastKnownData: Boolean
        get() = ConnectionStateHelper.canDisplayLastKnownData(connectionState.value)

    // Delayed-reveal variant of connectionState for display-layer consumers only.
    // Reconnecting is held back for 500 ms so short-lived reconnects (which
    // resolve almost instantly on retry) never flash a "Reconnecting" label.
    // Command guards (canSendCommands / canDisplayLastKnownData) must continue
    // reading the real connectionState directly — never this.
    val displayConnectionState: StateFlow<ConnectionState> =
        ConnectionStateHelper.debouncedConnectionState(connectionState, viewModelScope)

    val telemetry = hardwareRepository.telemetry

    /** One-shot event channel for snackbar messages when commands are blocked. */
    private val _commandBlockedEvents = Channel<String>(Channel.BUFFERED)
    val commandBlockedEvents = _commandBlockedEvents.receiveAsFlow()

    // Per-sensor calibration pulled from the device (sensorId -> (dry, wet)).
    private val _sensorCalibration = MutableStateFlow<Map<Int, Pair<Int, Int>>>(emptyMap())
    val sensorCalibration: StateFlow<Map<Int, Pair<Int, Int>>> = _sensorCalibration.asStateFlow()

    // Per-sensor flow rate pulled from the device (sensorId -> mlPerSec).
    private val _sensorFlowRate = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val sensorFlowRate: StateFlow<Map<Int, Int>> = _sensorFlowRate.asStateFlow()

    private val POLL_INTERVAL_MS = 30_000L

    private val _history = MutableStateFlow<List<WateringEvent>>(emptyList())
    val history: StateFlow<List<WateringEvent>> = _history.asStateFlow()

    private val _settings = MutableStateFlow(MockData.defaultSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    var isLoading by mutableStateOf(value = true)
        private set

    var isRefreshingDevice by mutableStateOf(value = false)
        private set

    val isSyncing get() = syncCoordinator.isSyncing
    val isConfigDirty get() = syncCoordinator.isConfigDirty

    var showOnboarding by mutableStateOf(value = true)
        private set

    private var wasPreviouslyConnected: Boolean = false

    // Watering completion tracking: motorNumber -> Deferred that completes on watering_finished event
    private val pendingWatering = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()

    private val historyManager = HistoryManager(
        history = _history,
        plants = _plants,
        scope = viewModelScope,
        settingsManager = settingsManager,
        notificationHelper = notificationHelper,
        onWateringFinished = { motor -> pendingWatering.remove(motor)?.complete(true) },
    )

    private val syncCoordinator = SyncCoordinator(
        plants = _plants,
        sensorCalibration = _sensorCalibration,
        sensorFlowRate = _sensorFlowRate,
        settings = _settings,
        scope = viewModelScope,
        repository = repository,
        historyManager = historyManager,
        canSendCommands = { canSendCommands },
        persistPlants = { configManager.persistPlants() },
    )

    private val configManager = PlantConfigManager(
        plants = _plants,
        scope = viewModelScope,
        settingsManager = settingsManager,
        markConfigDirty = { autoSync -> syncCoordinator.markConfigDirty(autoSync) },
    )

    private val telemetryProcessor = TelemetryProcessor(
        deviceState = _deviceState,
        plants = _plants,
        settings = _settings,
        scope = viewModelScope,
        settingsManager = settingsManager,
        notificationHelper = notificationHelper,
        applyDeviceConfig = syncCoordinator::applyDeviceConfig,
        persistPlants = { configManager.persistPlants() },
    )

    init {
        // Hydrate the last known device state before the first live message
        // arrives, so a reopened app shows up-to-date data instantly (persisted
        // snapshot, not mocks) and then live sync corrects it.
        viewModelScope.launch {
            settingsManager.lastTelemetryFlow.first()?.let { telemetryProcessor.applyTelemetry(it) }
        }

        // Observe real-time telemetry from WebSocket
        viewModelScope.launch {
            hardwareRepository.telemetry.collect { telemetry ->
                telemetry?.let { telemetryProcessor.applyTelemetry(it) }
            }
        }

        // Observe connection state (live, no debounce — UI derives directly from connectionState)
        viewModelScope.launch {
            hardwareRepository.connectionState.collect { state ->
                val reachable = state == ConnectionState.Connected || state == ConnectionState.Reconnecting
                val justReconnected = reachable && !wasPreviouslyConnected
                wasPreviouslyConnected = reachable
                _deviceState.value = _deviceState.value.copy(isConnected = reachable)
                if (justReconnected) {
                    // Two-way sync: pull whatever is newer on the device, push what's newer here.
                    syncCoordinator.performTwoWaySync()
                }
            }
        }

        // Load persisted settings
        viewModelScope.launch {
            settingsManager.deviceStateFlow.collect { partial ->
                _deviceState.value = _deviceState.value.copy(
                    deviceName = partial.name,
                    deviceIp = partial.ip,
                    wifiSsid = partial.ssid,
                    tankCapacityMl = partial.capacity,
                    lowWaterThreshold = partial.threshold
                )
                NetworkModule.updateBaseUrl(partial.ip)

                // Maintain WebSocket connection to current IP
                if (partial.ip.isNotBlank()) {
                    val url = if (partial.ip.startsWith("ws://")) partial.ip else "ws://${partial.ip}/ws"
                    hardwareRepository.connect(url)
                }
            }
        }

        viewModelScope.launch {
            settingsManager.appSettingsFlow.collect { _settings.value = it }
        }

        // Read the persisted onboarding flag synchronously before the first
        // frame is composed, so the tutorial only ever shows on the very first
        // launch — never a flash on subsequent launches.
        runBlocking {
            showOnboarding = !settingsManager.onboardingCompletedFlow.first()
        }

        viewModelScope.launch {
            settingsManager.historyFlow.collect { _history.value = it }
        }

        // Load persisted plants (schedules/config survive full app restarts).
        // Falls back to MockData on a truly fresh install (never-saved).
        // Hydrated once: DataStore's plants value only ever changes from this
        // process, and a continuous collect would re-emit the last *persisted*
        // (stale-moisture) plants on every unrelated DataStore write — e.g. the
        // 2s telemetry snapshot — clobbering live moisture in memory.
        viewModelScope.launch {
            settingsManager.plantsFlow.first()?.let { _plants.value = it }
        }

        // Observe hardware events (History tracking)
        viewModelScope.launch {
            hardwareRepository.hardwareEvents.collect { event ->
                if (event is HardwareEvent.WateringFinished) {
                    val plant = _plants.value.find { it.motorNumber == event.motor }
                    if (plant != null) {
                        val newEvent = WateringEvent(
                            id = UUID.randomUUID().toString(),
                            plantId = plant.id,
                            plantName = plant.name,
                            motorNumber = plant.motorNumber,
                            amountMl = event.amount_ml,
                            triggerType = when (event.trigger) {
                                "scheduled" -> TriggerType.SCHEDULED
                                "auto" -> TriggerType.AUTOMATIC
                                else -> TriggerType.MANUAL
                            },
                            timestamp = if (event.epoch > 0) event.epoch * 1000 else System.currentTimeMillis(),
                            moistureBefore = null, // We could track this if we had it
                            moistureAfter = event.soil_after
                        )
                        historyManager.addHistoryEvent(newEvent)
                    }
                }
            }
        }

        isLoading = false

        // Periodic poll: while offline, actively re-check the ESP32 so the UI
        // connection state recovers as soon as the device is reachable again
        // (independent of the WebSocket reconnect backoff).
        viewModelScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                if (!canDisplayLastKnownData) {
                    val ip = _deviceState.value.deviceIp
                    if (ip.isNotBlank()) {
                        // Silently re-check the ESP32 so the UI recovers as soon as
                        // the device is reachable; the status chip communicates state.
                        if (liveCheck()) connectToDevice()
                    }
                }
            }
        }
    }

    fun dismissOnboarding() {
        showOnboarding = false
        viewModelScope.launch {
            settingsManager.saveOnboardingCompleted(true)
        }
    }

    fun showOnboardingAgain() {
        showOnboarding = true
    }

    fun refreshData() {
        viewModelScope.launch {
            isRefreshingDevice = true
            val ok = liveCheck()
            if (ok) {
                if (!canDisplayLastKnownData) connectToDevice()
                syncCoordinator.performTwoWaySync()
            }
            isRefreshingDevice = false
        }
    }

    /**
     * Live connectivity handshake (HTTP GET /api/status) against the ESP32.
     * Never reports a cached/default state as if it were live.
     */
    fun checkConnection(onResult: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            isRefreshingDevice = true
            val ok = liveCheck()
            if (ok && !canDisplayLastKnownData) {
                connectToDevice()
            }
            isRefreshingDevice = false
            onResult?.invoke(ok)
        }
    }

    /** Called from MainActivity ON_RESUME so the app re-checks when foregrounded. */
    fun onAppResumed() {
        viewModelScope.launch {
            val ok = liveCheck()
            if (ok) {
                if (canDisplayLastKnownData) {
                    syncCoordinator.performTwoWaySync()
                    requestSensorReading()
                } else {
                    connectToDevice()
                }
            }
        }
    }

    private suspend fun liveCheck(): Boolean {
        val ip = _deviceState.value.deviceIp
        if (ip.isBlank()) return false
        NetworkModule.updateBaseUrl(ip)
        return repository.checkConnection().isSuccess
    }

    /** Pushes a dry/wet calibration for one sensor to the ESP32. */
    fun calibrateSensor(sensorId: Int, dry: Int, wet: Int, mlPerSec: Int? = null, onResult: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            if (!canSendCommands) {
                _commandBlockedEvents.trySend("Can't calibrate — device offline")
                onResult?.invoke(false)
                return@launch
            }
            val result = repository.calibrate(sensorId, dry, wet, mlPerSec)
            if (result.isSuccess) {
                _sensorCalibration.value = _sensorCalibration.value + (sensorId to (dry to wet))
                if (mlPerSec != null) {
                    _sensorFlowRate.value = _sensorFlowRate.value + (sensorId to mlPerSec)
                }
            }
            onResult?.invoke(result.isSuccess)
        }
    }

    /**
     * Enables/disables the realtime 1s raw-sensor stream used by the
     * calibration sheet. Send CAL_STREAM_ON while the sheet is open so the
     * user sees live raw ADC values; send CAL_STREAM_OFF when it closes.
     */
    fun setCalibrationStreaming(enabled: Boolean) {
        if (!canSendCommands) {
            _commandBlockedEvents.trySend("Can't stream sensor data — device offline")
            return
        }
        hardwareRepository.sendCommand(if (enabled) "CAL_STREAM_ON" else "CAL_STREAM_OFF")
    }

    /** Asks the ESP32 to read and push the sensors immediately (app open/resume). */
    fun requestSensorReading() {
        if (!canSendCommands) {
            _commandBlockedEvents.trySend("Can't read sensors — device offline")
            return
        }
        hardwareRepository.sendCommand("READ_SENSORS")
    }

    suspend fun waterPlant(plantId: String): Boolean {
        // Refuse immediately when offline — callers disable the button, and this
        // guards any path that slips through (no click-then-loading-then-fail).
        if (!canSendCommands) {
            _commandBlockedEvents.trySend("Can't water — device offline")
            return false
        }
        val plant = _plants.value.find { it.id == plantId } ?: return false

        notificationHelper.showWateringStarted(plant.name)

        // Create deferred BEFORE the HTTP request to avoid race condition
        // with the watering_finished WebSocket event
        val deferred = CompletableDeferred<Boolean>()
        pendingWatering[plant.motorNumber] = deferred

        // A brief link hiccup while the relay clicks (shared power rail) is
        // common, so mask it: the disconnect debounce won't flip to
        // "Reconnecting" while watering is in flight.
        hardwareRepository.setWateringInProgress(true)

        return try {
            val result = repository.waterNow(plant.motorNumber)
            if (result.isSuccess) {
                // Wait for the actual watering_finished event from the firmware (up to 60s timeout)
                try {
                    withTimeoutOrNull(60_000L) {
                        deferred.await()
                    } ?: false
                } finally {
                    pendingWatering.remove(plant.motorNumber)
                }
            } else {
                pendingWatering.remove(plant.motorNumber)
                false
            }
        } catch (e: Exception) {
            pendingWatering.remove(plant.motorNumber)
            false
        } finally {
            hardwareRepository.setWateringInProgress(false)
        }
    }

    fun syncConfigWithDevice(silent: Boolean = false, force: Boolean = false) {
        syncCoordinator.syncConfigWithDevice(silent, force)
    }

    fun updatePlant(plantId: String, update: (Plant) -> Plant) {
        configManager.updatePlant(plantId, update)
    }

    fun updateWateringMode(plantId: String, mode: WateringMode) {
        configManager.updateWateringMode(plantId, mode)
    }

    fun updateWateringAmount(plantId: String, amountMl: Int) {
        configManager.updateWateringAmount(plantId, amountMl)
    }

    fun updatePlantName(plantId: String, name: String) {
        configManager.updatePlantName(plantId, name)
    }

    fun updateMoistureThreshold(plantId: String, threshold: Int) {
        configManager.updateMoistureThreshold(plantId, threshold)
    }

    fun updateMinInterval(plantId: String, hours: Int) {
        configManager.updateMinInterval(plantId, hours)
    }

    fun updateGlobalMaxRuntime(minutes: Int) {
        updateSettings { it.copy(maxRuntimeMinutes = minutes) }
        syncCoordinator.markConfigDirty(autoSync = true)
    }

    fun updateSensorCadence(seconds: Int) {
        updateSettings { it.copy(sensorCadenceSec = seconds) }
        hardwareRepository.setStreamCadence(seconds)
    }

    fun deletePlant(plantId: String) {
        configManager.deletePlant(plantId)
    }

    fun addSchedule(plantId: String, schedule: WateringSchedule) {
        configManager.addSchedule(plantId, schedule)
    }

    fun removeSchedule(plantId: String, scheduleId: String) {
        configManager.removeSchedule(plantId, scheduleId)
    }

    fun updateSchedule(plantId: String, schedule: WateringSchedule) {
        configManager.updateSchedule(plantId, schedule)
    }

    fun resetDeviceConfig(onComplete: (Boolean) -> Unit) {
        if (!canSendCommands) {
            _commandBlockedEvents.trySend("Can't reset — device offline")
            onComplete(false)
            return
        }
        viewModelScope.launch {
            // 1. Wipe ESP32 NVS config data via WebSocket command
            hardwareRepository.sendCommand("RESET_CONFIG")

            // 2. Small delay to let the ESP32 finish NVS clear and reload defaults
            delay(800)

            // 3. Force push the latest app data to the "wiped" device.
            // We bump timestamps so the app's config is guaranteed to be "newer"
            // than the device defaults (which will have last_modified = 0).
            _plants.value = _plants.value.map {
                it.copy(
                    lastUpdated = System.currentTimeMillis()
                )
            }

            // Perform a full force sync
            syncCoordinator.syncConfigWithDevice(silent = false, force = true)
            onComplete(true)
        }
    }

    fun toggleDeviceConnection() {
        if (canDisplayLastKnownData) {
            disconnectFromDevice()
        } else {
            refreshData()
        }
    }

    fun connectToDevice() {
        val ip = _deviceState.value.deviceIp
        if (ip.isNotBlank()) {
            val url = if (ip.startsWith("ws://")) ip else "ws://$ip/ws"
            hardwareRepository.connect(url)
        }
    }

    fun disconnectFromDevice() {
        hardwareRepository.disconnect()
    }

    fun updateDeviceState(update: (DeviceState) -> DeviceState) {
        val oldIp = _deviceState.value.deviceIp
        val newState = update(_deviceState.value)

        if (newState.deviceIp != oldIp && newState.deviceIp.isNotBlank()) {
            NetworkModule.updateBaseUrl(newState.deviceIp)
        }
        _deviceState.value = newState

        viewModelScope.launch {
            settingsManager.saveDeviceState(newState)
        }
    }

    fun updateSettings(update: (AppSettings) -> AppSettings) {
        val newSettings = update(_settings.value)
        _settings.value = newSettings
        viewModelScope.launch {
            settingsManager.saveAppSettings(newSettings)
        }
    }
}