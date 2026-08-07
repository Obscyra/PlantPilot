/*
 * =====================================================================================
 *  PlantPilot — ESP32 Firmware
 *  Component: Core Hardware Controller (4-Channel Smart Irrigation & Telemetry Engine)
 *
 *  Architecture:
 *  - Network: Dual Wi-Fi STA / SoftAP Setup Mode (Captive Portal), AsyncWebSocket, REST API
 *  - Hardware: 4 Active-Low Relays (GPIO 25, 26, 27, 14), 4 Capacitive Soil Sensors (GPIO 34, 35, 32, 33)
 *  - Safety: Single-Motor Power Lock, Staggered Motor Activation (250ms), Max Runtime Failsafe
 *  - Power Management: Wi-Fi Modem Sleep (WIFI_PS_MIN_MODEM), FreeRTOS Task Yield (delay 1ms)
 *  - Flash Wear Protection: NVS writes use memcmp content comparisons
 * =====================================================================================
/*
 * =====================================================================================
 *  REQUIRED ARDUINO LIBRARIES & DEPENDENCIES:
 *  -----------------------------------------------------------------------------------
 *  1. <WiFi.h> (Built-in ESP32 Board Package)
 *     - Manages Wi-Fi Station (STA) connection & SoftAP Setup Access Point.
 *     - Configures modem sleep (WIFI_PS_MIN_MODEM) to prevent thermal overheating.
 *
 *  2. <ESPAsyncWebServer.h> (by me-no-dev / mathieucarbou - Install via Library Manager)
 *     - Asynchronous non-blocking HTTP server for REST API (/api/status, /api/config).
 *     - Hosts AsyncWebSocket server (ws://plantpilot.local/ws) for real-time telemetry.
 *
 *  3. <AsyncTCP.h> (by me-no-dev / mathieucarbou - Install via Library Manager)
 *     - Low-level asynchronous TCP framework underlying ESPAsyncWebServer on FreeRTOS.
 *
 *  4. <ArduinoJson.h> (v6.x / v7.x by Benoit Blanchon - Install via Library Manager)
 *     - Dynamic JSON payload builder & parser for WebSocket streaming & REST API.
 *
 *  5. <Preferences.h> (Built-in ESP32 Board Package)
 *     - Manages non-volatile flash storage (NVS) for motor configs & Wi-Fi credentials.
 *
 *  6. <time.h> (Built-in ESP32 C Standard Library)
 *     - Handles POSIX time routines, NTP time synchronization, and timezone conversion.
 *
 *  7. <ESPmDNS.h> (Built-in ESP32 Board Package)
 *     - Multicast DNS service responder enabling connection via "http://plantpilot.local/".
 *
 *  8. <DNSServer.h> (Built-in ESP32 Board Package)
 *     - Captive portal DNS server forwarding setup traffic to 192.168.4.1 in SoftAP mode.
 *
 *  9. "esp_adc_cal.h" (Built-in ESP-IDF Framework)
 *     - 12-bit ADC voltage calibration using internal eFuse Vref for soil sensors.
 * =====================================================================================
 */

#include <WiFi.h>
#include <ESPAsyncWebServer.h>
#include <AsyncTCP.h>
#include <ArduinoJson.h>
#include <Preferences.h>
#include <time.h>
#include <ESPmDNS.h>
#include <DNSServer.h>
#include "esp_adc_cal.h"

Preferences preferences;

// =====================================================================================
//  SECTION 1: SYSTEM CONFIGURATION & PIN MAPPINGS
// =====================================================================================
const char* HOSTNAME = "plantpilot";          // mDNS hostname -> http://plantpilot.local/
const char* SETUP_SSID = "PlantPilot_Setup";  // Open SoftAP network name for initial Wi-Fi setup
const int DEFAULT_ML_PER_SECOND = 10;         // Default pump flow rate fallback (ml/s)
const int MAX_ML_PER_SECOND = 100;            // Maximum flow rate sanity cap (ml/s)
const int MAX_WATERING_ML = 10000;            // Maximum safety cap per single watering run (ml)

// Status LED (GPIO 2 - Onboard Blue LED on ESP32 DevKit)
// - Fast Blink (200ms): Searching for Wi-Fi AP
// - Double-Heartbeat: SoftAP Setup Mode active
// - Short Blip (every 2s): Wi-Fi connected, App disconnected (Idle)
// - Solid ON: Wi-Fi connected AND WebSocket App client active
#define STATUS_LED GPIO_NUM_2

// Onboard User Button (BOOT Pin - GPIO 0)
// - Single Short Press (<5s): Software reboot ESP32
// - Long Press (>=5s): Blinks LED 6x, clears NVS Wi-Fi credentials, reboots to Setup AP
#define BUTTON_PIN GPIO_NUM_0
bool buttonPressed = false;
bool buttonLongPressHandled = false;
unsigned long buttonPressStartMs = 0;

// NTP Network Time Synchronization Constants
const char* NTP_SERVER = "pool.ntp.org";
const long  GMT_OFFSET_SEC = 21600; // UTC+6 timezone offset (seconds)
const int   DST_OFFSET_SEC = 0;

// Persisted Time Structure (survives soft reboots)
struct TimeSyncData {
    unsigned long epoch;
    unsigned long syncMillis;
};
bool timeSet = false;
unsigned long bootEpochOffset = 0; // Calculated epoch offset at current boot

// Wi-Fi Connection & Radio Management State
String cachedWifiSsid = "";
unsigned long lastWifiRetry = 0;
uint8_t lastWiFiReason = 0;
volatile bool wasConnected = false;
bool didRadioReset = false;
unsigned long restartTime = 0;
bool pendingRestart = false;
bool setupModeActive = false;

// =====================================================================================
//  SECTION 2: HARDWARE PINS & DATA STRUCTURES
// =====================================================================================

// Historical Watering Event Record (Ring buffer stored in RAM for sync catch-up)
struct HistoryEntry {
    int motor;             // Motor number (1..4)
    int amount;            // Target watering volume (ml)
    char trigger[12];      // Trigger origin: "manual", "auto", or "scheduled"
    unsigned long epoch;   // Epoch timestamp (seconds)
    int moistureAfter;     // Soil moisture level (%) after watering completed
    bool isValid;
};
HistoryEntry wateringHistory[10];
int historyWriteIdx = 0;

// Watering Schedule Slot (up to 5 per motor channel)
struct WateringSchedule {
    int hour;    // 0..23
    int minute;  // 0..59
};

// Motor Configuration Structure (persisted per-motor in ESP32 NVS)
struct MotorSettings {
    bool isEnabled;                    // True if motor channel is enabled
    bool autoMode;                     // True if auto moisture watering is active
    int amountMl;                      // Default watering volume per run (ml)
    int moistureThreshold;             // Soil moisture threshold (%) to trigger auto-watering
    int calibrationDry;                // Raw ADC value in open air (100% dry soil)
    int calibrationWet;                // Raw ADC value submerged in water (100% wet soil)
    int version;                       // Version counter for conflict resolution during sync
    unsigned long lastModified;        // Epoch seconds of last modification
    int minIntervalHours;              // Minimum cooldown hours between auto waterings
    WateringSchedule schedules[5];     // Fixed array of up to 5 daily schedules
    int scheduleCount;                 // Active schedule count
    unsigned long lastAutoWaterEpoch;  // Last auto-watering completion epoch
    int mlPerSecond;                   // Calibrated flow rate (ml/s)
    int maxRuntimeMinutes;             // Failsafe: maximum allowed runtime before auto shutdown
    bool stopOnDisconnect;             // True to cut pump power if WebSocket app disconnects
};

// Physical Relay Pin Mapping (Active Low Control)
struct Relay {
    int pin;                           // GPIO pin for relay control
    int sensorPin;                     // Analog ADC pin for capacitive soil sensor
    bool isOn;                         // Live operational state (true = relay ON)
    unsigned long startTime;           // Millis timestamp when motor started
    unsigned long duration;            // Target run duration (ms)
    const char* name;                  // Display name
    char lastTriggerSource[16];        // Origin trigger source
    int lastAmountMl;                  // Target watering volume (ml)
};

// Relays are Active Low: LOW = Relay Closed (ON), HIGH = Relay Open (OFF)
#define RELAY_ON  LOW
#define RELAY_OFF HIGH

// Hardware Pin Definitions (4 Motors & 4 Soil Sensors)
Relay pumps[4] = {
    {25, 34, false, 0, 0, "Pump 1", "none", 0}, // Motor 1: Relay GPIO 25, Sensor GPIO 34
    {26, 35, false, 0, 0, "Pump 2", "none", 0}, // Motor 2: Relay GPIO 26, Sensor GPIO 35
    {27, 32, false, 0, 0, "Pump 3", "none", 0}, // Motor 3: Relay GPIO 27, Sensor GPIO 32
    {14, 33, false, 0, 0, "Pump 4", "none", 0}  // Motor 4: Relay GPIO 14, Sensor GPIO 33
};

MotorSettings motorConfigs[4];
int soilMoisture[4] = {50, 50, 50, 50};
int rawSoilCache[4] = {0, 0, 0, 0};

// Buzzer Pin Assignment (GPIO 21)
#define BUZZER_PIN 21

struct BuzzerState {
    int remainingBeeps;
    int beepOnMs;
    int beepOffMs;
    unsigned long nextToggleMs;
    bool isOn;
};
BuzzerState buzzer = {0, 0, 0, 0, false};

void initBuzzer() {
    pinMode(BUZZER_PIN, OUTPUT);
    digitalWrite(BUZZER_PIN, LOW);
}

void triggerBuzzer(int beeps, int onMs = 100, int offMs = 100) {
    if (beeps <= 0) return;
    buzzer.remainingBeeps = beeps;
    buzzer.beepOnMs = onMs;
    buzzer.beepOffMs = offMs;
    buzzer.isOn = true;
    buzzer.nextToggleMs = millis() + onMs;
    digitalWrite(BUZZER_PIN, HIGH);
}

void updateBuzzer() {
    if (buzzer.remainingBeeps <= 0) return;
    unsigned long now = millis();
    if (now >= buzzer.nextToggleMs) {
        if (buzzer.isOn) {
            digitalWrite(BUZZER_PIN, LOW);
            buzzer.isOn = false;
            buzzer.remainingBeeps--;
            if (buzzer.remainingBeeps > 0) {
                buzzer.nextToggleMs = now + buzzer.beepOffMs;
            }
        } else {
            digitalWrite(BUZZER_PIN, HIGH);
            buzzer.isOn = true;
            buzzer.nextToggleMs = now + buzzer.beepOnMs;
        }
    }
}
#define WL1 16
#define WL2 17
#define WL3 18
#define WL4 19

int waterLevel = 100;
int waterLevelDiscrete = 4; // 0..4 (0=Empty, 1=25%, 2=50%, 3=75%, 4=100%, -1=Sensor Error)
bool demoModeActive = false;
bool useHardwareWaterSensor = true; // Can be toggled ON/OFF by app setting

void saveHwWaterSensorSetting(bool enabled) {
    useHardwareWaterSensor = enabled;
    preferences.begin("plantpilot", false);
    preferences.putBool("hw_sensor", enabled);
    preferences.end();
}

int lowWaterBuzzIntervalMin = 15; // 0 = Disabled, or 5, 15, 30, 60 minutes
unsigned long lastLowWaterBuzzMs = 0;

void checkLowWaterBuzzer() {
    if (!useHardwareWaterSensor || lowWaterBuzzIntervalMin <= 0) return;
    if (waterLevelDiscrete >= 0 && waterLevelDiscrete <= 1) { // Level 1 (25%) or Level 0 (Empty)
        unsigned long intervalMs = (unsigned long)lowWaterBuzzIntervalMin * 60000UL;
        unsigned long nowMs = millis();
        if (lastLowWaterBuzzMs == 0 || (nowMs - lastLowWaterBuzzMs >= intervalMs)) {
            lastLowWaterBuzzMs = nowMs;
            triggerBuzzer(3, 150, 150); // 3 warning beeps
            Serial.printf("[%s] [ALARM] Low water buzzer warning (Level %d, next in %d min)\n",
                getLocalTimeStr(), waterLevelDiscrete, lowWaterBuzzIntervalMin);
        }
    } else {
        lastLowWaterBuzzMs = 0; // Reset when tank is refilled
    }
}

// Helper to sample a probe pin with majority voting against electrical noise
static bool sampleProbeSubmerged(int pin) {
    pinMode(pin, INPUT_PULLUP);
    int lowCount = 0;
    for (int k = 0; k < 3; k++) {
        if (digitalRead(pin) == LOW) lowCount++;
        delay(2);
    }
    return (lowCount >= 2); // True if probe is pulled to GND by water
}

// Read all 4 water level probes (GPIO 16, 17, 18, 19 with 330 Ohm resistors & INPUT_PULLUP)
int readHardwareWaterLevel() {
    if (!useHardwareWaterSensor) {
        return waterLevelDiscrete; // Disabled by app setting — skip pin reading & logs
    }
    bool l1 = sampleProbeSubmerged(WL1);
    bool l2 = sampleProbeSubmerged(WL2);
    bool l3 = sampleProbeSubmerged(WL3);
    bool l4 = sampleProbeSubmerged(WL4);

    int percent = 0;

    if (l4 && l3 && l2 && l1)
        percent = 100;
    else if (!l4 && l3 && l2 && l1)
        percent = 75;
    else if (!l4 && !l3 && l2 && l1)
        percent = 50;
    else if (!l4 && !l3 && !l2 && l1)
        percent = 25;
    else if (!l4 && !l3 && !l2 && !l1)
        percent = 0;
    else
        percent = -1;   // Invalid sensor combination

    int winningLevel = 0;
    if (percent == 100) winningLevel = 4;
    else if (percent == 75) winningLevel = 3;
    else if (percent == 50) winningLevel = 2;
    else if (percent == 25) winningLevel = 1;
    else if (percent == 0) winningLevel = 0;
    else winningLevel = -1;

    // Hysteresis & Debounce:
    // 1. Require 2 consecutive reads before dropping to 0 (empty tank protection).
    // 2. Require 2 consecutive reads before switching to -1 (Sensor Error) to ignore transient pin noise.
    static int emptyDropCounter = 0;
    static int errorCounter = 0;

    if (!demoModeActive && winningLevel == 0 && waterLevelDiscrete > 0) {
        emptyDropCounter++;
        if (emptyDropCounter < 2) {
            winningLevel = waterLevelDiscrete;
            percent = winningLevel * 25;
        }
    } else {
        emptyDropCounter = 0;
    }

    if (!demoModeActive && winningLevel == -1 && waterLevelDiscrete >= 0) {
        errorCounter++;
        if (errorCounter < 2) {
            winningLevel = waterLevelDiscrete;
            percent = winningLevel * 25;
        }
    } else {
        errorCounter = 0;
    }

    if (winningLevel != waterLevelDiscrete) {
        if (percent == -1) {
            Serial.printf("[%s] [WARNING] Water Sensor Error -> Invalid probe pattern! [Probes: L1=%d, L2=%d, L3=%d, L4=%d]\n",
                getLocalTimeStr(), l1 ? 1 : 0, l2 ? 1 : 0, l3 ? 1 : 0, l4 ? 1 : 0);
        } else if (percent == 0) {
            Serial.printf("[%s] [ALARM] Water Tank EMPTY -> Level 0 (0%%) [Probes: L1=%d, L2=%d, L3=%d, L4=%d]\n",
                getLocalTimeStr(), l1 ? 1 : 0, l2 ? 1 : 0, l3 ? 1 : 0, l4 ? 1 : 0);
        } else {
            Serial.printf("[%s] [WATER_SENSOR] Tank Level Changed -> %d%% (Level %d) [Probes: L1=%d, L2=%d, L3=%d, L4=%d]\n",
                getLocalTimeStr(), percent, winningLevel, l1 ? 1 : 0, l2 ? 1 : 0, l3 ? 1 : 0, l4 ? 1 : 0);
        }
    }

    waterLevelDiscrete = winningLevel;
    waterLevel = percent;
    return winningLevel;
}

// ADC Calibration Characteristics
esp_adc_cal_characteristics_t *adc_chars;
unsigned long lastAutoWaterTime[4] = {0, 0, 0, 0};

// =====================================================================================
//  SECTION 3: ADAPTIVE POLLING & TIMING ENGINE
// =====================================================================================
// Adjusts sensor read intervals dynamically based on moisture proximity to threshold
// (conserves sensor probe lifespan and prevents electrolysis degradation).
struct ChannelPollState {
    unsigned long nextReadDueMs;     // Millis deadline for next read
    unsigned long currentIntervalMs; // Current adaptive polling interval (ms)
    int lastMoisture;                // Last cached moisture reading (%)
    bool active;                     // True if autoMode or schedules are enabled
};
ChannelPollState channelPoll[4];

// =====================================================================================
//  SECTION 4: MOTOR SAFETY QUEUES & HARDWARE LOCKS
// =====================================================================================
// Single Motor Busy Lock: Only ONE motor runs at a time to prevent power brownouts
int activeMotorIndex = -1;

const unsigned long SENSOR_READ_INTERVAL_MS = 600000UL; // 10 minutes default sensor poll
volatile bool calibrationStreamActive = false;
volatile bool telemetryPaused = false;
volatile unsigned long firstTelemetryAfterConnectMs = 0;
unsigned long lastSensorSent = 0;
volatile bool pendingSensorRead = false;

// --- CLIENT HEARTBEAT TRACKING ---
// Tracks the last time the app sent any WS command (STATUS, SYNC_MODE, etc.).
// If no command arrives for STALE_CLIENT_TIMEOUT_MS the ESP32 treats the client
// as dead and triggers stopOnDisconnect logic.  This catches silent disconnects
// where the TCP link appears alive but the app has crashed/been killed.
volatile unsigned long lastClientCommandMs = 0;
const unsigned long STALE_CLIENT_TIMEOUT_MS = 60000UL; // 60 seconds (increased for stability)

// Staggered pump update state to prevent network blocking and power surges
struct StartReq {
    volatile bool pending;
    int amount;
    char source[16];
};
StartReq startQueue[4] = { {false, 0, ""}, {false, 0, ""}, {false, 0, ""}, {false, 0, ""} };

struct StopReq {
    volatile bool pending;
};
StopReq stopQueue[4] = { {false}, {false}, {false}, {false} };

unsigned long lastGlobalStart = 0;
const int STAGGER_INTERVAL_MS = 250;

volatile bool staggeredStopPending = false;
int nextStaggeredStop = 0;
unsigned long lastStaggerTime = 0;

void requestPumpStart(int id, int amount, const char* src) {
    if (id < 0 || id >= 4 || pumps[id].isOn) return;

    // Manual Override: If the app requests a manual start (e.g. Pump Testing),
    // stop any currently running motor immediately to prevent power surges.
    if (src != nullptr && strcmp(src, "manual") == 0) {
        if (activeMotorIndex >= 0 && activeMotorIndex != id) {
            Serial.printf("[%s] [MOTOR] Manual override: stopping motor %d to start %d\n",
                getLocalTimeStr(), activeMotorIndex + 1, id + 1);
            requestPumpStop(activeMotorIndex);
        }
    } else {
        // Sequential Scheduling: If an auto or scheduled watering starts while
        // another is active, queue it. It will start automatically once the
        // current one finishes.
        if (activeMotorIndex >= 0 && activeMotorIndex != id) {
            Serial.printf("[%s] [MOTOR] %s queued — motor %d is busy\n",
                getLocalTimeStr(), pumps[id].name, activeMotorIndex + 1);
        }
    }

    startQueue[id].amount = amount;
    if (src != nullptr) {
        strncpy(startQueue[id].source, src, 15);
        startQueue[id].source[15] = '\0';
    } else {
        strcpy(startQueue[id].source, "manual");
    }
    startQueue[id].pending = true;
}

void requestPumpStop(int id) {
    if (id >= 0 && id < 4) stopQueue[id].pending = true;
}

// --- PER-CHANNEL ADAPTIVE POLLING ---

// Recompute the adaptive read interval for one channel based on current
// moisture distance from threshold.  Called after every sensor read and on
// config/state changes so the schedule stays responsive.
void recomputeChannelInterval(int i) {
    if (i < 0 || i >= 4) return;
    int moisture = channelPoll[i].lastMoisture;
    int threshold = motorConfigs[i].moistureThreshold;
    int gap = moisture - threshold; // positive = above threshold

    unsigned long intervalMs;
    if (gap > 20) {
        // Far above threshold — idle reading (30 min) to conserve sensor & power
        intervalMs = 1800000UL;
    } else if (gap > 5) {
        // Approaching threshold — moderate cadence (10 min)
        intervalMs = 600000UL;
    } else if (gap > 0) {
        // Very close — watch closely (3 min)
        intervalMs = 180000UL;
    } else {
        // Below threshold — read frequently but safely (2 min)
        intervalMs = 120000UL;
    }

    // After a recent watering (within cooldown window), slow down to avoid
    // re-triggering on residual moisture changes.
    unsigned long nowMs = millis();
    unsigned long nowEpoch = getNow();
    if (motorConfigs[i].minIntervalHours > 0 && nowEpoch != 0 &&
        motorConfigs[i].lastAutoWaterEpoch != 0) {
        unsigned long elapsed = nowEpoch - motorConfigs[i].lastAutoWaterEpoch;
        unsigned long cooldown = (unsigned long)motorConfigs[i].minIntervalHours * 3600UL;
        if (elapsed < cooldown) {
            // Inside cooldown — relax to 30 min so we don't pollute ADC with
            // pump-induced electrical noise and don't wear out sensor probes.
            intervalMs = 1800000UL;
        }
    }

    // Only log when the interval actually changes to avoid flooding Serial at 1s cadence
    if (intervalMs != channelPoll[i].currentIntervalMs) {
        Serial.printf("[%s] [POLL] %s sensor interval changed -> %lus (moisture=%d%% threshold=%d%%)\n",
            getLocalTimeStr(), pumps[i].name, intervalMs / 1000, moisture, threshold);
    }
    channelPoll[i].currentIntervalMs = intervalMs;
}

// Initialize all channel poll states (called from setup() after loadConfigs).
void initChannelPolling() {
    for (int i = 0; i < 4; i++) {
        channelPoll[i].nextReadDueMs = 0; // read immediately on first check
        channelPoll[i].currentIntervalMs = SENSOR_READ_INTERVAL_MS;
        soilMoisture[i] = readMoisture(i);
        channelPoll[i].lastMoisture = soilMoisture[i];
        channelPoll[i].active = motorConfigs[i].autoMode || motorConfigs[i].scheduleCount > 0;
    }
}

// Recompute active flags and intervals when config changes (sync, calibrate, etc).
void refreshChannelPolling() {
    for (int i = 0; i < 4; i++) {
        channelPoll[i].active = motorConfigs[i].autoMode || motorConfigs[i].scheduleCount > 0;
        if (channelPoll[i].active) {
            channelPoll[i].nextReadDueMs = 0; // Force immediate sensor read on mode/threshold update
            recomputeChannelInterval(i);
        }
    }
}

// Called after a motor finishes watering to reset its polling schedule.
void onWateringComplete(int i) {
    if (i >= 0 && i < 4) {
        channelPoll[i].nextReadDueMs = millis() + 15000UL; // re-read 15s after completion
        recomputeChannelInterval(i);
    }
}

// --- MOTOR BUSY LOCK ---

bool isMotorBusy() {
    return activeMotorIndex >= 0;
}

void acquireMotorLock(int i) {
    if (i >= 0 && i < 4) activeMotorIndex = i;
}

void releaseMotorLock() {
    activeMotorIndex = -1;
}

// Telemetry cadence requested by the app via SYNC_MODE. Foreground -> 1s,
// background -> 3s, no clients -> 60s. Reset to 3s on boot; the app re-sends
// its mode whenever it (re)connects.
volatile unsigned long streamCadenceMs = 3000UL;
int savedCadenceSec = 3;

// Last time the heavy per-motor config array was included in a telemetry frame
// (signature of versions/timestamps). Reset to 0 on boot and whenever a WS
// client connects so the first frame after (re)connect carries full config.
unsigned long motorsLastSentMs = 0;

AsyncWebServer server(80);
AsyncWebSocket ws("/ws");
DNSServer dnsServer;

void saveHistoryToNVS() {
    preferences.begin("history", false);
    preferences.putBytes("buf", wateringHistory, sizeof(wateringHistory));
    preferences.putInt("writeIdx", historyWriteIdx);
    preferences.end();
}

void loadHistoryFromNVS() {
    preferences.begin("history", true);
    if (preferences.isKey("buf")) {
        preferences.getBytes("buf", wateringHistory, sizeof(wateringHistory));
        historyWriteIdx = preferences.getInt("writeIdx", 0) % 10;
    }
    preferences.end();
}

// --- HELPERS ---



// --- TIME MANAGER ---

void saveTimeSync(unsigned long epoch) {
    // Avoid NVS flash wear if time offset is already set and drift is under 60 seconds
    unsigned long currentCalculated = getNow();
    if (currentCalculated > 0 && abs((long)epoch - (long)currentCalculated) < 60) {
        bootEpochOffset = epoch - millis() / 1000;
        timeSet = true;
        return;
    }
    TimeSyncData data = { epoch, millis() };
    preferences.begin("time", false);
    size_t written = preferences.putBytes("sync", &data, sizeof(data));
    preferences.end();
    if (written == 0) Serial.println("[TIME] WARNING: NVS write failed for time sync");
    bootEpochOffset = epoch - millis() / 1000;
    timeSet = true;
    Serial.printf("[TIME] Saved Sync to NVS: %lu at %lu ms\n", epoch, data.syncMillis);
}

// In-RAM time calculation — no NVS access per call.
// bootEpochOffset is set once at boot from persisted data, then updated by
// saveTimeSync() on NTP or app sync.
unsigned long getNow() {
    if (bootEpochOffset == 0) return 0;
    return bootEpochOffset + millis() / 1000;
}

// Reused across telemetry frames to avoid per-frame heap alloc/free churn.
// Reallocating a JsonDocument + String every second (foreground cadence) for
// hours fragments the heap and eventually kills the WS send path. Reuse one doc
// and one fixed buffer so the steady-state frame path does zero heap allocation.
static JsonDocument telemetryDoc;
static char telemetryBuf[2048];

// Global max loop iteration time for diagnostics — updated by loop(), read by broadcastTelemetry()
unsigned long g_maxLoopMs = 0;
unsigned long g_lastLoopReset = 0;

// Logs a throttled status line while no client is connected so the serial
// monitor shows the ESP is alive during downtime (telemetry is paused).
void logIdleStatus() {
    static unsigned long lastIdleLog = 0;
    unsigned long now = millis();
    if (now - lastIdleLog < 60000UL) return;
    lastIdleLog = now;

    bool wifiUp = (WiFi.status() == WL_CONNECTED);
    if (wifiUp) {
        Serial.printf("[%s] [IDLE] WiFi OK (%d dBm) | 0 app clients | heap=%u | uptime=%lus\n",
            getLocalTimeStr(), (int)WiFi.RSSI(), ESP.getFreeHeap(), millis() / 1000);
    } else {
        Serial.printf("[%s] [IDLE] WiFi DISCONNECTED | heap=%u | uptime=%lus\n",
            getLocalTimeStr(), ESP.getFreeHeap(), millis() / 1000);
    }
}

void broadcastTelemetry() {
    if (ws.count() == 0) { logIdleStatus(); return; }
    readHardwareWaterLevel();
    telemetryDoc.clear();
    JsonDocument &doc = telemetryDoc;
    doc["type"] = "telemetry";
    doc["water_level"] = waterLevel;
    doc["water_level_raw"] = waterLevelDiscrete;
    doc["demo_mode"] = demoModeActive;
    doc["ntp_synced"] = timeSet;

    JsonArray queued = doc["queued"].to<JsonArray>();
    for (int i = 0; i < 4; i++) {
        queued.add(startQueue[i].pending);
    }

    // Report max loop time from the global tracked by loop() (g_maxLoopMs)
    doc["loop_ms_max"] = g_maxLoopMs;

    // Refresh raw ADC readings:
    // 1. Always read if the App is connected (ws.count() > 0) or calibration is active.
    // 2. Always read on boot / manual READ_SENSORS (lastSensorSent == 0).
    // 3. Read on the idle 10-minute cadence if any automation is active.
    bool isAppConnected = (ws.count() > 0);
    bool anyAutoOrSchedule = false;
    for (int i = 0; i < 4; i++) {
        if (motorConfigs[i].autoMode || motorConfigs[i].scheduleCount > 0) {
            anyAutoOrSchedule = true;
            break;
        }
    }

    if (isAppConnected || calibrationStreamActive || lastSensorSent == 0 ||
       (anyAutoOrSchedule && (millis() - lastSensorSent >= SENSOR_READ_INTERVAL_MS))) {
        lastSensorSent = millis();
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
    doc["sensor_cadence_sec"] = savedCadenceSec;

    JsonArray pumpsState = doc["pumps"].to<JsonArray>();
    for (int i = 0; i < 4; i++) {
        pumpsState.add(pumps[i].isOn);
    }

    // Full per-motor config rides along only occasionally so the app stays in
    // sync (version/last_modified let the app decide which side is newer). Sending
    // the heavy array every frame (1-3s) is the dominant per-frame CPU/network
    // cost, so include it only when it changed or every 30s, else omit the key
    // (the app treats a missing motors field as null / unchanged). The app also
    // re-pulls authoritative config over REST two-way sync, so this is safe.
    static uint32_t motorsSig = 0;
    unsigned long motorsNow = millis();
    uint32_t sig = 0;
    for (int i = 0; i < 4; i++) {
        sig += (uint32_t)motorConfigs[i].version * 7919U;
        sig += (motorConfigs[i].lastModified & 0xFFFFU);
        sig += (uint32_t)motorConfigs[i].scheduleCount * 131U;
    }
    bool motorsChanged = (sig != motorsSig);
    bool motorsDue = (motorsLastSentMs == 0) || (motorsNow - motorsLastSentMs >= 30000UL);
    if (motorsChanged || motorsDue) {
        motorsSig = sig;
        motorsLastSentMs = motorsNow;
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
    }

    // Serialize into the reused fixed buffer: no String/heap alloc per frame.
    size_t jsonSize = serializeJson(doc, telemetryBuf, sizeof(telemetryBuf));
    if (jsonSize >= sizeof(telemetryBuf)) {
        Serial.printf("[%s] [ERR] Telemetry payload too large (%u bytes) - truncated\n",
            getLocalTimeStr(), (unsigned)jsonSize);
        return;
    }
    ws.textAll(telemetryBuf);

    // Throttled serial mirror so the IDE monitor shows live data like the app's
    // log screen, without flooding (max 1/sec, only while a client is connected).
    static unsigned long lastTeleLog = 0;
    unsigned long nowMs = millis();
    if (nowMs - lastTeleLog >= 1000) {
        lastTeleLog = nowMs;
        Serial.printf("[%s] [TELE] water=%d%% (L%d) soil=%d,%d,%d,%d raw=%d,%d,%d,%d heap=%u rssi=%d\n",
            getLocalTimeStr(),
            waterLevel, waterLevelDiscrete,
            soilMoisture[0], soilMoisture[1], soilMoisture[2], soilMoisture[3],
            rawSoilCache[0], rawSoilCache[1], rawSoilCache[2], rawSoilCache[3],
            ESP.getFreeHeap(), (int)WiFi.RSSI());
    }
}

// =====================================================================================
//  SECTION 5: TELEMETRY STREAMING & SERIAL LOGGING ENGINE
// =====================================================================================

// Writes local timestamp into static string buffer (no dynamic String heap allocation)
const char* getLocalTimeStr() {
    static char buf[10];
    unsigned long nowEpoch = getNow();
    if (nowEpoch < 1600000000) { strcpy(buf, "00:00:00"); return buf; }
    time_t localEpoch = (time_t)(nowEpoch + GMT_OFFSET_SEC);
    struct tm ti;
    gmtime_r(&localEpoch, &ti);
    sprintf(buf, "%02d:%02d:%02d", ti.tm_hour, ti.tm_min, ti.tm_sec);
    return buf;
}

// =====================================================================================
//  SECTION 6: TIME MANAGER & NTP NETWORK TIME SYNC
// =====================================================================================
void syncWithNtp() {
    if (WiFi.status() != WL_CONNECTED) return;
    configTime(GMT_OFFSET_SEC, DST_OFFSET_SEC, NTP_SERVER);
    struct tm timeinfo;
    if (getLocalTime(&timeinfo)) {
        time_t now;
        time(&now);
        unsigned long epoch = (unsigned long)now;
        // Only persist to NVS if time drift exceeds 2 seconds (flash wear protection)
        unsigned long prev = getNow();
        if (prev == 0 || (epoch > prev ? (epoch - prev) > 2 : (prev - epoch) > 2)) {
            saveTimeSync(epoch);
        }
        Serial.println("[TIME] NTP Sync Successful");
    }
}

// =====================================================================================
//  SECTION 7: HARDWARE RELAYS, SENSORS & USER BUTTON HANDLER
// =====================================================================================
void initRelays() {
    Serial.println("[HARDWARE] Initializing Pins & ADC...");

    // Characterize ADC for Rev 1.0 (uses eFuse Vref if available)
    adc_chars = (esp_adc_cal_characteristics_t *)calloc(1, sizeof(esp_adc_cal_characteristics_t));
    esp_adc_cal_value_t val_type = esp_adc_cal_characterize(ADC_UNIT_1, ADC_ATTEN_DB_11, ADC_WIDTH_BIT_12, 1100, adc_chars);
    if (val_type == ESP_ADC_CAL_VAL_EFUSE_VREF) {
        Serial.println("[HARDWARE] ADC characterized using eFuse Vref (Optimal for Rev 1.0)");
    } else {
        Serial.println("[HARDWARE] ADC characterized using Default Vref");
    }

    analogReadResolution(12);
    analogSetAttenuation(ADC_11db);

    for (int i = 0; i < 4; i++) {
        pinMode(pumps[i].pin, OUTPUT);
        digitalWrite(pumps[i].pin, RELAY_OFF);
        pinMode(pumps[i].sensorPin, INPUT);
    }

    // Configure water level sensor pins (GPIO 16, 17, 18, 19 with 330 Ohm resistors & INPUT_PULLUP)
    pinMode(WL1, INPUT_PULLUP);
    pinMode(WL2, INPUT_PULLUP);
    pinMode(WL3, INPUT_PULLUP);
    pinMode(WL4, INPUT_PULLUP);
}

void initStatusLed() {
    pinMode(STATUS_LED, OUTPUT);
    digitalWrite(STATUS_LED, LOW);
}

void initButton() {
    pinMode(BUTTON_PIN, INPUT_PULLUP);
}

// Polled button state handler (BOOT Pin / GPIO 0)
// - Short press (<5s): Software reboot
// - Long press (>=5s): Blinks LED 6x, clears NVS Wi-Fi credentials, reboots to Setup AP
void updateButton() {
    bool isDown = (digitalRead(BUTTON_PIN) == LOW);
    unsigned long now = millis();

    if (isDown) {
        if (!buttonPressed) {
            buttonPressed = true;
            buttonLongPressHandled = false;
            buttonPressStartMs = now;
        } else if (!buttonLongPressHandled && (now - buttonPressStartMs >= 5000)) {
            buttonLongPressHandled = true;
            Serial.printf("[%s] [BUTTON] 5s long press detected -> Clearing WiFi credentials & entering Setup mode...\n", getLocalTimeStr());
            for (int i = 0; i < 6; i++) {
                digitalWrite(STATUS_LED, HIGH); delay(50);
                digitalWrite(STATUS_LED, LOW); delay(50);
            }
            preferences.begin("wifi", false);
            preferences.clear();
            preferences.end();
            WiFi.disconnect(true, true);
            delay(500);
            ESP.restart();
        }
    } else {
        if (buttonPressed) {
            unsigned long duration = now - buttonPressStartMs;
            buttonPressed = false;
            if (!buttonLongPressHandled && duration >= 50) { // Single short press
                Serial.printf("[%s] [BUTTON] Single press detected (%lu ms) -> Restarting ESP32...\n", getLocalTimeStr(), duration);
                delay(200);
                ESP.restart();
            }
        }
    }
}

// Non-blocking status LED pattern generator.
// Uses distinct rhythmic patterns rather than just frequency to avoid confusion.
void updateStatusLed() {
    unsigned long now = millis();

    // Pattern 4: Solid ON (WiFi + App Connected)
    if (WiFi.status() == WL_CONNECTED && ws.count() > 0) {
        digitalWrite(STATUS_LED, HIGH);
        return;
    }

    // Pattern 2: Double-Heartbeat (Setup / SoftAP Mode)
    // ON(100ms) -> OFF(150ms) -> ON(100ms) -> OFF(650ms)
    if (setupModeActive) {
        unsigned long m = now % 1000;
        if (m < 100) digitalWrite(STATUS_LED, HIGH);
        else if (m < 250) digitalWrite(STATUS_LED, LOW);
        else if (m < 350) digitalWrite(STATUS_LED, HIGH);
        else digitalWrite(STATUS_LED, LOW);
        return;
    }

    // Pattern 1: Uniform Fast Blink (WiFi Disconnected / Searching)
    // 200ms ON / 200ms OFF
    if (WiFi.status() != WL_CONNECTED) {
        digitalWrite(STATUS_LED, (now % 400 < 200) ? HIGH : LOW);
        return;
    }

    // Pattern 3: Standby Blip (WiFi Connected, App Disconnected)
    // Short 50ms pulse every 2 seconds to show it's "alive" but idle.
    digitalWrite(STATUS_LED, (now % 2000 < 50) ? HIGH : LOW);
}

// Averages several ADC samples to suppress ESP32 analog noise.
// Revision 1.0 silicon is particularly sensitive to Wi-Fi noise, so we use
// 32 samples with small delays to ensure a stable reading.
int readRawSensor(int index) {
    if (index < 0 || index >= 4) return 0;
    uint32_t total = 0;
    const int SAMPLES = 32;
    for (int s = 0; s < SAMPLES; s++) {
        total += analogRead(pumps[index].sensorPin);
        delayMicroseconds(100);
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
    // Empty Tank Dry-Run Protection (Normal Mode only; Demo Mode bypasses for testing)
    if (useHardwareWaterSensor && waterLevelDiscrete == 0 && !demoModeActive) {
        Serial.printf("[%s] [ALARM] Pump %d (%s) Start ABORTED -> Water tank is empty (Level 0 / 0%%)\n",
            getLocalTimeStr(), index + 1, pumps[index].name);
        triggerBuzzer(3, 150, 150);
        return;
    }
    // Motor busy lock: if another motor is running, defer start.
    if (isMotorBusy() && activeMotorIndex != index) {
        Serial.printf("[%s] [SAFETY] Pump %d (%s) Start DEFERRED -> Motor %d is currently active (Single-motor safety lock)\n",
            getLocalTimeStr(), index + 1, pumps[index].name, activeMotorIndex + 1);
        return; // loop() will dequeue when motor becomes idle
    }
    amountMl = constrain(amountMl, 0, MAX_WATERING_ML);

    int rate = motorConfigs[index].mlPerSecond > 0
             ? motorConfigs[index].mlPerSecond
             : DEFAULT_ML_PER_SECOND;
    rate = constrain(rate, 1, MAX_ML_PER_SECOND);

    int durationMs = 0;
    if (amountMl > 0) {
        durationMs = ((long)amountMl * 1000) / rate;
        if (durationMs < 500) durationMs = 500;
    }

    acquireMotorLock(index);
    pumps[index].isOn = true;
    pumps[index].startTime = millis();
    pumps[index].duration = durationMs; // 0 means indefinite

    if (source != nullptr) {
        strncpy(pumps[index].lastTriggerSource, source, sizeof(pumps[index].lastTriggerSource) - 1);
        pumps[index].lastTriggerSource[sizeof(pumps[index].lastTriggerSource) - 1] = '\0';
    } else {
        strcpy(pumps[index].lastTriggerSource, "manual");
    }

    pumps[index].lastAmountMl = amountMl;

    digitalWrite(pumps[index].pin, RELAY_ON);
    triggerBuzzer(1, 80, 80); // 1 short start beep
    if (durationMs > 0) {
        Serial.printf("[%s] [PUMP] Pump %d (%s) STARTED -> Flow Rate: %d ml/s | Target: %d ml (~%d sec) [Trigger: %s]\n",
            getLocalTimeStr(), index + 1, pumps[index].name, rate, amountMl, durationMs / 1000, pumps[index].lastTriggerSource);
    } else {
        Serial.printf("[%s] [PUMP] Pump %d (%s) STARTED (Indefinite test mode) [Trigger: %s]\n",
            getLocalTimeStr(), index + 1, pumps[index].name, pumps[index].lastTriggerSource);
    }
}

void stopPump(int index) {
    if (index < 0 || index >= 4 || !pumps[index].isOn) return;

    pumps[index].isOn = false;
    digitalWrite(pumps[index].pin, RELAY_OFF);
    triggerBuzzer(2, 80, 80); // 2 short finish beeps
    releaseMotorLock();

    readHardwareWaterLevel(); // Re-read hardware water tank level immediately after every watering event

    int moistureAfter = readMoisture(index);
    Serial.printf("[%s] [PUMP] Pump %d (%s) STOPPED -> Delivered: %d ml [Soil moisture after: %d%%]\n",
        getLocalTimeStr(), index + 1, pumps[index].name, pumps[index].lastAmountMl, moistureAfter);

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
        saveHistoryToNVS();
    }

    // Notify app of completion only for real timed waterings.
    // Diagnostic test toggles (indefinite, amount=0) must NOT emit
    // watering_finished or the app logs a false history entry.
    if (ws.count() > 0 && pumps[index].lastAmountMl > 0) {
        JsonDocument evtDoc;
        char evtBuf[256];
        evtDoc.clear();
        JsonDocument &doc = evtDoc;
        doc["type"] = "watering_finished";
        doc["motor"] = index + 1;
        doc["amount_ml"] = pumps[index].lastAmountMl;
        doc["trigger"] = pumps[index].lastTriggerSource;
        doc["epoch"] = getNow();
        doc["soil_after"] = moistureAfter;

        serializeJson(doc, evtBuf, sizeof(evtBuf));
        ws.textAll(evtBuf);
    }

    // Record the completion time so auto watering respects minIntervalHours.
    // Only real "auto" waterings gate the next auto trigger.
    if (pumps[index].lastTriggerSource != nullptr && strcmp(pumps[index].lastTriggerSource, "auto") == 0) {
        lastAutoWaterTime[index] = millis();
        motorConfigs[index].lastAutoWaterEpoch = getNow();
        saveMotorConfig(index + 1);
    }

    // Notify adaptive polling that this channel just completed a watering.
    onWateringComplete(index);

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
                getLocalTimeStr(), pumps[i].name, motorConfigs[i].maxRuntimeMinutes);
            stopPump(i);
        }
    }
}

// =====================================================================================
//  SECTION 8: NVS FLASH PERSISTENCE MANAGER
// =====================================================================================
// Handles non-volatile flash storage for per-pump configurations, calibration, and cadence.
// Uses memcmp content comparison to eliminate redundant flash erase/write cycles.

void saveMotorConfig(int id) {
    char key[16];
    sprintf(key, "motor%d", id);

    // Flash Wear Guard: Compare existing NVS data with new data; skip write if identical
    MotorSettings existing;
    memset(&existing, 0, sizeof(MotorSettings));
    preferences.begin("plantpilot", true);
    if (preferences.isKey(key)) {
        preferences.getBytes(key, &existing, sizeof(MotorSettings));
    }
    preferences.end();

    if (memcmp(&existing, &motorConfigs[id-1], sizeof(MotorSettings)) == 0) {
        return; // Content is unchanged — skip NVS write to preserve flash health!
    }

    preferences.begin("plantpilot", false);
    size_t written = preferences.putBytes(key, &motorConfigs[id-1], sizeof(MotorSettings));
    preferences.end();
    if (written == 0) Serial.printf("[NVS] WARNING: write failed for Pump %d\n", id);
    else {
        MotorSettings &c = motorConfigs[id-1];
        Serial.printf("[%s] [NVS] Pump %d saved: enabled=%d auto=%d amount=%dml "
            "threshold=%d%% interval=%dh rate=%dml/s dry=%d wet=%d "
            "max_run=%dmin stop_disc=%d sched=%d v%d lm=%lu\n",
            getLocalTimeStr(), id, c.isEnabled, c.autoMode, c.amountMl,
            c.moistureThreshold, c.minIntervalHours, c.mlPerSecond,
            c.calibrationDry, c.calibrationWet, c.maxRuntimeMinutes,
            c.stopOnDisconnect, c.scheduleCount, c.version, c.lastModified);
    }
}

void loadConfigs() {
    preferences.begin("plantpilot", true);
    savedCadenceSec = preferences.getInt("cadence", 3);
    if (savedCadenceSec < 1 || savedCadenceSec > 30) savedCadenceSec = 3;
    streamCadenceMs = (unsigned long)savedCadenceSec * 1000UL;
    useHardwareWaterSensor = preferences.getBool("hw_sensor", true);

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
            getLocalTimeStr(), i + 1,
            motorConfigs[i].isEnabled, motorConfigs[i].autoMode,
            motorConfigs[i].scheduleCount, motorConfigs[i].version,
            motorConfigs[i].lastModified);
    }
}

// =====================================================================================
//  SECTION 9: AUTO-WATERING & CALENDAR SCHEDULER
// =====================================================================================
// Evaluates per-channel moisture thresholds and timed daily schedules.
// Features a 3-minute safety gap so water diffuses through soil before re-reading.

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
                Serial.printf("[%s] [SCHED] Queuing %s at %02d:%02d\n", getLocalTimeStr(), pumps[i].name, curHr, curMin);
                requestPumpStart(i, motorConfigs[i].amountMl, "scheduled");
                lastTriggeredMin[i] = curMin;
                lastTriggeredHour[i] = curHr;
            }
        }
    }
}

// Per-channel adaptive auto-watering: each channel is checked only when its
// individual deadline has arrived (set by recomputeChannelInterval).  Reads
// a fresh sensor value per-channel and compares to threshold.
void checkAutoWatering() {
    unsigned long nowMs = millis();
    unsigned long nowEpoch = getNow();
    bool isAppConnected = (ws.count() > 0);

    for (int i = 0; i < 4; i++) {
        if (!motorConfigs[i].isEnabled || !motorConfigs[i].autoMode) continue;
        // Skip if pump is already running or start is queued (avoids redundant ADC reads)
        if (pumps[i].isOn || startQueue[i].pending) continue;

        // Motor busy lock: defer if another motor is running
        if (isMotorBusy()) continue;

        // Respect the per-plant min auto-water interval.
        // In Demo Mode (while app is connected), bypass minIntervalHours for live testing.
        bool bypassInterval = (isAppConnected && demoModeActive);
        unsigned long minGapMs = 10000UL;
        if (!bypassInterval && motorConfigs[i].minIntervalHours > 0) {
            unsigned long minGapSec = (unsigned long)motorConfigs[i].minIntervalHours * 3600UL;

            // 1. Check persistent epoch (survives reboot)
            if (nowEpoch != 0 && motorConfigs[i].lastAutoWaterEpoch != 0) {
                if (nowEpoch - motorConfigs[i].lastAutoWaterEpoch < minGapSec) continue;
            }

            minGapMs = minGapSec * 1000UL;
        }

        // 2. Check session-based millis (protects against rapid loops even if NTP is broken)
        if (lastAutoWaterTime[i] != 0 && (nowMs - lastAutoWaterTime[i]) < minGapMs) continue;

        // Dual-mode Sensor Reading & Auto Triggering:
        // When App is open (isAppConnected == true): read raw sensor & evaluate auto-watering continuously in real-time.
        // When App is closed (isAppConnected == false): evaluate on the idle adaptive nextReadDueMs cadence to preserve probe health.
        if (isAppConnected) {
            soilMoisture[i] = readMoisture(i);
            channelPoll[i].lastMoisture = soilMoisture[i];
        } else if (nowMs >= channelPoll[i].nextReadDueMs || channelPoll[i].nextReadDueMs == 0) {
            soilMoisture[i] = readMoisture(i);
            channelPoll[i].lastMoisture = soilMoisture[i];
            channelPoll[i].nextReadDueMs = nowMs + channelPoll[i].currentIntervalMs;
            recomputeChannelInterval(i);
            Serial.printf("[%s] [SENSOR] Auto read %s: %d%% (next in %lus)\n",
                getLocalTimeStr(), pumps[i].name, soilMoisture[i],
                channelPoll[i].currentIntervalMs / 1000);
        }

        int moisture = soilMoisture[i];
        if (moisture < motorConfigs[i].moistureThreshold) {
            Serial.printf("[%s] [AUTO] Low moisture on %s: %d%% < %d%%. Queuing start.\n", getLocalTimeStr(), pumps[i].name, moisture, motorConfigs[i].moistureThreshold);
            requestPumpStart(i, motorConfigs[i].amountMl, "auto");
        }
    }
}

// =====================================================================================
//  SECTION 10: SOFT-AP SETUP MODE (OPEN WI-FI CAPTIVE PORTAL)
// =====================================================================================
// Runs an open Wi-Fi Access Point (PlantPilot_Setup) with captive portal DNS redirect
// for hassle-free network provisioning via any smartphone browser.

const char SETUP_HTML[] PROGMEM = R"rawliteral(<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>PlantPilot — Setup</title><style>:root{--bg:#121418;--card:#1D2026;--input:#262A32;--primary:#4CAF50;--primary-glow:rgba(76,175,80,0.25);--text:#E2E8F0;--sub:#94A3B8;--border:rgba(255,255,255,0.08)}*{box-sizing:border-box;margin:0;padding:0}body{font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:var(--bg);color:var(--text);display:flex;align-items:center;justify-content:center;min-height:100vh;padding:16px}.card{background:var(--card);border:1px solid var(--border);border-radius:20px;padding:28px 24px;width:100%;max-width:400px;box-shadow:0 12px 32px rgba(0,0,0,0.4);text-align:center}.logo{width:56px;height:56px;background:var(--primary-glow);border-radius:16px;display:inline-flex;align-items:center;justify-content:center;margin:0 auto 12px}.logo svg{width:32px;height:32px;fill:var(--primary)}h1{font-size:1.4rem;font-weight:700;color:#FFF;margin-bottom:4px}p.subtitle{font-size:0.88rem;color:var(--sub);margin-bottom:24px}label{display:block;text-align:left;font-size:0.75rem;font-weight:700;color:var(--sub);margin:14px 0 6px 4px;text-transform:uppercase;letter-spacing:0.5px}select,input[type="text"],input[type="password"]{width:100%;padding:14px 16px;background:var(--input);border:1px solid var(--border);border-radius:12px;color:#FFF;font-size:0.95rem;outline:none;transition:border-color .2s}select:focus,input:focus{border-color:var(--primary);box-shadow:0 0 0 3px var(--primary-glow)}.pass-wrapper{position:relative}.pass-wrapper input{padding-right:44px}.eye-btn{position:absolute;right:12px;top:50%;transform:translateY(-50%);background:none;border:none;color:var(--sub);cursor:pointer;padding:4px;font-size:1.1rem}.btn{width:100%;padding:15px;background:var(--primary);color:#0A1D0C;border:none;border-radius:12px;font-size:1rem;font-weight:700;cursor:pointer;margin-top:24px;box-shadow:0 4px 16px var(--primary-glow);transition:transform .15s}.btn:active{transform:scale(0.98)}.btn-sec{background:transparent;color:var(--primary);border:1px solid var(--primary);box-shadow:none;margin-top:8px;padding:8px;font-size:0.8rem}</style></head><body><div class="card"><div class="logo"><svg viewBox="0 0 24 24"><path d="M12,2 C12,2 6,7 6,13 C6,16.31 8.69,19 12,19 C15.31,19 18,16.31 18,13 C18,7 12,2 12,2 Z M12,17 C9.79,17 8,15.21 8,13 C8,9.17 10.74,5.43 12,4.19 C13.26,5.43 16,9.17 16,13 C16,15.21 14.21,17 12,17 Z"/></svg></div><h1>PlantPilot Setup</h1><p class="subtitle">Connect ESP32 Controller to Wi-Fi</p><form action="/save" method="POST"><label>Select Wi-Fi Network</label><select name="ssid" id="ssid" required><option disabled selected>Scanning networks...</option></select><button type="button" class="btn btn-sec" onclick="scanNetworks()">↻ Refresh Networks</button><label>Wi-Fi Password</label><div class="pass-wrapper"><input type="password" id="pass" name="pass" placeholder="Enter Wi-Fi password"><button type="button" class="eye-btn" onclick="togglePass()">👁</button></div><button type="submit" class="btn">Connect Device</button></form></div><script>function togglePass(){const x=document.getElementById("pass");x.type=x.type==="password"?"text":"password"}function scanNetworks(){const sel=document.getElementById("ssid");sel.innerHTML="<option disabled selected>Scanning networks...</option>";fetch("/api/scan").then(r=>r.json()).then(data=>{if(!data||data.length===0){sel.innerHTML="<option disabled>No networks found</option>";return}sel.innerHTML=data.map(n=>`<option value="${n.ssid}">${n.ssid} (${n.rssi} dBm)</option>`).join("")}).catch(()=>{sel.innerHTML="<option disabled>Failed to scan. Try refreshing.</option>"})}window.onload=scanNetworks;</script></body></html>)rawliteral";

const char CONNECTING_HTML[] PROGMEM = R"rawliteral(<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>PlantPilot — Connecting</title><style>:root{--bg:#121418;--card:#1D2026;--primary:#4CAF50;--primary-glow:rgba(76,175,80,0.25);--text:#E2E8F0;--sub:#94A3B8;--border:rgba(255,255,255,0.08)}*{box-sizing:border-box;margin:0;padding:0}body{font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:var(--bg);color:var(--text);display:flex;align-items:center;justify-content:center;min-height:100vh;padding:16px}.card{background:var(--card);border:1px solid var(--border);border-radius:20px;padding:28px 24px;width:100%;max-width:400px;box-shadow:0 12px 32px rgba(0,0,0,0.4);text-align:center}.spinner{width:44px;height:44px;border:3px solid var(--border);border-top-color:var(--primary);border-radius:50%;animation:spin 1s linear infinite;margin:16px auto}@keyframes spin{to{transform:rotate(0deg)}}h1{font-size:1.3rem;font-weight:700;color:#FFF;margin-bottom:8px}p{font-size:0.9rem;color:var(--sub);margin-bottom:16px}.ip-box{background:#262A32;border:1px solid var(--primary);border-radius:12px;padding:16px;margin:20px 0;display:none}.ip-text{font-size:1.4rem;font-weight:700;color:var(--primary);margin-bottom:8px;font-family:monospace}.btn{width:100%;padding:12px;background:var(--primary);color:#0A1D0C;border:none;border-radius:10px;font-size:0.95rem;font-weight:700;cursor:pointer}</style></head><body><div class="card"><div class="spinner" id="spinner"></div><h1 id="title">Connecting to Wi-Fi...</h1><p id="sub">ESP32 is joining your home network.</p><div class="ip-box" id="ip-box"><p style="color:var(--sub);font-size:0.8rem;margin-bottom:4px">Assigned Local IP Address:</p><div class="ip-text" id="ip-val">0.0.0.0</div><button class="btn" id="copy-btn" onclick="copyIp()">Copy IP</button></div><p id="restart-msg" style="font-size:0.8rem;color:var(--sub);display:none">Restarting in <span id="timer">10</span>s...</p></div><script>let done=false;function check(){fetch('/api/wifi_status').then(r=>r.json()).then(d=>{if(d.status===3&&d.ip&&d.ip!=="0.0.0.0"){document.getElementById("spinner").style.display="none";document.getElementById("title").innerText="Connected Successfully!";document.getElementById("sub").innerText="Your ESP32 is online. The app will discover it automatically via plantpilot.local.";document.getElementById("ip-val").innerText=d.ip;document.getElementById("ip-box").style.display="block";document.getElementById("restart-msg").style.display="block";if(!done){done=true;count()}}else{setTimeout(check,1000)}}).catch(()=>setTimeout(check,1000))}function copyIp(){const ip=document.getElementById("ip-val").innerText;navigator.clipboard.writeText(ip).then(()=>{const b=document.getElementById("copy-btn");b.innerText="Copied!";setTimeout(()=>b.innerText="Copy IP",2000)})}function count(){let c=10;const t=setInterval(()=>{c--;document.getElementById("timer").innerText=c;if(c<=0)clearInterval(t)},1000)}window.onload=check;</script></body></html>)rawliteral";

void startSetupMode() {
    setupModeActive = true;
    WiFi.mode(WIFI_AP_STA);
    WiFi.softAP(SETUP_SSID); // Open Wi-Fi network (No password required)
    Serial.println("[SETUP] ==============================");
    Serial.println("[SETUP] SETUP MODE ACTIVE");
    Serial.printf("[SETUP] Connect to open WiFi network: %s\n", SETUP_SSID);
    Serial.printf("[SETUP] Setup page: http://%s\n", WiFi.softAPIP().toString().c_str());
    Serial.println("[SETUP] Open browser, choose your network, save credentials.");
    Serial.println("[SETUP] ==============================");
    dnsServer.start(53, "*", WiFi.softAPIP());
    server.on("/", HTTP_GET, [](AsyncWebServerRequest *request){
        request->send(200, "text/html", String(FPSTR(SETUP_HTML)));
    });
    server.on("/api/scan", HTTP_GET, [](AsyncWebServerRequest *request){
        int count = WiFi.scanComplete();
        if (count < 0) {
            WiFi.scanNetworks();
            count = WiFi.scanComplete();
        }
        JsonDocument doc;
        JsonArray arr = doc.to<JsonArray>();
        if (count > 0) {
            for (int i = 0; i < count; ++i) {
                JsonObject net = arr.add<JsonObject>();
                net["ssid"] = WiFi.SSID(i);
                net["rssi"] = WiFi.RSSI(i);
            }
            WiFi.scanDelete();
        }
        String jsonStr;
        serializeJson(doc, jsonStr);
        request->send(200, "application/json", jsonStr);
    });
    server.on("/api/wifi_status", HTTP_GET, [](AsyncWebServerRequest *request){
        JsonDocument doc; doc["status"] = (int)WiFi.status(); doc["ip"] = WiFi.localIP().toString(); doc["time"] = getNow();
        String response; serializeJson(doc, response); request->send(200, "application/json", response);
    });
    server.on("/save", HTTP_POST, [](AsyncWebServerRequest *request){
        preferences.begin("wifi", false); preferences.putString("ssid", request->arg("ssid")); preferences.putString("pass", request->arg("pass")); preferences.end();
        WiFi.begin(request->arg("ssid").c_str(), request->arg("pass").c_str());
        request->send(200, "text/html", String(FPSTR(CONNECTING_HTML)));
        pendingRestart = true; restartTime = millis() + 30000;
    });
    server.onNotFound([](AsyncWebServerRequest *request){ request->redirect("/"); });
    server.begin();
}

// =====================================================================================
//  SECTION 11: WEBSOCKET & REST API COMMUNICATIONS ENGINE
// =====================================================================================
// Provides real-time bidirectional WebSocket interface (/ws) and REST HTTP fallback endpoints.

// Reused small response doc + buffer (ok / pump state ACKs) to keep the WS
// event-task path free of per-message heap alloc/free churn.
static JsonDocument respDoc;
static char respBuf[2048]; // Increased to 2048 for full STATUS payloads

// Deferred command state to avoid race conditions between WS task and main loop
volatile bool pendingStatusRequest = false;

// Sends a small {"type":"ok","cmd":<c>,"pumps":[bool x4]} ACK.
// Uses a local buffer to be thread-safe when called from the AsyncTCP task.
void sendOkResponse(AsyncWebSocketClient *client, const String& cmd, bool withPumps) {
    JsonDocument doc;
    char localBuf[256];
    doc["type"] = "ok";
    doc["cmd"] = cmd;
    if (withPumps) {
        JsonArray arr = doc["pumps"].to<JsonArray>();
        for (int i = 0; i < 4; i++) arr.add(pumps[i].isOn || startQueue[i].pending);
    }
    serializeJson(doc, localBuf, sizeof(localBuf));
    sendWsRaw(client, localBuf);
}

// Heavy JSON status response: serialized in the main loop to avoid stack
// overflow in the AsyncTCP task and to utilize the larger respBuf.
void sendStatusResponse() {
    if (ws.count() == 0) return;
    readHardwareWaterLevel();
    respDoc.clear();
    JsonDocument &doc = respDoc;
    doc["type"] = "status";
    doc["sensor_cadence_sec"] = savedCadenceSec;
    doc["demo_mode"] = demoModeActive;
    doc["low_water_buzz_cadence_min"] = lowWaterBuzzIntervalMin;
    doc["water_level"] = waterLevel;
    doc["water_level_raw"] = waterLevelDiscrete;
    JsonArray pumpsArr = doc["pumps"].to<JsonArray>();
    for (int i = 0; i < 4; i++) pumpsArr.add(pumps[i].isOn || startQueue[i].pending);
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
    serializeJson(doc, respBuf, sizeof(respBuf));
    ws.textAll(respBuf); // Targeted response to everyone (safe for app)
}

void sendWsRaw(AsyncWebSocketClient *client, const char* payload) {
    if (client == nullptr || client->status() != WS_CONNECTED) {
        ws.textAll(payload);
        return;
    }
    Serial.printf("[%s] [WS] TX: %s\n", getLocalTimeStr(), payload);
    size_t sent = client->text(payload);
    // AsyncWebSocketClient::text returns 0 when the message was dropped
    // (send buffer full / no space). Surface that in serial so a silent link
    // stall is visible; a stalled client here is what the app sees as a reset.
    if (sent == 0) {
        Serial.printf("[%s] [WS] WARN: text() returned 0 (client stalled or out of memory)\n",
            getLocalTimeStr());
    }
}

void handleWsCommand(String cmd, AsyncWebSocketClient *client) {
    cmd.trim();
    lastClientCommandMs = millis(); // Track client liveness
    if (cmd.length() > 128) {
        Serial.printf("[%s] [WS] RX: %s... (length: %d chars)\n", getLocalTimeStr(), cmd.substring(0, 128).c_str(), cmd.length());
    } else {
        Serial.printf("[%s] [WS] RX: %s\n", getLocalTimeStr(), cmd.c_str());
    }
    if (cmd == "READ_SENSORS") {
        Serial.printf("[%s] [APP] Sensor read & live telemetry requested by App\n", getLocalTimeStr());
        lastSensorSent = 0;
        pendingSensorRead = true;
    } else if (cmd == "PING") {
        sendOkResponse(client, cmd, false);
    } else if (cmd.startsWith("SYNC_MODE ")) {
        int seconds = cmd.substring(10).toInt();
        if (seconds < 3) seconds = 3;
        if (seconds > 30) seconds = 30;
        streamCadenceMs = (unsigned long)seconds * 1000UL;
        if (seconds != savedCadenceSec) {
            savedCadenceSec = seconds;
            Serial.printf("[%s] [SYNC] App Lifecycle -> Telemetry cadence set to %ds (%s mode)\n",
                getLocalTimeStr(), seconds, seconds == 1 ? "FOREGROUND" : "BACKGROUND");
        }

        JsonDocument okDoc;
        char localBuf[256];
        okDoc["type"] = "ok";
        okDoc["cmd"] = "SYNC_MODE";
        okDoc["cadence"] = seconds;
        serializeJson(okDoc, localBuf, sizeof(localBuf));
        sendWsRaw(client, localBuf);
    } else if (cmd == "BUZZ_TEST") {
        triggerBuzzer(3, 100, 100);
        Serial.printf("[%s] [BUZZER] Test alarm beep sequence triggered by App\n", getLocalTimeStr());
        sendOkResponse(client, cmd, false);
    } else if (cmd.startsWith("BUZZ_CADENCE ")) {
        int minutes = cmd.substring(13).toInt();
        if (minutes < 0) minutes = 0;
        if (minutes > 120) minutes = 120;
        lowWaterBuzzIntervalMin = minutes;
        Serial.printf("[%s] [BUZZER] Low water alarm repeat cadence updated -> %d minutes\n", getLocalTimeStr(), minutes);
        JsonDocument okDoc;
        char localBuf[256];
        okDoc["type"] = "ok";
        okDoc["cmd"] = "BUZZ_CADENCE";
        okDoc["minutes"] = minutes;
        serializeJson(okDoc, localBuf, sizeof(localBuf));
        sendWsRaw(client, localBuf);
    } else if (cmd == "DEMO_MODE_ON") {
        demoModeActive = true;
        readHardwareWaterLevel();
        broadcastTelemetry();
        Serial.printf("[%s] [MODE] Demo Mode -> ENABLED (3s cadence, dry-run safety bypassed)\n", getLocalTimeStr());
        sendOkResponse(client, cmd, false);
    } else if (cmd == "DEMO_MODE_OFF") {
        demoModeActive = false;
        readHardwareWaterLevel();
        broadcastTelemetry();
        Serial.printf("[%s] [MODE] Demo Mode -> DISABLED (Normal operation)\n", getLocalTimeStr());
        sendOkResponse(client, cmd, false);
    } else if (cmd == "CAL_STREAM_ON") {
        calibrationStreamActive = true;
        Serial.printf("[%s] [CALIBRATION] ADC Sensor Calibration Streaming -> STARTED\n", getLocalTimeStr());
        sendOkResponse(client, cmd, false);
    } else if (cmd == "CAL_STREAM_OFF") {
        calibrationStreamActive = false;
        Serial.printf("[%s] [CALIBRATION] ADC Sensor Calibration Streaming -> STOPPED\n", getLocalTimeStr());
        sendOkResponse(client, cmd, false);
    } else if (cmd == "HW_WATER_SENSOR_ON") {
        saveHwWaterSensorSetting(true);
        readHardwareWaterLevel();
        broadcastTelemetry();
        Serial.printf("[%s] [WATER_SENSOR] Hardware Water Sensor -> ENABLED (GPIO 16,17,18,19 active)\n", getLocalTimeStr());
        sendOkResponse(client, cmd, false);
    } else if (cmd == "HW_WATER_SENSOR_OFF") {
        saveHwWaterSensorSetting(false);
        Serial.printf("[%s] [WATER_SENSOR] Hardware Water Sensor -> DISABLED (Software history mode active)\n", getLocalTimeStr());
        sendOkResponse(client, cmd, false);
    } else if (cmd == "TELEMETRY_PAUSE") {
        telemetryPaused = true;
        Serial.printf("[%s] [APP_LIFECYCLE] App -> BACKGROUNDED (Telemetry stream paused, 30s cadence)\n", getLocalTimeStr());
        sendOkResponse(client, cmd, false);
    } else if (cmd == "TELEMETRY_RESUME") {
        telemetryPaused = false;
        Serial.printf("[%s] [APP_LIFECYCLE] App -> FOREGROUNDED (Telemetry stream resumed)\n", getLocalTimeStr());
        lastSensorSent = 0;
        pendingSensorRead = true;
        sendOkResponse(client, cmd, false);
    } else if (cmd == "STATUS") {
        Serial.printf("[%s] [WS] Query -> Device STATUS & motor configuration requested\n", getLocalTimeStr());
        pendingStatusRequest = true;
    } else if (cmd == "RESET_CONFIG") {
        preferences.begin("plantpilot", false);
        preferences.clear();
        preferences.end();
        Serial.printf("[%s] [SYSTEM] Configuration Reset requested by App -> Reverting to factory defaults\n", getLocalTimeStr());
        loadConfigs();
        initChannelPolling();
        sendOkResponse(client, cmd, false);
    } else if (cmd == "PUMP_ALL_OFF") {
        Serial.printf("[%s] [PUMP] Master STOP command -> Shutting down all active pumps\n", getLocalTimeStr());
        staggeredStopPending = true;
        nextStaggeredStop = 0;
        lastStaggerTime = 0;
        for (int i = 0; i < 4; i++) {
            startQueue[i].pending = false;
            stopQueue[i].pending = true;
        }
        sendOkResponse(client, cmd, true);
    } else if (cmd.startsWith("PUMP") && cmd.indexOf("_ON") >= 0) {
        char letter = cmd.charAt(4);
        int id = (letter >= 'A' && letter <= 'D') ? (letter - 'A') : (letter - '1');
        int amountMl = 0;
        int spaceIdx = cmd.indexOf(' ');
        if (spaceIdx > 0) {
            amountMl = cmd.substring(spaceIdx + 1).toInt();
        }
        Serial.printf("[%s] [PUMP] Manual START command for Pump %d (%dml)\n", getLocalTimeStr(), id + 1, amountMl);
        if (id >= 0 && id < 4) requestPumpStart(id, amountMl, "manual");
        sendOkResponse(client, cmd, true);
    } else if (cmd.startsWith("PUMP") && cmd.indexOf("_OFF") >= 0) {
        char letter = cmd.charAt(4);
        int id = (letter >= 'A' && letter <= 'D') ? (letter - 'A') : (letter - '1');
        Serial.printf("[%s] [PUMP] Manual STOP command for Pump %d\n", getLocalTimeStr(), id + 1);
        if (id >= 0 && id < 4) {
            stopQueue[id].pending = true;
        }
        sendOkResponse(client, cmd, true);
    } else {
        Serial.printf("[%s] [WARNING] Unrecognized WebSocket command received from App: '%s'\n", getLocalTimeStr(), cmd.c_str());
        sendOkResponse(client, cmd, false);
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
        doc["sensor_cadence_sec"] = savedCadenceSec;
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

        // Sync water level from App's estimate, then immediately re-evaluate hardware pins
        if (doc["water_level"].is<int>()) {
            waterLevel = constrain((int)doc["water_level"], 0, 100);
        }
        readHardwareWaterLevel(); // Always re-evaluate hardware sensor pins

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

            int newVersion = m.containsKey("version") ? (int)m["version"] : motorConfigs[idx].version;
            unsigned long incomingLastModified = m.containsKey("last_modified") ? (unsigned long)m["last_modified"] : motorConfigs[idx].lastModified;

            // Two-way sync rule: apply when the incoming config is newer by
            // version OR timestamp; otherwise keep the stored (newer) config.
            // If both are the same, we still check for content changes below.
            bool isNewer = (newVersion > motorConfigs[idx].version) ||
                           (incomingLastModified > motorConfigs[idx].lastModified);

            if (isNewer || newVersion == 0) {
                bool changed = false;
                const char* modeStr = m["mode"] | "off";
                bool newEnabled = (strcmp(modeStr, "off") != 0);
                bool newAuto = (strcmp(modeStr, "auto") == 0);
                int newAmount = constrain((int)m["amount_ml"], 0, MAX_WATERING_ML);
                int newThreshold = constrain((int)m["threshold"], 0, 100);
                int newInterval = max((int)m["min_interval_hours"], 0);
                unsigned long newLastWatered = m["last_watered"] | 0UL;
                int newRate = constrain((int)m["ml_per_sec"] | DEFAULT_ML_PER_SECOND, 0, MAX_ML_PER_SECOND);
                int newMaxRun = max((int)m["max_runtime_minutes"] | 1, 0);
                bool newStopDisc = m.containsKey("stop_on_disconnect") ? (bool)m["stop_on_disconnect"] : motorConfigs[idx].stopOnDisconnect;

                if (newEnabled != motorConfigs[idx].isEnabled || newAuto != motorConfigs[idx].autoMode ||
                    newAmount != motorConfigs[idx].amountMl || newThreshold != motorConfigs[idx].moistureThreshold ||
                    newInterval != motorConfigs[idx].minIntervalHours || newLastWatered != motorConfigs[idx].lastAutoWaterEpoch ||
                    newRate != motorConfigs[idx].mlPerSecond || newMaxRun != motorConfigs[idx].maxRuntimeMinutes ||
                    newStopDisc != motorConfigs[idx].stopOnDisconnect || newVersion != motorConfigs[idx].version ||
                    incomingLastModified != motorConfigs[idx].lastModified) {
                    changed = true;
                }

                JsonArray schedules = m["schedules"];
                if (schedules.size() != motorConfigs[idx].scheduleCount) changed = true;
                else {
                    for (int i = 0; i < motorConfigs[idx].scheduleCount; i++) {
                        if (schedules[i]["hour"] != motorConfigs[idx].schedules[i].hour ||
                            schedules[i]["minute"] != motorConfigs[idx].schedules[i].minute) {
                            changed = true; break;
                        }
                    }
                }

                if (changed) {
                    motorConfigs[idx].isEnabled = newEnabled;
                    motorConfigs[idx].autoMode = newAuto;
                    motorConfigs[idx].amountMl = newAmount;
                    motorConfigs[idx].moistureThreshold = newThreshold;
                    motorConfigs[idx].minIntervalHours = newInterval;
                    motorConfigs[idx].lastAutoWaterEpoch = newLastWatered;
                    motorConfigs[idx].mlPerSecond = newRate;
                    motorConfigs[idx].maxRuntimeMinutes = newMaxRun;
                    motorConfigs[idx].stopOnDisconnect = newStopDisc;
                    motorConfigs[idx].version = newVersion;
                    motorConfigs[idx].lastModified = incomingLastModified;

                    motorConfigs[idx].scheduleCount = min((int)schedules.size(), 5);
                    for (int i = 0; i < motorConfigs[idx].scheduleCount; i++) {
                        motorConfigs[idx].schedules[i].hour = constrain((int)schedules[i]["hour"], 0, 23);
                        motorConfigs[idx].schedules[i].minute = constrain((int)schedules[i]["minute"], 0, 59);
                    }

                    saveMotorConfig(id);
                    refreshChannelPolling();
                    Serial.printf("[%s] [CONFIG] Pump %d Config Updated -> Mode: %s | Water: %dml | Threshold: %d%% | Cooldown: %dh | FlowRate: %dml/s | MaxRun: %dmin (v%d)\n",
                        getLocalTimeStr(), id, modeStr, newAmount, newThreshold, newInterval, newRate, newMaxRun, motorConfigs[idx].version);
                    updated.add(id);
                } else {
                    ignored.add(id);
                }
            } else {
                Serial.printf("[%s] [CONFIG] Pump %d Config Skipped -> Device version (v%d) newer or equal to incoming (v%d)\n",
                    getLocalTimeStr(), id, motorConfigs[idx].version, newVersion);
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
            Serial.printf("[%s] [ERROR] /api/calibrate failed -> Body size (%u bytes) exceeds buffer\n", getLocalTimeStr(), total);
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
            Serial.printf("[%s] [ERROR] /api/calibrate failed -> Invalid JSON (%s)\n", getLocalTimeStr(), err.c_str());
            request->send(400, "application/json", "{\"status\":\"error\",\"message\":\"invalid JSON\"}");
            return;
        }
        int motor = doc["motor"];
        int dry = doc["dry"];
        int wet = doc["wet"];
        if (motor < 1 || motor > 4 || dry <= wet) {
            Serial.printf("[%s] [WARNING] /api/calibrate rejected -> Motor %d parameters invalid (Dry: %d, Wet: %d)\n", getLocalTimeStr(), motor, dry, wet);
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
        refreshChannelPolling();
        soilMoisture[idx] = readMoisture(idx);
        Serial.printf("[%s] [CALIBRATION] Soil Sensor %d Calibrated -> Dry ADC: %d | Wet ADC: %d | Flow Rate: %d ml/s (v%d)\n",
            getLocalTimeStr(), motor, dry, wet, motorConfigs[idx].mlPerSecond, motorConfigs[idx].version);
        request->send(200, "application/json", "{\"status\":\"ok\",\"sensor\":" + String(motor) + "}");
    });

    for (int m = 1; m <= 4; m++) {
        char path[32]; sprintf(path, "/api/motor/%d/water_now", m);
        server.on(path, HTTP_POST, [m](AsyncWebServerRequest *request){
            int rate = 0;
            int amount = motorConfigs[m-1].amountMl;
            if (request->hasParam("rate")) {
                rate = request->getParam("rate")->value().toInt();
            }
            if (request->hasParam("amount")) {
                amount = request->getParam("amount")->value().toInt();
            }
            if (rate > 0 && rate <= MAX_ML_PER_SECOND) {
                motorConfigs[m-1].mlPerSecond = rate;
                Serial.printf("[%s] [PUMP] Rate override for Pump %d → %d ml/s\n", getLocalTimeStr(), m, rate);
            }
            requestPumpStart(m-1, amount, "manual");
            request->send(200, "application/json", "{\"status\":\"ok\"}");
        });
    }
}

const char* getWifiReasonStr(uint8_t reason) {
    switch (reason) {
        case 1:   return "UNSPECIFIED";
        case 2:   return "AUTH_EXPIRE";
        case 3:   return "AUTH_LEAVE";
        case 4:   return "ASSOC_EXPIRE";
        case 5:   return "ASSOC_TOOMANY";
        case 6:   return "NOT_AUTHED";
        case 7:   return "NOT_ASSOCED";
        case 8:   return "ASSOC_LEAVE";
        case 15:  return "4WAY_HANDSHAKE_TIMEOUT / BAD_PASSWORD";
        case 201: return "NO_AP_FOUND / NOT_IN_RANGE";
        case 202: return "AUTH_FAIL / BAD_PASSWORD";
        case 203: return "ASSOC_FAIL";
        case 204: return "HANDSHAKE_TIMEOUT";
        case 205: return "CONNECTION_FAIL";
        default:  return "UNKNOWN_REASON";
    }
}

// =====================================================================================
//  SECTION 12: WEBSOCKET EVENT DISPATCHER & COMMAND PARSER
// =====================================================================================

// Wi-Fi Performance Mode: modem sleep is never enabled on this device.
// WIFI_PS_MIN_MODEM causes ESP32 radio sleep cycles that drop incoming TCP/WS pings.
// WiFi.setSleep(false) is called once at connect time; this helper is kept only
// as a log point for diagnostics (e.g. to confirm performance mode is active).
static unsigned long pendingModemSleepEnableMs = 0;

void setDynamicModemSleep(bool /*enableSleep*/, const char* reason) {
    WiFi.setSleep(false); // Always performance mode — modem sleep intentionally disabled
    pendingModemSleepEnableMs = 0;
    Serial.printf("[%s] [WIFI] Performance mode enforced (%s) — Wi-Fi modem sleep OFF\n",
        getLocalTimeStr(), reason);
}

void onWiFiEvent(WiFiEvent_t event, WiFiEventInfo_t info) {
    switch (event) {
        case ARDUINO_EVENT_WIFI_STA_CONNECTED:
            Serial.printf("[%s] [WIFI] Connected to AP: '%s'\n", getLocalTimeStr(), WiFi.SSID().c_str());
            break;
        case ARDUINO_EVENT_WIFI_STA_GOT_IP:
            Serial.printf("[%s] [WIFI] IP Assigned: %s (Subnet: %s, GW: %s, RSSI: %d dBm)\n",
                getLocalTimeStr(),
                WiFi.localIP().toString().c_str(),
                WiFi.subnetMask().toString().c_str(),
                WiFi.gatewayIP().toString().c_str(),
                (int)WiFi.RSSI());
            wasConnected = true;
            // Maintain continuous performance mode (0ms latency) for instant app connection
            setDynamicModemSleep(false, "WiFi GOT_IP Connected");
            if (pendingRestart) { restartTime = millis() + 10000; }
            break;
        case ARDUINO_EVENT_WIFI_STA_DISCONNECTED:
            lastWiFiReason = info.wifi_sta_disconnected.reason;
            cachedWifiSsid = "";
            Serial.printf("[%s] [WIFI] Disconnected! Reason Code %d: %s\n",
                getLocalTimeStr(), lastWiFiReason, getWifiReasonStr(lastWiFiReason));
            wasConnected = false;
            break;
        default: break;
    }
}

// =====================================================================================
//  SECTION 13: SYSTEM INITIALIZATION & MAIN EXECUTION LOOP
// =====================================================================================

void setup() {
    Serial.begin(115200);
    delay(500);
    Serial.println("\n=============================================");
    Serial.println("         PlantPilot ESP32 Firmware           ");
    Serial.println("=============================================");

    const char* resetReason;
    switch (esp_reset_reason()) {
        case ESP_RST_POWERON:     resetReason = "POWER_ON (Cold Boot)"; break;
        case ESP_RST_EXT:         resetReason = "EXTERNAL_RESET (EN Pin / Reset Button)"; break;
        case ESP_RST_SW:          resetReason = "SOFTWARE_RESET (ESP.restart)"; break;
        case ESP_RST_PANIC:       resetReason = "PANIC / CRASH (Exception / Core Dump)"; break;
        case ESP_RST_INT_WDT:     resetReason = "INTERRUPT_WATCHDOG (CPU Deadlock)"; break;
        case ESP_RST_TASK_WDT:    resetReason = "TASK_WATCHDOG (Loop Freeze)"; break;
        case ESP_RST_WDT:         resetReason = "OTHER_WATCHDOG"; break;
        case ESP_RST_DEEPSLEEP:   resetReason = "DEEP_SLEEP_AWAKE"; break;
        case ESP_RST_BROWNOUT:    resetReason = "BROWNOUT_WARNING (Power Supply Sag < 4.5V)"; break;
        case ESP_RST_SDIO:        resetReason = "SDIO"; break;
        default:                  resetReason = "UNKNOWN"; break;
    }
    Serial.printf("[SYSTEM] Boot Reset Reason : %s\n", resetReason);
    Serial.printf("[SYSTEM] Chip Model & Rev  : %s (Rev %d, %d Cores @ %d MHz)\n",
        ESP.getChipModel(), ESP.getChipRevision(), ESP.getChipCores(), ESP.getCpuFreqMHz());
    Serial.printf("[SYSTEM] Flash Memory      : %u KB @ %u MHz\n",
        ESP.getFlashChipSize() / 1024, ESP.getFlashChipSpeed() / 1000000);
    Serial.printf("[SYSTEM] MAC Address       : %s\n", WiFi.macAddress().c_str());
    Serial.printf("[SYSTEM] Initial Free Heap : %u bytes (Min: %u bytes)\n",
        ESP.getFreeHeap(), ESP.getMinFreeHeap());
    Serial.println("=============================================\n");
    initStatusLed(); initButton(); initRelays(); initBuzzer(); loadConfigs(); initChannelPolling();

    loadHistoryFromNVS();

    // Load persisted time and rebase against current boot millis
    preferences.begin("time", true);
    TimeSyncData timeData = {0, 0};
    preferences.getBytes("sync", &timeData, sizeof(timeData));
    preferences.end();
    if (timeData.epoch != 0) {
        bootEpochOffset = timeData.epoch;
        Serial.printf("[TIME] Boot epoch offset: %lu (persisted epoch %lu)\n", bootEpochOffset, timeData.epoch);
    }

    WiFi.persistent(false); WiFi.setAutoReconnect(true); WiFi.onEvent(onWiFiEvent);
    setDynamicModemSleep(true, "Setup Standby");
    preferences.begin("wifi", true); String ssid = preferences.getString("ssid", ""); String pass = preferences.getString("pass", ""); preferences.end();
    if (ssid.length() > 0) { Serial.printf("[%s] [WIFI] Target: %s\n", getLocalTimeStr(), ssid.c_str()); WiFi.begin(ssid.c_str(), pass.c_str()); }
    else { Serial.printf("[%s] [WIFI] No credentials. Setup Mode.\n", getLocalTimeStr()); startSetupMode(); }
    if (MDNS.begin(HOSTNAME)) MDNS.addService("http", "tcp", 80);
    setupApi();
    ws.onEvent([](AsyncWebSocket *server, AsyncWebSocketClient *client, AwsEventType type, void *arg, uint8_t *data, size_t len){
        if (type == WS_EVT_CONNECT) {
            Serial.printf("[%s] [WS] Client connected (ip: %s, total: %u, heap: %u)\n",
                getLocalTimeStr(), client->remoteIP().toString().c_str(), ws.count(), ESP.getFreeHeap());
            
            // Instantly disable modem sleep for 0ms sub-millisecond socket performance
            setDynamicModemSleep(false, "App client connected");

            client->text("PlantPilot Ready");
            Serial.println("[WS] TX: PlantPilot Ready");
            // New client always gets telemetry, regardless of pause state.
            telemetryPaused = false;
            // Include full per-motor config in the first telemetry frame so a
            // freshly connected app gets device state without a separate request.
            motorsLastSentMs = 0;
            // Delay the first telemetry push by 1s to avoid flooding the TCP
            // buffer right after the handshake (PlantPilot Ready + SYNC_MODE ack).
            firstTelemetryAfterConnectMs = millis() + 1000UL;
            // Reset stale-client timer so the new connection gets a full grace period.
            lastClientCommandMs = millis();
        } else if (type == WS_EVT_DISCONNECT) {
            Serial.printf("[%s] [WS] Client disconnected (total: %u)\n",
                getLocalTimeStr(), ws.count());
            // If all clients disconnected and any pump has stopOnDisconnect, queue a stop
            if (ws.count() == 0) {
                // Schedule modem sleep to re-enable after a 5-second grace period
                pendingModemSleepEnableMs = millis() + 5000UL;

                for (int i = 0; i < 4; i++) {
                    if (motorConfigs[i].stopOnDisconnect && pumps[i].isOn) {
                        Serial.printf("[%s] [PUMP] Stopping %s on disconnect (stopOnDisconnect=true)\n",
                            getLocalTimeStr(), pumps[i].name);
                        staggeredStopPending = true;
                        nextStaggeredStop = 0;
                        lastStaggerTime = 0;
                    }
                }
            }
        } else if (type == WS_EVT_DATA) {
            // Accumulate data across possible fragments
            String cmd = "";
            cmd.reserve(len);
            for (size_t i = 0; i < len; i++) cmd += (char)data[i];
            if (cmd.length() > 0) handleWsCommand(cmd, client);
        } else if (type == WS_EVT_ERROR) {
            Serial.printf("[%s] [WS] ERROR: code=%d, client=%u\n",
                getLocalTimeStr(), (int)(intptr_t)arg, client->id());
        }
    });
    server.addHandler(&ws); server.begin();
}

void loop() {
    unsigned long loopStart = millis();
    updateStatusLed();
    updateButton();
    updateBuzzer();
    checkLowWaterBuzzer();

    // Dynamic Wi-Fi Modem Sleep Grace Timer:
    // When all WebSocket clients disconnect, wait 5 seconds before returning to power-saving modem sleep.
    if (pendingModemSleepEnableMs > 0 && loopStart >= pendingModemSleepEnableMs) {
        pendingModemSleepEnableMs = 0;
        if (ws.count() == 0) {
            setDynamicModemSleep(true, "App closed / no active clients");
        }
    }

    // 1. Process Global Start Queue (Power Safety - Non-blocking)
    // Sequential Scheduling: Only trigger a queued pump if no other motor is running.
    if (!isMotorBusy() && !staggeredStopPending && (loopStart - lastGlobalStart >= STAGGER_INTERVAL_MS)) {
        for (int i = 0; i < 4; i++) {
            if (startQueue[i].pending) {
                triggerPump(i, startQueue[i].amount, startQueue[i].source);
                // Only dequeue if the pump actually started
                if (pumps[i].isOn) startQueue[i].pending = false;
                lastGlobalStart = loopStart;
                break; // Only process one per interval
            }
        }
    }

    // 2. Process Manual Staggered Stop (UI Smoothness)
    if (staggeredStopPending && (loopStart - lastStaggerTime >= STAGGER_INTERVAL_MS)) {
        requestPumpStop(nextStaggeredStop);
        nextStaggeredStop++;
        lastStaggerTime = loopStart;
        if (nextStaggeredStop >= 4) staggeredStopPending = false;
    }

    // 3. Process Individual Stop Queue (Safety/Thread-safety)
    for (int i = 0; i < 4; i++) {
        if (stopQueue[i].pending) {
            stopPump(i);
            stopQueue[i].pending = false;
        }
    }

    // 4. Process Deferred STATUS request
    if (pendingStatusRequest) {
        pendingStatusRequest = false;
        sendStatusResponse();
    }

    if (pendingRestart && loopStart >= restartTime) ESP.restart();
    if (WiFi.status() == WL_CONNECTED) {
        // Cache SSID on first successful connection
        if (cachedWifiSsid.length() == 0) cachedWifiSsid = WiFi.SSID();
        didRadioReset = false; // Reset the 10min recovery flag
        static unsigned long lastNtpSync = 0;
        if (loopStart - lastNtpSync > 3600000 || lastNtpSync == 0) { lastNtpSync = loopStart; syncWithNtp(); }
    } else if (WiFi.status() != WL_CONNECTED && (WiFi.getMode() & WIFI_STA)) {
        if (lastWifiRetry == 0) lastWifiRetry = loopStart;
        unsigned long offlineMs = loopStart - lastWifiRetry;

        // Escalating recovery:
        // 1. Restart at 30min
        if (offlineMs > 1800000UL) {
            Serial.println("[WIFI] Offline 30min, restarting...");
            ESP.restart();
        }
        // 2. Radio reset (disconnect+begin) at 10min
        else if (offlineMs > 600000UL) {
            if (!didRadioReset) {
                Serial.println("[WIFI] Offline 10min, radio reset...");
                WiFi.disconnect();
                delay(500);
                WiFi.begin();
                didRadioReset = true;
            }
        }
        // 3. Simple begin retry at 1min (faster recovery for minor glitches)
        else if (offlineMs > 60000UL) {
            static unsigned long lastBeginRetry = 0;
            if (loopStart - lastBeginRetry > 60000UL) {
                Serial.println("[WIFI] Offline 1min, retrying begin...");
                WiFi.begin();
                lastBeginRetry = loopStart;
            }
        }
    } else {
        lastWifiRetry = 0;
    }

    if (Serial.available()) {
        String cmd = Serial.readStringUntil('\n'); cmd.trim();
        if (cmd == "WIFI_RESET") { preferences.begin("wifi", false); preferences.clear(); preferences.end(); WiFi.disconnect(true, true); delay(1000); ESP.restart(); }
    }
    if (setupModeActive) dnsServer.processNextRequest();

    // Clean up dead TCP clients (only removes already-disconnected sockets,
    // does not timeout alive connections).
    ws.cleanupClients();

    // Low-heap guard: logs once per threshold crossing and triggers a protective
    // restart if the heap stays critically low for too long (5 minutes).
    {
        static unsigned long lastLowHeapLog = 0;
        static unsigned long criticalHeapStart = 0;
        static bool heapWarned = false;
        size_t freeHeap = ESP.getFreeHeap();
        size_t largest = ESP.getMaxAllocHeap();

        // Critical thresholds: < 48KB total or < 16KB largest block
        if (freeHeap < 48000 || largest < 16000) {
            if (criticalHeapStart == 0) criticalHeapStart = loopStart;

            if (!heapWarned || (loopStart - lastLowHeapLog > 60000)) {
                heapWarned = true;
                lastLowHeapLog = loopStart;
                Serial.printf("[%s] [SYS] WARNING: critical heap (free=%u, largest=%u)\n",
                    getLocalTimeStr(), (unsigned)freeHeap, (unsigned)largest);
            }

            // Protective restart after 5 minutes of persistent critical low heap
            if (loopStart - criticalHeapStart > 300000UL) {
                Serial.println("[SYS] FATAL: Heap critical for 5min. Restarting for health.");
                ESP.restart();
            }
        } else {
            heapWarned = false;
            criticalHeapStart = 0;
        }
    }

    updatePumps();

    // Stale client detection: if a WS client is connected but hasn't sent any
    // command for STALE_CLIENT_TIMEOUT_MS, treat it as dead and trigger
    // stopOnDisconnect logic.  Catches silent disconnects (app killed/crashed
    // without clean WebSocket close).
    // Using (long) cast handles potential millis() wrap and prevents underflow
    // when lastClientCommandMs is updated just after loopStart is captured.
    if (ws.count() > 0 && lastClientCommandMs > 0 &&
        (long)(loopStart - lastClientCommandMs) > (long)STALE_CLIENT_TIMEOUT_MS) {
        Serial.printf("[%s] [WS] Stale client detected (no commands for %lus)\n",
            getLocalTimeStr(), (loopStart - lastClientCommandMs) / 1000);
        for (int i = 0; i < 4; i++) {
            if (motorConfigs[i].stopOnDisconnect && pumps[i].isOn) {
                Serial.printf("[%s] [PUMP] Stopping %s on stale client (stopOnDisconnect=true)\n",
                    getLocalTimeStr(), pumps[i].name);
                staggeredStopPending = true;
                nextStaggeredStop = 0;
                lastStaggerTime = 0;
            }
        }
        lastClientCommandMs = 0; // Reset to prevent loop logging spam
    }
    // In Demo Mode only: Poll hardware water level every 1000ms (1s) for live testing
    static unsigned long lastWaterCheckMs = 0;
    if (demoModeActive && (loopStart - lastWaterCheckMs > 1000)) {
        lastWaterCheckMs = loopStart;
        int prevLvl = waterLevelDiscrete;
        int newLvl = readHardwareWaterLevel();
        if (newLvl != prevLvl && ws.count() > 0) {
            broadcastTelemetry();
        }
    }

    static unsigned long lastTele = 0;
    unsigned long telemetryMs = (calibrationStreamActive || demoModeActive) ? 3000UL
                    : (ws.count() > 0 ? streamCadenceMs : 60000UL);
    if (!telemetryPaused && (pendingSensorRead || (loopStart >= firstTelemetryAfterConnectMs && loopStart - lastTele > telemetryMs))) {
        // READ_SENSORS from the WS handler defers the heavy JSON build to here,
        // on the main loop stack. Also reset the timer so the forced push isn't
        // immediately followed by a regular cadence push.
        pendingSensorRead = false;
        lastTele = loopStart;
        broadcastTelemetry();
    }

    static unsigned long lastCheck = 0;
    if (loopStart - lastCheck > 1000) { lastCheck = loopStart; checkSchedules(); checkAutoWatering(); }

    // Track max loop iteration time for diagnostics (shared with broadcastTelemetry via g_maxLoopMs)
    unsigned long loopMs = millis() - loopStart;
    if (loopStart - g_lastLoopReset > 60000) {
        g_maxLoopMs = 0;
        g_lastLoopReset = loopStart;
    }
    if (loopMs > g_maxLoopMs) g_maxLoopMs = loopMs;

    // Yield 1ms to FreeRTOS IDLE task to reduce CPU temperature & power load
    delay(1);
}
