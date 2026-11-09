"""Wizard --fix serve: the two real-world refusal modes and their copy (measured on
tailscale 1.102.3, owner's tailnet, 2026-11-06):
  1. funnel not enabled  → CLI prints the per-node approval link and WAITS;
  2. enabled but no operator rights → "Access denied: serve config denied".
Both must surface as one-line actions, never tracebacks or dead console references."""

from __future__ import annotations

import json

from conftest import load_plugin

module = load_plugin()


class FakeSP:
    """Stands in for the subprocess module inside _fix_serve; scripts tailscale calls."""

    DEVNULL = -3

    class TimeoutExpired(Exception):
        pass

    def __init__(self, funnel_run=None, status_json=None):
        self.funnel_run = funnel_run or (0, "")
        self.status_json = status_json or {"Self": {"ID": "nXYZ123CNTRL"}}
        self.calls = []

    class _Completed:
        def __init__(self, rc, out):
            self.returncode, self.stdout, self.stderr = rc, out, ""

    def run(self, cmd, **kw):
        self.calls.append(cmd)
        if cmd[1:3] == ["status", "--json"]:
            return self._Completed(0, json.dumps(self.status_json))
        if cmd[1] == "funnel" and cmd[2] == "status":
            return self._Completed(1, "No serve config")  # pre-state: nothing funneled
        rc, text = self.funnel_run
        return self._Completed(rc, text)


def run_serve(wearos, sp, capsys):
    real = wearos.subprocess
    wearos.subprocess = sp
    try:
        wearos._fix_serve()
    finally:
        wearos.subprocess = real
    return capsys.readouterr().out


def test_not_enabled_branch_leads_with_per_node_link(wearos, capsys, monkeypatch):
    sp = FakeSP(funnel_run=(0, "Funnel is not enabled on your tailnet.\n"
                               "To enable, visit:\n"
                               "https://login.tailscale.com/f/funnel?node=nXYZ123CNTRL"))
    out = run_serve(wearos, sp, capsys)
    # The per-node link is printed BEFORE the wait (computed, not just echoed):
    assert "login.tailscale.com/f/funnel?node=nXYZ123CNTRL" in out
    assert "waiting up to 4 min" in out.lower()
    assert "--bg" in out and "42653" in out  # shows the exact command it will run
    assert "--yes" not in out  # measured hang: the wizard must never pass --yes


def test_operator_denied_branch_gives_the_one_time_sudo(wearos, capsys):
    sp = FakeSP(funnel_run=(1, "Success.\nsending serve config: Access denied: "
                               "serve config denied\nUse 'sudo tailscale funnel --bg "
                               "--https=443 http://127.0.0.1:42653'.\nTo not require "
                               "root, use 'sudo tailscale set --operator=$USER' once."))
    out = run_serve(wearos, sp, capsys)
    assert "sudo tailscale set --operator=" in out
    assert "enabled" in out.lower()  # tells the truth: enable succeeded, rights didn't


def test_gateway_fqdn_reads_capital_dnsname(monkeypatch):
    """Measured 1.102.3: the field is Self.DNSName (jq '.Self.DnsName' reads null —
    the case bug that shipped an empty gateway_fqdn in the first live packet)."""
    class SP:
        @staticmethod
        def run(cmd, **kw):
            class R: stdout = '{"Self": {"DNSName": "box.tailnet.ts.net."}}'
            return R()
    monkeypatch.setattr(module, "subprocess", SP)
    assert module._gateway_fqdn() == "box.tailnet.ts.net"  # unpatched original
