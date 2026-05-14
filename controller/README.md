# controller — cœur de régulation portable (C++)

Réécriture du cœur de régulation de la chaudière sous forme d'une **machine à
états C++ pure**, indépendante du matériel. C'est la première étape concrète de
la scission décrite dans [`../docs/critique-evolution.md`](../docs/critique-evolution.md) :
séparer la logique de régulation (déterministe, critique) de la supervision
(réseau, télémétrie).

À terme, ce cœur remplace les acteurs Scala `Boiler` + `EngineCyclesManager` et
se déploiera tel quel sur un micro-contrôleur (ESP32 visé).

## Principes de conception

- **Aucune dépendance matérielle.** Le cœur ne connaît que deux interfaces,
  `IActuators` et `IReporter` (voir `include/boiler/interfaces.hpp`). Les
  implémentations concrètes (GPIO ESP32, relais Pi, doublures de test) vivent en
  dehors.
- **Logique pure.** Pas d'allocation dynamique, pas d'exceptions, pas de RTTI —
  style adapté à l'embarqué.
- **Temps injecté.** Le contrôleur ne lit aucune horloge : l'hôte lui passe un
  temps monotone (`uint64_t` millisecondes, 64 bits pour éviter tout
  débordement). Conséquence : la régulation est 100 % déterministe et testable
  sur PC.
- **Un seul objet possède toute la logique** — états *et* minuteries de cycles.
  C'est ce qui élimine le découpage fragile `Boiler` / `EngineCyclesManager` de
  la version Scala.

## Machine à états

```
        Start                  T >= 30 °C
Stopped ──────▶ Igniting ──────────────────▶ Running
   ▲               │                          │  ▲
   │ Stop          │ 60 min sans montée        │  │ T <= consigne-3
   │               ▼                          ▼  │
   │            Locked ◀───────────────────  Slowing
   │               ▲     T <= 30 °C            │
   │               │     après 30 min          │
   │  Start (réarmement manuel)                │
   │                                           │
   └───────────────────────────────────────────┘
                   Stop

Running / Slowing / Igniting ──(T >= 90 °C)──▶ Overheating ──(T <= 85 °C)──▶ Running/Slowing
Igniting / Running ──(sonde muette x5)──▶ Slowing  (repli sécurisé)
```

États : `Stopped`, `Igniting`, `Running`, `Slowing`, `Overheating`, `Locked`.

Sécurités intégrées (alignées sur la notice du régulateur MONEX d'origine) :
- **watchdog d'allumage** — pas de montée à 30 °C en 60 min → `Locked` ;
- **verrouillage température basse** — sous 30 °C après 30 min de marche → `Locked` ;
- **surchauffe logicielle** — au-delà de 90 °C, combustion coupée, circulation
  forcée, hystérésis de sortie à 85 °C ;
- **panne de sonde** — après 5 lectures muettes, alerte et repli en ralenti.

> ⚠️ La surchauffe logicielle à 90 °C est un **second** niveau de protection.
> Le thermostat physique 95 °C doit rester une coupure matérielle en série sur
> la puissance, indépendante de ce logiciel.

## Compilation et tests

Prérequis : CMake ≥ 3.15 et un compilateur C++17.

```bash
cd controller
cmake -B build
cmake --build build
ctest --test-dir build --output-on-failure
```

Le harnais de test (`tests/test_framework.hpp`) est volontairement sans
dépendance — il compile et tourne partout, y compris sur la cible.

## Intégration sur une cible

L'hôte (ESP32 ou Pi) doit :

1. implémenter `IActuators` (pilotage des organes) et `IReporter` (logs / alertes) ;
2. fournir une horloge monotone 64 bits en millisecondes ;
3. dans sa boucle principale :
   - appeler `tick(now)` régulièrement (toutes les 200–500 ms) ;
   - appeler `on_temperature(now, °C)` ou `on_sensor_failure(now)` à chaque
     lecture de sonde ;
   - relayer les commandes externes via `handle_command(now, …)`.

Le cœur ne fait aucune E/S lui-même : tout passe par les deux interfaces.

## Arborescence

```
controller/
  include/boiler/
    types.hpp             États, organes, commandes, réglages, paramètres fixes
    interfaces.hpp        IActuators, IReporter
    boiler_controller.hpp Machine à états (API publique)
  src/
    boiler_controller.cpp Implémentation
  tests/
    test_framework.hpp        Micro-harnais sans dépendance
    test_boiler_controller.cpp Suite de tests (transitions, minuteries, sécurités)
  CMakeLists.txt
```
