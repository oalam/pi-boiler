# Changelog

Toutes les évolutions notables de `regulation` (pi-boiler) sont consignées ici.
Format : [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/).

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
