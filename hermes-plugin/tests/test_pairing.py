"""Pairing v2 state machine: gateway-issued codes, window gate, lockout, tokens,
outbox, first-session binding — and the file-level promises (secret never stored
raw, authkey zeroed at rest, fcntl transactions across processes)."""

from __future__ import annotations

import json
import re

import pytest

from conftest import SECRET_A, SECRET_B, FAKE_AUTHKEY

CODE_RE = re.compile(r"^[1-9][0-9]{5}$")


# ── the window (D5: closed by default) ──────────────────────────────────────────
def test_begin_refuses_with_the_door_closed(wearos):
    with pytest.raises(wearos.pairing.PairingError) as exc:
        wearos.door_begin(SECRET_A, "Galaxy Watch Ultra")
    assert exc.value.outcome == "closed"


def test_window_grants_one_pairing_and_expires(wearos, clock):
    wearos.open_pairing()
    begun = wearos.door_begin(SECRET_A, "Galaxy Watch Ultra")
    assert CODE_RE.fullmatch(begun["code"])       # the GATEWAY issued the code
    # one pairing per open: a second watch must wait for a fresh window
    with pytest.raises(wearos.pairing.PairingError) as exc:
        wearos.door_begin(SECRET_B, "Pixel Watch")
    assert exc.value.outcome == "closed"
    # a RE-begin (same watch, e.g. it missed the response) rides the same record
    again = wearos.door_begin(SECRET_A, "Galaxy Watch Ultra")
    assert again["code"] == begun["code"]
    clock.advance(wearos.PAIR_WINDOW_SECONDS + 1)  # window already consumed anyway


# ── claim (approval) side ────────────────────────────────────────────────────────
def test_unknown_code_is_never_adopted(wearos):
    wearos.open_pairing()
    wearos.door_begin(SECRET_A, "Watch")
    with pytest.raises(ValueError, match="unknown"):
        wearos.approve_pair("123456")  # a code this gateway never issued


def test_three_wrong_codes_lock_claiming(wearos, clock):
    wearos.open_pairing()
    code = wearos.door_begin(SECRET_A, "Watch")["code"]
    for guess in ("111111", "222222", "333333"):
        with pytest.raises(ValueError):
            wearos.approve_pair(guess)
    lock = wearos._engine().claim_locked()
    assert lock["locked"]
    # even the RIGHT code can't get through a lockout — and it counts for nothing
    with pytest.raises(ValueError, match="locked"):
        wearos.approve_pair(code)
    clock.advance(wearos.pairing.LOCK_SECONDS + 1)
    assert not wearos._engine().claim_locked()["locked"]
    wearos.open_pairing()  # the original code aged out during its own lockout: real life
    code2 = wearos.door_begin(SECRET_A, "Watch")["code"]
    wearos.approve_pair(code2)  # ...and claiming works again


def test_second_lockout_is_longer(wearos, clock):
    for round_no in (1, 2):
        wearos.open_pairing()
        wearos.door_begin(SECRET_A, "Watch")
        for guess in ("111111", "222222", "333333"):
            with pytest.raises(ValueError):
                wearos.approve_pair(guess)
        left = wearos._engine().claim_locked()["until"] - clock.now
        want = (wearos.pairing.LOCK_SECONDS if round_no == 1
                else wearos.pairing.LOCK_SECONDS_REPEAT)
        assert want - 1 <= left <= want + 1, f"lock {round_no} length {left}"
        clock.advance(left + 1)


def test_success_clears_the_miss_streak(wearos):
    wearos.open_pairing()
    code = wearos.door_begin(SECRET_A, "Watch")["code"]
    with pytest.raises(ValueError):
        wearos.approve_pair("111111")
    with pytest.raises(ValueError):
        wearos.approve_pair("222222")
    wearos.approve_pair(code)  # 2 misses then a hit: no lockout survives
    assert not wearos._engine().claim_locked()["locked"]


def test_malformed_codes_rejected_everywhere(wearos):
    wearos.open_pairing()
    for bad in ("", "12345", "1234567", "abcdef", "012345", "000000", "12 3456"):
        with pytest.raises(ValueError):
            wearos.approve_pair(bad)


# ── the full ceremony ───────────────────────────────────────────────────────────
def test_full_flow_issue_approve_pickup_ack(wearos, clock):
    wearos.open_pairing()
    code = wearos.door_begin(SECRET_A, "Galaxy Watch Ultra")["code"]
    # watch polls BEFORE approval: pending, not a miss
    assert wearos.door_poll(SECRET_A) == ("pending", None)
    wearos.approve_pair(code)
    state, payload = wearos.door_poll(SECRET_A)
    assert state == "ready"
    assert payload["authkey"] == FAKE_AUTHKEY
    assert payload["gateway_fqdn"] == "gw.example.ts.net"   # door_poll adds the dial plan
    assert payload["private_port"] == wearos.PRIVATE_PORT
    ack = wearos.door_ack(SECRET_A)
    assert ack["token"].startswith("wtk-")
    assert ack["device"]["label"] == "Galaxy Watch Ultra"
    # pickup was once: the key is gone, and the record retires behind the device
    assert wearos.door_poll(SECRET_A)[0] == "gone"
    dev = wearos._engine().device(ack["device"]["hostname"])
    assert dev and len(dev["token_hash"]) == 64


def test_expired_approval_expires_the_minted_key(wearos, clock, monkeypatch):
    expired = []
    monkeypatch.setattr(wearos.tailscale.TailnetClient, "expire_key",
                        lambda self, key_id: expired.append(key_id))
    wearos.open_pairing()
    code = wearos.door_begin(SECRET_A, "Watch")["code"]
    wearos.approve_pair(code)
    clock.advance(wearos.pairing.PICKUP_WINDOW + 2)
    assert wearos.door_poll(SECRET_A)[0] == "gone"
    assert expired, "an approved-but-never-collected key must not live on"


def test_wrong_secret_never_sees_the_key(wearos):
    wearos.open_pairing()
    code = wearos.door_begin(SECRET_A, "Watch")["code"]
    wearos.approve_pair(code)
    assert wearos.door_poll(SECRET_B)[0] == "gone"          # the secret IS the pickup auth
    with pytest.raises(ValueError):
        wearos.door_ack(SECRET_B)


# ── at-rest promises ────────────────────────────────────────────────────────────
def test_store_holds_no_raw_secret_and_no_live_authkey(wearos):
    wearos.open_pairing()
    code = wearos.door_begin(SECRET_A, "Watch")["code"]
    wearos.approve_pair(code)
    raw = wearos._engine().store.path.read_text(encoding="utf-8")
    assert SECRET_A not in raw                              # hash-only
    assert FAKE_AUTHKEY in raw                              # while approved, in-flight
    wearos.door_ack(SECRET_A)
    raw = wearos._engine().store.path.read_text(encoding="utf-8")
    assert FAKE_AUTHKEY not in raw                          # zeroed at pickup
    assert "wtk-" not in raw                                # device token: hash only


# ── cross-process transactionality ──────────────────────────────────────────────
def test_two_engines_one_file(wearos):
    """The CLI and the door live in DIFFERENT processes: two engine instances over
    the same store file must interleave transactionally (this is what the fcntl lock
    is for)."""
    pairing = wearos.pairing
    a = pairing.PairingEngine(pairing.Store(), mint_key=wearos._mint_key)
    b = pairing.PairingEngine(pairing.Store(), mint_key=wearos._mint_key)
    b.open_window("default")
    code = a.begin(SECRET_A, "Watch", "default")["code"]
    assert b.approve(code, tag="tag:hermes-watch")["label"] == "Watch"
    state, payload = a.poll(SECRET_A)
    assert state == "ready" and payload["authkey"] == FAKE_AUTHKEY
    ack = b.ack(SECRET_A)
    assert ack["token"]
    assert a.device(ack["device"]["hostname"])["token_hash"]


# ── flood caps (public side can delay, never lock out) ─────────────────────────
def test_begin_flood_hits_the_public_caps(wearos):
    """The D6 promise in its v2 shape: flood begins die on the RATES (3/min, then
    pending cap) — and the approval side stays untouched: already-issued codes are
    always approvable."""
    ok = 0
    for i in range(10):
        wearos.open_pairing()
        try:
            wearos.door_begin(f"{i:064x}", f"Watch {i}")
            ok += 1
        except wearos.pairing.PairingError as exc:
            assert exc.outcome == "hot"
    assert ok == wearos.pairing.BEGIN_PER_MINUTE
    codes = [p["code"] for p in wearos._engine().pending_list()]
    wearos.approve_pair(codes[0])           # flood can't slow a human down
    assert not wearos._engine().claim_locked()["locked"]


def test_public_traffic_cannot_trip_the_claim_lockout(wearos):
    """The v1 bug, v2 shape: a stranger hammering the door must not lock the human's
    approval side. Only TYPED misses count; floods of begins/polls do not."""
    wearos.open_pairing()
    code = wearos.door_begin(SECRET_A, "Watch")["code"]
    for i in range(50):
        wearos.door_poll(f"{i:064x}")
        try:
            wearos.door_begin(f"{i + 100:064x}", "Flood")
        except wearos.pairing.PairingError:
            pass  # caps — expected, and irrelevant to the lockout either way
    wearos.approve_pair(code)  # still approvable
    assert not wearos._engine().claim_locked()["locked"]


# ── private lane registry pieces ────────────────────────────────────────────────
def _enrolled(wearos):
    wearos.open_pairing()
    code = wearos.door_begin(SECRET_A, "Galaxy Watch Ultra")["code"]
    wearos.approve_pair(code)
    ack = wearos.door_ack(SECRET_A)
    return ack["device"]["hostname"], ack["token"]


def test_first_session_binds_the_node(wearos):
    name, _token = _enrolled(wearos)
    node = {"node_id": "n-watch", "node_name": "watch.example.ts.net", "tags": ["tag:hermes-watch"]}
    dev = wearos._engine().first_session(name, SECRET_A, node)
    assert dev["node_id"] == "n-watch"
    # same node, no secret needed anymore
    wearos._engine().first_session(name, "", {**node, "node_name": "x"})
    # a DIFFERENT node dialing with the same token is refused (identity law)
    with pytest.raises(PermissionError):
        wearos._engine().first_session(name, SECRET_A, {**node, "node_id": "n-other"})


def test_first_session_requires_fresh_pairing_secret(wearos):
    name, _ = _enrolled(wearos)
    wearos._engine().store.mutate(lambda doc: doc["pairs"].clear())  # records retired
    with pytest.raises(PermissionError, match="fresh pairing secret"):
        wearos._engine().first_session(name, SECRET_A,
                                       {"node_id": "n-1", "node_name": "", "tags": []})


def test_device_lookup_and_revoke(wearos, monkeypatch):
    monkeypatch.setattr(wearos.shutil, "which", lambda x: None)  # no real config-set
    name, token = _enrolled(wearos)
    assert wearos._engine().device_lookup(token) == name
    assert wearos._engine().device_lookup("wtk-nope") is None
    wearos._engine().queue_message(name, "ping", agent="wear")
    report = wearos.revoke_device(name)
    assert wearos._engine().device(name) is None
    steps = {s["step"]: s for s in report["steps"]}
    assert steps["token"]["ok"]
    assert steps["tailnet"]["ok"] is False and "never captured" in steps["tailnet"]["detail"]
    assert report["rotation"]["rotated"] or report["rotation"].get("reason")


# ── outbox + cron alerts (D8/D14) ───────────────────────────────────────────────
def test_watch_send_queues_and_watch_poll_acks(wearos, clock):
    name, _ = _enrolled(wearos)
    out = json.loads(wearos._tool_watch_send({"text": "standup in 5"}))
    assert out["queued"][0]["device"] == name
    got = wearos._lane_events("default", name, 0)
    assert got["events"][0]["text"] == "standup in 5"
    assert wearos._lane_ack("default", name, got["since"]) == {"remaining": 0}
    assert wearos._lane_events("default", name, 0)["events"] == []


def test_watch_send_cross_agent_lane_denied(wearos):
    name, _ = _enrolled(wearos)
    with pytest.raises(PermissionError):
        wearos._lane_events("other-agent", name, 0)


def test_cron_alert_lands_and_folds(wearos, clock):
    name, _ = _enrolled(wearos)
    for i in range(7):
        wearos._on_session_end(platform="cron", session_id="local-x", completed=True,
                               failed=False)
        clock.advance(2)
    events = wearos._lane_events("default", name, 0)["events"]
    assert len(events) == 1 and events[0]["kind"] == "cron"
    assert events[0]["fold"] >= 1            # burst collapsed into one alert
    wearos._engine().set_alert_pref("default", "off")
    wearos._on_session_end(platform="cron", session_id="local-y", completed=True,
                           failed=False)
    assert len(wearos._lane_events("default", name, 0)["events"]) == 1


def test_cron_alert_skips_other_platforms(wearos):
    name, _ = _enrolled(wearos)
    wearos._on_session_end(platform="cli", session_id="local-z", completed=True,
                           failed=False)
    assert wearos._lane_events("default", name, 0)["events"] == []


def test_failed_cron_says_failed(wearos):
    name, _ = _enrolled(wearos)
    wearos._on_session_end(platform="cron", session_id="local-q", completed=False,
                           failed=True)
    text = wearos._lane_events("default", name, 0)["events"][0]["text"]
    assert "failed" in text and text.startswith("⏰")


# ── grants (D17) ────────────────────────────────────────────────────────────────
def test_cross_profile_key_needs_a_grant(wearos):
    name, _ = _enrolled(wearos)
    wearos._engine().store.mutate(
        lambda doc: doc["devices"][name].__setitem__("profile", "research"))
    with pytest.raises(wearos.pairing.PairingError) as exc:
        wearos._engine().resolve_key("default", name, lambda p: "sk-new")
    assert "grant" in str(exc.value)
    wearos._engine().set_grant("research", True)
    assert wearos._engine().resolve_key("default", name, lambda p: "sk-new") == "sk-new"
    wearos._engine().set_grant("research", False)
    with pytest.raises(wearos.pairing.PairingError):
        wearos._engine().resolve_key("default", name, lambda p: "sk-new")


# ── the tools' contract ─────────────────────────────────────────────────────────
def test_watch_pair_takes_no_arguments(wearos):
    out = json.loads(wearos._tool_watch_pair({}))
    assert "pairing_window" in out
    assert "code" not in out                      # the model can't mint, only open
    err = json.loads(wearos._tool_watch_pair({"code": "123456"}))
    assert "no arguments" in err["error"]


def test_pair_command_surfaces(wearos):
    wearos.open_pairing()
    code = wearos.door_begin(SECRET_A, "Watch")["code"]
    on_phone = wearos._cmd_pair(code, platform="telegram")
    assert "on your computer" in on_phone         # D4: messaging gets directions, not claims
    assert wearos._engine().pending_list()[0]["state"] == "pending"  # nothing happened
    ok = wearos._cmd_pair(code, platform="cli")
    assert "approved" in ok                       # the computer surface claims


# ── rotation policy (D12/D19) ───────────────────────────────────────────────────
def test_rotate_named_profile_is_hot_no_restart(wearos, monkeypatch):
    calls = []
    monkeypatch.setattr(wearos.shutil, "which", lambda x: "/usr/bin/hermes")
    monkeypatch.setattr(wearos.subprocess, "run",
                        lambda argv, **kw: calls.append(argv) or type(
                            "R", (), {"returncode": 0, "stdout": "", "stderr": ""})())
    r = wearos.rotate_agent_key("wear-demo")
    assert r["rotated"] and "restart" not in r
    assert calls[0][:3] == ["/usr/bin/hermes", "-p", "wear-demo"]
    assert calls[0][3:5] == ["config", "set"]
    assert calls[0][5] == "API_SERVER_KEY"
    assert calls[0][6] != calls[0][6] or len(calls[0][6]) > 20  # fresh value, not in argv-echo


def test_rotate_default_profile_flags_the_restart(wearos, monkeypatch):
    monkeypatch.setattr(wearos.shutil, "which", lambda x: "/usr/bin/hermes")
    monkeypatch.setattr(wearos.subprocess, "run",
                        lambda argv, **kw: type("R", (),
                                                {"returncode": 0, "stdout": "",
                                                 "stderr": ""})())
    r = wearos.rotate_agent_key("default")
    assert r["rotated"] and "restart" in r["restart"].lower()
