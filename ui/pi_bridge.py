#!/usr/bin/env python3
"""Pont IHM pour la chaudière pilotée par l'appli Scala (phase 1).

Sert ui/index.html et implémente le contrat d'API de la phase 2 (/api/status,
/api/command) au-dessus de l'existant :

- /api/status  : température lue directement sur la sonde 1-Wire (sysfs),
  état des moteurs lu sur les GPIO (via la commande `gpio` de wiringPi,
  logique active-low : 0 = moteur en marche) ;
- /api/command : relayée telle quelle vers l'API Scala (port 9000).

Contraintes : Python 3.5 (Raspbian Stretch) — pas de f-strings, pas de
ThreadingHTTPServer. La consommation de granulés n'est pas suivie par la
phase 1 : consoToday/consoTotal valent 0.

Déploiement : /home/pi/ui/ + service systemd boiler-ui (port 8080).
"""
import json
import socketserver
import subprocess
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

PORT = 8080
UI_DIR = Path(__file__).parent
# NB : l'appli Scala binde exclusivement 192.168.1.12 (pas 127.0.0.1)
SCALA_API = "http://192.168.1.12:9000/api/v0.1/action"
W1_SLAVE = "/sys/bus/w1/devices/28-021581cc38ff/w1_slave"

# (libellé IHM, numéro wiringPi) dans l'ordre attendu par index.html :
# e0 trémie, e1 brûleur, e2 ventilo, e3 circul. (vanne 4V), e4 laddomat
ENGINE_PINS = [("tremie", 2), ("bruleur", 0), ("ventilo", 3),
               ("vanne4V", 4), ("laddomat", 1)]  # permutés depuis oct. 2026 (cf. EngineDriver)

ALLOWED_COMMANDS = ("load", "start", "stop", "shutdown")

_temp_cache = {"value": None, "ts": 0.0}
_temp_lock = threading.Lock()


def read_temperature():
    """Température en °C, cache de 2 s (la conversion 1-Wire prend ~750 ms)."""
    with _temp_lock:
        now = time.time()
        if _temp_cache["value"] is not None and now - _temp_cache["ts"] < 2.0:
            return _temp_cache["value"]
        try:
            with open(W1_SLAVE) as f:
                lines = f.read().splitlines()
            if len(lines) >= 2 and "YES" in lines[0] and "t=" in lines[1]:
                value = int(lines[1].split("t=")[1]) / 1000.0
                _temp_cache["value"] = value
                _temp_cache["ts"] = now
                return value
        except (OSError, ValueError):
            pass
        return _temp_cache["value"]


def read_engines():
    """[bool] moteur en marche, dans l'ordre de l'IHM (active-low : 0 = ON)."""
    states = []
    for _name, pin in ENGINE_PINS:
        try:
            out = subprocess.check_output(["gpio", "read", str(pin)], timeout=2)
            states.append(out.strip() == b"0")
        except (subprocess.SubprocessError, OSError):
            states.append(False)
    return states


class ThreadingServer(socketserver.ThreadingMixIn, HTTPServer):
    daemon_threads = True


class Handler(BaseHTTPRequestHandler):

    def _send(self, code, body, content_type="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path in ("/", "/index.html"):
            body = (UI_DIR / "index.html").read_bytes()
            self._send(200, body, "text/html; charset=utf-8")
        elif parsed.path in ("/doc", "/doc.html"):
            body = (UI_DIR / "doc.html").read_bytes()
            self._send(200, body, "text/html; charset=utf-8")
        elif parsed.path == "/api/status":
            temp = read_temperature()
            engines = read_engines()
            # État dérivé (le cœur Scala ne l'expose pas) : ventilo en marche
            # = cycle de combustion en cours.
            state = "Running" if engines[2] else "Stopped"
            payload = {
                "temp": temp if temp is not None else 0.0,
                "state": state,
                "engines": engines,
                "consoToday": 0.0,
                "consoTotal": 0.0,
            }
            self._send(200, json.dumps(payload).encode())
        elif parsed.path == "/api/command":
            params = parse_qs(parsed.query)
            cmd = params.get("type", [""])[0]
            if cmd not in ALLOWED_COMMANDS:
                self._send(400, b'{"error": "commande inconnue"}')
                return
            try:
                url = SCALA_API + "?type=" + cmd
                with urllib.request.urlopen(url, timeout=5) as resp:
                    resp.read()
                self._send(200, b'{"ok": true}')
            except OSError:
                self._send(502, b'{"error": "API regulation injoignable"}')
        else:
            self._send(404, b'{"error": "not found"}')

    def log_message(self, fmt, *args):
        pass  # pas de log par requête (poll toutes les 2 s)


if __name__ == "__main__":
    server = ThreadingServer(("", PORT), Handler)
    print("pont IHM sur http://0.0.0.0:{}".format(PORT))
    server.serve_forever()
