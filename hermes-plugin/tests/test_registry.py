"""Device registry: hostname comes from the watch, first-seen sticks, re-pair updates."""

from __future__ import annotations

from conftest import load_plugin


def _packet(wearos, tailnet="example.ts.example"):
    return {"authkey": "tskey-x", "tailnet": tailnet, "gateway_fqdn": "gw.example.ts.net",
            "gateway_port": 8642, "watchkit_port": 42651, "profile": "wear",
            "api_key": "k", "watchkit_token": None}


def test_records_land_under_the_watch_sent_hostname(wearos, clock):
    record = wearos._register_device(_packet(wearos), hostname="Pixel-Watch-6")
    devices = wearos._CTX.state.get("devices")
    assert list(devices) == ["pixel-watch-6"]           # lowercased, never the hardcoded default
    assert record["hostname"] == "pixel-watch-6"
    assert record["tag"] == "tag:hermes-watch"
    assert record["tailnet"] == "example.ts.example"
    assert record["gateway_fqdn"] == "gw.example.ts.net"
    assert "authkey" not in record                      # the registry never holds a key


def test_hostname_is_sanitized_not_injected(wearos, clock):
    record = wearos._register_device(_packet(wearos), hostname="Clay's Watch ✦")
    assert record["hostname"] == "clay-s-watch"         # path-safe key charset only
    empty = wearos._register_device(_packet(wearos), hostname="   ")
    assert empty["hostname"] == "wear-watch"            # falls back to <profile>-watch


def test_re_pair_updates_in_place_and_keeps_first_seen(wearos, clock):
    first = wearos._register_device(_packet(wearos), hostname="pixel-watch-6")
    clock.advance(3600)
    second = wearos._register_device(_packet(wearos), hostname="pixel-watch-6",
                                     tailnet_hint="old.example.ts.example")
    devices = wearos._CTX.state.get("devices")
    assert list(devices) == ["pixel-watch-6"]           # still ONE record
    assert second["first_seen"] == first["first_seen"]  # first-seen never moves
    assert second["re_enrolled_at"] == first["enrolled_at"] + 3600
    assert second["enrolled_at"] == first["enrolled_at"] + 3600
    assert second["tailnet_hint"] == "old.example.ts.example"


def test_claim_flow_registers_under_knocked_hostname_with_full_shape(wearos, clock):
    pin = "429183"
    wearos.queue_claim(pin, tailnet_hint="example.ts.example", hostname="ultra-2026")
    device = wearos.claim_pin(pin)
    devices = wearos._CTX.state.get("devices")
    assert device == devices["ultra-2026"]
    assert sorted(device) == sorted({"hostname", "tag", "tailnet", "gateway_fqdn", "profile",
                                     "first_seen", "enrolled_at", "tailnet_hint"})
