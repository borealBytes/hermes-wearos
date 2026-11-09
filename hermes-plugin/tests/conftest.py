"""Test harness for the Hermes Wear Kit plugin (protocol v2).

The plugin dir has a dotted/dashed name (`hermes-plugin`), so it is not importable by
path convention — we load it through importlib EXACTLY the way the Hermes plugin loader
does (spec_from_file_location + submodule_search_locations), which keeps the package's
`from . import tailscale` relative import working under pytest too. Exposed as
`wearos_plugin`.

The host modules the plugin borrows (hermes_constants, agent.secret_scope — all
stdlib-only at import) come from the Hermes install: already importable, else
$HERMES_AGENT_ROOT, else ~/.hermes/hermes-agent.

Every test gets a sandbox HERMES_HOME (the fcntl pairing store lands in tmp_path), a
FAKE Tailscale client (no network, ever) and a NEUTRAL tailscale-CLI view (no host
tailscaled leaks into assertions).
"""

from __future__ import annotations

import importlib.util
import os
import sys
from pathlib import Path

import pytest

_HERE = Path(__file__).resolve().parent
_PKG = _HERE.parent  # hermes-plugin/


def _ensure_hermes_on_path() -> None:
    try:
        import hermes_constants  # noqa: F401
        return
    except ImportError:
        pass
    candidates = [
        os.environ.get("HERMES_AGENT_ROOT", ""),
        str(Path.home() / ".hermes" / "hermes-agent"),
    ]
    for cand in candidates:
        if cand and (Path(cand) / "hermes_constants.py").is_file():
            sys.path.insert(0, cand)
            return
    raise RuntimeError(
        "Hermes host modules not importable — run pytest with the Hermes interpreter or "
        "point HERMES_AGENT_ROOT at the hermes-agent checkout"
    )


def _load_wearos():
    _ensure_hermes_on_path()
    spec = importlib.util.spec_from_file_location(
        "wearos_plugin", _PKG / "__init__.py", submodule_search_locations=[str(_PKG)]
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules["wearos_plugin"] = module
    spec.loader.exec_module(module)  # relative imports resolve via submodule_search_locations
    return module


_wearos = None  # session-constant: exec once, sandbox the per-test state via _CTX/HERMES_HOME


def load_plugin():
    global _wearos
    if _wearos is None:
        _wearos = _load_wearos()
    return _wearos


class FakeCtx:
    """The slice of PluginContext the plugin touches: settings only (v2 keeps its
    durable state in the fcntl pairing store, not ctx.state)."""

    def __init__(self, settings: dict):
        self._settings = dict(settings)

    def get_config(self, key: str, default=None):
        value = self._settings.get(key, default)
        return default if value is None else value

    def set_config(self, key: str, value) -> None:
        self._settings[key] = value


DEFAULT_SETTINGS = {
    "tailnet": "example.ts.example",
    "watch_tag": "tag:hermes-watch",
    "key_expiry": "24h",
    "claim_port": 42653,
}

FAKE_AUTHKEY = "tskey-FAKELABEL-0123456789abcdef"
SECRET_A = "a" * 64
SECRET_B = "b" * 64


@pytest.fixture
def wearos(tmp_path, monkeypatch):
    """The plugin bound to a sandbox HERMES_HOME (the pairing store lands in
    tmp_path), a credential that satisfies _cred() via the config settings, a FAKE
    Tailscale client, and a host-neutral tailscale CLI view."""
    module = load_plugin()
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))
    monkeypatch.setenv("tailscale_api_token", "tskey-api-test")   # _cred() fallback path
    monkeypatch.delenv("API_SERVER_KEY", raising=False)
    monkeypatch.delenv("watchkit_token", raising=False)
    def _fake_mint(self, tag, expiry="24h"):
        self.last_key_id = "key-test-id"
        return FAKE_AUTHKEY
    monkeypatch.setattr(module.tailscale.TailnetClient, "mint_enrollment_key", _fake_mint)
    monkeypatch.setattr(module.tailscale.TailnetClient, "expire_key",
                        lambda self, key_id: {"expired": key_id})
    monkeypatch.setattr(module.tailscale.TailnetClient, "delete_device",
                        lambda self, node_id: {"deleted": node_id})
    monkeypatch.setattr(module, "_gateway_fqdn", lambda: "gw.example.ts.net")
    monkeypatch.setattr(module, "_tailscale_self",
                        lambda: {"DNSName": "gw.example.ts.net",
                                 "TailscaleIPs": ["100.64.0.9"], "ID": "n-test"})
    module._CTX = FakeCtx(DEFAULT_SETTINGS)
    try:
        yield module
    finally:
        module._CTX = None


class FakeClock:
    """time.time()/sleep() replacement injected as the plugin module's `time` attr.
    The engine resolves `now` through the module global, so it flows everywhere."""

    def __init__(self, start: float = 1_700_000_000.0):
        self.now = float(start)

    def time(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.now += float(seconds)

    def advance(self, seconds: float) -> None:
        self.now += float(seconds)


@pytest.fixture
def clock(wearos, monkeypatch):
    fake = FakeClock()
    monkeypatch.setattr(wearos, "time", fake)
    return fake


@pytest.fixture
def window(wearos, clock):
    """A watch mid-flight: window open, begin done, code returned."""
    wearos.open_pairing()
    begun = wearos.door_begin(SECRET_A, "Galaxy Watch Ultra")
    return {"module": wearos, "clock": clock, "code": begun["code"],
            "secret": SECRET_A}
