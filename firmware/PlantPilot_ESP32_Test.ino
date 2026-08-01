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
const int ML_PER_SECOND = 10; // Pump flow rate

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

// WiFi Resilience
unsigned long lastWifiRetry = 0;
uint8_t lastWiFiReason = 0;
bool wasConnected = false;
unsigned long restartTime = 0;
bool pendingRestart = false;

// --- DATA STRUCTURES ---
struct WateringSchedule {
    int hour;
    int minute;
};

struct MotorSettings {
    bool isEnabled;
    bool autoMode;
    int amountMl;
    int moistureThreshold;
    int version;
    WateringSchedule schedules[5];
    int scheduleCount;
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
int waterLevel = 100;

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
    preferences.putBytes("sync", &data, sizeof(data));
    preferences.end();
    timeSet = true;
    Serial.printf("[TIME] Saved Sync: %lu at %lu ms\n", epoch, data.syncMillis);
}

unsigned long getNow() {
    preferences.begin("time", true);
    TimeSyncData data = {0, 0};
    preferences.getBytes("sync", &data, sizeof(data));
    preferences.end();
    if (data.epoch == 0) return 0;
    return data.epoch + (millis() - data.syncMillis) / 1000;
}

void broadcastTelemetry() {
    if (ws.count() == 0) return;
    JsonDocument doc;
    doc["type"] = "telemetry";
    doc["water_level"] = waterLevel;

    JsonArray soil = doc["soil"].to<JsonArray>();
    JsonArray rawSoil = doc["raw_soil"].to<JsonArray>();
    for (int i = 0; i < 4; i++) {
        int raw = analogRead(pumps[i].sensorPin);
        rawSoil.add(raw);
        int percent = map(raw, 4095, 1000, 0, 100);
        soil.add(constrain(percent, 0, 100));
    }

    doc["wifi_rssi"] = WiFi.RSSI();
    doc["wifi_ssid"] = WiFi.SSID();
    doc["uptime_sec"] = millis() / 1000;
    doc["free_heap"] = ESP.getFreeHeap();
    doc["epoch"] = getNow();

    JsonArray pumpsState = doc["pumps"].to<JsonArray>();
    for (int i = 0; i < 4; i++) {
        pumpsState.add(pumps[i].isOn);
    }

    String msg;
    serializeJson(doc, msg);
    ws.textAll(msg);
}

String getLocalTimeStr() {
    unsigned long nowEpoch = getNow();
    if (nowEpoch < 1600000000) return "00:00:00";
    time_t localEpoch = (time_t)(nowEpoch + 21600);
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
        saveTimeSync((unsigned long)now);
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

int readMoisture(int index) {
    if (index < 0 || index >= 4) return 0;
    int raw = analogRead(pumps[index].sensorPin);
    int percent = map(raw, 4095, 1000, 0, 100);
    return constrain(percent, 0, 100);
}

void triggerPump(int index, int amountMl, const char* source = "manual") {
    if (index < 0 || index >= 4 || pumps[index].isOn) return;

    int durationMs = 0;
    if (amountMl > 0) {
        durationMs = (amountMl * 1000) / ML_PER_SECOND;
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
        doc["soil_after"] = readMoisture(index);

        String msg;
        serializeJson(doc, msg);
        ws.textAll(msg);
    }

    pumps[index].startTime = 0;
    pumps[index].duration = 0;
}

void updatePumps() {
    unsigned long now = millis();
    for (int i = 0; i < 4; i++) {
        // Only auto-stop if duration is > 0
        if (pumps[i].isOn && pumps[i].duration > 0 && (now - pumps[i].startTime >= pumps[i].duration)) {
            stopPump(i);
        }
    }
}

// --- PERSISTENCE ---

void saveMotorConfig(int id) {
    char key[16];
    sprintf(key, "motor%d", id);
    preferences.begin("plantpilot", false);
    preferences.putBytes(key, &motorConfigs[id-1], sizeof(MotorSettings));
    preferences.end();
    Serial.printf("[%s] [NVS] Saved Pump %d configuration\n", getLocalTimeStr().c_str(), id);
}

void loadConfigs() {
    preferences.begin("plantpilot", true);
    for (int i = 1; i <= 4; i++) {
        char key[16]; sprintf(key, "motor%d", i);
        if (preferences.isKey(key)) {
            preferences.getBytes(key, &motorConfigs[i-1], sizeof(MotorSettings));
        } else {
            motorConfigs[i-1].isEnabled = true;
            motorConfigs[i-1].autoMode = false;
            motorConfigs[i-1].amountMl = 50;
            motorConfigs[i-1].moistureThreshold = 30;
            motorConfigs[i-1].version = 0;
            motorConfigs[i-1].scheduleCount = 0;
        }
    }
    preferences.end();
}

// --- SCHEDULING ---

int lastTriggeredMin[4] = {-1, -1, -1, -1};
int lastTriggeredHour[4] = {-1, -1, -1, -1};

void checkSchedules() {
    unsigned long nowEpoch = getNow();
    if (nowEpoch < 1600000000) return;

    time_t localEpoch = (time_t)(nowEpoch + 21600);
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
                Serial.printf("[%s] [SCHED] Triggering %s at %02d:%02d\n", getLocalTimeStr().c_str(), pumps[i].name, curHr, curMin);
                triggerPump(i, motorConfigs[i].amountMl, "scheduled");
                lastTriggeredMin[i] = curMin;
                lastTriggeredHour[i] = curHr;
            }
        }
    }
}

void checkAutoWatering() {
    for (int i = 0; i < 4; i++) {
        if (!motorConfigs[i].isEnabled || !motorConfigs[i].autoMode) continue;
        int moisture = readMoisture(i);
        soilMoisture[i] = moisture;
        if (moisture < motorConfigs[i].moistureThreshold) {
            Serial.printf("[%s] [AUTO] Low moisture on %s: %d%% < %d%%\n", getLocalTimeStr().c_str(), pumps[i].name, moisture, motorConfigs[i].moistureThreshold);
            triggerPump(i, motorConfigs[i].amountMl, "auto");
        }
    }
}

// --- SETUP MODE (SOFT AP) ---

const char SETUP_HTML[] PROGMEM = R"rawliteral(<!DOCTYPE html><html><head><title>PlantPilot Setup</title><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font-family:sans-serif;background:#121212;color:#B6FF3C;padding:20px;text-align:center}select,input{width:100%;padding:12px;margin:10px 0;border:1px solid #B6FF3C;background:#1e1e1e;color:white;border-radius:8px;box-sizing:border-box}.show-pass{margin:5px 0;color:#aaa;font-size:.9em;display:flex;align-items:center;cursor:pointer}.show-pass input{width:auto;margin-right:10px}button{background:#B6FF3C;color:#121212;border:none;padding:15px;width:100%;font-weight:bold;border-radius:8px;cursor:pointer;margin-top:10px}.refresh-btn{background:#333;color:#B6FF3C;border:1px solid #B6FF3C;margin-bottom:20px}</style><script>function togglePass(){var x=document.getElementById("pass");x.type=x.type==="password"?"text":"password"}</script></head><body><h1>PlantPilot Setup</h1><p>Connect your ESP32 to WiFi</p><button class="refresh-btn" onclick="location.reload()">Refresh Networks</button><form action="/save" method="POST"><label style="display:block;text-align:left">Select WiFi:</label><select name="ssid" required>{{SCAN_RESULTS}}</select><label style="display:block;text-align:left;margin-top:10px">Password:</label><input type="password" id="pass" name="pass" placeholder="Enter Password"><div class="show-pass"><input type="checkbox" onclick="togglePass()"> Show Password</div><button type="submit">Save and Connect</button></form></body></html>)rawliteral";

const char CONNECTING_HTML[] PROGMEM = R"rawliteral(<!DOCTYPE html><html><head><title>PlantPilot Connected</title><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font-family:sans-serif;background:#121212;color:#B6FF3C;padding:20px;text-align:center}.loader{border:4px solid #1e1e1e;border-top:4px solid #B6FF3C;border-radius:50%;width:40px;height:40px;animation:spin 2s linear infinite;margin:20px auto}@keyframes spin{0%{transform:rotate(0deg)}100%{transform:rotate(360deg)}}#status{font-size:1.2em;margin-bottom:10px}.host{background:#1e1e1e;border:1px solid #333;padding:10px;border-radius:8px;margin:10px 0;color:white;font-weight:bold}#ip-box{background:#1e1e1e;border:1px solid #B6FF3C;padding:15px;border-radius:8px;margin:20px 0;display:none}.note{color:#aaa;font-size:.9em;margin-bottom:8px}#ip{font-size:1.5em;font-weight:bold;color:white;display:block;margin-bottom:10px}button{background:#B6FF3C;color:#121212;border:none;padding:10px 20px;font-weight:bold;border-radius:5px;cursor:pointer}#countdown{margin-top:20px;color:#aaa;display:none}</style><script>let connected=false;function checkStatus(){fetch('/api/wifi_status').then(r=>r.json()).then(data=>{if(data.status===3&&data.ip!=="0.0.0.0"){document.getElementById("status").innerText="Connected!";document.getElementById("ip").innerText=data.ip;document.getElementById("ip-box").style.display="block";document.querySelector(".loader").style.display="none";document.getElementById("countdown").style.display="block";if(!connected){connected=true;startCountdown()}}else{setTimeout(checkStatus,1000)}}).catch(()=>setTimeout(checkStatus,1000))}function copyIp(){const ip=document.getElementById("ip").innerText;navigator.clipboard.writeText(ip).then(()=>{const btn=document.getElementById("copy-btn");btn.innerText="Copied!";setTimeout(()=>btn.innerText="Copy IP",2000)})}function startCountdown(){let count=10;setInterval(()=>{count--;document.getElementById("timer").innerText=count},1000)}window.onload=checkStatus;</script></head><body><h1>PlantPilot</h1><div id="status">Connecting to WiFi...</div><div class="loader"></div><div class="host">App connects to <b>plantpilot.local</b> by default</div><div id="ip-box"><div class="note">If the app can't connect, enter this IP:</div><span id="ip"></span><button id="copy-btn" onclick="copyIp()">Copy IP</button></div><div id="countdown">Restarting in <span id="timer">10</span> seconds...</div></body></html>)rawliteral";

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
    WiFi.mode(WIFI_AP_STA);
    WiFi.softAP(SETUP_SSID);
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
        preferences.begin("wifi", false); preferences.putString("ssid", request->arg("ssid")); preferences.putString("pass", request->arg("pass")); preferences.end();
        WiFi.begin(request->arg("ssid").c_str(), request->arg("pass").c_str());
        request->send(200, "text/html", String(FPSTR(CONNECTING_HTML)));
        pendingRestart = true; restartTime = millis() + 60000;
    });
    server.onNotFound([](AsyncWebServerRequest *request){ request->redirect("/"); });
    server.begin();
}

// --- API HANDLERS ---

void handleWsCommand(String cmd, AsyncWebSocketClient *client) {
    cmd.trim();
    if (cmd == "STATUS") {
        String status = ""; for (int i = 0; i < 4; i++) status += "Pump" + String(i + 1) + ": " + (pumps[i].isOn ? "ON" : "OFF") + (i < 3 ? "\n" : "");
        client->text(status);
    } else if (cmd == "PUMP_ALL_ON") {
        unsigned long allStart = millis();
        for (int i = 0; i < 4; i++) {
            triggerPump(i, 0, "manual");
            while (millis() - allStart < (unsigned long)(i + 1) * 100) { yield(); }
        }
        // Send JSON response with actual pump states
        JsonDocument doc;
        doc["type"] = "ok";
        doc["cmd"] = cmd;
        JsonArray arr = doc["pumps"].to<JsonArray>();
        for (int i = 0; i < 4; i++) arr.add(pumps[i].isOn);
        String resp; serializeJson(doc, resp);
        client->text(resp);
    } else if (cmd == "PUMP_ALL_OFF") {
        for (int i = 0; i < 4; i++) stopPump(i);
        JsonDocument doc;
        doc["type"] = "ok";
        doc["cmd"] = cmd;
        JsonArray arr = doc["pumps"].to<JsonArray>();
        for (int i = 0; i < 4; i++) arr.add(pumps[i].isOn);
        String resp; serializeJson(doc, resp);
        client->text(resp);
    } else if (cmd.startsWith("PUMP") && cmd.endsWith("_ON")) {
        char letter = cmd.charAt(4);
        int id = (letter >= 'A' && letter <= 'D') ? (letter - 'A') : (letter - '1');
        if (id >= 0 && id < 4) triggerPump(id, 0, "manual");
        JsonDocument doc;
        doc["type"] = "ok";
        doc["cmd"] = cmd;
        JsonArray arr = doc["pumps"].to<JsonArray>();
        for (int i = 0; i < 4; i++) arr.add(pumps[i].isOn);
        String resp; serializeJson(doc, resp);
        client->text(resp);
    } else if (cmd.startsWith("PUMP") && cmd.endsWith("_OFF")) {
        char letter = cmd.charAt(4);
        int id = (letter >= 'A' && letter <= 'D') ? (letter - 'A') : (letter - '1');
        if (id >= 0 && id < 4) stopPump(id);
        JsonDocument doc;
        doc["type"] = "ok";
        doc["cmd"] = cmd;
        JsonArray arr = doc["pumps"].to<JsonArray>();
        for (int i = 0; i < 4; i++) arr.add(pumps[i].isOn);
        String resp; serializeJson(doc, resp);
        client->text(resp);
    }
}

void setupApi() {
    server.on("/api/sync", HTTP_POST, [](AsyncWebServerRequest *request){}, NULL,
      [](AsyncWebServerRequest *request, uint8_t *data, size_t len, size_t index, size_t total) {
        JsonDocument doc;
        deserializeJson(doc, data, len);

        unsigned long epoch = doc["epoch"];
        if (epoch > 1600000000) {
            // NOTE: Reduces flash wear by only saving on epoch update
            saveTimeSync(epoch);
        }

        JsonArray motors = doc["motors"];
        JsonDocument response;
        JsonArray updated = response["updated"].to<JsonArray>();
        JsonArray ignored = response["ignored"].to<JsonArray>();

        for (JsonObject m : motors) {
            int id = m["id"];
            int idx = id - 1;
            if (idx < 0 || idx >= 4) continue;

            int newVersion = m["version"];
            // NOTE: Trusts app's version increment as authoritative, no field-level re-verification.
            if (newVersion > motorConfigs[idx].version || newVersion == 0) {
                const char* mode = m["mode"];
                motorConfigs[idx].isEnabled = (strcmp(mode, "off") != 0);
                motorConfigs[idx].autoMode = (strcmp(mode, "auto") == 0);
                motorConfigs[idx].amountMl = m["amount_ml"];
                motorConfigs[idx].moistureThreshold = m["threshold"];
                motorConfigs[idx].version = newVersion;

                JsonArray schedules = m["schedules"];
                motorConfigs[idx].scheduleCount = min((int)schedules.size(), 5);
                for (int i = 0; i < motorConfigs[idx].scheduleCount; i++) {
                    motorConfigs[idx].schedules[i].hour = schedules[i]["hour"];
                    motorConfigs[idx].schedules[i].minute = schedules[i]["minute"];
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

    for (int m = 1; m <= 4; m++) {
        char path[32]; sprintf(path, "/api/motor/%d/water_now", m);
        server.on(path, HTTP_POST, [m](AsyncWebServerRequest *request){
            triggerPump(m-1, motorConfigs[m-1].amountMl, "manual");
            request->send(200, "application/json", "{\"status\":\"ok\"}");
        });
    }
}

void onWiFiEvent(WiFiEvent_t event, WiFiEventInfo_t info) {
    switch (event) {
        case ARDUINO_EVENT_WIFI_STA_CONNECTED: Serial.println("[WIFI] Connected to AP"); break;
        case ARDUINO_EVENT_WIFI_STA_GOT_IP: Serial.printf("[%s] [WIFI] IP: %s\n", getLocalTimeStr().c_str(), WiFi.localIP().toString().c_str()); wasConnected = true; break;
        case ARDUINO_EVENT_WIFI_STA_DISCONNECTED:
            lastWiFiReason = info.wifi_sta_disconnected.reason;
            if (wasConnected) { Serial.printf("[%s] [WIFI] Lost, Reason: %d\n", getLocalTimeStr().c_str(), lastWiFiReason); wasConnected = false; }
            break;
        default: break;
    }
}

void setup() {
    Serial.begin(115200); delay(500); Serial.printf("\n[%s] [SYSTEM] Booting PlantPilot...\n", getLocalTimeStr().c_str());
    initRelays(); loadConfigs();
    WiFi.persistent(false); WiFi.setAutoReconnect(true); WiFi.onEvent(onWiFiEvent);
    WiFi.setSleep(WIFI_PS_MIN_MODEM);
    preferences.begin("wifi", true); String ssid = preferences.getString("ssid", ""); String pass = preferences.getString("pass", ""); preferences.end();
    if (ssid.length() > 0) { Serial.printf("[%s] [WIFI] Target: %s\n", getLocalTimeStr().c_str(), ssid.c_str()); WiFi.begin(ssid.c_str(), pass.c_str()); }
    else { Serial.printf("[%s] [WIFI] No credentials. Setup Mode.\n", getLocalTimeStr().c_str()); startSetupMode(); }
    if (MDNS.begin(HOSTNAME)) MDNS.addService("http", "tcp", 80);
    setupApi();
    ws.onEvent([](AsyncWebSocket *server, AsyncWebSocketClient *client, AwsEventType type, void *arg, uint8_t *data, size_t len){
        if (type == WS_EVT_CONNECT) client->text("PlantPilot Ready");
        else if (type == WS_EVT_DATA) { String cmd = ""; for (size_t i = 0; i < len; i++) cmd += (char)data[i]; handleWsCommand(cmd, client); }
    });
    server.addHandler(&ws); server.begin();
}

void loop() {
    unsigned long nowMs = millis();
    if (pendingRestart && nowMs >= restartTime) ESP.restart();
    if (WiFi.status() == WL_CONNECTED) {
        static unsigned long lastNtpSync = 0;
        if (nowMs - lastNtpSync > 3600000 || lastNtpSync == 0) { lastNtpSync = nowMs; syncWithNtp(); }
    } else if (WiFi.status() != WL_CONNECTED && (WiFi.getMode() & WIFI_STA)) {
        if (lastWifiRetry == 0) lastWifiRetry = nowMs;
        if (nowMs - lastWifiRetry > 300000) { Serial.println("[WIFI] Radio Reset..."); WiFi.disconnect(); delay(500); WiFi.begin(); lastWifiRetry = nowMs; }
    } else lastWifiRetry = 0;

    if (Serial.available()) {
        String cmd = Serial.readStringUntil('\n'); cmd.trim();
        if (cmd == "WIFI_RESET") { preferences.begin("wifi", false); preferences.clear(); preferences.end(); WiFi.disconnect(true, true); delay(1000); ESP.restart(); }
    }
    if (WiFi.getMode() & WIFI_AP_STA) dnsServer.processNextRequest();
    ws.cleanupClients(); updatePumps();

    static unsigned long lastTele = 0;
    int telemetryMs = (ws.count() > 0) ? 3000 : 60000;
    if (nowMs - lastTele > telemetryMs) { lastTele = nowMs; broadcastTelemetry(); }

    static unsigned long lastCheck = 0;
    if (nowMs - lastCheck > 1000) { lastCheck = nowMs; checkSchedules(); checkAutoWatering(); }
}
