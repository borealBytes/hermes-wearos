"""dashboard/plugin_api.py — the Desktop pane's real data plane (ctx.rest routes).

Proves the three answers the pane depends on, against the sandboxed plugin (no
network): /status carries every key the checklist renders, /enable performs the
same config.yaml surgery as the tool (thin here — test_enablement pins the
engine), /pin mints under the documented rules. fastapi is core's runtime, not a
plugin dependency (see plugin_api.py docstring) — importorskip keeps rule 10.

The module under test is loaded EXACTLY the way core loads it (file path, no
package convention). plugin_api finds the live wearos instance by scanning
sys.modules for this package's __init__ — which conftest already loaded — so no
second copy of the plugin is ever exec'd here.
"""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import pytest

from conftest import load_plugin

pytest.importorskip("fastapi", reason="core runtime module; plugin adds no pip deps")

_API = Path(__file__).resolve().parent.parent / "dashboard" / "plugin_api.py"


@pytest.fixture
def api(monkeypatch):
    module = load_plugin()
    # The checklist hits the Tailscale API live; the pane shape is what's under test.
    monkeypatch.setattr(module, "tailnet_state", lambda: {"tailscaled": {"ok": True, "detail": "fake"}})
    # _door_listening() probes 127.0.0.1:<claim_port> — on a real gateway box a LIVE
    # door answers and breaks isolation (measured). The route just reports it; pin it.
    monkeypatch.setattr(module, "_door_listening", lambda: False)
    spec = importlib.util.spec_from_file_location("wearos_plugin_api", _API)
    api_mod = importlib.util.module_from_spec(spec)
    sys.modules["wearos_plugin_api"] = api_mod
    spec.loader.exec_module(api_mod)
    yield api_mod
    sys.modules.pop("wearos_plugin_api", None)


def test_wearos_resolver_finds_the_loaded_instance(wearos, api):
    # Production semantics: these routes only answer for a REGISTERED plugin
    # (core gates plugin_api import on plugins.enabled anyway). The bound _CTX
    # is what distinguishes the live module from any file-alias copy.
    assert api._wearos() is load_plugin(), "must reuse the live module — shared _CTX/door/ledger"


def test_status_shape(wearos, api):
    payload = api.status()
    assert set(payload) == {"checks", "devices", "profiles", "apk_release_url", "door"}
    assert payload["devices"] == {}  # clean sandbox
    # Empty sandbox: default (root config, no file yet) and nothing else; no
    # install-metadata → no pin claims at all.
    assert payload["profiles"] == [{"name": "default", "enabled": False, "has_config": False,
                                    "pin": None, "own_copy": False, "update_available": False}]
    assert payload["checks"]["tailscaled"]["ok"] is True
    assert payload["apk_release_url"].startswith("https://")
    assert payload["door"] == {"listening": False}  # door not started in tests


def test_inventory_reports_stale_per_agent_pins(wearos, api, tmp_path):
    """Core freezes a per-profile install at its copied SHA; the pane must show it."""
    root_rev = "cafe1234" + "0" * 32
    own_rev = "deadbeef" + "1" * 32
    root_meta = tmp_path / "plugins"
    (root_meta).mkdir()
    (root_meta / ".install-metadata.json").write_text(
        json.dumps({"wearos": {"revision": root_rev, "pinned": True}}), encoding="utf-8")
    ls = tmp_path / "profiles" / "lightshow"          # stale own copy (identity marker needed)
    (ls / "plugins").mkdir(parents=True)
    (ls / "config.yaml").write_text("model: x\n", encoding="utf-8")
    (ls / "plugins" / ".install-metadata.json").write_text(
        json.dumps({"wearos": {"revision": own_rev, "pinned": True}}), encoding="utf-8")
    fresh = tmp_path / "profiles" / "upwork"          # own copy AT root rev → not stale
    (fresh / "plugins").mkdir(parents=True)
    (fresh / "config.yaml").write_text("model: x\n", encoding="utf-8")
    (fresh / "plugins" / ".install-metadata.json").write_text(
        json.dumps({"wearos": {"revision": root_rev, "pinned": True}}), encoding="utf-8")

    by_name = {p["name"]: p for p in api.status()["profiles"]}
    assert by_name["default"] == {"name": "default", "enabled": False, "has_config": False,
                                  "pin": "cafe1234", "own_copy": False, "update_available": False}
    assert by_name["lightshow"]["update_available"] is True and by_name["lightshow"]["pin"] == "deadbeef"
    assert by_name["lightshow"]["own_copy"] is True
    assert by_name["upwork"]["update_available"] is False and by_name["upwork"]["pin"] == "cafe1234"


def test_enable_round_trip(wearos, api, tmp_path):
    # Real gates, no monkeypatching: profiles/<name>/config.yaml is the identity
    # marker _named_profile_dirs demands, inside the fixture's sandbox HERMES_HOME.
    (tmp_path / "profiles" / "chef").mkdir(parents=True)
    cfg = tmp_path / "profiles" / "chef" / "config.yaml"
    cfg.write_text("model: x\n", encoding="utf-8")
    answer = api.enable({"agents": "chef", "enable": True})
    assert answer["enable"] is True
    assert answer["results"] == [{"name": "chef", "before": False, "after": True, "changed": True}]
    assert wearos._wearos_enabled(cfg.read_text(encoding="utf-8"))
    answer2 = api.enable({"agents": ["chef"], "enable": False})
    assert answer2["results"][0]["changed"] is True
    assert not wearos._wearos_enabled(cfg.read_text(encoding="utf-8"))


def test_pin_mints(wearos, api):
    answer = api.pin()
    assert len(str(answer["pin"])) == 6 and str(answer["pin"]).isdigit()
    assert answer["ttl_seconds"] == wearos.PIN_TTL_SECONDS
