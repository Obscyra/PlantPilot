# AGENTS.md

Android (Kotlin/Compose) companion app + ESP32 firmware for a 4-pump watering rig.
Real-time comms = OkHttp WebSocket; config sync / water-now = Retrofit REST.

## Build & verify (CRITICAL)
- Do not build, run Gradle, or compile/flash anything. No JDK exists in this
  shell and the user compiles/man flashes manually. Never push or commit APKs.
- No unit/instrument tests exist; verify by reading/reasoning, then user does
  device builds. README's `./gradlew assembleDebug` -> APK in
  `app/build/outputs/apk/`.
- Commit in conventional style (`fix:`, `docs:`, `feat:`).

## Secrets
- `signing.properties` at repo root holds the release signing keystore creds
  (`STORE_FILE/PASSWORD`, `KEY_ALIAS/PASSWORD`) and is NOT in `.gitignore`.
  Never `git add`/commit/send it. `app/build.gradle.kts` reads it to sign
  `release` when present.

## Architecture gotchas
- `data/HardwareRepository.kt` is an object (singleton) owning the WebSocket and
  is the single source of truth for connectivity (`isConnected`/`isConnecting`
  StateFlows). Views derive connection state from these, never from cached
  `_deviceState`/mocks.
- Connection state is a sealed class (`ConnectionState`) with five states:
  `Connected`, `Connecting`, `Reconnecting`, `Disconnected`, `Failed`. Views
  derive state from the single `connectionState` StateFlow. Command guards read
  `connectionState` directly; visual consumers read `displayConnectionState`,
  which delays `Reconnecting` by 500ms so the chip doesn't flash during short
  glitches.
- Command guards: `canSendCommands = state == Connected` (strict);
  `canDisplayLastKnownData = Connected || Reconnecting` (permissive).
- Reconnect uses exponential backoff: base 2s (`BACKOFF_BASE_MS`), multiplier
  1.7x (`BACKOFF_MULTIPLIER`), cap 30s (`BACKOFF_MAX_MS`), ±20% jitter
  (`BACKOFF_JITTER`), max 8 attempts (`MAX_RETRY_ATTEMPTS`) before `Failed`.
  The debounce before reconnect is `DISCONNECT_DEBOUNCE_MS` (10s) guarded by
  `disconnectDebounceJob?.isActive` (no boolean flag).
- Heartbeat sends `STATUS` every `HEARTBEAT_INTERVAL_MS` (5s) but only
  force-closes ("ESP32 not responding") after `MAX_MISSED_PROBES` (3)
  consecutive unanswered probes — a slow-but-alive link is never aggressively
  torn down. Stale sockets are guarded by `isCurrent(webSocket)` identity check.
- Pump state: read from `ok` ACKs and `STATUS` replies ONLY, not `telemetry`
  frames (telemetry overwrites in-flight toggles). `pumps[]` is 0-indexed but
  app `motorNumber` is 1-indexed.
- Stale sockets guarded by `isCurrent(webSocket)` identity check.
- Two-way config sync (`PlantPilotViewModel`): a device config applies to a
  plant only when the device is newer (`dev.last_modified*1000 >
  plant.lastUpdated`) so in-app edits are never clobbered. `minIntervalHours`
  gates AUTO watering only.
- Background sync: foreground `SyncService`; cadence 1s foreground / 3s
  background via `SYNC_MODE <sec>`; fully-closed app disconnects the WS and
  rehydrates from a persisted telemetry snapshot (debounced <= 1 write / 2s).
- Telemetry `motors[]` already carries full per-plant config (version,
  last_modified, min_interval_hours, calibration_dry/wet, schedules) - live
  pushes keep config synced without extra HTTP.
- DataStore: keep the focus-guarded local-draft pattern in `SettingsScreen`
  (commit on focus-loss, else fields self-reset / "prefill while editing");
  hydrate plants once with `.first()` so periodic telemetry writes don't clobber
  live moisture.

## Firmware
- `firmware/PlantPilot_ESP32.ino` is the entire firmware (single .ino,
  Arduino IDE: ESPAsyncWebServer, AsyncTCP, ArduinoJson). Not built here;
  the user compiles it in their own Arduino IDE. Keep it single-file
  Arduino-compatible.
- Heavy work must never run on the AsyncTCP event-task stack: `READ_SENSORS`
  sets `pendingSensorRead` and `loop()` runs `broadcastTelemetry()` on the main
  loop stack instead (a stack overflow here was resetting the ESP32 on connect).
  STATUS replies use a fixed `char[64]` buffer, WS fragment accumulation
  `cmd.reserve(len)`, and every command is echoed to `[WS] RX`/`[WS] TX`.
- Boot logs `esp_reset_reason()` via an enum switch (do NOT use
  `esp_reset_reason_str()` — it's missing on some Arduino cores). The web UI
  Serial Output screen shows the same RX/TX/SYS/ERR traffic as the Arduino IDE
  serial monitor.

## Docs
- `README.md` is useful but can lag the code (treat code as source of truth).