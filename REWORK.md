# REWORK — stratégie de remise à plat de la régulation

Ce document est le fil conducteur du chantier de réécriture de `pi-boiler`. Il
relie les trois documents du dépôt et trace la trajectoire de bout en bout :

- [`docs/critique-evolution.md`](docs/critique-evolution.md) — l'analyse critique qui motive le chantier
- [`controller/README.md`](controller/README.md) — le cœur de régulation C++ déjà écrit
- [`docs/hardware.md`](docs/hardware.md) — focus matériel : pièces, câblage, packaging, interface téléphone
- [`README.md`](README.md) — la doc du système actuel (Scala/Pi)

---

## 1. Pourquoi ce rework

La régulation actuelle (Scala / Akka / Spray sur Raspberry Pi) remplace le
régulateur MONEX d'origine, hors service. Elle fonctionne, mais l'analyse a mis
en évidence trois problèmes de fond :

1. **Le Pi porte deux rôles de natures opposées** — une boucle de régulation
   critique (qui exige déterminisme et fiabilité) et une couche de supervision
   non critique (réseau, télémétrie). Le Pi est bon pour le second, médiocre
   pour le premier (corruption SD, pas de watchdog, pas de temps réel).
2. **L'étage de puissance est fragile** — relais mécaniques sur des moteurs qui
   commutent toutes les ~30 s, pas de snubber, pilotage en boucle ouverte.
3. **La pile logicielle est morte** — Scala 2.11, Akka 2.4, Spray, sbt 0.13,
   `pi4j` en SNAPSHOT non publié : build non reproductible, plus de support.

Le détail est dans [`docs/critique-evolution.md`](docs/critique-evolution.md).

> **Principe directeur, non négociable** — on régule un appareil de combustion.
> La chaîne de sécurité matérielle (thermostat 95 °C, protections thermiques
> moteurs) doit rester une coupure câblée en série sur la puissance,
> **indépendante de tout logiciel**. Tout ce qui suit est une seconde couche.

---

## 2. La stratégie : la scission

La décision structurante n'est pas « changer le Pi » mais **séparer les deux
rôles** :

```
┌─────────────────────────┐         ┌──────────────────────────────┐
│   Cœur de régulation     │         │      Superviseur             │
│   (micro-contrôleur)     │◀──────▶│   (Raspberry Pi ou autre SBC) │
│                          │ série / │                              │
│ - machine à états        │  MQTT   │ - API REST                   │
│ - pilotage des organes   │         │ - journalisation Elastic     │
│ - seuils & sécurités     │         │ - alertes SMS / e-mail       │
│ - watchdog               │         │ - dashboards / réglages      │
└─────────────────────────┘         └──────────────────────────────┘
          │
          ▼
  chaîne de sécurité MATÉRIELLE — indépendante de tout logiciel
```

Bénéfices : le cœur critique devient déterministe, testable et redémarre en
moins d'une seconde ; le superviseur peut planter, redémarrer ou être mis à jour
sans jamais interrompre la régulation.

**Choix de mise en œuvre actés :**

| Décision | Choix | Raison |
|----------|-------|--------|
| Cœur de régulation | **C++ portable**, écrit cœur logique d'abord | langage de la cible, zéro travail jetable, testable sur PC sans matériel |
| Cible matérielle du cœur | **ESP32** (à confirmer) | watchdog matériel, démarrage rapide, double cœur, communauté |
| Superviseur | Pi conservé, à réécrire (Python ou Go) | abandonne la pile Scala/Spray/Akka morte |
| Sécurités | extraction **sécurisée** : on ajoute les watchdog MONEX absents | allumage raté, verrouillage température basse |

---

## 3. Ce qui a été fait

### Phase 0 — Corrections critiques du code Scala existant

Le système Scala reste en service pendant la transition ; ses bugs bloquants ont
été corrigés au préalable (détail dans l'historique git) :

- `EngineCyclesManager` — `lastDate == 0` (comparaison sans effet) corrigé en
  `lastDate = 0` : le mode ralenti n'est plus bloqué après un arrêt de cycle.
- `EngineCyclesManager` — suppression du `Await.result` sur `../boiler` (acteur
  pas encore créé au démarrage → risque de crash au boot).
- `TemperatureEventHandler` — seuil de sortie de ralenti `61` codé en dur
  remplacé par `tempConsigne - 3`.
- `Boiler` — réactivation de l'arrêt du circulateur sous 40 °C (anti-condensation).
- `TemperatureSensor` — `tempIndex != 1` corrigé en `!= -1` ; ajout de la
  détection de panne de sonde avec alerte (`TemperatureSensorFailure`).
- Mode ralenti : durée de relance des vis portée à 2 min (notice MONEX) et
  ventilateur aligné sur les vis (corrige l'étouffement du feu en ralenti).

### Phase 1 — Cœur de régulation C++ portable ✅

Écrit dans [`controller/`](controller/README.md). C'est l'artefact central de la
scission.

- **Machine à états explicite** : `Stopped → Igniting → Running → Slowing →
  Overheating → Locked`, avec les sécurités MONEX qui manquaient (watchdog
  d'allumage 60 min, verrouillage température basse après 30 min, hystérésis de
  surchauffe, repli en ralenti sur panne de sonde).
- **Logique pure** : aucune dépendance matérielle, pas d'allocation, pas
  d'exceptions. Tout passe par deux interfaces, `IActuators` et `IReporter`.
- **Temps injecté** → 100 % déterministe, testé sur PC : 20 tests, 57
  vérifications, build propre en `-Wall -Wextra -Wpedantic`.
- Le cœur **absorbe `Boiler` + `EngineCyclesManager`** : un seul objet possède
  états *et* minuteries, ce qui élimine le découpage fragile d'origine.

### Phase 2a — Firmware ESP32 « glue » (écrit, non encore validé)

Écrit dans [`firmware/`](firmware/README.md) : la boucle hôte qui fait tourner le
cœur sur l'ESP32.

- `Esp32Actuators` / `Esp32Reporter` — implémentations matérielles des deux
  interfaces du cœur.
- `main.cpp` — horloge monotone 64 bits, lecture DS18B20, lecture des contacts
  de sécurité (`on_engine_fault`), watchdog matériel, boucle déterministe.
- `web_ui` — interface web responsive servie par l'ESP32, accessible au
  téléphone (`chaudiere.local`).
- Le cœur a gagné `on_engine_fault()` (verrouillage sur défaut de vis) — entrée
  qui manquait pour les contacts thermiques / anti-bourrage.
- **Non encore compilé** (pas de toolchain ESP32 sur la machine de dev) ni
  validé sur matériel.

---

## 4. Décisions de conception à valider

La réécriture C++ s'écarte volontairement du code Scala sur quatre points. Ils
sont à confirmer avant d'aller plus loin :

1. **Tout est niveau + hystérésis + minuteries**, plus de détection de fronts.
   Plus robuste (aucun franchissement manqué si un cycle saute). L'hydraulique
   devient une fonction pure de la température : ≥ 60 °C circulateur + laddomat,
   40–60 °C circulateur seul, < 40 °C rien.
2. **Entrée en ralenti = repos d'abord**, relance après `rest_duration`. Le
   Scala faisait une relance immédiate. Raison : à l'atteinte de la consigne le
   feu est bien alimenté, inutile d'ajouter du combustible aussitôt.
3. **Panne de sonde → repli en `Slowing`** (nouveau ; le Scala se contentait
   d'alerter). Alimentation minimale, feu maintenu, le thermostat 95 °C matériel
   reste le garde-fou.
4. **Laddomat sous 60 °C** : mis à l'arrêt sous 60 °C. Le câblage hydraulique
   réel (Laddomat + vanne 4 voies) n'a pas été précisé — à valider.

---

## 5. Feuille de route

### Fait
- [x] Phase 0 — corrections critiques du code Scala
- [x] Phase 1 — cœur de régulation C++ portable, testé
- [x] Phase 2a — firmware ESP32 « glue » écrit (`firmware/`), non encore compilé
      ni validé sur matériel

### À venir

**Phase 2 — Intégration sur cible** (matériel détaillé dans [`docs/hardware.md`](docs/hardware.md))
- [x] Implémenter `IActuators` / `IReporter` pour l'ESP32 (`Esp32Actuators`, `Esp32Reporter`)
- [x] Boucle hôte : horloge monotone, lecture DS18B20, `tick()` régulier (`main.cpp`)
- [x] Armer le watchdog matériel ; organes à l'arrêt au boot
- [x] Interface téléphone : UI web servie par l'ESP32 (`web_ui`)
- [ ] Compiler le firmware (`pio run`) et figer les versions des bibliothèques
- [ ] Confirmer la cible matérielle et le câblage hydraulique
- [ ] Étage de puissance propre : opto-isolation, SSR pour les vis, carte relais,
      snubbers, fusibles par sortie
- [ ] Valider sur le matériel réel (polarité des contacts, ordre des sondes)

**Phase 3 — Superviseur**
- [ ] Réécrire la couche supervision (Python ou Go) : REST, Elasticsearch, alertes
- [ ] Lien superviseur ↔ cœur par série ou MQTT
- [ ] Externaliser les secrets (identifiants SMS / e-mail)

**Phase 4 — Mise en service & retrait du Scala**
- [ ] Validation sur banc puis sur la chaudière
- [ ] Bascule, puis suppression du code Scala (`src/`, `build.sbt`, etc.)

**Cible long terme**
- [ ] Boucle fermée : mesure de courant par moteur pour confirmer l'action et
      détecter relais collé / moteur grippé

---

## 6. Points en suspens

- Cible matérielle du cœur : ESP32 supposé, **à confirmer**.
- Câblage hydraulique réel (rôle exact du Laddomat et de la vanne 4 voies) — voir
  décision §4.4.
- Langage du superviseur : Python ou Go, **non tranché**.
- Les quatre décisions de conception du §4 attendent validation.
