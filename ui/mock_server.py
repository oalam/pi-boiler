#!/usr/bin/env python3
"""Serveur de développement pour l'IHM de la chaudière.

Sert ui/index.html et simule les endpoints /api/status et /api/command avec une
chaudière factice — de quoi développer et prévisualiser l'interface sur PC ou
téléphone, sans ESP32.

    python3 ui/mock_server.py

Puis http://localhost:8080 (ou http://<ip-du-mac>:8080 depuis le téléphone,
sur le même réseau WiFi).

NB : la simulation ci-dessous n'est PAS la vraie logique de régulation — elle
est juste assez crédible pour exercer l'IHM. La vraie machine à états est dans
controller/.
"""
import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

PORT = 8080
UI_DIR = Path(__file__).parent

# Consommation simulée quand la chaudière brûle (kg/s). Volontairement ACCÉLÉRÉE
# par rapport au réel (~5,85 kg/h ≈ 0,0016 kg/s à 24 kW) pour voir l'IHM bouger
# pendant le développement. La vraie estimation calibrée est dans le cœur.
FEED_AVG_KG_S = 0.02


class FakeBoiler:
    def __init__(self):
        self.state = "Stopped"
        self.temp = 22.0
        self.conso_today_kg = 0.0
        self.conso_total_kg = 0.0
        self._last = time.time()

    def command(self, cmd: str):
        if cmd == "start" and self.state in ("Stopped", "Locked"):
            self.state = "Igniting"
        elif cmd in ("stop", "shutdown"):
            self.state = "Stopped"
        # "load" (amorçage manuel) : acquitté, sans effet d'état dans la simu.

    def _advance(self):
        now = time.time()
        dt = now - self._last
        self._last = now
        if self.state in ("Igniting", "Running"):
            self.temp += 0.6 * dt
            self.conso_today_kg += FEED_AVG_KG_S * dt
            self.conso_total_kg += FEED_AVG_KG_S * dt
        elif self.state == "Slowing":
            self.temp -= 0.2 * dt
        else:
            self.temp += (22.0 - self.temp) * 0.05 * dt  # retour lent à l'ambiant
        if self.state == "Igniting" and self.temp >= 32:
            self.state = "Running"
        elif self.state == "Running" and self.temp >= 75:
            self.state = "Slowing"
        elif self.state == "Slowing" and self.temp <= 72:
            self.state = "Running"
        self.temp = max(15.0, min(99.0, self.temp))

    def status(self) -> dict:
        self._advance()
        burning = self.state in ("Igniting", "Running")
        engines = [
            burning,                             # trémie
            burning,                             # brûleur
            burning or self.state == "Slowing",  # ventilo
            self.temp >= 40.0,                   # circulateur
            self.temp >= 60.0,                   # laddomat
        ]
        return {
            "state": self.state,
            "temp": round(self.temp, 1),
            "engines": engines,
            "consoToday": round(self.conso_today_kg, 2),
            "consoTotal": round(self.conso_total_kg, 2),
        }


boiler = FakeBoiler()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path in ("/", "/index.html"):
            self._send_file("index.html", "text/html; charset=utf-8")
        elif parsed.path == "/api/status":
            self._send_json(boiler.status())
        elif parsed.path == "/api/command":
            cmd = parse_qs(parsed.query).get("type", [""])[0]
            boiler.command(cmd)
            self._send_json({"ok": True})
        else:
            self.send_error(404)

    def _send_file(self, name: str, content_type: str):
        try:
            body = (UI_DIR / name).read_bytes()
        except OSError:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_json(self, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass  # silencieux


if __name__ == "__main__":
    print(f"IHM chaudière (mock) — http://localhost:{PORT}")
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
