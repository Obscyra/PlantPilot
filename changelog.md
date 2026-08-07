# Changelog

## [3.1] - 2026-08-07

### Fixed
- Hardware water level probe sensing: Updated GPIO 16, 17, 18, 19 with 330Ω resistors to use `INPUT_PULLUP` mode, 3-sample majority voting, and 2-cycle error debounce to prevent floating pin noise on disconnected probes.
- Water tank UI state synchronization: Updated `PlantPilotViewModel` data state flow collector so live telemetry volume updates take precedence over DataStore default fallbacks, preventing tank level indicator flickering.
- Telemetry discrete level mapping: Aligned discrete hardware water level fractions directly to exact 25%, 50%, 75%, and 100% bounds.

## [3.0] - 2026-08-06

### Fixed
- Firmware active-low relay startup: Fixed initialization sequence in `initRelays()` so output pins default to `RELAY_OFF` (`HIGH`) on boot without clicking/activating pumps.
- Firmware DNS server loop check: Fixed bitwise evaluation `WiFi.getMode() & WIFI_AP_STA` to `setupModeActive` so `dnsServer.processNextRequest()` is only called in SoftAP mode.
- Firmware multi-client WebSocket disconnect: Enclosed `stopOnDisconnect` pump termination in `ws.count() == 0` check to prevent active watering cancellation when secondary socket connections drop.
- WebSocket URL sanitization: Added `sanitizeWsUrl()` and `updateBaseUrl()` in App to normalize host schemes (`ws://`, `http://`, trailing slashes) and prevent `IllegalArgumentException` crashes.
- WebSocket message parsing: Added support for `type == "status"` frames in `HardwareRepository` so `"STATUS"` replies update live pump states.
- Service coroutine leak: Managed single `reconnectJob` reference in `SyncService` and added `cancelChildren()` cleanup in `onDestroy()`.
- Data synchronization: Added `stop_on_disconnect` to `MotorConfig` payload and updated ESP32 firmware sync parser to preserve existing NVS values when keys are omitted.
- Watering Animation Overlay: Dynamic timeout calculation based on plant water volume & flow rate, automatic completion on pump state OFF (`pumpStates`), `"STATUS"` probe on WS connect, and post-watering status sync to eliminate stuck overlay animations during network drops.

## [2.0] - 2026-08-04

### Added
- `HardwareConnection` interface defining the WebSocket connection API for testability.
- `ConnectionStateHelper` with shared `canSendCommands()`, `canDisplayLastKnownData()`, and `debouncedConnectionState()` replacing duplicated inline logic.
- `PlantPilotApp` Application class owning `hardwareConnection` and `repository` singletons.
- `PlantConfigManager` handling plant CRUD, schedule management, watering modes, and `persistPlants()`.
- `SyncCoordinator` for two-way sync, config push, offline event handling, and `isSyncing`/`isConfigDirty` Compose state.
- `HistoryManager` for watering history, low-water notifications, and pending-watering completion.
- `TelemetryProcessor` for telemetry projection, low-water alerts, and debounced snapshot persistence.

### Changed
- Converted `HardwareRepository` from `object` singleton to `class` implementing `HardwareConnection` interface.
- Refactored `PlantPilotViewModel` from ~837 lines to ~350 lines by delegating to four focused manager classes.
- Updated `MainActivity`, `SyncService`, and `PumpTestViewModel` to access `HardwareRepository` via `PlantPilotApp`.
- Updated `HomeScreen`, `PlantDetailScreen`, `SettingsScreen`, `SerialOutputScreen`, `CalibrationScreen`, and `PumpTestingScreen` to use `ConnectionStateHelper` instead of inline logic.

### Fixed
- Watering disconnect: Added `wateringInProgress` flag and 30-second `WATERING_DEBOUNCE_MS` grace period in `HardwareRepository.onConnectionLost()` to prevent brief app-side "Reconnecting" during ESP32 relay switching.

## [1.0] - 2026-08-03

### Added
- Background resilience: App holds a wake lock and self-reconnects to ESP32 after process kill.
- Low-water notifications.
- First-run onboarding experience.
- Metric/24-hour preference settings.

### Changed
- Converted social contact icons (LinkedIn, Facebook/Discord) to monochrome style.
- Replaced Facebook with Discord in developer contacts.
- Updated developer social links (Fahim and Mahim).
- Improved sensor calibration UI with live needle and wet/dry capture logic.

### Fixed
- Fixed syntax error in `SettingsScreen.kt` regarding top-level declarations.
- Corrected LinkedIn icon visibility logic in developer cards.