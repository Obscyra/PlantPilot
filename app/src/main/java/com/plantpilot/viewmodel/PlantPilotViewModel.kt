package com.plantpilot.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.plantpilot.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.plantpilot.data.HardwareEvent
import com.plantpilot.data.HardwareRepository
import com.plantpilot.data.NetworkModule
import com.plantpilot.data.PlantPilotRepository
import com.plantpilot.data.SettingsManager
import com.plantpilot.network.DeviceConfigResponse
import com.plantpilot.network.MotorConfig
import com.plantpilot.network.SyncRequest
import com.plantpilot.util.NotificationHelper
import kotlin.time.Duration.Companion.milliseconds

class PlantPilotViewModel(application: Application) : AndroidViewModel(application) {

    private val notificationHelper = NotificationHelper(application)
    private val repository = PlantPilotRepository()
    private val settingsManager = SettingsManager(application)
    private val hardwareRepository = HardwareRepository

    private val _plants = MutableStateFlow(MockData.generatePlants())
    val plants: StateFlow<List<Plant>> = _plants.asStateFlow()

    private val _deviceState = MutableStateFlow(MockData.defaultDeviceState())
    val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()

    // Single source of truth for ESP32 connectivity: the live WebSocket state,
    // updated by socket callbacks, heartbeat, lifecycle checks and post-action
    // responses. UI must derive connection state from these flows, never from
    // cached DeviceState / defaults.
    val isConnected = hardwareRepository.isConnected
    val isConnecting = hardwareRepository.isConnecting
    val telemetry = hardwareRepository.telemetry

    private val POLL_INTERVAL_MS = 30_000L

    private val _history = MutableStateFlow<List<WateringEvent>>(emptyList())
    val history: StateFlow<List<WateringEvent>> = _history.asStateFlow()

    private val _settings = MutableStateFlow(MockData.defaultSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    var isLoading by mutableStateOf(value = true)
        private set

    var isRefreshingDevice by mutableStateOf(value = false)
        private set

    var connectionError by mutableStateOf<String?>(null)
        private set

    var isSyncing by mutableStateOf(value = false)
        private set

    var isConfigDirty by mutableStateOf(value = false)
        private set

    var showOnboarding by mutableStateOf(value = true)
        private set

    private var wasPreviouslyConnected: Boolean = false

    // Watering completion tracking: motorNumber -> Deferred that completes on watering_finished event
    private val pendingWatering = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()

    // Low water reminder: notify at most once per hour while low
    private var lastLowWaterNotifiedAt = 0L
    private val LOW_WATER_NOTIFY_INTERVAL_MS = 60 * 60 * 1000L

    init {
        // Observe real-time telemetry from WebSocket
        viewModelScope.launch {
            hardwareRepository.telemetry.collect { telemetry ->
                telemetry?.let { status ->
                    val tankLevel = percentageToLevel(status.water_level)
                    _deviceState.value = _deviceState.value.copy(
                        waterTankLevel = tankLevel,
                        wifiSsid = status.wifi_ssid ?: _deviceState.value.wifiSsid
                    )

                    // Hourly low-water reminder
                    if (_settings.value.notificationsLowWater &&
                        tankLevel <= _deviceState.value.lowWaterThreshold
                    ) {
                        val now = System.currentTimeMillis()
                        if (now - lastLowWaterNotifiedAt >= LOW_WATER_NOTIFY_INTERVAL_MS) {
                            lastLowWaterNotifiedAt = now
                            notificationHelper.showLowWaterAlert()
                        }
                    }

                    // Update plants with real moisture data from push
                    _plants.value = _plants.value.map { plant ->
                        val moisture = status.soil.getOrNull(plant.motorNumber - 1) ?: plant.currentMoisture
                        plant.copy(currentMoisture = moisture)
                    }
                }
            }
        }

        // Observe connection state (live, no debounce — UI derives directly from isConnected)
        viewModelScope.launch {
            hardwareRepository.isConnected.collect { isConnected ->
                val justReconnected = isConnected && !wasPreviouslyConnected
                wasPreviouslyConnected = isConnected
                _deviceState.value = _deviceState.value.copy(isConnected = isConnected)
                if (justReconnected) {
                    // Two-way sync: pull whatever is newer on the device, push what's newer here.
                    performTwoWaySync()
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
        viewModelScope.launch {
            settingsManager.plantsFlow.collect { saved ->
                if (saved != null) {
                    _plants.value = saved
                }
            }
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
                        addHistoryEvent(newEvent)
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
                if (!hardwareRepository.isConnected.value) {
                    val ip = _deviceState.value.deviceIp
                    if (ip.isNotBlank()) {
                        val ok = liveCheck()
                        connectionError = if (ok) null else "Device unreachable"
                        if (ok) connectToDevice()
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

    private fun percentageToLevel(percentage: Int): Int {
        return when {
            percentage <= 10 -> 0
            percentage <= 35 -> 1
            percentage <= 60 -> 2
            percentage <= 85 -> 3
            else -> 4
        }
    }

    fun refreshData() {
        viewModelScope.launch {
            isRefreshingDevice = true
            val ok = liveCheck()
            connectionError = if (ok) null else "Device unreachable"
            if (ok) {
                if (!hardwareRepository.isConnected.value) connectToDevice()
                performTwoWaySync()
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
            connectionError = if (ok) null else "Device unreachable"
            if (ok && !hardwareRepository.isConnected.value) {
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
            connectionError = if (ok) null else "Device unreachable"
            if (ok) {
                if (hardwareRepository.isConnected.value) {
                    performTwoWaySync()
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

    /**
     * Two-way config sync. Pulls the ESP32's current config with last-modified
     * timestamps and, per motor, whichever side is more recent wins. Then pushes
     * our (possibly updated) config; the firmware independently ignores anything
     * that isn't newer than what it already stores.
     */
    private fun performTwoWaySync() {
        viewModelScope.launch {
            val result: Result<DeviceConfigResponse> = repository.fetchDeviceConfig()
            if (result.isSuccess) {
                val deviceMotors = result.getOrThrow().motors
                var changed = false
                val updatedPlants = _plants.value.map { plant ->
                    val dev = deviceMotors.find { it.id == plant.motorNumber }
                    // NOTE: device last_modified is epoch seconds, plant.lastUpdated is epoch millis.
                    if (dev != null && dev.last_modified * 1000L > plant.lastUpdated) {
                        changed = true
                        plant.copy(
                            wateringMode = devModeToMode(dev.mode),
                            waterAmountMl = dev.amount_ml,
                            moistureThreshold = dev.threshold ?: plant.moistureThreshold,
                            schedules = dev.schedules.map {
                                WateringSchedule(
                                    id = UUID.randomUUID().toString(),
                                    hour = it.hour,
                                    minute = it.minute,
                                    daysOfWeek = DayOfWeek.entries.toSet()
                                )
                            },
                            configVersion = dev.version,
                            lastUpdated = dev.last_modified * 1000
                        )
                    } else plant
                }
                if (changed) {
                    _plants.value = updatedPlants
                    persistPlants()
                }
            }
            syncConfigWithDevice(silent = true, force = true)
        }
    }

    private fun devModeToMode(mode: String): WateringMode = when (mode) {
        "auto" -> WateringMode.AUTOMATIC
        "scheduled" -> WateringMode.SCHEDULED
        else -> WateringMode.OFF
    }

    suspend fun waterPlant(plantId: String): Boolean {
        // Refuse immediately when offline — callers disable the button, and this
        // guards any path that slips through (no click-then-loading-then-fail).
        if (!hardwareRepository.isConnected.value) return false
        val plant = _plants.value.find { it.id == plantId } ?: return false
        
        notificationHelper.showWateringStarted(plant.name)
        
        // Create deferred BEFORE the HTTP request to avoid race condition
        // with the watering_finished WebSocket event
        val deferred = CompletableDeferred<Boolean>()
        pendingWatering[plant.motorNumber] = deferred
        
        return try {
            val result = repository.waterNow(plant.motorNumber)
            if (result.isSuccess) {
                // Wait for the actual watering_finished event from PilotCore (up to 60s timeout)
                try {
                    kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                        deferred.await()
                    } ?: false
                } finally {
                    pendingWatering.remove(plant.motorNumber)
                }
            } else {
                pendingWatering.remove(plant.motorNumber)
                connectionError = "Failed to reach device"
                false
            }
        } catch (e: Exception) {
            pendingWatering.remove(plant.motorNumber)
            connectionError = "Failed to reach device"
            false
        }
    }

    private fun addHistoryEvent(event: WateringEvent) {
        val updatedHistory = (listOf(event) + _history.value).take(50)
        _history.value = updatedHistory
        viewModelScope.launch {
            settingsManager.saveHistory(updatedHistory)
        }
        
        // Also update the plant's last watered timestamp
        _plants.value = _plants.value.map {
            if (it.id == event.plantId) {
                it.copy(
                    lastWateredTimestamp = event.timestamp,
                    currentMoisture = event.moistureAfter ?: it.currentMoisture
                )
            } else it
        }
        
        // Complete any pending watering deferred for this motor
        pendingWatering.remove(event.motorNumber)?.complete(true)
        
        // Show notification if it was a finished event we just received
        val plant = _plants.value.find { it.id == event.plantId }
        if (plant != null) {
            notificationHelper.showWateringFinished(plant.name)
        }
    }

    fun syncConfigWithDevice(silent: Boolean = false, force: Boolean = false) {
        if (isSyncing || (!isConfigDirty && !force)) return
        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            if (!silent) isSyncing = true
            
            val motorConfigs = _plants.value.map { plant ->
                MotorConfig(
                    id = plant.motorNumber,
                    name = plant.name,
                    mode = when (plant.wateringMode) {
                        WateringMode.OFF -> "off"
                        WateringMode.AUTOMATIC -> "auto"
                        WateringMode.SCHEDULED -> "scheduled"
                    },
                    amount_ml = plant.waterAmountMl,
                    threshold = plant.moistureThreshold,
                    version = plant.configVersion,
                    last_modified = plant.lastUpdated / 1000,
                    schedules = plant.schedules
                )
            }

            val request = SyncRequest(
                epoch = System.currentTimeMillis() / 1000,
                motors = motorConfigs
            )

            val result = repository.sync(request)
            
            if (result.isSuccess) {
                isConfigDirty = false
                connectionError = null
            } else {
                connectionError = "Sync Failed: ${result.exceptionOrNull()?.message}"
            }
            
            if (!silent) {
                val elapsed = System.currentTimeMillis() - startTime
                val remaining = (2000L - elapsed).coerceAtLeast(0)
                delay(remaining.milliseconds)
                isSyncing = false
            }
        }
    }

    private fun markConfigDirty(autoSync: Boolean = false) {
        isConfigDirty = true
        // Only auto-sync when actually connected; otherwise edits are persisted
        // locally and synced on the next connection.
        if (autoSync && hardwareRepository.isConnected.value) {
            syncConfigWithDevice()
        }
    }

    fun updatePlant(plantId: String, update: (Plant) -> Plant) {
        var changed = false
        val updatedList = _plants.value.map {
            if (it.id == plantId) {
                val updated = update(it)
                if (updated != it) {
                    changed = true
                    updated.copy(
                        configVersion = updated.configVersion + 1,
                        lastUpdated = System.currentTimeMillis()
                    )
                } else it
            } else it
        }
        if (changed) {
            _plants.value = updatedList
            markConfigDirty()
            persistPlants()
        }
    }

    fun updateWateringMode(plantId: String, mode: WateringMode) {
        updatePlant(plantId) { plant ->
            plant.copy(
                wateringMode = mode,
                moistureThreshold = if (mode == WateringMode.AUTOMATIC && plant.moistureThreshold == 0) 10 else plant.moistureThreshold
            )
        }
        markConfigDirty(autoSync = true)
    }

    fun updateWateringAmount(plantId: String, amountMl: Int) {
        updatePlant(plantId) { it.copy(waterAmountMl = amountMl) }
        markConfigDirty(autoSync = true)
    }

    fun updatePlantName(plantId: String, name: String) {
        updatePlant(plantId) { it.copy(name = name) }
        markConfigDirty(autoSync = true)
    }

    fun updateMoistureThreshold(plantId: String, threshold: Int) {
        updatePlant(plantId) { it.copy(moistureThreshold = threshold) }
        markConfigDirty(autoSync = true)
    }

    fun updateMinInterval(plantId: String, hours: Int) {
        updatePlant(plantId) { it.copy(minIntervalHours = hours) }
        markConfigDirty(autoSync = true)
    }

    fun deletePlant(plantId: String) {
        _plants.value = _plants.value.filter { it.id != plantId }
        markConfigDirty(autoSync = true)
        persistPlants()
    }

    private fun persistPlants() {
        viewModelScope.launch {
            settingsManager.savePlants(_plants.value)
        }
    }

    fun addSchedule(plantId: String, schedule: WateringSchedule) {
        updatePlant(plantId) { plant ->
            plant.copy(
                schedules = plant.schedules + schedule,
                wateringMode = if (plant.wateringMode == WateringMode.OFF) WateringMode.SCHEDULED else plant.wateringMode
            )
        }
        markConfigDirty(autoSync = true)
    }

    fun removeSchedule(plantId: String, scheduleId: String) {
        updatePlant(plantId) { plant ->
            val remainingSchedules = plant.schedules.filter { s -> s.id != scheduleId }
            plant.copy(
                schedules = remainingSchedules,
                wateringMode = if (remainingSchedules.isEmpty() && plant.wateringMode == WateringMode.SCHEDULED) {
                    WateringMode.OFF
                } else {
                    plant.wateringMode
                }
            )
        }
        markConfigDirty(autoSync = true)
    }

    fun updateSchedule(plantId: String, schedule: WateringSchedule) {
        updatePlant(plantId) { plant ->
            plant.copy(
                schedules = plant.schedules.map { s -> if (s.id == schedule.id) schedule else s },
                wateringMode = if (plant.wateringMode == WateringMode.OFF) WateringMode.SCHEDULED else plant.wateringMode
            )
        }
        markConfigDirty(autoSync = true)
    }

    fun toggleDeviceConnection() {
        if (hardwareRepository.isConnected.value) {
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
