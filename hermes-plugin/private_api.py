"""The private channel — the watch's post-enrollment lane (SPEC §11.4, D8/D12/D13/D18).

A stdlib ``ThreadingHTTPServer`` bound to the gateway's OWN TAILNET IP (never
0.0.0.0, never loopback-only): only tailnet peers can reach it at all, and Funnel
cannot front it. It is the single private surface carrying everything that must NOT
be public:

  POST /watch/<profile>/session   Bearer <device token>, body {"v":2,"secret":"<hex>"?}
       → 200 {"api_key","watchkit_token","gateway_port","watchkit_port","gateway_fqdn"}
       The identity check runs on the CONNECTING SOCKET, not a self-reported field:
       ``tailscale whois <peer ip>`` is the tailnet daemon's own verified answer for
       which node dialed. The first session must carry the pairing secret (binds the
       node identity to the device that paired); afterwards the recorded node id must
       match, so a squatter's node can never collect a key it didn't earn.
  GET  /watch/<profile>/events?since=N   outbox + cron alerts (pairing.queue_message)
  POST /watch/<profile>/ack      {"up_to":N}   delivery confirmation
  POST /watch/<profile>/forget   self-unpair: same full revoke the CLI runs, started
       from the watch's own confirm dialog.

The voice-out surface (SPEC §19, issue #7) rides the SAME lane with the SAME
identity law — the tailnet node, not a bearer token (the watch hits these before
and after session refresh, and the payload half of them ARE credential-bearing
audio):

  GET  /voice/prefs              the canonical voiceout.yaml document (rev'd)
  PUT  /voice/prefs              replay the watch's offline edits (higher rev wins)
  GET  /voice/cards              the mirror snapshot (prefs + engine/aligner flags)
  GET  /voice/card_wav?card_id=  a card's reference wav, raw audio/wav
  POST /voice/card_create        {wav_b64,label,transcript?} — validated 3–60 s
  POST /voice/card_delete        {card_id}
  POST /voice/synthesize         {text,agent?} → {audio_wav_b64,words[],align,…}

Every /voice/* request requires a WHOIS-verified node bound to a paired device;
unbound nodes answer 403. Bodies there cap at VOICE_MAX_BODY (a 60 s clip fits);
every other route keeps the 4 KiB cap.

Auth is a per-device bearer token issued once at pairing ack (shown exactly once;
the store keeps only its SHA-256). Limits mirror the public door: small bodies,
socket timeouts, per-device throttle. This server also answers every profile's
devices from the shared pairing store; reading a DIFFERENT profile's API_SERVER_KEY
requires an explicit grant (D17) — see pairing.resolve_key.
"""

from __future__ import annotations

import hashlib
import json
import logging
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Optional
from urllib.parse import parse_qs, urlparse

logger = logging.getLogger("hermes_wearkit.private")

MAX_BODY_BYTES = 4096
VOICE_MAX_BODY = 8_500_000         # a 60 s 16-bit clip base64'd (create_card caps raw at 6 MB)
SOCKET_TIMEOUT = 30
MAX_INFLIGHT = 64

_server: Optional[ThreadingHTTPServer] = None
_thread: Optional[threading.Thread] = None


class PrivateBackend:
    """Everything the server needs, dependency-injected (tests run without tailscale).

    ``whois(peer_ip) -> {node_id, node_name, tags}`` or None when the peer is not a
    tailnet node. ``session(profile, device_name, secret) -> dict`` returns the
    credential payload. ``forget(profile, device_name)`` runs the full revoke."""

    def __init__(self, whois: Callable[[str], Optional[dict]],
                 session: Callable[..., dict],
                 events: Callable[..., dict],
                 ack: Callable[..., dict],
                 forget: Callable[..., None],
                 voice: Optional[Callable[..., tuple]] = None) -> None:
        self.whois = whois
        self.session = session
        self.events = events
        self.ack = ack
        self.forget = forget
        # voice(action, method, body, node, query) -> (status, payload); payload may
        # be {"_raw": bytes, "_ctype": str} to stream audio. None = surface unwired.
        self.voice = voice


class PrivateHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "hermes-wearkit-private/0.2"
    timeout = SOCKET_TIMEOUT

    def log_message(self, fmt: str, *args: Any) -> None:
        logger.debug("private lane: %s " + fmt, self.client_address[0], *args)

    def handle(self) -> None:
        server = self.server
        server.inflight += 1
        try:
            if server.inflight > MAX_INFLIGHT:
                try:
                    self._send(503, {"error": "private lane busy"})
                except OSError:
                    pass
                return
            super().handle()
        finally:
            server.inflight -= 1

    def _send(self, status: int, payload: Optional[dict] = None) -> None:
        body = b"" if payload is None else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _read_body(self, cap: int = MAX_BODY_BYTES) -> Optional[dict]:
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = -1
        if not 0 <= length <= cap:
            self._send(400, {"error": "body must be a small JSON object"})
            return None
        try:
            raw = self.rfile.read(length)
        except OSError:
            raw = b""
        if not raw:
            return {}
        try:
            payload = json.loads(raw)
        except ValueError:
            self._send(400, {"error": "invalid JSON body"})
            return None
        if not isinstance(payload, dict):
            self._send(400, {"error": "body must be a JSON object"})
            return None
        return payload

    def _device(self) -> Optional[str]:
        """Identify the dialing device by bearer token against the registry (constant
        time). The URL's <profile> is checked against the device record afterwards."""
        auth = str(self.headers.get("Authorization") or "")
        if not auth.startswith("Bearer "):
            return None
        token = auth[7:].strip()[:128]
        if not token:
            return None
        return self.server.backend_device_lookup(token)  # type: ignore[attr-defined]

    def _route(self) -> tuple[str, str]:
        """(action, profile) from /watch/<profile>/<action>; ("", "") otherwise."""
        parts = [p for p in urlparse(self.path).path.split("/") if p]
        if len(parts) == 3 and parts[0] == "watch":
            return parts[2], parts[1]
        return "", ""

    # ------------------------------------------------------------- /voice/* surface

    def _is_voice(self) -> bool:
        return urlparse(self.path).path.startswith("/voice/")

    def _handle_voice(self, method: str, body: Optional[dict]) -> None:
        """Node-law auth (WHOIS says which tailnet node dialed; the pairing store
        says whether that node is bound to a paired device), then the voice
        backend. NO bearer token on this surface — identity is the socket."""
        parts = [p for p in urlparse(self.path).path.split("/") if p]
        action = parts[1] if len(parts) == 2 else ""
        backend: PrivateBackend = self.server.private_backend  # type: ignore[attr-defined]
        node = backend.whois(self.client_address[0])
        if node is None:
            self._send(403, {"error": "connection did not arrive over the tailnet"})
            return
        if backend.voice is None:
            self._send(501, {"error": "voice surface not wired"})
            return
        try:
            status, payload = backend.voice(action, method, body or {}, node,
                                            parse_qs(urlparse(self.path).query))
        except PermissionError as exc:
            status, payload = 403, {"error": str(exc)}
        except ValueError as exc:
            status, payload = 400, {"error": str(exc)[:300]}
        except RuntimeError as exc:                 # engine absent/crashed: transient
            status, payload = 503, {"error": str(exc)[:300]}
        except Exception:
            logger.exception("voice route %s", action)
            status, payload = 500, {"error": "voice route failed"}
        if isinstance(payload, dict) and "_raw" in payload:
            raw = payload["_raw"]
            if not isinstance(raw, (bytes, bytearray)):
                self._send(500, {"error": "voice backend returned a bad raw body"})
                return
            self.send_response(int(status))
            self.send_header("Content-Type", payload.get("_ctype", "application/oct"
                                                                    "et-stream"))
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(raw)
            return
        self._send(int(status), payload)

    def do_POST(self) -> None:  # noqa: N802
        if self._is_voice():
            body = self._read_body(VOICE_MAX_BODY)
            if body is not None:
                self._handle_voice("POST", body)
            return
        body = self._read_body()
        if body is None:
            return
        action, profile = self._route()
        if action == "":
            self._send(404, {"error": "not found"})
            return
        device = self._device()
        if device is None:
            self._send(401, {"error": "missing or unknown device token"})
            return
        backend: PrivateBackend = self.server.private_backend  # type: ignore[attr-defined]
        try:
            if action == "session":
                peer_ip = self.client_address[0]
                node = backend.whois(peer_ip)
                if node is None:
                    self._send(403, {"error": "connection did not arrive over the tailnet"})
                    return
                secret = str(body.get("secret", ""))[:128]
                self._send(200, backend.session(profile, device, secret, node))
            elif action == "ack":
                self._send(200, backend.ack(profile, device, int(body.get("up_to", 0) or 0)))
            elif action == "forget":
                backend.forget(profile, device)
                self._send(200, {"forgotten": device})
            elif action == "events":
                self._send(405, {"error": "use GET"})
            else:
                self._send(404, {"error": "not found"})
        except PermissionError as exc:
            self._send(403, {"error": str(exc)})
        except KeyError:
            self._send(410, {"error": "device or pairing record is gone"})
        except ValueError as exc:  # pairing.PairingError is a ValueError
            from . import pairing
            outcome = getattr(exc, "outcome", "malformed")
            code = {"locked": 403, "gone": 410, "hot": 429, "closed": 403}.get(
                outcome, 400)
            self._send(code, {"error": str(exc)})

    def do_GET(self) -> None:  # noqa: N802
        if self._is_voice():
            self._handle_voice("GET", None)
            return
        action, profile = self._route()
        if action == "":
            self._send(404, {"error": "not found"})
            return
        if action != "events":
            self._send(405 if action == "session" else 404, {"error": "use POST"
                                                             if action == "session"
                                                             else "not found"})
            return
        device = self._device()
        if device is None:
            self._send(401, {"error": "missing or unknown device token"})
            return
        since = (parse_qs(urlparse(self.path).query).get("since") or ["0"])[0]
        backend: PrivateBackend = self.server.private_backend  # type: ignore[attr-defined]
        try:
            n = max(0, int(since))
        except ValueError:
            n = 0
        try:
            self._send(200, backend.events(profile, device, n))
        except KeyError:
            self._send(410, {"error": "device is gone"})
        except PermissionError as exc:
            # A poll for a sibling agent's feed is a POLICY answer, not a
            # crash: answer a clean 403 (the watch backs off its 20 s retry
            # loop against a status, and the gateway journal stops holding a
            # fresh traceback every poll — owner 2026-11-11 "fix that noisy
            # error"). The message names the paired agent so a pane-side
            # grant click is the obvious cure.
            self._send(403, {"error": str(exc)})

    def do_PUT(self) -> None:  # noqa: N802
        if self._is_voice():
            body = self._read_body(VOICE_MAX_BODY)
            if body is not None:
                self._handle_voice("PUT", body)
            return
        self._send(405, {"error": "use POST"})


def _whois_local(peer_ip: str, timeout: float = 5.0) -> Optional[dict]:
    """``tailscale whois --json <ip>`` — the LOCAL daemon's verified answer for which
    tailnet node owns a connection. None (fail closed) on any error."""
    ip = peer_ip.strip("[]").rsplit("%", 1)[0]
    try:
        out = subprocess.run(["tailscale", "whois", "--json", ip],
                             capture_output=True, text=True, timeout=timeout)
        if out.returncode != 0:
            return None
        data = json.loads(out.stdout)
    except Exception:
        return None
    node = data.get("Node") or {}
    profile = data.get("Profile") or {}
    if not node.get("ID"):
        return None
    return {"node_id": str(node.get("ID") or ""),
            "node_name": str(node.get("Name") or node.get("HostName") or ""),
            "tags": [str(t) for t in (node.get("Tags") or [])][:8],
            "profile": str(profile.get("LoginName") or "")}


def start_server(host: str, port: int, backend: PrivateBackend,
                 device_lookup: Callable[[str], Optional[str]]) -> threading.Thread:
    """Bind host:port and serve (daemon thread). host MUST be the gateway's tailnet
    IP in production; tests pass 127.0.0.1. Raises OSError on bind collision."""
    global _server, _thread
    if _server is not None:
        return _thread
    httpd = ThreadingHTTPServer((host, int(port)), PrivateHandler)
    httpd.daemon_threads = True
    httpd.private_backend = backend
    httpd.backend_device_lookup = device_lookup
    httpd.inflight = 0
    thread = threading.Thread(target=httpd.serve_forever, daemon=True, name="wearos-private")
    _server, _thread = httpd, thread
    thread.start()
    logger.info("wearos: private lane listening on %s:%s (tailnet-only)", host, port)
    return thread


def actual_port() -> int:
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


def lane_alive(host: str, port: int, timeout: float = 1.0) -> bool:
    """A LANE-shaped answer from the lane port proves a real lane listens: the
    route table itself is the identity (401/403 to an anonymous GET /watch/x/events
    is the expected answer — the route exists and auth is on). Any HTTP answer is
    NOT enough: a squatter (an unrelated HTTP server that grabbed the port during
    a restart race — observed 2026-11-10: a watcher process importing a half-wired
    wearos snapshot served 404 'not found' on both lane doors) answers every
    request and would masquerade as healthy while every voice call 404s. A 404
    only passes if the voice surface is wired behind it."""
    import urllib.error
    import urllib.request
    req = urllib.request.Request(f"http://{host}:{port}/watch/x/events")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            resp.read()
            return True
    except urllib.error.HTTPError as exc:
        if exc.code != 404:
            return True                      # 401/403/410 — the lane's own auth law
    except OSError:
        return False
    try:                                     # 404 on /watch — is at least voice wired?
        with urllib.request.urlopen(
                f"http://{host}:{port}/voice/prefs", timeout=timeout) as resp:
            resp.read()
            return True
    except urllib.error.HTTPError as exc:
        return exc.code != 404               # 403 (WHOIS gate off-lane) still means lane
    except OSError:
        return False
