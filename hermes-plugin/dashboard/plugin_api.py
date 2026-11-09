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
threadpool, which is what `local_checks()` (subprocess to the tailscale CLI)
wants. Routes run in the gateway process, so the live plugin instance — the
pairing engine, the claim door, the private lane — is in this same interpreter:
we find it by file identity and only self-import as a last resort (under a name
of OUR OWN — never a bare "wearos" key in the host's module registry, review
fix, rule 9).

The pane is a login-protected surface (the dashboard's own auth middleware wraps
/api/*), which is what makes POST /pair/claim and POST /revoke legitimate
approval actions next to the typed CLI claim.
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
_SELF_NAME = "hermes_wearos_dashboard_self"  # OUR key in sys.modules, never "wearos"


def _wearos():
    """The gateway's already-loaded wearos module (shared engine/door/lane)."""
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
    existing = sys.modules.get(_SELF_NAME)
    if existing is not None and str(getattr(existing, "__file__", "")) == expected:
        return existing
    spec = importlib.util.spec_from_file_location(
        _SELF_NAME, _PLUGIN_DIR / "__init__.py",
        submodule_search_locations=[str(_PLUGIN_DIR)],
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[_SELF_NAME] = module  # our own key; the host's namespace is untouched
    spec.loader.exec_module(module)  # relative imports resolve via submodule_search_locations
    return module


def _p():
    return _wearos()


# ── status ─────────────────────────────────────────────────────────────────────
@router.get("/status")
def status(api: int = 0):
    """The exact JSON `watch_status` gives the agent — door/lane bits included."""
    payload = json.loads(_p()._tool_watch_status({"api": bool(api)}))
    payload["door"] = {"listening": bool(_p()._door_listening())}
    return payload


# ── pairing (open window → watch shows code → pane/CLI types it) ──────────────
@router.post("/pair/window")
def pair_window(body: dict):
    action = str(body.get("action", "open")).lower()
    if action == "close":
        _p()._engine().close_window()
        return {"window": None}
    return _p().pairing_state() | {"opened": _p().open_pairing()}


@router.get("/pair/pending")
def pair_pending():
    return _p().pairing_state()


@router.post("/pair/claim")
def pair_claim(body: dict):
    """The pane's Approve button — a logged-in human typed the code; the dashboard's
    auth middleware is the consent. Surfaces without a login (messaging) can't claim."""
    try:
        approved = _p().approve_pair(str(body.get("code", "")))
    except ValueError as exc:  # pairing.PairingError is a ValueError
        return {"error": str(exc), "lock": _p()._engine().claim_locked()}
    return {"approved": approved,
            "note": "the watch collects its key on its next poll (≤5 s)"}


# ── devices / revoke (D12) ─────────────────────────────────────────────────────
@router.get("/devices")
def devices():
    return {"devices": _p()._engine().devices(), "grants": _p()._engine().grants()}


@router.post("/devices/revoke")
def devices_revoke(body: dict):
    """Full removal. The pane shows the preview (tailnet delete + key rotation + the
    default-profile restart warning) and only calls this after the human confirms —
    the button IS the approval (rule 12)."""
    name = str(body.get("name", "")).strip()
    if not name:
        return {"error": "name is required"}
    return _p().revoke_device(name, started_from="pane")


# ── per-agent key grants (D17) ─────────────────────────────────────────────────
@router.post("/grants")
def grants(body: dict):
    profile = str(body.get("profile", "")).strip().lower()
    if not profile:
        return {"error": "profile is required"}
    return {"grants": _p()._engine().set_grant(profile, bool(body.get("allow", True)))}


# ── cron alerts (D14) ──────────────────────────────────────────────────────────
@router.post("/alerts")
def alerts(body: dict):
    profile = str(body.get("profile", "")).strip().lower()
    pref = str(body.get("pref", "all")).strip().lower()
    if not profile or pref not in ("all", "fail", "off"):
        return {"error": "profile and pref (all|fail|off) are required"}
    return {"profile": profile, "pref": _p()._engine().set_alert_pref(profile, pref)}


# ── enablement (review fix: delegate to `hermes -p <profile> plugins enable`,
#    never a hand-rolled config.yaml editor) ────────────────────────────────────
@router.post("/enable")
def enable(body: dict):
    profile = str(body.get("profile", "")).strip().lower()
    if not profile:
        return {"error": "profile is required (no silent all-profiles default)"}
    return _p().delegate_enablement(profile, bool(body.get("enable", True)))


# ── streaming-STT install (D16) ────────────────────────────────────────────────
@router.get("/stt")
def stt():
    stt_install = _p().stt_install
    return stt_install.status() | {"preview": stt_install.preview()}


@router.post("/stt")
def stt_post(body: dict):
    stt_install = _p().stt_install
    action = str(body.get("action", "install")).lower()
    if action == "cancel":
        return stt_install.cancel()
    if action == "select":
        # quant menu choice (same engine, different weights) — persists, no install
        return stt_install.select_variant(str(body.get("variant", "")))
    # The pane IS the interactive surface + the consent dialog (preview shown first).
    return stt_install.start(interactive=True)


# ── voice-out (SPEC §19, slice 3) ───────────────────────────────────────────────
# The pane's door to the same engine the watch lane drives. One surface, two
# doors: the lane speaks for devices (node-law auth), these routes speak for the
# logged-in human. Rule 13: consent links and token capture live HERE, in a
# surface the owner can audit; rule 12: nothing installs without the button.

def _voice_mods():
    import importlib
    w = _p()
    return (importlib.import_module(w.__name__ + ".voiceout"),
            importlib.import_module(w.__name__ + ".tts_air"),
            importlib.import_module(w.__name__ + ".align_parakeet"))


@router.get("/voice")
def voice():
    vo, ta, al = _voice_mods()
    return {"voice": vo.status(),
            "air": ta.status() | {"sources": ta.sources_menu(), "slots": ta._slots(),
                                  "preview": ta.preview()},
            "aligner": al.status()}


@router.post("/voice")
def voice_post(body: dict):
    import os
    vo, ta, _al = _voice_mods()
    action = str(body.get("action", "")).lower()
    if action == "select":
        return ta.select_source(str(body.get("source") or ""),
                                str(body.get("for") or "both"))
    if action == "install":
        tok = str(body.get("token") or "").strip()
        if tok:                                   # paste box: store 0600, never echo
            tf = ta._root() / "hf_token"
            tf.parent.mkdir(parents=True, exist_ok=True)
            tf.write_text(tok)
            os.chmod(tf, 0o600)
        return ta.start(interactive=True, source=str(body.get("source") or "") or None,
                        for_slot=str(body.get("for") or "gateway"))
    if action == "cancel":
        return ta.cancel()
    if action == "soundtest":
        import base64
        r = vo.synthesize(str(body.get("text") or "")[:4000],
                          str(body.get("agent") or "default"), want_words=False)
        if isinstance(r.get("audio"), (bytes, bytearray)):
            r["audio_wav_b64"] = base64.b64encode(r.pop("audio")).decode()
        return r
    if action == "speed":
        return vo.set_speed(float(body.get("speed", 1.0)))
    if action == "karaoke":
        return vo.set_karaoke(bool(body.get("karaoke", True)))
    if action == "voice":                       # per-agent default card
        return vo.set_voice(str(body.get("agent") or "default"),
                            str(body.get("card") or ""))
    if action == "mode":
        return vo.set_mode(str(body.get("agent") or "default"),
                           str(body.get("mode") or "gateway"))
    if action == "card_delete":
        return vo.delete_card(str(body.get("card") or ""))
    return {"error": f"unknown voice action {action!r} — select|install|cancel|"
                     "soundtest|speed|karaoke|voice|mode|card_delete"}
