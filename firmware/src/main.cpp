// Firmware ESP32 — chaudière à granulés.
//
// Rôle : la « boucle hôte » autour du cœur de régulation portable
// (../controller). L'ESP32 fournit l'horloge, lit la sonde et les contacts de
// sécurité, pilote les organes, sert l'UI web — le cœur prend les décisions.

#include <Arduino.h>
#include <WiFi.h>
#include <ESPmDNS.h>
#include <OneWire.h>
#include <DallasTemperature.h>
#include <esp_task_wdt.h>

#include "boiler/boiler_controller.hpp"
#include "pins.hpp"
#include "esp32_actuators.hpp"
#include "esp32_reporter.hpp"
#include "web_ui.hpp"

#if __has_include("config.hpp")
#include "config.hpp"
#else
#warning "firmware/include/config.hpp absent — copier config.hpp.example (voir README)"
#define WIFI_SSID "wifi"
#define WIFI_PASS "password"
#define HOSTNAME  "chaudiere"
#endif

namespace {

constexpr uint32_t SENSOR_PERIOD_MS = 2000;
constexpr uint32_t WDT_TIMEOUT_S    = 8;

// Horloge monotone 64 bits. millis() (uint32) déborde à ~49 jours ; on étend en
// 64 bits, comme l'exige le cœur de régulation.
uint64_t now_ms() {
    static uint32_t last = 0;
    static uint64_t epoch_high = 0;
    const uint32_t m = millis();
    if (m < last) epoch_high += 0x100000000ULL;  // débordement détecté
    last = m;
    return epoch_high + m;
}

Esp32Actuators            actuators;
Esp32Reporter             reporter;
boiler::BoilerSettings    settings;            // valeurs par défaut du cœur
boiler::BoilerController* controller = nullptr;

OneWire           one_wire(pins::ONEWIRE);
DallasTemperature sensor(&one_wire);

uint32_t last_sensor_ms = 0;

// Contacts de sécurité : on agit sur le FRONT (contact qui s'ouvre), pas en
// continu. Polarité (HIGH = défaut) à valider selon le module opto utilisé.
bool secu_tremie_prev  = false;
bool secu_bruleur_prev = false;

bool fault_edge(int pin, bool& prev) {
    const bool active = (digitalRead(pin) == HIGH);
    const bool edge = active && !prev;
    prev = active;
    return edge;
}

void start_watchdog() {
#if defined(ESP_ARDUINO_VERSION_MAJOR) && ESP_ARDUINO_VERSION_MAJOR >= 3
    esp_task_wdt_config_t cfg = {};
    cfg.timeout_ms = WDT_TIMEOUT_S * 1000;
    cfg.idle_core_mask = 0;
    cfg.trigger_panic = true;
    esp_task_wdt_reconfigure(&cfg);
#else
    esp_task_wdt_init(WDT_TIMEOUT_S, true);
#endif
    esp_task_wdt_add(nullptr);
}

}  // namespace

void setup() {
    Serial.begin(115200);
    delay(200);

    // Sécurité d'abord : tous les organes à l'arrêt avant quoi que ce soit.
    actuators.begin();
    pinMode(pins::BUZZER, OUTPUT);
    digitalWrite(pins::BUZZER, LOW);
    pinMode(pins::STATUS_LED, OUTPUT);
    pinMode(pins::SECU_TREMIE, INPUT_PULLUP);
    pinMode(pins::SECU_BRULEUR, INPUT_PULLUP);

    // Watchdog matériel : reset de l'ESP32 si la boucle se fige.
    start_watchdog();

    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASS);
    Serial.print("WiFi");
    for (int i = 0; i < 40 && WiFi.status() != WL_CONNECTED; ++i) {
        delay(250);
        Serial.print('.');
        esp_task_wdt_reset();
    }
    Serial.println();
    if (WiFi.status() == WL_CONNECTED) {
        Serial.printf("IP: %s\n", WiFi.localIP().toString().c_str());
        if (MDNS.begin(HOSTNAME))
            Serial.printf("mDNS: http://%s.local\n", HOSTNAME);
    } else {
        // La régulation ne dépend pas du réseau : on continue sans l'UI web.
        Serial.println("WiFi indisponible — la regulation tourne quand meme");
    }

    sensor.begin();
    sensor.setWaitForConversion(true);

    controller = new boiler::BoilerController(actuators, reporter, settings, now_ms());
    webui_begin(controller, &reporter, &actuators);

    Serial.println("Coeur de regulation demarre (etat initial : Stopped)");
}

void loop() {
    esp_task_wdt_reset();
    const uint64_t t = now_ms();

    // 1. Minuteries du cœur (cycles de combustion, ralenti, watchdog d'allumage).
    controller->tick(t);

    // 2. Contacts de sécurité des vis (protections thermiques / bourrage).
    if (fault_edge(pins::SECU_TREMIE, secu_tremie_prev) ||
        fault_edge(pins::SECU_BRULEUR, secu_bruleur_prev)) {
        controller->on_engine_fault(t);
    }

    // 3. Requêtes de l'UI web (commandes et réglages), déposées par la tâche web.
    WebRequest req;
    while (webui_next_request(req)) {
        if (req.is_settings)
            controller->update_settings(t, req.settings);
        else
            controller->handle_command(t, req.command);
    }

    // 4. Lecture de la sonde toutes les 2 s.
    if (millis() - last_sensor_ms >= SENSOR_PERIOD_MS) {
        last_sensor_ms = millis();
        sensor.requestTemperatures();
        const float c = sensor.getTempCByIndex(0);
        if (c <= DEVICE_DISCONNECTED_C || c < -50.0f || c > 125.0f)
            controller->on_sensor_failure(t);
        else
            controller->on_temperature(t, c);
    }

    // 5. Publication de l'instantané pour l'UI web.
    webui_publish();

    // Battement de cœur visible.
    digitalWrite(pins::STATUS_LED, (millis() / 500) % 2);
    delay(20);  // boucle ~50 Hz
}
