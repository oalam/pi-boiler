# Focus matériel — pièces, câblage, packaging, interface téléphone

Proposition de plateforme matérielle pour le cœur de régulation décrit dans
[`../REWORK.md`](../REWORK.md) (phase 2). Cible : **ESP32**, étage de puissance
propre, coffret IP65, interface accessible depuis un téléphone.

> ⚠️ **Sécurité électrique.** Ce document décrit du 230 V secteur pilotant des
> moteurs. Les travaux sur le secteur doivent être réalisés ou vérifiés par une
> personne qualifiée, dans le respect de la norme NF C 15-100. La chaîne de
> sécurité matérielle (thermostat 95 °C, protections moteurs) est **critique**
> et indépendante de tout logiciel — voir §3.2. Ce document est une proposition
> à valider, pas un schéma certifié.

> **Sur les liens.** Les liens marchands périment vite et je ne devine pas
> d'URL. La liste ci-dessous donne des **références précises** (fabricant +
> modèle) à rechercher chez un distributeur sérieux : Mouser, RS Components,
> TME, Reichelt, Conrad, ou AliExpress pour les pièces génériques. Je peux faire
> une recherche web pour les liens et prix actuels si tu veux.

---

## 1. Vue d'ensemble

```
        230 V secteur
            │
        [Disjoncteur C6A]
            │
   ┌────────┴───────────────────────────────┐
   │                                        │
[PSU 230→5 V]                    ┌───────────┴───────────┐
   │                             │  Étage de puissance   │
  5 V                            │ 2 SSR + carte relais  │
   │                             └───────────┬───────────┘
[ESP32] ──GPIO──[ULN2803]──commande──────────┘     │
   │                                               ▼
   ├── 1-Wire ──── sonde DS18B20 (doigt de gant)   5 moteurs 230 V
   ├── entrées ─── contacts thermiques vis
   └── WiFi ────── interface web (téléphone)

Chaîne de sécurité matérielle : STB 95 °C en série, indépendant de l'ESP32
```

5 organes à piloter (230 V) : vis trémie, vis brûleur, ventilateur, circulateur,
laddomat. 2 entrées de sécurité : protections thermiques des deux vis. 1 sonde
de température. Le tout dans un coffret mural près de la chaudière.

---

## 2. Liste de pièces

### Contrôleur

| Réf.                                             | Description                                                                                              | Qté | ~Prix  |
| ------------------------------------------------ | -------------------------------------------------------------------------------------------------------- | --- | ------ |
| **Espressif ESP32-DevKitC-V4** (module WROOM-32) | Carte contrôleur : WiFi, watchdog matériel, GPIO suffisants. Préférer un exemplaire de marque Espressif. | 1   | 8–12 € |
| **ULN2803A** (DIP-18) + support                  | Réseau de 8 Darlington : isole l'ESP32 des entrées SSR et fixe les niveaux.                              | 1   | 1–2 €  |
| Résistances 10 kΩ                                | Tirage bas sur les entrées ULN — garantit les sorties à l'arrêt pendant le boot ESP32.                   | 5   | —      |
| Condensateur électrolytique 470 µF / ≥ 10 V      | Réservoir sur le rail 5 V, près de la carte relais (creux de tension à l'enclenchement des bobines).     | 1   | <1 €   |
| Plaque à trous / PCB + supports                  | Support de l'ESP32 et du ULN, ou petit PCB sur mesure.                                                   | 1   | 2–5 €  |

### Alimentation

| Réf. | Description | Qté | ~Prix |
|------|-------------|-----|-------|
| **Mean Well HDR-15-5** | Alim. rail DIN 230 V → 5 V / 2,4 A. Marque fiable, format DIN, marge confortable. | 1 | 12–16 € |
| Disjoncteur DIN **C6A** 1P+N | Protection générale (comme la notice MONEX d'origine). | 1 | 8–12 € |

### Étage de puissance

Deux SSR pour les **deux vis sans fin** (elles commutent toutes les ~30 s — un
relais mécanique s'y userait en quelques mois) ; une carte relais mécanique pour
les trois organes à commutation rare (ventilateur, circulateur, laddomat).

| Réf. | Description | Qté | ~Prix |
|------|-------------|-----|-------|
| **SSR à passage par zéro**, entrée 3–32 V DC, sortie 230 V AC, ≥ 10 A, montage rail DIN — qualité : *Carlo Gavazzi RM1A* / *Crydom* ; budget : *Fotek SSR-xxDA* d'un vendeur sérieux | Commutation des **2 vis sans fin** (trémie, brûleur). Aucune usure mécanique. Fail-safe : SSR ouvert si pas de commande. | 2 | 5–25 €/u |
| Clips / dissipateurs rail DIN pour SSR | Fixation des SSR (dissipation faible, vis ≈ 50–90 W). | 2 | 2–4 €/u |
| **Carte relais opto-isolée** 4 voies, bobines 5 V (3 voies utilisées) | Commutation du ventilateur, du circulateur et du laddomat — commutation rare, le relais mécanique convient. | 1 | 4–8 € |
| **Snubber RC** 0,1 µF + 100 Ω, 600 V (ou module anti-parasite tout fait) | Aux bornes de **chaque** moteur (les 5) : amortit les surtensions inductives, protège SSR et relais. | 5 | 1–3 €/u |
| Varistance (MOV) 275 V AC, optionnel | Protection supplémentaire contre les pics secteur. | 1–5 | <1 €/u |

### Capteurs

| Réf. | Description | Qté | ~Prix |
|------|-------------|-----|-------|
| **Sonde DS18B20 étanche**, câble inox | Température eau de sortie, placée dans le doigt de gant existant de la chaudière. Plage −55…125 °C, large marge. | 1 (2 conseillé) | 3–6 €/u |
| Résistance 4,7 kΩ | Tirage haut de la ligne 1-Wire. | 1 | — |
| Module entrée opto-isolée (PC817) 2 canaux | Lecture des contacts thermiques (sans potentiel) des deux vis, isolée de l'environnement moteur. | 1 | 2–4 € |

> Une seconde DS18B20 (redondance / recoupement) est peu coûteuse et supprime un
> point de défaillance unique sur la mesure.

### Chaîne de sécurité matérielle

| Réf. | Description | Qté | ~Prix |
|------|-------------|-----|-------|
| **Thermostat de sécurité à réarmement manuel (STB)** ~95–100 °C, à capillaire — type *IMIT*, *Jumo*, *Thermowatt* | Coupure **matérielle** des organes de combustion en cas de surchauffe, indépendante de l'ESP32. Réutiliser celui d'origine s'il est fonctionnel. | 1 | 10–20 € |
| Thermostat mécanique anti-condensation ~40 °C, optionnel | Secours du circulateur : le fait tourner si l'eau est chaude même ESP32 hors service. | 1 | 8–15 € |

### Connectique & coffret

| Réf. | Description | Qté | ~Prix |
|------|-------------|-----|-------|
| **Coffret IP65** polycarbonate/ABS + platine ou rail DIN, ~300×200×120 mm — type *Fibox*, *Spelsberg*, *Gewiss* | Boîtier mural étanche (environnement poussiéreux/chaud). | 1 | 20–40 € |
| Rail DIN + borniers (Wago 221 / Phoenix) | Répartition N/PE, raccordements. | lot | 10–20 € |
| Presse-étoupes M16/M20 | Entrées câbles : secteur, 5 moteurs, sonde, contacts. | ~10 | <1 €/u |
| Câble souple 3G1,5 mm², câble signal blindé 2–4 conducteurs | Liaisons puissance et capteurs. | au besoin | — |
| Séparateurs / barrières, étiquettes | Séparation physique zones 230 V / TBT, repérage. | lot | 5–10 € |

**Budget indicatif total : 150–300 €** selon la qualité des SSR.

---

## 3. Câblage

### 3.1 Deux zones, deux domaines de tension

Dans le coffret, séparer **physiquement** la zone 230 V (PSU, disjoncteur, SSR,
borniers moteurs) de la zone très basse tension (ESP32, ULN, capteurs).
Presse-étoupes dédiés, barrière entre les deux.

### 3.2 La chaîne de sécurité (le point critique)

Le STB 95 °C se câble **en série sur l'alimentation des organes de combustion
uniquement** — exactement comme le régulateur MONEX d'origine (la pompe continue
de tourner en surchauffe pour évacuer la chaleur).

```
230 V ─[C6A]─┬─────────────────────────────────────── N ───────────┬─ N moteurs
             │                                                      │
             │   ZONE COMBUSTION  (derrière le STB)                 │
             ├─[STB 95 °C]─┬─ SSR1 ───── L vis trémie ──────────────┤
             │             ├─ SSR2 ───── L vis brûleur ─────────────┤
             │             └─ RELAIS 1 ─ L ventilateur ─────────────┤
             │                                                      │
             │   ZONE CIRCULATION  (hors STB)                       │
             ├─ RELAIS 2 ─── L circulateur ─────────────────────────┤
             ├─ RELAIS 3 ─── L laddomat ────────────────────────────┤
             │                                                      │
             └─[PSU 230→5 V]                                        │
```

- Les 2 SSR pilotent les vis ; la carte relais (3 voies) pilote ventilateur,
  circulateur et laddomat.
- Le STB déclenché coupe vis + ventilateur **même si l'ESP32 est planté**.
- Circulateur et laddomat ne sont **pas** derrière le STB : ils doivent pouvoir
  évacuer la chaleur. Le thermostat anti-condensation optionnel les fait tourner
  même ESP32 hors service.
- Snubber RC aux bornes de chaque moteur.

### 3.3 Commande des organes

Les 5 sorties sont pilotées de façon uniforme à travers le **ULN2803** : il
protège l'ESP32 et, avec un tirage bas sur chaque entrée, garantit que tout
reste à l'arrêt pendant le boot/reset de l'ESP32. Si l'ESP32 meurt, tous les
organes s'arrêtent (fail-safe).

```
ESP32 GPIO ──┬──[10 kΩ]── GND        (tirage bas : organe à l'arrêt au boot)
             └──────────── ULN2803 entrée
ULN2803 sortie ──┬── SSR(−)            pour les 2 vis   [SSR(+) ── +5 V]
                 └── carte relais INx  pour les 3 autres organes
```

Détail du câblage de la carte de commande : voir §3.6.

### 3.4 Capteurs et entrées

```
DS18B20  : DATA ── ESP32 GPIO  +  4,7 kΩ entre DATA et 3,3 V ; VCC=3,3 V ; GND
Contacts vis : contact sec ── module opto PC817 ── ESP32 GPIO (entrée)
```

### 3.5 Affectation des GPIO ESP32 (proposition)

| GPIO | Usage | Sens |
|------|-------|------|
| 25, 26 | Commande SSR : vis trémie, vis brûleur | sortie → ULN2803 |
| 27, 32, 33 | Commande carte relais : ventilateur, circulateur, laddomat | sortie → ULN2803 |
| 21 | 1-Wire (DS18B20) | E/S |
| 16, 17 | Contacts thermiques vis trémie / brûleur (via opto) | entrée |
| 13 | Buzzer/alarme locale (optionnel) | sortie |
| 2 | LED d'état (intégrée) | sortie |

GPIO de strapping (0, 5, 12, 15) volontairement évités.

### 3.6 Schéma de câblage de la carte de commande

Carte à souder (plaque à trous) regroupant l'ESP32 et le ULN2803. Les 2 SSR, la
carte relais et le PSU sont sur le rail DIN — la carte de commande s'y relie par
des borniers à vis.

**Brochage du ULN2803A (DIP-18)**

```
          ┌────────∪────────┐
   IN1 ──┤ 1            18 ├── OUT1
   IN2 ──┤ 2            17 ├── OUT2
   IN3 ──┤ 3            16 ├── OUT3
   IN4 ──┤ 4            15 ├── OUT4
   IN5 ──┤ 5            14 ├── OUT5
   IN6 ──┤ 6            13 ├── OUT6   (non câblé)
   IN7 ──┤ 7            12 ├── OUT7   (non câblé)
   IN8 ──┤ 8            11 ├── OUT8   (non câblé)
   GND ──┤ 9            10 ├── COM
          └─────────────────┘
```

**Liste des liaisons (netlist) — c'est la référence de soudure**

| De | Vers | Note |
|----|------|------|
| PSU +5 V | ESP32 `5V` / `VIN` | alimentation de l'ESP32 |
| PSU +5 V | ULN2803 pin 10 (COM) | retour des diodes de roue libre |
| PSU +5 V | SSR1(+), SSR2(+) | alim. entrée des 2 SSR |
| PSU +5 V | carte relais `VCC` et `JD-VCC` | alim. carte relais |
| PSU +5 V | condensateur 470 µF (+) | réservoir sur le rail 5 V |
| PSU GND | ESP32 `GND`, ULN2803 pin 9, carte relais `GND`, 470 µF (−) | masse commune, en étoile |
| ESP32 `GPIO25` | ULN2803 pin 1 (IN1) | vis trémie |
| ESP32 `GPIO26` | ULN2803 pin 2 (IN2) | vis brûleur |
| ESP32 `GPIO27` | ULN2803 pin 3 (IN3) | ventilateur |
| ESP32 `GPIO32` | ULN2803 pin 4 (IN4) | circulateur |
| ESP32 `GPIO33` | ULN2803 pin 5 (IN5) | laddomat |
| ULN2803 pins 1–5 | GND, via 10 kΩ chacun | tirage bas : sorties OFF au boot |
| ULN2803 pin 18 (OUT1) | bornier → SSR1(−) | vis trémie |
| ULN2803 pin 17 (OUT2) | bornier → SSR2(−) | vis brûleur |
| ULN2803 pin 16 (OUT3) | bornier → carte relais `IN1` | ventilateur |
| ULN2803 pin 15 (OUT4) | bornier → carte relais `IN2` | circulateur |
| ULN2803 pin 14 (OUT5) | bornier → carte relais `IN3` | laddomat |
| ESP32 `3V3` | DS18B20 VDD, module opto VCC | alim. capteurs (3,3 V) |
| ESP32 `GPIO21` | DS18B20 DATA | ligne 1-Wire |
| DS18B20 DATA | ESP32 `3V3`, via 4,7 kΩ | tirage haut 1-Wire |
| ESP32 `GPIO16` | module opto sortie 1 | contact thermique vis trémie |
| ESP32 `GPIO17` | module opto sortie 2 | contact thermique vis brûleur |
| module opto entrées | borniers → contacts secs des vis | contacts sans potentiel |

**Schéma**

```
  PSU 5V ●──────┬──────────┬──────────┬─────────────┬────────── +5 V
                │          │          │             │
            [470µF]   ESP32 5V   SSR1(+) SSR2(+)  relais VCC/JD-VCC
                │
  PSU GND ●─────┴── GND commune (ESP32 GND · ULN p9 · relais GND · 470µF−) ──

  ESP32                          ULN2803                vers le rail DIN
  ┌────────────┐            ┌───────────────┐
  │      GPIO25├────────────┤1 IN1   OUT1 18├── bornier ─▶ SSR1(−)      trémie
  │      GPIO26├────────────┤2 IN2   OUT2 17├── bornier ─▶ SSR2(−)      brûleur
  │      GPIO27├────────────┤3 IN3   OUT3 16├── bornier ─▶ relais IN1   ventilo
  │      GPIO32├────────────┤4 IN4   OUT4 15├── bornier ─▶ relais IN2   circul.
  │      GPIO33├────────────┤5 IN5   OUT5 14├── bornier ─▶ relais IN3   laddomat
  │            │   │ │ │ │ │                                
  │            │  [10kΩ ×5 vers GND]      p10 COM ── +5 V
  │            │                          p9  GND ── GND commune
  │            │
  │      GPIO21├──┬───────────────────────── DS18B20 DATA
  │        3V3 ├──┴[4,7kΩ]  ●─────────────── DS18B20 VDD · module opto VCC
  │      GPIO16├──────────────────────────── module opto OUT1 (contact trémie)
  │      GPIO17├──────────────────────────── module opto OUT2 (contact brûleur)
  │        GND ├──────────────────────────── GND commune
  └────────────┘
```

**Points d'attention soudure**

- Les **5 résistances de 10 kΩ** (entrées ULN → GND) sont indispensables : sans
  elles, un organe peut s'activer pendant le boot de l'ESP32.
- ULN2803 monté sur **support tulipe**, jamais soudé directement.
- Condensateur 470 µF (≥ 10 V) sur le rail 5 V, au plus près de la carte relais.
- Masse en **étoile** : ramener tous les GND à un point unique côté PSU.
- Les liaisons vers le rail DIN (SSR, carte relais) passent par des **borniers à
  vis** soudés sur la plaque — aucun fil de puissance soudé « en l'air ».
- L'ESP32 est alimenté par le 5 V du PSU (broche `5V`/`VIN`) : ne pas le brancher
  en USB en même temps.
- Vérifier le sens de la carte relais : pilotée ici en logique basse (le ULN tire
  l'entrée `INx` vers la masse pour enclencher) — c'est le cas des cartes relais
  « actives au niveau bas » courantes.

---

## 4. Packaging

- **Coffret IP65 mural**, fixé près de la chaudière mais **pas dessus** (chaleur).
- Platine ou rail DIN portant : disjoncteur C6A, PSU Mean Well, 2 SSR, carte
  relais, carte de commande ESP32+ULN, borniers.
- SSR espacés sur le rail (dissipation) ; dissipation réelle faible vu la
  puissance des moteurs.
- Presse-étoupes en partie basse : 1 secteur, 5 moteurs, 1 sonde, 1 nappe
  contacts. Câbles signal **blindés**, séparés des câbles puissance.
- Tout repérer (sérigraphie ou étiquettes) : chaque SSR, chaque bornier.
- Antenne WiFi de l'ESP32 dégagée du métal ; si le coffret est métallique,
  déporter une antenne.
- Réutiliser le doigt de gant existant de la chaudière pour la DS18B20 (et le
  STB).

---

## 5. Interface téléphone

Approche retenue : **l'ESP32 sert lui-même une interface web responsive**.
Aucune application à installer — on ouvre le navigateur du téléphone.

- **Mode WiFi station** : l'ESP32 rejoint le réseau de la maison. Première
  configuration via portail captif (bibliothèque type *WiFiManager*).
- **Accès** : `http://chaudiere.local` (mDNS) ou IP fixe.
- **Serveur web embarqué** (*ESPAsyncWebServer*) : page unique mobile-first.
  - Affichage : état (`Stopped`/`Running`/`Slowing`/…), température courante,
    état des 5 organes, dernières alertes.
  - Commandes : Start / Stop / LoadPellets / Shutdown, réglages (consigne,
    `delay_off`, `rest_duration`).
  - **WebSocket** pour la mise à jour en temps réel.
- **Sécurité d'accès** : code PIN ou authentification simple ; appareil à garder
  **sur le réseau local**, jamais exposé directement à Internet.

Côté logiciel, le firmware ESP32 = le cœur C++ [`controller/`](../controller/README.md)
+ implémentations de `IActuators` (pilotage des GPIO/SSR) et `IReporter`
(journal + push vers l'UI web) + boucle hôte (horloge, lecture DS18B20, `tick()`).

### Évolution (phase 3)

Pour l'historique, les graphes et l'accès distant : l'ESP32 publie en **MQTT**
vers le superviseur sur le Raspberry Pi (Elasticsearch, dashboards, alertes
SMS/e-mail). Le téléphone dialogue alors avec le superviseur. Accès hors domicile
via VPN/Tailscale sur le Pi — jamais en exposant l'ESP32.

---

## 6. Mise en service — précautions

1. Vérifier la **chaîne de sécurité matérielle** avant tout : STB câblé en série,
   testé (déclenchement + réarmement). C'est non négociable.
2. Tester l'étage de puissance **sans la chaudière** : vérifier que chaque sortie
   commute le bon organe, que tout est à l'arrêt au boot de l'ESP32.
3. Vérifier le fail-safe : ESP32 hors tension → tous les organes à l'arrêt.
4. Contrôler la lecture de la sonde et des contacts thermiques avant d'autoriser
   la combustion.
5. Première chauffe sous surveillance, STB et extincteur à portée.
