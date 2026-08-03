# Changelog

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