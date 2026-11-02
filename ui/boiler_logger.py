#!/usr/bin/env python3
"""Journalisation de la chaudière vers Powerview2 (format PV2_CSV / PV2_EVENTS).

Service indépendant de la régulation Scala : il observe la sonde 1-Wire et
les GPIO (5 moteurs + 2 contacts de bourrage) chaque seconde, agrège par
minute, détecte les événements par transition, écrit des CSV et les dépose
par SFTP sur le VPS Powerview2 toutes les PUSH_INTERVAL_S secondes.

Mesures (une ligne par minute et par voie, préfixe CHANNEL_PREFIX) :
  temp (C)            température moyenne de l'eau sur la minute
  tremie, bruleur, ventilo, circulateur, laddomat (s)
                      secondes de marche de chaque organe dans la minute
  pellets (g)         granulés estimés = secondes trémie × FEED_RATE_G_PER_S
  power (W)           puissance thermique utile moyenne sur la minute
  energy (Wh)         énergie thermique utile de la minute
  pellets_total (kg), energy_total (Wh)
                      cumuls depuis la mise en service du logger (index)
  state (code)        1 = combustion (ventilateur en marche ≥ 30 s), 0 sinon
  event (code)        code du dernier événement de la minute, 0 sinon

Modèle énergétique (notice MONEX, CEDRA ECO 24) :
  energy_Wh = pellets_g × PCI_KWH_PER_KG × EFFICIENCY
  power_W   = energy_Wh × 60   (moyenne sur une minute)

Événements (fichier *_events.csv, catégorie EVENT_CATEGORY) : voir EVENTS.

Contraintes : Python 3.5 (Raspbian Stretch) — pas de f-strings. Dépôt SFTP
par curl (support sftp de libssh2), identifiants dans un fichier netrc hors
git. Déploiement : /home/pi/ui/ + service systemd boiler-logger.
"""
import csv
import json
import logging
import os
import shlex
import subprocess
import threading
import time
from datetime import datetime, timezone

log = logging.getLogger("boiler-logger")

CONF_PATH = "/etc/boiler-logger.conf"
W1_SLAVE = "/sys/bus/w1/devices/28-021581cc38ff/w1_slave"

DEFAULTS = {
    "SFTP_URL": "sftp://217.160.116.85:2022/chaudiere/pi-boiler/",
    "NETRC_FILE": "/etc/boiler-logger.netrc",
    "SPOOL_DIR": "/var/lib/boiler-logger",
    "CHANNEL_PREFIX": "boiler",
    "EVENT_CATEGORY": "chaudiere",
    "FEED_RATE_G_PER_S": "8.7",
    "PCI_KWH_PER_KG": "5.0",
    "EFFICIENCY": "0.85",
    "CONSIGNE_C": "75",
    "PUSH_INTERVAL_S": "300",
    "SAMPLE_PERIOD_S": "1",
    "SENSOR_FAILURE_THRESHOLD": "5",
}

# Organes lus sur les GPIO (numérotation wiringPi, identique à EngineDriver et
# pi_bridge) : sorties actives au niveau bas (0 = en marche).
ENGINE_PINS = {"tremie": 2, "bruleur": 0, "ventilo": 3, "circulateur": 4, "laddomat": 1}
# Contacts de bourrage (entrées, pull-up) : 1 = protection thermique déclenchée.
JAM_PINS = {"bourrage_tremie": 5, "bourrage_bruleur": 6}
ENGINES = ("tremie", "bruleur", "ventilo", "circulateur", "laddomat")

# Codes d'événement (voie `event`) et libellés d'annotation.
EVENTS = {
    "logger_start": (1, "Démarrage du logger"),
    "combustion_on": (2, "Démarrage des cycles de combustion"),
    "combustion_off": (3, "Arrêt des cycles de combustion"),
    "amorcage": (4, "Amorçage du brûleur"),
    "circulateur_on": (5, "Circulateur en marche"),
    "circulateur_off": (6, "Circulateur à l'arrêt"),
    "laddomat_on": (7, "Laddomat en marche"),
    "laddomat_off": (8, "Laddomat à l'arrêt"),
    "seuil_30_down": (10, "Passage sous 30 °C (verrouillage)"),
    "seuil_40_up": (11, "Passage au-dessus de 40 °C"),
    "seuil_40_down": (12, "Passage sous 40 °C"),
    "seuil_60_up": (13, "Passage au-dessus de 60 °C"),
    "consigne_up": (14, "Consigne atteinte (ralenti)"),
    "consigne_down": (15, "Retour sous consigne - 3 °C (reprise)"),
    "surchauffe": (16, "Surchauffe (90 °C)"),
    "bourrage_tremie": (20, "Bourrage vis trémie"),
    "bourrage_bruleur": (21, "Bourrage vis brûleur"),
    "sonde_muette": (30, "Sonde de température muette"),
    "sonde_ok": (31, "Sonde de température rétablie"),
    "gpio_muet": (40, "Lecture des GPIO en échec"),
}

COMBUSTION_DEBOUNCE_S = 5
AMORCAGE_MIN_S = 10
STATE_RUNNING_MIN_S = 30


# --------------------------------------------------------------------------
# Configuration
# --------------------------------------------------------------------------
def load_conf(path=CONF_PATH):
    """Lit un fichier KEY=VALUE (syntaxe shell, commentaires #) ; défauts sinon."""
    conf = dict(DEFAULTS)
    try:
        with open(path) as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                parts = shlex.split(value)
                conf[key.strip()] = parts[0] if parts else ""
    except OSError:
        log.warning("configuration %s absente, valeurs par défaut", path)
    return conf


def iso_utc(epoch):
    return datetime.fromtimestamp(int(epoch), timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# --------------------------------------------------------------------------
# Lecture du matériel
# --------------------------------------------------------------------------
def parse_gpio_readall(text):
    """`gpio readall` → {wPi: valeur}. Chaque ligne de données porte deux
    broches (colonnes de gauche et de droite)."""
    values = {}
    for line in text.splitlines():
        if not line.startswith(" |") and not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        # gauche : BCM, wPi, Name, Mode, V, Physique ; cellule vide (le « || ») ;
        # droite : Physique, V, Mode, Name, wPi, BCM
        if len(cells) >= 13:
            for wpi, v in ((cells[1], cells[4]), (cells[11], cells[8])):
                if wpi.isdigit() and v in ("0", "1"):
                    values[int(wpi)] = int(v)
    return values


def read_gpio():
    """{organe: True si en marche, contact: True si déclenché} ou None si échec."""
    try:
        out = subprocess.check_output(["gpio", "readall"], timeout=3).decode("ascii", "replace")
    except (subprocess.SubprocessError, OSError):
        return None
    values = parse_gpio_readall(out)
    try:
        states = {name: values[pin] == 0 for name, pin in ENGINE_PINS.items()}
        states.update({name: values[pin] == 1 for name, pin in JAM_PINS.items()})
    except KeyError:
        return None
    return states


def read_temperature(path=W1_SLAVE):
    """Température en °C (float) ou None. Bloque ~750 ms (conversion 1-Wire)."""
    try:
        with open(path) as f:
            lines = f.read().splitlines()
        if len(lines) >= 2 and "YES" in lines[0] and "t=" in lines[1]:
            return int(lines[1].split("t=")[1]) / 1000.0
    except (OSError, ValueError):
        pass
    return None


class TemperatureReader(threading.Thread):
    """Lit la sonde toutes les 2 s dans un fil dédié (la lecture bloque)."""

    def __init__(self, path=W1_SLAVE, period=2.0):
        super().__init__(daemon=True)
        self.path = path
        self.period = period
        self.value = None
        self.ok = False
        self.failures = 0

    def run(self):
        while True:
            value = read_temperature(self.path)
            if value is None:
                self.failures += 1
                self.ok = False
            else:
                self.failures = 0
                self.ok = True
                self.value = value
            time.sleep(self.period)


# --------------------------------------------------------------------------
# Agrégation par minute et modèle énergétique
# --------------------------------------------------------------------------
class EnergyModel:
    def __init__(self, feed_rate_g_per_s, pci_kwh_per_kg, efficiency):
        self.feed_rate = float(feed_rate_g_per_s)
        self.pci = float(pci_kwh_per_kg)
        self.efficiency = float(efficiency)

    def pellets_g(self, tremie_seconds):
        return tremie_seconds * self.feed_rate

    def energy_wh(self, pellets_g):
        # g × kWh/kg = Wh (le facteur 1000 des g→kg et des kWh→Wh se compense)
        return pellets_g * self.pci * self.efficiency


class MinuteAggregator:
    """Cumule les échantillons d'une minute et produit les lignes de mesures."""

    def __init__(self, model, prefix, sample_period):
        self.model = model
        self.prefix = prefix
        self.sample_period = float(sample_period)
        self.pellets_total_g = 0.0
        self.energy_total_wh = 0.0
        self.reset()

    def reset(self):
        self.temps = []
        self.run_s = {name: 0.0 for name in ENGINES}
        self.event_code = 0
        self.nb_samples = 0

    def add_sample(self, gpio, temperature):
        self.nb_samples += 1
        if temperature is not None:
            self.temps.append(temperature)
        if gpio:
            for name in ENGINES:
                if gpio.get(name):
                    self.run_s[name] += self.sample_period

    def note_event(self, code):
        self.event_code = code

    def flush(self, minute_epoch):
        """Renvoie les lignes (ts, channel, value, unit) de la minute écoulée."""
        ts = iso_utc(minute_epoch)
        pellets = self.model.pellets_g(self.run_s["tremie"])
        energy = self.model.energy_wh(pellets)
        self.pellets_total_g += pellets
        self.energy_total_wh += energy
        rows = []

        def add(channel, value, unit):
            rows.append((ts, self.prefix + "_" + channel, value, unit))

        if self.temps:
            add("temp", round(sum(self.temps) / len(self.temps), 2), "C")
        for name in ENGINES:
            add(name, round(self.run_s[name], 1), "s")
        add("pellets", round(pellets, 1), "g")
        add("power", round(energy * 60.0, 1), "W")
        add("energy", round(energy, 2), "Wh")
        add("pellets_total", round(self.pellets_total_g / 1000.0, 3), "kg")
        add("energy_total", round(self.energy_total_wh, 1), "Wh")
        add("state", 1 if self.run_s["ventilo"] >= STATE_RUNNING_MIN_S else 0, "code")
        add("event", self.event_code, "code")
        self.reset()
        return rows


# --------------------------------------------------------------------------
# Détection des événements
# --------------------------------------------------------------------------
class EventDetector:
    """Émet les événements par transition entre deux échantillons."""

    def __init__(self, consigne, sensor_failure_threshold=5):
        self.consigne = float(consigne)
        self.sensor_failure_threshold = int(sensor_failure_threshold)
        self.prev_gpio = None
        self.prev_temp = None
        self.ventilo_state = None  # état débouncé du ventilateur
        self.fan_pending_since = None  # début d'un changement non encore confirmé
        self.augers_without_fan_since = None
        self.amorcage_reported = False
        self.over_consigne = False
        self.surchauffe_since = None
        self.sensor_mute = False
        self.gpio_mute = False

    def _event(self, now, name, ts_end=None, description=None):
        code, title = EVENTS[name]
        return {
            "code": code,
            "name": name,
            "ts_start": iso_utc(now),
            "ts_end": iso_utc(ts_end) if ts_end is not None else "",
            "title": title,
            "description": description or "",
        }

    def update(self, now, gpio, temperature, sensor_failures=0):
        events = []
        # --- disponibilité des GPIO
        if gpio is None:
            if not self.gpio_mute:
                self.gpio_mute = True
                events.append(self._event(now, "gpio_muet"))
            return events
        self.gpio_mute = False
        prev = self.prev_gpio

        # --- combustion : ventilateur débouncé
        fan = bool(gpio["ventilo"])
        if self.ventilo_state is None:
            self.ventilo_state = fan
        elif fan != self.ventilo_state:
            if self.fan_pending_since is None:
                self.fan_pending_since = now
            elif now - self.fan_pending_since >= COMBUSTION_DEBOUNCE_S:
                self.ventilo_state = fan
                self.fan_pending_since = None
                events.append(self._event(now, "combustion_on" if fan else "combustion_off"))
        else:
            self.fan_pending_since = None

        # --- amorçage : vis en marche sans ventilateur pendant AMORCAGE_MIN_S
        augers = gpio["tremie"] and gpio["bruleur"] and not fan
        if augers:
            if self.augers_without_fan_since is None:
                self.augers_without_fan_since = now
            elif not self.amorcage_reported and now - self.augers_without_fan_since >= AMORCAGE_MIN_S:
                self.amorcage_reported = True
                events.append(self._event(self.augers_without_fan_since, "amorcage"))
        else:
            self.augers_without_fan_since = None
            self.amorcage_reported = False

        # --- pompes et contacts de bourrage : transitions simples
        if prev is not None:
            for name in ("circulateur", "laddomat"):
                if gpio[name] != prev[name]:
                    events.append(self._event(now, name + ("_on" if gpio[name] else "_off")))
            for name in ("bourrage_tremie", "bourrage_bruleur"):
                if gpio[name] and not prev[name]:
                    events.append(self._event(now, name))
        self.prev_gpio = dict(gpio)

        # --- sonde
        if sensor_failures >= self.sensor_failure_threshold and not self.sensor_mute:
            self.sensor_mute = True
            events.append(self._event(now, "sonde_muette", description="%d lectures en échec" % sensor_failures))
        elif sensor_failures == 0 and self.sensor_mute:
            self.sensor_mute = False
            events.append(self._event(now, "sonde_ok"))

        # --- seuils de température (mêmes franchissements que la régulation)
        if temperature is not None:
            desc = "%.1f °C" % temperature
            last = self.prev_temp
            if last is not None:
                if last > 30 and temperature <= 30:
                    events.append(self._event(now, "seuil_30_down", description=desc))
                if last < 40 <= temperature:
                    events.append(self._event(now, "seuil_40_up", description=desc))
                if last > 40 >= temperature:
                    events.append(self._event(now, "seuil_40_down", description=desc))
                if last < 60 <= temperature:
                    events.append(self._event(now, "seuil_60_up", description=desc))
                if temperature >= self.consigne and last < self.consigne:
                    self.over_consigne = True
                    events.append(self._event(now, "consigne_up", description=desc))
                if self.over_consigne and temperature <= self.consigne - 3:
                    self.over_consigne = False
                    events.append(self._event(now, "consigne_down", description=desc))
            if temperature >= 90 and self.surchauffe_since is None:
                self.surchauffe_since = now
                self.surchauffe_max = temperature
            elif temperature >= 90 and self.surchauffe_since is not None:
                self.surchauffe_max = max(self.surchauffe_max, temperature)
            elif temperature < 90 and self.surchauffe_since is not None:
                events.append(
                    self._event(
                        self.surchauffe_since,
                        "surchauffe",
                        ts_end=now,
                        description="maximum %.1f °C" % self.surchauffe_max,
                    )
                )
                self.surchauffe_since = None
            self.prev_temp = temperature
        return events


# --------------------------------------------------------------------------
# Spool local et dépôt SFTP
# --------------------------------------------------------------------------
MEASURES_HEADER = "PV2_CSV_V001\ntimestamp,channel_id,value,unit\n"
EVENTS_HEADER = "PV2_EVENTS_V001\nts_start,ts_end,category,title,description\n"


class Spool:
    def __init__(self, spool_dir, category):
        self.dir = spool_dir
        self.outbox = os.path.join(spool_dir, "outbox")
        self.category = category
        self.pending_measures = os.path.join(spool_dir, "pending.csv")
        self.pending_events = os.path.join(spool_dir, "pending_events.csv")
        self.state_file = os.path.join(spool_dir, "state.json")
        os.makedirs(self.outbox, exist_ok=True)

    def _append(self, path, header, rows):
        new = not os.path.exists(path)
        with open(path, "a", newline="") as f:
            if new:
                f.write(header)
            csv.writer(f, lineterminator="\n").writerows(rows)

    def add_measures(self, rows):
        if rows:
            self._append(self.pending_measures, MEASURES_HEADER, rows)

    def add_events(self, events):
        rows = [(e["ts_start"], e["ts_end"], self.category, e["title"], e["description"]) for e in events]
        if rows:
            self._append(self.pending_events, EVENTS_HEADER, rows)

    def rotate(self, now):
        """Déplace les fichiers en attente dans l'outbox, nommés par horodatage."""
        stamp = datetime.fromtimestamp(int(now), timezone.utc).strftime("%Y%m%d_%H%M%S")
        for src, suffix in ((self.pending_measures, ".csv"), (self.pending_events, "_events.csv")):
            if os.path.exists(src):
                os.replace(src, os.path.join(self.outbox, stamp + suffix))

    def outbox_files(self):
        return sorted(os.path.join(self.outbox, n) for n in os.listdir(self.outbox) if n.endswith(".csv"))

    def load_state(self):
        try:
            with open(self.state_file) as f:
                return json.load(f)
        except (OSError, ValueError):
            return {}

    def save_state(self, state):
        tmp = self.state_file + ".tmp"
        with open(tmp, "w") as f:
            json.dump(state, f)
        os.replace(tmp, self.state_file)


def sftp_upload(path, sftp_url, netrc_file, timeout=60):
    """Dépose `path` sous `sftp_url` (dossier, slash final) via curl. True si OK."""
    url = sftp_url.rstrip("/") + "/" + os.path.basename(path)
    cmd = ["curl", "-sS", "--netrc-file", netrc_file, "--ftp-create-dirs", "-m", str(timeout), "-T", path, url]
    try:
        subprocess.check_output(cmd, stderr=subprocess.STDOUT, timeout=timeout + 10)
        return True
    except subprocess.CalledProcessError as exc:
        log.warning("dépôt SFTP échoué (%s) : %s", os.path.basename(path), exc.output.decode("utf-8", "replace").strip())
    except (subprocess.SubprocessError, OSError) as exc:
        log.warning("dépôt SFTP échoué (%s) : %s", os.path.basename(path), exc)
    return False


def push(spool, now, conf):
    """Rotation puis dépôt de tout l'outbox (plus ancien d'abord). Les
    fichiers déposés sont supprimés, les autres attendent le prochain tour."""
    spool.rotate(now)
    for path in spool.outbox_files():
        if sftp_upload(path, conf["SFTP_URL"], conf["NETRC_FILE"]):
            os.remove(path)
            log.info("déposé %s", os.path.basename(path))
        else:
            break


# --------------------------------------------------------------------------
# Boucle principale
# --------------------------------------------------------------------------
def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    conf = load_conf()
    sample_period = float(conf["SAMPLE_PERIOD_S"])
    push_interval = int(conf["PUSH_INTERVAL_S"])
    spool = Spool(conf["SPOOL_DIR"], conf["EVENT_CATEGORY"])
    model = EnergyModel(conf["FEED_RATE_G_PER_S"], conf["PCI_KWH_PER_KG"], conf["EFFICIENCY"])
    aggregator = MinuteAggregator(model, conf["CHANNEL_PREFIX"], sample_period)
    state = spool.load_state()
    aggregator.pellets_total_g = float(state.get("pellets_total_g", 0.0))
    aggregator.energy_total_wh = float(state.get("energy_total_wh", 0.0))
    detector = EventDetector(conf["CONSIGNE_C"], conf["SENSOR_FAILURE_THRESHOLD"])
    sensor = TemperatureReader()
    sensor.start()

    now = time.time()
    spool.add_events([detector._event(now, "logger_start")])
    aggregator.note_event(EVENTS["logger_start"][0])
    current_minute = int(now // 60)
    last_push = now
    log.info("démarré : dépôt vers %s toutes les %d s, débit vis %s g/s", conf["SFTP_URL"], push_interval, conf["FEED_RATE_G_PER_S"])

    while True:
        loop_start = time.time()
        now = loop_start
        minute = int(now // 60)
        if minute != current_minute:
            spool.add_measures(aggregator.flush(current_minute * 60))
            current_minute = minute
        gpio = read_gpio()
        temperature = sensor.value if sensor.ok else None
        events = detector.update(now, gpio, temperature, sensor.failures)
        aggregator.add_sample(gpio, temperature)
        if events:
            for e in events:
                log.info("événement %s", e["name"])
                aggregator.note_event(e["code"])
            spool.add_events(events)
        if now - last_push >= push_interval:
            last_push = now
            push(spool, now, conf)
            spool.save_state({"pellets_total_g": aggregator.pellets_total_g, "energy_total_wh": aggregator.energy_total_wh})
        time.sleep(max(0.0, sample_period - (time.time() - loop_start)))


if __name__ == "__main__":
    main()
