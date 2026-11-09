"""Pairing v2: gateway-issued codes, fcntl-transactional state, device registry.

Protocol doctrine (review 2026-11-09 + owner decisions D2/D3/D6/D12/D13):
  * The GATEWAY mints the 6-digit code (RFC 8628 shape). The watch never chooses it, so
    no public request can squat a code the user is about to type: unknown-PIN adoption
    is gone — a code this gateway did not issue can never be approved.
  * The PIN never passes through a model: tools mint nothing (``watch_pair`` takes no
    arguments); claims ride only the typed ``/pair``, ``hermes watch pair`` and the
    pane's claim button.
  * Pickup speaks the 256-bit device secret the watch generated at ``begin`` — the code
    never appears in any public URL, and the secret never appears in an approval prompt.
  * Cross-process transactionality: the plugin host (CLI/TUI), the gateway process
    hosting the claim door, and the private-channel server are DIFFERENT processes.
    Every read-modify-write here takes an exclusive ``fcntl`` lock on the state file
    for the whole transaction (``ctx.state`` locks per-call only — not enough).

State file: ``<gateway root>/plugin-data/wearos/pairing.json`` (0600) — the GATEWAY's
root home, so a named profile (``<root>/profiles/<p>``) and the door's launch process
share one registry. The device secret
and the PIN are stored ONLY as SHA-256 hashes; the Tailscale authkey lives here
short-term, zeroed the moment the watch acknowledges pickup.
"""

from __future__ import annotations

import errno
import fcntl
import hashlib
import hmac
import json
import os
import re
import secrets
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Callable, Iterator, Optional

# Windows
CODE_TTL = 300            # pairing code lives 5 min from begin
PICKUP_WINDOW = 120       # seconds an approved code waits for poll/ack
BEGIN_PER_MINUTE = 3      # new begins accepted per rolling minute (public flood cap)
PENDING_CAP = 8           # ledger cap: at most this many unclaimed records
WRONG_LOCK_TRIES = 3      # typed wrong codes before the approval lockout
WRONG_LOCK_WINDOW = 900   # misses older than this don't count
LOCK_SECONDS = 300        # first lockout length
LOCK_SECONDS_REPEAT = 900 # second and later (misses also age out after this)
MAX_QUEUE = 50            # outbox depth per device (matches the APK)
ALERT_FOLD_AFTER = 5      # per-job-id alerts kept before folding into "+N more"
CODE_RE = re.compile(r"[1-9][0-9]{5}")
SECRET_RE = re.compile(r"[0-9a-f]{64}")
_HOSTNAME_ALLOWED = re.compile(r"[^a-z0-9._-]+")
_LABEL_ALLOWED = re.compile(r"[^A-Za-z0-9 .(_-]")


class PairingError(ValueError):
    """User-presentable refusal. ``outcome`` maps to the door's HTTP status."""

    def __init__(self, msg: str, outcome: str = "malformed") -> None:
        super().__init__(msg)
        self.outcome = outcome


# ── hashing helpers (single place so nothing ever stores the raw code/secret) ──
def hash_secret(secret_hex: str) -> str:
    return hashlib.sha256(bytes.fromhex(secret_hex)).hexdigest()


def hash_code(code: str) -> str:
    return hashlib.sha256(code.encode()).hexdigest()[:16]


def new_device_token() -> str:
    return "wtk-" + secrets.token_hex(32)


def sanitize_label(name: str) -> str:
    clean = _LABEL_ALLOWED.sub("-", str(name or "").strip())
    return (clean or "Watch")[:64]


def sanitize_hostname(name: str) -> str:
    clean = _HOSTNAME_ALLOWED.sub("-", str(name or "").strip().lower()).strip("-.")
    return clean[:64]


# ── storage ────────────────────────────────────────────────────────────────────
def _default_home() -> Path:
    try:
        from hermes_constants import get_hermes_home
        home = Path(get_hermes_home())
    except Exception:
        home = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes")))
    # ONE registry per GATEWAY, not per profile: the claim door is a single shared port
    # hosted by the launch profile's process, while `hermes -p <profile> watch pair`
    # runs under `<root>/profiles/<profile>`. Collapsing that layout to the root keeps
    # door, CLI and lane reading the SAME fcntl-locked file — per-profile copies gave
    # the door a store whose window it never saw (caught live 2026-11-09: begin 403
    # "pairing closed" right after the window opened).
    if home.parent.name == "profiles":
        return home.parent.parent
    return home


class Store:
    """One JSON document under an exclusive fcntl lock for the whole transaction.

    ``mutate(fn)`` calls ``fn(doc)`` with the parsed document; whatever it returns is
    written back atomically (0600, temp+replace). Readers take a shared lock."""

    def __init__(self, home: Optional[Path] = None) -> None:
        self.dir = (home or _default_home()) / "plugin-data" / "wearos"
        self.path = self.dir / "pairing.json"

    @staticmethod
    def _empty() -> dict:
        return {"pairs": {}, "devices": {}, "grants": {}, "window": None,
                "claim": {"miss_window": [], "lock_until": 0, "locks_seen": 0},
                "begins": [], "alert_pref": {}}

    @contextmanager
    def _locked(self, exclusive: bool) -> Iterator[None]:
        self.dir.mkdir(parents=True, exist_ok=True)
        try:
            os.chmod(self.dir, 0o700)
        except OSError:
            pass
        fd = os.open(self.path, os.O_RDWR | os.O_CREAT, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH)
            yield
        finally:
            try:
                fcntl.flock(fd, fcntl.LOCK_UN)
            finally:
                os.close(fd)

    def read(self) -> dict:
        with self._locked(exclusive=False):
            return self._load()

    def _load(self) -> dict:
        try:
            with open(self.path, encoding="utf-8") as fh:
                doc = json.load(fh)
        except (OSError, ValueError):
            return self._empty()
        if not isinstance(doc, dict):
            return self._empty()
        base = self._empty()
        for k, v in base.items():
            doc.setdefault(k, v)
        return doc

    def mutate(self, fn: Callable[[dict], Any], keep_on_error: bool = False) -> Any:
        """Run a transaction. ``keep_on_error`` still PERSISTS the document when fn
        raises — the miss counter of a refused claim MUST survive the raise (a lost
        count is a lost lockout)."""
        with self._locked(exclusive=True):
            doc = self._load()
            try:
                result = fn(doc)
            except BaseException:
                if keep_on_error:
                    self._store(doc)
                raise
            self._store(doc)
            return result

    def _store(self, doc: dict) -> None:
        tmp = self.path.with_name(self.path.name + ".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump(doc, fh, separators=(",", ":"))
                fh.flush()
                os.fsync(fh.fileno())
            os.replace(tmp, self.path)
            os.chmod(self.path, 0o600)
        except OSError as exc:
            try:
                os.unlink(tmp)
            except OSError:
                pass
            if exc.errno == errno.EROFS:
                raise PairingError("pairing store is read-only on this machine", "hot")
            raise


# ── the state machine ─────────────────────────────────────────────────────────
class PairingEngine:
    """Every method takes the store lock for its whole transaction. ``mint_key`` and
    ``now`` are injected so tests (and the claim door) never need the Tailscale API."""

    def __init__(self, store: Store, *, mint_key: Callable[[str], tuple[str, str]],
                 now: Callable[[], float] = time.time) -> None:
        self.store = store
        self.mint_key = mint_key  # profile -> (authkey, key_id); raises PairingError
        self.now = now

    # -- housekeeping (always called inside a transaction) ----------------------
    @staticmethod
    def _live_record(doc: dict, secret_hash: str,
                     states: tuple = ("pending", "approved")) -> Optional[dict]:
        """The FRESHEST pairing record for a secret in one of ``states`` — re-pairing
        after an expired attempt leaves older tombstones that must never shadow the
        new record (every transaction runs _prune first, so expiry is already a
        terminal state here)."""
        cands = [r for r in doc["pairs"].values()
                 if r.get("secret_hash") == secret_hash and r.get("state") in states]
        return max(cands, key=lambda r: r.get("created", 0), default=None)

    def _prune(self, doc: dict, now: float) -> None:
        pairs = doc["pairs"]
        for pid, rec in list(pairs.items()):
            if rec.get("state") in ("delivered", "gone", "expired"):
                if rec.get("expires", 0) + CODE_TTL < now:
                    del pairs[pid]
                continue
            if rec.get("expires", 0) <= now:
                # An approved-but-never-collected code must not leave its minted key
                # behind: expire it before flipping the tombstone.
                kid = rec.pop("authkey_id", None)
                rec.pop("authkey", None)
                if kid:
                    try:
                        self._expire_key(kid)
                    except Exception:
                        pass
                rec["state"] = "expired"
                rec["expires"] = now
        doc["begins"] = [t for t in doc.get("begins", []) if now - t < 60]
        claim = doc["claim"]
        claim["miss_window"] = [t for t in claim.get("miss_window", [])
                                if now - t < WRONG_LOCK_WINDOW]

    def _expire_key(self, key_id: str) -> None:
        expire = getattr(self.mint_key, "expire", None)
        if callable(expire):
            expire(key_id)

    def _device_name(self, doc: dict, rec: dict) -> str:
        base = sanitize_label(rec["label"]).lower().replace(" ", "-")
        base = sanitize_hostname(base) or "watch"
        name = base
        i = 2
        while True:
            existing = doc["devices"].get(name)
            if existing is None or existing.get("pair_id") == rec["id"]:
                return name
            name = f"{base}-{i}"
            i += 1

    # -- pairing window (D5: closed by default; opened at the gateway) --------------
    def open_window(self, profile: str, seconds: int = CODE_TTL) -> dict:
        def tx(doc: dict) -> dict:
            now = self.now()
            until = max(float((doc.get("window") or {}).get("until", 0)), now) + seconds
            doc["window"] = {"profile": profile, "until": until, "opened_at": now}
            return dict(doc["window"])
        return self.store.mutate(tx)

    def close_window(self) -> None:
        self.store.mutate(lambda doc: doc.__setitem__("window", None))

    def window(self) -> Optional[dict]:
        now = self.now()
        w = self.store.read().get("window")
        if not w or float(w.get("until", 0)) <= now:
            return None
        return {"profile": w["profile"], "seconds_left": int(w["until"] - now)}

    # -- public door ------------------------------------------------------------
    def begin(self, secret_hex: str, label: str, profile: str,
              tailnet_hint: str = "") -> dict:
        """Watch knocks BEFORE any code exists: proves it generated a 256-bit secret,
        gets a gateway-issued code back. Public flood caps only — anonymous traffic
        can never lock a legitimate pairing out (D6). Requires the pairing window
        open at the gateway (D5); the window's profile wins over any hint."""
        if not isinstance(secret_hex, str) or not SECRET_RE.fullmatch(secret_hex):
            raise PairingError("secret must be 64 hex chars", "malformed")
        label = sanitize_label(label)
        now = self.now()

        def tx(doc: dict) -> dict:
            self._prune(doc, now)
            sh = hash_secret(secret_hex)
            pending = [r for r in doc["pairs"].values() if r.get("state") == "pending"]
            if len(doc["begins"]) >= BEGIN_PER_MINUTE or len(pending) >= PENDING_CAP:
                raise PairingError("pairing requests are rate limited — try again in "
                                   "a minute", "hot")
            for rec in doc["pairs"].values():  # re-begin: same watch, same open flow
                if rec.get("secret_hash") == sh and rec.get("state") == "pending":
                    rec["label"] = label
                    rec["expires"] = now + CODE_TTL
                    doc["begins"].append(now)
                    return {"code": rec["code"], "expires_in": CODE_TTL}
            win = doc.get("window")
            if not win or float(win.get("until", 0)) <= now:
                raise PairingError("pairing is closed — open it at the gateway first "
                                   "(hermes watch pair)", "closed")
            profile = str(win.get("profile") or profile)
            doc["window"] = None  # one pairing per open — the door closes when it's used
            code = ""
            for _ in range(8):
                code = str(secrets.randbelow(900_000) + 100_000)
                if hash_code(code) not in {r.get("code_hash") for r in doc["pairs"].values()}:
                    break
            pid = secrets.token_hex(4)
            doc["begins"].append(now)
            doc["pairs"][pid] = {
                "id": pid, "secret_hash": sh, "code": code, "code_hash": hash_code(code),
                "label": label, "profile": profile, "tailnet_hint": tailnet_hint[:128],
                "state": "pending", "created": now, "expires": now + CODE_TTL,
                "polls": 0,
            }
            return {"code": code, "expires_in": CODE_TTL}

        return self.store.mutate(tx, keep_on_error=True)

    def poll(self, secret_hex: str) -> tuple[str, Optional[dict]]:
        """Watch polling for its code's fate. ('pending'|'gone'|('ready', payload)).
        An approved record is matched BEFORE any throttle (v1 bug: a flood of pending
        polls could 429 a watch whose code was already approved)."""
        if not isinstance(secret_hex, str) or not SECRET_RE.fullmatch(secret_hex):
            return "gone", None
        now = self.now()

        def tx(doc: dict) -> tuple[str, Optional[dict]]:
            self._prune(doc, now)
            sh = hash_secret(secret_hex)
            rec = self._live_record(doc, sh)
            if rec is None or rec["state"] in ("gone", "expired"):
                return "gone", None
            if rec["state"] == "pending":
                if rec.get("polls", 0) % 16 == 0 and now - rec["created"] > CODE_TTL * 2:
                    return "gone", None  # abandoned flow pruned early
                rec["polls"] = rec.get("polls", 0) + 1
                return "pending", None
            if rec["state"] == "approved":
                payload = {
                    "authkey": rec["authkey"], "key_id": rec["authkey_id"],
                    "profile": rec["profile"],
                }
                return "ready", payload
            return "gone", None

        return self.store.mutate(tx)

    def ack(self, secret_hex: str) -> dict:
        """Watch collected the key: hand out its device token, register the device.
        Idempotent: an ack for a record already delivered returns the same token only
        when the record is still live (crash between poll and ack re-acks safely)."""
        if not isinstance(secret_hex, str) or not SECRET_RE.fullmatch(secret_hex):
            raise PairingError("bad secret", "malformed")
        now = self.now()

        def tx(doc: dict) -> dict:
            self._prune(doc, now)
            sh = hash_secret(secret_hex)
            rec = self._live_record(doc, sh, ("pending", "approved", "delivered"))
            if rec is None:
                raise PairingError("pairing record is gone — start again on the watch",
                                   "gone")
            if rec["state"] == "delivered":
                dev = doc["devices"].get(rec["device_name"]) or {}
                return {"token_hint": rec.get("token_hash", ""), "device": dev}
            if rec["state"] != "approved":
                raise PairingError("pairing not approved (or the window closed)", "gone")
            return self._finish(doc, rec, now)

        return self.store.mutate(tx)

    def _finish(self, doc: dict, rec: dict, now: float) -> dict:
        name = self._device_name(doc, rec)
        token = new_device_token()
        rec["state"] = "delivered"
        rec["expires"] = now + CODE_TTL  # tombstone life for late polls
        rec.pop("authkey", None)         # zero the key from the ledger on pickup
        rec["token_hash"] = hashlib.sha256(token.encode()).hexdigest()
        rec["device_name"] = name
        prev = doc["devices"].get(name) or {}
        doc["devices"][name] = {
            "hostname": name,
            "label": rec["label"],
            "profile": rec["profile"],
            "tag": rec.get("tag", ""),
            "tailnet": rec.get("tailnet_hint", ""),
            "token_hash": rec["token_hash"],
            "pair_id": rec["id"],
            "first_seen": prev.get("first_seen") or now,
            "enrolled_at": now,
            "node_id": prev.get("node_id", "") if prev.get("token_hash") == rec["token_hash"] else "",
        }
        return {"token": token, "device": doc["devices"][name]}

    # -- approval side (NEVER the public door) -----------------------------------
    def approve(self, code: str, *, tag: str = "", allow_key: bool = True) -> dict:
        """Claim a typed 6-digit code. Only codes THIS engine issued are adoptable —
        an unknown code counts as a miss (the review's option (a) + (b) combined).
        Three misses in WRONG_LOCK_WINDOW lock claiming for LOCK_SECONDS (15 min on
        repeat). Success clears the counter."""
        if not isinstance(code, str) or not CODE_RE.fullmatch(code):
            raise PairingError("pairing code must be 6 digits", "malformed")
        now = self.now()

        def tx(doc: dict) -> dict:
            self._prune(doc, now)
            claim = doc["claim"]
            if claim.get("lock_until", 0) > now:
                left = int(claim["lock_until"] - now) + 1
                raise PairingError(f"too many wrong codes — pairing approval is locked "
                                   f"for {left // 60 + 1} more minute(s)", "locked")
            rec = next((r for r in doc["pairs"].values()
                        if r.get("code_hash") == hash_code(code)), None)
            if (rec is None or rec["state"] != "pending"
                    or rec.get("expires", 0) <= now):
                self._count_miss(doc, now)
                raise PairingError("that code is unknown, expired, or already used — "
                                   "open pairing again on the watch", "badcode")
            if allow_key:
                key_id = ""
                authkey = ""
                authkey, key_id = self.mint_key(rec["profile"])
                rec["authkey"] = authkey
                rec["authkey_id"] = key_id
                rec["tag"] = tag
            rec["state"] = "approved"
            rec["expires"] = now + PICKUP_WINDOW
            rec["approved_at"] = now
            claim["miss_window"] = []
            claim["lock_until"] = 0
            return {"label": rec["label"], "profile": rec["profile"], "pair_id": rec["id"]}

        return self.store.mutate(tx, keep_on_error=True)

    def _count_miss(self, doc: dict, now: float) -> None:
        claim = doc["claim"]
        misses = claim.setdefault("miss_window", [])
        misses.append(now)
        if len(misses) >= WRONG_LOCK_TRIES:
            claim["locks_seen"] = int(claim.get("locks_seen", 0)) + 1
            claim["lock_until"] = now + (LOCK_SECONDS if claim["locks_seen"] <= 1
                                         else LOCK_SECONDS_REPEAT)
            claim["miss_window"] = []

    def note_tailscale_failure(self, doc_mutator: Callable[[dict], None]) -> None:
        """approve() minted a key but the tailscale API failed AFTER the record was
        marked: callers keep the record pending (mutate doc here) — small hook so the
        transaction stays in one place."""
        self.store.mutate(doc_mutator)

    def pending_list(self) -> list:
        now = self.now()

        def tx(doc: dict) -> list:
            self._prune(doc, now)
            return [{"code": r["code"], "label": r["label"], "profile": r["profile"],
                     "state": r["state"], "seconds_left": max(0, int(r["expires"] - now))}
                    for r in doc["pairs"].values() if r["state"] in ("pending", "approved")]

        return self.store.mutate(tx)

    def claim_locked(self) -> dict:
        doc = self.store.read()
        until = doc["claim"].get("lock_until", 0)
        return {"locked": until > self.now(), "until": until}

    # -- registry / devices -------------------------------------------------------
    def devices(self) -> dict:
        return dict(self.store.read()["devices"])

    def device(self, name: str) -> Optional[dict]:
        return self.store.read()["devices"].get(name)

    def check_token(self, name: str, token: str) -> bool:
        """Constant-time token check straight off the registry (private-channel auth)."""
        dev = self.device(name)
        if not dev:
            return False
        return hmac.compare_digest(hashlib.sha256(token.encode()).hexdigest(),
                                   dev.get("token_hash", "x"))

    def device_lookup(self, token: str) -> Optional[str]:
        """Registry name behind a bearer token (constant-time over candidates)."""
        if not isinstance(token, str) or not token:
            return None
        want = hashlib.sha256(token.encode()).hexdigest()
        hit = None
        for name, dev in self.store.read()["devices"].items():
            if hmac.compare_digest(dev.get("token_hash", "x"), want):
                hit = name  # keep scanning: no early-exit timing map
        return hit

    def first_session(self, device_name: str, secret_hex: str, node: dict) -> dict:
        """Bind (or verify) the tailnet node behind a session call. The FIRST session
        for a device must carry the pairing secret and must be a fresh, matching
        pairing record — after that the captured node id is law: a different node
        dialing with the same token is refused (stolen-token-on-new-node needs the
        old node offline; note it on revoke)."""
        def tx(doc: dict) -> dict:
            dev = doc["devices"].get(device_name)
            if dev is None:
                raise KeyError(device_name)
            have = dev.get("node_id") or ""
            if have:
                if node.get("node_id") != have:
                    raise PermissionError("this device is bound to a different tailnet node")
                dev["node_name"] = node.get("node_name", dev.get("node_name", ""))
                dev["node_tags"] = node.get("tags", [])[:8]
                dev["last_session_at"] = self.now()
                return dict(dev)
            if not isinstance(secret_hex, str) or not SECRET_RE.fullmatch(secret_hex):
                raise PermissionError("first session must carry the pairing secret")
            now = self.now()
            sh = hash_secret(secret_hex)
            cands = [r for r in doc["pairs"].values()
                     if r.get("secret_hash") == sh and r.get("device_name") == device_name
                     and r.get("state") == "delivered" and r.get("expires", 0) > now]
            rec = max(cands, key=lambda r: r.get("created", 0), default=None)
            if rec is None:
                raise PermissionError("first session needs a fresh pairing secret — "
                                      "re-pair this watch")
            dev["node_id"] = node.get("node_id", "")
            dev["node_name"] = node.get("node_name", "")
            dev["node_tags"] = node.get("tags", [])[:8]
            dev["bootstrapped_at"] = now
            dev["last_session_at"] = now
            return dict(dev)
        return self.store.mutate(tx, keep_on_error=True)

    def note_rotation(self, device_name: str) -> None:
        def tx(doc: dict) -> None:
            dev = doc["devices"].get(device_name)
            if dev is not None:
                dev["key_rotated_at"] = self.now()
        self.store.mutate(tx)

    def record_node(self, name: str, node_id: str, node_name: str, tags: list) -> dict:
        """Bootstrap identity: the tailscale node behind the connection, captured from
        ``tailscale whois`` — the truth about the source, not the watch's self-report."""
        def tx(doc: dict) -> dict:
            dev = doc["devices"].get(name)
            if dev is None:
                raise PairingError("unknown device", "gone")
            dev["node_id"] = node_id
            dev["node_name"] = node_name
            dev["node_tags"] = [str(t) for t in tags][:8]
            dev["bootstrapped_at"] = self.now()
            return dict(dev)
        return self.store.mutate(tx)

    def set_tag(self, name: str, tag: str) -> None:
        def tx(doc: dict) -> None:
            dev = doc["devices"].get(name)
            if dev is not None:
                dev["tag"] = tag
                for rec in doc["pairs"].values():
                    if rec.get("device_name") == name:
                        rec["tag"] = tag
        self.store.mutate(tx)

    def revoke(self, name: str) -> dict:
        """Drop the device record + its queue + any pending credential for it. The
        CALLER performs the outside-world half (tailscale DELETE, key rotation) using
        the returned record, then confirms. Idempotent."""
        def tx(doc: dict) -> dict:
            dev = doc["devices"].pop(name, None) or {"hostname": name, "absent": True}
            for rec in doc["pairs"].values():
                if rec.get("device_name") == name:
                    rec.pop("authkey", None)
                    rec["state"] = "gone"
            queues = doc.setdefault("queues", {})
            queues.pop(name, None)
            return dev
        return self.store.mutate(tx)

    # -- per-agent grants (D17) ----------------------------------------------------
    def grants(self) -> dict:
        return dict(self.store.read()["grants"])

    def set_grant(self, profile: str, allowed: bool) -> dict:
        def tx(doc: dict) -> dict:
            if allowed:
                doc["grants"][profile] = {"granted_at": self.now()}
            else:
                doc["grants"].pop(profile, None)
            return dict(doc["grants"])
        return self.store.mutate(tx)

    # -- outbox (per device) — lives in the same document for one atomic lock ------
    def queue_message(self, device_name: str, text: str, *, kind: str = "msg",
                      agent: str = "", title: str = "") -> dict:
        text = str(text or "")[:4096]
        if not text:
            raise PairingError("message text is required", "malformed")

        def tx(doc: dict) -> dict:
            if device_name not in doc["devices"]:
                raise PairingError(f"no paired watch named {device_name!r}", "gone")
            queues = doc.setdefault("queues", {})
            q = queues.setdefault(device_name, {"next_id": 1, "items": []})
            now = self.now()
            q["items"] = [i for i in q["items"] if now - i["ts"] < 86400]
            folded_key = (kind, agent, title)
            same = [i for i in q["items"]
                    if (i.get("kind"), i.get("agent"), i.get("title")) == folded_key]
            if kind == "cron" and (len(same) >= ALERT_FOLD_AFTER
                                   or any(int(i.get("fold", 0)) for i in same)):
                # Collapse the WHOLE burst into the oldest alert: one line, "+N more".
                keep, rest = same[0], same[1:]
                keep["fold"] = int(keep.get("fold", 0)) + sum(int(i.get("fold", 0))
                                                              for i in rest) + len(same)
                q["items"] = [i for i in q["items"] if i is keep or i not in rest]
                keep["ts"] = now
                if len(q["items"]) >= MAX_QUEUE:
                    q["items"].pop(0)
                return {"queued": False, "folded": True, "id": keep["id"]}
            mid = q["next_id"]
            q["next_id"] = mid + 1
            q["items"].append({"id": mid, "kind": kind, "agent": agent, "title": title,
                               "text": text, "ts": now, "fold": 0})
            while len(q["items"]) > MAX_QUEUE:
                q["items"].pop(0)
            return {"queued": True, "id": mid, "depth": len(q["items"])}

        return self.store.mutate(tx)

    def events(self, device_name: str, since_id: int = 0) -> dict:
        def tx(doc: dict) -> dict:
            if device_name not in doc["devices"]:
                raise KeyError(device_name)
            q = doc.get("queues", {}).get(device_name)
            if q is None:
                return {"since": int(since_id or 0), "events": []}
            now = self.now()
            q["items"] = [i for i in q["items"] if now - i["ts"] < 86400]
            out = []
            for i in q["items"]:
                if i["id"] <= int(since_id or 0):
                    continue
                row = {"id": i["id"], "kind": i["kind"], "text": i["text"],
                       "agent": i.get("agent", ""), "title": i.get("title", "")}
                if i.get("fold"):
                    row["fold"] = i["fold"]
                out.append(row)
            return {"since": max([i["id"] for i in q["items"]] + [int(since_id or 0)]),
                    "events": out}
        return self.store.mutate(tx)

    def ack_events(self, device_name: str, up_to_id: int) -> dict:
        def tx(doc: dict) -> dict:
            if device_name not in doc["devices"]:
                raise KeyError(device_name)
            q = doc.get("queues", {}).get(device_name)
            if q is None:
                return {"remaining": 0}
            q["items"] = [i for i in q["items"] if i["id"] > int(up_to_id or 0)]
            return {"remaining": len(q["items"])}
        return self.store.mutate(tx)

    def alert_pref(self, profile: str) -> str:
        return str(self.store.read()["alert_pref"].get(profile, "all"))

    def set_alert_pref(self, profile: str, pref: str) -> str:
        pref = pref if pref in ("all", "fail", "off") else "all"

        def tx(doc: dict) -> None:
            doc["alert_pref"][profile] = pref
        self.store.mutate(tx)
        return pref

    # -- bootstrap credential delivery (D13) ----------------------------------------
    def resolve_key(self, profile: str, device_name: str, key_reader: Callable[[str], str],
                    grants: Optional[dict] = None) -> str:
        """Return the API key for the ROUTE's agent. The law (D17/D13, live-fixed
        2026-11-09): a device ALWAYS gets its own paired agent's key — no grant —
        over its private lane; reaching ANOTHER agent's key requires that other
        agent to be granted on the Wear Kit pane. The lane process's own profile is
        irrelevant (one shared door/lane can host every agent). ``key_reader(agent)``
        reads that agent's API_SERVER_KEY through Hermes's secret route.
        """
        dev = self.device(device_name)
        if dev is None:
            raise PairingError("unknown device", "gone")
        own = str(dev.get("profile") or "")
        want = str(profile or own)
        if want != own:
            g = grants if grants is not None else self.grants()
            if want not in g:
                raise PairingError(
                    f"agent {want!r} has no key grant yet — approve it under "
                    "Gateways ▸ Agents in the Wear Kit pane", "locked")
        key = key_reader(want)
        if not key:
            raise PairingError(f"agent {want!r} has no API_SERVER_KEY set", "locked")
        return key
