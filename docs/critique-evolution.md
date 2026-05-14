# Critique du système et pistes d'évolution

Document annexe au `README.md`. Il porte un regard critique sur l'architecture actuelle
de `pi-boiler` et propose des pistes d'évolution. Trois questions sont traitées :

1. Le Raspberry Pi est-il le bon support ? Faut-il un autre micro-contrôleur ?
2. Comment interfacer proprement les moteurs ?
3. Le code doit-il être porté sur un autre langage ?

> **Principe directeur** — on régule ici un appareil de combustion. Le fil rouge de tout
> ce document est : **la chaîne de sécurité matérielle ne doit jamais dépendre du
> logiciel.** Le régulateur MONEX d'origine l'avait bien compris (thermostat 95 °C
> « indépendant du régulateur », protections thermiques moteurs câblées en dur). Toute
> évolution doit conserver ce principe.

---

## 1. Critique du système actuel

### 1.1 Le Raspberry Pi comme contrôleur temps réel

Le Pi a permis de remettre la chaudière en service rapidement et apporte gratuitement le
réseau, l'API REST, la journalisation Elasticsearch et les alertes. Mais comme **organe de
régulation d'une chaudière**, il présente des faiblesses structurelles :

- **Pas de temps réel, pas de déterminisme.** Linux + JVM : pauses GC, ordonnancement non
  garanti. La régulation tolère ces aléas (constantes de temps thermiques longues), mais
  rien ne le garantit *par conception*.
- **Corruption de carte SD.** C'est le mode de défaillance n°1 d'un Pi en service continu :
  une coupure secteur pendant une écriture corrompt le système de fichiers → la chaudière
  ne redémarre plus. Aggravé ici par `logback.xml` en `level="debug"` qui écrit en
  permanence sur la SD.
- **Pas de watchdog matériel activé.** Si la JVM se fige ou si l'OS se bloque, rien ne
  relance le système. Pour une chaudière, c'est dangereux : une vis pourrait rester
  alimentée, ou le feu s'éteindre sans alerte.
- **Temps de démarrage long** (dizaines de secondes). Après une coupure, la chaudière est
  non régulée pendant tout le boot.
- **Alimentation et E/S fragiles.** Les GPIO 3,3 V ne sont pas isolés ; un défaut de
  câblage côté puissance peut détruire le Pi. Les micro-coupures provoquent des
  comportements indéfinis.

**Le vrai problème n'est pas le Pi en soi : c'est qu'on lui fait porter deux rôles de
natures opposées.**

| Rôle | Besoins | Criticité |
|------|---------|-----------|
| **Boucle de régulation** (lire la sonde, piloter vis/ventilo, faire respecter les seuils) | déterminisme, fiabilité, watchdog, repli sûr | **critique** |
| **Supervision / télémétrie** (logs, dashboards, REST, alertes) | réseau, librairies, confort de dev | non critique |

Le Pi est excellent pour le second rôle, médiocre pour le premier.

### 1.2 L'interfaçage moteurs

Les organes pilotés sont des **moteurs asynchrones 230 V** (vis trémie S1, vis brûleur S2,
ventilateur, circulateur). Le code (`GPIOEngineDriver`) commande des broches GPIO, a priori
vers une carte de relais. Plusieurs points posent problème :

- **Usure des relais mécaniques.** En cycle de combustion, les vis commutent toutes les
  ~15–55 s, soit de l'ordre de **10⁶ manœuvres par an**. Un relais mécanique standard
  (100 k–500 k manœuvres sous charge) tient quelques mois. C'est un défaut réel de
  conception.
- **Charges inductives.** Les moteurs génèrent des surtensions à la coupure (back-EMF).
  Sans circuit d'amortissement (snubber RC), les contacts s'arquent, s'usent vite et
  génèrent des perturbations électromagnétiques susceptibles de faire planter le Pi.
- **Courant d'appel.** Le courant de démarrage d'un moteur vaut plusieurs fois le courant
  nominal ; relais/SSR doivent être dimensionnés pour l'appel, pas seulement le régime.
- **Isolation galvanique.** Le 230 V ne doit jamais côtoyer les 3,3 V de la logique. Les
  cartes relais opto-isolées sont un minimum.
- **Protections thermiques moteurs.** La notice insiste : chaque vis a une protection
  thermique (fils blancs, bornes MOTOR 1/MOTOR 2) qui **ne doit jamais voir le 230 V**.
  Les entrées `tremieSecu` / `bruleurSecu` du code correspondent aux capteurs
  anti-bourrage ; elles doivent être des contacts secs isolés, et leur action de sécurité
  gagnerait à être aussi câblée en dur, pas seulement logicielle.
- **Pilotage en boucle ouverte.** Le contrôleur commande une broche et *suppose* que le
  moteur a tourné. Les seuls retours sont les capteurs anti-bourrage. Pas de mesure de
  courant → impossible de détecter un moteur grippé ou un relais collé.

### 1.3 La pile logicielle

- **Stack en fin de vie.** Scala 2.11, Akka 2.4, **Spray** (mort, remplacé par Akka HTTP),
  sbt 0.13.8 : tout est EOL. `pi4j-core 1.2-SNAPSHOT` est un *snapshot* non publié → le
  build n'est même pas reproductible en l'état.
- **JVM sur Pi pour du contrôle.** Empreinte mémoire, démarrage lent, pauses GC.
- **Le modèle acteur, en revanche, est pertinent** pour ce domaine événementiel. Cette
  partie de la conception n'est pas à jeter — c'est le *socle d'exécution* qui est en
  cause, pas le paradigme.
- **Pas de machine à états explicite.** La logique de régulation est éparpillée entre
  `Boiler` et `EngineCyclesManager` ; il n'existe pas d'état `LOCKED`/`STOP` persistant ni
  de temporisation de verrouillage (allumage raté, chute prolongée < 30 °C) comme le
  faisait le MONEX.
- **Aucun test sur la logique de régulation** — seule la sonde est couverte.

---

## 2. Pistes d'évolution

### 2.1 Architecture cible : séparer contrôle et supervision

C'est la décision structurante. Plutôt que de remplacer le Pi, on **scinde les deux
rôles** :

```
┌─────────────────────────┐         ┌──────────────────────────────┐
│   Cœur de régulation     │         │      Superviseur             │
│   (micro-contrôleur)     │◀──────▶│   (Raspberry Pi ou autre SBC) │
│                          │ série / │                              │
│ - lecture sonde          │  MQTT   │ - API REST                   │
│ - machine à états        │         │ - journalisation Elastic     │
│ - pilotage vis/ventilo   │         │ - alertes SMS / e-mail       │
│ - seuils & repli sûr     │         │ - dashboards / UI            │
│ - watchdog               │         │ - réglages utilisateur       │
└─────────────────────────┘         └──────────────────────────────┘
          │
          ▼
  chaîne de sécurité MATÉRIELLE (thermostat 95 °C, protections
  thermiques moteurs) — en série sur la puissance, indépendante de tout logiciel
```

Bénéfices : le cœur critique devient déterministe, testable et redémarre en < 1 s ; le Pi
peut planter, être rebooté ou mis à jour sans jamais interrompre la régulation ; la
télémétrie reste confortable à développer.

### 2.2 Choix du micro-contrôleur pour le cœur de régulation

| Option | Avantages | Inconvénients | Pour qui |
|--------|-----------|---------------|----------|
| **ESP32** | dual-core, WiFi/BT intégré, watchdog matériel, peu cher, peut porter sa propre télémétrie MQTT | pile WiFi parfois capricieuse, pas « industriel » | bon compromis, communauté énorme sur ce cas d'usage |
| **Arduino AVR / RP2040 / STM32** | très déterministe, robuste, watchdog matériel, simple | pas de réseau → nécessite le Pi comme compagnon | si on veut le cœur le plus simple et béton |
| **Automate (PLC) — LOGO!, OpenPLC sur carte durcie** | conçu pour ça (sorties relais, entrées sûres), souvent certifié | plus cher, moins « bidouillable » | si l'objectif est de faire « comme un vrai » régulateur |

**Recommandation : ESP32 pour le cœur de régulation**, en gardant le Pi comme superviseur
si la télémétrie riche est souhaitée. L'ESP32 a un watchdog matériel, démarre vite, et son
double cœur permet d'isoler la boucle de contrôle de la communication.

Quel que soit le choix : **le watchdog matériel doit être armé**, et au reset le
contrôleur doit repartir dans un **état sûr connu** (vis à l'arrêt, circulateur géré selon
température).

### 2.3 Interfaçage moteurs propre

- **Étage de commande opto-isolé** systématiquement entre logique et puissance.
- **Pour les vis (commutation fréquente) : SSR à passage par zéro, ou contacteurs.**
  - SSR : aucune usure mécanique, idéal pour le cycle court — mais dimensionner pour charge
    inductive (déclassement) et prévoir la dissipation thermique + snubber.
  - Contacteur : standard pour moteur (catégorie AC-3), durée de vie en millions de
    manœuvres — solution la plus « industrielle ».
- **Snubber RC** sur les contacts / sortie, pour les charges inductives.
- **Dimensionnement au courant d'appel**, pas seulement au courant nominal.
- **Protection par circuit** : un fusible / disjoncteur par sortie (la notice montre un
  C6A).
- **Câbler correctement les protections thermiques moteurs** (contacts secs, isolés,
  jamais de 230 V dessus) — idéalement aussi dans une coupure matérielle.
- **Retour d'information** : à terme, une mesure de courant par moteur (transformateur de
  courant / capteur à effet Hall) permet de passer en **boucle fermée** — savoir qu'un
  moteur a *réellement* tourné, détecter un relais collé ou un moteur grippé.
- **CEM** : séparer physiquement câblage logique et puissance, câbles torsadés/blindés,
  ferrites.

### 2.4 Langage / portage du code

La question du langage **découle de la décision d'architecture** (§2.1) — elle n'a pas de
réponse isolée.

- **Ne pas porter pour porter.** Une fois ses bugs corrigés, la logique Scala *fonctionne*.
  Un portage iso-fonctionnel sur Python ou Go ne corrige aucun des problèmes de fond
  (carte SD, watchdog, déterminisme) : ce serait un déplacement latéral.
- **Si on fait la scission (recommandé) :**
  - **Cœur de régulation sur MCU** : à réécrire de toute façon. En **C/C++** (Arduino /
    ESP-IDF) ou en **Rust** (de plus en plus mûr sur ESP32, et apporte la sûreté mémoire).
    La logique de contrôle est petite — quelques machines à états — donc le portage est
    modeste.
  - **Superviseur sur le Pi** : le langage importe peu. **Python** (FastAPI, `paho-mqtt`)
    ou **Go** (binaire statique unique, démarrage instantané, cross-compilation ARM
    triviale) seraient bien moins coûteux à maintenir que la pile Scala/Spray/Akka morte.
- **Étape incrémentale utile dès maintenant**, sans rien casser : extraire la logique de
  régulation dans une **machine à états explicite** (`Stopped → Igniting → Running →
  Slowing → Locked → Overheating`), avec les sécurités (sonde HS, verrouillage, surchauffe)
  comme transitions de premier ordre, et la couvrir de tests. La garder en Scala pour
  l'instant. Cette machine à états devient alors *l'artefact* qui migrera plus tard sur le
  MCU — un portage propre et quasi mécanique.

---

## 3. Recommandations priorisées

### Court terme — durcir l'existant (sans changer le matériel)
1. **Vérifier / installer la chaîne de sécurité matérielle** : thermostat 95 °C et
   protections thermiques moteurs en coupure série, indépendants du Pi. *Non négociable.*
2. Activer le **watchdog matériel** du Pi.
3. Sortir les logs de la carte SD (tmpfs / rootfs en lecture seule) et repasser
   `logback.xml` en `info`.
4. Rendre le **build reproductible** : figer `pi4j`, monter de version `sbt`.
5. Externaliser / révoquer les **secrets en clair** (`SmsAgent`, `MailAgent`).
6. Rendre **configurables** l'hôte/port REST et l'adresse Elasticsearch.

### Moyen terme — fiabiliser la logique et la puissance
7. Extraire la régulation dans une **machine à états explicite** + tests (§2.4).
8. Refaire l'**étage de puissance** : opto-isolation, SSR/contacteurs pour les vis,
   snubbers, fusibles par sortie (§2.3).

### Cible — scinder contrôle et supervision
9. Porter le cœur de régulation sur un **ESP32** (ou MCU équivalent) avec watchdog et état
   de repli sûr.
10. Conserver le Pi en **superviseur** (REST, Elastic, alertes), réécrit en Python ou Go ;
    communication MCU ↔ Pi par série ou MQTT.
11. À terme, **boucle fermée** : mesure de courant par moteur pour confirmer l'action et
    détecter les défauts.
