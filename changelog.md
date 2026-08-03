# Changelog

## [2.0] - 2026-08-04

### Added
- `HardwareConnection` interface and `ConnectionStateHelper` for shared connection logic.
- `PlantPilotApp` Application class owning repository singletons.
- `PlantConfigManager` for plant CRUD, schedules, and watering modes.
- `SyncCoordinator` for two-way sync, config push, and offline event handling.
- `HistoryManager` for watering history, notifications, and pending-watering completion.
- `TelemetryProcessor` for telemetry projection, low-water alerts, and snapshot persistence.

### Changed
- Converted `HardwareRepository` from object singleton to class implementing `HardwareConnection`.
- Extracted duplicated connection-state logic (`canSendCommands`, `canDisplayLastKnownData`, `debouncedConnectionState`) into `ConnectionStateHelper`.
- Refactored `PlantPilotViewModel` (837 → 544 lines) by delegating to four focused manager classes.
- Updated all UI screens to use `ConnectionStateHelper` instead of inline logic.

### Fixed
- Watering disconnect: `HardwareRepository` now sets a 30-second grace period during `waterPlant()` to prevent brief app-side disconnection while the ESP32 relay switches.

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