"""Funnel-attribute detection against canned ACL payloads (no network)."""

from __future__ import annotations

import json

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
