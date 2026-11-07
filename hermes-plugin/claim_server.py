"""The funnel claim door — the ONLY pre-enrollment network surface (SPEC §4, §11.4).

A stdlib ``ThreadingHTTPServer`` bound to ``127.0.0.1:<claim_port>`` (default 42653). On
the gateway box, ``tailscale serve --https=443 --funnel /pair/*`` fronts it, so the public
internet sees exactly two routes and nothing else:

  POST /pair/claim   body {"pin":"123456","tailnet":"<handle>","hostname":"<watch name>"}
                     → 202 {"queued":true}; wrong/expired/rate-hot → 429/410 {"error":...}
  GET  /pair/claim?pin=123456
                     → 204 while pending; 200 + packet JSON ONCE then 410.
  Packet = {"authkey","tailnet","gateway_fqdn","gateway_port":8642,
            "watchkit_port":42651,"profile","api_key","watchkit_token"(nullable)}

No bearer, no cookie: the 6-digit PIN + 5-min TTL + single-claim + per-PIN knock cap +
global cooldown (all in the pairing module) ARE the auth. Delivery is state-file based —
claim (chat/CLI thread or process) and GET (server thread) meet through the plugin state
JSON, which is fcntl-locked, so the watch's ~3 s polling survives the gateway restarting
mid-pair. The server never runs the pairing logic itself; it maps pairing outcomes to the
HTTP contract above.

Lifecycle: started from the ``gateway:startup`` hook and by ``hermes watch pair`` when no
door is answering yet; daemon thread; stopped via ``ctx.on_unload``.
"""

from __future__ import annotations

import json
import logging
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Optional
from urllib.parse import parse_qs, urlparse

logger = logging.getLogger("hermes_wearkit.claim")

MAX_BODY_BYTES = 4096  # the claim body is ~80 bytes; more is noise or attack


class ClaimBackend:
    """The HTTP door's view of the pairing state machine (dependency-injected so tests
    spin the server against a sandbox; the plugin passes ``from_module()``)."""

    def __init__(self, queue: Callable[..., str], poll: Callable[[str], tuple[str, Optional[dict]]]) -> None:
        self.queue = queue
        self.poll = poll

    @classmethod
    def from_module(cls) -> "ClaimBackend":
        from . import poll_packet, queue_claim  # package under BOTH loaders
        return cls(
            lambda pin, tailnet="", hostname="": queue_claim(pin, tailnet, hostname),
            poll_packet,
        )


class ClaimHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "hermes-wearkit-claim/0.1"

    # -- plumbing ---------------------------------------------------------------
    def log_message(self, fmt: str, *args: Any) -> None:  # quiet: access → debug only
        logger.debug("claim door: %s " + fmt, self.client_address[0], *args)

    def _send(self, status: int, payload: Optional[dict] = None) -> None:
        body = b"" if payload is None else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if body:
            self.wfile.write(body)

    # -- routes (only /pair/claim exists; everything else is a blank 404) --------
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

    def do_POST(self) -> None:  # noqa: N802 (BaseHTTPRequestHandler API)
        body = self._read_body()
        if body is None:
            return
        if self.path.split("?", 1)[0].rstrip("/") != "/pair/claim":
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
        pin = str(payload.get("pin", ""))
        tailnet = str(payload.get("tailnet", ""))[:128]
        hostname = str(payload.get("hostname", ""))[:64]
        status = self._backend().queue(pin, tailnet, hostname)
        if status == "queued":
            self._send(202, {"queued": True})
        elif status == "consumed":
            self._send(410, {"error": "that pairing code was already used"})
        elif status == "expired":
            self._send(410, {"error": "pairing code expired — show it again on the watch"})
        elif status == "hot":
            self._send(429, {"error": "too many pairing attempts — wait a minute and try again"})
        else:  # "malformed" and anything unknown fail closed
            self._send(400, {"error": "pin must be 6 digits, 100000-999999"})

    def do_GET(self) -> None:  # noqa: N802
        if self.path.split("?", 1)[0].rstrip("/") != "/pair/claim":
            self._send(404, {"error": "not found"})
            return
        pin = (parse_qs(urlparse(self.path).query).get("pin") or [""])[0]
        status, packet = self._backend().poll(str(pin))
        if status == "pending":
            self._send(204)
        elif status == "ready":
            self._send(200, packet)
        elif status == "hot":
            self._send(429, {"error": "too many pairing attempts — wait a minute and try again"})
        else:  # "gone": unknown/expired/consumed — the single-claim door says 410 once used
            self._send(410, {"error": "pairing code expired, unknown, or already claimed"})

    # Unknown methods on the two live routes get 405, matching "only /pair/* exposed".
    def do_PUT(self) -> None: self._method_guard()      # noqa: N802
    def do_DELETE(self) -> None: self._method_guard()   # noqa: N802
    def do_PATCH(self) -> None: self._method_guard()    # noqa: N802

    def _method_guard(self) -> None:
        if self.path.split("?", 1)[0].rstrip("/") == "/pair/claim":
            self._send(405, {"error": "method not allowed"})
        else:
            self._send(404, {"error": "not found"})


def start_server(port: int, backend: Optional[ClaimBackend] = None) -> threading.Thread:
    """Bind 127.0.0.1:port and serve on a daemon thread (idempotent; raises OSError on
    bind failure so callers can distinguish 'another door is already up')."""
    global _server, _thread
    if _server is not None:
        return _thread  # type: ignore[return-value]
    httpd = ThreadingHTTPServer(("127.0.0.1", int(port)), ClaimHandler)
    httpd.daemon_threads = True
    httpd.claim_backend = backend or ClaimBackend.from_module()  # type: ignore[attr-defined]
    thread = threading.Thread(target=httpd.serve_forever, daemon=True, name="wearos-claim")
    _server, _thread = httpd, thread
    thread.start()
    logger.info("wearos: claim door listening on 127.0.0.1:%s (funnel-facing)", httpd.server_address[1])
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
    httpd.shutdown()  # stop serve_forever (safe from any thread but the server's own)
    if thread is not None and thread.is_alive():
        thread.join(timeout=2.0)
    httpd.server_close()
    logger.info("wearos: claim door stopped")


def door_alive(port: int, timeout: float = 1.0) -> bool:
    """Is a claim door ALREADY answering on 127.0.0.1:port (possibly the gateway's)?
    POSTs a PIN-less body — 'malformed' → 400, a route that can never create state
    (adoption requires a valid 6-digit PIN). Any HTTP answer at all proves a listener;
    only 'couldn't connect' is False."""
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
