"""Hermes Wear Kit — the Desktop pane's own data plane (`/api/plugins/wearos/*`).

Measured against the installed core (v0.23.0), why this file exists at all: the
desktop SDK's `host` has NO tool-call door (`apps/desktop/src/sdk/index.ts`; the
gateway RPC table has no generic tools.call either — plugin *tools* only answer
inside an agent turn), but every plugin gets a REST door — pane-side
`ctx.rest('/status')` reaches `GET /api/plugins/wearos/status`
(`apps/desktop/src/api/plugins.ts` pluginRest, and core's own
`tests/hermes_cli/test_plugin_api_hot_mount.py` proves the mount is HOT: the
first request after install serves, no app restart). So the pane reads/writes
through these routes instead of the (tool) door the first pane draft guessed at.

Handlers are `def`, not `async def` — FastAPI sends sync handlers to its
threadpool, which is what `tailnet_state()` (urllib to the Tailscale API) wants.

Rule 10 nuance: `fastapi` is core's own runtime (the gateway that imports this
file runs on it) — the plugin still adds zero pip deps; pytest importorskip's it.
Routes run in the gateway process, so the live plugin instance — `_CTX` state,
the PIN ledger, the claim door — is in this same interpreter: we find it by file
identity and only self-import as a last resort.
"""
from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

from fastapi import APIRouter

router = APIRouter()

# This file lives at <plugin_dir>/dashboard/, and the repo layout is the install
# layout (hermes-plugin/ == ~/.hermes/plugins/wearos/), so the scan target is
# identical in pytest, in the gateway, and in the dashboard test client.
_PLUGIN_DIR = Path(__file__).resolve().parent.parent


def _wearos():
    """The gateway's already-loaded wearos module (shared _CTX/door/ledger)."""
    expected = str(_PLUGIN_DIR / "__init__.py")
    candidates = [m for m in list(sys.modules.values())
                  if str(getattr(m, "__file__", "") or "") == expected]
    # More than one dict can map to the same file (pytest's rewriter registers a
    # second under the bare "__init__"). The LIVE one is distinguishable:
    # register() bound _CTX. Prefer it; fall back to any match, then load fresh.
    live = [m for m in candidates if getattr(m, "_CTX", None) is not None]
    pool = live or candidates
    if pool:
        return pool[0]
    spec = importlib.util.spec_from_file_location(
        "wearos", _PLUGIN_DIR / "__init__.py",
        submodule_search_locations=[str(_PLUGIN_DIR)],
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules["wearos"] = module
    spec.loader.exec_module(module)  # relative imports resolve via submodule_search_locations
    return module


@router.get("/status")
def status():
    """The exact JSON `watch_status` gives the agent — plus the claim door's
    listening bit, cheap here and load-bearing for the pane's step 3/4 copy."""
    p = _wearos()
    payload = json.loads(p._tool_watch_status({}))
    payload["door"] = {"listening": bool(p._door_listening())}
    return payload


@router.post("/enable")
def enable(body: dict):
    """One checkbox = one call = the same line-level config.yaml patch the CLI
    and the `watch_enable` tool run ({agents: "all" | "a,b" | [..], enable: bool})."""
    return json.loads(_wearos()._tool_watch_enable({
        "agents": body.get("agents", "all"),
        "enable": bool(body.get("enable", True)),
    }))


@router.get("/pin")
def pin():
    """Mint a pairing PIN plugin-side (same 5-minute, single-use rules as the
    watch's own screen)."""
    return json.loads(_wearos()._tool_watch_pair({}))
