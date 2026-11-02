# pi-boiler — régulation DIY de chaudière à granulés

Logiciel de régulation pour une chaudière à granulés de bois **Deville CEDRA ECO 24**,
tournant sur un Raspberry Pi. Il remplace le régulateur d'origine **MONEX** (hors service).

Le programme lit une sonde de température 1-Wire, pilote les moteurs de la chaudière via
les GPIO du Pi, expose une petite API REST pour la commande manuelle, et journalise les
événements dans Elasticsearch avec alerte par SMS / e-mail.

Projet `sbt` nommé `regulation` — Scala 2.11.7, Akka actors, Spray HTTP, pi4j pour les GPIO.

---

## Contexte matériel

La chaudière comporte (voir notice MONEX, `68209d.pdf`) :

| Organe | Rôle |
|--------|------|
| Vis sans fin trémie (`Tremie`) | amène les granulés de la trémie vers le flexible |
| Vis sans fin brûleur (`Bruleur`) | amène les granulés dans le brûleur |
| Ventilateur (`Ventilo`) | air de combustion |
| Circulateur / vanne 4 voies (`Vanne4V`) | circulation eau de chauffage |
| Pompe de charge (`Laddomat`) | circuit de charge |

Deux capteurs de sécurité (anti-bourrage) sur les vis remontent leur état au programme.

> ⚠️ **Sécurité** — la protection surchauffe à 95 °C doit rester un **thermostat physique
> câblé en coupure matérielle**, indépendant du Raspberry Pi. La protection logicielle
> (à 90 °C) n'est qu'un complément ; elle ne couvre pas un plantage du Pi.

---

## Architecture

Le programme est un système d'acteurs Akka (`ActorSystem("pi-boiler")`). Les acteurs sont
frères et se référencent par chemin relatif (`../boiler`, etc.) — **les noms d'acteurs dans
`Boot` sont donc structurants**.

```
TemperatureEventHandler  ──(événements de seuil)──▶  Boiler
   (lit la sonde toutes les 2 s)                       │
                                                       │ (commandes de cycle)
                                                       ▼
                                              EngineCyclesManager
                                                       │ (séquences de moteurs temporisées)
                                                       ▼
                                                 GPIOEngineDriver  ──▶  GPIO / relais
                                                       
ReportHandler  ◀──(tous les événements notables)──  Elasticsearch + SMS + e-mail
RestServiceActor  ──▶  API REST de commande manuelle
```

- **`TemperatureEventHandler`** — interroge la sonde toutes les 2 s, émet des événements
  *de franchissement* de seuils (30 / 40 / 60 / 90 °C) et de consigne.
- **`Boiler`** — acteur de décision : traduit les événements de température en commandes
  de cycle, gère la consigne et la réaction de surchauffe.
- **`EngineCyclesManager`** — transforme les commandes de cycle en séquences temporisées
  d'arrêt/démarrage moteurs (`scheduler.scheduleOnce`), et se replanifie pour le cycle
  suivant.
- **`GPIOEngineDriver`** — seul acteur en contact avec le matériel. **Broches actives au
  niveau bas** : `.low()` démarre un moteur, `.high()` l'arrête.

  Affectation des broches (numérotation wiringPi, reprise à l'identique dans
  `ENGINE_PINS` de `ui/pi_bridge.py`) :

  | Organe | wPi | BCM | Broche physique |
  |--------|-----|-----|-----------------|
  | Brûleur (`Bruleur`) | 0 | 17 | 11 |
  | Trémie (`Tremie`) | 2 | 27 | 13 |
  | Ventilateur (`Ventilo`) | 3 | 22 | 15 |
  | Circulateur (`Vanne4V`) | 4 | 23 | 16 |
  | Laddomat (`Laddomat`) | 1 | 18 | 12 |
  | Sécurité trémie (entrée) | 5 | 24 | 18 |
  | Sécurité brûleur (entrée) | 6 | 25 | 22 |

  Circulateur et laddomat ont été permutés en octobre 2026 (v1.8.1) pour suivre le
  câblage constaté après le déménagement de la chaudière.
- **`ReportHandler`** — journalise dans Elasticsearch, envoie les alertes SMS / e-mail.
- **`RestServiceActor`** — expose `GET /api/v0.1/action?type=...`.

Les messages partagés et les constantes de réglage sont dans `core/package.scala`.

### Deux points d'entrée

- **`api.Boot`** — production : système complet, GPIO réels, sonde 1-Wire réelle
  (`/sys/bus/w1/devices`), service REST.
- **`app.Main`** — simulation locale : mêmes acteurs, sans REST, écrit de fausses
  températures dans `src/main/resources/.../w1_slave` pour tester la logique sans matériel.

---

## Logique de régulation

Comportement actuel (aligné sur la notice MONEX) :

- **Cycle de combustion** — les deux vis + le ventilateur démarrent ensemble ; la vis
  trémie s'arrête après `HEATING_MODE_ENGINES_RUNNING_DURATION` (3 s), la vis brûleur
  5 s plus tard (post-fonctionnement, vide le flexible). Un nouveau cycle est replanifié
  après le temps d'arrêt (`delayOff`, réglable).
- **Circulateur** — démarré au franchissement de 40 °C en montée, arrêté en descente sous
  40 °C (anti-condensation).
- **Mode ralenti** — à l'atteinte de la consigne, la chaudière passe en ralenti :
  relance des moteurs pendant `SLOWDOWN_MODE_ENGINES_RUNNING_DURATION` (2 min) toutes les
  `restDuration` minutes pour entretenir le feu. Sortie du ralenti quand la température
  redescend à `consigne − 3 °C`.
- **Surchauffe** — au-delà de 90 °C : arrêt des vis, démarrage circulateur + pompe.
- **Panne de sonde** — après `SENSOR_FAILURE_ALERT_THRESHOLD` (5) lectures muettes
  consécutives (~10 s), une alerte `TemperatureSensorFailure` est émise (SMS + e-mail + log).

Constantes de réglage : `core/package.scala`. Réglages dynamiques (consigne, `delayOff`,
`restDuration`) : via l'API REST `?type=set`.

---

## Compilation

Le build a été remis en état de marche en juillet 2026 : **sbt 0.13.18** (cf.
`project/build.properties`), **toutes les dépendances viennent de Maven Central**
(y compris `pi4j-core 1.2`, qui remplace l'ancien `1.2-SNAPSHOT` disparu des
dépôts) — plus aucun build local préalable n'est nécessaire.

> ⚠️ **Java 8 obligatoire** : Scala 2.11.7 ne compile pas sur un JDK ≥ 9.
> Sur un Mac Apple Silicon, le plus simple est un JDK 8 Zulu (Azul publie des
> builds arm64) :
> ```bash
> brew install --cask zulu@8
> sbt -java-home "$(/usr/libexec/java_home -v 1.8)" clean package
> ```
> Le lanceur sbt Homebrew récent peut échouer à reconstituer un vieux sbt 0.13 ;
> en cas de souci, utiliser le lanceur d'époque
> ([sbt-0.13.18.tgz](https://github.com/sbt/sbt/releases/tag/v0.13.18)) :
> `java -jar sbt/bin/sbt-launch.jar clean package`.

```bash
sbt clean package              # produit target/scala-2.11/regulation_2.11-1.8.1.jar
sbt assemblyPackageDependency  # produit le jar des dépendances
sbt test                       # lance les tests (scalatest)
sbt "testOnly org.oalam.regulation.TemperatureSensorTest"   # un seul test
```

---

## Déploiement et lancement

Le Pi (`boiler`, un Pi 2 depuis juillet 2026, Raspbian 9 Stretch + Java 8 +
wiringPi) porte l'**IP statique `192.168.1.12`** — configurée dans son
`/etc/dhcpcd.conf`, avec `net.ifnames=0` dans `/boot/cmdline.txt` pour que
l'interface reste `eth0` quel que soit le Pi (le code binde cette IP en dur).
Accès : `ssh pi@192.168.1.12` (clé).

Les jars vivent dans `/home/pi/`. Deux services systemd, activés au boot :

| Service | Rôle |
|---------|------|
| `boiler` | la régulation (`java … api.Boot`, root pour les GPIO, `Restart=always`, attend l'IP avant de démarrer) |
| `boiler-ui` | l'IHM web mobile (`python3 ui/pi_bridge.py`, port 8080, utilisateur `pi`) |

```bash
sudo systemctl status boiler boiler-ui   # état
sudo journalctl -u boiler -f             # logs (en RAM, cf. supervision)
```

Mise à jour du code : recompiler sur le poste de dev, `scp` les jars vers
`/home/pi/`, puis `sudo systemctl restart boiler`.

### IHM mobile

`http://192.168.1.12:8080` (depuis le réseau local) : température en direct,
état, voyants des 5 moteurs, boutons Démarrer / Arrêter / Amorcer / Arrêt
complet. La page (`ui/index.html`) est **celle de la phase 2 ESP32, inchangée** :
`ui/pi_bridge.py` implémente son contrat d'API (`/api/status`, `/api/command`)
au-dessus de l'existant — sonde lue en sysfs, moteurs lus via `gpio read`,
commandes relayées à l'API Scala. Limites : consommation de granulés à 0 (non
suivie par la phase 1) et état dérivé du ventilateur (`Running`/`Stopped`).

Une page **Fonctionnement et règles de régulation** (`ui/doc.html`, servie sur
`http://192.168.1.12:8080/doc.html`, lien en bas de l'IHM) documente pour
l'utilisateur toutes les règles métier telles que le code les applique : boutons,
cycle de combustion, seuils, ralenti, surchauffe, verrouillage, bourrages, panne
de sonde, réglages, supervision et points d'attention. À tenir synchrone avec
`core/package.scala` et `Boiler.scala` à chaque changement de règle. Le firmware
ESP32 embarque sa propre copie de l'IHM et ne sert pas cette page.

---

## Supervision et robustesse

Mise en place en juillet 2026 (sources dans [`deploy/`](deploy/), configuration
secrète — topic ntfy, URL healthchecks — dans `/etc/boiler-monitor.conf` sur le
Pi, **hors git**) :

- **Chien de garde applicatif** — `boiler-monitor.timer` lance
  `boiler-monitor.sh` chaque minute : API de régulation muette → redémarrage
  automatique du service + alerte ; sonde 1-Wire muette → alerte « régulation
  aveugle » ; eau ≥ 90 °C → alerte urgente. Anti-spam : une alerte par
  changement d'état (flags dans `/run`, tmpfs).
- **Alertes push** — [ntfy.sh](https://ntfy.sh), abonnement au topic depuis
  l'appli smartphone. Remplace de fait les vieux `SmsAgent`/`MailAgent` morts.
- **Heartbeat externe** — ping [healthchecks.io](https://healthchecks.io) chaque
  minute (grâce 5 min) : alerte quand le Pi *ne peut plus* parler (Pi mort, CPL
  débranché, box HS) — le seul cas qu'aucune supervision embarquée ne couvre.
- **Watchdog matériel** — le watchdog du BCM2835 est armé par systemd
  (`RuntimeWatchdogSec=14`) : OS gelé → reboot automatique, la pile complète
  revient seule (testé).
- **Journaux en RAM** — `journald` en `Storage=volatile` : zéro écriture SD en
  régime normal (les logs ne survivent pas au reboot, les événements importants
  partent en notification).

**Reste à faire côté matériel** : carte microSD neuve « high endurance » + image
de sauvegarde (la carte de 2018 a déjà corrompu une écriture), puis racine en
lecture seule ; remplacer les relais mécaniques des deux vis — déjà morts deux
fois, ~3 700 commutations/jour en chauffe — par des SSR zéro-cross (cf. panier
Conrad de la phase 2 : Crouzet 84132210N + varistance + condensateur X2).

---

## Journalisation vers Powerview2

`ui/boiler_logger.py` (service `boiler-logger`, utilisateur `pi`) observe chaque
seconde la sonde et les 7 GPIO (5 organes, 2 contacts de bourrage), agrège par
minute, détecte les événements par transition et dépose des CSV par SFTP sur le
VPS Powerview2 (SFTPGo, port 2022) toutes les 5 min, au format canonique `PV2_CSV_V001` /
`PV2_EVENTS_V001` (powerview2 v1.4.33). Chemin de dépôt
`<home SFTP>/chaudiere/pi-boiler/` : `chaudiere` devient la campagne et
`pi-boiler` l'appareil dans Powerview2. Il ne dépend pas de la régulation Scala
(qui n'est pas modifiée) et ne touche pas aux GPIO en écriture.

Voies produites (une valeur par minute, préfixe `boiler_`) :

| Voie | Unité | Contenu |
|------|-------|---------|
| `temp` | C | température moyenne de l'eau sur la minute |
| `tremie`, `bruleur`, `ventilo`, `circulateur`, `laddomat` | s | secondes de marche dans la minute |
| `pellets` | g | granulés estimés = secondes trémie × `FEED_RATE_G_PER_S` |
| `power` | W | puissance thermique utile moyenne sur la minute |
| `energy` | Wh | énergie thermique utile de la minute |
| `pellets_total` (kg), `energy_total` (Wh) | | cumuls depuis la mise en service (index) |
| `state` | code | 1 = combustion (ventilateur en marche au moins 30 s), 0 sinon |
| `event` | code | code du dernier événement de la minute, 0 sinon |

Modèle énergétique (notice MONEX, CEDRA ECO 24 : PCI ≈ 5 kWh/kg, rendement
85 %) : `energy_Wh = pellets_g × PCI_KWH_PER_KG × EFFICIENCY`, `power_W =
energy_Wh × 60`. Avec les réglages par défaut (3 s de trémie par cycle de 23 s,
débit 8,7 g/s) : 26,1 g par cycle, 156,5 cycles/h, 4,1 kg/h, soit 20,4 kW PCI et
17,4 kW utiles, dans la plage 7 à 24 kW de la notice. **Le débit de la vis est à
calibrer** : lancer Amorcer (50 s de trémie), peser les granulés livrés, diviser
par 50, reporter dans `FEED_RATE_G_PER_S`.

Événements (fichier `*_events.csv`, catégorie `chaudiere`, annotations globales
dans Powerview2) : démarrage du logger (1), cycles de combustion démarrés /
arrêtés (2 / 3, ventilateur débouncé 5 s), amorçage (4), circulateur et
laddomat en marche / à l'arrêt (5 à 8), passages sous 30 °C (10), 40 °C en
montée / descente (11 / 12), 60 °C (13), consigne atteinte / reprise à
consigne − 3 (14 / 15), surchauffe 90 °C en plage avec le maximum (16),
bourrages trémie / brûleur (20 / 21), sonde muette / rétablie (30 / 31), GPIO
illisibles (40). La liste fait foi dans `EVENTS` de `ui/boiler_logger.py`.

Installation sur le Pi (sources dans `deploy/` et `ui/`) :

```bash
scp ui/boiler_logger.py pi@192.168.1.12:/home/pi/ui/
scp deploy/boiler-logger.service pi@192.168.1.12:/tmp/
sudo install -m 644 /tmp/boiler-logger.service /etc/systemd/system/
sudo install -m 640 -o root -g pi deploy/boiler-logger.conf.sample /etc/boiler-logger.conf   # puis éditer
sudo sh -c 'umask 077; printf "machine 217.160.116.85 login <user> password <mdp>\n" > /etc/boiler-logger.netrc; chown pi /etc/boiler-logger.netrc'
sudo mkdir -p /var/lib/boiler-logger && sudo chown pi /var/lib/boiler-logger
sudo systemctl daemon-reload && sudo systemctl enable --now boiler-logger
sudo journalctl -u boiler-logger -f
```

Le dépôt utilise `curl` (support SFTP de libssh2, présent sur Raspbian). Les
fichiers non déposés (VPS injoignable) attendent dans
`/var/lib/boiler-logger/outbox/` et partent au tour suivant ; un fichier rejoué
n'écrase que les mêmes horodatages côté Influx et ne crée pas de doublon
d'annotation. Tests sans matériel : `python3 -m pytest tests/ui`.

## Configuration

- **Service REST** — hôte et port sont **codés en dur** dans `api/Boot.scala`
  (`192.168.1.12:9000`). À adapter si l'IP du Pi change.
- **Elasticsearch** — adresse codée en dur dans `util/ElasticsearchAgent.scala`
  (`192.168.1.67:9300`, cluster `boiler`). Les écritures sont best-effort.
- **Sonde 1-Wire** — `TemperatureSensor` mappe le nom logique `temperatureEauSortie` vers
  l'identifiant physique `28-021581cc38ff`.
- **Akka** — `src/main/resources/application.conf` ; logs : `logback.xml`.

---

## API REST

`GET /api/v0.1/action?type=<action>`

| `type` | Effet |
|--------|-------|
| `load` | charge le brûleur en granulés (allumage) |
| `start` | démarre la chaudière |
| `stop` | arrête le cycle de combustion |
| `shutdown` | arrêt complet + extinction du programme |
| `set` | met à jour les réglages : `&temperature=`, `&delayOff=` (s), `&restDuration=` (min) |

---

## Critique et évolution

Le fil conducteur du chantier de réécriture est [`REWORK.md`](REWORK.md) : stratégie,
état d'avancement et feuille de route.

L'analyse critique de l'architecture (pertinence du Raspberry Pi, interfaçage moteurs,
choix de langage) et les pistes d'évolution priorisées sont dans
[`docs/critique-evolution.md`](docs/critique-evolution.md).

La réécriture du cœur de régulation a commencé : une machine à états C++ portable,
indépendante du matériel et testée, vit dans [`controller/`](controller/README.md). Elle a
vocation à remplacer les acteurs Scala `Boiler` + `EngineCyclesManager` et à se déployer
sur micro-contrôleur.

## Limitations connues

- **Pas de machine à états explicite** — il n'existe pas d'état `LOCKED` persistant ni de
  temporisation de verrouillage (allumage raté à 60 min, chute sous 30 °C après 30 min,
  comme le faisait le MONEX). La panne de sonde est signalée mais ne met pas la chaudière
  en sécurité automatiquement.
- **Protection surchauffe purement logicielle** (90 °C) — voir l'avertissement de sécurité
  plus haut. La supervision (`deploy/`) ajoute une deuxième couche logicielle d'alerte,
  mais ne remplace pas non plus un thermostat matériel.
- **Secrets en clair** — `util/SmsAgent.scala` contient un identifiant API SMS dans le code,
  `util/MailAgent.scala` a des identifiants Gmail en placeholder (`"***"`). Ces deux canaux
  sont morts (remplacés en pratique par ntfy) mais les identifiants restent à révoquer et le
  code à retirer.
- **Aucun test sur la logique de régulation** — seul `TemperatureSensor` est couvert.
