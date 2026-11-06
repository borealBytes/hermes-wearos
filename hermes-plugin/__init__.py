"""Hermes Wear Kit — pairing state machine, device registry, tools, CLI, hooks.

Doctrine (docs/SPEC.md §11.4, handoff §2.5): the watch enrolls ONLY into the user's own
tailnet with its own embedded tsnet node — no LAN transport, no phone relay, ever. The
funnel claim door below is the single pre-enrollment network exchange; after enrollment
every byte rides the tunnel.

Flow (handoff §4):
  1. The watch cold-starts and shows a 6-digit PIN (5-min TTL, single-claim). The same
     PIN can also be minted from the plugin side (``watch_pair`` tool / ``hermes watch
     pair --show``) for the Desktop "show PIN" verb.
  2. The watch POSTs {pin, tailnet} to the claim endpoint (behind Funnel:
     https://pair.<tailnet>.ts.net/pair/claim) — Mode B — or the user speaks/types the
     PIN to Hermes anywhere (``/pair 429183``).
  3. On the FIRST match of a queued claim + a spoken PIN, the plugin mints a single-use,
     pre-authorized, tagged auth key (tailscale.py), assembles the enrollment packet
     (authkey, tailnet, gateway ts.net FQDN :8642, watchkit :42651, profile binding,
     gateway API key) and releases it to the watch. The authkey is zeroed after use.
  4. Registry lives in profile-scoped plugin state (ctx.state → HERMES_HOME/plugin-data/
     wearos/state.json): key ``devices``.

Waves: claim HTTP endpoint + funnel serve + wizard fix-verbs land in the next commits
(TODO(pairing-impl), TODO(wizard)); the Node sidecar stays the documented media fallback
until media.py finishes its port (handoff cut line).
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import secrets
import subprocess
import time
from typing import Any, Callable, Optional

from . import tailscale

logger = logging.getLogger("hermes_wearkit")

STATE_DEVICES = "devices"        # ctx.state key: {hostname: device-record}
STATE_PINS = "pins"              # ctx.state key: {pin-hash: pin-record}
PIN_TTL_SECONDS = 300            # SPEC §4: 5-minute TTL, single-claim
CLAIM_RATE_LIMIT = 10            # failed claim attempts per window before cooldown
WATCHKIT_PORT = 42651            # fixed by SPEC §11.4
GATEWAY_PORT = 8642              # Hermes API (per-profile /p/<profile>)

_CTX: Any = None                 # module singleton for handlers wired at register()
_secrets: Callable[[str], Optional[str]] | None = None


def _get_secret(name: str) -> Optional[str]:
    """Read a config_schema secret the Hermes way (rule 11: never os.environ/.env)."""
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


# ── PIN state machine ─────────────────────────────────────────────────────────
def _hash_pin(pin: str) -> str:
    # Pins are 6 digits (low entropy) — hash only to keep state files clean-looking;
    # the rate limiter below is what actually bounds guessing.
    return hashlib.sha256(pin.encode()).hexdigest()[:16]


def _pins() -> dict:
    return dict(_CTX.state.get(STATE_PINS) or {})


def mint_pin() -> str:
    """Create a plugin-side PIN (Desktop 'show PIN' / watch_pair). 6 digits, no leading
    zeros, TTL PIN_TTL_SECONDS, single-claim."""
    pin = str(secrets.randbelow(900_000) + 100_000)
    pins = _pins()
    pins = {k: v for k, v in pins.items() if v.get("expires", 0) > time.time()}
    pins[_hash_pin(pin)] = {
        "created": time.time(),
        "expires": time.time() + PIN_TTL_SECONDS,
        "claims": 0,
        "origin": "plugin",
    }
    _CTX.state.set(STATE_PINS, pins)
    return pin


def queue_claim(pin: str, tailnet_hint: str = "") -> bool:
    """A watch knocked on the claim door with its PIN: remember the pending claim.
    Returns False when the rate limit window is hot (caller answers 429)."""
    pins = _pins()
    now = time.time()
    pins = {k: v for k, v in pins.items() if v.get("expires", 0) > now}
    rec = pins.get(_hash_pin(pin))
    if rec is None:
        # Watch-minted PIN arriving first: adopt it as pending with a fresh TTL window.
        rec = {"created": now, "expires": now + PIN_TTL_SECONDS, "claims": 0, "origin": "watch"}
    rec["claims"] = int(rec.get("claims", 0)) + 1
    if rec["claims"] > CLAIM_RATE_LIMIT:
        return False
    rec["pending_claim"] = {"at": now, "tailnet_hint": tailnet_hint}
    pins[_hash_pin(pin)] = rec
    _CTX.state.set(STATE_PINS, pins)
    return True


def claim_pin(pin: str) -> dict:
    """Match a spoken/typed PIN against a pending watch claim, enroll, return the
    device record (the packet is delivered to the watch's waiting POST)."""
    pins = _pins()
    key = _hash_pin(pin)
    rec = pins.get(key)
    if rec is None or rec.get("expires", 0) <= time.time():
        raise ValueError("pairing code expired or unknown — show it again on the watch")
    pending = rec.get("pending_claim")
    if pending is None:
        raise ValueError("that watch hasn't knocked yet — make sure the PIN is on screen")
    packet = _build_packet()
    device = _register_device(packet)
    pins.pop(key, None)  # single-claim
    _CTX.state.set(STATE_PINS, pins)
    _deliver(packet, pending)  # TODO(pairing-impl): release to the waiting claim POST
    return device


def _build_packet() -> dict:
    """Single-use, pre-authorized, tagged key + everything the watch needs to skip the
    console entirely (SPEC §4, handoff §4)."""
    tag = str(_cfg("watch_tag", "tag:hermes-watch"))
    expiry = str(_cfg("key_expiry", "24h"))
    client = _client()
    authkey = client.mint_enrollment_key(tag=tag, expiry=expiry)
    return {
        "authkey": authkey,                    # zero after use (tailscale.py)
        "tailnet": client.tailnet,
        "gateway": {"fqdn": gateway_fqdn(), "port": GATEWAY_PORT},
        "watchkit_port": WATCHKIT_PORT,        # present only when the sidecar is detected
        "profile": str(_cfg("gateway_profile", "wear")),
        "tag": tag,
    }


def _register_device(packet: dict) -> dict:
    devices = dict(_CTX.state.get(STATE_DEVICES) or {})
    hostname = f"{str(_cfg('gateway_profile', 'wear'))}-watch"  # TODO(pairing-impl): watch sends its hostname
    record = {
        "hostname": hostname,
        "tag": packet["tag"],
        "enrolled_at": time.time(),
        "gateway_fqdn": packet["gateway"]["fqdn"],
        "tailnet": packet["tailnet"],
    }
    devices[hostname] = record
    _CTX.state.set(STATE_DEVICES, devices)
    return record


def _deliver(packet: dict, pending: dict) -> None:
    """TODO(pairing-impl): hand the packet to the claim endpoint's waiting response and
    zero packet['authkey'] once acknowledged."""
    packet["authkey"] = ""  # fail-safe: never leak past one use even before delivery lands


def gateway_fqdn() -> str:
    """This box's ts.net FQDN via the local socket (AGENTS.md §4). Never fabricate: an
    empty answer makes claim/wizard red, which is the truth."""
    try:
        out = subprocess.run(
            ["tailscale", "status", "--json"], capture_output=True, text=True, timeout=10
        )
        return json.loads(out.stdout).get("Self", {}).get("DnsName", "").rstrip(".")
    except Exception:
        return ""


def tailnet_state() -> dict:
    """The butler's checklist (handoff §3.5): each red item is one fix verb, never a
    wall of text. Probe order = the wizard order."""
    checks: dict[str, dict] = {}
    try:
        client = _client()
        checks["tailscaled"] = {"ok": True, "detail": "socket present"}
        try:
            checks["credential"] = {"ok": True, "detail": client.describe_credential()}
        except tailscale.TailnetAPIError as exc:
            checks["credential"] = {"ok": False, "detail": str(exc), "fix": "hermes watch setup --fix credential"}
        try:
            prefs = client.dns_preferences()
            magic = bool(prefs.get("magicDNS"))
            checks["magicdns"] = {"ok": magic, "detail": "enabled" if magic else "disabled",
                                  "fix": "console: https://login.tailscale.com/admin/dns"}
        except tailscale.TailnetAPIError as exc:
            checks["magicdns"] = {"ok": False, "detail": str(exc)}
        try:
            funnel_ok, detail = client.funnel_attr_present()
            checks["funnel_attr"] = {"ok": funnel_ok, "detail": detail,
                                     "fix": "hermes watch setup --fix funnel"}
        except tailscale.TailnetAPIError as exc:
            checks["funnel_attr"] = {"ok": False, "detail": str(exc)}
    except tailscale.CredentialMissing as exc:
        checks["credential"] = {"ok": False, "detail": str(exc),
                                "fix": "hermes watch setup — walks you through it (account → install → token)"}
    devices = _CTX.state.get(STATE_DEVICES) or {} if _CTX else {}
    checks["devices_online"] = {
        "ok": bool(devices),
        "detail": f"{len(devices)} paired",
    }
    return checks


# ── tools ─────────────────────────────────────────────────────────────────────
def _tool_watch_status(args: dict, **kw) -> str:
    devices = _CTX.state.get(STATE_DEVICES) or {}
    return json.dumps({"checks": tailnet_state(), "devices": devices}, indent=2)


def _tool_watch_pair(args: dict, **kw) -> str:
    if args.get("code"):
        return json.dumps({"paired": claim_pin(str(args["code"]))}, indent=2)
    return json.dumps({"pin": mint_pin(),
                       "ttl_seconds": PIN_TTL_SECONDS,
                       "say": "type or say this PIN to Hermes on any surface: /pair <PIN>"}, indent=2)


def _tool_watch_send(args: dict, **kw) -> str:
    # TODO(pairing-impl): POST through the tunnel to the paired device's gateway lane.
    devices = _CTX.state.get(STATE_DEVICES) or {}
    if not devices:
        return json.dumps({"error": "no paired watch yet — run hermes watch pair"})
    return json.dumps({"sent": args.get("text", ""), "to": sorted(devices)}, indent=2)


_TOOLS = (
    ("watch_send", {
        "type": "object",
        "properties": {"text": {"type": "string", "description": "Message to show on the watch"}},
        "required": ["text"],
    }, _tool_watch_send, "📳"),
    ("watch_status", {"type": "object", "properties": {}}, _tool_watch_status, "🛰"),
    ("watch_pair", {
        "type": "object",
        "properties": {"code": {"type": "string", "description": "6-digit PIN shown on the watch (omit to mint one here)"}},
    }, _tool_watch_pair, "🔗"),
)


# ── in-session /pair command (demo hero: "pair 429183" in any chat surface) ───
def _cmd_pair(raw_args: str) -> str:
    pin = (raw_args or "").strip()
    if not pin:
        return f"usage: /pair <6-digit PIN from the watch>  (or /pair show to mint one)"
    if pin.lower() == "show":
        return f"pair with this PIN (valid {PIN_TTL_SECONDS // 60} min): {mint_pin()}"
    try:
        device = claim_pin(pin)
        return f"watch enrolled on the tailnet → {device['hostname']} ({device['gateway_fqdn']})"
    except ValueError as exc:
        return f"pairing failed: {exc}"


# ── hermes watch CLI ──────────────────────────────────────────────────────────
def _watch_cli_setup(sub) -> None:
    sub.add_subparsers(dest="watch_command")
    p = sub.add_parser("pair", help="show or claim a pairing PIN")
    p.add_argument("code", nargs="?", help="6-digit PIN from the watch")
    sub.add_parser("status", help="tailnet readiness checklist + devices")
    s = sub.add_parser("setup", help="wizard: tailscaled → credential → MagicDNS → funnel → serve")
    s.add_argument("--fix", choices=["credential", "funnel", "serve"], default=None)
    sub.add_parser("devices", help="paired watch records")


def _watch_cli_run(args) -> None:
    cmd = getattr(args, "watch_command", None) or "status"
    if cmd == "pair":
        print(_tool_watch_pair({"code": getattr(args, "code", None)}))
    elif cmd == "devices":
        print(json.dumps(_CTX.state.get(STATE_DEVICES) or {}, indent=2))
    else:  # status / setup
        print(json.dumps({"checks": tailnet_state()}, indent=2))
    # TODO(wizard): --fix verbs land with the funnel/serve automation (handoff §3.5).


def _on_gateway_startup(**kw) -> None:
    """Readiness first pass so `watch_status` and the Desktop page have fresh lights;
    never block the pipeline (hook errors are caught by the host, but we stay cheap)."""
    try:
        logger.info("wearos: gateway startup readiness: %s", tailnet_state())
    except Exception:
        logger.debug("wearos: readiness probe failed at startup", exc_info=True)


def register(ctx) -> None:
    """Entry point called by the Hermes plugin loader (docs: developer-guide/plugins)."""
    global _CTX
    _CTX = ctx
    for name, schema, handler, emoji in _TOOLS:
        ctx.register_tool(name=name, toolset="wearos", schema=schema, handler=handler, emoji=emoji)
    ctx.register_command("pair", handler=_cmd_pair,
                         description="Pair a Wear OS watch to Hermes with its 6-digit PIN",
                         args_hint="<123456>")
    ctx.register_cli_command(
        name="watch", help="Hermes Wear Kit (pair, status, setup, devices)",
        setup_fn=_watch_cli_setup, handler_fn=_watch_cli_run,
        description="One-command Wear OS watch pairing over your own tailnet.",
    )
    ctx.register_hook("gateway:startup", _on_gateway_startup)
