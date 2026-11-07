"""The claim door end-to-end over real HTTP (server on 127.0.0.1 ephemeral port,
FAKE Tailscale client injected via conftest — no network leaves the process)."""

from __future__ import annotations

import json
import urllib.error
import urllib.request

import pytest

from conftest import FAKE_AUTHKEY


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


CONTRACT_KEYS = {"authkey", "tailnet", "gateway_fqdn", "gateway_port",
                 "watchkit_port", "profile", "api_key", "watchkit_token"}


@pytest.fixture
def door(wearos, tmp_path):
    """Spin the claim server on an ephemeral port against the sandboxed pairing core."""
    backend = wearos.claim_server.ClaimBackend(wearos.queue_claim, wearos.poll_packet)
    wearos.claim_server.start_server(0, backend)
    yield f"http://127.0.0.1:{wearos.claim_server.actual_port()}"
    wearos.claim_server.stop_server()


def knock(base, pin, tailnet="example.ts.example", hostname="pixel-watch-6"):
    return http(base, "POST", "/pair/claim", {"pin": pin, "tailnet": tailnet, "hostname": hostname})


def poll(base, pin):
    return http(base, "GET", f"/pair/claim?pin={pin}")


def test_full_202_204_200_once_410_sequence(wearos, door):
    pin = "429183"
    assert knock(door, pin) == (202, {"queued": True})
    assert poll(door, pin) == (204, None)          # knocked, user hasn't claimed yet
    device = wearos.claim_pin(pin)                 # the user's verb, wherever it runs
    status, packet = poll(door, pin)
    assert status == 200
    assert set(packet) == CONTRACT_KEYS            # contract-exact, no key drift
    assert packet["authkey"] == FAKE_AUTHKEY
    assert packet["tailnet"] == "example.ts.example"
    assert packet["gateway_fqdn"] == "gw.example.ts.net"
    assert packet["gateway_port"] == 8642
    assert packet["watchkit_port"] == 42651
    assert packet["profile"] == "wear"
    assert packet["watchkit_token"] is None        # nullable by contract
    assert device["hostname"] == "pixel-watch-6"   # the hostname THE WATCH SENT
    assert poll(door, pin)[0] == 410               # once means ONCE
    assert knock(door, pin)[0] == 410              # and the door stays closed after it


def test_authkey_zeroed_in_ledger_after_delivery(wearos, door):
    pin = "314159"
    knock(door, pin)
    wearos.claim_pin(pin)
    state_text = wearos._CTX.state.path.read_text(encoding="utf-8")
    assert FAKE_AUTHKEY in state_text              # queued between claim and pickup...
    assert poll(door, pin)[0] == 200
    state_text = wearos._CTX.state.path.read_text(encoding="utf-8")
    assert FAKE_AUTHKEY not in state_text          # ...zeroed the moment it's handed over
    devices = wearos._CTX.state.get("devices")
    assert all(FAKE_AUTHKEY not in json.dumps(rec) for rec in devices.values())


def test_api_key_reads_gateway_profile_scope(wearos, door, tmp_path):
    profile_env = tmp_path / "profiles" / "wear" / ".env"
    profile_env.parent.mkdir(parents=True)
    profile_env.write_text("API_SERVER_KEY=profile-scoped-key\n", encoding="utf-8")
    knock(door, "271828")
    wearos.claim_pin("271828")
    _, packet = poll(door, "271828")
    assert packet["api_key"] == "profile-scoped-key"   # tier 1: the wear profile's own .env


def test_api_key_falls_back_to_declared_secret(wearos, door, monkeypatch):
    monkeypatch.setenv("gateway_api_key", "declared-fallback")   # no profile .env, no env key
    knock(door, "161803")
    wearos.claim_pin("161803")
    _, packet = poll(door, "161803")
    assert packet["api_key"] == "declared-fallback"


def test_429_on_per_pin_cap(wearos, door):
    for _ in range(wearos.CLAIM_RATE_LIMIT):
        assert knock(door, "777777")[0] == 202
    status, body = knock(door, "777777")
    assert status == 429 and "error" in body


def test_400_for_malformed_bodies(wearos, door):
    assert knock(door, "0123456")[0] == 400          # leading zero is NOT a valid PIN
    assert knock(door, "12ab56")[0] == 400
    assert knock(door, 12345)[0] == 400              # wrong JSON type/length coerces short
    assert http(door, "POST", "/pair/claim", ["not", "an", "object"])[0] == 400
    assert http(door, "POST", "/pair/claim", {})[0] == 400  # probe body is no state-op


def test_unaired_paths_are_blank_404_and_wrong_method_405(wearos, door):
    assert http(door, "GET", "/")[0] == 404
    assert http(door, "GET", "/p/wear/v1/health")[0] == 404
    assert http(door, "GET", "/pair/anything")[0] == 404
    assert http(door, "PUT", "/pair/claim", {})[0] == 405
    assert http(door, "DELETE", "/pair/claim")[0] == 405


def test_unknown_pin_poll_is_gone_and_counts_a_miss(wearos, door):
    for i in range(wearos.CLAIM_RATE_LIMIT):
        status, body = poll(door, f"{600_000 + i}")
        assert status == 410 and "error" in body
    status, body = poll(door, "654321")              # streak tripped → global cooldown
    assert status == 429 and "error" in body
