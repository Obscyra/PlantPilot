/**
 * PlantPilot — Production Hardware Firmware
 *
 * Lightweight SoftAP Setup (No BLE).
 * Hardware: 4-Channel Relay (Active Low) on GPIO 25, 26, 27, 14.
 */

#include <WiFi.h>
#include <ESPAsyncWebServer.h>
#include <AsyncTCP.h>
#include <ArduinoJson.h>
#include <Preferences.h>
#include <time.h>
#include <ESPmDNS.h>
#include <DNSServer.h>

// --- CONFIGURATION ---
const char* HOSTNAME = "plantpilot";
const char* SETUP_SSID = "PlantPilot-Setup";
const int DEFAULT_ML_PER_SECOND = 10; // Fallback if per-pump value is 0
const int MAX_ML_PER_SECOND = 100;    // Sanity cap
const int MAX_WATERING_ML = 10000;    // Max ml per watering cycle

// --- ONBOARD LED STATUS ---
// WROOM-32 dev kits expose a built-in blue LED on GPIO 2.
// Solid = WiFi connected, fast blink = connecting/lost, slow blink = setup mode.
#define STATUS_LED GPIO_NUM_2
#define LED_BLINK_CONNECTING_MS 250
#define LED_BLINK_SETUP_MS      700

// NTP & Time
const char* NTP_SERVER = "pool.ntp.org";
const long  GMT_OFFSET_SEC = 21600; // UTC+6
const int   DST_OFFSET_SEC = 0;

// Time Persistence
struct TimeSyncData {
    unsigned long epoch;
    unsigned long syncMillis;
};
bool timeSet = false;
unsigned long bootEpochOffset = 0; // epoch at current boot, computed once

// Cached WiFi SSID (avoids temp String allocation per telemetry frame)
String cachedWifiSsid = "";

// WiFi Resilience
unsigned long lastWifiRetry = 0;
uint8_t lastWiFiReason = 0;
bool wasConnected = false;
unsigned long restartTime = 0;
bool pendingRestart = false;
bool setupModeActive = false;

// --- DATA STRUCTURES ---
struct HistoryEntry {
    int motor;
    int amount;
    char trigger[12];
    unsigned long epoch;
    int moistureAfter;
    bool isValid;
};
HistoryEntry wateringHistory[10];
int historyWriteIdx = 0;

struct WateringSchedule {
    int hour;
    int minute;
};

struct MotorSettings {
    bool isEnabled;
    bool autoMode;
    int amountMl;
    int moistureThreshold;
    int calibrationDry;   // raw ADC value in open air (driest point)
    int calibrationWet;   // raw ADC value submerged in water (wettest point)
    int version;
    unsigned long lastModified; // epoch seconds of last config change (two-way sync)
    int minIntervalHours; // min hours between auto waterings (0 = no limit)
    WateringSchedule schedules[5];
    int scheduleCount;
    unsigned long lastAutoWaterEpoch; // Last auto-watering completion (epoch)
    int mlPerSecond;       // Per-pump flow rate (0 = use default)
    int maxRuntimeMinutes; // Failsafe: max minutes pump can run (0 = no limit)
    bool stopOnDisconnect; // Stop pump when WebSocket client disconnects
};

struct Relay {
    int pin;
    int sensorPin;
    bool isOn;
    unsigned long startTime;
    unsigned long duration;
    const char* name;
    const char* lastTriggerSource;
    int lastAmountMl;
};

#define RELAY_ON  LOW
#define RELAY_OFF HIGH

Relay pumps[4] = {
    {25, 34, false, 0, 0, "Pump 1"},
    {26, 35, false, 0, 0, "Pump 2"},
    {27, 32, false, 0, 0, "Pump 3"},
    {14, 33, false, 0, 0, "Pump 4"}
};

MotorSettings motorConfigs[4];
int soilMoisture[4] = {50, 50, 50, 50};
int rawSoilCache[4] = {0, 0, 0, 0};
int waterLevel = 100;
// Epoch of the last completed timed watering per pump; used to enforce the
// per-plant min auto-water interval. Reset to 0 on boot (no gating until the
// first completion). Uses millis() for internal robustness against NTP issues.
unsigned long lastAutoWaterTime[4] = {0, 0, 0, 0};

// Sensor read cadence. Raw ADC readings are only refreshed every 10 minutes
// for the dashboard; opening the calibration sheet forces a 1s realtime stream.
const unsigned long SENSOR_READ_INTERVAL_MS = 600000UL; // 10 min
bool calibrationStreamActive = false;
unsigned long lastSensorSent = 0;
// Set by the WS command handler (AsyncTCP task) to ask loop() to run the heavy
// broadcastTelemetry() on the main loop stack instead of the small async task
// stack, preventing a stack overflow when a command triggers a full sensor read.
volatile bool pendingSensorRead = false;

// Staggered pump update state to prevent network blocking and power surges
struct StartReq {
    bool pending;
    int amount;
    const char* source;
};
StartReq startQueue[4] = { {false, 0, ""}, {false, 0, ""}, {false, 0, ""}, {false, 0, ""} };
unsigned long lastGlobalStart = 0;
const int STAGGER_INTERVAL_MS = 150;

bool staggeredStopPending = false;
int nextStaggeredStop = 0;
unsigned long lastStaggerTime = 0;

void requestPumpStart(int id, int amount, const char* src) {
    if (id < 0 || id >= 4 || pumps[id].isOn) return;
    startQueue[id].pending = true;
    startQueue[id].amount = amount;
    startQueue[id].source = src;
}

// Telemetry cadence requested by the app via SYNC_MODE. Foreground -> 1s,
// background -> 3s, no clients -> 60s. Reset to 3s on boot; the app re-sends
// its mode whenever it (re)connects.
unsigned long streamCadenceMs = 3000UL;

Preferences preferences;
AsyncWebServer server(80);
AsyncWebSocket ws("/ws");
DNSServer dnsServer;

// --- HELPERS ---

void printWiFiDiagnostics() {
    Serial.printf("[WIFI] Status: %d, Reason: %d\n", (int)WiFi.status(), (int)lastWiFiReason);
}

// --- TIME MANAGER ---

void saveTimeSync(unsigned long epoch) {
    TimeSyncData data = { epoch, millis() };
    preferences.begin("time", false);
    size_t written = preferences.putBytes("sync", &data, sizeof(data));
    preferences.end();
    if (written == 0) Serial.println("[TIME] WARNING: NVS write failed for time sync");
    bootEpochOffset = epoch - millis() / 1000;
    timeSet = true;
    Serial.printf("[TIME] Saved Sync: %lu at %lu ms\n", epoch, data.syncMillis);
}

// In-RAM time calculation — no NVS access per call.
// bootEpochOffset is set once at boot from persisted data, then updated by
// saveTimeSync() on NTP or app sync.
unsigned long getNow() {
    if (bootEpochOffset == 0) return 0;
    return bootEpochOffset + millis() / 1000;
}

void broadcastTelemetry() {
    if (ws.count() == 0) return;
    JsonDocument doc;
    doc["type"] = "telemetry";
    doc["water_level"] = waterLevel;
    doc["ntp_synced"] = timeSet;

    // Track max loop time for diagnostics
    static unsigned long maxLoopMs = 0;
    static unsigned long lastLoopReset = 0;
    unsigned long now = millis();
    if (now - lastLoopReset > 60000) {
        maxLoopMs = 0;
        lastLoopReset = now;
    }
    doc["loop_ms_max"] = maxLoopMs;

    // Refresh raw ADC readings on a 10-minute cadence when idle, but on every
    // broadcast while the app is connected (foreground 1s / background 3s) or
    // the calibration sheet is streaming. A zero lastSensorSent (boot /
    // READ_SENSORS command) forces an immediate read.
    if (calibrationStreamActive || streamCadenceMs <= 3000UL || lastSensorSent == 0 || now - lastSensorSent >= SENSOR_READ_INTERVAL_MS) {
        lastSensorSent = now;
        for (int i = 0; i < 4; i++) {
            rawSoilCache[i] = readRawSensor(i);
            soilMoisture[i] = rawToPercent(i, rawSoilCache[i]);
        }
    }

    JsonArray soil = doc["soil"].to<JsonArray>();
    JsonArray rawSoil = doc["raw_soil"].to<JsonArray>();
    for (int i = 0; i < 4; i++) {
        rawSoil.add(rawSoilCache[i]);
        soil.add(soilMoisture[i]);
    }

    doc["wifi_rssi"] = WiFi.RSSI();
    // Use cached SSID to avoid temp String allocation every frame
    if (cachedWifiSsid.length() == 0 && WiFi.status() == WL_CONNECTED) {
        cachedWifiSsid = WiFi.SSID();
    }
    doc["wifi_ssid"] = cachedWifiSsid;
    doc["uptime_sec"] = millis() / 1000;
    doc["free_heap"] = ESP.getFreeHeap();
    doc["epoch"] = getNow();

    JsonArray pumpsState = doc["pumps"].to<JsonArray>();
    for (int i = 0; i < 4; i++) {
        pumpsState.add(pumps[i].isOn);
    }

    // Full per-motor config rides along so the app stays in sync without a
    // separate request while the WebSocket is open (version/last_modified let
    // the app decide which side is newer).
    JsonArray motors = doc["motors"].to<JsonArray>();
    for (int i = 0; i < 4; i++) {
        JsonObject m = motors.add<JsonObject>();
        m["id"] = i + 1;
        m["version"] = motorConfigs[i].version;
        m["last_modified"] = motorConfigs[i].lastModified;
        m["mode"] = !motorConfigs[i].isEnabled ? "off"
                    : (motorConfigs[i].autoMode ? "auto" : "scheduled");
        m["amount_ml"] = motorConfigs[i].amountMl;
        m["threshold"] = motorConfigs[i].moistureThreshold;
        m["min_interval_hours"] = motorConfigs[i].minIntervalHours;
        m["calibration_dry"] = motorConfigs[i].calibrationDry;
        m["calibration_wet"] = motorConfigs[i].calibrationWet;
        m["last_watered"] = motorConfigs[i].lastAutoWaterEpoch;
        m["ml_per_sec"] = motorConfigs[i].mlPerSecond > 0
                         ? motorConfigs[i].mlPerSecond : DEFAULT_ML_PER_SECOND;
        m["max_runtime_minutes"] = motorConfigs[i].maxRuntimeMinutes;
        m["stop_on_disconnect"] = motorConfigs[i].stopOnDisconnect;
        JsonArray sched = m["schedules"].to<JsonArray>();
        for (int j = 0; j < motorConfigs[i].scheduleCount; j++) {
            JsonObject s = sched.add<JsonObject>();
            s["hour"] = motorConfigs[i].schedules[j].hour;
            s["minute"] = motorConfigs[i].schedules[j].minute;
        }
    }

    // Pre-size the String to reduce heap fragmentation
    size_t jsonSize = measureJson(doc);
    String msg;
    msg.reserve(jsonSize + 16);
    serializeJson(doc, msg);
    ws.textAll(msg);
}

String getLocalTimeStr() {
    unsigned long nowEpoch = getNow();
    if (nowEpoch < 1600000000) return "00:00:00";
    time_t localEpoch = (time_t)(nowEpoch + GMT_OFFSET_SEC);
    struct tm ti;
    gmtime_r(&localEpoch, &ti);
    char buf[10];
    sprintf(buf, "%02d:%02d:%02d", ti.tm_hour, ti.tm_min, ti.tm_sec);
    return String(buf);
}

void syncWithNtp() {
    if (WiFi.status() != WL_CONNECTED) return;
    configTime(GMT_OFFSET_SEC, DST_OFFSET_SEC, NTP_SERVER);
    struct tm timeinfo;
    if (getLocalTime(&timeinfo)) {
        time_t now;
        time(&now);
        unsigned long epoch = (unsigned long)now;
        // Only persist if time actually changed (reduces NVS writes)
        unsigned long prev = getNow();
        if (prev == 0 || (epoch > prev ? (epoch - prev) > 2 : (prev - epoch) > 2)) {
            saveTimeSync(epoch);
        }
        Serial.println("[TIME] NTP Sync Successful");
    }
}

// --- HARDWARE ---

void initRelays() {
    Serial.println("[HARDWARE] Initializing Pins...");
    for (int i = 0; i < 4; i++) {
        pinMode(pumps[i].pin, OUTPUT);
        digitalWrite(pumps[i].pin, RELAY_OFF);
        pinMode(pumps[i].sensorPin, INPUT);
    }
}

void initStatusLed() {
    pinMode(STATUS_LED, OUTPUT);
    digitalWrite(STATUS_LED, LOW);
}

// Non-blocking WiFi status LED:
//  - Solid ON  : STA connected (WL_CONNECTED)
//  - Fast blink: connecting to / disconnected from the AP
//  - Slow blink: SoftAP setup mode (no credentials yet)
void updateStatusLed() {
    static unsigned long lastToggle = 0;
    static bool ledOn = false;

    if (WiFi.status() == WL_CONNECTED) {
        digitalWrite(STATUS_LED, HIGH);
        lastToggle = 0;
        ledOn = true;
        return;
    }

    unsigned long interval = setupModeActive ? LED_BLINK_SETUP_MS : LED_BLINK_CONNECTING_MS;
    unsigned long now = millis();
    if (lastToggle == 0 || (now - lastToggle) >= interval) {
        lastToggle = now;
        ledOn = !ledOn;
        digitalWrite(STATUS_LED, ledOn ? HIGH : LOW);
    }
}

// Averages several ADC samples to suppress ESP32 analog noise.
int readRawSensor(int index) {
    if (index < 0 || index >= 4) return 0;
    long total = 0;
    const int SAMPLES = 4;
    for (int s = 0; s < SAMPLES; s++) {
        total += analogRead(pumps[index].sensorPin);
        delay(2);
    }
    return (int)(total / SAMPLES);
}

// Maps a raw ADC reading to 0..100% using the sensor's stored dry/wet points.
int rawToPercent(int index, int raw) {
    if (index < 0 || index >= 4) return 0;
    int dry = motorConfigs[index].calibrationDry;
    int wet = motorConfigs[index].calibrationWet;
    // Guard against uncalibrated / inverted values.
    if (dry <= wet) return 50;
    int percent = map(raw, dry, wet, 0, 100);
    return constrain(percent, 0, 100);
}

int readMoisture(int index) {
    if (index < 0 || index >= 4) return 0;
    return rawToPercent(index, readRawSensor(index));
}

void triggerPump(int index, int amountMl, const char* source = "manual") {
    if (index < 0 || index >= 4 || pumps[index].isOn) return;
    amountMl = constrain(amountMl, 0, MAX_WATERING_ML);

    int durationMs = 0;
    if (amountMl > 0) {
        int rate = motorConfigs[index].mlPerSecond > 0
                 ? motorConfigs[index].mlPerSecond
                 : DEFAULT_ML_PER_SECOND;
        rate = constrain(rate, 1, MAX_ML_PER_SECOND);
        durationMs = ((long)amountMl * 1000) / rate;
        if (durationMs < 500) durationMs = 500;
    }

    pumps[index].isOn = true;
    pumps[index].startTime = millis();
    pumps[index].duration = durationMs; // 0 means indefinite
    pumps[index].lastTriggerSource = source;
    pumps[index].lastAmountMl = amountMl;

    digitalWrite(pumps[index].pin, RELAY_ON);
    if (durationMs > 0) {
        Serial.printf("[%s] [PUMP] %s ON for %d ml (%d ms) from %s\n", getLocalTimeStr().c_str(), pumps[index].name, amountMl, durationMs, source);
    } else {
        Serial.printf("[%s] [PUMP] %s ON (Indefinite) from %s\n", getLocalTimeStr().c_str(), pumps[index].name, source);
    }
}

void stopPump(int index) {
    if (index < 0 || index >= 4 || !pumps[index].isOn) return;

    pumps[index].isOn = false;
    digitalWrite(pumps[index].pin, RELAY_OFF);
    Serial.printf("[%s] [PUMP] %s OFF\n", getLocalTimeStr().c_str(), pumps[index].name);

    int moistureAfter = readMoisture(index);

    // Only log real timed waterings (amount > 0) in history.
    // Diagnostic test toggles (indefinite, amount=0) are excluded so they
    // don't evict real watering entries the app needs for sync catch-up.
    if (pumps[index].lastAmountMl > 0) {
        wateringHistory[historyWriteIdx].motor = index + 1;
        wateringHistory[historyWriteIdx].amount = pumps[index].lastAmountMl;
        strncpy(wateringHistory[historyWriteIdx].trigger, pumps[index].lastTriggerSource, 11);
        wateringHistory[historyWriteIdx].trigger[11] = '\0';
        wateringHistory[historyWriteIdx].epoch = getNow();
        wateringHistory[historyWriteIdx].moistureAfter = moistureAfter;
        wateringHistory[historyWriteIdx].isValid = true;
        historyWriteIdx = (historyWriteIdx + 1) % 10;
    }

    // Notify app of completion only for real timed waterings.
    // Diagnostic test toggles (indefinite, amount=0) must NOT emit
    // watering_finished or the app logs a false history entry.
    if (ws.count() > 0 && pumps[index].lastAmountMl > 0) {
        JsonDocument doc;
        doc["type"] = "watering_finished";
        doc["motor"] = index + 1;
        doc["amount_ml"] = pumps[index].lastAmountMl;
        doc["trigger"] = pumps[index].lastTriggerSource;
        doc["epoch"] = getNow();
        doc["soil_after"] = moistureAfter;

        String msg;
        serializeJson(doc, msg);
        ws.textAll(msg);
    }

    // Record the completion time so auto watering respects minIntervalHours.
    // Only real "auto" waterings gate the next auto trigger.
    if (pumps[index].lastTriggerSource != nullptr && strcmp(pumps[index].lastTriggerSource, "auto") == 0) {
        lastAutoWaterTime[index] = millis();
        motorConfigs[index].lastAutoWaterEpoch = getNow();
        saveMotorConfig(index + 1);
    }

    pumps[index].startTime = 0;
    pumps[index].duration = 0;
}

void updatePumps() {
    unsigned long now = millis();
    for (int i = 0; i < 4; i++) {
        if (!pumps[i].isOn) continue;
        unsigned long elapsed = now - pumps[i].startTime;
        // Auto-stop if timed duration expired
        if (pumps[i].duration > 0 && elapsed >= pumps[i].duration) {
            stopPump(i);
        }
        // Failsafe: force-stop if maxRuntimeMinutes exceeded
        else if (motorConfigs[i].maxRuntimeMinutes > 0 &&
                 elapsed >= (unsigned long)motorConfigs[i].maxRuntimeMinutes * 60000UL) {
            Serial.printf("[%s] [PUMP] FAILSAFE: %s exceeded max runtime of %d min\n",
                getLocalTimeStr().c_str(), pumps[i].name, motorConfigs[i].maxRuntimeMinutes);
            stopPump(i);
        }
    }
}

// --- PERSISTENCE ---

void saveMotorConfig(int id) {
    char key[16];
    sprintf(key, "motor%d", id);
    preferences.begin("plantpilot", false);
    size_t written = preferences.putBytes(key, &motorConfigs[id-1], sizeof(MotorSettings));
    preferences.end();
    if (written == 0) Serial.printf("[NVS] WARNING: write failed for Pump %d\n", id);
    else Serial.printf("[%s] [NVS] Saved Pump %d configuration\n", getLocalTimeStr().c_str(), id);
}

void loadConfigs() {
    preferences.begin("plantpilot", true);
    for (int i = 1; i <= 4; i++) {
        char key[16]; sprintf(key, "motor%d", i);
        memset(&motorConfigs[i-1], 0, sizeof(MotorSettings));
        if (preferences.isKey(key)) {
            preferences.getBytes(key, &motorConfigs[i-1], sizeof(MotorSettings));
            // Guard against fields zeroed by older NVS blobs / memset.
            if (motorConfigs[i-1].calibrationDry <= 0) motorConfigs[i-1].calibrationDry = 4095;
            if (motorConfigs[i-1].calibrationWet <= 0) motorConfigs[i-1].calibrationWet = 1000;
            // Fix inverted calibration if persisted incorrectly
            if (motorConfigs[i-1].calibrationDry <= motorConfigs[i-1].calibrationWet) {
                motorConfigs[i-1].calibrationDry = 4095;
                motorConfigs[i-1].calibrationWet = 1000;
            }
            // Clamp schedule count (protects against corrupted NVS blobs)
            motorConfigs[i-1].scheduleCount = constrain(motorConfigs[i-1].scheduleCount, 0, 5);
            // Clamp mlPerSecond to sane range
            motorConfigs[i-1].mlPerSecond = constrain(motorConfigs[i-1].mlPerSecond, 0, MAX_ML_PER_SECOND);
        } else {
            motorConfigs[i-1].isEnabled = true;
            motorConfigs[i-1].autoMode = false;
            motorConfigs[i-1].amountMl = 50;
            motorConfigs[i-1].moistureThreshold = 30;
            motorConfigs[i-1].calibrationDry = 4095;
            motorConfigs[i-1].calibrationWet = 1000;
            motorConfigs[i-1].version = 0;
            motorConfigs[i-1].lastModified = 0;
            motorConfigs[i-1].scheduleCount = 0;
            motorConfigs[i-1].mlPerSecond = DEFAULT_ML_PER_SECOND;
            motorConfigs[i-1].maxRuntimeMinutes = 1;
            motorConfigs[i-1].stopOnDisconnect = false;
        }
    }
    preferences.end();

    // Boot log proving persisted schedules/configs resume even without the app.
    for (int i = 0; i < 4; i++) {
        Serial.printf("[%s] [NVS] Pump %d: enabled=%d auto=%d sched=%d v=%d lm=%lu\n",
            getLocalTimeStr().c_str(), i + 1,
            motorConfigs[i].isEnabled, motorConfigs[i].autoMode,
            motorConfigs[i].scheduleCount, motorConfigs[i].version,
            motorConfigs[i].lastModified);
    }
}

// --- SCHEDULING ---

int lastTriggeredMin[4] = {-1, -1, -1, -1};
int lastTriggeredHour[4] = {-1, -1, -1, -1};

void checkSchedules() {
    unsigned long nowEpoch = getNow();
    if (nowEpoch < 1600000000) return;

    time_t localEpoch = (time_t)(nowEpoch + GMT_OFFSET_SEC);
    struct tm ti;
    gmtime_r(&localEpoch, &ti);

    int curHr = ti.tm_hour;
    int curMin = ti.tm_min;

    for (int i = 0; i < 4; i++) {
        if (!motorConfigs[i].isEnabled || motorConfigs[i].autoMode) continue;
        if (lastTriggeredMin[i] == curMin && lastTriggeredHour[i] == curHr) continue;

        for (int j = 0; j < motorConfigs[i].scheduleCount; j++) {
            WateringSchedule &s = motorConfigs[i].schedules[j];
            if (s.hour == curHr && s.minute == curMin) {
                Serial.printf("[%s] [SCHED] Queuing %s at %02d:%02d\n", getLocalTimeStr().c_str(), pumps[i].name, curHr, curMin);
                requestPumpStart(i, motorConfigs[i].amountMl, "scheduled");
                lastTriggeredMin[i] = curMin;
                lastTriggeredHour[i] = curHr;
            }
        }
    }
}

void checkAutoWatering() {
    unsigned long nowMs = millis();
    unsigned long nowEpoch = getNow();
    for (int i = 0; i < 4; i++) {
        if (!motorConfigs[i].isEnabled || !motorConfigs[i].autoMode) continue;
        // Skip if pump is already running or start is queued (avoids redundant ADC reads)
        if (pumps[i].isOn || startQueue[i].pending) continue;

        // Respect the per-plant min auto-water interval.
        // 0 hours defaults to a 10-second safety gap to prevent rapid loops.
        unsigned long minGapMs = 10000UL;
        if (motorConfigs[i].minIntervalHours > 0) {
            unsigned long minGapSec = (unsigned long)motorConfigs[i].minIntervalHours * 3600UL;

            // 1. Check persistent epoch (survives reboot)
            if (nowEpoch != 0 && motorConfigs[i].lastAutoWaterEpoch != 0) {
                if (nowEpoch - motorConfigs[i].lastAutoWaterEpoch < minGapSec) continue;
            }

            minGapMs = minGapSec * 1000UL;
        }

        // 2. Check session-based millis (protects against rapid loops even if NTP is broken)
        if (lastAutoWaterTime[i] != 0 && (nowMs - lastAutoWaterTime[i]) < minGapMs) continue;

        // Use cached moisture from telemetry instead of reading sensor again
        int moisture = soilMoisture[i];
        if (moisture < motorConfigs[i].moistureThreshold) {
            Serial.printf("[%s] [AUTO] Low moisture on %s: %d%% < %d%%. Queuing start.\n", getLocalTimeStr().c_str(), pumps[i].name, moisture, motorConfigs[i].moistureThreshold);
            requestPumpStart(i, motorConfigs[i].amountMl, "auto");
        }
    }
}

// --- SETUP MODE (SOFT AP) ---

// --- SETUP MODE (SOFT AP) ---
// Generate a random 8-char password for the setup AP (printed to Serial)
char setupApPassword[9];
void generateSetupPassword() {
    const char charset[] = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no ambiguous chars
    for (int i = 0; i < 8; i++) {
        setupApPassword[i] = charset[random(0, sizeof(charset) - 1)];
    }
    setupApPassword[8] = '\0';
}

const char SETUP_HTML[] PROGMEM = R"rawliteral(<!DOCTYPE html><html><head><title>PlantPilot Setup</title><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font-family:sans-serif;background:#121212;color:#B6FF3C;padding:20px;text-align:center}select,input{width:100%;padding:12px;margin:10px 0;border:1px solid #B6FF3C;background:#1e1e1e;color:white;border-radius:8px;box-sizing:border-box}.show-pass{margin:5px 0;color:#aaa;font-size:.9em;display:flex;align-items:center;cursor:pointer}.show-pass input{width:auto;margin-right:10px}button{background:#B6FF3C;color:#121212;border:none;padding:15px;width:100%;font-weight:bold;border-radius:8px;cursor:pointer;margin-top:10px}.refresh-btn{background:#333;color:#B6FF3C;border:1px solid #B6FF3C;margin-bottom:20px}</style><script>function togglePass(){var x=document.getElementById("pass");x.type=x.type==="password"?"text":"password"}</script></head><body><h1>PlantPilot Setup</h1><p>Connect your ESP32 to WiFi</p><button class="refresh-btn" onclick="location.reload()">Refresh Networks</button><form action="/save" method="POST"><label style="display:block;text-align:left">Select WiFi:</label><select name="ssid" required>{{SCAN_RESULTS}}</select><label style="display:block;text-align:left;margin-top:10px">Password:</label><input type="password" id="pass" name="pass" placeholder="Enter Password"><div class="show-pass"><input type="checkbox" onclick="togglePass()"> Show Password</div><label style="display:block;text-align:left;margin-top:10px">Device Password:</label><input type="password" id="devpass" name="devpass" placeholder="Enter device password shown on Serial"><button type="submit">Save and Connect</button></form></body></html>)rawliteral";

const char CONNECTING_HTML[] PROGMEM = R"rawliteral(<!DOCTYPE html><html><head><title>PlantPilot Connected</title><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font-family:sans-serif;background:#121212;color:#B6FF3C;padding:20px;text-align:center}.loader{border:4px solid #1e1e1e;border-top:4px solid #B6FF3C;border-radius:50%;width:40px;height:40px;animation:spin 2s linear infinite;margin:20px auto}@keyframes spin{0%{transform:rotate(0deg)}100%{transform:rotate(360deg)}}#status{font-size:1.2em;margin-bottom:10px}.host{background:#1e1e1e;border:1px solid #333;padding:10px;border-radius:8px;margin:10px 0;color:white;font-weight:bold}#ip-box{background:#1e1e1e;border:1px solid #B6FF3C;padding:15px;border-radius:8px;margin:20px 0;display:none}.note{color:#aaa;font-size:.9em;margin-bottom:8px}#ip{font-size:1.5em;font-weight:bold;color:white;display:block;margin-bottom:10px}button{background:#B6FF3C;color:#121212;border:none;padding:10px 20px;font-weight:bold;border-radius:5px;cursor:pointer}#countdown{margin-top:20px;color:#aaa;display:none}</style><script>let connected=false;function checkStatus(){fetch('/api/wifi_status').then(r=>r.json()).then(data=>{if(data.status===3&&data.ip!=="0.0.0.0"){document.getElementById("status").innerText="Connected!";document.getElementById("ip").innerText=data.ip;document.getElementById("ip-box").style.display="block";document.querySelector(".loader").style.display="none";document.getElementById("countdown").style.display="block";if(!connected){connected=true;startCountdown()}}else{setTimeout(checkStatus,1000)}}).catch(()=>setTimeout(checkStatus,1000))}function copyIp(){const ip=document.getElementById("ip").innerText;navigator.clipboard.writeText(ip).then(()=>{const btn=document.getElementById("copy-btn");btn.innerText="Copied!";setTimeout(()=>btn.innerText="Copy IP",2000)})}function startCountdown(){let count=10;const timer=setInterval(()=>{count--;if(count<=0){count=0;clearInterval(timer)}document.getElementById("timer").innerText=count},1000)}window.onload=checkStatus;</script></head><body><h1>PlantPilot</h1><div id="status">Connecting to WiFi...</div><div class="loader"></div><div class="host">App connects to <b>plantpilot.local</b> by default</div><div id="ip-box"><div class="note">If the app can't connect, enter this IP:</div><span id="ip"></span><button id="copy-btn" onclick="copyIp()">Copy IP</button></div><div id="countdown">Restarting in <span id="timer">10</span> seconds...</div></body></html>)rawliteral";

String getScanResults() {
    int n = WiFi.scanNetworks();
    String options = "";
    if (n == 0) options = "<option disabled>No networks found</option>";
    else {
        for (int i = 0; i < n; ++i) options += "<option value=\"" + WiFi.SSID(i) + "\">" + WiFi.SSID(i) + " (" + String(WiFi.RSSI(i)) + "dBm)</option>";
    }
    return options;
}

void startSetupMode() {
    setupModeActive = true;
    generateSetupPassword();
    WiFi.mode(WIFI_AP_STA);
    WiFi.softAP(SETUP_SSID, setupApPassword);
    Serial.println("[SETUP] ==============================");
    Serial.println("[SETUP] SETUP MODE ACTIVE");
    Serial.printf("[SETUP] Connect to WiFi network: %s\n", SETUP_SSID);
    Serial.printf("[SETUP] AP Password: %s\n", setupApPassword);
    Serial.printf("[SETUP] Setup page: http://%s\n", WiFi.softAPIP().toString().c_str());
    Serial.println("[SETUP] Open browser, choose your network, save credentials.");
    Serial.println("[SETUP] ==============================");
    dnsServer.start(53, "*", WiFi.softAPIP());
    server.on("/", HTTP_GET, [](AsyncWebServerRequest *request){
        String html = String(FPSTR(SETUP_HTML));
        html.replace("{{SCAN_RESULTS}}", getScanResults());
        request->send(200, "text/html", html);
    });
    server.on("/api/wifi_status", HTTP_GET, [](AsyncWebServerRequest *request){
        JsonDocument doc; doc["status"] = (int)WiFi.status(); doc["ip"] = WiFi.localIP().toString(); doc["time"] = getNow();
        String response; serializeJson(doc, response); request->send(200, "application/json", response);
    });
    server.on("/save", HTTP_POST, [](AsyncWebServerRequest *request){
        // Validate device password
        String devPass = request->arg("devpass");
        if (devPass != String(setupApPassword)) {
            request->send(403, "text/html", "<html><body style='background:#121212;color:#ff4444;padding:40px;text-align:center;font-family:sans-serif'><h2>Wrong device password</h2><p>Check the Serial monitor for the correct password.</p><a href='/' style='color:#B6FF3C'>Go back</a></body></html>");
            return;
        }
        preferences.begin("wifi", false); preferences.putString("ssid", request->arg("ssid")); preferences.putString("pass", request->arg("pass")); preferences.end();
        WiFi.begin(request->arg("ssid").c_str(), request->arg("pass").c_str());
        request->send(200, "text/html", String(FPSTR(CONNECTING_HTML)));
        pendingRestart = true; restartTime = millis() + 30000;
    });
    server.onNotFound([](AsyncWebServerRequest *request){ request->redirect("/"); });
    server.begin();
}

// --- API HANDLERS ---

void handleWsCommand(String cmd, AsyncWebSocketClient *client) {
    cmd.trim();
    Serial.printf("[%s] [WS] RX: %s\n", getLocalTimeStr().c_str(), cmd.c_str());
    if (cmd == "READ_SENSORS") {
        // Force an immediate sensor read + telemetry push (app open/resume).
        // Deferred to loop(): broadcastTelemetry() builds a large JSON payload
        // and must run on the main loop's stack, not this small AsyncTCP task.
        lastSensorSent = 0;
        pendingSensorRead = true;
    } else if (cmd.startsWith("SYNC_MODE ")) {
        // App signals its lifecycle: foreground 1s, background 3s. Clamped so a
        // bad value can't starve or flood the stream.
        int seconds = cmd.substring(10).toInt();
        if (seconds < 1) seconds = 1;
        if (seconds > 30) seconds = 30;
        streamCadenceMs = (unsigned long)seconds * 1000UL;
        Serial.printf("[%s] [SYNC] Stream cadence set to %ds\n", getLocalTimeStr().c_str(), seconds);
        JsonDocument doc;
        doc["type"] = "ok";
        doc["cmd"] = "SYNC_MODE";
        doc["cadence"] = seconds;
        String resp; serializeJson(doc, resp);
        Serial.printf("[%s] [WS] TX: %s\n", getLocalTimeStr().c_str(), resp.c_str());
        client->text(resp);
    } else if (cmd == "CAL_STREAM_ON") {
        calibrationStreamActive = true;
        Serial.println("[SENSOR] Calibration streaming ON (1s cadence)");
        client->text("{\"type\":\"ok\",\"cmd\":\"CAL_STREAM_ON\"}");
        Serial.println("[WS] TX: {\"type\":\"ok\",\"cmd\":\"CAL_STREAM_ON\"}");
    } else if (cmd == "CAL_STREAM_OFF") {
        calibrationStreamActive = false;
        Serial.println("[SENSOR] Calibration streaming OFF");
        client->text("{\"type\":\"ok\",\"cmd\":\"CAL_STREAM_OFF\"}");
        Serial.println("[WS] TX: {\"type\":\"ok\",\"cmd\":\"CAL_STREAM_OFF\"}");
    } else if (cmd == "STATUS") {
        // Fixed-size buffer instead of String concatenation: this runs on the
        // AsyncTCP event task where repeated String reallocations fragment the
        // heap and risk stack/alloc failures on a freshly connected client.
        char status[64];
        int off = 0;
        for (int i = 0; i < 4; i++) {
            off += snprintf(status + off, sizeof(status) - off, "Pump%d: %s%s",
                            i + 1, pumps[i].isOn ? "ON" : "OFF", i < 3 ? "\n" : "");
        }
        client->text(status);
        Serial.printf("[%s] [WS] TX: %s\n", getLocalTimeStr().c_str(), status);
    } else if (cmd == "RESET_CONFIG") {
        preferences.begin("plantpilot", false);
        preferences.clear();
        preferences.end();
        Serial.println("[SYSTEM] Configuration Reset requested by App");
        loadConfigs(); // Re-initialize with defaults
        client->text("{\"type\":\"ok\",\"cmd\":\"RESET_CONFIG\"}");
        Serial.println("[WS] TX: {\"type\":\"ok\",\"cmd\":\"RESET_CONFIG\"}");
    } else if (cmd == "PUMP_ALL_ON") {
        for (int i = 0; i < 4; i++) requestPumpStart(i, 0, "manual");

        JsonDocument doc;
        doc["type"] = "ok";
        doc["cmd"] = cmd;
        JsonArray arr = doc["pumps"].to<JsonArray>();
        for (int i = 0; i < 4; i++) arr.add(pumps[i].isOn);
        String resp; serializeJson(doc, resp);
        Serial.printf("[%s] [WS] TX: %s\n", getLocalTimeStr().c_str(), resp.c_str());
        client->text(resp);
    } else if (cmd == "PUMP_ALL_OFF") {
        staggeredStopPending = true;
        nextStaggeredStop = 0;
        lastStaggerTime = 0;
        // Clear any pending starts if we are doing a master stop
        for (int i = 0; i < 4; i++) startQueue[i].pending = false;

        JsonDocument doc;
        doc["type"] = "ok";
        doc["cmd"] = cmd;
        JsonArray arr = doc["pumps"].to<JsonArray>();
        for (int i = 0; i < 4; i++) arr.add(pumps[i].isOn);
        String resp; serializeJson(doc, resp);
        Serial.printf("[%s] [WS] TX: %s\n", getLocalTimeStr().c_str(), resp.c_str());
        client->text(resp);
    } else if (cmd.startsWith("PUMP") && cmd.endsWith("_ON")) {
        char letter = cmd.charAt(4);
        int id = (letter >= 'A' && letter <= 'D') ? (letter - 'A') : (letter - '1');
        if (id >= 0 && id < 4) requestPumpStart(id, 0, "manual");
        JsonDocument doc;
        doc["type"] = "ok";
        doc["cmd"] = cmd;
        JsonArray arr = doc["pumps"].to<JsonArray>();
        for (int i = 0; i < 4; i++) arr.add(pumps[i].isOn);
        String resp; serializeJson(doc, resp);
        Serial.printf("[%s] [WS] TX: %s\n", getLocalTimeStr().c_str(), resp.c_str());
        client->text(resp);
    }
else if (cmd.startsWith("PUMP") && cmd.endsWith("_OFF")) {
        char letter = cmd.charAt(4);
        int id = (letter >= 'A' && letter <= 'D') ? (letter - 'A') : (letter - '1');
        if (id >= 0 && id < 4) stopPump(id);
        JsonDocument doc;
        doc["type"] = "ok";
        doc["cmd"] = cmd;
        JsonArray arr = doc["pumps"].to<JsonArray>();
        for (int i = 0; i < 4; i++) arr.add(pumps[i].isOn);
        String resp; serializeJson(doc, resp);
        Serial.printf("[%s] [WS] TX: %s\n", getLocalTimeStr().c_str(), resp.c_str());
        client->text(resp);
    }
}

void setupApi() {
    // Live connectivity handshake for the app ("Check Connection" / onResume poll).
    server.on("/api/status", HTTP_GET, [](AsyncWebServerRequest *request){
        JsonDocument doc;
        doc["status"] = "ok";
        doc["ip"] = WiFi.localIP().toString();
        doc["uptime_sec"] = millis() / 1000;
        doc["wifi_rssi"] = WiFi.RSSI();
        doc["epoch"] = getNow();
        String res;
        serializeJson(doc, res);
        request->send(200, "application/json", res);
    });

    // Full current config with last-modified timestamps, so the app can pull
    // whatever is newer on the device side (two-way sync).
    server.on("/api/config", HTTP_GET, [](AsyncWebServerRequest *request){
        JsonDocument doc;
        JsonArray motors = doc["motors"].to<JsonArray>();
        for (int i = 0; i < 4; i++) {
            JsonObject m = motors.add<JsonObject>();
            m["id"] = i + 1;
            m["version"] = motorConfigs[i].version;
            m["last_modified"] = motorConfigs[i].lastModified;
            m["mode"] = !motorConfigs[i].isEnabled ? "off"
                       : (motorConfigs[i].autoMode ? "auto" : "scheduled");
            m["amount_ml"] = motorConfigs[i].amountMl;
            m["threshold"] = motorConfigs[i].moistureThreshold;
            m["min_interval_hours"] = motorConfigs[i].minIntervalHours;
            m["calibration_dry"] = motorConfigs[i].calibrationDry;
            m["calibration_wet"] = motorConfigs[i].calibrationWet;
            m["last_watered"] = motorConfigs[i].lastAutoWaterEpoch;
            m["ml_per_sec"] = motorConfigs[i].mlPerSecond > 0
                             ? motorConfigs[i].mlPerSecond : DEFAULT_ML_PER_SECOND;
            m["max_runtime_minutes"] = motorConfigs[i].maxRuntimeMinutes;
            m["stop_on_disconnect"] = motorConfigs[i].stopOnDisconnect;
            JsonArray sched = m["schedules"].to<JsonArray>();
            for (int j = 0; j < motorConfigs[i].scheduleCount; j++) {
                JsonObject s = sched.add<JsonObject>();
                s["hour"] = motorConfigs[i].schedules[j].hour;
                s["minute"] = motorConfigs[i].schedules[j].minute;
            }
        }
        String res;
        serializeJson(doc, res);
        request->send(200, "application/json", res);
    });

    // Chunk-safe sync: accumulate body until complete, reject oversized input
    static uint8_t syncBuffer[4096];
    static size_t syncBufferLen = 0;
    server.on("/api/sync", HTTP_POST, [](AsyncWebServerRequest *request){}, NULL,
      [](AsyncWebServerRequest *request, uint8_t *data, size_t len, size_t index, size_t total) {
        // Reject oversized bodies
        if (total > sizeof(syncBuffer)) {
            request->send(413, "application/json", "{\"status\":\"error\",\"message\":\"body too large\"}");
            syncBufferLen = 0;
            return;
        }
        // Accumulate chunks
        memcpy(syncBuffer + index, data, len);
        syncBufferLen = index + len;
        // Only process when complete body received
        if (syncBufferLen < total) return;
        syncBufferLen = 0;

        JsonDocument doc;
        DeserializationError err = deserializeJson(doc, syncBuffer, total);
        if (err) {
            request->send(400, "application/json", "{\"status\":\"error\",\"message\":\"invalid JSON\"}");
            return;
        }

        unsigned long epoch = doc["epoch"];
        if (epoch > 1600000000) {
            // Only persist if time actually changed (reduces NVS writes)
            unsigned long prev = getNow();
            if (prev == 0 || (epoch > prev ? (epoch - prev) > 2 : (prev - epoch) > 2)) {
                saveTimeSync(epoch);
            }
        }

        JsonArray motors = doc["motors"];
        JsonDocument response;
        JsonArray updated = response["updated"].to<JsonArray>();
        JsonArray ignored = response["ignored"].to<JsonArray>();

        // Include the recent history in every sync response so the app can catch
        // up on events that happened while it was closed.
        JsonArray history = response["history"].to<JsonArray>();
        for (int i = 0; i < 10; i++) {
            int idx = (historyWriteIdx + 9 - i) % 10; // Newest first
            if (wateringHistory[idx].isValid && wateringHistory[idx].amount > 0) {
                JsonObject entry = history.add<JsonObject>();
                entry["motor"] = wateringHistory[idx].motor;
                entry["amount_ml"] = wateringHistory[idx].amount;
                entry["trigger"] = wateringHistory[idx].trigger;
                entry["epoch"] = wateringHistory[idx].epoch;
                entry["soil_after"] = wateringHistory[idx].moistureAfter;
            }
        }

        for (JsonObject m : motors) {
            int id = m["id"];
            int idx = id - 1;
            if (idx < 0 || idx >= 4) continue;

            int newVersion = m["version"];
            unsigned long incomingLastModified = m["last_modified"] | 0UL;
            // Two-way sync rule: apply when the incoming config is newer by
            // version OR timestamp; otherwise keep the stored (newer) config.
            if (newVersion > motorConfigs[idx].version || newVersion == 0 ||
                incomingLastModified > motorConfigs[idx].lastModified) {
                const char* mode = m["mode"];
                motorConfigs[idx].isEnabled = (strcmp(mode, "off") != 0);
                motorConfigs[idx].autoMode = (strcmp(mode, "auto") == 0);
                motorConfigs[idx].amountMl = constrain((int)m["amount_ml"], 0, MAX_WATERING_ML);
                motorConfigs[idx].moistureThreshold = constrain((int)m["threshold"], 0, 100);
                motorConfigs[idx].minIntervalHours = max((int)m["min_interval_hours"], 0);
                motorConfigs[idx].lastAutoWaterEpoch = m["last_watered"] | 0UL;
                motorConfigs[idx].version = newVersion;
                motorConfigs[idx].lastModified = incomingLastModified;
                motorConfigs[idx].mlPerSecond = constrain((int)m["ml_per_sec"] | DEFAULT_ML_PER_SECOND, 0, MAX_ML_PER_SECOND);
                motorConfigs[idx].maxRuntimeMinutes = max((int)m["max_runtime_minutes"] | 1, 0);
                motorConfigs[idx].stopOnDisconnect = m["stop_on_disconnect"] | false;

                JsonArray schedules = m["schedules"];
                motorConfigs[idx].scheduleCount = min((int)schedules.size(), 5);
                for (int i = 0; i < motorConfigs[idx].scheduleCount; i++) {
                    int hr = (int)schedules[i]["hour"];
                    int mn = (int)schedules[i]["minute"];
                    motorConfigs[idx].schedules[i].hour = constrain(hr, 0, 23);
                    motorConfigs[idx].schedules[i].minute = constrain(mn, 0, 59);
                }
                saveMotorConfig(id);
                updated.add(id);
            } else {
                ignored.add(id);
            }
        }
        String res;
        serializeJson(response, res);
        request->send(200, "application/json", res);
    });

    // Store per-sensor dry/wet calibration in NVS and recompute moisture.
    // Also accepts optional ml_per_sec for per-pump flow rate.
    static uint8_t calBuffer[512];
    static size_t calBufferLen = 0;
    server.on("/api/calibrate", HTTP_POST, [](AsyncWebServerRequest *request){}, NULL,
      [](AsyncWebServerRequest *request, uint8_t *data, size_t len, size_t index, size_t total) {
        if (total > sizeof(calBuffer)) {
            request->send(413, "application/json", "{\"status\":\"error\",\"message\":\"body too large\"}");
            calBufferLen = 0;
            return;
        }
        memcpy(calBuffer + index, data, len);
        calBufferLen = index + len;
        if (calBufferLen < total) return;
        calBufferLen = 0;

        JsonDocument doc;
        DeserializationError err = deserializeJson(doc, calBuffer, total);
        if (err) {
            request->send(400, "application/json", "{\"status\":\"error\",\"message\":\"invalid JSON\"}");
            return;
        }
        int motor = doc["motor"];
        int dry = doc["dry"];
        int wet = doc["wet"];
        if (motor < 1 || motor > 4 || dry <= wet) {
            request->send(400, "application/json", "{\"status\":\"error\",\"message\":\"dry must exceed wet\"}");
            return;
        }
        int idx = motor - 1;
        motorConfigs[idx].calibrationDry = dry;
        motorConfigs[idx].calibrationWet = wet;
        // Accept optional ml_per_sec
        if (doc["ml_per_sec"].is<int>()) {
            motorConfigs[idx].mlPerSecond = constrain((int)doc["ml_per_sec"], 1, MAX_ML_PER_SECOND);
        }
        motorConfigs[idx].version++;
        motorConfigs[idx].lastModified = getNow();
        saveMotorConfig(motor);
        soilMoisture[idx] = readMoisture(idx);
        Serial.printf("[%s] [CAL] Sensor %d calibrated dry=%d wet=%d rate=%dml/s (v%d)\n",
            getLocalTimeStr().c_str(), motor, dry, wet, motorConfigs[idx].mlPerSecond, motorConfigs[idx].version);
        request->send(200, "application/json", "{\"status\":\"ok\",\"sensor\":" + String(motor) + "}");
    });

    for (int m = 1; m <= 4; m++) {
        char path[32]; sprintf(path, "/api/motor/%d/water_now", m);
        server.on(path, HTTP_POST, [m](AsyncWebServerRequest *request){
            requestPumpStart(m-1, motorConfigs[m-1].amountMl, "manual");
            request->send(200, "application/json", "{\"status\":\"ok\"}");
        });
    }
}

void onWiFiEvent(WiFiEvent_t event, WiFiEventInfo_t info) {
    switch (event) {
        case ARDUINO_EVENT_WIFI_STA_CONNECTED: Serial.println("[WIFI] Connected to AP"); break;
        case ARDUINO_EVENT_WIFI_STA_GOT_IP:
            Serial.printf("[%s] [WIFI] IP: %s\n", getLocalTimeStr().c_str(), WiFi.localIP().toString().c_str());
            wasConnected = true;
            // After setup-mode credentials are saved, restart 10s after getting
            // an IP so the device comes up in normal STA mode (matches the page
            // countdown).
            if (pendingRestart) { restartTime = millis() + 10000; }
            break;
        case ARDUINO_EVENT_WIFI_STA_DISCONNECTED:
            lastWiFiReason = info.wifi_sta_disconnected.reason;
            if (wasConnected) { Serial.printf("[%s] [WIFI] Lost, Reason: %d\n", getLocalTimeStr().c_str(), lastWiFiReason); wasConnected = false; }
            break;
        default: break;
    }
}

void setup() {
    Serial.begin(115200); delay(500); Serial.printf("\n[%s] [SYSTEM] Booting PlantPilot...\n", getLocalTimeStr().c_str());
    // Map reset reason to a readable string (esp_reset_reason_str is not
    // available on all core versions).
    const char* resetReason;
    switch (esp_reset_reason()) {
        case ESP_RST_POWERON:     resetReason = "POWERON"; break;
        case ESP_RST_EXT:         resetReason = "EXTERNAL"; break;
        case ESP_RST_SW:          resetReason = "SOFTWARE"; break;
        case ESP_RST_PANIC:       resetReason = "PANIC"; break;
        case ESP_RST_INT_WDT:     resetReason = "INTERRUPT_WATCHDOG"; break;
        case ESP_RST_TASK_WDT:    resetReason = "TASK_WATCHDOG"; break;
        case ESP_RST_WDT:         resetReason = "OTHER_WATCHDOG"; break;
        case ESP_RST_DEEPSLEEP:   resetReason = "DEEP_SLEEP"; break;
        case ESP_RST_BROWNOUT:    resetReason = "BROWNOUT"; break;
        case ESP_RST_SDIO:        resetReason = "SDIO"; break;
        default:                  resetReason = "UNKNOWN"; break;
    }
    Serial.printf("[SYSTEM] Reset reason: %s, free heap: %u bytes\n", resetReason, ESP.getFreeHeap());
    initStatusLed(); initRelays(); loadConfigs();

    // Load persisted time and rebase against current boot millis
    preferences.begin("time", true);
    TimeSyncData timeData = {0, 0};
    preferences.getBytes("sync", &timeData, sizeof(timeData));
    preferences.end();
    if (timeData.epoch != 0) {
        bootEpochOffset = timeData.epoch - timeData.syncMillis / 1000;
        Serial.printf("[TIME] Boot epoch offset: %lu (persisted epoch %lu)\n", bootEpochOffset, timeData.epoch);
    }

    WiFi.persistent(false); WiFi.setAutoReconnect(true); WiFi.onEvent(onWiFiEvent);
    WiFi.setSleep(WIFI_PS_MIN_MODEM);
    preferences.begin("wifi", true); String ssid = preferences.getString("ssid", ""); String pass = preferences.getString("pass", ""); preferences.end();
    if (ssid.length() > 0) { Serial.printf("[%s] [WIFI] Target: %s\n", getLocalTimeStr().c_str(), ssid.c_str()); WiFi.begin(ssid.c_str(), pass.c_str()); }
    else { Serial.printf("[%s] [WIFI] No credentials. Setup Mode.\n", getLocalTimeStr().c_str()); startSetupMode(); }
    if (MDNS.begin(HOSTNAME)) MDNS.addService("http", "tcp", 80);
    setupApi();
    ws.onEvent([](AsyncWebSocket *server, AsyncWebSocketClient *client, AwsEventType type, void *arg, uint8_t *data, size_t len){
        if (type == WS_EVT_CONNECT) {
            Serial.printf("[%s] [WS] Client connected (total: %u, heap: %u)\n",
                getLocalTimeStr().c_str(), ws.count(), ESP.getFreeHeap());
            client->text("PlantPilot Ready");
            Serial.println("[WS] TX: PlantPilot Ready");
        } else if (type == WS_EVT_DISCONNECT) {
            Serial.printf("[%s] [WS] Client disconnected (total: %u)\n",
                getLocalTimeStr().c_str(), ws.count());
            // If any pump has stopOnDisconnect, queue a stop
            for (int i = 0; i < 4; i++) {
                if (motorConfigs[i].stopOnDisconnect && pumps[i].isOn) {
                    Serial.printf("[%s] [PUMP] Stopping %s on disconnect (stopOnDisconnect=true)\n",
                        getLocalTimeStr().c_str(), pumps[i].name);
                    staggeredStopPending = true;
                    nextStaggeredStop = 0;
                    lastStaggerTime = 0;
                }
            }
        } else if (type == WS_EVT_DATA) {
            // Accumulate data across possible fragments
            String cmd = "";
            cmd.reserve(len);
            for (size_t i = 0; i < len; i++) cmd += (char)data[i];
            if (cmd.length() > 0) handleWsCommand(cmd, client);
        }
    });
    server.addHandler(&ws); server.begin();
}

void loop() {
    unsigned long loopStart = millis();
    updateStatusLed();

    // 1. Process Global Start Queue (Power Safety - Non-blocking)
    if (!staggeredStopPending && (loopStart - lastGlobalStart >= STAGGER_INTERVAL_MS)) {
        for (int i = 0; i < 4; i++) {
            if (startQueue[i].pending) {
                triggerPump(i, startQueue[i].amount, startQueue[i].source);
                startQueue[i].pending = false;
                lastGlobalStart = loopStart;
                break; // Only start one per interval
            }
        }
    }

    // 2. Process Manual Staggered Stop (UI Smoothness)
    if (staggeredStopPending && (loopStart - lastStaggerTime >= STAGGER_INTERVAL_MS)) {
        stopPump(nextStaggeredStop);
        nextStaggeredStop++;
        lastStaggerTime = loopStart;
        if (nextStaggeredStop >= 4) staggeredStopPending = false;
    }

    if (pendingRestart && loopStart >= restartTime) ESP.restart();
    if (WiFi.status() == WL_CONNECTED) {
        // Cache SSID on first successful connection
        if (cachedWifiSsid.length() == 0) cachedWifiSsid = WiFi.SSID();
        static unsigned long lastNtpSync = 0;
        if (loopStart - lastNtpSync > 3600000 || lastNtpSync == 0) { lastNtpSync = loopStart; syncWithNtp(); }
    } else if (WiFi.status() != WL_CONNECTED && (WiFi.getMode() & WIFI_STA)) {
        if (lastWifiRetry == 0) lastWifiRetry = loopStart;
        unsigned long offlineMs = loopStart - lastWifiRetry;
        // Escalating recovery: disconnect+reconnect at 10min, restart at 30min
        if (offlineMs > 1800000UL) {
            Serial.println("[WIFI] Offline 30min, restarting...");
            ESP.restart();
        } else if (offlineMs > 600000UL) {
            static bool didRadioReset = false;
            if (!didRadioReset) {
                Serial.println("[WIFI] Offline 10min, radio reset...");
                WiFi.disconnect();
                delay(500);
                WiFi.begin();
                didRadioReset = true;
            }
        }
    } else {
        lastWifiRetry = 0;
    }

    if (Serial.available()) {
        String cmd = Serial.readStringUntil('\n'); cmd.trim();
        if (cmd == "WIFI_RESET") { preferences.begin("wifi", false); preferences.clear(); preferences.end(); WiFi.disconnect(true, true); delay(1000); ESP.restart(); }
    }
    if (WiFi.getMode() & WIFI_AP_STA) dnsServer.processNextRequest();
    ws.cleanupClients(); updatePumps();

    static unsigned long lastTele = 0;
    unsigned long telemetryMs = calibrationStreamActive ? 1000UL
                    : (ws.count() > 0 ? streamCadenceMs : 60000UL);
    if (pendingSensorRead || (loopStart - lastTele > telemetryMs)) {
        // READ_SENSORS from the WS handler defers the heavy JSON build to here,
        // on the main loop stack. Also reset the timer so the forced push isn't
        // immediately followed by a regular cadence push.
        pendingSensorRead = false;
        lastTele = loopStart;
        broadcastTelemetry();
    }

    static unsigned long lastCheck = 0;
    if (loopStart - lastCheck > 1000) { lastCheck = loopStart; checkSchedules(); checkAutoWatering(); }

    // Track max loop iteration time for diagnostics
    unsigned long loopMs = millis() - loopStart;
    static unsigned long maxLoopMs = 0;
    static unsigned long lastLoopReset = 0;
    if (loopStart - lastLoopReset > 60000) {
        maxLoopMs = 0;
        lastLoopReset = loopStart;
    }
    if (loopMs > maxLoopMs) maxLoopMs = loopMs;
}
