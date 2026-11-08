"""PIN state machine: TTL expiry, single-claim, rate limits, no-leading-zero format."""

from __future__ import annotations

import re

import pytest

PIN_RE = re.compile(r"^[1-9][0-9]{5}$")


def test_minted_pins_are_6_digits_no_leading_zero(wearos, clock):
    for _ in range(200):
        assert PIN_RE.fullmatch(wearos.mint_pin())
        assert wearos.PIN_TTL_SECONDS == 300  # SPEC §4: 5-minute TTL


def test_plugin_pin_expires_after_ttl(wearos, clock):
    pin = wearos.mint_pin()
    clock.advance(wearos.PIN_TTL_SECONDS + 1)
    with pytest.raises(ValueError, match="expired or unknown"):
        wearos.claim_pin(pin)


def test_late_knock_on_expired_plugin_pin_reports_expired_not_adopted(wearos, clock):
    pin = wearos.mint_pin()
    clock.advance(wearos.PIN_TTL_SECONDS + 1)
    assert wearos.queue_claim(pin, "example.ts.example", "pixel-watch") == "expired"
    # ...and the tombstone itself dies: a much later knock re-adopts as a fresh watch PIN
    clock.advance(wearos.PIN_TTL_SECONDS + 1)
    assert wearos.queue_claim(pin) == "queued"


def test_watch_pin_adopts_on_first_claim(wearos, clock):
    pin = "483920"  # minted on the watch, never seen by the plugin
    assert wearos.queue_claim(pin, "example.ts.example", "pixel-watch") == "queued"
    assert wearos.poll_packet(pin) == ("pending", None)


def test_single_claim(wearos, clock):
    pin = wearos.mint_pin()
    assert wearos.queue_claim(pin, "example.ts.example", "pixel-watch") == "queued"
    wearos.claim_pin(pin)                 # first (and only) match consumes it
    status, _ = wearos.poll_packet(pin)
    assert status == "ready"
    status, _ = wearos.poll_packet(pin)   # pickup is once-only
    assert status == "gone"
    with pytest.raises(ValueError, match="already claimed"):
        wearos.claim_pin(pin)
    assert wearos.queue_claim(pin) == "consumed"


def test_claim_requires_knock_first(wearos, clock):
    pin = wearos.mint_pin()               # Desktop "show PIN": plugin-minted, no knock yet
    with pytest.raises(ValueError, match="hasn't knocked"):
        wearos.claim_pin(pin)


def test_per_pin_knock_cap_turns_hot(wearos, clock):
    pin = "222222"
    for _ in range(wearos.CLAIM_RATE_LIMIT):
        assert wearos.queue_claim(pin) == "queued"
    assert wearos.queue_claim(pin) == "hot"   # 11th knock on the same PIN
    # other PINs are unaffected — the cap is per-PIN, the cooldown below is global
    assert wearos.queue_claim("333333") == "queued"


def test_global_cooldown_after_miss_streak(wearos, clock):
    for i in range(wearos.CLAIM_RATE_LIMIT):
        wearos.poll_packet(f"{500_000 + i}")  # ten guesses at unknown PINs = ten misses
    assert wearos.poll_packet("654321") == ("hot", None)
    assert wearos.queue_claim("654321") == "hot"   # even a fresh knock is 429'd
    clock.advance(wearos.CLAIM_COOLDOWN_SECONDS + 1)
    assert wearos.queue_claim("654321") == "queued"  # ...until the window passes


def test_pending_polls_are_not_misses(wearos, clock):
    pin = wearos.mint_pin()
    wearos.queue_claim(pin)
    for _ in range(30):                      # the watch's ~3 s polling must not self-trip
        assert wearos.poll_packet(pin) == ("pending", None)
    wearos.claim_pin(pin)
    assert wearos.poll_packet(pin)[0] == "ready"


def test_malformed_pins_are_rejected_everywhere(wearos, clock):
    for bad in ("", "12345", "1234567", "abcdef", "012345", "12 3456", "000000"):
        assert wearos.queue_claim(bad) == "malformed"
        assert wearos.poll_packet(bad) == ("gone", None)
        with pytest.raises(ValueError):
            wearos.claim_pin(bad)
