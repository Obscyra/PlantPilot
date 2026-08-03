# PlantPilot

> Automated plant watering system — an Android companion app + ESP32 firmware that manages up to 4 water pumps with soil moisture sensors, schedules, and auto-watering.

| Component | Tech |
|---|---|
| Android app | Kotlin · Jetpack Compose (Material 3) · MVVM |
| Real-time comms | OkHttp WebSocket + Retrofit REST |
| Firmware | C++ (Arduino) · ESPAsyncWebServer · ArduinoJson |
| Persistence | Jetpack DataStore (app) · NVS / Preferences (firmware) |
| License | [MIT](LICENSE) |

---

## Table of Contents

- [Features](#features)
- [Hardware Requirements](#hardware-requirements)
- [Wiring](#wiring)
- [Getting Started](#getting-started)
  - [Firmware](#firmware)
  - [Android App](#android-app)
- [How It Works](#how-it-works)
  - [Network & Discovery](#network--discovery)
  - [WebSocket Protocol](#websocket-protocol)
  - [REST API](#rest-api)
  - [Adaptive Power Saving](#adaptive-power-saving)
- [Android App Screens](#android-app-screens)
- [Project Structure](#project-structure)
- [Status & Roadmap](#status--roadmap)
- [License](#license)

---

## Features

### App
- **Live dashboard** — per-plant moisture rings, water tank level, connection status.
- **Background resilience** — holds a wake lock and self-reconnects after a process kill, so watering and sync keep working when the app is backgrounded or swiped away (the connection only drops when you fully close the app).
- **Scheduling** — per-plant daily/weekly watering schedules (up to 5 per pump).
- **Auto-watering** — moisture-threshold triggered watering with min-interval guard.
- **Water now** — instant manual watering with a 3s watering animation overlay.
- **Full history** — last 50 watering events, persisted locally, updated in real time.
- **Pump hardware diagnostics** — terminal-style communication log with color-coded `[TX]/[RX]/[ERR]/[SYS]`, RSSI/heap/uptime, and per-pump toggle + master switch.
- **Sensor calibration UI** — wet→dry gradient scale with live needle, "Set as Wet/Dry" capture (logic wiring pending).
- **First-run onboarding**, low-water notifications, and metric/24-hour preferences.

### Firmware
- Drives a **4-channel active-low relay board** (4 water pumps).
- Per-pump soil moisture sensing via analog ADC.
- **STA mode** with **SoftAP captive-portal setup** fallback (`PlantPilot-Setup`, random 8-char password printed to Serial).
- **mDNS** hostname `plantpilot` → app connects by default as `http://plantpilot.local/`.
- NTP time sync (UTC+6) persisted to NVS so schedules survive reboots.
- Adaptive telemetry: **1s** foreground / **3s** background with a client connected, **60s** when idle.
- Background power saving via logical sensor-off: with the app closed, the telemetry path early-returns so no sensors are read at all; only plants with **auto-watering** enabled get their own sensor re-read (per-plant, every **10 min**), so sensors are logically off the rest of the time.
- Resilience: WiFi reconnect with escalating radio reset/restart, per-pump configurable `mlPerSecond`, max-runtime failsafe, and `stopOnDisconnect`.
- Full **Serial diagnostics**: boot logs reset reason + free heap; every WS command echoed as `[WS] RX` / `[WS] TX`; stale/evicted clients, WS stalls, low-heap warnings, and a periodic `[IDLE]` status line (WiFi/RSSI, cached soil %, pumps, water, heap, uptime) when disconnected so the monitor shows the device is alive.
- Command safety: heavy telemetry builds run on the main loop stack (not the AsyncTCP task), and STATUS replies use a fixed buffer.

---

## Hardware Requirements

| Part | Notes |
|---|---|
| ESP32 DevKit | WROOM-32, 38-pin |
| 4-Channel Relay Module | Active-low (`RELAY_ON = LOW`) |
| 4 × Water Pumps | ~10 ml/s flow rate (configurable in firmware) |
| 4 × Soil Moisture Sensors | Analog output (capacitive or resistive) |
| Power supply | Sized for pump draw; common ground with ESP32 |

> **Important:** relay modules are active-low. The firmware asserts `LOW` to turn a pump **on**, so keep the jumper/config on the relay board set accordingly.

## Wiring

| ESP32 GPIO | Connection |
|---|---|
| 25 | Relay IN 1 (Pump A) |
| 26 | Relay IN 2 (Pump B) |
| 27 | Relay IN 3 (Pump C) |
| 14 | Relay IN 4 (Pump D) |
| 34 | Soil moisture sensor 1 (ADC) |
| 35 | Soil moisture sensor 2 (ADC) |
| 32 | Soil moisture sensor 3 (ADC) |
| 33 | Soil moisture sensor 4 (ADC) |

Soil moisture raw ADC is 12-bit (0–4095); the firmware maps `map(raw, 4095, 1000, 0, 100)` to a 0–100% reading.

---

## Getting Started

### Firmware

1. Install the **Arduino IDE** (or PlatformIO) with the **ESP32 board package** (espressif/arduino-esp32).
2. Install these libraries:
   - `ESPAsyncWebServer` + `AsyncTCP`
   - `ArduinoJson`
   - (Built-ins used: `WiFi`, `Preferences`, `ESPmDNS`, `DNSServer`, `time`)
3. Open [`firmware/PlantPilot_ESP32.ino`](firmware/PlantPilot_ESP32.ino), select your ESP32 board/port, and flash.
4. On first boot the device opens a **SoftAP** `PlantPilot-Setup`:
   - Connect your phone/PC to that Wi-Fi network.
   - A captive-portal page opens asking for your Wi-Fi SSID/password (or visit `http://192.168.4.1/`).
   - Once connected, the device joins your network as **`plantpilot.local`**.
5. Optional firmware tweaks (top of file):
   - `SETUP_SSID` — SoftAP network name.
   - `DEFAULT_ML_PER_SECOND` / `MAX_ML_PER_SECOND` — pump flow rate fallback + sanity cap (per-pump rates are configurable from the app).
   - `GMT_OFFSET_SEC` / `NTP_SERVER` — time source.

> **mDNS note:** the app defaults to `plantpilot.local`. If your network doesn't support mDNS, enter the device's IP manually in Settings → Device.

### Android App

1. Open the project in **Android Studio** (Hedgehog+ / recent stable recommended).
2. Let Gradle sync. Minimum supported SDK is **26 (Android 8.0)**, target/compile **SDK 35**.
3. Build & run on a device/emulator on the same network as the ESP32:

   ```bash
   ./gradlew assembleDebug
   # APK at app/build/outputs/apk/debug/app-debug.apk
   ```

4. On first launch the app shows onboarding, then connects to **`plantpilot.local`** automatically.

---

## How It Works

### Network & Discovery

```
┌─────────────┐   WebSocket ws://plantpilot.local/ws    ┌──────────────────┐
│ Android App │ ──────────────────────────────────────▶ │      ESP32        │
│             │   REST  http://plantpilot.local/api/... │  (mDNS: plantpilot)│
└─────────────┘                                         └──────────────────┘
```

- **WebSocket** (push) — live telemetry, pump states, and watering events.
- **REST** (request/response) — config sync (`/api/sync`) and `water_now` triggers.
- The app's **default device address** is `http://plantpilot.local/`, overridable in Settings.

### WebSocket Protocol

Messages from the app (commands):

| Command | Meaning |
|---|---|
| `STATUS` | Request current pump states |
| `READ_SENSORS` | Force an immediate sensor read + telemetry push |
| `SYNC_MODE <sec>` | Set telemetry cadence (app lifecycle: 1s foreground / 3s background, clamped 1–30) |
| `CAL_STREAM_ON` / `CAL_STREAM_OFF` | Toggle real-time calibration sensor streaming (1s) |
| `PUMP{A–D}_ON` / `PUMP{A–D}_OFF` | Toggle a single pump (letter-based) |
| `PUMP_ALL_ON` / `PUMP_ALL_OFF` | Toggle all pumps |
| `RESET_CONFIG` | Wipe NVS config back to defaults |

Messages from the device:

| Type | Payload | Purpose |
|---|---|---|
| `telemetry` | water level, soil %, raw soil, RSSI/SSID, uptime, heap, epoch, pump states, full per-motor config (`motors[]`) | Live readings + config sync |
| `ok` | `cmd` echo + `pumps: [bool × 4]` | ACK carrying the **actual** firmware pump state |
| `watering_finished` | plant, motor, amount, moisture before/after | History entries (only for real timed waterings) |

> **Note:** pump states are sourced from `ok` ACKs / `STATUS` replies, not telemetry, so pending local toggles aren't overwritten. The `pumps` array is 0-indexed; the app maps 1-indexed `motorNumber`.

### REST API

| Method | Endpoint | Body | Purpose |
|---|---|---|---|
| `POST` | `/api/sync` | JSON config (plants, schedules, thresholds, `configVersion`) | Push configuration |
| `POST` | `/api/motor/{1–4}/water_now` | — | Trigger a manual watering |
| `GET` | `/api/wifi_status` | — | SoftAP setup page status polling |

### Adaptive Power Saving

- `WiFi.setSleep(WIFI_PS_MIN_MODEM)` — light modem sleep keeps reconnects near-instant.
- Telemetry cadence: **1s** foreground / **3s** background with a WebSocket client, **60s** when idle; `broadcastTelemetry()` early-returns when `ws.count() == 0`.
- **Logical sensor-off:** while disconnected, no sensors are read by the dashboard path. Only plants with auto-watering enabled get a fresh per-plant sensor read every **10 min** just before their watering decision; all other sensors stay logically off.
- Schedules and auto-watering checks still run every **1s** regardless of connection state — watering is never degraded.

---

## Android App Screens

| Screen | Purpose |
|---|---|
| **Home** | Plant overview, water tank indicator (+ low-water message), connection chip/dialog |
| **Plants** | Plant list / empty state; plant detail: name, amounts, schedules, water-now |
| **History** | Real-time list of completed watering events |
| **Settings** | Device connection & setup, app preferences, hardware settings (max pump runtime, sensor cadence), diagnostics (pump testing, sensor calibration) |
| **Hardware Diagnostics** | IP field, connect/disconnect, RSSI/heap/uptime/time card, all-pumps master switch, 4 pump toggles, terminal-style communication log |
| **Onboarding** | First-run intro (re-triggerable from Settings) |

---

## Project Structure

```
PlantPilot/
├── firmware/
│   └── PlantPilot_ESP32.ino      # Relays, sensors, WS, schedules, SoftAP setup
├── app/
│   └── src/main/
│       ├── java/com/plantpilot/
│       │   ├── MainActivity.kt
│       │   ├── data/
│       │   │   ├── HardwareRepository.kt   # WebSocket singleton + telemetry/events/logs
│       │   │   ├── SettingsManager.kt      # DataStore persistence
│       │   │   └── PlantPilotRepository.kt # Retrofit REST client
│       │   ├── network/ApiService.kt       # Retrofit interface
│       │   ├── model/                       # Plant, schedule, event, device state, mock data
│       │   ├── viewmodel/                   # PlantPilotViewModel, PumpTestViewModel
│       │   ├── navigation/NavGraph.kt       # Screen routes + bottom nav
│       │   ├── ui/components/               # Cards, sheets, rings, animations
│       │   ├── ui/screens/                  # Home, Plants, Detail, History, Settings, Onboarding, PumpTest
│       │   └── ui/theme/                    # Neon green / dark theme
│       └── res/
├── gradle/libs.versions.toml        # Version catalog
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Status & Roadmap

**Working today:**
- WebSocket + REST networking, state-machine connection handling with exponential backoff, auto-reconnect, and heartbeat.
- Background-safe sync: wake lock + self-reconnect so the link survives app backgrounding and process restarts.
- Configuration sync, scheduling, auto-watering, manual watering.
- Real-time history persistence, low-water notifications.
- Pump hardware diagnostics terminal.
- Sensor calibration: live raw ADC streaming (`CAL_STREAM_ON/OFF`) with dry/wet persistence to the device over sync.

**In progress / next:**
- [ ] Nothing planned right now — sensor calibration, background sync, and power-saving are all wired end-to-end.

---

## License

[MIT](LICENSE) © 2026 Magnum
