#include "web_ui.hpp"
#include <Arduino.h>
#include <ESPAsyncWebServer.h>
#include "esp32_reporter.hpp"
#include "esp32_actuators.hpp"

namespace {

AsyncWebServer    g_server(80);
QueueHandle_t     g_queue = nullptr;
portMUX_TYPE      g_mux = portMUX_INITIALIZER_UNLOCKED;

boiler::BoilerController* g_controller = nullptr;
Esp32Reporter*            g_reporter = nullptr;
Esp32Actuators*           g_actuators = nullptr;

// Instantané publié par loop(), lu par les handlers web.
struct Snapshot {
    boiler::State state = boiler::State::Stopped;
    float         temperature = 0.0f;
    bool          engines[5] = {false, false, false, false, false};
    uint32_t      alert_count = 0;
    boiler::Alert last_alert = boiler::Alert::Overheat;
    float         consigne = 75.0f;
    uint32_t      delay_off_s = 20;
    uint32_t      rest_duration_min = 60;
};
Snapshot g_snapshot;

// Page web — mobile-first, sans dépendance externe. Sondage de /api/status
// toutes les 2 s (un passage en WebSocket sera un raffinement ultérieur).
const char PAGE[] PROGMEM = R"HTML(<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Chaudière</title>
<style>
  :root { color-scheme: dark; }
  body { font-family: system-ui, sans-serif; margin: 0; background: #1b1d22; color: #e6e6e6; }
  .wrap { max-width: 460px; margin: 0 auto; padding: 16px; }
  h1 { font-size: 1.1rem; font-weight: 600; opacity: .7; }
  .temp { font-size: 3.4rem; font-weight: 700; text-align: center; }
  .temp small { font-size: 1.2rem; opacity: .6; }
  .state { text-align: center; padding: 6px 0; border-radius: 8px; font-weight: 600; margin: 8px 0; background: #2a2d34; }
  .state.Running, .state.Igniting { background: #1f5132; }
  .state.Slowing { background: #4a3f15; }
  .state.Overheating, .state.Locked { background: #5a1f1f; }
  .engines { display: grid; grid-template-columns: repeat(5, 1fr); gap: 6px; margin: 12px 0; }
  .eng { text-align: center; font-size: .7rem; padding: 8px 2px; border-radius: 6px; background: #2a2d34; }
  .eng.on { background: #1f5132; }
  .btns { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin: 12px 0; }
  button { padding: 14px; font-size: 1rem; border: 0; border-radius: 8px; background: #3a8; color: #fff; }
  button.warn { background: #a53; }
  fieldset { border: 1px solid #3a3d44; border-radius: 8px; margin: 12px 0; }
  label { display: block; margin: 8px 0 2px; font-size: .8rem; opacity: .7; }
  input { width: 100%; padding: 8px; box-sizing: border-box; background: #2a2d34; color: #e6e6e6; border: 1px solid #3a3d44; border-radius: 6px; }
  .alerts { font-size: .8rem; opacity: .7; text-align: center; margin-top: 12px; }
</style>
</head>
<body>
<div class="wrap">
  <h1>Chaudière à granulés</h1>
  <div class="temp"><span id="temp">--</span><small> °C</small></div>
  <div id="state" class="state">--</div>
  <div class="engines">
    <div class="eng" id="e0">trémie</div>
    <div class="eng" id="e1">brûleur</div>
    <div class="eng" id="e2">ventilo</div>
    <div class="eng" id="e3">circul.</div>
    <div class="eng" id="e4">laddomat</div>
  </div>
  <div class="btns">
    <button onclick="cmd('start')">Démarrer</button>
    <button onclick="cmd('stop')">Arrêter</button>
    <button onclick="cmd('load')">Amorcer</button>
    <button class="warn" onclick="cmd('shutdown')">Arrêt complet</button>
  </div>
  <fieldset>
    <legend>Réglages</legend>
    <label>Consigne (°C)</label><input id="consigne" type="number" min="60" max="90">
    <label>Arrêt vis entre cycles (s)</label><input id="delayOff" type="number" min="3" max="90">
    <label>Repos en ralenti (min)</label><input id="restDuration" type="number" min="3" max="90">
    <div style="margin-top:10px"><button onclick="applySettings()">Appliquer</button></div>
  </fieldset>
  <div class="alerts" id="alerts"></div>
</div>
<script>
let editing = false;
['consigne','delayOff','restDuration'].forEach(id => {
  document.getElementById(id).addEventListener('focus', () => editing = true);
});
async function refresh() {
  try {
    const s = await (await fetch('/api/status')).json();
    document.getElementById('temp').textContent = s.temp.toFixed(1);
    const st = document.getElementById('state');
    st.textContent = s.state; st.className = 'state ' + s.state;
    for (let i = 0; i < 5; i++)
      document.getElementById('e' + i).className = 'eng' + (s.engines[i] ? ' on' : '');
    document.getElementById('alerts').textContent =
      s.alertCount ? (s.alertCount + ' alerte(s) — dernière : ' + s.lastAlert) : 'aucune alerte';
    if (!editing) {
      document.getElementById('consigne').value = s.consigne;
      document.getElementById('delayOff').value = s.delayOff;
      document.getElementById('restDuration').value = s.restDuration;
    }
  } catch (e) { document.getElementById('state').textContent = 'hors ligne'; }
}
function cmd(t) { fetch('/api/command?type=' + t).then(refresh); }
function applySettings() {
  const q = '?type=set'
    + '&consigne=' + document.getElementById('consigne').value
    + '&delayOff=' + document.getElementById('delayOff').value
    + '&restDuration=' + document.getElementById('restDuration').value;
  fetch('/api/command' + q).then(() => { editing = false; refresh(); });
}
refresh();
setInterval(refresh, 2000);
</script>
</body>
</html>)HTML";

void handle_status(AsyncWebServerRequest* request) {
    Snapshot s;
    portENTER_CRITICAL(&g_mux);
    s = g_snapshot;
    portEXIT_CRITICAL(&g_mux);

    char buf[384];
    snprintf(buf, sizeof(buf),
             "{\"state\":\"%s\",\"temp\":%.1f,"
             "\"engines\":[%s,%s,%s,%s,%s],"
             "\"alertCount\":%u,\"lastAlert\":\"%s\","
             "\"consigne\":%.0f,\"delayOff\":%u,\"restDuration\":%u}",
             state_name(s.state), s.temperature,
             s.engines[0] ? "true" : "false", s.engines[1] ? "true" : "false",
             s.engines[2] ? "true" : "false", s.engines[3] ? "true" : "false",
             s.engines[4] ? "true" : "false",
             static_cast<unsigned>(s.alert_count), alert_name(s.last_alert),
             s.consigne, static_cast<unsigned>(s.delay_off_s),
             static_cast<unsigned>(s.rest_duration_min));
    request->send(200, "application/json", buf);
}

void handle_command(AsyncWebServerRequest* request) {
    if (!request->hasParam("type")) {
        request->send(400, "text/plain", "parametre 'type' manquant");
        return;
    }
    const String type = request->getParam("type")->value();
    WebRequest wr{};

    if (type == "start") {
        wr.is_settings = false; wr.command = boiler::Command::Start;
    } else if (type == "stop") {
        wr.is_settings = false; wr.command = boiler::Command::Stop;
    } else if (type == "load") {
        wr.is_settings = false; wr.command = boiler::Command::LoadPellets;
    } else if (type == "shutdown") {
        wr.is_settings = false; wr.command = boiler::Command::Shutdown;
    } else if (type == "set") {
        wr.is_settings = true;
        // On part des réglages courants, on n'écrase que ce qui est fourni.
        portENTER_CRITICAL(&g_mux);
        wr.settings.consigne_celsius = g_snapshot.consigne;
        wr.settings.delay_off_ms     = g_snapshot.delay_off_s * 1000u;
        wr.settings.rest_duration_ms = g_snapshot.rest_duration_min * 60u * 1000u;
        portEXIT_CRITICAL(&g_mux);
        if (request->hasParam("consigne"))
            wr.settings.consigne_celsius = request->getParam("consigne")->value().toFloat();
        if (request->hasParam("delayOff"))
            wr.settings.delay_off_ms =
                static_cast<uint32_t>(request->getParam("delayOff")->value().toInt()) * 1000u;
        if (request->hasParam("restDuration"))
            wr.settings.rest_duration_ms =
                static_cast<uint32_t>(request->getParam("restDuration")->value().toInt()) * 60u * 1000u;
    } else {
        request->send(400, "text/plain", "type inconnu");
        return;
    }

    if (g_queue && xQueueSend(g_queue, &wr, 0) == pdTRUE)
        request->send(202, "text/plain", "ok");
    else
        request->send(503, "text/plain", "file de commandes pleine");
}

}  // namespace

void webui_begin(boiler::BoilerController* controller,
                 Esp32Reporter* reporter,
                 Esp32Actuators* actuators) {
    g_controller = controller;
    g_reporter = reporter;
    g_actuators = actuators;
    g_queue = xQueueCreate(8, sizeof(WebRequest));

    g_server.on("/", HTTP_GET, [](AsyncWebServerRequest* request) {
        request->send_P(200, "text/html", PAGE);
    });
    g_server.on("/api/status", HTTP_GET, handle_status);
    g_server.on("/api/command", HTTP_GET, handle_command);
    g_server.onNotFound([](AsyncWebServerRequest* request) {
        request->send(404, "text/plain", "introuvable");
    });
    g_server.begin();
}

void webui_publish() {
    if (!g_controller) return;
    const boiler::BoilerSettings& set = g_controller->settings();

    portENTER_CRITICAL(&g_mux);
    g_snapshot.state = g_controller->state();
    g_snapshot.temperature = g_controller->last_temperature();
    for (int i = 0; i < 5; ++i)
        g_snapshot.engines[i] = g_actuators->engine(static_cast<boiler::Engine>(i));
    g_snapshot.alert_count = g_reporter->alert_count();
    g_snapshot.last_alert = g_reporter->last_alert();
    g_snapshot.consigne = set.consigne_celsius;
    g_snapshot.delay_off_s = set.delay_off_ms / 1000u;
    g_snapshot.rest_duration_min = set.rest_duration_ms / 60000u;
    portEXIT_CRITICAL(&g_mux);
}

bool webui_next_request(WebRequest& out) {
    if (!g_queue) return false;
    return xQueueReceive(g_queue, &out, 0) == pdTRUE;
}
