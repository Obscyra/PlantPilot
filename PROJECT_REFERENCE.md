# PlantPilot Project Reference

## Overview
Android companion app for an ESP32-based automated plant watering system (branded **PilotCore** in the app UI). Real-time communication happens over a WebSocket; config sync and "water now" use a lightweight REST API. The firmware drives a 4-channel relay pump board with per-pump soil moisture sensors, schedules, and auto-watering.

## Hardware Configuration (ESP32)
- **DevKit**: WROOM-32 (38-pin).
- **4-Channel Relay Board**: Active-low relays on GPIO 25, 26, 27, 14 (`RELAY_ON = LOW`). Controls 4 water pumps.
- **Soil Moisture Sensors**: Analog input on GPIO 34, 35, 32, 33 (one per pump). Raw ADC is 12-bit (0-4095); `map(raw, 4095, 1000, 0, 100)` converts to a 0-100% reading.
- **WiFi**: STA connection to saved network; fallback to **SoftAP setup mode** (`PlantPilot-Setup`) with a captive-portal HTML page when no credentials are stored. mDNS hostname `plantpilot`.
- **Time**: NTP sync (`pool.ntp.org`, UTC+6 offset) persisted to NVS so it survives reboots; schedules rely on this clock.
- **Firmware**: `firmware/PlantPilot_ESP32_Test.ino` using `ESPAsyncWebServer`, `AsyncWebSocket`, `ArduinoJson`, `Preferences` (NVS), `ESPmDNS`, `DNSServer`.

## Firmware Power Saving (adaptive idle behavior)
- `WiFi.setSleep(WIFI_PS_MIN_MODEM)` in setup (light modem sleep; keeps reconnects near-instant).
- **Adaptive telemetry interval** in `loop()`: **3s** while a WebSocket client is connected, **60s** when idle. `broadcastTelemetry()` early-returns on `ws.count() == 0`, so idle does zero sensor reads/broadcasts.
- Schedule + auto-watering checks run every 1s regardless of connection state (watering behavior is never degraded).
- `dnsServer.processNextRequest()` only runs while in SoftAP/setup mode.

## Tech Stack (Android)
- **UI**: Jetpack Compose (Material 3), Neon Green/Dark theme, left-aligned screen headers.
- **Architecture**: MVVM with `PlantPilotViewModel` (app state, watering, notifications) and `PumpTestViewModel` (diagnostics terminal).
- **Real-time comms**: OkHttp **WebSocket** (`ws://<host>/ws`) via `HardwareRepository` — push-based telemetry, pump states, and `watering_finished` events.
- **REST**: Retrofit 2 + OkHttp 3 (`PlantPilotRepository` / `NetworkModule`, base URL default `http://plantpilot.local/`). Endpoints: `POST /api/sync` (config push), `POST /api/motor/{id}/water_now`.
- **Persistence**: **Jetpack DataStore** (Preferences) via `SettingsManager`:
    - Device name (`PlantPilot-PilotCore`), IP, and WiFi SSID.
    - Tank capacity and low-water threshold.
    - App settings (low-water notifications, metric units, 24-hour format).
    - **Watering History**: Last 50 `WateringEvent`s stored as JSON.
- **Notifications**: `NotificationHelper` — `watering_notifications` + `low_water_alerts` channels; hourly low-water reminders.
- **Permissions**: INTERNET, Cleartext (HTTP), ACCESS_FINE_LOCATION (for SSID detection).

## Data Models
- `Plant`: id, name, motorNumber (1-4), wateringMode (OFF/SCHEDULED/AUTOMATIC), waterAmountMl, moistureThreshold, minIntervalHours, currentMoisture, schedules, dryCalibration, wetCalibration, configVersion.
- `WateringSchedule`: id, hour, minute, daysOfWeek.
- `WateringEvent`: id, plantId, plantName, motorNumber, amountMl, triggerType (MANUAL/SCHEDULED/AUTOMATIC), timestamp, moistureBefore/After (persisted JSON).
- `DeviceState`: isConnected, deviceName, wifiSsid, deviceIp, waterTankLevel, tankCapacityMl, lowWaterThreshold.
- `AppSettings`: notificationsLowWater, useMetricUnits, use24HourFormat (watering-completed/schedule-reminder flags deprecated).

## Communication Protocol (WebSocket)
- **Commands (app → device)**: `STATUS`, `PUMP{A-D}_ON`/`PUMP{A-D}_OFF` (letters), `PUMP_ALL_ON`/`PUMP_ALL_OFF`.
- **Telemetry (device → app)**: JSON `{"type":"telemetry", ...}` with water level, soil %, raw_soil, wifi_rssi/ssid, uptime, free_heap, epoch, pump states.
- **ACKs (device → app)**: JSON `{"type":"ok","cmd":"...","pumps":[bool x4]}` — carries the *actual* firmware pump states (array is 0-indexed, app map is 1-indexed).
- **Events (device → app)**: JSON `{"type":"watering_finished", ...}` emitted only for real timed waterings (`lastAmountMl > 0`), so diagnostic test toggles never create false history entries.

## Key Logic & Workflows
- **Connection**: `HardwareRepository` is a singleton owning the WebSocket. Stale-socket callbacks are guarded with an `isCurrent(webSocket)` identity check. Logs clear when a new connection starts. On connect, the app sends `STATUS` for an instant pump-state sync.
- **Pump state sync**: Pump states are updated from `ok` ACK responses and `STATUS` replies — **not** from telemetry (telemetry would overwrite pending local toggles). Each `Plant` maps to a pump via `motorNumber`.
- **Persistence versioning**: Firmware trusts the app's `configVersion` increment and skips re-writing unchanged configs to NVS (reduces flash wear).
- **Watering**: `waterPlant` runs a fixed 3s overlay animation while the real watering happens in the background; "watering complete" snackbars were removed. Low-water state shows a message inside the water tank card and triggers hourly reminders.
- **History**: Real-time. Every completed `watering_finished` event adds a `WateringEvent` and persists to DataStore.

## Screens
- **Home**: plant overview, water tank indicator (with low-water message), connection chip/dialog.
- **Plants**: plant list / empty state, plant detail (name, amounts, schedules, water-now).
- **History**: watering event list.
- **Settings**: device connection + setup, app preferences, **Diagnostics** section with "Pump Hardware Testing" (navigates to `PumpTestingScreen`) and "Sensor Calibration" (bottom sheet).
- **Onboarding**: first-run / re-triggered intro (references PilotCore).
- **Hardware Diagnostics** (`PumpTestingScreen`): IP field, connect/disconnect, RSSI/heap/uptime/time status card, All-Pumps master switch, 4 pump toggle rows, and a GitHub-dark terminal-style **Communication Log** with color-coded `[ERR]/[RX]/[TX]/[SYS]` prefixes.

## Sensor Calibration (UI only, logic pending)
- `CalibrationBottomSheet` (`Sheets.kt`): 4-segment sensor selector, live-reading card (simulated jitter until real telemetry is wired via the `liveReadings` param), estimated-moisture % preview, interactive wet→dry gradient scale with labeled markers + a live "needle", "Set as Wet"/"Set as Dry" capture buttons, and fine-tune value fields. `onSave` returns `(sensorId, dryValue, wetValue)`; Save is enabled only when `dry > wet`.

## File Structure Highlights
- `firmware/PlantPilot_ESP32_Test.ino`: Core hardware logic (relays, sensors, WebSocket, schedules, SoftAP setup, adaptive telemetry).
- `app/src/main/java/com/plantpilot/data/HardwareRepository.kt`: WebSocket singleton — telemetry/pump/event parsing, logs.
- `app/src/main/java/com/plantpilot/data/SettingsManager.kt`: DataStore persistence.
- `app/src/main/java/com/plantpilot/data/PlantPilotRepository.kt` + `network/ApiService.kt`: Retrofit REST client.
- `app/src/main/java/com/plantpilot/viewmodel/`: `PlantPilotViewModel.kt`, `PumpTestViewModel.kt`.
- `app/src/main/java/com/plantpilot/ui/screens/`: Home, Plants, PlantDetail, History, Settings, Onboarding, PumpTesting.
- `app/src/main/java/com/plantpilot/ui/components/`: Sheets (schedules/calibration), CommonComponents (connection chip/dialog), MoistureRing, PlantCard, WaterTankIndicator, WateringAnimation, Shimmer.
- `app/src/main/java/com/plantpilot/navigation/NavGraph.kt`: sealed `Screen` routes + bottom nav items.

## Project Status
Networking (WebSocket + REST), persistence, notifications, and hardware communication are implemented and functional. Sensor calibration logic (real telemetry feed + saving values to the device) is still pending — the UI shell is in place. Calibration values currently don't persist to the device (TODO in `Sheets.kt`/firmware).
