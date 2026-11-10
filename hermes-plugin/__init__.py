"""Hermes Wear Kit — pairing v2, device registry, private channel, tools, CLI, hooks.

Doctrine (docs/SPEC.md §4/§11.4 + the 2026-11-09 review): the watch enrolls ONLY into
the user's own tailnet with its own embedded tsnet node — no LAN transport, no phone
relay, ever. The funnel door (claim_server.py) is the single pre-enrollment network
exchange; after enrollment every byte rides the tunnel through the private lane
(private_api.py).

Flow (protocol v2):
  1. Pairing is CLOSED by default. A human opens the window at the gateway:
     ``hermes watch pair``, the pane's Pair button, or ``/pair`` on a local surface.
  2. The watch taps Pair, mints a 256-bit device secret, POSTs its hash to the door.
     The GATEWAY issues the 6-digit code (the watch never chooses it — no squatting
     race) and the watch displays it.
  3. The human types the code at the gateway (``/pair <code>``, CLI, or pane). The
     code never passes through any model in either direction. Three wrong typed
     codes lock claiming (anonymous traffic can be rate-limited but can never lock
     the approval side out).
  4. Approved: a single-use tagged tailnet key is minted; the watch picks it up by
     its secret (the code appears in no URL) and acks to collect its device token.
  5. The watch joins the tailnet, then GETs its credentials over the PRIVATE lane:
     the gateway's API_SERVER_KEY (delivered only after the node is verified with
     ``tailscale whois`` on the socket — D13) plus the watchkit bearer.
  6. The device registry lives in one fcntl-locked file (pairing.py) shared across
     the CLI, gateway and dashboard processes.

Revoke (D12) cuts everything: device token, queue, tailnet node (DELETE via the
admin API, id captured at bootstrap via whois — not a watch self-report) and the
agent's API key (rotated through Hermes's own ``config set``; named profiles pick
it up hot, the default profile needs the restart the confirmation warns about).

Cron (D14): the ``on_session_end`` hook turns every finished cron run for this
agent into a watch notification through the outbox; the Sessions screen's ⏰ view
reads the runs themselves from watchkit.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import secrets
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any, Callable, Optional

try:
    from . import claim_server, pairing, private_api, stt_install, tailscale
except ImportError:  # flat loader outside a package context (see tests/conftest.py)
    import claim_server  # type: ignore[no-redef]
    import pairing  # type: ignore[no-redef]
    import private_api  # type: ignore[no-redef]
    import stt_install  # type: ignore[no-redef]
    import tailscale  # type: ignore[no-redef]

logger = logging.getLogger("hermes_wearkit")


def _singleton_alias(mod) -> None:
    """Pin the bare module name to THIS package module object.

    The plugin submodules import each other both `from . import x` and
    `import x` (standalone fallback). Under a host that has the plugin dir on
    sys.path BOTH resolve — as two module objects with independent globals,
    i.e. TWO resident engine workers per gateway (measured 2026-11-10: the
    restarted gateway carried a cuda twin and a cpu twin, ~7 GB). Aliasing
    sys.modules after the package import makes every later bare import hit
    the same object: one module, one worker, one queue."""
    try:
        short = str(getattr(mod, "__name__", "")).rsplit(".", 1)[-1]
        if short:
            sys.modules[short] = mod
    except Exception:
        pass

PRIVATE_PORT = 42654             # tailnet-bound private lane (SPEC §11.4)
WATCHKIT_PORT = 42651            # fixed by SPEC §11.4
GATEWAY_PORT = 8642              # Hermes API (per-profile /p/<profile>)
DEFAULT_CLAIM_PORT = 42653       # loopback listener behind tailscale funnel
PAIR_WINDOW_SECONDS = 300        # how long an open pairing window lives
CLAIM_WAIT_SECONDS = 60          # `hermes watch pair <code>` waits this long for pickup
RELEASES_LATEST = "https://github.com/borealBytes/hermes-wearos/releases/latest"

# Surfaces allowed to CLAIM a code (D4): the typed claim rides these only; messaging
# platforms get "approve on your computer". "" = direct/local invocation (CLI, tests).
APPROVAL_SURFACES = {"", "cli", "tui", "local", "desktop", "desktop-app", "webui"}

_CTX: Any = None                 # module singleton for handlers wired at register()
_secrets: Callable[[str], Optional[str]] | None = None
_lane_state: dict = {"bound": None, "error": ""}   # private-lane status for the checklist
_lane_retrying = False                              # one bind-retry thread at a time


def _get_secret(name: str) -> Optional[str]:
    """Read a declared secret the Hermes way (rule 11: never os.environ/.env)."""
    global _secrets
    if _secrets is None:
        try:
            from agent.secret_scope import get_secret as _gs
            _secrets = _gs
        except Exception:  # older/newer host: degrade, do not crash the loader
            _secrets = lambda name: None  # noqa: E731
    try:
        return _secrets(name)
    except Exception:
        return None


def _cfg(key: str, default: Any = None) -> Any:
    if _CTX is None:
        return default
    value = _CTX.get_config(key, default)
    return default if value is None else value


def _cred() -> tailscale.Credential:
    """Prefer the least-privilege OAuth client; fall back to the personal API token."""
    client_id = str(_cfg("tailscale_oauth_client_id", "") or "")
    client_secret = _get_secret("tailscale_oauth_client_secret") or ""
    if client_id and client_secret:
        return tailscale.Credential(client_id=client_id, client_secret=client_secret)
    token = _get_secret("tailscale_api_token") or ""
    if not token:
        raise tailscale.CredentialMissing(
            "no Tailscale credential configured — set plugins.entries.wearos.settings."
            "tailscale_oauth_client_id (+secret) or the tailscale_api_token secret"
        )
    return tailscale.Credential(api_token=token)


def _client() -> tailscale.TailnetClient:
    return tailscale.TailnetClient(_cred(), tailnet=str(_cfg("tailnet", "") or "-"))


def _running_profile() -> str:
    """The profile THIS process runs as: get_hermes_home() IS the active profile home
    (named profiles live at <root>/profiles/<name>)."""
    try:
        from hermes_constants import get_hermes_home
        home = Path(get_hermes_home())
        if home.parent.name == "profiles" and home.name != "default":
            return home.name
    except Exception:
        pass
    return "default"


def _profile_home(profile: str) -> Optional[Path]:
    """Named profile's HERMES_HOME via the host's own resolver; id-gated, traversal-
    refusing (same regex hermes_cli.profiles enforces)."""
    profile = str(profile or "default").strip().lower()
    try:
        from hermes_constants import PROFILE_ID_RE
        # HERMES_HOME wins first: it is THE active-profile root contract (pairing
        # store, cards, prefs all resolve under it). get_default_hermes_root()
        # ignores it when config-root detection finds a real ~/.hermes on disk —
        # inside a sandbox or a relocated home that quietly points at the wrong
        # root (measured 2026-11-09, the grant-law test caught it).
        env_home = os.environ.get("HERMES_HOME", "").strip()
        root = None
        if env_home:
            h = Path(env_home)
            # HERMES_HOME is either the root (default profile) or a named
            # profile home <root>/profiles/<name> — same layout test as
            # _running_profile above.
            root = h.parent.parent if (h.parent.name == "profiles" and
                                       h.name != "default") else h
        if root is None:
            from hermes_constants import get_default_hermes_root
            root = Path(get_default_hermes_root())
        if profile == "default":
            return root
        if PROFILE_ID_RE.fullmatch(profile):
            return root / "profiles" / profile
    except Exception:
        pass
    return None


def _all_profile_names() -> list:
    """default + every live profiles/<dir> (identity-gated — ghost shells can't be
    adopted, #95188 lesson)."""
    try:
        from hermes_constants import PROFILE_ID_RE, get_default_hermes_root
        root = Path(get_default_hermes_root())
    except Exception:
        return ["default"]
    out = ["default"]
    try:
        for child in sorted((root / "profiles").iterdir(), key=lambda p: p.name):
            if child.name == "default" or not PROFILE_ID_RE.fullmatch(child.name):
                continue
            if not child.is_dir():
                continue
            if not any((child / m).is_file() for m in ("config.yaml", "SOUL.md", "profile.yaml",
                                                       "auth.json", "state.db")):
                continue
            out.append(child.name)
    except OSError:
        pass
    return out


# ── the pairing engine (one file, one lock, every process) ─────────────────────
def _engine() -> pairing.PairingEngine:
    """Cheap stateless facade over the fcntl-locked store: constructed per call so
    the HOME (and test sandboxes) resolve fresh every time."""
    return pairing.PairingEngine(pairing.Store(), mint_key=_mint_key, now=time.time)


def _mint_key(profile: str) -> tuple[str, str]:
    client = _client()
    key = client.mint_enrollment_key(tag=str(_cfg("watch_tag", "tag:hermes-watch")),
                                     expiry=str(_cfg("key_expiry", "24h")))
    return key, client.last_key_id


def _expire_key(key_id: str) -> None:
    try:
        _client().expire_key(key_id)
    except Exception:
        logger.debug("wearos: could not expire unspent key %s", key_id, exc_info=True)


_mint_key.expire = staticmethod(_expire_key)  # engine prunes abandoned keys through it


def _gateway_fqdn() -> str:
    """This box's ts.net FQDN via the LOCAL daemon (no API call, load-safe). Measured
    on 1.102.3: the field is Self.DNSName (capitals)."""
    st = _tailscale_self()
    fqdn = str(st.get("DNSName") or st.get("DnsName") or "")
    return fqdn.rstrip(".")


def _tailscale_self() -> dict:
    try:
        out = subprocess.run(["tailscale", "status", "--json"], capture_output=True,
                             text=True, timeout=10)
        return json.loads(out.stdout).get("Self", {}) or {}
    except Exception:
        return {}


def _self_tailnet_ips() -> list:
    return [str(ip) for ip in (_tailscale_self().get("TailscaleIPs") or []) if str(ip)]


# ── door backends (claim_server talks to these) ────────────────────────────────
def door_begin(secret: str, label: str, tailnet_hint: str = "") -> dict:
    return _engine().begin(secret, label, _running_profile(), tailnet_hint)


def door_poll(secret: str) -> tuple:
    state, payload = _engine().poll(secret)
    if state == "ready" and payload is not None:
        payload = dict(payload)
        payload.update({"gateway_fqdn": _gateway_fqdn(), "gateway_port": GATEWAY_PORT,
                        "watchkit_port": WATCHKIT_PORT, "private_port": PRIVATE_PORT})
    return state, payload


def door_ack(secret: str) -> dict:
    result = _engine().ack(secret)
    if "token" not in result:  # re-ack after the record retired: idempotent gone
        raise pairing.PairingError("pairing already completed", "gone")
    return result


# ── the human claim (CLI / /pair / pane — never the model) ─────────────────────
def approve_pair(code: str) -> dict:
    return _engine().approve(str(code or "").strip(),
                             tag=str(_cfg("watch_tag", "tag:hermes-watch")))


def open_pairing(seconds: int = PAIR_WINDOW_SECONDS) -> dict:
    ensure_door()
    return _engine().open_window(_running_profile(), seconds)


def pairing_state() -> dict:
    eng = _engine()
    return {"window": eng.window(), "pending": eng.pending_list(),
            "claim_lock": eng.claim_locked(), "door": _door_listening()}


# ── full revoke (D12) ──────────────────────────────────────────────────────────
def _self_node_id() -> str:
    """Numeric id of THIS machine's tailnet node — ``tailscale whois`` answers the
    gateway's own IPs, so the check costs two local CLI calls and never touches the
    API. "" when tailscaled is unreachable (the guard then simply doesn't fire)."""
    for ip in _self_tailnet_ips():
        try:
            n = private_api._whois_local(ip)
        except Exception:
            return ""
        if n and n.get("node_id"):
            return str(n["node_id"])
    return ""


def revoke_device(name: str, *, started_from: str = "cli") -> dict:
    """Cut one watch off completely: token+queue (store), tailnet node (admin API),
    agent API key (rotated through Hermes). Steps report honestly; a failed later
    step never re-grants the earlier ones."""
    dev = _engine().device(name)
    if dev is None:
        return {"name": name, "error": f"no paired watch named {name!r}"}
    report = {"name": name, "profile": dev.get("profile", ""), "steps": []}
    gone = _engine().revoke(name)          # token+queue+pending records, atomic
    report["steps"].append({"step": "token", "ok": True,
                            "detail": "device token and outbox removed"})
    node_id = str(gone.get("node_id") or dev.get("node_id") or "")
    if node_id and node_id == _self_node_id():
        report["steps"].append({"step": "tailnet", "ok": True,
                                "detail": "captured node was the gateway's own (a NAT'd "
                                          "dial, never a watch tunnel) — nothing to delete"})
    elif node_id:
        try:
            _client().delete_device(node_id)
            report["steps"].append({"step": "tailnet", "ok": True,
                                    "detail": f"node {node_id} removed from your tailnet"})
        except tailscale.TailnetAPIError as exc:
            hint = ("" if exc.status != 404 else
                    " — 404 means the captured ref no longer names a device (the "
                    "node list retry already tried id/nodeId/hostname); check the "
                    "admin console for a stale device row.")
            report["steps"].append({"step": "tailnet", "ok": False,
                                    "detail": f"node {node_id} NOT deleted: {exc}{hint}"})
    else:
        report["steps"].append({"step": "tailnet", "ok": False,
                                "detail": "node id never captured (watch never opened a "
                                          "private session) — check the admin console for "
                                          "a stale "
                                          f"{dev.get('label', name)!r} device"})
    report["rotation"] = rotate_agent_key(dev.get("profile") or _running_profile(),
                                          started_from)
    return report


def rotate_agent_key(profile: str, started_from: str = "cli") -> dict:
    """Write a fresh API_SERVER_KEY for the profile through Hermes's OWN command (rule
    11), never by editing the file ourselves. Named profiles authenticate per-request
    (api_server._expected_api_key re-reads the scope) so the change is LIVE — measured
    2026-11-09: rotated key 401s the old value on the next request, no restart. The
    default profile caches its key at startup: it needs the gateway restart, which is
    why the confirmation copy warns about it (D19)."""
    hermes = shutil.which("hermes")
    new_key = "sk-hermes-" + secrets.token_hex(24)
    if not hermes:
        return {"rotated": False, "reason": "hermes CLI not on PATH — set "
                                            f"{profile}'s API_SERVER_KEY yourself"}
    argv = ([hermes] if profile == "default" else [hermes, "-p", profile]
            ) + ["config", "set", "API_SERVER_KEY", new_key]
    try:
        out = subprocess.run(argv, capture_output=True, text=True, timeout=60,
                             stdin=subprocess.DEVNULL)
    except (subprocess.SubprocessError, OSError) as exc:
        return {"rotated": False, "reason": f"config set failed: {exc}"}
    if out.returncode != 0:
        return {"rotated": False,
                "reason": f"config set failed: {(out.stderr or out.stdout)[-200:]!r}"}
    result = {"rotated": True, "profile": profile}
    if profile == "default":
        if started_from == "watch":
            threading.Thread(target=_restart_default_gateway, daemon=True,
                             name="wearos-restart").start()
            result["restart"] = "scheduled (you confirmed it on the watch)"
        else:
            result["restart"] = "REQUIRED for the default profile: hermes gateway restart"
    return result


def _restart_default_gateway() -> None:
    time.sleep(0.5)  # let the HTTP response flush first
    hermes = shutil.which("hermes")
    if hermes:
        try:
            subprocess.run([hermes, "gateway", "restart"], capture_output=True,
                           text=True, timeout=120, stdin=subprocess.DEVNULL)
        except (subprocess.SubprocessError, OSError):
            logger.warning("wearos: gateway restart failed — run it yourself")


# ── private lane backend (D8/D13) ──────────────────────────────────────────────
def _read_profile_key(profile: str) -> str:
    """API_SERVER_KEY for a profile through Hermes's secret route. Own profile: the
    ambient scope. Another profile (device on a granted agent): bind that profile's
    scope explicitly (pure filesystem read, thread-safe)."""
    if profile == _running_profile():
        k = _get_secret("API_SERVER_KEY") or ""
        if k:
            return k
        # A gateway process that IS the default profile can have an empty ambient
        # secret scope (systemd EnvironmentFile-less boot, `-I` bootstrap — measured
        # 2026-11-10: a default-paired watch's lane session died 'has no
        # API_SERVER_KEY set' while every NAMED profile's file scope resolved fine).
        # Fall through to the same canonical file route, not process env.
    home = _profile_home(profile)
    if home is None:
        return ""
    try:
        from agent.secret_scope import (build_profile_secret_scope, get_secret,
                                        reset_secret_scope, set_secret_scope)
        token = set_secret_scope(build_profile_secret_scope(home), profile_home=str(home))
        try:
            return get_secret("API_SERVER_KEY") or ""
        finally:
            reset_secret_scope(token)
    except Exception:
        return ""


def _read_watchkit_token(profile: str) -> str:
    """Watchkit bearer for the ROUTED agent, through that profile's secret scope
    (same file convention as API_SERVER_KEY: profiles/<p>/.env, root .env for the
    single-profile box). The plugin host thread has no scope bound — a bare
    _get_secret here reads the daemon's os.environ and misses the .env body."""
    home = _profile_home(profile) or Path(os.environ.get("HERMES_HOME",
                                                         str(Path.home() / ".hermes")))
    try:
        from agent.secret_scope import (build_profile_secret_scope, get_secret,
                                        reset_secret_scope, set_secret_scope)
        token = set_secret_scope(build_profile_secret_scope(home), profile_home=str(home))
        try:
            return get_secret("watchkit_token") or ""
        finally:
            reset_secret_scope(token)
    except Exception:
        return _get_secret("watchkit_token") or ""


def _lane_session(profile: str, device: str, secret: str, node: dict) -> dict:
    eng = _engine()
    # A dial whose whois resolves to THIS machine's own node never came through a
    # watch tunnel — it arrived over the host's own tailnet IP (emulator NAT, or an
    # app run on the gateway box itself). The guard covers the ONE dangerous moment:
    # the FIRST session, which would CAPTURE the gateway node as the device's node
    # and fence the real one out for the device's whole life (measured live
    # 2026-11-09). Once bound, first_session's node-identity law already refuses a
    # different node — and a hairpin'd refresh simply retries its next direct dial.
    if not (eng.device(device) or {}).get("node_id"):
        me = _self_node_id()
        if me and str(node.get("node_id") or "") == me:
            raise PermissionError("the dial arrived from the gateway's own tailnet IP — "
                                  "pairing must ride the WATCH's tunnel")
    dev = eng.first_session(device, secret, node)
    # THE GRANT LAW (D17, live-fixed 2026-11-09): the device's OWN agent's key always
    # flows over its lane; a session routed at another agent is that agent's key and
    # requires an explicit grant. The lane host's own running profile is irrelevant —
    # one shared lane hosts every agent on a multiplexed gateway.
    key = eng.resolve_key(profile, device, _read_profile_key)
    payload = {
        "api_key": key,
        "profile": profile,
        "gateway_fqdn": _gateway_fqdn(),
        "gateway_port": GATEWAY_PORT,
        "watchkit_port": WATCHKIT_PORT,
        # The helper bearer, read through the ROUTED agent's secret scope: a plugin
        # host thread has no scope bound and would otherwise fall to os.environ —
        # systemd doesn't carry this one (live: every session said wkToken.len=0
        # while profiles/<p>/.env held the exact bearer). Same trick as
        # _read_profile_key; empty when watchkit isn't deployed (nullable by design).
        "watchkit_token": _read_watchkit_token(profile),
    }
    return payload


def _lane_events(profile: str, device: str, since: int) -> dict:
    dev = _engine().device(device)
    if dev is None:
        raise KeyError(device)
    if str(dev.get("profile") or "") != str(profile):
        raise PermissionError(f"this watch is paired to agent {dev.get('profile')!r}")
    return _engine().events(device, since)


def _lane_ack(profile: str, device: str, up_to: int) -> dict:
    return _engine().ack_events(device, up_to)


def _lane_forget(profile: str, device: str) -> None:
    revoke_device(device, started_from="watch")


def _lane_lookup(token: str) -> Optional[str]:
    return _engine().device_lookup(token)


def _voiceout_register_engines() -> None:
    """Best-effort engine/aligner registration at lane start: each adapter
    self-probes and only registers when installed+verified (or honestly
    size-verified-pending). Absent engines leave voiceout's refusal intact.

    Idempotent and cheap (the probes are stat-level), so BOTH human doors —
    the dashboard /voice route and the lane's voice surface — may call it
    again lazily via `ensure_engines()` before an engine-backed action. One
    swallowed startup probe must never strand the owner's Play button; the
    first real click re-probes and registers (2026-11-10 owner report)."""
    try:
        from . import align_parakeet, tts_air
    except ImportError:              # flat loader outside a package context
        import align_parakeet        # type: ignore[no-redef]
        import tts_air               # type: ignore[no-redef]
    for mod in (tts_air, align_parakeet):
        _singleton_alias(mod)
        try:
            mod.register_if_ready()
        except Exception:            # engine problems never block the lane
            logger.debug("voice engine %s not registered", mod.__name__,
                         exc_info=True)
    _voiceout_prewarm()


_PREWARM_TEXT = "Hermes voice engine ready."
_prewarm_done = threading.Event()


def _voiceout_prewarm() -> None:
    """Warm the synthesis cache in the background right after engine registration.

    The Air engine reloads weights per call (cold first call: minutes on the
    owner's box, 2026-11-10) — a human clicking Play in the pane must never pay
    that. One daemon thread synthesizes a fixed seed sentence once per process
    (voiceout's cache dedupes across restarts by key, so this costs nothing when
    the entry is fresh and self-heals when it is not). Silent on failure: the
    refusal path in voiceout.synthesize already tells the truth to the caller.
    """
    if _prewarm_done.is_set():
        return
    _prewarm_done.set()

    def run() -> None:
        try:
            from . import voiceout
            _singleton_alias(voiceout)
            st = voiceout.status()
            if not st.get("engine") or not st.get("cards"):
                return                 # nothing to speak with yet; honest no-op
            voiceout.synthesize(_PREWARM_TEXT, "default", want_words=False)
            voiceout._warm_audition()  # also cache each card's OWN line — the picker's ▶
        except Exception:
            logger.debug("voice prewarm skipped", exc_info=True)

    threading.Thread(target=run, name="wearos-voice-prewarm", daemon=True).start()


def _lane_voice(action: str, method: str, body: dict, node: dict,
                query: dict) -> tuple:
    """The voice-out surface (SPEC §19). Node law first: the WHOIS-verified node
    must be bound to a paired device — no token, but never just 'a tailnet peer'
    either (a stranger's node on the same tailnet gets 403)."""
    _voiceout_register_engines()   # lazy heal (same rationale as ensure_engines)
    try:
        from . import voiceout
    except ImportError:
        import voiceout  # type: ignore[no-redef]
    _singleton_alias(voiceout)
    eng = _engine()
    nid = str(node.get("node_id") or "")
    bound = next((name for name, d in eng.devices().items()
                  if nid and str(d.get("node_id") or "") == nid), None)
    if bound is None:
        return 403, {"error": "tailnet node is not bound to a paired device"}
    dev = eng.device(bound) or {}
    if action == "prefs":
        if method == "GET":
            return 200, voiceout.read_prefs()
        if method in ("PUT", "POST"):
            remote = body.get("prefs")
            if not isinstance(remote, dict):
                remote = body
            return 200, voiceout.merge_prefs(remote)
        return 405, {"error": "GET or PUT"}
    if action == "cards":
        return 200, voiceout.status()
    if action == "card_wav":
        cid = re.sub(r"[^a-z0-9-]", "",
                     str((query.get("card_id") or [""])[0]))[:64]
        path = voiceout._root() / voiceout.CARDS_DIR / f"{cid}.wav"
        if not cid or not path.is_file():
            return 404, {"error": "no such card"}
        return 200, {"_raw": path.read_bytes(), "_ctype": "audio/wav"}
    if action == "card_create":
        import base64
        wav = base64.b64decode(str(body.get("wav_b64") or ""), validate=True)
        card = voiceout.create_card(
            wav, str(body.get("label") or "watch card")[:48],
            source=str(body.get("source") or "watch-record")[:24],
            transcript=body.get("transcript") if isinstance(
                body.get("transcript"), str) else None)
        return 200, card
    if action == "card_delete":
        return 200, voiceout.delete_card(
            re.sub(r"[^a-z0-9-]", "", str(body.get("card_id") or ""))[:64])
    if action == "synthesize":
        import base64
        r = voiceout.synthesize(str(body.get("text") or "")[:4000],
                                str(body.get("agent") or dev.get("profile") or ""),
                                want_words=bool(body.get("words", True)),
                                card_id=str(body.get("card_id") or "") or None)
        if isinstance(r.get("audio"), (bytes, bytearray)):
            r["audio_wav_b64"] = base64.b64encode(r.pop("audio")).decode()
        return 200, r
    if action.startswith("tts_") or action == "engines":
        try:
            from . import align_parakeet, tts_air
        except ImportError:
            import align_parakeet  # type: ignore[no-redef]
            import tts_air         # type: ignore[no-redef]
        _singleton_alias(tts_air)
        _singleton_alias(align_parakeet)
        if action == "engines":
            return 200, {"air": tts_air.status(), "aligner": align_parakeet.status()}
        if action == "tts_sources":
            return 200, {"sources": tts_air.sources_menu(),
                         "slots": tts_air._slots()}
        if action == "tts_status":
            return 200, tts_air.status()
        if action == "tts_install" and method == "POST":
            tok = str(body.get("token") or "").strip()
            if tok:                                   # paste box: store 0600, never echo
                tf = tts_air._root() / "hf_token"
                tf.parent.mkdir(parents=True, exist_ok=True)
                tf.write_text(tok)
                os.chmod(tf, 0o600)
            r = tts_air.start(interactive=True,
                              source=str(body.get("source") or "") or None,
                              for_slot=str(body.get("for") or "gateway"))
            return 200, r
        return 405, {"error": "GET tts_sources / GET tts_status / POST tts_install"}
    return 404, {"error": "not found"}


def ensure_private_lane() -> bool:
    """Bind the private lane to the gateway's FIRST tailnet IP (never 0.0.0.0 — the
    scanner and the doctrine agree). No tailscaled → no lane → pairing can't finish:
    the truth, reported in the checklist."""
    global _lane_state
    if private_api.actual_port() > 0:
        return True
    ips = _self_tailnet_ips()
    if not ips:
        _lane_state = {"bound": None, "error": "no tailnet address on this machine "
                                               "(tailscaled down or logged out)"}
        return False
    backend = private_api.PrivateBackend(private_api._whois_local, _lane_session,
                                         _lane_events, _lane_ack, _lane_forget,
                                         voice=_lane_voice)
    _voiceout_register_engines()
    for host in ips:
        try:
            private_api.start_server(host, _private_port(), backend, _lane_lookup)
            _lane_state = {"bound": f"{host}:{_private_port()}", "error": ""}
            return True
        except OSError:
            if private_api.lane_alive(host, _private_port()):
                _lane_state = {"bound": f"{host}:{_private_port()} (owned by another "
                                        "wearos process)", "error": ""}
                return True
            # Port busy with something that is NOT a lane (or a half-wired squatter
            # answering 404 to everything — the 2026-11-10 webui incident). Never
            # silent: the pane must show it, and the bind must retry, because a
            # restart RACE for the port is normal on a busy box and losing it must
            # not strand every voice call forever.
            logger.warning("wearos: private lane could not bind %s:%s — port held by a "
                           "non-lane listener; retrying every 5 s for 15 min",
                           host, _private_port())
            _lane_state = {"bound": None,
                           "error": f"port {_private_port()} held by a non-lane listener "
                                    "(restart race?) — retrying"}
            _lane_retry_thread()
            return False
    _lane_state = {"bound": None, "error": "could not bind the private lane"}
    return False


def _lane_retry_thread() -> None:
    """Retry the lane bind after a port race. Daemon thread; stops on success,
    on tailscaled disappearing (no IPs), or after the budget. One thread at a
    time (the bound check above makes re-entry cheap and correct)."""
    global _lane_retrying
    if _lane_retrying:
        return
    _lane_retrying = True

    def _run():
        global _lane_retrying
        try:
            import time as _t
            for _ in range(180):                       # 15 min of 5 s tries
                _t.sleep(5.0)
                if ensure_private_lane():
                    logger.info("wearos: private lane bound on retry")
                    return
        finally:
            _lane_retrying = False
    threading.Thread(target=_run, name="wearos-lane-retry", daemon=True).start()


def _private_port() -> int:
    try:
        return int(_cfg("private_port", PRIVATE_PORT))
    except (TypeError, ValueError):
        return PRIVATE_PORT


# ── cron → watch notifications (D14) ───────────────────────────────────────────
def _cron_title(profile: str, session_id: str, task_id: str = "") -> str:
    """Best-effort human title for the finished cron run.

    Primary: the job NAME from the cron store — the hook's ``task_id`` carries
    ``cron:<job id>:<run id>``, and ``<home>/cron/jobs.json`` resolves it without
    any race. Secondary: the session row's '<job name> · <date>' title in the
    profile's state.db (Hermes's public session store, READ-ONLY — but the title
    is written at cron FINALIZE, seconds AFTER this hook fires, so it's usually
    still pending; live-measured 2026-11-09). Any miss degrades to the generic
    copy; the notification never depends on it."""
    home = _profile_home(profile)
    if home is None:
        return "cron run"
    job_id = (str(task_id or "").split(":", 2)[1]
              if str(task_id or "").startswith("cron:") else "")
    if job_id:
        try:
            import json as _json
            doc = _json.loads((home / "cron" / "jobs.json").read_text(encoding="utf-8"))
            rows = (doc.get("jobs") if isinstance(doc, dict) else doc) or []
            if isinstance(rows, dict):
                rows = list(rows.values())
            for j in rows:
                if isinstance(j, dict) and str(j.get("id") or "") == job_id and j.get("name"):
                    return str(j["name"])[:120]
        except Exception:
            pass
    if not session_id:
        return "cron run"
    try:
        import sqlite3
        sid = str(session_id)[:128]
        con = sqlite3.connect(f"file:{home / 'state.db'}?mode=ro", uri=True, timeout=1.0)
        try:
            # Cron rows key the id as 'local-<sha256>'; match id or chat_id.
            row = con.execute("select title from sessions where id=? or chat_id=?",
                              (sid, sid)).fetchone()
        finally:
            con.close()
        if row and row[0]:
            return str(row[0])[:120]
    except Exception:
        pass
    return "cron run"


def _on_session_end(**kw: Any) -> None:
    """on_session_end: every completed/failed turn on platform='cron' becomes a watch
    notification for this agent (the hook is fire-and-forget — never raises, tiny
    budget, the outbox is the queue)."""
    try:
        if str(kw.get("platform") or "") != "cron":
            return
        profile = _running_profile()
        eng = _engine()
        pref = eng.alert_pref(profile)
        if pref == "off":
            return
        failed = bool(kw.get("failed"))
        if pref == "fail" and not failed:
            return
        title = _cron_title(profile, str(kw.get("session_id") or ""),
                            str(kw.get("task_id") or ""))
        mark = "✗ failed" if failed else "✓ finished"
        text = f"⏰ {title} {mark}"
        for name, dev in eng.devices().items():
            if str(dev.get("profile") or "") != profile:
                continue
            try:
                eng.queue_message(name, text, kind="cron", agent=profile, title=title)
            except pairing.PairingError:
                pass
    except Exception:
        logger.debug("wearos: cron alert failed", exc_info=True)


# ── readiness / wizard checklist ───────────────────────────────────────────────
def local_checks() -> dict:
    """The LOCAL half of the story — safe at load, no network. Each red item carries
    one fix verb."""
    checks: dict[str, dict] = {}
    st = _tailscale_self()
    up = bool(st.get("DNSName") or st.get("TailscaleIPs"))
    checks["tailscaled"] = {
        "ok": up,
        "detail": (f"logged in as {st.get('DNSName', '?')}" if up else
                   "this machine is not on a tailnet (install/run tailscale up)"),
    }
    port = _claim_port()
    door_up = claim_server.door_alive(port) or claim_server.actual_port() > 0
    checks["claim_door"] = {
        "ok": door_up, "detail": f"127.0.0.1:{port} {'answering' if door_up else 'DOWN'}",
        "fix": "hermes watch pair (opens the door with the window)",
    }
    lane_up = private_api.actual_port() > 0 or _lane_state.get("bound")
    checks["private_lane"] = {
        "ok": bool(lane_up),
        "detail": str(_lane_state.get("bound") or _lane_state.get("error")
                      or "not started yet"),
        "fix": "hermes watch status --api (binds it; needs tailscaled)",
    }
    try:
        fs = subprocess.run(["tailscale", "funnel", "status"], capture_output=True,
                            text=True, timeout=5)
        text = (fs.stdout or "") + (fs.stderr or "")
        funneled = f":{port}" in text.replace("127.0.0.1", "")
        checks["funnel"] = {
            "ok": funneled,
            "detail": ("fronts 127.0.0.1:" + str(port)) if funneled else
                      ("not fronting the door — " + text.strip().splitlines()[0:1][0]
                       if text.strip() else "not fronting the door"),
            "fix": "hermes watch setup --fix serve",
        }
    except Exception as exc:
        checks["funnel"] = {"ok": False, "detail": f"cannot read funnel status: {exc}",
                            "fix": "hermes watch setup --fix serve"}
    devices = _engine().devices()
    online = 0
    watch_tag = str(_cfg("watch_tag", "tag:hermes-watch"))
    try:
        st2 = subprocess.run(["tailscale", "status", "--json"], capture_output=True,
                             text=True, timeout=10)
        for peer in (json.loads(st2.stdout).get("Peer") or {}).values():
            if watch_tag in [str(t) for t in (peer.get("Tags") or [])]:
                online += 1 if peer.get("Online") else 0
    except Exception:
        pass
    checks["devices"] = {"ok": bool(devices),
                         "detail": f"{len(devices)} paired · ≤{online} tailnet nodes online"}
    return checks


def api_checks() -> dict:
    """The API half (never at load — only on explicit status/refresh): credential,
    MagicDNS, funnel attribute ON THE GATEWAY NODE."""
    checks: dict[str, dict] = {}
    try:
        client = _client()
        checks["credential"] = {"ok": True, "detail": client.describe_credential()}
        try:
            checks["magicdns"] = {
                "ok": bool(client.dns_preferences().get("magicDNS")),
                "detail": "MagicDNS enables/disables in the console",
            }
        except tailscale.TailnetAPIError as exc:
            checks["magicdns"] = {"ok": False, "detail": str(exc)}
        want = (_self_tailnet_ips() or [""])[0]
        try:
            ok, detail = client.funnel_attr_present(want)
            checks["funnel_attr"] = {"ok": ok, "detail": detail,
                                     "fix": "hermes watch setup --fix funnel"}
        except tailscale.TailnetAPIError as exc:
            checks["funnel_attr"] = {"ok": False, "detail": str(exc)}
    except tailscale.CredentialMissing as exc:
        checks["credential"] = {"ok": False, "detail": str(exc),
                                "fix": "hermes watch setup --fix credential"}
    return checks


# ── profile visibility (D10): the pane checklist reads the SAME truth the manage
#    CLI sees — per-profile `plugins list --json` — never private metadata. ───────
_INVENTORY_CACHE: dict = {}


def _profile_inventory(max_age: float = 10.0) -> list:
    now = time.monotonic()
    hit = _INVENTORY_CACHE.get("inv")
    if hit and now - hit[0] < max_age:
        return hit[1]
    hermes = shutil.which("hermes")

    def _row(name: str) -> dict:
        row = {"name": name, "enabled": False, "visible": False, "version": "",
               "source": ""}
        if hermes:
            argv = ([hermes] if name == "default" else [hermes, "-p", name]) + \
                   ["plugins", "list", "--json"]
            try:
                res = subprocess.run(argv, capture_output=True, text=True, timeout=30,
                                     stdin=subprocess.DEVNULL,
                                     env=_clean_env())
                rows = json.loads(res.stdout) if res.returncode == 0 else []
                for r in rows if isinstance(rows, list) else []:
                    if str(r.get("name", "")) == "wearos":
                        row.update({"enabled": r.get("status") == "enabled",
                                    "visible": True,
                                    "version": str(r.get("version") or ""),
                                    "source": str(r.get("source") or "")})
                        break
            except (subprocess.SubprocessError, OSError, ValueError):
                pass
        return row

    names = _all_profile_names()
    if len(names) > 1:
        # one cold CLI per profile is ~3 s; sequential on 27 profiles that was a
        # 68–84 s /status (2026-11-10). Fan out, keep _all_profile_names() order.
        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(max_workers=8) as ex:
            out = list(ex.map(_row, names))
    else:
        out = [_row(n) for n in names]
    _INVENTORY_CACHE["inv"] = (now, out)
    return out


_INVENTORY_LOCK = threading.Lock()
_INVENTORY_REFRESHING = False


def _inventory_serving(max_age: float = 300.0) -> list:
    """Status-path read: NEVER blocks on the CLI fan-out (measured 68–84 s on a
    27-profile gateway — the pane's /status timed out and showed no watches/agents,
    2026-11-10). Serve the newest scan whatever its age; if stale, refresh on one
    background thread. First call per process seeds synchronously (unavoidable).
    TTL 300 s: inventory only changes through THIS pane (enable/disable), which
    refreshes eagerly — a short TTL re-ran 26 cold CLIs every poll while the pane
    sat open and pinned cores (owner 'CPU noise', 2026-11-10 16:52)."""
    global _INVENTORY_REFRESHING
    hit = _INVENTORY_CACHE.get("inv")
    if hit is None:
        return _profile_inventory(max_age=0)
    if time.monotonic() - hit[0] >= max_age:
        with _INVENTORY_LOCK:
            if not _INVENTORY_REFRESHING:
                _INVENTORY_REFRESHING = True
                threading.Thread(target=_inventory_refresh, daemon=True).start()
    return hit[1]


def _inventory_refresh() -> None:
    global _INVENTORY_REFRESHING
    try:
        _profile_inventory(max_age=0)
    finally:
        with _INVENTORY_LOCK:
            _INVENTORY_REFRESHING = False


def invalidate_inventory() -> None:
    """Post-mutation hook (enable/disable): mark the cache stale and refresh NOW
    on the background thread, so the TTL exists only as a safety net, not as the
    pane's staleness budget. Keep serving the previous snapshot meanwhile —
    DROPPING it made the pane's own click-triggered refetch pay the full 26-CLI
    fan-out synchronously (the owner's 'whole thing flashes, no status change',
    2026-11-10 ~17:10: the disable HAD landed; the fetch timed out and the box
    re-rendered from the error)."""
    hit = _INVENTORY_CACHE.get("inv")
    if hit is not None:
        _INVENTORY_CACHE["inv"] = (0.0, hit[1])
    else:
        _INVENTORY_CACHE.pop("inv", None)
    global _INVENTORY_REFRESHING
    with _INVENTORY_LOCK:
        if not _INVENTORY_REFRESHING:
            _INVENTORY_REFRESHING = True
            threading.Thread(target=_inventory_refresh, daemon=True).start()


def _clean_env() -> dict:
    env = dict(os.environ)
    for k in ("API_SERVER_KEY", "HERMES_TAILNET_KEY"):
        env.pop(k, None)
    return env


def _install_source_ref() -> tuple[str, str]:
    """(source, 40-hex revision) of THIS plugin per the install metadata at
    <HERMES_HOME>/plugins/.install-metadata.json — the pin the pane itself runs
    on. Empty strings when the plugin is a checkout/link without metadata."""
    home = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes")))
    try:
        meta = json.loads((home / "plugins" / ".install-metadata.json").read_text("utf-8"))
        row = meta.get("wearos") or {}
        return str(row.get("source") or ""), str(row.get("revision") or "")
    except (OSError, ValueError):
        return "", ""


def delegate_enablement(profile: str, enable: bool) -> dict:
    """One checkbox = one fixed-argv `hermes -p <profile> plugins enable|disable
    wearos` (review fix: no hand-rolled YAML editor; the CLI's consent flow runs, we
    NEVER pass --yes; the value lives in core's hands).

    Enable self-heals (owner ruling 2026-11-10 ~17:05, the 'Where Agent flashes'
    report): a named profile that never INSTALLS wearos answers 'No plugin named',
    which used to just flash the checkbox back. When that happens and the pane's
    own install metadata names the repo+ref, we install from that exact pin (same
    consent flags the pane's Enable-all uses — --yes rides the pinned community
    source, --force matches how the owner re-pins) and retry the enable once."""
    profile = re.sub(r"[^a-z0-9_-]", "", str(profile or "").strip().lower())[:64]
    if not profile:
        return {"name": profile, "error": "profile name is required"}
    hermes = shutil.which("hermes")
    if not hermes:
        return {"name": profile, "error": "hermes CLI not on PATH"}
    verb = "enable" if enable else "disable"
    prefix = [hermes] if profile == "default" else [hermes, "-p", profile]
    argv = prefix + ["plugins", verb, "wearos"]
    try:
        out = subprocess.run(argv, capture_output=True, text=True, timeout=60,
                             stdin=subprocess.DEVNULL, env=_clean_env())
    except (subprocess.SubprocessError, OSError) as exc:
        return {"name": profile, "error": f"{verb} failed: {exc}"}
    text = ((out.stdout or "") + (out.stderr or "")).strip()
    if verb == "enable" and out.returncode != 0 and "No plugin named" in text:
        repo, ref = _install_source_ref()
        if repo and re.fullmatch(r"[0-9a-f]{40}", ref or ""):
            try:
                inst = subprocess.run(
                    prefix + ["plugins", "install", repo, "--force",
                              "--ref", ref, "--yes"],
                    capture_output=True, text=True, timeout=180,
                    stdin=subprocess.DEVNULL, env=_clean_env())
            except (subprocess.SubprocessError, OSError) as exc:
                return {"name": profile, "ok": False, "verb": "install",
                        "detail": f"auto-install failed: {exc}"}
            if inst.returncode == 0:
                try:
                    out = subprocess.run(argv, capture_output=True, text=True,
                                         timeout=60, stdin=subprocess.DEVNULL,
                                         env=_clean_env())
                except (subprocess.SubprocessError, OSError) as exc:
                    return {"name": profile, "error": f"{verb} failed: {exc}"}
                text = ((out.stdout or "") + (out.stderr or "")).strip()
            else:
                return {"name": profile, "ok": False, "verb": "install",
                        "detail": ("auto-install failed: "
                                   + ((inst.stdout or "") + (inst.stderr or "")).strip()[-400:])}
    return {"name": profile, "ok": out.returncode == 0, "verb": verb,
            "detail": text[-600:] or ("done" if out.returncode == 0 else "failed")}


# ── claim door lifecycle ───────────────────────────────────────────────────────
def _claim_port() -> int:
    try:
        return int(_cfg("claim_port", DEFAULT_CLAIM_PORT))
    except (TypeError, ValueError):
        return DEFAULT_CLAIM_PORT


def _door_listening() -> bool:
    if claim_server.actual_port() > 0:
        return True  # ours, in-process (gateway hook / earlier CLI start)
    return claim_server.door_alive(_claim_port())


def ensure_door() -> bool:
    """Start the claim door if nothing answers on the configured port (whichever
    wearos process got here first owns it). Returns True when a door answers."""
    port = _claim_port()
    if _door_listening():
        return True
    try:
        claim_server.start_server(port, claim_server.ClaimBackend.from_module())
        return True
    except OSError:
        return claim_server.door_alive(port)


def _await_pickup(code: str, timeout: float) -> str:
    """CLI patience: wait for the watch to collect (and ack) its key."""
    deadline = time.time() + timeout
    eng = _engine()
    while time.time() < deadline:
        for rec in eng.pending_list():
            if rec.get("code") == code:
                if rec.get("state") == "pending":
                    return "unknown"  # record vanished under the tombstone
                break
        else:
            return "delivered"  # not in the live list anymore: retired or acked
        time.sleep(1.0)
    return "waiting"


# ── wizard fix-verbs (hermes watch setup --fix …) ──────────────────────────────
def _wizard_fix(verb: str, yes: bool = False) -> None:
    if verb == "credential":
        _fix_credential()
    elif verb == "funnel":
        _fix_funnel(yes=yes)
    elif verb == "serve":
        _fix_serve()
    elif verb == "stt":
        _fix_stt()
    else:
        print(f"unknown --fix {verb!r} — try credential | funnel | serve | stt")


def _fix_stt() -> None:
    """Streaming STT install, terminal path (the pane button is the other one)."""
    prev = stt_install.preview()
    print("Streaming speech-to-text (Parakeet) — this will:")
    for step in prev["steps"]:
        print(f"  · {step}")
    print(f"  · hosts: {', '.join(prev['hosts'])} · needs: {', '.join(prev['needs'])}"
          f" · disk: ~{prev['disk_mb']} MB")
    if not prev["toolchain_ok"]:
        print("  ✗ toolchain missing (git, cmake, g++) — install those first.")
        return
    if sys.stdin.isatty():
        answer = input("Proceed? [y/N] ").strip().lower()
        if answer not in ("y", "yes"):
            print("cancelled.")
            return
        r = stt_install.start(interactive=True)
    else:
        r = stt_install.start(interactive=False)
    if not r.get("started"):
        print(f"  ✗ {r.get('error', 'not started')}")
        return
    print("  · installing (progress below; ctrl-c does NOT cancel — use "
          "hermes watch setup --fix stt again to check, or the pane's Cancel):")
    while True:
        st = stt_install.status()
        print(f"    [{st.get('phase')}] {st.get('detail')}"
              f"{' — ' + st['error'] if st.get('error') else ''}")
        if st.get("phase") in ("done", "failed", "cancelled", "idle"):
            break
        time.sleep(3.0)


def _fix_credential() -> None:
    print("· validating the Tailscale credential (GET /tailnet/-/devices)…")
    try:
        client = _client()
    except tailscale.CredentialMissing as exc:
        print(f"  ✗ {exc}")
        print("  set one: hermes plugins configure wearos — prefer the OAuth pair")
        print("  (tailscale_oauth_client_id + tailscale_oauth_client_secret, scoped to")
        print("  auth_keys + devices:core, the watch tag, dns:read, and a policy READ")
        print("  grant) or the tailscale_api_token secret.")
        return
    try:
        data = client.validate()
    except tailscale.TailnetAPIError as exc:
        hint = {401: "token revoked or wrong — mint a fresh one in the admin console",
                403: "role too low — Owner/Admin (or an OAuth client with auth_keys)",
                0: "cannot reach api.tailscale.com — DNS/firewall?"}.get(exc.status, "")
        print(f"  ✗ {exc}")
        if hint:
            print(f"  why: {hint}")
        return
    print(f"  ✓ {client.describe_credential()}")
    print(f"  tailnet seen: {client.tailnet} · {len(data.get('devices', []))} devices visible")
    if not str(_cfg("tailnet", "") or ""):
        print(f"  set it: plugins.entries.wearos.settings.tailnet: {client.tailnet}")


def _fix_funnel(yes: bool) -> None:
    try:
        client = _client()
    except tailscale.CredentialMissing as exc:
        print(f"  ✗ {exc}")
        print("  run `hermes watch setup --fix credential` first.")
        return
    # ACL attribute half of the funnel story — granted to THIS GATEWAY NODE (review
    # fix; v0.1 targeted the watch tag, the wrong node: the funnel fronts the door
    # here). The OTHER half — enabling Funnel — has no API (measured 2026-11-06):
    # --fix serve discovers it live from the CLI's own error + link.
    try:
        target = (_self_tailnet_ips() or [""])[0]
        if not target:
            print("  ✗ this machine has no tailnet address (tailscaled down?)")
            return
        ok, detail = client.funnel_attr_present(target)
    except tailscale.TailnetAPIError as exc:
        print(f"  ✗ cannot read the tailnet ACL: {exc}")
        print("  run `hermes watch setup --fix credential` first.")
        return
    if ok:
        print(f"  ✓ funnel attribute already granted — {detail}")
        return
    print(f"  ✗ {detail}")
    print(f'  one-node grant — add a funnel nodeAttrs entry targeting THIS gateway')
    print(f'  (its tailscale IP: {target}; the tag rule below lets enrolled watch keys')
    print('  carry their tag — both sections, all shapes measured):')
    tag = str(_cfg("watch_tag", "tag:hermes-watch"))
    print(json.dumps({"tagOwners": {tag: ["autogroup:member"]},
                      "nodeAttrs": [{"target": [target], "attr": ["funnel"]}]},
                     indent=2).replace("\n", "\n        "))
    print("        console: console.tailscale.com/admin/acls/visual (Access controls ▸ Policies ▸ JSON editor)")
    if not yes:
        print("  re-run with --yes to apply this preview (replaces the tailnet policy file, If-Match guarded).")
        print("  NOTE: your commented legacy policy renders as clean JSON after a replace —")
        print("  same rules, comments dropped. Preview first, obviously.")
        return
    print("  · preview approved (--yes) — replacing the ACL (etag-guarded)…")
    try:
        client.add_funnel_attr(target, also_tag=str(_cfg("watch_tag", "tag:hermes-watch")))
    except tailscale.TailnetAPIError as exc:
        print(f"  ✗ replace refused: {exc}")
        print("    (conflict = someone edited the ACL meanwhile; apply the diff above in the console)")
        return
    ok, detail = client.funnel_attr_present(target)
    print(f"  {'✓' if ok else '✗'} funnel_attr: {detail}")


def _fix_serve() -> None:
    """Front the claim door AND learn the funnel-enable truth in one live attempt:
    `tailscale funnel` fails with 'Funnel is not enabled on your tailnet' + a per-node
    approval link."""
    port = int(_cfg("claim_port", DEFAULT_CLAIM_PORT))
    try:
        out = subprocess.run(["tailscale", "funnel", "status"],
                             capture_output=True, text=True, timeout=10)
        text = (out.stdout or "") + (out.stderr or "")
    except FileNotFoundError:
        print("  ✗ tailscale CLI not found — install tailscaled first (see README).")
        return
    except subprocess.SubprocessError as exc:
        print(f"  ✗ could not read `tailscale funnel status`: {exc}")
        return
    if out.returncode == 0 and f":{port}" in text.replace("127.0.0.1", ""):
        print("  ✓ door already funneled — nothing to do.")
        print("    " + "\n    ".join(text.strip().splitlines()[:6]))
        return
    # Flags measured on 1.102.3: funnel is its own verb mirroring serve (--bg/--https);
    # serve has no --funnel flag. Root mount is safe: the door 404s every path except
    # /pair/begin|poll|ack (and 426s v1), so nothing else is internet-reachable.
    # Measured to the bottom: on a DISABLED enable `tailscale funnel` prints the
    # approval link and then WAITS. We EMBRACE it: the wizard stays alive up to 4 min,
    # and the moment the user approves Funnel the command proceeds and applies itself.
    cmd = ["tailscale", "funnel", "--bg", "--https=443", f"http://127.0.0.1:{port}"]
    print(f"  · running: {' '.join(cmd)}")
    try:
        st = json.loads(subprocess.run(["tailscale", "status", "--json"],
                                       capture_output=True, text=True, timeout=10).stdout)
        node_id = (st.get("Self") or {}).get("ID", "")
        if node_id:
            print(f"    enable (one click, approves THIS node): https://login.tailscale.com/f/funnel?node={node_id}")
    except Exception:
        pass
    print("    (waiting up to 4 min for the approval; it applies itself once clicked)")
    try:
        run = subprocess.run(cmd, capture_output=True, text=True, timeout=240,
                             stdin=subprocess.DEVNULL)
    except subprocess.TimeoutExpired:
        print("  ✗ gave up waiting (4 min). Approve the link, then re-run this verb.")
        return
    except (FileNotFoundError, subprocess.SubprocessError) as exc:
        print(f"  ✗ could not run the tailscale CLI: {exc}")
        return
    out_text = ((run.stdout or "") + (run.stderr or "")).strip()
    # Measured (post-approval): serve/funnel config WRITES need operator rights; a
    # plain user gets "Access denied: serve config denied" after the enable succeeds.
    if "serve config denied" in out_text.lower() or "access denied" in out_text.lower():
        print("  ✗ funnel is enabled ✓ but this user may not write serve config.")
        print("    one-time fix (the tailscale CLI prints this itself):")
        print(f"        sudo tailscale set --operator={os.environ.get('USER', '$USER')}")
        print("  …then re-run this verb.")
        return
    # NB: the disabled path exits 0 with prose (measured) — parse text BEFORE rc.
    if "funnel is not enabled" in out_text.lower():
        print("  ✗ funnel not enabled yet. Approve it at the per-node link (free,")
        print("    Personal plan ok — there is no console switch; this link IS the UI):")
        link = next((w for w in out_text.split() if w.startswith("https://")), "")
        print(f"        {link or 'https://login.tailscale.com/f/funnel?node=<Self.ID from: tailscale status --json>'}")
        print("  …then re-run this verb. The door must be PUBLIC — a watch with no")
        print("  tunnel yet can't ride a tailnet-only serve.")
        return
    if run.returncode == 0:
        print("  ✓ funnel route applied.")
        print("    " + "\n    ".join(out_text.splitlines()[:6]))
        url = next((w for w in out_text.split() if w.startswith("https://")), "")
        if url:
            print("  · watch-side pairing address (type on the watch, minus https://):")
            print(f"      {url.split('//', 1)[1].split('/')[0]}   → POST /pair/begin")
        return
    print(f"  ✗ funnel refused: {out_text[:300]}")


# ── tools ──────────────────────────────────────────────────────────────────────
def _tool_watch_status(args: dict, **kw) -> str:
    if set(args or {}) - {"api"}:
        return json.dumps({"error": "watch_status takes no arguments"})
    include_api = bool(args.get("api") or kw.get("api"))
    payload = {
        "checks": local_checks(),
        "devices": _engine().devices(),
        "pairing": pairing_state(),
        "profiles": _inventory_serving(),
        "grants": _engine().grants(),
        "apk_release_url": str(_cfg("apk_release_url", RELEASES_LATEST) or RELEASES_LATEST),
    }
    if include_api:
        payload["checks"].update(api_checks())
    return json.dumps(payload, indent=2)


def _tool_watch_pair(args: dict, **kw) -> str:
    """No arguments at all (review fix): the model opens the pairing WINDOW and reads
    status — it can neither mint nor approve nor see a claim it can act on."""
    if args:
        return json.dumps({"error": "watch_pair takes no arguments — the pairing code "
                                    "is typed by a human at the gateway, never by a model"})
    state = open_pairing()
    return json.dumps({
        "pairing_window": {"profile": state["profile"],
                           "seconds_left": max(0, int(state["until"] - time.time()))},
        "how": "tap Pair on the watch — it shows a 6-digit code; the HUMAN types it at "
               "the gateway (hermes watch pair <CODE> or /pair <CODE> on the computer). "
               "The code never passes through a model.",
        "door": _door_listening(),
    }, indent=2)


def _tool_watch_send(args: dict, **kw) -> str:
    text = str(args.get("text", "") or "")
    if not text.strip():
        return json.dumps({"error": "text is required"})
    target = str(args.get("device", "") or "")
    profile = _running_profile()
    eng = _engine()
    devices = eng.devices()
    picked = ([target] if target else
              [name for name, dev in devices.items()
               if str(dev.get("profile") or "") == profile])
    if not picked:
        return json.dumps({"error": f"no paired watch for agent {profile!r} — run "
                                    "hermes watch pair"})
    results = []
    for name in picked:
        try:
            r = eng.queue_message(name, text, kind="msg", agent=profile)
            results.append({"device": name, **r})
        except pairing.PairingError as exc:
            results.append({"device": name, "error": str(exc)})
    return json.dumps({"queued": [r for r in results if r.get("queued")],
                       "failed": [r for r in results if not r.get("queued")],
                       "note": "delivered on the watch's next private-lane poll "
                               "(screen awake ≤ ~30 s; doze can hold it longer)"},
                      indent=2)


_TOOLS = (
    ("watch_send", {
        "type": "object",
        "properties": {
            "text": {"type": "string", "description": "Message to show on the watch"},
            "device": {"type": "string", "description": "Paired watch name (default: every watch paired to this agent)"},
        },
        "required": ["text"],
    }, _tool_watch_send, "📳"),
    ("watch_status", {"type": "object", "properties": {
        "api": {"type": "boolean", "description": "include the tailscale-API checks (credential, MagicDNS, funnel attribute)"}}},
        _tool_watch_status, "🛰"),
    ("watch_pair", {"type": "object", "properties": {}}, _tool_watch_pair, "🔗"),
)


# ── in-session /pair command — the claim surface (D4) ──────────────────────────
def _cmd_pair(raw_args: str, platform: str = "", **kw) -> str:
    arg = (raw_args or "").strip()
    eng = _engine()
    if not arg:
        st = pairing_state()
        w = st["window"]
        lines = [f"pairing window: OPEN for {w['seconds_left']}s as agent {w['profile']}"
                 if w else "pairing window: CLOSED (this command opens it)"]
        for p in st["pending"]:
            lines.append(f"  code {p['code']} → {p['label']!r} [{p['state']}, "
                         f"{p['seconds_left']}s]")
        lines.append("usage: /pair <6-digit code> — after tapping Pair on the watch")
        return "\n".join(lines)
    if arg.lower() in ("open", "again"):
        state = open_pairing()
        return (f"pairing window open {int(state['until'] - time.time())}s for agent "
                f"{state['profile']} — tap Pair on the watch to see its code")
    if arg.lower() in ("close", "stop"):
        eng.close_window()
        return "pairing window closed"
    if platform not in APPROVAL_SURFACES:
        return (f"watch code {arg} received — approve it on your computer: "
                f"`hermes watch pair {arg}` in a terminal, or the Wear Kit pane. "
                "Pairing claims happen on the gateway, never in chat.")
    try:
        approved = approve_pair(arg)
    except pairing.PairingError as exc:
        lock = eng.claim_locked()
        if lock["locked"]:
            return ("too many wrong codes — pairing approval is locked for a few minutes")
        return f"pairing failed: {exc}"
    tail = ("the watch collects its key on its next poll (≤5 s)"
            if _door_listening() else
            "claim door not answering — the approved record waits 2 min; pair once the "
            "gateway is up (hermes watch setup --fix serve)")
    return (f"approved: {approved['label']!r} joins agent {approved['profile']}'s tailnet "
            f"— {tail}")


# ── hermes watch CLI ───────────────────────────────────────────────────────────
def _watch_cli_setup(sub) -> None:
    watch_sub = sub.add_subparsers(dest="watch_command")
    p = watch_sub.add_parser("pair",
                             help="open the pairing window, or claim a code: "
                                  "hermes watch pair [<CODE>]")
    p.add_argument("code", nargs="?", help="6-digit code shown on the watch")
    e = watch_sub.add_parser("devices", help="list paired watches")
    e.add_argument("--revoke", metavar="NAME", help="full removal: token, tailnet node, "
                                                    "agent key rotation")
    e.add_argument("--yes", action="store_true", help="skip the confirm prompt")
    s = watch_sub.add_parser("status", help="readiness checklist (local; --api adds the "
                                            "tailscale-API half)")
    s.add_argument("--api", action="store_true")
    w = watch_sub.add_parser("setup", help="wizard: tailscaled → credential → funnel")
    w.add_argument("--fix", choices=["credential", "funnel", "serve", "stt"], default=None)
    w.add_argument("--yes", action="store_true",
                   help="approve the printed preview (currently: --fix funnel PATCHes the ACL)")
    a = watch_sub.add_parser("alerts", help="cron→watch notifications: on|fail|off|list")
    a.add_argument("pref", choices=["on", "fail", "off", "list"], nargs="?", default="list")


def _cli_pair(args) -> None:
    code = str(getattr(args, "code", "") or "")
    eng = _engine()
    if code:
        try:
            approved = approve_pair(code)
        except pairing.PairingError as exc:
            print(f"pairing failed: {exc}")
            raise SystemExit(1)
        print(f"✓ approved {approved['label']!r} for agent {approved['profile']}")
        if not _door_listening():
            print("⚠ claim door not answering — hermes watch setup --fix serve")
            return
        status = _await_pickup(code, CLAIM_WAIT_SECONDS)
        if status == "delivered":
            print("✓ the watch collected its key and its device token")
        elif status == "unknown":
            print("⚠ the approved record aged out before the watch polled — re-pair")
        else:
            print(f"approved — the watch polls every ~3 s; still waiting after {CLAIM_WAIT_SECONDS}s")
        return
    state = open_pairing()
    print(f"pairing window OPEN for {PAIR_WINDOW_SECONDS // 60} min, agent "
          f"{state['profile']} — tap Pair on the watch now.")
    print("waiting for the watch to show its code (ctrl-c stops this; the window stays "
          "open; re-run to see it):")
    deadline = time.time() + 45
    shown = ""
    while time.time() < deadline:
        for rec in eng.pending_list():
            if rec["state"] == "pending" and rec["code"] != shown:
                shown = rec["code"]
                print(f"  code on the watch: {shown}  ({rec['label']!r})")
            elif rec["state"] == "approved" and rec["code"] == shown:
                print(f"  ✓ {shown} approved — waiting for pickup…")
                shown = ""
        if not shown and not eng.pending_list():
            time.sleep(1.0)
            continue
        time.sleep(1.0)
    if shown:
        print(f"if the watch is showing {shown}: hermes watch pair {shown}")


def _cli_devices(args) -> None:
    eng = _engine()
    revoke = str(getattr(args, "revoke", "") or "")
    if revoke:
        dev = eng.device(revoke)
        if dev is None:
            print(f"no paired watch named {revoke!r}")
            raise SystemExit(1)
        prof = str(dev.get("profile") or "")
        print(f'Remove "{dev.get("label", revoke)}" from agent {prof!r}?')
        print(" • deletes it from your tailnet (admin API, node id captured at bootstrap)")
        print(" • invalidates its device token and clears its queue")
        print(f" • rotates agent {prof!r}'s API key — other paired watches pick the new")
        print("   key up automatically over the tailnet; scripts using the old value")
        print("   must update.")
        if prof == "default":
            print(" • ⚠ THE GATEWAY WILL RESTART (the default profile caches its key at")
            print("   startup). Active chats on every agent are interrupted.")
        if not getattr(args, "yes", False):
            if not sys.stdin.isatty():
                print("no terminal to confirm — re-run with --yes.")
                raise SystemExit(2)
            answer = input("Type yes to remove it: ").strip().lower()
            if answer != "yes":
                print("cancelled.")
                return
        report = revoke_device(revoke, started_from="cli")
        for step in report["steps"]:
            print(f"  {'✓' if step['ok'] else '✗'} {step['step']}: {step['detail']}")
        rot = report["rotation"]
        if rot.get("rotated"):
            extra = f" — {rot['restart']}" if rot.get("restart") else " (named profile: live on the next request)"
            print(f"  ✓ rotate: agent {rot['profile']}'s API key replaced{extra}")
        else:
            print(f"  ✗ rotate: {rot.get('reason', 'no key was configured')}")
        return
    devices = eng.devices()
    if not devices:
        print("no paired watches yet — hermes watch pair")
        return
    print(json.dumps(devices, indent=2))


def _cli_alerts(args) -> None:
    pref = str(getattr(args, "pref", "list") or "list")
    eng = _engine()
    profile = _running_profile()
    if pref == "list":
        print(f"agent {profile!r}: cron alerts = {eng.alert_pref(profile)} "
              "(on = every run, fail = failures only, off = silent)")
        return
    mapping = {"on": "all", "fail": "fail", "off": "off"}
    eng.set_alert_pref(profile, mapping[pref])
    print(f"agent {profile!r}: cron alerts = {pref}")


def _watch_cli_run(args) -> None:
    cmd = getattr(args, "watch_command", None) or "status"
    if cmd == "pair":
        _cli_pair(args)
        return
    if cmd == "devices":
        _cli_devices(args)
        return
    if cmd == "alerts":
        _cli_alerts(args)
        return
    if cmd == "setup" and getattr(args, "fix", None):
        _wizard_fix(args.fix, yes=bool(getattr(args, "yes", False)))
        return
    checks = local_checks()
    if getattr(args, "api", False) or cmd == "setup":
        checks.update(api_checks())
    print(json.dumps({"checks": checks, "devices": _engine().devices(),
                      "pairing": pairing_state()}, indent=2))
    if cmd == "setup":
        print("red light? each check carries its own `fix` — `hermes watch setup --fix"
              " credential|funnel|serve [--yes]`.")


# ── load-time bring-up (LOCAL ONLY: no external calls here) ────────────────────
def _startup_pass() -> None:
    try:
        ensure_door()
    except Exception:
        logger.debug("wearos: claim door did not start at load", exc_info=True)
    try:
        ensure_private_lane()
    except Exception:
        logger.debug("wearos: private lane did not start at load", exc_info=True)
    logger.info("wearos: local readiness: %s", {k: bool(v.get("ok"))
                                                for k, v in local_checks().items()})


def register(ctx) -> None:
    """Entry point called by the Hermes plugin loader (docs: developer-guide/plugins)."""
    global _CTX
    _CTX = ctx
    for name, schema, handler, emoji in _TOOLS:
        ctx.register_tool(name=name, toolset="wearos", schema=schema, handler=handler, emoji=emoji)
    ctx.register_command("pair", handler=_cmd_pair,
                         description="Pair a Wear OS watch: /pair <6-digit code from the watch>",
                         args_hint="<123456>")
    ctx.register_hook("on_session_end", _on_session_end)
    # Settings ▸ Voice ▸ provider: 'wearkit-gateway' (owner ruling 2026-11-10 — one
    # engine, one queue: desktop TTS rides the Wear Kit's warm worker via voiceout).
    try:
        try:
            from . import tts_provider     # loaded as a package (gateway/dashboard)
        except ImportError:
            import tts_provider            # type: ignore[no-redef]
        _singleton_alias(tts_provider)
        tts_provider.register(ctx)
    except Exception:
        logger.warning("wearos: TTS provider registration skipped", exc_info=True)
    ctx.register_cli_command(
        name="watch", help="Hermes Wear Kit (pair, devices, status, setup, alerts)",
        setup_fn=_watch_cli_setup, handler_fn=_watch_cli_run,
        description="One-command Wear OS watch pairing over your own tailnet.",
    )
    ctx.on_unload(lambda: (claim_server.stop_server(), private_api.stop_server()))
    # Rule 11: the voice-out engine reads its declared secret (config_schema
    # hf_token → env HF_TOKEN) through the plugin's own route, profile-scoped.
    from . import tts_air as _ta
    _singleton_alias(_ta)
    _ta._SECRETS = lambda key: _cfg(key) or _get_secret("HF_TOKEN")
    # Register installed voice engines AT LOAD, not only at watch-lane bind:
    # the dashboard soundtest and any host without tailscaled still get the
    # engine the user installed (rig-measured gap 2026-11-09 — the lane call
    # stays, it re-probes so mid-session installs activate without a restart).
    _voiceout_register_engines()
    # Door + lane come up at LOAD on a daemon thread (0.23 has no gateway:startup hook;
    # a door that never comes up is a SILENT pairing failure). The pass is strictly
    # local: tailscale CLI probes only, never api.tailscale.com.
    threading.Thread(target=_startup_pass, daemon=True, name="wearos-readiness").start()


def ensure_engines() -> None:
    """Lazy re-registration hook for the human doors (dashboard + lane voice
    routes). See _voiceout_register_engines — probes are stat-level, safe per
    request; a cold-start miss heals on the first action that needs it. The
    prewarm gate re-arms until an engine is actually visible, so an engine
    installed AFTER boot still gets its seed sentence cached before any
    human click pays the cold-load tax."""
    global _prewarm_done
    _voiceout_register_engines()
    try:
        from . import voiceout
        _singleton_alias(voiceout)
        if voiceout.status().get("engine") and _prewarm_done.is_set():
            _prewarm_done = threading.Event()     # re-arm once: engine just appeared
            _voiceout_prewarm()
    except Exception:
        pass
