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

> ⚠️ **Java 8 obligatoire.** Le projet utilise **sbt 0.13.8** (cf.
> `project/build.properties`), qui **ne fonctionne pas sur un JDK ≥ 9** : sur un
> JDK récent (17, 21…) le build échoue avec `NoClassDefFoundError: sbt/Configuration`.
> Il faut un **JDK 8**.
>
> Sur macOS, installer puis cibler explicitement le JDK 8 :
> ```bash
> brew install --cask temurin@8
> export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)   # à faire avant chaque build
> ```

### Ordre de build

**1. Construire `pi4j-core` d'abord.** La dépendance `pi4j-core 1.2-SNAPSHOT`
n'est pas publiée sur les dépôts publics — il faut la compiler localement et
l'installer dans le dépôt Maven local, sinon `sbt` échoue à la résolution :

```bash
# dans le dépôt pi4j, avec le JDK 8 actif
mvn clean install -P raspberrypi
```

**2. Construire ce dépôt** (toujours avec le JDK 8 actif) :

```bash
sbt clean package              # produit target/scala-2.11/regulation_2.11-1.8.jar
sbt assemblyPackageDependency  # produit le jar des dépendances
sbt test                       # lance les tests (scalatest)
```

Pour ne lancer qu'un test :

```bash
sbt "testOnly org.oalam.regulation.TemperatureSensorTest"
```

> Ces contraintes (JDK 8, snapshot non publié) sont précisément le « build non
> reproductible » identifié dans [`docs/critique-evolution.md`](docs/critique-evolution.md) ;
> leur résolution fait partie du chantier décrit dans [`REWORK.md`](REWORK.md).

---

## Déploiement et lancement

Sur le Raspberry Pi (l'accès GPIO nécessite les droits root) :

```bash
sudo apt-get install wiringpi   # support GPIO

cd apps/
sudo nohup java -cp regulation_2.11-1.8.jar:regulation-assembly-1.8-deps.jar \
    org.oalam.regulation.api.Boot &
sudo tail -f nohup.out
```

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
  plus haut.
- **Secrets en clair** — `util/SmsAgent.scala` contient un identifiant API SMS dans le code,
  `util/MailAgent.scala` a des identifiants Gmail en placeholder (`"***"`). À externaliser /
  révoquer.
- **Aucun test sur la logique de régulation** — seul `TemperatureSensor` est couvert.
