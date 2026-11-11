"""/voice/* on the private lane: node law, prefs replay, card round trip, honest
synthesize outcomes (issue #7, slice 2c). Real HTTP, FAKE whois, no tailscale."""
from __future__ import annotations

import base64
import json
import math
import struct
import urllib.error
import urllib.request

import pytest

from conftest import SECRET_A

NODE = {"node_id": "n-watch", "node_name": "watch.example.ts.net",
        "tags": ["tag:hermes-watch"], "profile": "me@example"}


class FakeWhois:
    def __init__(self):
        self.answer = dict(NODE)

    def __call__(self, peer_ip):
        return dict(self.answer) if self.answer else None


def mkwav(secs, rate=16000, amp=0.7):
    """Speech-like: AM envelope + quiet edges — a FLAT sine gives the energy VAD
    no noise floor to gate against (same lesson as the slice-1 fixtures)."""
    sm = []
    for i in range(int(secs * rate)):
        t = i / rate
        env = (0.15 + 0.85 * (0.5 + 0.5 * math.sin(2 * math.pi * 3.1 * t))
               if 0.2 <= t <= secs - 0.2 else 0.02)
        sm.append(int(env * amp * 12000
                      * math.sin(2 * math.pi * (200 + (i // rate) * 800) * i / rate)))
    data = b"".join(struct.pack("<h", s) for s in sm)
    return (b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt "
            + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
            + b"data" + struct.pack("<I", len(data)) + data)


@pytest.fixture
def vlane(wearos, monkeypatch):
    """Paired, sessioned (node bound), voice surface wired; token NOT used on
    /voice/* — the fixture returns the lane base and the whois knob."""
    monkeypatch.setenv("API_SERVER_KEY", "sk-her...ey-A")
    wearos.open_pairing()
    code = wearos.door_begin(SECRET_A, "Galaxy Watch Ultra")["code"]
    wearos.approve_pair(code)
    ack = wearos.door_ack(SECRET_A)
    whois = FakeWhois()
    backend = wearos.private_api.PrivateBackend(
        whois, wearos._lane_session, wearos._lane_events, wearos._lane_ack,
        lambda p, d: None, voice=wearos._lane_voice)
    wearos.private_api.start_server("127.0.0.1", 0, backend, wearos._lane_lookup)
    token = ack["token"]
    base = f"http://127.0.0.1:{wearos.private_api.actual_port()}"
    st, _ = http(base, "POST", "/watch/default/session", {"secret": SECRET_A},
                 token=token)                     # binds n-watch to the device
    assert st == 200
    yield {"base": base, "whois": whois, "module": wearos}
    wearos.private_api.stop_server()


def http(base, method, path, body=None, token=None, raw=False):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, data=data, method=method,
                                 headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            payload = resp.read()
            if raw:
                return resp.status, payload, resp.headers.get("Content-Type")
            return resp.status, (json.loads(payload) if payload else None)
    except urllib.error.HTTPError as exc:
        payload = exc.read()
        if raw:
            return exc.code, payload, exc.headers.get("Content-Type")
        return exc.code, (json.loads(payload) if payload else None)


# ---------------------------------------------------------------- identity law

def test_voice_without_a_node_is_403(vlane):
    vlane["whois"].answer = None
    st, body = http(vlane["base"], "GET", "/voice/prefs")
    assert st == 403 and "tailnet" in body["error"]


def test_voice_needs_no_token_but_a_BOUND_node(vlane):
    st, body = http(vlane["base"], "GET", "/voice/prefs")     # no token at all
    assert st == 200 and body["rev"] == 1
    vlane["whois"].answer = {**NODE, "node_id": "n-stranger"}  # on-tailnet, unbound
    st, body = http(vlane["base"], "GET", "/voice/prefs")
    assert st == 403 and "not bound" in body["error"]


# ---------------------------------------------------------------- prefs sync

def test_prefs_put_replays_watch_edits_by_rev(vlane):
    st, doc = http(vlane["base"], "GET", "/voice/prefs")
    assert st == 200
    remote = json.loads(json.dumps(doc))          # deep copy
    remote["rev"] = 7
    remote.setdefault("global", {})["speed"] = 1.4
    st, merged = http(vlane["base"], "PUT", "/voice/prefs", {"prefs": remote})
    assert st == 200
    assert merged["global"]["speed"] == 1.4
    st, doc = http(vlane["base"], "GET", "/voice/prefs")
    assert doc["global"]["speed"] == 1.4          # canonical store moved


def test_stale_rev_watch_cannot_clobber(vlane):
    http(vlane["base"], "PUT", "/voice/prefs",
         {"prefs": {"rev": 9, "global": {"speed": 1.8, "karaoke": True}}})
    st, merged = http(vlane["base"], "PUT", "/voice/prefs",
                      {"prefs": {"rev": 2, "global": {"speed": 0.6,
                                                      "karaoke": True}}})
    assert st == 200 and merged["global"]["speed"] == 1.8   # higher rev wins


# ---------------------------------------------------------------- cards

def test_card_create_wav_and_delete_round_trip(vlane):
    import voiceout as vo
    wav = mkwav(4.0)
    st, card = http(vlane["base"], "POST", "/voice/card_create",
                    {"wav_b64": base64.b64encode(wav).decode(),
                     "label": "Lane Voice", "transcript": "words",
                     "source": "watch-record"})
    assert st == 200, card
    cid = card["card_id"]
    assert card["ref_rate"] > 0                      # measured, not assumed
    assert card["transcript"] == "words"             # persisted for cloning
    st, raw, ctype = http(vlane["base"], "GET",
                          f"/voice/card_wav?card_id={cid}", raw=True)
    assert st == 200 and ctype == "audio/wav" and raw == wav
    st, snap = http(vlane["base"], "GET", "/voice/cards")
    assert cid in snap["cards"]                      # mirror snapshot lists it
    # the pane still serves the script line for its own demo box.
    assert snap["prompt"] == vo.SCRIPTED_PROMPT
    st, auto = http(vlane["base"], "POST", "/voice/card_create",
                    {"wav_b64": base64.b64encode(mkwav(5.0)).decode(),
                     "label": "no transcript sent"})
    assert st == 200 and auto["transcript"] == vo.SCRIPTED_PROMPT   # scripted default
    # FREEFORM law (2026-11-11): the watch sends transcript "" — talk about
    # anything — and pace comes back flagged as the envelope estimate it is.
    st, free = http(vlane["base"], "POST", "/voice/card_create",
                    {"wav_b64": base64.b64encode(mkwav(5.0)).decode(),
                     "label": "freeform", "transcript": ""})
    assert st == 200 and free["transcript"] == ""
    assert free["rate_source"] == "syllabic-est"
    # fixture AM = 3.1 Hz pulses → ~186 syl/min / 1.65 ≈ 113 wpm; band is wide.
    assert 60 < free["ref_rate"] < 200, free["ref_rate"]
    st, meas = http(vlane["base"], "POST", "/voice/card_create",
                    {"wav_b64": base64.b64encode(mkwav(5.0)).decode(),
                     "label": "scripted", "transcript": "one two three four"})
    assert st == 200 and meas["rate_source"] == "measured"
    # NAME IS DISPLAY TEXT: rename swaps the label, the id never moves.
    st, doc = http(vlane["base"], "POST", "/voice/card_rename",
                   {"card_id": cid, "label": "  dad  "})
    assert st == 200 and doc["cards"][cid]["label"] == "dad"
    st, snap = http(vlane["base"], "GET", "/voice/cards")
    assert snap["cards"][cid]["label"] == "dad"      # every mirror sees it
    st, doc = http(vlane["base"], "POST", "/voice/card_rename",
                   {"card_id": "no-such-card", "label": "x"})
    assert st == 500 or "no such" in str(doc.get("error", doc))
    for junk in (free, meas):
        http(vlane["base"], "POST", "/voice/card_delete", {"card_id": junk["card_id"]})
    st, doc = http(vlane["base"], "POST", "/voice/card_delete", {"card_id": cid})
    assert st == 200 and cid not in doc.get("cards", {})
    st, doc2 = http(vlane["base"], "POST", "/voice/card_delete",
                    {"card_id": auto["card_id"]})
    assert st == 200
    st, raw, _ = http(vlane["base"], "GET",
                      f"/voice/card_wav?card_id={cid}", raw=True)
    assert st == 404


def test_card_rejects_a_two_second_clip_over_the_lane(vlane):
    st, body = http(vlane["base"], "POST", "/voice/card_create",
                    {"wav_b64": base64.b64encode(mkwav(2.0)).decode(),
                     "label": "Too Short"})
    assert st == 400 and "3-60" in body["error"]     # the floor is gateway-enforced


# ---------------------------------------------------------------- synthesize

def test_synthesize_without_engine_reports_ok_false_not_500(vlane):
    m = vlane["module"]
    m.voiceout._SYNTH.clear()
    st, body = http(vlane["base"], "POST", "/voice/synthesize",
                    {"text": "hello watch", "agent": "wear"})
    assert st == 200 and body["ok"] is False
    assert "NeuTTS Air" in body["error"]              # names the fix


def test_synthesize_ships_b64_audio_and_words_with_fake_engine(vlane):
    m = vlane["module"]
    fake = mkwav(3.0)
    m.voiceout._SYNTH["air"] = lambda t, c, s: (fake, 16000)
    m.voiceout._ALIGN["parakeet"] = lambda a, r, t: [
        {"text": "hello", "start": 0.0, "end": 0.4}]
    st, body = http(vlane["base"], "POST", "/voice/synthesize",
                    {"text": "hello", "agent": "wear"})
    assert st == 200 and body["ok"] and body["align"] == "forced"
    assert base64.b64decode(body["audio_wav_b64"]) == fake
    m.voiceout._SYNTH.pop("air")
    m.voiceout._ALIGN.pop("parakeet")


def test_engine_crash_is_503_transparent(vlane):
    m = vlane["module"]
    def crash(t, c, s):
        raise RuntimeError("engine failed: segfault")
    m.voiceout._SYNTH["air"] = crash
    try:
        st, body = http(vlane["base"], "POST", "/voice/synthesize",
                        {"text": "hi", "agent": "wear"})
        assert st == 503 and "segfault" in body["error"]
    finally:
        m.voiceout._SYNTH.pop("air")


def test_unknown_voice_action_404(vlane):
    st, _ = http(vlane["base"], "GET", "/voice/plebs")
    assert st == 404


def test_tts_sources_menu_serves_the_pane_dropdown(vlane):
    base = vlane["base"]
    st, r = http(base, "GET", "/voice/tts_sources")
    assert st == 200 and len(r["sources"]) >= 4
    assert r["slots"] == {"gateway": "community-q8",       # best community quant
                          "watch": "community-q4"}         # watch-size quant
    assert any(s["gated"] and s["id"] == "official-q8" for s in r["sources"])


def test_tts_install_stores_token_0600_and_names_valid_rows(vlane, monkeypatch):
    base = vlane["base"]
    st, r = http(base, "POST", "/voice/tts_install", {"source": "bogus-row"})
    assert st == 200 and not r["started"] and "community-q8" in r["error"]
    import sys
    ta = (sys.modules.get("wearos_plugin.tts_air") or sys.modules.get("wearos.tts_air")
          or sys.modules.get("tts_air"))
    assert ta is not None, "lane must have imported the engine module"
    monkeypatch.setattr(ta, "start",
                        lambda **kw: {"started": True, "stub": (kw.get("source"),
                                                                kw.get("for_slot"))})
    st, r = http(base, "POST", "/voice/tts_install",
                 {"token": "hf_testtoken", "source": "alt-q8-drinking", "for": "watch"})
    assert st == 200 and r.get("started") and r["stub"] == ["alt-q8-drinking", "watch"]
    tf = ta._root() / "hf_token"
    assert tf.read_text() == "hf_testtoken"
    import stat
    assert stat.S_IMODE(tf.stat().st_mode) == 0o600       # credentials are 0600, always
