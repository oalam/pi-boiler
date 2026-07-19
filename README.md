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
sbt clean package              # produit target/scala-2.11/regulation_2.11-1.8.jar
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
