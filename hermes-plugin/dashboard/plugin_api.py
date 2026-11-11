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
import os
import re
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


def _launch_profile() -> str:
    """Name of the profile whose config mounted THIS plugin instance — the
    pane's host. The gateway process's HERMES_HOME is its launch scope: the
    root home is 'default', a profiles/<name> home is that named profile."""
    home = str(Path(os.environ.get("HERMES_HOME",
                                   str(Path.home() / ".hermes"))).resolve())
    m = re.search(r"[\\/]profiles[\\/]([^\\/]+)$", home)
    return m.group(1) if m else "default"


# ── status ─────────────────────────────────────────────────────────────────────
@router.get("/status")
def status(api: int = 0):
    """The exact JSON `watch_status` gives the agent — door/lane bits included."""
    payload = json.loads(_p()._tool_watch_status({"api": bool(api)}))
    payload["door"] = {"listening": bool(_p()._door_listening())}
    # the address to TYPE INTO THE WATCH, derived live (owner 2026-11-10):
    payload["tailnet"] = _p().tailnet_gateway_url()
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
# NOTE body-key law (owner pane 403s, 2026-11-10): the desktop's native proxy
# (dashboard_auth/native_http._own_profile) 403s ANY JSON body whose `profile`
# key names a profile other than the session's scope — and this pane drives
# OTHER agents from the default session. Cross-agent bodies therefore key the
# target as `agent`; `profile` is still accepted for back-compat (direct curl,
# older panes), never preferred.
@router.post("/grants")
def grants(body: dict):
    profile = str(body.get("agent") or body.get("profile", "")).strip().lower()
    if not profile:
        return {"error": "agent is required"}
    return {"grants": _p()._engine().set_grant(profile, bool(body.get("allow", True)))}


# ── cron alerts (D14) ──────────────────────────────────────────────────────────
@router.post("/alerts")
def alerts(body: dict):
    profile = str(body.get("agent") or body.get("profile", "")).strip().lower()
    pref = str(body.get("pref", "all")).strip().lower()
    if not profile or pref not in ("all", "fail", "off"):
        return {"error": "agent and pref (all|fail|off) are required"}
    return {"agent": profile, "pref": _p()._engine().set_alert_pref(profile, pref)}


# ── enablement (review fix: delegate to `hermes -p <profile> plugins enable`,
#    never a hand-rolled config.yaml editor) ────────────────────────────────────
@router.post("/enable")
def enable(body: dict):
    profile = str(body.get("agent") or body.get("profile", "")).strip().lower()
    if not profile:
        return {"error": "agent is required (no silent all-profiles default)"}
    if not bool(body.get("enable", True)) and profile == _launch_profile():
        # OWNER RULING 2026-11-11 (v0.3.3, supersedes the v0.3.2 refusal): the
        # checkbox means WATCH PRESENCE, not "kill the plugin". Unchecking the
        # host's own box revokes its grant — the agent leaves the watch — while
        # Wear Kit keeps running here to host this pane and the watch lane. The
        # full off-switch is the Plugins page toggle (or the CLI); it stays
        # honest because THAT control names what it does.
        _p()._engine().set_grant(profile, False)
        _p().refresh_inventory_row(profile)
        return {"name": profile, "ok": True, "verb": "enable",
                "detail": "removed from the watch — Wear Kit still runs here (hosts this pane); "
                          "full disable lives on the Plugins page"}
    res = _p().delegate_enablement(profile, bool(body.get("enable", True)))
    if res.get("ok"):
        # owner ruling 2026-11-10: enabling IS the whole decision — the grant
        # (watch may use this agent's key) rides with it; no second checkbox.
        _p()._engine().set_grant(profile, bool(body.get("enable", True)))
        # the row just changed: re-read THAT row synchronously (one CLI) and
        # splice it — the pane's very next fetch sees its own click, no flash.
        # The full fan-out invalidation still runs behind it for the others.
        _p().refresh_inventory_row(profile)
    return res


# ── streaming-STT install (D16) ────────────────────────────────────────────────
def _stt_mod():
    # importlib, never the attribute: `_p()` may hand us the self-import identity
    # (fresh mount / OOM-restart before the host bound the package), and a package
    # attribute only exists once somebody imported the submodule — an
    # import-ORDER lottery that broke the pane's STT card on the live box
    # (AttributeError 2026-11-10). Same pattern as _voice_mods, same lesson.
    import importlib
    w = _p()
    return importlib.import_module(w.__name__ + ".stt_install")


@router.get("/stt")
def stt():
    stt_install = _stt_mod()
    return stt_install.status() | {"preview": stt_install.preview()}


@router.post("/stt")
def stt_post(body: dict):
    stt_install = _stt_mod()
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

def _voice_mods(ensure: bool = False):
    import importlib
    w = _p()
    if ensure:
        w.ensure_engines()       # lazy heal: a missed startup probe must not strand
                                 # the button (owner report 2026-11-10)
    return (importlib.import_module(w.__name__ + ".voiceout"),
            importlib.import_module(w.__name__ + ".tts_air"),
            importlib.import_module(w.__name__ + ".align_parakeet"))


def _installed_revision() -> str:
    """The revision the gateway ACTUALLY loaded (install-metadata pin), so the
    pane header can stamp truth instead of a build-time string that lies after
    the next commit (owner 2026-11-11: 'I still see 7A2129' — static stamps do
    that)."""
    try:
        import json, os
        p = os.path.join(os.path.expanduser("~"), ".hermes", "plugins",
                         ".install-metadata.json")
        with open(p, encoding="utf-8") as f:
            return str(json.load(f).get("wearos", {}).get("revision", ""))[:8]
    except Exception:
        return ""


@router.get("/voice")
def voice():
    vo, ta, al = _voice_mods(ensure=True)
    return {"revision": _installed_revision(),
            "voice": vo.status(),
            "air": ta.status() | {"sources": ta.sources_menu(), "slots": ta._slots(),
                                  "preview": ta.preview(), "engine": ta.engine_state(),
                                  "devices": ta.devices()},
            "aligner": al.status()}


@router.post("/voice")
def voice_post(body: dict):
    import os
    vo, ta, _al = _voice_mods(ensure=True)
    action = str(body.get("action", "")).lower()
    if action == "select":
        return ta.select_source(str(body.get("source") or ""),
                                str(body.get("for") or "both"))
    if action == "install":
        tok = str(body.get("token") or "").strip()
        if tok:                                   # paste box: validated, 0600, never echo
            v = ta.store_token(tok)
            if not v.get("ok"):
                return {"error": f"token {v.get('error', 'rejected')}"}
        return ta.start(interactive=True, source=str(body.get("source") or "") or None,
                        for_slot=str(body.get("for") or "gateway"))
    if action == "cancel":
        return ta.cancel()
    if action == "soundtest":
        import base64
        try:
            r = vo.synthesize(str(body.get("text") or "")[:4000],
                              str(body.get("agent") or "default"), want_words=False)
        except Exception as exc:
            # A button never answers 500: the pane shows this string in its red
            # note line (honesty rule; owner hit exactly this 2026-11-10).
            r = {"ok": False, "error": str(exc)[:300]}
        if isinstance(r.get("audio"), (bytes, bytearray)):
            r["audio_wav_b64"] = base64.b64encode(r.pop("audio")).decode()
        return r
    if action == "engine_devices":
        return {"ok": True, "devices": ta.devices()}
    if action == "set_engine_device":
        # The pick IS the consent (rule 12): a GPU choice starts the one-time
        # CUDA variant build; the engine keeps speaking on CPU meanwhile and
        # rebinds the worker the moment the build is done. SPEC 19.4(m).
        return ta.set_device(str(body.get("device") or "cpu"))
    if action == "speed":
        return vo.set_speed(float(body.get("speed", 1.0)))
    if action == "karaoke":
        return vo.set_karaoke(bool(body.get("karaoke", True)))
    if action == "chunk_mode":                    # sentence | paragraph (v0.3.4)
        return vo.set_chunk_mode(str(body.get("value", "")))
    if action == "voice":                       # per-agent default card
        return vo.set_voice(str(body.get("agent") or "default"),
                            str(body.get("card") or ""))
    if action == "mode":
        return vo.set_mode(str(body.get("agent") or "default"),
                           str(body.get("mode") or "gateway"))
    if action == "card_create":
        import base64
        import logging
        try:
            blob = base64.b64decode(str(body.get("wav_b64") or ""), validate=True)
            if body.get("gzip"):
                import gzip
                blob = gzip.decompress(blob)
            logging.getLogger("hermes.wearos").warning(
                "voice card_create: %s label=%r b64=%dKB wav=%dKB gzip=%s",
                "ok", str(body.get("label") or "")[:24],
                len(str(body.get("wav_b64") or "")) // 1024, len(blob) // 1024,
                bool(body.get("gzip")))
            # transcript "" = the freeform lane: Parakeet hears the take and
            # the card stores the REAL words (owner 2026-11-11). A pane
            # enrollment is a first-class voice, not a second-class watch echo.
            card = vo.create_card(
                blob, str(body.get("label") or "pane voice")[:48],
                source=str(body.get("source") or "pane-record")[:24],
                transcript="")
            logging.getLogger("hermes.wearos").warning(
                "voice card created: %s rate=%s", card.get("card_id"),
                card.get("ref_rate"))
            return card
        except Exception as exc:
            logging.getLogger("hermes.wearos").warning(
                "voice card_create FAILED: %r", exc)
            return {"error": str(exc)[:300]}
    if action == "card_delete":
        return vo.delete_card(str(body.get("card") or ""))
    if action == "card_rename":
        return vo.rename_card(str(body.get("card") or ""),
                              str(body.get("label") or ""))
    return {"error": f"unknown voice action {action!r} — select|install|cancel|"
                     "soundtest|speed|karaoke|voice|mode|card_create|card_delete|card_rename"}
