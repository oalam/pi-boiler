"""Tests du logger chaudière → Powerview2 (ui/boiler_logger.py).

Lancer : python3 -m pytest tests/ui            (tout)
         python3 -m pytest tests/ui -k energy  (sélection)
Aucun matériel requis : GPIO et sonde sont simulés.
"""
import csv
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "ui"))

import boiler_logger as bl  # noqa: E402

READALL = """\
 +-----+-----+---------+------+---+---Pi 2---+---+------+---------+-----+-----+
 | BCM | wPi |   Name  | Mode | V | Physical | V | Mode | Name    | wPi | BCM |
 +-----+-----+---------+------+---+----++----+---+------+---------+-----+-----+
 |     |     |    3.3v |      |   |  1 || 2  |   |      | 5v      |     |     |
 |  17 |   0 | GPIO. 0 |  OUT | 0 | 11 || 12 | 1 | OUT  | GPIO. 1 | 1   | 18  |
 |  27 |   2 | GPIO. 2 |  OUT | 0 | 13 || 14 |   |      | 0v      |     |     |
 |  22 |   3 | GPIO. 3 |  OUT | 0 | 15 || 16 | 0 | OUT  | GPIO. 4 | 4   | 23  |
 |     |     |    3.3v |      |   | 17 || 18 | 0 | IN   | GPIO. 5 | 5   | 24  |
 |  10 |  12 |    MOSI |   IN | 0 | 19 || 20 |   |      | 0v      |     |     |
 |   9 |  13 |    MISO |   IN | 0 | 21 || 22 | 1 | IN   | GPIO. 6 | 6   | 25  |
 +-----+-----+---------+------+---+----++----+---+------+---------+-----+-----+
"""


def gpio(tremie=False, bruleur=False, ventilo=False, circulateur=False, laddomat=False, jam_t=False, jam_b=False):
    return {
        "tremie": tremie,
        "bruleur": bruleur,
        "ventilo": ventilo,
        "circulateur": circulateur,
        "laddomat": laddomat,
        "bourrage_tremie": jam_t,
        "bourrage_bruleur": jam_b,
    }


def test_parse_gpio_readall_reads_both_columns():
    values = bl.parse_gpio_readall(READALL)
    assert values[0] == 0 and values[2] == 0 and values[3] == 0  # brûleur, trémie, ventilo en marche
    assert values[1] == 1 and values[4] == 0  # laddomat arrêté, circulateur en marche
    assert values[5] == 0 and values[6] == 1  # contact brûleur déclenché
    assert values[12] == 0 and values[13] == 0


def test_energy_model_matches_notice_arithmetic():
    model = bl.EnergyModel(8.7, 5.0, 0.85)
    # 3 s de trémie par cycle de 23 s : 26,1 g → 110,9 Wh utiles
    pellets = model.pellets_g(3.0)
    assert pellets == pytest.approx(26.1)
    assert model.energy_wh(pellets) == pytest.approx(26.1 * 5.0 * 0.85)
    # une minute à 2,6 cycles ≈ 7,8 s de trémie → ~288 Wh → ~17,3 kW
    assert model.energy_wh(model.pellets_g(7.8)) * 60 == pytest.approx(17306, rel=1e-3)


def test_minute_aggregator_rows_and_totals():
    agg = bl.MinuteAggregator(bl.EnergyModel(8.7, 5.0, 0.85), "boiler", 1.0)
    for i in range(60):
        on = i % 23 < 3  # trémie 3 s par cycle de 23 s
        agg.add_sample(gpio(tremie=on, bruleur=on, ventilo=True, circulateur=True), 60.0 + i * 0.01)
    agg.note_event(13)
    rows = agg.flush(1_793_628_000)  # 2026-11-02T14:00:00Z
    by_channel = {r[1]: (r[2], r[3]) for r in rows}
    assert rows[0][0] == "2026-11-02T14:00:00Z"
    assert by_channel["boiler_temp"] == (pytest.approx(60.3, abs=0.01), "C")
    assert by_channel["boiler_tremie"] == (9.0, "s")  # 3 cycles complets (0, 23, 46)
    assert by_channel["boiler_ventilo"] == (60.0, "s")
    assert by_channel["boiler_circulateur"] == (60.0, "s")
    assert by_channel["boiler_laddomat"] == (0.0, "s")
    assert by_channel["boiler_pellets"] == (pytest.approx(78.3), "g")
    assert by_channel["boiler_energy"] == (pytest.approx(78.3 * 5 * 0.85, abs=0.01), "Wh")
    assert by_channel["boiler_power"] == (pytest.approx(78.3 * 5 * 0.85 * 60, abs=0.1), "W")
    assert by_channel["boiler_pellets_total"] == (pytest.approx(0.078, abs=1e-3), "kg")
    assert by_channel["boiler_state"] == (1, "code")
    assert by_channel["boiler_event"] == (13, "code")
    # la minute suivante repart de zéro mais les cumuls restent
    rows2 = agg.flush(1_793_628_060)
    by2 = {r[1]: r[2] for r in rows2}
    assert by2["boiler_tremie"] == 0.0 and by2["boiler_event"] == 0 and by2["boiler_state"] == 0
    assert by2["boiler_pellets_total"] == pytest.approx(0.078, abs=1e-3)
    assert "boiler_temp" not in by2  # aucune lecture de sonde dans la minute


def names(events):
    return [e["name"] for e in events]


def test_detector_combustion_debounce_and_pumps():
    d = bl.EventDetector(75)
    assert d.update(0, gpio(), 20.0) == []
    # ventilateur démarre : confirmé après 5 s
    assert names(d.update(1, gpio(ventilo=True), 20.0)) == []
    assert names(d.update(3, gpio(ventilo=True), 20.0)) == []
    assert names(d.update(6, gpio(ventilo=True), 20.0)) == ["combustion_on"]
    # micro-coupure de 2 s : pas d'événement
    d.update(10, gpio(), 20.0)
    assert names(d.update(12, gpio(ventilo=True), 20.0)) == []
    assert names(d.update(20, gpio(ventilo=True), 20.0)) == []
    # pompes : transitions immédiates
    assert names(d.update(21, gpio(ventilo=True, circulateur=True), 20.0)) == ["circulateur_on"]
    assert names(d.update(22, gpio(ventilo=True, circulateur=True, laddomat=True), 20.0)) == ["laddomat_on"]
    assert names(d.update(23, gpio(ventilo=True, laddomat=True), 20.0)) == ["circulateur_off"]
    # arrêt du ventilateur confirmé après 5 s
    d.update(30, gpio(laddomat=True), 20.0)
    assert names(d.update(36, gpio(laddomat=True), 20.0)) == ["combustion_off"]


def test_detector_thresholds_consigne_and_surchauffe_range():
    d = bl.EventDetector(75)
    d.update(0, gpio(), 25.0)
    assert names(d.update(1, gpio(), 41.0)) == ["seuil_40_up"]
    assert names(d.update(2, gpio(), 61.0)) == ["seuil_60_up"]
    assert names(d.update(3, gpio(), 75.5)) == ["consigne_up"]
    assert names(d.update(4, gpio(), 73.0)) == []  # pas encore consigne − 3
    assert names(d.update(5, gpio(), 72.0)) == ["consigne_down"]
    assert names(d.update(6, gpio(), 91.0)) == ["consigne_up"]  # surchauffe en cours, plage ouverte
    assert names(d.update(7, gpio(), 92.4)) == []
    ev = d.update(8, gpio(), 88.0)
    assert names(ev) == ["surchauffe"]
    assert ev[0]["ts_start"] == bl.iso_utc(6) and ev[0]["ts_end"] == bl.iso_utc(8)
    assert ev[0]["description"] == "maximum 92.4 °C"
    assert names(d.update(9, gpio(), 39.0)) == ["seuil_40_down", "consigne_down"]
    assert names(d.update(10, gpio(), 29.0)) == ["seuil_30_down"]


def test_detector_amorcage_jams_and_sensor():
    d = bl.EventDetector(75)
    d.update(0, gpio(), None)
    # vis sans ventilateur pendant 10 s = amorçage, daté du début
    for t in range(1, 11):
        ev = d.update(t, gpio(tremie=True, bruleur=True), None)
        assert ev == [] or t == 11
    ev = d.update(11, gpio(tremie=True, bruleur=True), None)
    assert names(ev) == ["amorcage"] and ev[0]["ts_start"] == bl.iso_utc(1)
    assert d.update(12, gpio(tremie=True, bruleur=True), None) == []  # signalé une seule fois
    # bourrage : front montant du contact
    assert names(d.update(13, gpio(jam_t=True), None)) == ["bourrage_tremie"]
    assert names(d.update(14, gpio(jam_t=True), None)) == []
    # sonde muette au 5e échec, rétablie au retour
    assert names(d.update(15, gpio(), None, sensor_failures=4)) == []
    assert names(d.update(16, gpio(), None, sensor_failures=5)) == ["sonde_muette"]
    assert names(d.update(17, gpio(), None, sensor_failures=6)) == []
    assert names(d.update(18, gpio(), 50.0, sensor_failures=0)) == ["sonde_ok"]
    # GPIO illisibles : un seul événement
    assert names(d.update(19, None, 50.0)) == ["gpio_muet"]
    assert names(d.update(20, None, 50.0)) == []


def test_spool_writes_pv2_files_and_rotates(tmp_path):
    spool = bl.Spool(str(tmp_path), "chaudiere")
    spool.add_measures([("2026-11-02T14:00:00Z", "boiler_temp", 61.3, "C")])
    spool.add_measures([("2026-11-02T14:01:00Z", "boiler_temp", 61.9, "C")])
    d = bl.EventDetector(75)
    spool.add_events([d._event(0, "logger_start"), d._event(5, "surchauffe", ts_end=9, description="max, 92.4 °C")])
    spool.rotate(1_793_628_300)
    files = spool.outbox_files()
    assert [os.path.basename(f) for f in files] == ["20261102_140500.csv", "20261102_140500_events.csv"]
    with open(files[0]) as f:
        lines = f.read().splitlines()
    assert lines[:2] == ["PV2_CSV_V001", "timestamp,channel_id,value,unit"]
    assert lines[2:] == ["2026-11-02T14:00:00Z,boiler_temp,61.3,C", "2026-11-02T14:01:00Z,boiler_temp,61.9,C"]
    with open(files[1]) as f:
        rows = list(csv.reader(f))
    assert rows[0] == ["PV2_EVENTS_V001"]
    assert rows[1] == ["ts_start", "ts_end", "category", "title", "description"]
    assert rows[2] == ["1970-01-01T00:00:00Z", "", "chaudiere", "Démarrage du logger", ""]
    assert rows[3] == ["1970-01-01T00:00:05Z", "1970-01-01T00:00:09Z", "chaudiere", "Surchauffe (90 °C)", "max, 92.4 °C"]
    assert not os.path.exists(spool.pending_measures)
    spool.save_state({"pellets_total_g": 12.5})
    assert spool.load_state() == {"pellets_total_g": 12.5}


def test_load_conf_defaults_and_overrides(tmp_path):
    conf_file = tmp_path / "boiler-logger.conf"
    conf_file.write_text('# commentaire\nFEED_RATE_G_PER_S=9.2\nSFTP_URL="sftp://h/c/d/"\n')
    conf = bl.load_conf(str(conf_file))
    assert conf["FEED_RATE_G_PER_S"] == "9.2"
    assert conf["SFTP_URL"] == "sftp://h/c/d/"
    assert conf["PUSH_INTERVAL_S"] == "300"
    assert bl.load_conf(str(tmp_path / "absent"))["EFFICIENCY"] == "0.85"
