package com.plantpilot.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.plantpilot.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
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
import com.plantpilot.network.MotorConfig
import com.plantpilot.network.SyncRequest
import com.plantpilot.util.NotificationHelper
import com.plantpilot.util.WifiUtils
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

    val isConnecting = hardwareRepository.isConnecting

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

    // Disconnect debounce: delay before marking as disconnected
    private var disconnectJob: Job? = null
    private val DISCONNECT_DELAY_MS = 3000L

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

        // Observe connection state
        viewModelScope.launch {
            hardwareRepository.isConnected.collect { isConnected ->
                if (isConnected) {
                    // Connected: cancel any pending disconnect, update immediately
                    disconnectJob?.cancel()
                    disconnectJob = null
                    val justReconnected = !wasPreviouslyConnected
                    wasPreviouslyConnected = true
                    _deviceState.value = _deviceState.value.copy(isConnected = true)
                    
                    if (justReconnected) {
                        syncConfigWithDevice(silent = true, force = true)
                    }
                } else {
                    // Disconnected: debounce - only mark disconnected after3 seconds
                    if (wasPreviouslyConnected && disconnectJob == null) {
                        disconnectJob = viewModelScope.launch {
                            delay(DISCONNECT_DELAY_MS)
                            // Re-check if still disconnected after delay
                            if (!hardwareRepository.isConnected.value) {
                                wasPreviouslyConnected = false
                                _deviceState.value = _deviceState.value.copy(isConnected = false)
                            }
                            disconnectJob = null
                        }
                    }
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

        viewModelScope.launch {
            delay(500.milliseconds)
            
            // Auto-detect current WiFi SSID
            val currentSsid = WifiUtils.getCurrentSsid(application)
            if (currentSsid != "Unknown WiFi") {
                updateDeviceState { it.copy(wifiSsid = currentSsid) } // Uses the setter that SAVES to DataStore
            }
            
            isLoading = false
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
        // Manual refresh now just forces a config sync since status is pushed via WebSocket
        syncConfigWithDevice(silent = false, force = true)
    }

    suspend fun waterPlant(plantId: String): Boolean {
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
                false
            }
        } catch (e: Exception) {
            pendingWatering.remove(plant.motorNumber)
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
        if (autoSync) {
            syncConfigWithDevice()
        }
    }

    fun updatePlant(plantId: String, update: (Plant) -> Plant) {
        _plants.value = _plants.value.map {
            if (it.id == plantId) {
                val updated = update(it)
                if (updated != it) {
                    val versioned = updated.copy(configVersion = updated.configVersion + 1)
                    markConfigDirty()
                    versioned
                } else it
            } else it
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
        refreshData()
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
