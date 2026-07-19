# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`pi-boiler` (sbt project name `regulation`) is the control software for a wood-pellet
boiler ("chaudière à granulés"), running on a Raspberry Pi. It reads a 1-Wire temperature
probe, drives the boiler's motors via GPIO relays, exposes a small REST API for manual
control, and logs events to Elasticsearch with SMS/email alerting. Code comments and
domain terms are in French.

Scala 2.11.7, sbt 0.13.18, Akka actors, Spray HTTP, pi4j 1.2 for GPIO. Building requires a
JDK 8 (Scala 2.11 does not compile on recent JDKs); all dependencies resolve from Maven Central.

## Commands

```bash
sbt clean package              # compile + jar
sbt assemblyPackageDependency  # build the fat deps jar (sbt-assembly)
sbt test                       # run all tests (scalatest)
sbt "testOnly org.oalam.regulation.TemperatureSensorTest"   # single test
```

Run on the Pi: two systemd units, enabled at boot (jars deployed in `/home/pi/`):
```bash
sudo systemctl status boiler      # regulation (java api.Boot, needs root for GPIO)
sudo systemctl status boiler-ui   # web UI bridge (python3 ui/pi_bridge.py, port 8080)
```
The Pi (`boiler`, spare Pi 2 since 2026) has static IP 192.168.1.12 set in its
`/etc/dhcpcd.conf` (+ `net.ifnames=0` in cmdline.txt so the interface stays `eth0`).
The mobile web UI (`ui/index.html`, shared with the phase-2 ESP32) is served at
`http://192.168.1.12:8080` by `ui/pi_bridge.py`, which implements `/api/status`
(1-Wire sysfs + `gpio read`) and proxies `/api/command` to the Scala API.

## Two entry points

- **`api.Boot`** — production. Starts the full actor system, binds the Spray REST service,
  and uses real GPIO (`GPIOEngineDriver`) and the real 1-Wire sensor path
  (`/sys/bus/w1/devices`). Note the bind host is hardcoded (`192.168.1.12:9000`).
- **`app.Main`** — local simulation. Same actors but no REST service, and it *writes* fake
  temperatures into `src/main/resources/.../w1_slave` on a timer so the regulation logic
  can be exercised on a dev machine without hardware.

## Architecture

Everything is Akka actors under one `ActorSystem("pi-boiler")`. Actors are **siblings**
and address each other by relative path (`context.actorSelection("../boiler")`), so actor
names in `Boot`/`Main` are load-bearing — renaming one breaks the lookups.

Control flow:
1. **`TemperatureEventHandler`** polls `TemperatureSensor` every 2s. It tracks the last
   reading and emits *crossing* events (`TemperatureCrossThresholdUp/Down` at 30/40/60/90°,
   `TemperatureCrossConsigne*` around the setpoint) — events fire on transitions, not levels.
2. **`Boiler`** is the decision actor. It maps temperature events to commands: start/stop
   burning cycles, slow-motion cycles, overheating response (cut augers, run pump + valve).
   Holds the mutable `BoilerSettings`.
3. **`EngineCyclesManager`** turns high-level cycle commands into *timed* motor sequences
   using `scheduler.scheduleOnce`, and reschedules itself for the next cycle. All the
   `Cancellable` schedulers are torn down via `cleanupSchedulers()` on every new
   command/stop/jam.
4. **`GPIOEngineDriver`** is the only actor touching hardware. It provisions pi4j output
   pins for the 5 motors and input pins for the two jam sensors. **Pins are active-low**:
   `.low()` starts an engine, `.high()` stops it. Jam sensors (`tremieSecu`, `bruleurSecu`)
   push `BourrageTremie`/`BourrageBruleur` back into the system.
5. **`ReportHandler`** subscribes to all notable events: indexes them into Elasticsearch
   via `ElasticsearchAgent`, and on over/under-temperature fires `SmsAgent` + `MailAgent`.
6. **`RestServiceActor`** (Spray) exposes `GET /api/v0.1/action?type=...` —
   `load|start|stop|shutdown|set` — translating query params into messages to `boiler`.

The five physical engines (`BoilerEngine` enum): `Tremie` (hopper auger), `Bruleur`
(burner auger), `Ventilo` (fan), `Laddomat` (charge pump), `Vanne4V` (4-way valve).

Shared message types and the tuning constants (cycle durations, default setpoint) live in
`core/package.scala`.

## Gotchas

- `ElasticsearchAgent` connects to a hardcoded transport address (`192.168.1.67:9300`,
  cluster `boiler`). All ES writes are best-effort and swallow exceptions.
- `SmsAgent` shells out to `w3m` against the Free Mobile SMS API and currently contains a
  **hardcoded credential in the URL**; `MailAgent` has placeholder Gmail credentials.
  Treat these as secrets to externalize, not to copy.
- `TemperatureSensor.measure` maps the logical name `"temperatureEauSortie"` to the
  physical 1-Wire device id `28-021581cc38ff`; tests point `rootPath` at
  `src/test/resources`.
