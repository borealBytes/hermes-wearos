"""Funnel-attribute detection against canned ACL payloads (no network)."""

from __future__ import annotations

import json

import pytest

from conftest import load_plugin

tailscale = load_plugin().tailscale

ACL_WITH_FUNNEL = json.dumps({
    "grants": [{"src": ["autogroup:member"], "dst": ["autogroup:self:*"]}],
    "nodeAttrs": {
        "hermes-funnel": {"attr": ["funnel:hermes-watch"], "hosts": ["tag:hermes-watch"]},
        "tagmap": {"attr": ["tag:research"], "hosts": ["group:research"]},
    },
}).encode()

ACL_BARE_FUNNEL = json.dumps({
    "nodeAttrs": {"any": {"attr": ["funnel"], "hosts": ["tag:hermes-watch"]}},
}).encode()

ACL_NO_FUNNEL = json.dumps({
    "nodeAttrs": {"tagmap": {"attr": ["tag:research"], "hosts": ["group:research"]}},
}).encode()

ACL_EMPTY = b'{"grants": []}'

# no nodeAttrs at all — the shape a default-policy tailnet GETs as (array-write applies)
ACL_ARRAY_WRITE_TARGET = b'{"grants": [{"src": ["autogroup:member"], "dst": ["*:*"]}], "ssh": {}}'

HUJSON = b'// tailnet policy file, huJSON edition\n"grants": [\n  // keep editing by hand\n]\n'


def client_returning(payload: bytes | None = None, raise_status: int | None = None,
                     posted: list | None = None):
    client = tailscale.TailnetClient(tailscale.Credential(api_token="tskey-api-test"),
                                     tailnet="example.ts.example")

    def fake_request(method, path, payload_=None, *, raw_body=b"", content_type="application/json",
                      auth=True, if_match=None, accept=None, want_headers=False):
        if raise_status is not None:
            raise tailscale.TailnetAPIError(raise_status, "no such file")
        if method == "POST":  # the ACL replacement — capture for assertions
            if posted is not None:
                posted.append((raw_body, if_match))
            return b'{"changing": true}'
        return (payload, {"ETag": '"etag-test"'}) if want_headers else payload

    client._request = fake_request
    return client


def test_funnel_attr_present_is_detected():
    ok, detail = client_returning(ACL_WITH_FUNNEL).funnel_attr_present()
    assert ok is True and "funnel" in detail


def test_delete_device_resolves_numeric_node_ref():
    # Measured live 2026-11-09: a real-watch revoke captured the engine-self-reported
    # NUMERIC nodeId at bootstrap, but DELETE devices/{id} is keyed by the object id —
    # the numeric call 404s ("404 page not found"), the resolver finds the device by
    # nodeId, and the retry deletes the object id.
    client = tailscale.TailnetClient(tailscale.Credential(api_token="tskey-api-test"),
                                     tailnet="example.ts.example")
    calls: list = []
    devices = {"devices": [
        {"id": "nRealId12345CNTRL", "nodeId": 6528413509656018, "name": "wear-demo-watch-2"},
    ]}

    def fake_request(method, path, payload_=None, *, raw_body=b"", content_type="application/json",
                     auth=True, if_match=None, accept=None, want_headers=False):
        calls.append((method, path))
        if path.endswith("/devices/6528413509656018"):
            raise tailscale.TailnetAPIError(404, "404 page not found")
        return b'{"deleting": true}'

    client._request = fake_request
    client._get = lambda path: (calls.append(("GETLIST", path)), devices)[1]

    out = client.delete_device("6528413509656018")
    assert out == {"deleted": "nRealId12345CNTRL", "resolved_from": "6528413509656018"}
    assert calls[-1] == ("DELETE", "/tailnet/example.ts.example/devices/nRealId12345CNTRL")


def test_delete_device_object_id_skips_resolver():
    client = tailscale.TailnetClient(tailscale.Credential(api_token="tskey-api-test"),
                                     tailnet="example.ts.example")
    calls: list = []

    def fake_request(method, path, payload_=None, *, raw_body=b"", content_type="application/json",
                     auth=True, if_match=None, accept=None, want_headers=False):
        calls.append((method, path))
        return b'{"deleting": true}'

    client._request = fake_request
    client._get = lambda path: pytest.fail("resolver must not run when the id matches")
    assert client.delete_device("nDirectId999CNTRL") == {"deleted": "nDirectId999CNTRL"}


def test_delete_device_unknown_ref_keeps_the_404():
    client = tailscale.TailnetClient(tailscale.Credential(api_token="tskey-api-test"),
                                     tailnet="example.ts.example")

    def fake_request(method, path, payload_=None, *, raw_body=b"", content_type="application/json",
                     auth=True, if_match=None, accept=None, want_headers=False):
        raise tailscale.TailnetAPIError(404, "404 page not found")

    client._request = fake_request
    client._get = lambda path: {"devices": [{"id": "nOther", "nodeId": 1, "name": "elsewhere"}]}
    with pytest.raises(tailscale.TailnetAPIError):
        client.delete_device("999999999")


def test_bare_funnel_attr_is_detected():
    ok, _ = client_returning(ACL_BARE_FUNNEL).funnel_attr_present()
    assert ok is True


def test_acl_without_funnel_attr_is_cleanly_absent():
    ok, detail = client_returning(ACL_NO_FUNNEL).funnel_attr_present()
    assert ok is False and "lacks a funnel node attribute" in detail


def test_acl_without_nodeattrs_at_all():
    ok, detail = client_returning(ACL_EMPTY).funnel_attr_present()
    assert ok is False and "lacks a funnel" in detail


def test_default_policy_404_is_cleanly_absent():
    ok, detail = client_returning(raise_status=404).funnel_attr_present()
    assert ok is False and "no ACL file" in detail


def test_hujson_degrades_to_false_with_a_reason_and_never_raises():
    ok, detail = client_returning(HUJSON).funnel_attr_present()
    assert ok is False and "huJSON" in detail and "console" in detail


def test_add_funnel_attr_patches_json_only():
    """huJSON / no-ACL tails must refuse the PATCH loudly, never half-write."""
    for broken in (client_returning(HUJSON), client_returning(raise_status=404)):
        try:
            broken.add_funnel_attr("tag:hermes-watch")
        except tailscale.TailnetAPIError as exc:
            assert "console" in str(exc)
        else:
            raise AssertionError("add_funnel_attr must raise on non-JSON/default ACLs")


def test_add_funnel_attr_defines_tag_and_funnel_grant():
    """Measured 2026-11-06: mint 400s unless the tag EXISTS in the policy; nodeAttrs
    must be the grants-ARRAY {src,hosts,prop} (legacy-map rejected on write). Apply
    writes both, as a full-file POST with the GET's etag in If-Match."""
    posted: list = []
    client = client_returning(ACL_ARRAY_WRITE_TARGET, posted=posted)
    client.add_funnel_attr("tag:hermes-watch")
    assert len(posted) == 1
    body, if_match = posted[0]
    assert if_match == "etag-test"  # etag from the GET header, threaded to the replace
    acl = json.loads(body)
    assert acl["tagOwners"]["tag:hermes-watch"] == ["autogroup:member"]  # legacy-map form (measured 400)
    assert {"target": ["tag:hermes-watch"], "attr": ["funnel"]} in acl["nodeAttrs"]


def test_add_funnel_attr_is_idempotent():
    """Second apply must not duplicate the grant (re-running the wizard is safe).
    ACL_WITH_FUNNEL carries the legacy-map spelling AND a non-funnel attr."""
    posted: list = []
    client_returning(ACL_ARRAY_WRITE_TARGET, posted=posted).add_funnel_attr("tag:hermes-watch")
    first = json.loads(posted[0][0])
    posted.clear()
    client = client_returning(json.dumps(first).encode(), posted=posted)
    client.add_funnel_attr("tag:hermes-watch")  # already granted array-form now
    second = json.loads(posted[0][0])
    assert len(second["nodeAttrs"]) == len(first["nodeAttrs"])


def test_funnel_attr_detected_in_grants_array_spelling():
    """A GET after our write returns array form — detection must follow (measured)."""
    acl = json.dumps({"grants": [], "nodeAttrs": [
        {"src": ["autogroup:member"], "hosts": ["tag:hermes-watch"], "prop": ["funnel"]}]})
    ok, _ = client_returning(acl.encode()).funnel_attr_present()
    assert ok is True


def test_legacy_map_nodeattrs_refused_on_write():
    """A tailnet still on the legacy MAP nodeAttrs gets a clear 'convert in console'
    error instead of a server 400 (measured shape split)."""
    try:
        client_returning(ACL_NO_FUNNEL).add_funnel_attr("tag:hermes-watch")
    except tailscale.TailnetAPIError as exc:
        assert "console" in str(exc)
    else:
        raise AssertionError("legacy-map nodeAttrs must be refused")


# ── review fix v0.2: the funnel grant belongs on the GATEWAY node, not the tag ──
ACL_GATEWAY_FUNNEL = json.dumps({"grants": [], "nodeAttrs": [
    {"src": ["autogroup:member"], "hosts": ["100.64.0.9"], "prop": ["funnel"]},
    {"src": ["autogroup:member"], "hosts": ["tag:hermes-watch"], "prop": ["tagread"]}]})


def test_want_target_only_passes_when_the_GATEWAY_has_the_attr():
    ok, detail = client_returning(ACL_GATEWAY_FUNNEL.encode()
                                  ).funnel_attr_present("100.64.0.9")
    assert ok is True and "this gateway" in detail
    ok, detail = client_returning(ACL_GATEWAY_FUNNEL.encode()
                                  ).funnel_attr_present("100.64.9.9")
    assert ok is False and "100.64.9.9" in detail


def test_tag_only_funnel_does_not_satisfy_a_gateway_target_check():
    """v0.1 shipped funnel-on-the-watch-tag: detection must now FAIL that shape when
    asked "does this gateway node have funnel" — the exact review point."""
    ok, detail = client_returning(ACL_BARE_FUNNEL).funnel_attr_present("100.64.0.9")
    assert ok is False and "100.64.0.9" in detail


def test_add_funnel_attr_targets_the_gateway_ip_also_defining_the_tag():
    """The wizard grants the gateway IP but MUST also keep the watch tag defined, or
    enrollment mints start 400ing (measured lesson stays)."""
    posted: list = []
    client_returning(ACL_ARRAY_WRITE_TARGET, posted=posted).add_funnel_attr(
        "100.64.0.9", also_tag="tag:hermes-watch")
    acl = json.loads(posted[0][0])
    assert {"target": ["100.64.0.9"], "attr": ["funnel"]} in acl["nodeAttrs"]
    assert acl["tagOwners"]["tag:hermes-watch"] == ["autogroup:member"]
