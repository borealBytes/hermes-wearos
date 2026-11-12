"""Enable must land CURRENT bytes (owner fresh-person test 2026-11-11).

`hermes -p X plugins enable wearos` flips a flag on whatever revision the
profile ALREADY has, and the catalog pin can lag the release while its PR is
open — one box carried five different wearos revisions. The checkbox therefore
force-syncs the target to the pane's own revision after a successful enable,
and says so in the detail (no silent upgrades either)."""

from __future__ import annotations

import json
import types

import pytest
from pathlib import Path

CANON = "d2bc4175604abc3dcc35da25e8907117e6ce1dea"
STALE = "628f28a200000000000000000000000000000000"


class _Done:
    def __init__(self, rc=0, out=""):
        self.returncode, self.stdout, self.stderr = rc, out, ""


def _seed_pins(home: Path, default_rev: str, profile: str, profile_rev):
    (home / "plugins").mkdir(parents=True, exist_ok=True)
    (home / "plugins" / ".install-metadata.json").write_text(json.dumps(
        {"wearos": {"source": "https://github.com/borealBytes/hermes-wearos-plugin.git",
                    "revision": default_rev}}))
    if profile_rev is not None:
        pdir = home / "profiles" / profile / "plugins"
        pdir.mkdir(parents=True, exist_ok=True)
        (pdir / ".install-metadata.json").write_text(json.dumps(
            {"wearos": {"source": "https://github.com/borealBytes/hermes-wearos-plugin.git",
                        "revision": profile_rev}}))


def _wire(wearos, monkeypatch, tmp_path, calls):
    def fake_run(argv, **kw):
        calls.append(list(argv))
        if "enable" in argv or "disable" in argv:
            return _Done(0, "✓ Plugin wearos enabled.")
        if "install" in argv:  # the re-pin — lands the revision it is given
            _seed_pins(tmp_path, CANON, "target", argv[argv.index("--ref") + 1])
            return _Done(0, "installed")
        return _Done(1, "unexpected argv")
    monkeypatch.setattr(wearos.shutil, "which", lambda name: "/usr/bin/hermes")
    monkeypatch.setattr(wearos.subprocess, "run", fake_run)


def test_enable_reports_drift_without_touching_it(wearos, monkeypatch, tmp_path):
    """Review round 2 contract: enable NEVER mutates a foreign profile's
    revision — drift is REPORTED with the exact update command (consent is
    core's job, and a forced --yes re-pin could downgrade a newer pin)."""
    calls = []
    _seed_pins(tmp_path, CANON, "target", STALE)
    _wire(wearos, monkeypatch, tmp_path, calls)
    res = wearos.delegate_enablement("target", True)
    assert res["ok"] is True
    assert STALE[:7] in res["detail"] and CANON[:7] in res["detail"]
    assert "hermes -p target plugins update wearos" in res["detail"]
    assert not any("install" in c for c in calls)            # no re-pin subprocess
    assert wearos._profile_pin("target") == STALE            # bytes untouched


def test_never_worn_profile_gets_the_install_command(wearos, monkeypatch, tmp_path):
    """No metadata == no install consent ever given there. core errors on
    enable ('No plugin named'); the answer must be the EXACT install command
    — never a --yes run on the user's behalf (review round 2)."""
    calls = []
    _seed_pins(tmp_path, CANON, "target", None)
    def fake_run(argv, **kw):
        calls.append(list(argv))
        return _Done(1, "Error: No plugin named wearos")
    monkeypatch.setattr(wearos.shutil, "which", lambda name: "/usr/bin/hermes")
    monkeypatch.setattr(wearos.subprocess, "run", fake_run)
    res = wearos.delegate_enablement("target", True)
    assert res["ok"] is False and res["verb"] == "install"
    assert "plugins install" in res["detail"] and CANON in res["detail"]
    assert not any("install" in c for c in calls)   # command handed over, not run


def test_current_profile_is_left_alone(wearos, monkeypatch, tmp_path):
    calls = []
    _seed_pins(tmp_path, CANON, "target", CANON)
    _wire(wearos, monkeypatch, tmp_path, calls)
    res = wearos.delegate_enablement("target", True)
    assert res["ok"] is True
    assert not any("install" in c for c in calls)
    assert "re-pinned" not in res["detail"]


def test_failed_enable_reports_no_repinnote_and_no_crash(wearos, monkeypatch, tmp_path):
    _seed_pins(tmp_path, CANON, "target", STALE)
    monkeypatch.setattr(wearos.shutil, "which", lambda name: "/usr/bin/hermes")
    monkeypatch.setattr(wearos.subprocess, "run",
                        lambda argv, **kw: _Done(2, "permission denied"))
    res = wearos.delegate_enablement("target", True)
    assert res["ok"] is False                                # NameError guard
    assert "re-pinned" not in res["detail"]


def test_pair_host_drops_the_dot_net_suffix(wearos, monkeypatch, tmp_path):
    """minimal-input law: the pane shows device.tailnet, not the .ts.net FQDN."""
    import io
    payload = json.dumps({"Self": {"DNSName": "Gw.example.ts.net."}}).encode()
    monkeypatch.setattr(wearos.shutil, "which", lambda name: "/usr/bin/tailscale")
    monkeypatch.setattr(wearos.subprocess, "run",
                        lambda argv, **kw: type("D", (), {
                            "returncode": 0, "stdout": payload, "stderr": b""})())
    wearos._TAILNET_CACHE.update(stamp=0.0, out={})          # force a refresh
    info = wearos.tailnet_gateway_url(ttl=0)
    assert info["host"] == "gw.example"
    assert info["gateway_url"] == "http://gw.example.ts.net:8642"


def test_unchecking_the_host_profile_revokes_the_grant_without_kill(monkeypatch):
    """OWNER RULING 2026-11-11 (v0.3.3): the checkbox is WATCH PRESENCE. The
    host's own uncheck must revoke the grant, NOT disable the plugin that hosts
    the pane — and must not delegate a disable down the CLI lane."""
    import importlib.util
    import sys
    from pathlib import Path
    api_path = Path(__file__).resolve().parent.parent / "dashboard" / "plugin_api.py"
    pytest.importorskip("fastapi")
    spec = importlib.util.spec_from_file_location("wearos_plugin_api_rpin", api_path)
    api = importlib.util.module_from_spec(spec)
    sys.modules["wearos_plugin_api_rpin"] = api
    spec.loader.exec_module(api)
    try:
        seen, grants = [], []
        fake_engine = types.SimpleNamespace(set_grant=lambda p, a: grants.append((p, a)))
        monkeypatch.setattr(api, "_launch_profile", lambda: "default")
        monkeypatch.setattr(api, "_p", lambda: types.SimpleNamespace(
            _engine=lambda: fake_engine,
            delegate_enablement=lambda p, e: seen.append((p, e)) or {"name": p, "ok": True},
            refresh_inventory_row=lambda p: None))
        out = api.enable({"agent": "default", "enable": False})
        assert out["ok"] is True and not seen                    # no CLI disable
        assert grants == [("default", False)]                    # presence revoked
        assert "removed from the watch" in out["detail"] and "Plugins page" in out["detail"]
        out2 = api.enable({"agent": "wear-demo", "enable": False})
        assert out2.get("ok") is True and seen == [("wear-demo", False)]  # others still delegate
        assert grants == [("default", False), ("wear-demo", False)]       # uncheck revokes, always
        out3 = api.enable({"agent": "default", "enable": True})
        assert out3.get("ok") is True and seen[-1] == ("default", True)   # re-check = full path
        assert grants[-1] == ("default", True)                            # re-check re-grants
    finally:
        sys.modules.pop("wearos_plugin_api_rpin", None)


def test_launch_profile_reads_the_home_scope(wearos, monkeypatch, tmp_path):
    import importlib.util
    import sys
    from pathlib import Path
    pytest.importorskip("fastapi")
    api_path = Path(__file__).resolve().parent.parent / "dashboard" / "plugin_api.py"
    spec = importlib.util.spec_from_file_location("wearos_plugin_api_scope", api_path)
    api = importlib.util.module_from_spec(spec)
    sys.modules["wearos_plugin_api_scope"] = api
    spec.loader.exec_module(api)
    try:
        monkeypatch.setenv("HERMES_HOME", str(tmp_path))
        assert api._launch_profile() == "default"
        nested = tmp_path / "profiles" / "wear"
        nested.mkdir(parents=True)
        monkeypatch.setenv("HERMES_HOME", str(nested))
        assert api._launch_profile() == "wear"
    finally:
        sys.modules.pop("wearos_plugin_api_scope", None)
