"""Hermes Wear Kit — pairing state machine, device registry, tools, CLI, hooks.

Doctrine (docs/SPEC.md §11.4, handoff §2.5): the watch enrolls ONLY into the user's own
tailnet with its own embedded tsnet node — no LAN transport, no phone relay, ever. The
funnel claim door (claim_server.py) is the single pre-enrollment network exchange; after
enrollment every byte rides the tunnel.

Flow (handoff §4):
  1. The watch cold-starts and shows a 6-digit PIN (5-min TTL, single-claim). The same
     PIN can also be minted from the plugin side (``watch_pair`` tool / ``hermes watch
     pair --show``) for the Desktop "show PIN" verb.
  2. The watch POSTs {pin, tailnet, hostname} to the claim endpoint (behind Funnel:
      https://pair.<tailnet>.ts.net/pair/claim) — Mode B — or the user speaks/types the
      PIN to Hermes anywhere (``/pair 429183``).
  3. On the FIRST match of a queued claim + a spoken PIN, the plugin mints a single-use,
      pre-authorized, tagged auth key (tailscale.py) and assembles the enrollment packet
      — {"authkey","tailnet","gateway_fqdn","gateway_port","watchkit_port","profile",
      "api_key","watchkit_token"} — onto the PIN record. The watch's polling
      GET /pair/claim?pin=… collects it ONCE; the authkey is zeroed right after.
  4. Registry lives in profile-scoped plugin state (ctx.state → HERMES_HOME/plugin-data/
      wearos/state.json): key ``devices``, keyed by the hostname THE WATCH SENT.

Wave status: claim HTTP door + delivery + wizard fix-verbs are LIVE here; the Node
sidecar stays the documented media fallback until media.py finishes its port (handoff
cut line).
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import secrets
import subprocess
import threading
import time
from pathlib import Path
from typing import Any, Callable, Optional

try:
    from . import claim_server, tailscale
except ImportError:  # flat loader outside a package context (see tests/conftest.py)
    import claim_server  # type: ignore[no-redef]
    import tailscale  # type: ignore[no-redef]

logger = logging.getLogger("hermes_wearkit")

STATE_DEVICES = "devices"        # ctx.state key: {hostname: device-record}
STATE_PINS = "pins"              # ctx.state key: {pin-hash: pin-record}
STATE_CLAIM_RATE = "claim_rate"  # ctx.state key: global miss streak + cooldown window
PIN_TTL_SECONDS = 300            # SPEC §4: 5-minute TTL, single-claim
CLAIM_RATE_LIMIT = 10            # failed claim attempts per window before cooldown
CLAIM_MISS_WINDOW = 60           # seconds the global miss streak accumulates over
CLAIM_COOLDOWN_SECONDS = 60      # how long the door slams (429) once the streak trips
PACKET_TTL_SECONDS = 900         # how long a claimed packet waits for the watch to poll
MAX_LIVE_PINS = 500              # ledger bound: random-PIN POST floods die on the cap, not the disk
WATCHKIT_PORT = 42651            # fixed by SPEC §11.4
GATEWAY_PORT = 8642              # Hermes API (per-profile /p/<profile>)
DEFAULT_CLAIM_PORT = 42653       # loopback listener behind tailscale serve/funnel
CLAIM_WAIT_SECONDS = 30          # `hermes watch pair <PIN>` waits this long for pickup

_PIN_RE = re.compile(r"[1-9][0-9]{5}")  # 6 digits, no leading zero (mint format)
_HOSTNAME_ALLOWED = re.compile(r"[^a-z0-9._-]+")

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


def _now() -> float:
    return time.time()


def _pins() -> dict:
    return dict(_CTX.state.get(STATE_PINS) or {})


def _prune_pins(pins: dict, now: float) -> dict:
    """Drop records past their horizon. A plugin-minted PIN that expired WITHOUT ever
    knocking survives as an ``expired`` tombstone for one more TTL, so the watch's late
    knock hears the truth (410 expired) instead of silently re-adopting a dead code."""
    for key, rec in list(pins.items()):
        if not isinstance(rec, dict) or rec.get("expires", 0) > now:
            continue
        tombstoned = rec.get("expired") or (rec.get("origin") == "plugin" and not rec.get("claims"))
        if tombstoned and rec.get("created", now) + 2 * PIN_TTL_SECONDS > now:
            rec["expired"] = True
            pins[key] = rec
        else:
            del pins[key]
    return pins


def _rate_hot(now: float) -> bool:
    meta = _CTX.state.get(STATE_CLAIM_RATE) or {}
    try:
        return float(meta.get("cooldown_until", 0)) > now
    except (TypeError, ValueError):
        return False


def _note_miss(now: float) -> None:
    """Count a door miss (unknown/consumed PIN, per-PIN cap hit). CLAIM_RATE_LIMIT
    misses inside CLAIM_MISS_WINDOW trip a global cooldown — 429 for everyone."""
    meta = dict(_CTX.state.get(STATE_CLAIM_RATE) or {})
    if now - float(meta.get("window_start") or 0) > CLAIM_MISS_WINDOW:
        meta = {"window_start": now, "misses": 0}
    meta["misses"] = int(meta.get("misses", 0)) + 1
    if meta["misses"] >= CLAIM_RATE_LIMIT:
        meta["cooldown_until"] = now + CLAIM_COOLDOWN_SECONDS
        meta["misses"] = 0
    _CTX.state.set(STATE_CLAIM_RATE, meta)


def _clear_misses() -> None:
    _CTX.state.set(STATE_CLAIM_RATE, {})


def mint_pin() -> str:
    """Create a plugin-side PIN (Desktop 'show PIN' / watch_pair). 6 digits, no leading
    zeros, TTL PIN_TTL_SECONDS, single-claim."""
    pins = _pins()
    pins = _prune_pins(pins, _now())
    pin = ""
    for _ in range(8):
        pin = str(secrets.randbelow(900_000) + 100_000)
        if _hash_pin(pin) not in pins:
            break
    pins[_hash_pin(pin)] = {
        "created": _now(),
        "expires": _now() + PIN_TTL_SECONDS,
        "claims": 0,
        "origin": "plugin",
    }
    _CTX.state.set(STATE_PINS, pins)
    return pin


def queue_claim(pin: str, tailnet_hint: str = "", hostname: str = "") -> str:
    """A watch knocked on the claim door with its PIN: remember the pending claim
    (adopting watch-minted PINs on first knock). Returns the outcome the door maps to
    HTTP: 'queued' (202) | 'hot' (429) | 'expired'/'consumed' (410) | 'malformed' (400)."""
    if not isinstance(pin, str) or not _PIN_RE.fullmatch(pin):
        return "malformed"
    now = _now()
    if _rate_hot(now):
        return "hot"
    pins = _prune_pins(_pins(), now)
    rec = pins.get(_hash_pin(pin))
    if rec is None:
        # Watch-minted PIN arriving first: adopt it as pending with a fresh TTL window.
        if len(pins) >= MAX_LIVE_PINS:
            _note_miss(now)  # random-PIN flood creating ledger records faster than TTLs die
            return "hot"
        rec = {"created": now, "expires": now + PIN_TTL_SECONDS, "claims": 0, "origin": "watch"}
    if rec.get("expired"):
        _note_miss(now)
        return "expired"
    if rec.get("delivered"):
        _note_miss(now)
        return "consumed"
    rec["claims"] = int(rec.get("claims", 0)) + 1
    if rec["claims"] > CLAIM_RATE_LIMIT:
        _note_miss(now)
        return "hot"
    prev = rec.get("pending_claim") or {}
    rec["pending_claim"] = {
        "at": now,
        "tailnet_hint": tailnet_hint or prev.get("tailnet_hint", ""),
        "hostname": hostname or prev.get("hostname", ""),
    }
    pins[_hash_pin(pin)] = rec
    _CTX.state.set(STATE_PINS, pins)
    return "queued"


def claim_pin(pin: str) -> dict:
    """Match a spoken/typed PIN against a pending watch claim, enroll, return the
    device record. The packet rides along on the PIN record until the watch's polling
    GET collects it (poll_packet)."""
    if not isinstance(pin, str) or not _PIN_RE.fullmatch(pin):
        raise ValueError("pairing code must be 6 digits, 100000-999999")
    now = _now()
    pins = _prune_pins(_pins(), now)
    rec = pins.get(_hash_pin(pin))
    if rec is None or rec.get("expired") or rec.get("expires", 0) <= now:
        raise ValueError("pairing code expired or unknown — show it again on the watch")
    if rec.get("packet") or rec.get("delivered"):
        raise ValueError("that pairing code was already claimed")
    pending = rec.get("pending_claim")
    if pending is None:
        raise ValueError("that watch hasn't knocked yet — make sure the PIN is on screen")
    packet = _build_packet()
    device = _register_device(packet, hostname=str(pending.get("hostname", "")),
                              tailnet_hint=str(pending.get("tailnet_hint", "")))
    rec.pop("pending_claim", None)
    rec["packet"] = packet
    rec["claimed_at"] = now
    rec["expires"] = now + PACKET_TTL_SECONDS
    pins[_hash_pin(pin)] = rec
    _CTX.state.set(STATE_PINS, pins)
    _clear_misses()  # a successful pairing clears the door's suspicion
    return device


def poll_packet(pin: str) -> tuple[str, Optional[dict]]:
    """The watch's polling GET. ('pending', None) while the user hasn't claimed yet;
    ('ready', packet) ONCE — the stored authkey is zeroed on pickup; ('gone', None) for
    unknown/expired/consumed; ('hot', None) during the global cooldown."""
    if not isinstance(pin, str) or not _PIN_RE.fullmatch(pin):
        return "gone", None
    now = _now()
    if _rate_hot(now):
        return "hot", None
    pins = _prune_pins(_pins(), now)
    rec = pins.get(_hash_pin(pin))
    if rec is None or rec.get("expired") or rec.get("delivered") or rec.get("expires", 0) <= now:
        _note_miss(now)
        return "gone", None
    packet = rec.get("packet")
    if packet is None:
        return "pending", None  # knocked, awaiting the user's claim — a 204, not a miss
    rec = dict(rec)
    packet = dict(packet)
    rec.pop("packet", None)
    rec["delivered"] = True
    pins[_hash_pin(pin)] = rec
    _CTX.state.set(STATE_PINS, pins)
    return "ready", packet  # the ledger copy is already packet-less above — zeroed


def _build_packet() -> dict:
    """Single-use, pre-authorized, tagged key + everything the watch needs to skip the
    console entirely (SPEC §4, handoff §4). Shape is the claim API contract — the watch
    client is built against EXACTLY these keys; adding or nesting breaks the lane."""
    tag = str(_cfg("watch_tag", "tag:hermes-watch"))
    expiry = str(_cfg("key_expiry", "24h"))
    profile = str(_cfg("gateway_profile", "wear"))
    client = _client()
    authkey = client.mint_enrollment_key(tag=tag, expiry=expiry)
    fqdn = gateway_fqdn()
    if not fqdn:
        # Loud fail beats a packet the watch can't dial — and never leave the minted
        # key orphaned: this is exactly what last_key_id exists for.
        try:
            client.expire_key(client.last_key_id)
        except Exception:
            pass
        raise ValueError("gateway FQDN unknown (tailscale status reports none) — "
                         "cannot hand the watch a tunnel it can't dial")
    return {
        "authkey": authkey,                       # zeroed after pickup (poll_packet)
        "tailnet": (client.tailnet if client.tailnet not in ("", "-")
                    else tailnet_name()) or "-",
        "gateway_fqdn": fqdn,
        "gateway_port": GATEWAY_PORT,
        "watchkit_port": WATCHKIT_PORT,           # present only when the sidecar is detected
        "profile": profile,
        "api_key": _gateway_api_key(profile),
        "watchkit_token": _get_secret("watchkit_token"),  # nullable by contract
    }


def _sanitize_hostname(name: str) -> str:
    clean = _HOSTNAME_ALLOWED.sub("-", str(name or "").strip().lower()).strip("-.")
    return clean[:64]


def _register_device(packet: dict, hostname: str = "", tailnet_hint: str = "") -> dict:
    """Registry entry under the hostname THE WATCH SENT (falling back to
    <profile>-watch when the knock carried none). Re-pairing the same watch updates the
    record in place and notes re_enrolled_at; first_seen never moves."""
    devices = dict(_CTX.state.get(STATE_DEVICES) or {})
    name = _sanitize_hostname(hostname) or f"{packet['profile']}-watch"
    now = _now()
    prev = devices.get(name) or {}
    record = {
        "hostname": name,
        "tag": str(_cfg("watch_tag", "tag:hermes-watch")),
        "tailnet": packet["tailnet"],
        "gateway_fqdn": packet["gateway_fqdn"],
        "profile": packet["profile"],
        "first_seen": prev.get("first_seen") or prev.get("enrolled_at") or now,
        "enrolled_at": now,
    }
    if prev:
        record["re_enrolled_at"] = now
    if tailnet_hint:
        record["tailnet_hint"] = tailnet_hint
    devices[name] = record
    _CTX.state.set(STATE_DEVICES, devices)
    return record


def _gateway_api_key(profile: str) -> Optional[str]:
    """Resolve the gateway profile's API_SERVER_KEY for the packet.

    Measured against hermes-agent v0.22.5 (agent/secret_scope.py):
      get_secret(name: str, default: Optional[str] = None) -> Optional[str]
    reads a CONTEXTVAR-bound per-task scope — there is NO profile argument. Our claim
    server answers on its own threads, where no scope is bound: with multiplexing
    active a bare read RAISES UnscopedSecretError (fail-closed), and in a CLI process
    it silently falls through to os.environ — the LAUNCH profile's value, which can be
    the wrong profile's key. So we bind the configured profile's own scope explicitly
    (build_profile_secret_scope is a pure filesystem read — thread-safe), and only
    then fall back: ambient read → declared gateway_api_key secret (README).
    """
    try:  # tier 1: the configured profile's own scope, on any thread
        scope_home = _profile_home(profile)
        if scope_home is not None:
            from agent.secret_scope import (build_profile_secret_scope, get_secret,
                                            reset_secret_scope, set_secret_scope)
            token = set_secret_scope(build_profile_secret_scope(scope_home),
                                     profile_home=str(scope_home))
            try:
                key = get_secret("API_SERVER_KEY")
            finally:
                reset_secret_scope(token)
            if key:
                return key
    except Exception:
        logger.debug("wearos: profile-scoped API_SERVER_KEY read failed", exc_info=True)
    try:  # tier 2: ambient scope / single-profile os.environ pass-through
        from agent.secret_scope import get_secret as _gs
        key = _gs("API_SERVER_KEY")
        if key:
            return key
    except Exception:  # UnscopedSecretError under multiplex on a server thread: expected
        pass
    return _get_secret("gateway_api_key")  # tier 3: declared fallback (config_schema)


def _profile_home(profile: str) -> Optional[Path]:
    """Named profile's HERMES_HOME via the public host constants: ``<root>/profiles/<name>``
    (PROFILE_ID_RE is THE canonical id gate — same regex hermes_cli.profiles enforces,
    and it refuses traversal). ``hermes_cli.profiles.get_profile_dir`` would be the
    authority but is a private module per validate rule 9; the layout is stable contract."""
    profile = str(profile or "default").strip().lower()
    try:
        from hermes_constants import PROFILE_ID_RE, get_default_hermes_root
        root = Path(get_default_hermes_root())
        if profile == "default":
            return root
        if PROFILE_ID_RE.fullmatch(profile):
            return root / "profiles" / profile
    except Exception:
        pass
    return None


# ── profile enablement (watch_enable / `hermes watch enable`) ─────────────────
# The pane's checklist and the CLI share ONE engine: a MINIMAL LINE-LEVEL edit of
# each profile's config.yaml. No yaml round-trip — a dump would silently drop the
# user's comments, blank lines, key order and quoting; the anchor walker below
# touches only the item it must add/remove, or appends one small block when the
# section is absent. Shapes handled are exactly what core and the README document:
#
#   plugins:            plugins:                plugins:
#     enabled:            enabled: [a]            enabled: {}      /  (no plugins: at all)
#     - a               (no enabled key)
#
# Anything less recognizable (a scalar value where our list belongs) is reported
# as a refusal, not a rewrite: the file is the user's, and we only ever hold an
# anchor we actually understand.
_PLUGINS = "wearos"                    # the id as written in plugins.enabled
DEFAULT_GATEWAY_PROFILE = "wear"       # entries.settings.gateway_profile default
RELEASES_LATEST = "https://github.com/borealBytes/hermes-wearos/releases/latest"
# Same pattern as hermes_constants.PROFILE_ID_RE / hermes_cli.profiles (import-time
# fallback for hosts where that module is unresolvable; the host copy still wins).
_PROFILE_ID_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")
# hermes_constants._PROFILE_IDENTITY_MARKERS — mirrored so the pane checklist sees
# exactly the profiles `hermes profile list` sees (ghost dirs carry none of these).
_PROFILE_IDENTITY_MARKERS = ("config.yaml", ".env", "SOUL.md", "profile.yaml",
                             "auth.json", "state.db")
_DELETED_MARK = ".deleted"  # hermes_constants._DELETED_PROFILES_DIR


def _hermes_root() -> Optional[Path]:
    """HERMES_HOME through the host's own resolver (env-sandboxable, memoized),
    falling back to the raw env var; None when neither answers."""
    try:
        from hermes_constants import get_default_hermes_root
        return Path(get_default_hermes_root())
    except Exception:
        env = str(os.environ.get("HERMES_HOME", "") or "").strip()
        return Path(env) if env else None


def _line_indent(line: str) -> int:
    stripped = line.rstrip("\r\n")
    return len(stripped) - len(stripped.lstrip(" \t"))


def _line_content(line: str) -> str:
    return line.rstrip("\r\n").strip()


def _after_comment(value: str) -> str:
    """YAML: a '#' opens a comment only at line start or after whitespace."""
    for i, ch in enumerate(value):
        if ch == "#" and (i == 0 or value[i - 1] in " \t"):
            return value[:i].strip()
    return value.strip()


def _unquote(item: str) -> str:
    if len(item) >= 2 and item[0] == item[-1] and item[0] in "\"'":
        return item[1:-1]
    return item


def _split_eol(text: str) -> tuple[list, str]:
    """Lines keeping their terminators, plus the file's dominant newline (CRLF
    survives the patch). The caller works on the normalized COPY and returns the
    ORIGINAL text untouched whenever nothing changed — no trailing-newline diffs."""
    nl = "\r\n" if "\r\n" in text else "\n"
    if not text:
        return [], nl
    return [ln if ln.endswith(("\n", "\r\n")) else ln + nl
            for ln in text.splitlines(keepends=True)], nl


def _plugins_span(lines: list) -> Optional[dict]:
    """Locate the top-level ``plugins:`` block → {at, lo, hi}. hi excludes the
    trailing blank/comment run so inserts land after the last real line (a comment
    sitting there belongs to the NEXT top-level key). None when the key is absent
    or carries a nontrivial inline value (no safe spot for children)."""
    for i, ln in enumerate(lines):
        c = _line_content(ln)
        if c.startswith("#"):
            continue
        if _line_indent(ln) != 0 or not c.startswith("plugins:"):
            continue
        value = _after_comment(c[len("plugins:"):])
        if value not in ("", "{}", "[]"):
            return None  # `plugins: <scalar>` — no safe spot for children; refuse, don't rewrite
        j = i + 1
        while j < len(lines):
            cj = _line_content(lines[j])
            if cj == "" or cj.startswith("#") or _line_indent(lines[j]) > 0:
                j += 1
                continue
            break
        k = j
        while k > i + 1:
            tail = _line_content(lines[k - 1])
            if tail == "" or tail.startswith("#"):
                k -= 1
            else:
                break
        return {"at": i, "lo": i + 1, "hi": k, "value": value}
    return None


def _find_key(lines: list, lo: int, hi: int, key: str) -> Optional[tuple]:
    """(idx, indent, inline_value) of the first ``key:`` in the block span — only
    ever called with spans that already exclude parent siblings, so no cross-section
    matches are reachable."""
    for j in range(lo, hi):
        c = _line_content(lines[j])
        if c == "" or c.startswith("#") or _line_indent(lines[j]) < 1:
            continue
        if c.startswith(key + ":"):
            rest = c[len(key) + 1:]
            if rest == "" or rest[0] in " \t":
                return (j, _line_indent(lines[j]), _after_comment(rest))
    return None


def _child_span(lines: list, idx: int, hi: int) -> tuple:
    """Half-open span of the block under the key at idx (lines strictly deeper than
    the key, plus interior blanks/comments; trailing blanks/comments trimmed)."""
    ind = _line_indent(lines[idx])
    j = idx + 1
    while j < hi:
        c = _line_content(lines[j])
        if c == "" or c.startswith("#") or _line_indent(lines[j]) > ind:
            j += 1
            continue
        break
    k = j
    while k > idx + 1:
        tail = _line_content(lines[k - 1])
        if tail == "" or tail.startswith("#"):
            k -= 1
        else:
            break
    return idx + 1, k


def _first_child_indent(lines: list, lo: int, hi: int) -> int:
    """Indent of the first real child line in a block span (style reuse), 0 if none."""
    for j in range(lo, hi):
        c = _line_content(lines[j])
        if c != "" and not c.startswith("#"):
            return _line_indent(lines[j])
    return 0


def _block_items(lines: list, anchor: int, hi: int) -> tuple:
    """([ (idx, dash_indent, value) ... ], end_idx) for the block sequence under the
    key at anchor. PyYAML style (dashes at the key indent) and deeper-indent style
    both read; a blank/comment ends the run conservatively; complex items (nested
    maps) are consumed whole and contribute their first line's value."""
    items: list = []
    key_indent = _line_indent(lines[anchor])
    j = anchor + 1
    while j < hi:
        c = _line_content(lines[j])
        if c == "" or c.startswith("#") or _line_indent(lines[j]) < key_indent:
            break
        if not (c == "-" or c.startswith("- ")):
            break
        items.append((j, _line_indent(lines[j]), _after_comment(c[1:].strip())))
        j += 1
        while j < hi and _line_content(lines[j]) != "" and _line_indent(lines[j]) > items[-1][1]:
            j += 1  # continuation lines of this item — opaque to us
    return items, j


def _flow_items(value: str) -> Optional[list]:
    """Items of an inline ``[a, b]`` list; None when the value isn't flow."""
    v = _after_comment(value)
    if not (v.startswith("[") and v.endswith("]")):
        return None
    inner = v[1:-1].strip()
    return [p.strip() for p in inner.split(",")] if inner else []


def _rewrite_key_line(lines: list, idx: int, key: str, new_value: str, nl: str) -> None:
    """Replace the inline value on a key line, keeping its indent and trailing comment."""
    old = lines[idx].rstrip("\r\n")
    m = re.match(r"^([ \t]*" + re.escape(key) + r":[ \t]*)([^#]*?)([ \t]*#[^\r\n]*)?$", old)
    head = m.group(1) if m else " " * _line_indent(lines[idx]) + key + ": "
    comment = (m.group(3) or "") if m else ""
    if new_value and not head.endswith((" ", "\t")):
        head = head.rstrip() + " "
    if not new_value:
        head = head.rstrip()
    lines[idx] = head + new_value + comment + nl


def _new_plugins_block(nl: str, gateway_profile: str) -> str:
    return "".join([
        "plugins:" + nl,
        "  enabled:" + nl,
        "  - " + _PLUGINS + nl,
        "  entries:" + nl,
        "    " + _PLUGINS + ":" + nl,
        "      settings:" + nl,
        f"        gateway_profile: {gateway_profile}" + nl,
    ])


def _ensure_enabled_item(lines: list, span: dict, enable: bool, nl: str) -> bool:
    """True when lines changed. Item style follows the list already there; a fresh
    list lands PyYAML-style (dash at the key's indent)."""
    en = _find_key(lines, span["lo"], span["hi"], "enabled")
    if en is None:
        if not enable:
            return False
        child = _first_child_indent(lines, span["lo"], span["hi"]) or 2
        lines[span["hi"]:span["hi"]] = [
            " " * child + "enabled:" + nl,
            " " * child + "- " + _PLUGINS + nl,
        ]
        return True
    j, ind, value = en
    if value == "[]":
        _rewrite_key_line(lines, j, "enabled", "", nl)
    if value in ("", "{}", "[]"):
        items, end = _block_items(lines, j, span["hi"])
        hit = next((it for it in items if _unquote(it[2]) == _PLUGINS), None)
        if enable and hit is None:
            dash = items[0][1] if items else ind
            lines.insert(end if items else j + 1, " " * dash + "- " + _PLUGINS + nl)
            return True
        if not enable and hit is not None:
            del lines[hit[0]]
            return True
        return False
    flow = _flow_items(value)
    if flow is not None:
        has = any(_unquote(p) == _PLUGINS for p in flow)
        if enable == has:
            return False
        keep = flow + [_PLUGINS] if enable else [p for p in flow if _unquote(p) != _PLUGINS]
        _rewrite_key_line(lines, j, "enabled", "[" + ", ".join(keep) + "]", nl)
        return True
    # bare scalar (`enabled: wearos`) — the ONLY value that counts as listed
    if _unquote(value) == _PLUGINS and not enable:
        _rewrite_key_line(lines, j, "enabled", "[]", nl)
        return True
    return False


def _ensure_entries(lines: list, span: dict, gateway_profile: str, nl: str) -> bool:
    """Create plugins.entries.wearos.settings.gateway_profile at whatever depth is
    missing — never overwrite a value the user already set."""
    ent = _find_key(lines, span["lo"], span["hi"], "entries")
    if ent is None:
        child = _first_child_indent(lines, span["lo"], span["hi"]) or 2
        lines[span["hi"]:span["hi"]] = [
            " " * child + "entries:" + nl,
            " " * (child + 2) + _PLUGINS + ":" + nl,
            " " * (child + 4) + "settings:" + nl,
            " " * (child + 6) + f"gateway_profile: {gateway_profile}" + nl,
        ]
        return True
    j, ind, value = ent
    if value == "{}":
        _rewrite_key_line(lines, j, "entries", "", nl)
    if value not in ("", "{}"):
        return False  # `entries: <scalar>` — not ours to fix
    clo, chi = _child_span(lines, j, span["hi"])
    wr = _find_key(lines, clo, chi, _PLUGINS)
    child = _first_child_indent(lines, clo, chi) or (ind + 2)
    if wr is None:
        lines[j + 1:j + 1] = [
            " " * child + _PLUGINS + ":" + nl,
            " " * (child + 2) + "settings:" + nl,
            " " * (child + 4) + f"gateway_profile: {gateway_profile}" + nl,
        ]
        return True
    w, wind, wval = wr
    if wval == "{}":
        _rewrite_key_line(lines, w, _PLUGINS, "", nl)
    if wval not in ("", "{}"):
        return False
    slo, shi = _child_span(lines, w, chi)
    st = _find_key(lines, slo, shi, "settings")
    schild = _first_child_indent(lines, slo, shi) or (wind + 2)
    if st is None:
        lines[w + 1:w + 1] = [
            " " * schild + "settings:" + nl,
            " " * (schild + 2) + f"gateway_profile: {gateway_profile}" + nl,
        ]
        return True
    s, sind, sval = st
    if sval == "{}":
        _rewrite_key_line(lines, s, "settings", "", nl)
    if sval not in ("", "{}"):
        return False
    glo, ghi = _child_span(lines, s, shi)
    if _find_key(lines, glo, ghi, "gateway_profile") is not None:
        return False  # user's value survives
    lines[s + 1:s + 1] = [" " * ((sind + 2)) + f"gateway_profile: {gateway_profile}" + nl]
    return True


def enablement_patch(text: str, enable: bool,
                     gateway_profile: str = DEFAULT_GATEWAY_PROFILE) -> tuple:
    """(new_text, changed) — add/remove ``wearos`` from plugins.enabled in config.yaml
    TEXT, and on enable make sure plugins.entries.wearos.settings.gateway_profile
    exists (created with ``gateway_profile``, never overwritten). Pure line-level edit:
    comments, spacing, key order and CRLF survive; disable leaves the settings block
    standing (re-enabling keeps the user's tuning). Idempotent both ways; unrecognized
    shapes report unchanged rather than rewritten."""
    lines, nl = _split_eol(text)
    span = _plugins_span(lines)
    if span is None:
        if not enable:
            return text, False
        if any(_line_indent(ln) == 0 and _line_content(ln).startswith("plugins:")
               for ln in lines):
            return text, False  # a scalar `plugins:` exists — refuse, never duplicate the key
        base = "".join(lines)
        glue = nl if base.strip() else ""
        return base + glue + _new_plugins_block(nl, gateway_profile), True
    changed = False
    if span["value"]:  # `plugins: {}` / `plugins: []` — promote the empty inline node
        _rewrite_key_line(lines, span["at"], "plugins", "", nl)
        changed = True
    changed |= _ensure_enabled_item(lines, span, enable, nl)
    if enable:
        span = _plugins_span(lines)  # an insertion above shifted the indices
        changed |= _ensure_entries(lines, span, gateway_profile, nl)
    if not changed:
        return text, False
    return "".join(lines), True


def _wearos_enabled(text: str) -> bool:
    """The checkbox truth for the pane: is wearos LISTED in plugins.enabled? (the
    settings block alone never counts — enablement is the list)."""
    lines, _ = _split_eol(text)
    span = _plugins_span(lines)
    if span is None:
        return False
    en = _find_key(lines, span["lo"], span["hi"], "enabled")
    if en is None:
        return False
    j, ind, value = en
    if value in ("", "{}", "[]"):
        items, _end = _block_items(lines, j, span["hi"])
        return any(_unquote(v) == _PLUGINS for _, _, v in items)
    flow = _flow_items(value)
    if flow is not None:
        return any(_unquote(p) == _PLUGINS for p in flow)
    return _unquote(value) == _PLUGINS


def _profile_config_path(name: str) -> tuple[Optional[Path], str]:
    """(config.yaml path, error) for a profile ON THIS MACHINE — default = the root
    config, named = ``<root>/profiles/<name>`` ONLY when the host's own listing gates
    it as live (valid id, identity marker, no tombstone — ghost dirs can't be adopted)."""
    name = str(name or "").strip().lower()
    root = _hermes_root()
    if root is None:
        return None, "cannot resolve HERMES_HOME from this process"
    if name == "default":
        return root / "config.yaml", ""
    if name not in {p.name for p in _named_profile_dirs(root)}:
        return None, f"no profile {name!r} on this machine"
    return root / "profiles" / name / "config.yaml", ""


def _named_profile_dirs(root: Path) -> list:
    """Live named-profile dirs under ``<root>/profiles`` — the same gate hermes_cli.profiles
    uses (never ``default``, id-grammar legal, identity marker, not tombstoned): cron/log
    ghost shells must not surface in the pane checklist (#95188 lesson)."""
    try:
        children = sorted((root / "profiles").iterdir(), key=lambda p: p.name)
    except OSError:
        return []
    out = []
    for child in children:
        try:
            if child.name == "default" or not _PROFILE_ID_RE.fullmatch(child.name):
                continue
            if not child.is_dir():
                continue
            if not any((child / m).is_file() for m in _PROFILE_IDENTITY_MARKERS):
                continue
            if (root / "profiles" / _DELETED_MARK / child.name).exists():
                continue
            out.append(child)
        except OSError:
            pass
    return out


def _all_profile_names() -> list:
    """Every profile on the box: default (root config) + each live profiles/<dir>."""
    root = _hermes_root()
    if root is None:
        return []
    return ["default"] + [p.name for p in _named_profile_dirs(root)]


def _parse_agents(agents) -> list:
    """["wear","wear-demo"] | "wear,wear-demo" | "all" (also None/"" → all)."""
    if agents is None or (isinstance(agents, str) and not agents.strip()):
        return _all_profile_names()
    if isinstance(agents, str):
        parts = [p.strip().lower() for p in agents.split(",") if p.strip()]
        return _all_profile_names() if "all" in parts else parts
    if isinstance(agents, (list, tuple)):
        out: list = []
        for part in agents:
            out.extend(p.strip().lower() for p in str(part).split(",") if p.strip())
        return out
    return []


def _revision_ledgers(root: Path) -> tuple:
    """(root revision, {profile: revision}) straight from core's own
    .install-metadata.json ledgers. An agent with a per-profile copy freezes at
    the SHA it was installed with (core updates it only via the card's
    'Update to pin') — without this the pane shows one plugin wearing three
    versions and every reader blames us."""
    def _rev(meta: Path) -> Optional[str]:
        try:
            entry = (json.loads(meta.read_text(encoding="utf-8")).get("wearos") or {})
        except (OSError, ValueError):
            return None
        return str(entry.get("revision") or "") or None
    return _rev(root / "plugins" / ".install-metadata.json"), {
        p.name: _rev(p / "plugins" / ".install-metadata.json") for p in _named_profile_dirs(root)
    }


def _profile_inventory() -> list:
    """[{name, enabled, has_config, pin, own_copy, update_available}] — the pane's
    step-2 checklist data; it rides watch_status (the call the pane already makes)
    rather than a second tool round-trip."""
    root = _hermes_root()
    root_rev, per_profile = (_revision_ledgers(root) if root else (None, {}))
    out = []
    for name in _all_profile_names():
        path, _err = _profile_config_path(name)
        if path is None:
            continue
        try:
            text = path.read_text(encoding="utf-8") if path.is_file() else ""
        except OSError:
            text = ""
        own = per_profile.get(name) if name else None
        rev = own or root_rev
        out.append({"name": name, "enabled": _wearos_enabled(text), "has_config": bool(text),
                    "pin": rev[:8] if rev else None,
                    "own_copy": bool(own),
                    "update_available": bool(own and root_rev and own != root_rev)})
    return out


def _write_config(path: Path, text: str) -> None:
    """Atomic in-place write; keeps the file's mode (config.yaml holds secret-adjacent
    routing and is often 0600 — and new files get that mode, not the umask's 0644)."""
    try:
        mode = path.stat().st_mode & 0o777
    except OSError:
        mode = 0o600
    tmp = path.with_name(path.name + ".wearos.tmp")
    with open(tmp, "w", encoding="utf-8", newline="") as fh:  # newline='' → CRLF intact
        fh.write(text)
    os.chmod(tmp, mode)
    os.replace(tmp, path)


def apply_enablement(names: list, enable: bool) -> list:
    """The engine behind BOTH the watch_enable tool and `hermes watch enable`.
    Per profile: {name, before, after, changed} — or {name, error}."""
    gp = str(_cfg("gateway_profile", DEFAULT_GATEWAY_PROFILE) or DEFAULT_GATEWAY_PROFILE)
    results = []
    for raw in names:
        name = str(raw or "").strip().lower()
        path, err = _profile_config_path(name)
        if path is None:
            results.append({"name": name or str(raw), "error": err})
            continue
        try:
            before_text = path.read_text(encoding="utf-8") if path.is_file() else ""
        except OSError as exc:
            results.append({"name": name, "error": f"cannot read config.yaml: {exc}"})
            continue
        after_text, changed = enablement_patch(before_text, enable, gateway_profile=gp)
        if changed:
            try:
                _write_config(path, after_text)
            except OSError as exc:
                results.append({"name": name, "error": f"cannot write config.yaml: {exc}"})
                continue
        results.append({"name": name,
                        "before": _wearos_enabled(before_text),
                        "after": _wearos_enabled(after_text),
                        "changed": changed})
    return results


def gateway_fqdn() -> str:
    """This box's ts.net FQDN via the local socket. Measured on 1.102.3: the field is
    Self.DNSName (capitals — AGENTS.md's old `.Self.DnsName` reads null); no
    fabrication: an empty answer makes claim/wizard red, which is the truth."""
    try:
        out = subprocess.run(
            ["tailscale", "status", "--json"], capture_output=True, text=True, timeout=10
        )
        self_ = json.loads(out.stdout).get("Self", {})
        fqdn = str(self_.get("DNSName") or self_.get("DnsName") or "")
        return fqdn.rstrip(".")
    except Exception:
        return ""


def tailnet_name() -> str:
    """Real tailnet handle (CurrentTailnet.Name, measured location) for packets/registry
    — never the '-' placeholder, which is API-internal only."""
    try:
        out = subprocess.run(
            ["tailscale", "status", "--json"], capture_output=True, text=True, timeout=10
        )
        return str(json.loads(out.stdout).get("CurrentTailnet", {}).get("Name", ""))
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
                                  "fix": "console: Network ▸ DNS — enable MagicDNS (console.tailscale.com/admin)"}
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
    port = _claim_port()
    funneled = False
    try:
        fs = subprocess.run(["tailscale", "funnel", "status"],
                            capture_output=True, text=True, timeout=5)
        funneled = fs.returncode == 0 and f":{port}" in ((fs.stdout or "") + (fs.stderr or ""))
    except Exception:
        pass
    door_up = claim_server.door_alive(port)
    checks["claim_door"] = {
        "ok": funneled and door_up,
        "detail": (f"127.0.0.1:{port} — funneled:{'yes' if funneled else 'no'}, "
                   f"door:{'up' if door_up else 'down'}"),
        "fix": "hermes watch setup --fix serve (fronts the door); hermes watch pair starts the door",
    }
    devices = _CTX.state.get(STATE_DEVICES) or {} if _CTX else {}
    checks["devices_online"] = {
        "ok": bool(devices),
        "detail": f"{len(devices)} paired",
    }
    return checks


# ── claim door lifecycle ──────────────────────────────────────────────────────
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
    """Start the claim door if nothing is answering on the configured port (the
    gateway's hook usually beat us here). Returns True when a door answers."""
    port = _claim_port()
    if _door_listening():
        return True
    try:
        claim_server.start_server(port, claim_server.ClaimBackend.from_module())
        return True
    except OSError:
        return claim_server.door_alive(port)  # raced with another starter: fine


def _await_pickup(pin: str, timeout: float) -> str:
    """Poll the ledger until the watch collects the packet. 'delivered' | 'waiting'."""
    deadline = _now() + timeout
    while _now() < deadline:
        rec = _pins().get(_hash_pin(pin)) or {}
        if rec.get("delivered"):
            return "delivered"
        if rec.get("expired") or (rec and rec.get("expires", 0) <= _now()):
            return "pruned"
        time.sleep(1.0)
    return "waiting"


# ── wizard fix-verbs (hermes watch setup --fix …) ─────────────────────────────
def _wizard_fix(verb: str, yes: bool = False) -> None:
    if verb == "credential":
        _fix_credential()
    elif verb == "funnel":
        _fix_funnel(yes=yes)
    elif verb == "serve":
        _fix_serve()
    else:
        print(f"unknown --fix {verb!r} — try credential | funnel | serve")


def _fix_credential() -> None:
    print("· validating the Tailscale credential (GET /tailnet/-/devices)…")
    try:
        client = _client()
    except tailscale.CredentialMissing as exc:
        print(f"  ✗ {exc}")
        print("  set one: hermes plugins configure wearos — prefer the OAuth pair")
        print("  (tailscale_oauth_client_id + tailscale_oauth_client_secret, scoped to")
        print("  auth_keys + the watch tag) or the tailscale_api_token secret.")
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
    # ACL attribute half of the funnel story. The OTHER half — the tailnet-level toggle —
    # has no API endpoint (measured 2026-11-06: /prefs/funnel 404s); it is console-only
    # (Settings ▸ General ▸ Funnel ▸ Manage). `--fix serve` is what actually discovers a
    # disabled toggle, straight from the tailscale CLI's own error + link.
    try:
        ok, detail = client.funnel_attr_present()
    except tailscale.TailnetAPIError as exc:  # CredentialMissing included: it's the base
        print(f"  ✗ cannot read the tailnet ACL: {exc}")
        print("  run `hermes watch setup --fix credential` first.")
        return
    if ok:
        print(f"  ✓ funnel attribute already granted — {detail}")
        return
    print(f"  ✗ {detail}")
    tag = str(_cfg("watch_tag", "tag:hermes-watch"))
    print('  two-section diff (add to the policy file — the tag must exist before a')
    print('  key can carry it, and nodeAttrs entries are {target,attr} — all measured):')
    print(json.dumps({"tagOwners": {tag: ["autogroup:member"]},
                      "nodeAttrs": [{"target": [tag], "attr": ["funnel"]}]},
                     indent=2).replace("\n", "\n        "))
    print("        console: console.tailscale.com/admin/acls/visual (Access controls ▸ Policies ▸ JSON editor)")
    if not yes:
        print("  re-run with --yes to apply this preview (replaces the tailnet policy file, If-Match guarded).")
        print("  NOTE: your commented legacy policy renders as clean JSON after a replace —")
        print("  same rules, comments dropped. Preview first, obviously.")
        return
    print("  · preview approved (--yes) — replacing the ACL (etag-guarded)…")
    try:
        client.add_funnel_attr(tag)
    except tailscale.TailnetAPIError as exc:
        print(f"  ✗ replace refused: {exc}")
        print("    (conflict = someone edited the ACL meanwhile; apply the diff above in the console)")
        return
    ok, detail = client.funnel_attr_present()
    print(f"  {'✓' if ok else '✗'} funnel_attr: {detail}")


def _fix_serve() -> None:
    """Front the claim door AND learn the funnel-toggle truth in one live attempt:
    `tailscale funnel` fails with 'Funnel is not enabled on your tailnet' + a console
    link when the (console-only, no-API — measured) toggle is off."""
    port = int(_cfg("claim_port", 42653))
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
    # /pair/claim, so nothing else is internet-reachable even mounted at /.
    # Measured to the bottom: on a DISABLED toggle `tailscale funnel` prints the enable
    # link and then WAITS (no flag disables this; --yes makes it wait forever; /dev/tty
    # and stdin tricks irrelevant — it polls). So we EMBRACE it: the wizard stays alive
    # up to 4 min, and the moment the user clicks Funnel→enable the command proceeds
    # and applies itself. One click, zero re-runs.
    cmd = ["tailscale", "funnel", "--bg", "--https=443", f"http://127.0.0.1:{port}"]
    print(f"  · running: {' '.join(cmd)}")
    # The enable approval lives at a per-NODE link (docs Jan 2026: the CLI "triggers a
    # web interface that prompts you to approve enabling Funnel"; no console toggle
    # exists in the redesign). We compute it ourselves so it prints BEFORE the wait.
    try:
        st = json.loads(subprocess.run(["tailscale", "status", "--json"],
                                       capture_output=True, text=True, timeout=10).stdout)
        node_id = st.get("Self", {}).get("ID", "")
        if node_id:
            print(f"    enable (one click, approves THIS node): https://login.tailscale.com/f/funnel?node={node_id}")
    except Exception:
        pass
    print("    (waiting up to 4 min for the approval; it applies itself once clicked)")
    try:
        run = subprocess.run(cmd, capture_output=True, text=True, timeout=240,
                             stdin=subprocess.DEVNULL)
    except subprocess.TimeoutExpired:
        print("  ✗ gave up waiting (4 min). Flip the toggle, then re-run this verb.")
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
    # NB: the disabled-toggle path exits 0 with prose (measured) — parse text BEFORE rc.
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
            print(f"      {url.split('//', 1)[1].split('/')[0]}   → POST /pair/claim")
        return
    print(f"  ✗ funnel refused: {out_text[:300]}")


# ── tools ─────────────────────────────────────────────────────────────────────
def _tool_watch_status(args: dict, **kw) -> str:
    devices = _CTX.state.get(STATE_DEVICES) or {}
    return json.dumps({
        "checks": tailnet_state(),
        "devices": devices,
        # Additive (pane install-checklist): `profiles` drives the per-profile enable
        # checkboxes — the inventory rides THIS call (the pane already makes it) rather
        # than a second round-trip; `apk_release_url` mirrors the plugin config because
        # the SDK gives panes no config door (see desktop/plugin.js).
        "profiles": _profile_inventory(),
        "apk_release_url": str(_cfg("apk_release_url", RELEASES_LATEST) or RELEASES_LATEST),
    }, indent=2)


def _tool_watch_enable(args: dict, **kw) -> str:
    enable = args.get("enable", True)
    enable = True if enable is None else bool(enable)
    results = apply_enablement(_parse_agents(args.get("agents")), enable)
    return json.dumps({"enable": enable, "results": results}, indent=2)


def _tool_watch_pair(args: dict, **kw) -> str:
    if args.get("code"):
        try:
            device = claim_pin(str(args["code"]))
        except ValueError as exc:
            return json.dumps({"error": str(exc)})
        return json.dumps({"paired": device,
                           "claim_door": _door_listening(),
                           "note": "the watch collects the packet on its next poll (≤3 s)"
                                   if _door_listening()
                                   else "claim door not answering — the packet waits in the "
                                        "ledger; run `hermes watch pair` once the gateway is up"},
                          indent=2)
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
    ("watch_enable", {
        "type": "object",
        "properties": {
            "agents": {
                "type": ["array", "string"],
                "items": {"type": "string"},
                "description": 'Profiles to toggle: ["wear","wear-demo"], a comma list '
                               '"wear,wear-demo", or "all" — every profile on this machine '
                               "(named profiles + the default root config).",
            },
            "enable": {
                "type": "boolean",
                "description": "true (default) lists wearos in the profile's plugins.enabled; "
                               "false removes it (the settings block is left standing).",
            },
        },
    }, _tool_watch_enable, "🔌"),
)


# ── in-session /pair command (demo hero: "pair 429183" in any chat surface) ───
def _cmd_pair(raw_args: str) -> str:
    pin = (raw_args or "").strip()
    if not pin:
        return f"usage: /pair <6-digit PIN from the watch>  (or /pair show to mint one)"
    if pin.lower() == "show":
        ensure_door()
        return f"pair with this PIN (valid {PIN_TTL_SECONDS // 60} min): {mint_pin()}"
    try:
        device = claim_pin(pin)
    except ValueError as exc:
        return f"pairing failed: {exc}"
    tail = ("the watch collects it on its next poll (≤3 s)"
            if _door_listening() else
            "packet queued — start the door with `hermes watch pair` if the watch stalls")
    return f"watch enrolled on the tailnet → {device['hostname']} ({device['gateway_fqdn']}) — {tail}"


# ── hermes watch CLI ──────────────────────────────────────────────────────────
def _watch_cli_setup(sub) -> None:
    watch_sub = sub.add_subparsers(dest="watch_command")
    p = watch_sub.add_parser("pair", help="claim the watch's PIN (or mint one with --show)")
    p.add_argument("code", nargs="?", help="6-digit PIN from the watch")
    p.add_argument("--show", action="store_true", help="mint a plugin-side PIN instead of claiming")
    e = watch_sub.add_parser("enable",
                             help="turn the wearos plugin on/off per profile "
                                  "(idempotent, comment-preserving config.yaml edits)")
    e.add_argument("--agents", default="all", metavar="NAMES",
                   help='profiles to toggle: "wear,wear-demo", or "all" (default — '
                        "every profile on this machine)")
    e.add_argument("--disable", action="store_true",
                   help="remove wearos from plugins.enabled instead of adding it")
    watch_sub.add_parser("status", help="tailnet readiness checklist + devices")
    s = watch_sub.add_parser("setup", help="wizard: tailscaled → credential → MagicDNS → funnel → serve")
    s.add_argument("--fix", choices=["credential", "funnel", "serve"], default=None)
    s.add_argument("--yes", action="store_true",
                   help="approve the printed preview (currently: --fix funnel PATCHes the ACL)")
    watch_sub.add_parser("devices", help="paired watch records")


def _cli_enable(args) -> None:
    names = _parse_agents(getattr(args, "agents", "all"))
    enable = not bool(getattr(args, "disable", False))
    results = apply_enablement(names, enable)
    for r in results:
        if r.get("error"):
            print(f"  ✗ {r['name']} — {r['error']}")
        elif r.get("changed"):
            print(f"  {'✓ enabled' if enable else '− disabled'} · {r['name']}")
        else:
            print(f"  · {r['name']} — already {'enabled' if r['after'] else 'off'}")
    touched = sum(1 for r in results if r.get("changed"))
    print(f"{len(results)} profile(s) inspected, {touched} changed — each profile picks "
          "the plugin up when its agent (re)loads plugins. The Wear Kit pane drives the "
          "same verb (watch_enable).")


def _cli_pair(args) -> None:
    code = str(getattr(args, "code", "") or "")
    if code and not getattr(args, "show", False):
        ensure_door()
        try:
            device = claim_pin(code)
        except ValueError as exc:
            print(f"pairing failed: {exc}")
            raise SystemExit(1)
        print(json.dumps({"paired": device}, indent=2))
        if not _door_listening():
            print("⚠ claim door not answering — the gateway must serve it (hermes watch setup --fix serve).")
            return
        status = _await_pickup(code, CLAIM_WAIT_SECONDS)
        if status == "delivered":
            print("✓ the watch collected its enrollment packet")
        elif status == "pruned":
            print("⚠ the packet aged out before the watch polled it — re-pair")
        else:
            print(f"packet queued — the watch polls every ~3 s, still in the ledger after {CLAIM_WAIT_SECONDS}s")
        return
    ensure_door()
    print(json.dumps({"pin": mint_pin(),
                      "ttl_seconds": PIN_TTL_SECONDS,
                      "claim_door_listening": _door_listening(),
                      "say": "type or say this PIN to Hermes on any surface: /pair <PIN>"}, indent=2))


def _watch_cli_run(args) -> None:
    cmd = getattr(args, "watch_command", None) or "status"
    if cmd == "pair":
        _cli_pair(args)
        return
    if cmd == "enable":
        _cli_enable(args)
        return
    if cmd == "devices":
        print(json.dumps(_CTX.state.get(STATE_DEVICES) or {}, indent=2))
        return
    if cmd == "setup" and getattr(args, "fix", None):
        _wizard_fix(args.fix, yes=bool(getattr(args, "yes", False)))
        return
    print(json.dumps({"checks": tailnet_state()}, indent=2))
    if cmd == "setup":
        print("red light? each check carries its own `fix` — `hermes watch setup --fix"
              " credential|funnel|serve [--yes]`.")


def _startup_readiness_pass() -> None:
    """Open the claim door (the funnel's only tenant) and take a readiness first pass so
    `watch_status` and the Desktop page have fresh lights. Runs on its own daemon thread
    at LOAD (see register): hermes-agent 0.23 removed the `gateway:startup` plugin hook,
    and a hook that never fires means the door's silent-failure guard never runs."""
    try:
        ensure_door()
        logger.info("wearos: load-time readiness: %s", tailnet_state())
    except Exception:
        logger.debug("wearos: readiness probe failed at load", exc_info=True)


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
        name="watch", help="Hermes Wear Kit (pair, enable, status, setup, devices)",
        setup_fn=_watch_cli_setup, handler_fn=_watch_cli_run,
        description="One-command Wear OS watch pairing over your own tailnet.",
    )
    ctx.on_unload(claim_server.stop_server)
    # Door + readiness first pass come up at LOAD, not on a lifecycle hook: 0.23's
    # VALID_HOOKS has no gateway-scoped startup event (registering one logs
    # "registered unknown hook" on every load and never fires), and a down door is a
    # SILENT pairing failure. Idempotent (defers to any door already answering); one
    # daemon thread off the load path; harmless in one-shot CLI processes and
    # validation probes (they exit; on_unload stops the door where loaded).
    threading.Thread(target=_startup_readiness_pass, daemon=True,
                     name="wearos-readiness").start()
