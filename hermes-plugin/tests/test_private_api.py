"""The private lane over real HTTP with a FAKE whois (the whole point: identity comes
from the tailnet daemon, not from the watch). Covers D8/D12/D13/D17 surfaces."""

from __future__ import annotations

import json
import urllib.error
import urllib.request

import pytest

from conftest import FAKE_AUTHKEY, SECRET_A

NODE = {"node_id": "n-watch", "node_name": "watch.example.ts.net",
        "tags": ["tag:hermes-watch"], "profile": "me@example"}


class FakeWhois:
    def __init__(self):
        self.answer = dict(NODE)

    def __call__(self, peer_ip):
        return dict(self.answer) if self.answer else None


@pytest.fixture
def lane(wearos, monkeypatch):
    """Full ceremony done (begin → approve → ack), then the private lane is up with
    the plugin's real backend over the fake whois. api_key resolves through the
    declared-secret route (env API_SERVER_KEY stands in for the profile .env)."""
    monkeypatch.setenv("API_SERVER_KEY", "sk-hermes-profilekey-A")
    monkeypatch.setenv("watchkit_token", "wk-helper-token")
    wearos.open_pairing()
    code = wearos.door_begin(SECRET_A, "Galaxy Watch Ultra")["code"]
    wearos.approve_pair(code)
    ack = wearos.door_ack(SECRET_A)
    whois = FakeWhois()
    backend = wearos.private_api.PrivateBackend(
        whois, wearos._lane_session, wearos._lane_events, wearos._lane_ack,
        lambda p, d: None)
    wearos.private_api.start_server("127.0.0.1", 0, backend, wearos._lane_lookup)
    yield {"base": f"http://127.0.0.1:{wearos.private_api.actual_port()}",
           "device": ack["device"]["hostname"], "token": ack["token"],
           "whois": whois, "module": wearos}
    wearos.private_api.stop_server()


def http(base, method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        return exc.code, (json.loads(raw) if raw else None)


def test_anonymous_is_401(lane):
    assert http(lane["base"], "POST", "/watch/default/session", {})[0] == 401
    assert http(lane["base"], "GET", "/watch/default/events")[0] == 401
    assert http(lane["base"], "GET", "/watch/default/events", token="wtk-nope")[0] == 401


def test_session_delivers_credentials_over_the_lane(lane):
    status, body = http(lane["base"], "POST", "/watch/default/session",
                        {"v": 2, "secret": SECRET_A}, token=lane["token"])
    assert status == 200
    assert body["api_key"] == "sk-hermes-profilekey-A"        # D13: private lane only
    assert body["watchkit_token"] == "wk-helper-token"
    assert body["gateway_fqdn"] == "gw.example.ts.net"
    assert body["gateway_port"] == 8642 and body["watchkit_port"] == 42651
    dev = lane["module"]._engine().device(lane["device"])
    assert dev["node_id"] == "n-watch"                        # identity captured


def test_first_session_without_the_secret_is_refused(lane):
    status, body = http(lane["base"], "POST", "/watch/default/session", {},
                        token=lane["token"])
    assert status == 403 and "pairing secret" in body["error"]


def test_a_non_tailnet_peer_is_refused(lane):
    lane["whois"].answer = None
    status, body = http(lane["base"], "POST", "/watch/default/session",
                        {"secret": SECRET_A}, token=lane["token"])
    assert status == 403 and "tailnet" in body["error"]


def test_node_identity_is_law_after_binding(lane):
    http(lane["base"], "POST", "/watch/default/session", {"secret": SECRET_A},
         token=lane["token"])
    lane["whois"].answer = {**NODE, "node_id": "n-squatter"}
    status, body = http(lane["base"], "POST", "/watch/default/session", {},
                        token=lane["token"])
    assert status == 403 and "different tailnet node" in body["error"]


def test_wrong_agent_lane_is_refused(lane):
    status, body = http(lane["base"], "POST", "/watch/lightshow/session",
                        {"secret": SECRET_A}, token=lane["token"])
    assert status == 403 and "paired to agent" in body["error"]


def test_events_round_trip(lane):
    m, name = lane["module"], lane["device"]
    m._tool_watch_send({"text": "water the ferns"})
    status, body = http(lane["base"], "GET", "/watch/default/events?since=0",
                        token=lane["token"])
    assert status == 200
    assert [e["text"] for e in body["events"]] == ["water the ferns"]
    assert body["since"] == body["events"][-1]["id"]
    assert http(lane["base"], "POST", "/watch/default/ack",
                {"up_to": body["since"]}, token=lane["token"])[1] == {"remaining": 0}
    status, body = http(lane["base"], "GET", "/watch/default/events?since=0",
                        token=lane["token"])
    assert body["events"] == []


def test_unknown_routes_are_404(lane):
    assert http(lane["base"], "GET", "/watch/default/bogus", token=lane["token"])[0] == 404
    assert http(lane["base"], "POST", "/pair/begin", {}, token=lane["token"])[0] == 404
    assert http(lane["base"], "GET", "/nope", token=lane["token"])[0] == 404


def test_cross_agent_key_needs_grant_then_works(lane, monkeypatch, tmp_path):
    m, name = lane["module"], lane["device"]
    m._engine().store.mutate(lambda doc: doc["devices"][name].__setitem__(
        "profile", "research"))
    (tmp_path / "profiles" / "research").mkdir(parents=True)
    (tmp_path / "profiles" / "research" / ".env").write_text(
        "API_SERVER_KEY=sk-research-key\n", encoding="utf-8")
    # first: no grant → refused (D17: no silent cross-profile reads)
    status, body = http(lane["base"], "POST", "/watch/research/session",
                        {"secret": SECRET_A}, token=lane["token"])
    assert status == 403 and "grant" in body["error"]
    m._engine().set_grant("research", True)
    status, body = http(lane["base"], "POST", "/watch/research/session", {},
                        token=lane["token"])
    assert status == 200 and body["api_key"] == "sk-research-key"
