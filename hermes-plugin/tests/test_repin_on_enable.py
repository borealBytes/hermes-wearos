"""Enable must land CURRENT bytes (owner fresh-person test 2026-11-11).

`hermes -p X plugins enable wearos` flips a flag on whatever revision the
profile ALREADY has, and the catalog pin can lag the release while its PR is
open — one box carried five different wearos revisions. The checkbox therefore
force-syncs the target to the pane's own revision after a successful enable,
and says so in the detail (no silent upgrades either)."""

from __future__ import annotations

import json
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


def test_enable_repins_a_drifted_profile(wearos, monkeypatch, tmp_path):
    calls = []
    _seed_pins(tmp_path, CANON, "target", STALE)
    _wire(wearos, monkeypatch, tmp_path, calls)
    res = wearos.delegate_enablement("target", True)
    assert res["ok"] is True
    assert "re-pinned" in res["detail"] and f"{STALE[:7]}→{CANON[:7]}" in res["detail"]
    inst = [c for c in calls if "install" in c][-1]
    assert "--force" in inst and "--yes" in inst
    assert inst[inst.index("--ref") + 1] == CANON           # exact bytes, no roulette
    assert wearos._profile_pin("target") == CANON


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
    payload = json.dumps({"Self": {"DNSName": "tuxdesktop.tailb12aa5.ts.net."}}).encode()
    monkeypatch.setattr(wearos.shutil, "which", lambda name: "/usr/bin/tailscale")
    monkeypatch.setattr(wearos.subprocess, "run",
                        lambda argv, **kw: type("D", (), {
                            "returncode": 0, "stdout": payload, "stderr": b""})())
    wearos._TAILNET_CACHE.update(stamp=0.0, out={})          # force a refresh
    info = wearos.tailnet_gateway_url(ttl=0)
    assert info["host"] == "tuxdesktop.tailb12aa5"
    assert info["gateway_url"] == "http://tuxdesktop.tailb12aa5.ts.net:8642"
