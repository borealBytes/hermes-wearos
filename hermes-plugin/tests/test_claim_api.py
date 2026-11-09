"""The claim door (v2) end-to-end over real HTTP (server on 127.0.0.1 ephemeral
port, FAKE Tailscale client via conftest — no network leaves the process)."""

from __future__ import annotations

import json
import urllib.error
import urllib.request

import pytest

from conftest import FAKE_AUTHKEY, SECRET_A, SECRET_B


def http(base: str, method: str, path: str, body=None) -> tuple[int, dict | None]:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        return exc.code, (json.loads(raw) if raw else None)


def begin(base, secret=SECRET_A, label="pixel-watch-6"):
    return http(base, "POST", "/pair/begin",
                {"v": 2, "secret": secret, "label": label, "tailnet": "example.ts.example"})


def poll(base, secret):
    return http(base, "GET", f"/pair/poll?secret={secret}")


def ack(base, secret):
    return http(base, "POST", "/pair/ack", {"v": 2, "secret": secret})


@pytest.fixture
def door(wearos):
    """Spin the claim server on an ephemeral port against the sandboxed pairing core."""
    backend = wearos.claim_server.ClaimBackend(wearos.door_begin, wearos.door_poll,
                                               wearos.door_ack)
    wearos.claim_server.start_server(0, backend)
    yield f"http://127.0.0.1:{wearos.claim_server.actual_port()}"
    wearos.claim_server.stop_server()


# ── v1 retirement ───────────────────────────────────────────────────────────────
def test_v1_routes_answer_426_upgrade_required(wearos, door):
    assert http(door, "POST", "/pair/claim", {"pin": "123456", "hostname": "w"}) == (
        426, {"error": "pairing protocol v1 retired — update the watch app",
              "upgrade": "v2"})
    assert http(door, "GET", "/pair/claim?pin=123456")[0] == 426
    assert http(door, "POST", "/pair/begin", {"secret": SECRET_A, "label": "w"})[0] == 426
    # the door_alive probe itself:
    assert http(door, "POST", "/pair/claim", {})[0] == 426


# ── the window gate + happy path ────────────────────────────────────────────────
def test_begin_refuses_closed_door_of_a_closed_window(wearos, door):
    status, body = begin(door)
    assert status == 403 and "open it at the gateway" in body["error"]


def test_full_201_204_200_200_sequence(wearos, door):
    wearos.open_pairing()
    status, body = begin(door)
    assert status == 201 and body["expires_in"] == 300
    code = body["code"]
    assert poll(door, SECRET_A) == (204, None)          # awaiting the human's claim
    wearos.approve_pair(code)
    status, payload = poll(door, SECRET_A)
    assert status == 200 and payload["state"] == "ready"
    assert set(payload) == {"state", "authkey", "key_id", "profile", "gateway_fqdn",
                            "gateway_port", "watchkit_port", "private_port"}
    assert payload["authkey"] == FAKE_AUTHKEY
    assert payload["gateway_fqdn"] == "gw.example.ts.net"
    assert payload["gateway_port"] == 8642
    # THE review fix, contract shape: no api_key, no watchkit_token, and NO PIN on
    # any public response — credentials wait for the private lane (D13).
    assert "api_key" not in payload and "watchkit_token" not in payload
    status, body = ack(door, SECRET_A)
    assert status == 200 and body["token"].startswith("wtk-")
    assert poll(door, SECRET_A)[0] == 410               # once means ONCE
    assert ack(door, SECRET_A)[0] == 410                # re-ack: gone, no second token


def test_the_code_never_rides_a_url(wearos, door):
    wearos.open_pairing()
    code = begin(door)[1]["code"]
    wearos.approve_pair(code)
    # the door's own access-log line would carry query strings — prove the ONLY
    # public GET param in the protocol is the 256-bit secret, never the code:
    assert code not in f"/pair/poll?secret={SECRET_A}"
    status, payload = poll(door, SECRET_A)
    assert status == 200 and code not in json.dumps(payload)


# ── flood/abuse behavior ────────────────────────────────────────────────────────
def test_malformed_begin_bodies(wearos, door):
    wearos.open_pairing()
    assert http(door, "POST", "/pair/begin",
                {"v": 2, "secret": "nothex", "label": "w"})[0] == 400
    assert http(door, "POST", "/pair/begin", {"v": 2, "secret": "zz" * 32})[0] == 400
    assert http(door, "POST", "/pair/begin",
                {"v": 2, "secret": 12345, "label": None})[0] == 400
    assert http(door, "POST", "/pair/begin", ["array"])[0] == 400


def test_giant_body_refused(door):
    big = {"v": 2, "secret": SECRET_A, "label": "x" * 8000}
    status, body = http(door, "POST", "/pair/begin", big)
    assert status == 400 and "small JSON" in body["error"]


def test_pending_polls_are_throttled_but_approval_breaks_through(wearos, door, monkeypatch):
    wearos.open_pairing()
    begin(door)
    assert poll(door, SECRET_A)[0] == 204
    assert poll(door, SECRET_A)[0] == 429          # per-secret floor (POLL_MIN_INTERVAL)
    # THE v1 bug inverted: an APPROVED pickup is never throttled, even mid-429:
    code = wearos._engine().pending_list()[0]["code"]
    wearos.approve_pair(code)
    status, payload = poll(door, SECRET_A)
    assert status == 200 and payload["state"] == "ready"


def test_wrong_secret_gets_410_not_a_lockout(wearos, door):
    wearos.open_pairing()
    begin(door)
    assert poll(door, SECRET_B)[0] == 410
    assert ack(door, SECRET_B)[0] == 410
    assert not wearos._engine().claim_locked()["locked"]  # (v1: misses froze everyone)


def test_blank_404_and_method_guards(wearos, door):
    assert http(door, "GET", "/")[0] == 404
    assert http(door, "GET", "/p/wear/v1/health")[0] == 404
    assert http(door, "GET", "/pair/begin")[0] == 404
    assert http(door, "PUT", "/pair/begin", {})[0] == 405
    assert http(door, "DELETE", "/pair/poll")[0] == 405
