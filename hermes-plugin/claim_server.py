"""The funnel claim door (protocol v2) — the ONLY pre-enrollment network surface.

A stdlib ``ThreadingHTTPServer`` bound to ``127.0.0.1:<claim_port>`` (default 42653).
``tailscale funnel`` on the gateway node fronts it, so the public internet sees exactly
these routes and nothing else:

  POST /pair/begin  {"v":2,"secret":"<64 hex>","label":"<watch name>","tailnet":"…"}
                    → 201 {"code":"429183","expires_in":300}
                    The GATEWAY issues the code (review fix: watch-minted codes are
                    gone — unknown-PIN adoption can no longer be raced or flooded).
  GET  /pair/poll?secret=<hex>
                    → 204 still pending · 200 {"state":"ready","authkey":…,…} ONCE
                    → 410 unknown/expired/consumed · 429 poll too fast
  POST /pair/ack    {"v":2,"secret":"<hex>"} → 200 {"token":"wtk-…"} (device token,
                    issued exactly once; the pairing record is retired behind it)
  *  /pair/claim    → 426 (v1 protocol; the v1 PIN rode these URLs)

No bearer, no cookie. Authorization is: possession of the 256-bit begin secret +
the user typing the code at the gateway. The code NEVER appears in any URL the public
door answers (review fix), the secret never appears in an approval prompt, and the
pickup payload carries no gateway API key — that rides the private channel after the
watch has joined the tailnet (pairing.resolve_key / private_api.py).

The door holds NO state itself: pairing.PairingEngine (fcntl-locked JSON) is the
transaction boundary, shared with the CLI and the dashboard. Caps here are the PUBLIC
half of the anti-DoS story (D6): anonymous traffic can be refused, but can never lock
out the approval side (that lockout lives in pairing.approve, user-typed misses only).

Lifecycle: started at load on a daemon thread (the funnel's only tenant); every
process hosting the plugin tries one, ``door_alive`` probe keeps exactly one bound.
"""

from __future__ import annotations

import json
import logging
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Optional
from urllib.parse import parse_qs, urlparse

logger = logging.getLogger("hermes_wearkit.claim")

MAX_BODY_BYTES = 4096       # the begin body is ~120 bytes; more is noise or attack
SOCKET_TIMEOUT = 30         # slow-loris cap: no connection idles a thread for minutes
MAX_INFLIGHT = 64           # hard concurrency ceiling (429/503 beyond)
MAX_KEEPALIVE_REQS = 8      # recycle connections so thread count recycles too
POLL_MIN_INTERVAL = 5.0     # per-secret GET poll floor (pairing counts polls too)


class ClaimBackend:
    """The door's view of the pairing engine (dependency-injected for tests).

    ``begin(secret, label, tailnet)`` / ``ack(secret)`` raise pairing.PairingError with
    an ``outcome`` attribute; ``poll(secret)`` returns (state, payload|None)."""

    def __init__(self, begin: Callable[..., dict], poll: Callable[[str], tuple],
                 ack: Callable[[str], dict]) -> None:
        self.begin = begin
        self.poll = poll
        self.ack = ack

    @classmethod
    def from_module(cls) -> "ClaimBackend":
        from . import door_begin, door_poll, door_ack  # package under BOTH loaders
        return cls(door_begin, door_poll, door_ack)


class ClaimHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "hermes-wearkit-claim/0.2"
    timeout = SOCKET_TIMEOUT

    # -- plumbing ---------------------------------------------------------------
    def log_message(self, fmt: str, *args: Any) -> None:  # quiet: access → debug only
        logger.debug("claim door: %s " + fmt, self.client_address[0], *args)

    def handle(self) -> None:
        server = self.server
        server.inflight += 1
        try:
            if server.inflight > MAX_INFLIGHT:
                try:
                    self._send(503, {"error": "pairing door is busy — try again in a "
                                             "few seconds"})
                except OSError:
                    pass
                return
            super().handle()
        finally:
            server.inflight -= 1  # type: ignore[attr-defined]

    def _send(self, status: int, payload: Optional[dict] = None) -> None:
        body = b"" if payload is None else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _backend(self) -> ClaimBackend:
        return self.server.claim_backend  # type: ignore[attr-defined]

    def _read_body(self) -> Optional[bytes]:
        """Drain the request body BEFORE any early return — OkHttp keep-alives, and an
        unread body desyncs the next request on the connection."""
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = -1
        if not 0 <= length <= MAX_BODY_BYTES:
            self._send(400, {"error": "body must be a small JSON object"})
            return None
        try:
            return self.rfile.read(length)
        except OSError:
            return b""

    def _route(self) -> str:
        return self.path.split("?", 1)[0].rstrip("/") or "/"

    # -- routes -------------------------------------------------------------------
    def do_POST(self) -> None:  # noqa: N802 (BaseHTTPRequestHandler API)
        body = self._read_body()
        if body is None:
            return
        route = self._route()
        if route == "/pair/claim":
            self._send(426, {"error": "pairing protocol v1 retired — update the watch app",
                             "upgrade": "v2"})
            return
        if route == "/pair/ack":
            try:
                payload = json.loads(body or b"")
            except ValueError:
                self._send(400, {"error": "invalid JSON body"})
                return
            if not isinstance(payload, dict) or payload.get("v") != 2:
                self._send(426, {"error": "pairing protocol v1 retired — update the watch app",
                                 "upgrade": "v2"})
                return
            from . import pairing  # late import: module lives beside this file
            try:
                result = self._backend().ack(str(payload.get("secret", "")))
            except pairing.PairingError as exc:
                self._send(_OUTCOMES.get(exc.outcome, 400), {"error": str(exc)})
                return
            self._send(200, result)
            return
        if route != "/pair/begin":
            self._send(404, {"error": "not found"})
            return
        try:
            payload = json.loads(body or b"")
        except ValueError:
            self._send(400, {"error": "invalid JSON body"})
            return
        if not isinstance(payload, dict):
            self._send(400, {"error": "body must be a JSON object"})
            return
        if payload.get("v") != 2:
            self._send(426, {"error": "pairing protocol v1 retired — update the watch app",
                             "upgrade": "v2"})
            return
        secret = str(payload.get("secret", ""))
        label = str(payload.get("label", ""))[:64]
        tailnet = str(payload.get("tailnet", ""))[:128]
        from . import pairing  # late import: module lives beside this file
        try:
            result = self._backend().begin(secret, label, tailnet)
        except pairing.PairingError as exc:
            self._send(_OUTCOMES.get(exc.outcome, 400), {"error": str(exc)})
            return
        self._send(201, result)

    def do_GET(self) -> None:  # noqa: N802
        route = self._route()
        if route == "/pair/claim":
            self._send(426, {"error": "pairing protocol v1 retired — update the watch app",
                             "upgrade": "v2"})
            return
        if route != "/pair/poll":
            self._send(404, {"error": "not found"})
            return
        secret = (parse_qs(urlparse(self.path).query).get("secret") or [""])[0]
        now = time.monotonic()
        bucket = self.server.poll_buckets  # type: ignore[attr-defined]
        with self.server.poll_lock:  # type: ignore[attr-defined]
            last = bucket.get(secret, 0.0)
            throttled = now - last < POLL_MIN_INTERVAL
            if not throttled:
                bucket[secret] = now
                if len(bucket) > 512:
                    cutoff = now - 60
                    for k in [k for k, v in bucket.items() if v < cutoff] or list(bucket)[:256]:
                        bucket.pop(k, None)
        try:
            state, payload = self._backend().poll(str(secret))
        except Exception:
            logger.debug("wearos: claim poll failed", exc_info=True)
            self._send(500, {"error": "pairing backend error"})
            return
        if state == "ready":
            self._send(200, {**payload, "state": "ready"})
        elif state == "pending":
            self._send(429 if throttled else 204,
                       {"error": "poll too fast"} if throttled else None)
        else:
            self._send(410, {"error": "pairing expired, unknown, or already collected"})

    def do_PUT(self) -> None: self._method_guard()      # noqa: N802
    def do_DELETE(self) -> None: self._method_guard()   # noqa: N802
    def do_PATCH(self) -> None: self._method_guard()    # noqa: N802

    def _method_guard(self) -> None:
        self._send(405 if self._route().startswith("/pair/") else 404,
                   {"error": "method not allowed" if self._route().startswith("/pair/")
                    else "not found"})


_OUTCOMES = {"malformed": 400, "hot": 429, "gone": 410, "locked": 423, "badcode": 403,
             "closed": 403}


def start_server(port: int, backend: Optional[ClaimBackend] = None,
                 host: str = "127.0.0.1") -> threading.Thread:
    """Bind and serve on a daemon thread (idempotent; raises OSError on bind failure
    so callers can distinguish 'another door is already up')."""
    global _server, _thread
    if _server is not None:
        return _thread  # type: ignore[return-value]
    httpd = ThreadingHTTPServer((host, int(port)), ClaimHandler)
    httpd.daemon_threads = True
    httpd.claim_backend = backend or ClaimBackend.from_module()  # type: ignore[attr-defined]
    httpd.inflight = 0
    httpd.poll_buckets: dict[str, float] = {}  # type: ignore[no-redef]
    httpd.poll_lock = threading.Lock()
    httpd.requests_since_recycle = 0
    thread = threading.Thread(target=httpd.serve_forever, daemon=True, name="wearos-claim")
    _server, _thread = httpd, thread
    thread.start()
    logger.info("wearos: claim door (v2) listening on %s:%s (funnel-facing)",
                host, httpd.server_address[1])
    return thread


def actual_port() -> int:
    """The bound port (meaningful with port 0 in tests)."""
    return int(_server.server_address[1]) if _server is not None else 0


def stop_server() -> None:
    global _server, _thread
    httpd, thread = _server, _thread
    _server, _thread = None, None
    if httpd is None:
        return
    httpd.shutdown()
    if thread is not None and thread.is_alive():
        thread.join(timeout=2.0)
    httpd.server_close()
    logger.info("wearos: claim door stopped")


def door_alive(port: int, timeout: float = 1.0) -> bool:
    """Is a claim door ALREADY answering on 127.0.0.1:port (possibly the gateway's)?
    POSTs a v1 body — 'claim' answers 426 without touching state (route probe). Any
    HTTP answer at all proves a listener; only 'couldn't connect' is False."""
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}/pair/claim", data=b"{}", method="POST",
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            resp.read()
            return True
    except urllib.error.HTTPError:
        return True
    except OSError:
        return False


_server: Optional[ThreadingHTTPServer] = None
_thread: Optional[threading.Thread] = None
