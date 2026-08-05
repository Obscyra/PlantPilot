# AGENTS.md

## Repo layout
Two independent components; changes rarely touch both:
- `app/` — Android app. Kotlin, Jetpack Compose (Material 3), MVVM. Single Gradle module `:app`.
- `firmware/PlantPilot_ESP32.ino` — ESP32 firmware (single file). Built/flashed with Arduino IDE (ESPAsyncWebServer + AsyncTCP + ArduinoJson; ESP32 board package). NOT built by Gradle.

No tests and no CI exist. The only compile check is `./gradlew assembleDebug` (APK at `app/build/outputs/apk/debug/app-debug.apk`). Don't invent `./gradlew test`/`lint` — neither is configured.

## Build notes
- Version catalog: `gradle/libs.versions.toml` (Gradle 9.6.1, AGP 8.7.3, Kotlin 2.1.0, compileSdk 35, minSdk 26, Java 11).
- `local.properties` points to a Windows SDK path (`C:\Users\magnum\...`); it only resolves on the Windows/Android Studio side. On WSL/Linux, fix `sdk.dir` first.
- Release signing reads `signing.properties` (untracked, placeholder values; minify disabled). Debug builds don't need it.
- Bump `versionCode`/`versionName` in `app/build.gradle.kts`, update `changelog.md`, and drop the APK in `releases/` when cutting a version.

## App architecture (v2.0 refactor — don't "fix" back)
- `HardwareRepository` is a `class` implementing `HardwareConnection`, not a singleton object. `PlantPilotApp` owns the singletons (`hardwareConnection`, `repository`); components resolve them via `(application as PlantPilotApp)`.
- `PlantPilotViewModel` delegates to managers: `SyncCoordinator`, `HistoryManager`, `TelemetryProcessor`, `PlantConfigManager`.
- Screens must use `ConnectionStateHelper` (`canSendCommands()`, `canDisplayLastKnownData()`, `debouncedConnectionState()`) for connection logic — no inline duplication.
- Persistence is DataStore Preferences; fresh installs fall back to `MockData`.
- `HardwareRepository.onConnectionLost()` has a `wateringInProgress` guard + `WATERING_DEBOUNCE_MS` (30s) so relay switching during watering doesn't show a bogus "Reconnecting" state.

## Protocol gotchas (app ↔ firmware)
- Pump states come from `ok` ACKs / `STATUS` replies, NOT telemetry. `pumps` arrays are 0-indexed; `motorNumber` in watering events is 1-indexed.
- `motors[]` config only rides in telemetry on config-signature change or every 30s.
- WS commands: `STATUS`, `READ_SENSORS`, `SYNC_MODE <sec>`, `CAL_STREAM_ON/OFF`, `PUMP{A–D}_ON/OFF`, `PUMP_ALL_OFF`, `RESET_CONFIG`.
- REST: `/api/status`, `/api/config`, `/api/sync`, `/api/motor/{1–4}/water_now`, `/api/calibrate`, `/api/wifi_status`. Default device address `http://plantpilot.local/` (mDNS), overridable in Settings.

## Firmware notes
- Relays are active-low (`RELAY_ON = LOW`). GPIO 25/26/27/14 = pumps, 34/35/32/33 = soil sensors, GPIO 2 = status LED.
- Per-sensor dry/wet calibration persists to NVS; auto-watering plants re-read their sensor every 10 min only.

## Conventions
- Conventional commits: `fix:`, `feat:`, `refactor:`, `docs:` (see git history).
- `README.md` and `changelog.md` are the source of truth for protocol details — keep them in sync when behavior changes.
