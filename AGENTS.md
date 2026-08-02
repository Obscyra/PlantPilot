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
- Connection resilience: fixed 2s retry (`RETRY_DELAY_MS`, no backoff),
  `_isConnecting` true only inside `openSocket()` (so the chip shows
  "Disconnected" during the retry wait), OkHttp `pingInterval(20s)` keepalive,
  and a heartbeat that sends `STATUS` every 5s but only force-closes
  ("ESP32 not responding") after `MAX_MISSED_PROBES` (3) consecutive unanswered
  probes — a slow-but-alive link is never aggressively torn down.
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
- `firmware/PlantPilot_ESP32_Test.ino` is the entire firmware (single .ino,
  Arduino IDE: ESPAsyncWebServer, AsyncTCP, ArduinoJson). Not built here;
  the user compiles it in their own Arduino IDE. Keep it single-file
  Arduino-compatible.

## Docs
- `README.md` is useful but can lag the code (treat code as source of truth).