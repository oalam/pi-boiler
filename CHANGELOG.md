# Changelog

Toutes les évolutions notables de `regulation` (pi-boiler) sont consignées ici.
Format : [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/).

## [Non publié]

### Ajouté

- Journalisation vers Powerview2 : `ui/boiler_logger.py` (service
  `boiler-logger`, `deploy/boiler-logger.service` et `.conf.sample`) observe
  sonde et GPIO chaque seconde, agrège par minute (température, secondes de
  marche des organes, granulés / puissance / énergie estimés, cumuls, état),
  détecte les événements par transition (combustion, amorçage, pompes, seuils,
  consigne, surchauffe, bourrages, sonde, GPIO) et dépose les CSV
  `PV2_CSV_V001` / `PV2_EVENTS_V001` par SFTP toutes les 5 min. Modèle
  énergétique CEDRA ECO 24 (PCI 5 kWh/kg, rendement 85 %, débit de vis à
  calibrer). Tests `tests/ui/test_boiler_logger.py`. Section dans le README et
  dans la page de fonctionnement de l'IHM.
- Page `ui/doc.html` « Fonctionnement et règles de régulation », servie par
  `ui/pi_bridge.py` sur `/doc.html` et liée depuis l'IHM : boutons, cycle de
  combustion, seuils de température, ralenti, surchauffe, verrouillage,
  bourrages, panne de sonde, réglages, supervision, points d'attention. Le jar
  de régulation est inchangé.

## [1.8.1] - 2026-10-31

### Corrigé

- Permutation des broches du circulateur (`Vanne4V`, désormais wPi 4) et du
  laddomat (`Laddomat`, désormais wPi 1) dans `GPIOEngineDriver` et dans
  `ENGINE_PINS` de `ui/pi_bridge.py`, pour suivre le câblage constaté après le
  déménagement de la chaudière : en mode « Démarrer », le laddomat tournait à la
  place du circulateur.

### Documentation

- Table d'affectation des broches (wiringPi / BCM / physique) dans le README.

## [1.8] - 2026-07-19

Remise en service 2026 : build sbt reconstitué (Maven Central, pi4j 1.2),
déploiement systemd, IHM mobile servie par `ui/pi_bridge.py`, supervision du Pi
(watchdogs, ntfy, heartbeat).
