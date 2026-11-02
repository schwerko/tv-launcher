"""A stand-in for Home Assistant's REST API, enough to exercise the launcher's Home Assistant row.

Run:  python3 tools/mock_ha.py   (listens on port 8123; token test-token-123)
In the app: Settings › Home Assistant › server http://<this computer>:8123, token test-token-123.
"""
import json, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = "test-token-123"
STATES = {
    "light.living_room": {"friendly_name": "Living room", "state": "on"},
    "light.kitchen": {"friendly_name": "Kitchen", "state": "off"},
    "switch.fan": {"friendly_name": "Ceiling fan", "state": "off"},
    "scene.movie_night": {"friendly_name": "Movie night", "state": "unknown"},
    "sensor.outside_temperature": {"friendly_name": "Outside temperature", "state": "12.4", "unit_of_measurement": "°C"},
    "binary_sensor.front_door": {"friendly_name": "Front door", "state": "off"},
    "automation.lights_off": {"friendly_name": "Lights off at night", "state": "on"},
}

class H(BaseHTTPRequestHandler):
    def _auth(self):
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            self.send_response(401); self.end_headers(); self.wfile.write(b'401: Unauthorized'); return False
        return True
    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        if not self._auth(): return
        if self.path == "/api/": return self._json(200, {"message": "API running."})
        if self.path == "/api/states":
            return self._json(200, [{"entity_id": k, "state": v["state"],
                "attributes": {a: b for a, b in v.items() if a != "state"}} for k, v in STATES.items()])
        self._json(404, {"message": "not found"})
    def do_POST(self):
        if not self._auth(): return
        n = int(self.headers.get("Content-Length", 0)); body = json.loads(self.rfile.read(n) or b"{}")
        parts = self.path.strip("/").split("/")   # api services domain service
        if len(parts) == 4 and parts[1] == "services":
            domain, service = parts[2], parts[3]; eid = body.get("entity_id")
            print("call", domain, service, eid, flush=True)
            if eid in STATES and service == "toggle":
                STATES[eid]["state"] = "off" if STATES[eid]["state"] == "on" else "on"
            return self._json(200, [])
        self._json(404, {"message": "not found"})
    def log_message(self, *a): pass

print("mock HA on 0.0.0.0:8123, token", TOKEN, flush=True)
ThreadingHTTPServer(("0.0.0.0", 8123), H).serve_forever()
