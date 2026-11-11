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
from threading import Thread as _RealThread   # captured at import: the inventory test fakes threading.Thread

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
    # /status mirrors watch_status PLUS two pane-only derivations: the door
    # listening-bit and the pair host computed live (owner ruling 2026-11-10).
    assert set(payload) == {"checks", "devices", "pairing", "profiles", "grants",
                            "apk_release_url", "door", "tailnet"}
    assert isinstance(payload["tailnet"], dict)
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
    # Bounded (audit 2026-11-11: this test could hang at the pin): run the queued
    # refresh on a helper thread with a hard join deadline — under the faked CLI it
    # must land in seconds, and a wedged refresh must surface as THIS failure, not
    # a frozen suite. _RealThread is the pre-patch class: the monkeypatch above
    # replaced threading.Thread on the SHARED module object.
    _runner = _RealThread(target=started[0], daemon=True)
    _runner.start()
    _runner.join(10)
    assert not _runner.is_alive(), "queued refresh must finish within 10 s under the faked CLI"
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
    begun = wearos.door_begin(SECRET_A, "Watch")   # the WATCH is the only surface
    code = begun["code"]                          # that ever sees the code
    pend = api.pair_pending()
    assert pend["pending"][0]["label"] == "Watch"
    # Catalog review law (Nous #151546): the pending list says WHAT waits, never
    # WHICH code — the code is a secret until a human types it.
    assert "code" not in pend["pending"][0]
    out = api.pair_claim({"code": "000000"})
    assert "error" in out and out["lock"]["locked"] is False      # miss 1
    assert "approved" in api.pair_claim({"code": code})
    assert wearos.door_poll(SECRET_A)[0] == "ready"


def test_revoke_requires_a_name_and_reports(wearos, api, monkeypatch):
    assert api.devices_revoke({}) == {"error": "name is required"}
    monkeypatch.setattr(wearos.shutil, "which", lambda name: None)  # no real key-write
    assert api.devices_revoke({"name": "ghost"})["error"].startswith("no paired watch")


def test_grants_and_alerts_routes(wearos, api):
    assert api.grants({"agent": "research", "allow": True})["grants"] == \
        {"research": {"granted_at": pytest.approx(wearos.time.time(), abs=5)}}
    assert api.grants({"agent": "research", "allow": False})["grants"] == {}
    assert api.alerts({"agent": "wear", "pref": "fail"}) == {"agent": "wear",
                                                             "pref": "fail"}
    assert api.alerts({"agent": "", "pref": "bogus"}) == {
        "error": "agent and pref (all|fail|off) are required"}


def test_enable_delegates_to_the_manage_cli(wearos, api):
    fake = wearos._INVENTORY_CACHE["fake"]
    fake.calls.clear()
    out = api.enable({"profile": "chef", "enable": True})
    assert out["ok"] is True
    # eager background refresh (invalidate_inventory) may append 'list' calls —
    # filter to the mutation argv we assert on.
    mut = [c for c in fake.calls if "enable" in c or "disable" in c]
    assert mut and mut[-1] == ["/usr/bin/hermes", "-p", "chef", "plugins", "enable",
                               "wearos"]
    # owner ruling 2026-11-10: enable IS the grant — one checkbox, no second box.
    assert wearos._engine().grants().get("chef"), "enable must auto-grant"
    out2 = api.enable({"profile": "chef", "enable": False})
    mut = [c for c in fake.calls if "enable" in c or "disable" in c]
    assert mut[-1][-2:] == ["disable", "wearos"]
    assert not wearos._engine().grants().get("chef"), "disable must revoke"
    assert "--yes" not in mut[-1]                  # never --yes (review, rule 12)
    assert api.enable({"enable": True}) == {"error": "agent is required (no silent "
                                                     "all-profiles default)"}


def test_tailnet_gateway_url_is_derived_not_narrated(wearos, monkeypatch):
    """Owner ruling 2026-11-10: the Pair card shows the address the watch user
    must TYPE, computed from `tailscale status --json` (never LLM prose)."""
    import json as _json
    calls = {"n": 0}

    class Done:
        returncode = 0
        stdout = _json.dumps({"Self": {"DNSName": "Gw.example.ts.net."}}).encode()

    def fake_run(cmd, **kw):
        calls["n"] += 1
        assert cmd[1:] == ["status", "--json"]
        return Done()

    monkeypatch.setattr(wearos.shutil, "which", lambda x: "/usr/bin/tailscale")
    monkeypatch.setattr(wearos.subprocess, "run", fake_run)
    out = wearos.tailnet_gateway_url(ttl=0)
    # minimal-input law (owner 2026-11-11): the host drops the trailing .ts.net —
    # the MagicDNS-relative form is what belongs on the watch's keyboard.
    assert out == {"gateway_url": "http://gw.example.ts.net:8642",
                   "host": "gw.example"}
    assert calls["n"] == 1
    # cache: a second call inside the TTL does not re-shell
    wearos.tailnet_gateway_url()
    assert calls["n"] == 1
    monkeypatch.setattr(wearos.shutil, "which", lambda x: None)
    assert wearos.tailnet_gateway_url(ttl=0) == {"reason": "tailscale-missing"}


def test_cross_agent_bodies_key_the_target_as_agent(wearos, api):
    """The desktop's native proxy (dashboard_auth/native_http._own_profile)
    403s any body whose `profile` key names another profile — and this pane
    drives OTHER agents from the default session (owner's checkbox 403s,
    2026-11-10 19:2x). Cross-agent bodies therefore use `agent`; `profile`
    remains accepted for direct/curl back-compat but must never be the
    documented spelling."""
    fake = wearos._INVENTORY_CACHE["fake"]
    fake.rows = []
    assert api.enable({"agent": "chef", "enable": True}).get("ok")
    assert api.enable({"profile": "chef", "enable": True}).get("ok")   # back-compat
    assert api.grants({"agent": "farmer", "allow": True})["grants"].get("farmer")
    assert api.grants({"profile": "silo", "allow": True})["grants"].get("silo")


def test_enable_row_is_true_in_the_very_next_status(wearos, api):
    """Round two of 'checkbox flashes' (owner 2026-11-10 ~18:20): the pane
    refetches /status the instant the click returns; serve-stale + background
    fan-out still let the box jump BACK for seconds. After /enable ok the
    mutated row must answer TRUE from the same process, immediately — and a
    profile with no cached row yet must APPEAR, not wait for the fan-out."""
    fake = wearos._INVENTORY_CACHE["fake"]
    fake.rows = [{"name": "wearos", "status": "disabled", "version": "0.2.0",
                  "description": "d", "source": "user", "removed": None}]
    assert api.status()["profiles"][0]["enabled"] is False
    fake.rows = [{"name": "wearos", "status": "enabled", "version": "0.2.0",
                  "description": "d", "source": "user", "removed": None}]
    assert api.enable({"profile": "default", "enable": True}).get("ok")
    assert api.status()["profiles"][0]["enabled"] is True
    assert api.enable({"agent": "default", "enable": False}).get("ok")
    # disable answers from the fresh read too (the CLI above flipped back):
    fake.rows = [{"name": "wearos", "status": "disabled", "version": "0.2.0",
                  "description": "d", "source": "user", "removed": None}]
    assert api.enable({"agent": "default", "enable": False}).get("ok")
    assert api.status()["profiles"][0]["enabled"] is False


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
    # `revision` rides along with the pane-header truth fix (owner 2026-11-11):
    # the header stamps the revision the gateway ACTUALLY loaded (install
    # metadata), not a build-time string.
    assert set(feed) == {"voice", "air", "aligner", "revision"}
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

    # install: gated row + token → stored 0600; worker thread faked, no pip in
    # tests. The whoami check is a REAL huggingface.co call in shipped code —
    # the suite is no-network by contract (conftest law), so answer it locally:
    # the test asserts the store/0600 half, not the vendor's verdict.
    monkeypatch.setattr(ta, "validate_token",
                        lambda tok: {"ok": True, "user": "pane-test"})
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
