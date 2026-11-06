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

HUJSON = b'// tailnet policy file, huJSON edition\n"grants": [\n  // keep editing by hand\n]\n'


def client_returning(payload: bytes | None = None, raise_status: int | None = None):
    client = tailscale.TailnetClient(tailscale.Credential(api_token="tskey-api-test"),
                                     tailnet="example.ts.example")

    def fake_request(method, path, payload_=None, *, raw_body=b"", content_type="application/json",
                      auth=True, if_match=None, accept=None):
        if raise_status is not None:
            raise tailscale.TailnetAPIError(raise_status, "no such file")
        return payload

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
