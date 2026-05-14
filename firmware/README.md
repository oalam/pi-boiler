# firmware — boucle hôte ESP32

Firmware ESP32 qui fait tourner le cœur de régulation portable
([`../controller/`](../controller/README.md)) sur le matériel décrit dans
[`../docs/hardware.md`](../docs/hardware.md).

Le cœur (`BoilerController`) prend les décisions ; ce firmware lui fournit le
contexte matériel :

| Rôle | Implémentation |
|------|----------------|
| Horloge monotone 64 bits | `now_ms()` dans `main.cpp` (extension de `millis()`) |
| Pilotage des organes | `Esp32Actuators` → GPIO → ULN2803 → SSR / carte relais |
| Journal & alertes | `Esp32Reporter` → port série (MQTT en phase 3) |
| Lecture de la sonde | DS18B20 1-Wire (`DallasTemperature`) |
| Contacts de sécurité | GPIO + détection de front → `on_engine_fault()` |
| Watchdog matériel | `esp_task_wdt` — reset si la boucle se fige |
| Interface téléphone | `web_ui` — UI web responsive servie en WiFi |

## Arborescence

```
firmware/
  platformio.ini            Cible ESP32, framework Arduino, dépendances
  include/
    pins.hpp                Affectation des broches (source unique de vérité)
    config.hpp.example      Modèle de configuration WiFi (à copier en config.hpp)
    esp32_actuators.hpp     IActuators : pilotage des GPIO
    esp32_reporter.hpp      IReporter : journal série + état pour l'UI
    web_ui.hpp              Interface de l'UI web
  src/
    web_ui.cpp              Serveur web + page mobile + file de commandes
    main.cpp                setup() / loop() — la boucle hôte
```

## Compilation et flash

Prérequis : [PlatformIO](https://platformio.org/) (CLI ou extension VS Code).

```bash
cd firmware
cp include/config.hpp.example include/config.hpp   # puis renseigner SSID / mot de passe
pio run                  # compile
pio run -t upload        # flash l'ESP32
pio device monitor       # console série (115200 bauds)
```

Le cœur C++ est tiré automatiquement comme bibliothèque locale via
`symlink://../controller` (déclarée par `controller/library.json`).

> Les versions des bibliothèques externes ne sont pas figées dans `platformio.ini` :
> à verrouiller après le premier build réussi, pour un build reproductible.

## Modèle d'exécution

`loop()` est mono-tâche et déterministe :

1. `controller->tick(t)` — minuteries du cœur ;
2. contacts de sécurité — sur front → `on_engine_fault()` ;
3. requêtes de l'UI web — drainées d'une file FreeRTOS ;
4. lecture de la sonde toutes les 2 s → `on_temperature()` / `on_sensor_failure()` ;
5. publication de l'instantané pour l'UI.

Le serveur web tourne dans la tâche AsyncTCP : il **ne touche jamais** le
contrôleur directement. Les commandes passent par une file, l'état est lu dans
un instantané publié par `loop()`.

## Interface téléphone

L'ESP32 rejoint le WiFi (mode station) et sert une page responsive :
`http://chaudiere.local` (ou l'IP affichée sur le port série).

- affichage : température, état, organes actifs, alertes ;
- commandes : Démarrer / Arrêter / Amorcer / Arrêt complet ;
- réglages : consigne, arrêt vis entre cycles, repos en ralenti.

La page sonde `/api/status` toutes les 2 s. Si le WiFi est absent, la régulation
tourne quand même — elle n'en dépend pas.

## Limites de cette étape (à traiter ensuite)

- **Config WiFi en dur** via `config.hpp` — à remplacer par un portail captif
  (WiFiManager) pour configurer sans recompiler.
- **Polarité des contacts opto** (`fault_edge`) et **ordre des sondes DS18B20**
  à valider sur le matériel réel.
- **Pas d'authentification** sur l'UI web — appareil à garder sur le réseau
  local. Voir l'évolution MQTT + superviseur dans `../docs/hardware.md`.
- Firmware **non compilé ici** (pas de toolchain ESP32 sur la machine de dev) —
  à vérifier par un `pio run`.
