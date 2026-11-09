#!/usr/bin/env python3
"""Exercise the /voice/* surface inside the container with a FAKE whois (the
same seam the test suite drives): pair a device, sync prefs, create a card
(Clay's real voice if /opt/wearos-in/clay.wav is mounted, else a synthetic
speech-like clip), ask for synthesis, and write every artifact to
/opt/wearos-out/. No tailscale, no gateway process — this proves the plugin
half end-to-end in a fresh image.
"""
import base64
import importlib.util
import json
import math
import os
import struct
import sys
import urllib.error
import urllib.request
from pathlib import Path

os.environ.setdefault("HERMES_HOME", "/opt/wearos-home")
os.environ.setdefault("API_SERVER_KEY", "sk-demo...tion")
os.environ["tailscale_api_token"] = "tskey-api-demo"   # _cred() fallback (conftest trick)
OUT = Path(os.environ.get("WEAROS_OUT", "/opt/wearos-out"))
IN = Path("/opt/wearos-in")
OUT.mkdir(parents=True, exist_ok=True)

sys.path.insert(0, "/opt/hermes")  # host modules (hermes_constants)
spec = importlib.util.spec_from_file_location(
    "wearos_plugin", "/opt/wearos/plugin/__init__.py",
    submodule_search_locations=["/opt/wearos/plugin"])
m = importlib.util.module_from_spec(spec)
sys.modules["wearos_plugin"] = m
spec.loader.exec_module(m)
vo = importlib.import_module("wearos_plugin.voiceout")
air = importlib.import_module("wearos_plugin.tts_air")
ap = importlib.import_module("wearos_plugin.align_parakeet")
air.register_if_ready()          # no-ops honestly until the model is installed
ap.register_if_ready()
# The pairing ceremony mints a real tailnet authkey on approve; in the demo box
# we stand in for the Tailscale API (same seam the test suite monkeypatches).
m.tailscale.TailnetClient.mint_enrollment_key = lambda self, tag, expiry="24h": (
    "tskey-fake", "key-demo")

SECRET = os.urandom(32).hex()
NODE = {"node_id": "n-demo", "node_name": "demo.container.ts.net",
        "tags": ["tag:hermes-watch"], "profile": "default@example"}


def whois(peer_ip):
    return dict(NODE)


port = m.private_api.start_server("127.0.0.1", 0, m.private_api.PrivateBackend(
    whois, m._lane_session, m._lane_events, m._lane_ack, lambda p, d: None,
    voice=m._lane_voice), m._lane_lookup)
base = f"http://127.0.0.1:{m.private_api.actual_port()}"


def call(method, path, body=None, raw=False, token=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, data=data, method=method,
                                 headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            payload = r.read()
            return (r.status, payload) if raw else \
                (r.status, json.loads(payload) if payload else None)
    except urllib.error.HTTPError as e:
        payload = e.read()
        return (e.code, payload) if raw else \
            (e.code, json.loads(payload) if payload else None)


def clip():
    """Speech-like wav: AM envelope + quiet edges (a flat sine has no noise
    floor for the energy VAD — slice-1 lesson)."""
    rate, secs = 16000, 5.2
    sm = []
    for i in range(int(secs * rate)):
        t = i / rate
        env = (0.15 + 0.85 * (0.5 + 0.5 * math.sin(2 * math.pi * 3.1 * t))
               if 0.25 <= t <= secs - 0.25 else 0.02)
        sm.append(int(env * 8000 * math.sin(2 * math.pi
                      * (180 + (i // rate) * 620) * i / rate)))
    data = b"".join(struct.pack("<h", s) for s in sm)
    return (b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt "
            + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
            + b"data" + struct.pack("<I", len(data)) + data)


print("== pairing ceremony (door) ==")
m.open_pairing()
code = m.door_begin(SECRET, "Container Watch")["code"]
m.approve_pair(code)
ack = m.door_ack(SECRET)
name, token = ack["device"]["hostname"], ack["token"]
st, _ = call("POST", "/watch/default/session", {"secret": SECRET}, token=token)
print(f"   session on {name}: {st} (node {NODE['node_id']} now bound)")

print("== GET /voice/prefs (no bearer — the socket is the credential) ==")
st, doc = call("GET", "/voice/prefs")
(OUT / "prefs.json").write_text(json.dumps(doc, indent=2))
print(f"   {st} rev={doc.get('rev')}")

print("== PUT /voice/prefs (watch-side edit replay, rev 4) ==")
doc["rev"] = 4
doc.setdefault("global", {})["speed"] = 1.15
st, merged = call("PUT", "/voice/prefs", {"prefs": doc})
print(f"   {st} speed={merged['global']['speed']} rev={merged['rev']}")

real = IN / "clay.wav"
wav = real.read_bytes() if real.is_file() else clip()
label = "Clay (real)" if real.is_file() else "synthetic-demo"
tx = ((IN / "clay.txt").read_text().strip() if (IN / "clay.txt").is_file()
      else vo.SCRIPTED_PROMPT)
print(f"== POST /voice/card_create  [{label}, {len(wav)} bytes] ==")
st, card = call("POST", "/voice/card_create", {
    "wav_b64": base64.b64encode(wav).decode(), "label": label,
    "transcript": tx, "source": "docker-demo"})
(OUT / "card.json").write_text(json.dumps(card, indent=2))
cid = (card.get("card_id") or "")
print(f"   {st} card={cid} measured_ref_rate={card.get('ref_rate')}"
      f" error={card.get('error', '')}")

if cid:
    print("== GET /voice/card_wav (raw audio lane) ==")
    st, raw = call("GET", f"/voice/card_wav?card_id={cid}", raw=True)
    print(f"   {st}, {len(raw)} bytes round-tripped: {raw == wav}")

print("== POST /voice/synthesize ==")
st, r = call("POST", "/voice/synthesize",
             {"text": "Your cron digest is ready: three farm alerts, no "
                      "incidents. The lake is holding.", "agent": "default"})
(OUT / "synthesize.json").write_text(
    json.dumps({k: v for k, v in r.items() if k != "audio_wav_b64"}, indent=2))
if isinstance(r.get("audio_wav_b64"), str):
    (OUT / "synth-1.0x.wav").write_bytes(base64.b64decode(r["audio_wav_b64"]))
    (OUT / "words.json").write_text(json.dumps(r.get("words"), indent=2))
    print("   REAL AUDIO -> /opt/wearos-out/synth-1.0x.wav "
          f"({len(base64.b64decode(r['audio_wav_b64']))} bytes), "
          f"align={r.get('align')}, words={len(r.get('words') or [])}")
else:
    print(f"   {st} ok={r.get('ok')} error={r.get('error')}")

print("== engine/aligner status (honest, reported) ==")
(OUT / "engines.json").write_text(json.dumps(
    {"air": air.status(), "aligner": ap.status()}, indent=2, default=str))
print("   air:", air.status().get("phase"),
      "| aligner:", ap.status().get("phase"))
print(f"\nartifacts in {OUT}/")
