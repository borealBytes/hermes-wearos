"""dashboard/plugin_api.py — the Desktop pane's real data plane (ctx.rest routes).

v2 shape: /status renders the checklist, /pair/* runs the window+claim, /devices/*
and /grants manage the registry, /enable DELEGATES to the manage CLI (no hand-rolled
editor anymore), /stt reports the pinned installer. fastapi is core's runtime, not a
plugin dependency (see plugin_api.py docstring) — importorskip keeps rule 10.

The module under test is loaded EXACTLY the way core loads it (file path, no package
convention). plugin_api finds the live wearos instance by scanning sys.modules for
this package's __init__ — which conftest already loaded — so no second copy of the
plugin is ever exec'd here, and the self-import fallback (when there is no live
copy) registers under a key of ITS OWN: the host's "wearos" namespace is never
touched (review fix, rule 9).
"""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import pytest

from conftest import load_plugin, SECRET_A

pytest.importorskip("fastapi", reason="core runtime module; plugin adds no pip deps")

_API = Path(__file__).resolve().parent.parent / "dashboard" / "plugin_api.py"


class FakeRun:
    """subprocess.run replacement recording argv; answers `plugins list --json`."""

    def __init__(self, rows=None, fail=()):
        self.calls = []
        self.rows = rows if rows is not None else [
            {"name": "wearos", "status": "enabled", "version": "0.2.0",
             "description": "d", "source": "user", "removed": None}]
        self.fail = fail

    def __call__(self, argv, **kw):
        self.calls.append(list(argv))
        ok = not any(w in " ".join(argv) for w in self.fail)
        out = json.dumps(self.rows) if "list" in argv and "--json" in argv else ""
        return type("R", (), {"returncode": 0 if ok else 1, "stdout": out,
                              "stderr": "" if ok else "No plugin named 'wearos'."})()


@pytest.fixture
def api(wearos, monkeypatch):
    # The pane inventory consults `hermes plugins list --json` (D10); fake the CLI.
    monkeypatch.setattr(wearos.shutil, "which", lambda name: "/usr/bin/hermes")
    fake = FakeRun()
    monkeypatch.setattr(wearos.subprocess, "run", fake)
    wearos._INVENTORY_CACHE.pop("inv", None)
    wearos._INVENTORY_CACHE["fake"] = fake
    # _door_listening() probes 127.0.0.1:<claim_port> — on a real gateway box a LIVE
    # door answers and breaks isolation (measured). The route just reports it; pin it.
    monkeypatch.setattr(wearos, "_door_listening", lambda: False)
    spec = importlib.util.spec_from_file_location("wearos_plugin_api", _API)
    api_mod = importlib.util.module_from_spec(spec)
    sys.modules["wearos_plugin_api"] = api_mod
    spec.loader.exec_module(api_mod)
    yield api_mod
    sys.modules.pop("wearos_plugin_api", None)


def test_wearos_resolver_finds_the_loaded_instance(wearos, api):
    assert api._wearos() is load_plugin(), "must reuse the live module — shared engine"
    assert "wearos" not in sys.modules, "the self-import must never claim the bare key"


def test_status_shape(wearos, api):
    payload = api.status()
    assert set(payload) == {"checks", "devices", "pairing", "profiles", "grants",
                            "apk_release_url", "door"}
    assert payload["devices"] == {}
    assert payload["pairing"]["window"] is None
    assert payload["profiles"] == [{"name": "default", "enabled": True, "visible": True,
                                    "version": "0.2.0", "source": "user"}]
    assert payload["checks"]["tailscaled"]["ok"] is True
    assert payload["door"] == {"listening": False}


def test_inventory_serving_serves_stale_and_refreshes_off_the_path(wearos, api,
                                                                   monkeypatch):
    """The 2026-11-10 /status timeout: 27 profiles x cold CLI fan-out = 68-84 s.
    A stale cache must SERVE immediately; the refresh rides a background thread."""
    import time
    fake = wearos._INVENTORY_CACHE["fake"]
    stale = [{"name": "old", "enabled": True, "visible": True, "version": "0.0.1",
              "source": "x"}]
    wearos._INVENTORY_CACHE["inv"] = (time.monotonic() - 999, stale)
    started = []
    monkeypatch.setattr(wearos.threading, "Thread",
                        lambda target=None, **kw: type(
                            "T", (), {"start": lambda s: started.append(target),
                                      "join": lambda s: None})())
    fake.calls.clear()
    rows = wearos._inventory_serving(max_age=10.0)
    assert rows == stale                      # served, not blocked
    assert fake.calls == []                   # no CLI on the request path
    assert len(started) == 1                  # exactly one refresh queued
    started[0]()                              # run the queued refresh
    assert wearos._INVENTORY_CACHE["inv"][1] != stale and fake.calls
    assert wearos._INVENTORY_REFRESHING is False
    # while a refresh is in flight, a second serving call must not queue another
    fake.calls.clear()
    wearos._INVENTORY_CACHE["inv"] = (time.monotonic() - 999, stale)
    started.clear()
    rows2 = wearos._inventory_serving(max_age=10.0)
    wearos._INVENTORY_REFRESHING = True       # simulate in-flight refresh
    wearos._inventory_serving(max_age=10.0)
    wearos._INVENTORY_REFRESHING = False
    assert rows2 == stale


def test_pair_window_claim_round_trip(wearos, api):
    opened = api.pair_window({"action": "open"})
    assert opened["opened"]["profile"] == "default"
    wearos.door_begin(SECRET_A, "Watch")
    pend = api.pair_pending()
    assert pend["pending"][0]["label"] == "Watch"
    code = pend["pending"][0]["code"]
    out = api.pair_claim({"code": "000000"})
    assert "error" in out and out["lock"]["locked"] is False      # miss 1
    assert "approved" in api.pair_claim({"code": code})
    assert wearos.door_poll(SECRET_A)[0] == "ready"


def test_revoke_requires_a_name_and_reports(wearos, api, monkeypatch):
    assert api.devices_revoke({}) == {"error": "name is required"}
    monkeypatch.setattr(wearos.shutil, "which", lambda name: None)  # no real key-write
    assert api.devices_revoke({"name": "ghost"})["error"].startswith("no paired watch")


def test_grants_and_alerts_routes(wearos, api):
    assert api.grants({"profile": "research", "allow": True})["grants"] == \
        {"research": {"granted_at": pytest.approx(wearos.time.time(), abs=5)}}
    assert api.grants({"profile": "research", "allow": False})["grants"] == {}
    assert api.alerts({"profile": "wear", "pref": "fail"}) == {"profile": "wear",
                                                               "pref": "fail"}
    assert api.alerts({"profile": "", "pref": "bogus"}) == {
        "error": "profile and pref (all|fail|off) are required"}


def test_enable_delegates_to_the_manage_cli(wearos, api):
    fake = wearos._INVENTORY_CACHE["fake"]
    fake.calls.clear()
    out = api.enable({"profile": "chef", "enable": True})
    assert out["ok"] is True
    assert fake.calls[-1] == ["/usr/bin/hermes", "-p", "chef", "plugins", "enable",
                              "wearos"]
    # owner ruling 2026-11-10: enable IS the grant — one checkbox, no second box.
    assert wearos._engine().grants().get("chef"), "enable must auto-grant"
    out2 = api.enable({"profile": "chef", "enable": False})
    assert fake.calls[-1][-2:] == ["disable", "wearos"]
    assert not wearos._engine().grants().get("chef"), "disable must revoke"
    assert "--yes" not in fake.calls[-1]           # never --yes (review, rule 12)
    assert api.enable({"enable": True}) == {"error": "profile is required (no silent "
                                                     "all-profiles default)"}


def test_enable_surfaces_the_consult_no_plugin_error(wearos, api):
    fake = wearos._INVENTORY_CACHE["fake"]
    fake.fail = ("chef",)
    out = api.enable({"profile": "chef", "enable": True})
    assert out["ok"] is False and "No plugin named" in out["detail"]


def test_stt_status_reports_the_pinned_plan(wearos, api):
    payload = api.stt()
    assert payload["preview"]["toolchain_ok"] in (True, False)
    assert payload["preview"]["steps"]
    # sudo law: the approval card shows the EXACT argv lines before anything runs.
    cmds = payload["preview"]["commands"]
    assert any(c.startswith("git init") for c in cmds)
    assert any("cmake --build" in c for c in cmds)
    assert all(isinstance(c, str) for c in cmds)
    assert payload["preview"]["cli"] == "hermes watch setup --fix stt"
    assert payload["installed"] is False
    assert payload["model_verified"] is False
    assert payload["lib"] is False


# ── voice-out pane door (slice 3) ───────────────────────────────────────────────

def _tair(wearos):
    import importlib
    return importlib.import_module(wearos.__name__ + ".tts_air")


def test_voice_feed_shapes_the_pane(wearos, api):
    feed = api.voice()
    assert set(feed) == {"voice", "air", "aligner"}
    assert feed["air"]["slots"] == {"gateway": "community-q8",   # community default
                                    "watch": "community-q4"}      # watch-size quant
    assert {r["id"] for r in feed["air"]["sources"]} == set(_tair(wearos).SOURCES)
    assert feed["voice"]["cards"] == {}
    hosts = feed["air"]["preview"]["hosts"]                      # rule 13: hosts up front
    assert "huggingface.co" in hosts and "pypi.org" in hosts


def test_voice_post_select_install_soundtest(wearos, api, monkeypatch):
    ta = _tair(wearos)
    out = api.voice_post({"action": "select", "source": "official-q4", "for": "watch"})
    assert out["ok"] and out["slots"]["watch"] == "official-q4"
    assert out["slots"]["gateway"] == "community-q8"             # other slot untouched
    assert not api.voice_post({"action": "select", "source": "nope"})["ok"]

    # install: gated row + token → stored 0600; worker thread faked, no pip in tests
    monkeypatch.setattr(ta.shutil, "which", lambda n: "/usr/bin/" + n)
    monkeypatch.setattr(ta.threading, "Thread",
                        lambda **kw: type("T", (), {"start": lambda s: None,
                                                    "is_alive": lambda s: False})())
    r = api.voice_post({"action": "install", "source": "official-q8",
                        "token": "hf_pane_test"})
    assert r.get("started"), r
    tf = ta._root() / "hf_token"
    assert tf.read_text() == "hf_pane_test"
    import stat
    assert stat.S_IMODE(tf.stat().st_mode) == 0o600

    # sound test without an engine: the refusal TEXT, never a silent button
    r = api.voice_post({"action": "soundtest", "text": "hello"})
    assert "audio_wav_b64" not in r and r.get("error")
    assert "unknown voice action" in api.voice_post({"action": "launch-missiles"})["error"]
