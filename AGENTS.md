# AGENTS.md

## Repo Layout
Two independent components; changes rarely touch both:
- `app/` — Android app. Kotlin, Jetpack Compose (Material 3), MVVM. Single Gradle module `:app`.
- `firmware/PlantPilot_ESP32.ino` — ESP32 firmware (single file). Built/flashed with Arduino IDE (ESPAsyncWebServer + AsyncTCP + ArduinoJson; ESP32 board package). NOT built by Gradle.

No unit tests or CI runner configured. The primary compile check for the Android app is `./gradlew assembleDebug` (outputs APK at `app/build/outputs/apk/debug/app-debug.apk`).

## Build & Release Notes
- **Gradle Version Catalog**: `gradle/libs.versions.toml` (Gradle 9.6.1, AGP 8.7.3, Kotlin 2.1.0, compileSdk 35, minSdk 26, Java 11).
- **`local.properties`**: Contains Windows SDK path (`C:\Users\magnum\...`); fix `sdk.dir` when building in WSL/Linux environments.
- **Signing**: Release builds use `signing.properties` (untracked/placeholder; minify disabled). Debug builds compile out of the box.
- **Version Bumping Process**: When cutting a release, update `versionCode` and `versionName` in `app/build.gradle.kts`, document changes in `changelog.md`, compile debug APK, and copy it into `releases/`.

## App Architecture (v2.0 Refactor)
- **Dependency Ingestion**: `HardwareRepository` implements `HardwareConnection` (class instance, not a singleton object). `PlantPilotApp` owns singletons (`hardwareConnection`, `repository`); screens resolve via `(application as PlantPilotApp)`.
- **ViewModel Architecture**: `PlantPilotViewModel` acts as orchestrator, delegating domain logic to specialized managers:
  - `SyncCoordinator` — Handles bi-directional motor config & schedule sync.
  - `HistoryManager` — Manages watering history events and RAM/DataStore ring buffer.
  - `TelemetryProcessor` — Single entry point for telemetry frames, soil calibration math, and tank volume reconciliation.
  - `PlantConfigManager` — Manages per-plant settings and schedule CRUD operations.
- **Connection Logic**: Screens must use `ConnectionStateHelper` (`canSendCommands()`, `canDisplayLastKnownData()`, `debouncedConnectionState()`) — do not duplicate inline connection checks.
- **Persistence**: DataStore Preferences for settings & state snapshots; fresh installs fall back to `MockData`.
- **Reconnection Guard**: `HardwareRepository.onConnectionLost()` uses a `wateringInProgress` guard + `WATERING_DEBOUNCE_MS` (30s) so relay toggling during active watering does not trigger false "Reconnecting" UI states.

---

## Firmware Hardware Pin Mappings & Circuit Specifications

### 1. Soil Moisture Sensors (Capacitive)

| Plant | VCC Power Pin | Mode   | Signal (ADC) Pin | Mode              | ADC Channel | Behavior                                                    |
|-------|---------------|--------|-----------------|-------------------|-------------|-------------------------------------------------------------|
| 1     | GPIO 4        | OUTPUT | GPIO 34         | INPUT (ADC1_CH6)  | ADC1_CH6    | Pulsed 3.3V for 100ms during sampling, 0V when idle          |
| 2     | GPIO 5        | OUTPUT | GPIO 35         | INPUT (ADC1_CH7)  | ADC1_CH7    | Pulsed 3.3V for 100ms during sampling, 0V when idle          |
| 3     | GPIO 21       | OUTPUT | GPIO 32         | INPUT (ADC1_CH4)  | ADC1_CH4    | Pulsed 3.3V for 100ms during sampling, 0V when idle          |
| 4     | GPIO 22       | OUTPUT | GPIO 33         | INPUT (ADC1_CH5)  | ADC1_CH5    | Pulsed 3.3V for 100ms during sampling, 0V when idle          |

> **Power Management**: Driven HIGH for 100ms per sampling cycle (100ms stabilization + 32-sample ADC averaging at 100µs intervals) and turned LOW immediately after to eliminate probe electrolysis and power waste. All signal pins are read-only analog inputs. Shared common GND rail.

### 2. Water Level Sensor (4-Stage Probe)

| Probe | Height Level | ESP32 Pin | Mode         | Circuit Details                                                |
|-------|--------------|-----------|--------------|----------------------------------------------------------------|
| Common Power | Tank Bottom | GPIO 23 | OUTPUT | Pulsed 3.3V power (Normal Mode) / Solid HIGH (Demo Mode)      |
| WL1   | 25% (Low)    | GPIO 16   | INPUT_PULLUP | 330Ω inline resistor                                           |
| WL2   | 50% (Mid)    | GPIO 17   | INPUT_PULLUP | 330Ω inline resistor                                           |
| WL3   | 75% (High)   | GPIO 18   | INPUT_PULLUP | 330Ω inline resistor                                           |
| WL4   | 100% (Full)  | GPIO 19   | INPUT_PULLUP | 330Ω inline resistor                                           |

#### Firmware Probe Sampling & Debounce Logic (`readHardwareWaterLevel`)
- **Probe Power**: In Normal Mode, GPIO 23 is driven HIGH for 100ms before reading, then LOW immediately after (prevents electrolysis). In Demo Mode, GPIO 23 remains solid HIGH.
- **Probe Majority Voting (`sampleProbeSubmerged`)**: Takes 3 reads at 2ms intervals per pin; pin is submerged if `lowCount >= 2` (LOW state = conduct to ground/water).
- **Pattern Evaluation**:
  - `L4 && L3 && L2 && L1` → 100% (Level 4)
  - `!L4 && L3 && L2 && L1` → 75% (Level 3)
  - `!L4 && !L3 && L2 && L1` → 50% (Level 2)
  - `!L4 && !L3 && !L2 && L1` → 25% (Level 1)
  - `!L4 && !L3 && !L2 && !L1` → 0% (Level 0 / Empty)
  - Any out-of-order state → Sensor Error (`Level -1`).
- **Debounce & Hysteresis Guards**:
  - Drops to Level 0 (Empty) require **2 consecutive reads** of Level 0 to prevent false dry-run pump shutdowns due to water sloshing.
  - Sensor Error transitions (Level -1) require **2 consecutive reads** to ignore transient noise.

#### App Volume Reconciliation Logic (`TelemetryProcessor`)
- **Demo Mode**: Direct calculation (`rawDiscrete * 0.25 * capacityMl`), bypassing volume subtraction.
- **Normal Mode**: Hardware sensor level anchors volume bounds:
  - Level 4: 75% – 100% (midpoint 100%)
  - Level 3: 50% – 75% (midpoint 75%)
  - Level 2: 25% – 50% (midpoint 50%)
  - Level 1: 5% – 25% (midpoint 25%)
  - Level 0: 0ml
- If history volume is within bounds, smooth subtraction is used; if volume falls outside bounds (e.g. tank refilled), volume snaps to the sensor level midpoint.

### 3. Peripherals & Control Pins

| Component    | ESP32 Pin | Mode         | Details / Behavior                                                               |
|--------------|-----------|--------------|----------------------------------------------------------------------------------|
| Relay 1 (P1) | GPIO 25   | OUTPUT       | Active-Low (`RELAY_ON = LOW`, `RELAY_OFF = HIGH`)                                |
| Relay 2 (P2) | GPIO 26   | OUTPUT       | Active-Low (`RELAY_ON = LOW`, `RELAY_OFF = HIGH`)                                |
| Relay 3 (P3) | GPIO 27   | OUTPUT       | Active-Low (`RELAY_ON = LOW`, `RELAY_OFF = HIGH`)                                |
| Relay 4 (P4) | GPIO 14   | OUTPUT       | Active-Low (`RELAY_ON = LOW`, `RELAY_OFF = HIGH`)                                |
| Status LED   | GPIO 2    | OUTPUT       | Onboard Blue LED (Fast blink=Connecting, Short blip=Idle, Solid=App WS connected)|
| User Button  | GPIO 0    | INPUT_PULLUP | BOOT button. Short press (<5s) = Reboot; Long press (>=5s) = Clear Wi-Fi & AP reset|
| Buzzer       | GPIO 13   | OUTPUT       | Piezo alarm for low water warnings & user feedback beeps                         |

---

## Communication & Protocol (App ↔ Firmware)

### Protocol Mechanics
- **Pump States**: Derived from `ok` ACKs and direct `STATUS` responses — **not** regular telemetry broadcasts.
- **Indexing**: `pumps` arrays in firmware are 0-indexed (`0..3`); `motorNumber` in user/app events is 1-indexed (`1..4`).
- **Telemetry Payload**: Transmitted over WebSocket (`ws://plantpilot.local/ws` or IP). `motors[]` config payload only rides on telemetry when config signature changes or every 30s.

### WebSocket Commands
- `STATUS` — Requests immediate full telemetry frame.
- `READ_SENSORS` — Triggers fresh sensor sampling and telemetry push.
- `PUMP{1..4}_ON` / `PUMP{A..D}_ON` — Runs specified motor.
- `PUMP{1..4}_OFF` / `PUMP{A..D}_OFF` — Stops specified motor.
- `PUMP_ALL_OFF` — Emergency stop for all relays.
- `DEMO_MODE_ON` / `DEMO_MODE_OFF` — Toggles Demo Mode.
- `HW_WATER_SENSOR_ON` / `HW_WATER_SENSOR_OFF` — Enables/disables hardware tank sensor reading.
- `BUZZ_CADENCE <min>` — Sets low water alarm buzzer cadence (0, 5, 15, 30, 60 minutes).
- `CAL_STREAM_ON` / `CAL_STREAM_OFF` — Enables/disables high-rate calibration stream (3s cadence).
- `SYNC_MODE <sec>` — Temporarily sets telemetry stream interval.
- `TELEMETRY_PAUSE` / `TELEMETRY_RESUME` — Background/foreground app state notifications.
- `RESET_CONFIG` / `REBOOT` / `FACTORY_RESET` — Maintenance commands.

### REST API Endpoints
- `GET /api/status` — Returns current status, sensor readings, and motor states.
- `GET /api/config` / `POST /api/config` — Reads or updates per-motor settings JSON.
- `POST /api/sync` — Bi-directional configuration & schedule synchronization endpoint.
- `POST /api/motor/{1..4}/water_now` — Triggers instant watering for specified channel.
- `POST /api/calibrate` — Updates dry/wet ADC calibration bounds for a sensor channel.
- `GET /api/wifi_status` — Returns Wi-Fi connection info and RSSI.
- `POST /api/reboot` — Reboots the ESP32.
- `POST /api/factory_reset` — Clears NVS preferences and reboots to setup AP.

---

## Special Operating Modes

1. **Normal Mode**:
   - Empty tank dry-run safety lock active (prevents running pumps with no water).
   - Sensor sampling follows configured cadence.
   - Water level tank power probe pulsed during sampling to prevent corrosion.
2. **Demo Mode (`DEMO_MODE_ON`)**:
   - Fast 3s telemetry streaming cadence.
   - Bypasses dry-run empty tank safety check (allows testing without water).
   - Bypasses `minIntervalHours` auto-watering cooldowns when app is open.
   - Powers water level tank probe continuously (`3.3V solid HIGH`).
   - Bypasses `useHardwareWaterSensor = false` guard so probes are always read when demo mode is active.

---

## Conventions & Maintenance Guidelines
- Conventional commits: `fix:`, `feat:`, `refactor:`, `docs:`.
- Maintain single-source-of-truth documentation: keep `README.md`, `AGENTS.md`, and `changelog.md` updated whenever protocol or hardware pin assignments change.

