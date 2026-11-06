"""Tailscale Management API client — stdlib only (rule 10: zero pip deps).

Endpoints verified LIVE against the API surface and the official client
`tailscale/tailscale-client-go-v2` (probe 2026-11-06, /tmp/opencode/tsprobe/REPORT.md):
  OAuth   POST /api/v2/oauth/token            grant_type=client_credentials
  Devices GET  /api/v2/tailnet/-/devices      credential validation (any readable tailnet)
  Keys    POST /api/v2/tailnet/{t}/keys       one-use, pre-authorized, tagged enrollment keys
            body: capabilities.devices.create{reusable:false, ephemeral:false,
                  preauthorized:true, tags:["tag:x"]} + TOP-LEVEL expirySeconds
            response: secret field is "key", id field is "id" (one-time keys spend on
            enrollment; last_key_id lets us expire an UN-spent key after a lost claim)
            POST .../keys/{id}/expire         always expire what you never used
  DNS     GET  /api/v2/tailnet/{t}/dns/preferences   MagicDNS truth for the wizard
  Funnel  GET  /api/v2/tailnet/{t}/prefs/funnel      funnelNodeAttr:
            enabled | attr_required | disabled  (the tailnet-level toggle — a separate
            red item from the ACL attr; on borealbytes.github it measured DISABLED)
  ACL     GET  /api/v2/tailnet/{t}/acl        JSON envelope {"acl": text, "etag": ...}
            (raw body also tolerated for canned/test payloads)
        POST /api/v2/tailnet/{t}/acl          FULL-FILE REPLACEMENT — there is no PATCH —
            with If-Match: "<etag>" so a concurrent console edit conflicts instead of
            being silently overwritten (measured via client source, policyfile.go)

Rule 11: the credential arrives ONLY through config_schema secrets read via
agent.secret_scope.get_secret (see __init__._get_secret) — this module never touches
os.environ or .env files itself.
"""

from __future__ import annotations

import base64
import hashlib
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, Optional

API_BASE = "https://api.tailscale.com/api/v2"
USER_AGENT = "hermes-wearkit/0.1 (Hermes Wear Kit plugin)"


def funnel_rule_key(tag: str) -> str:
    """Stable nodeAttrs rule name for our grant (sha256, NOT process-random hash())."""
    return f"hermes-wearkit-funnel-{hashlib.sha256(tag.encode()).hexdigest()[:6]}"


class TailnetAPIError(RuntimeError):
    """API call failed; message embeds status + short body for one-glance wizard copy."""

    def __init__(self, status: int, body: str) -> None:
        super().__init__(f"tailscale API {status}: {body[:300]}")
        self.status = status


class CredentialMissing(TailnetAPIError):
    def __init__(self, msg: str) -> None:
        RuntimeError.__init__(self, msg)
        self.status = 0


@dataclass(frozen=True)
class Credential:
    """Either a personal API token or an OAuth client (preferred: tag-restricted)."""

    api_token: str = ""
    client_id: str = ""
    client_secret: str = ""

    @property
    def is_oauth(self) -> bool:
        return bool(self.client_id and self.client_secret)


class TailnetClient:
    def __init__(self, cred: Credential, tailnet: str = "-", timeout: float = 15.0) -> None:
        if not cred.is_oauth and not cred.api_token:
            raise CredentialMissing("empty credential")
        self.cred = cred
        self.tailnet = tailnet or "-"
        self.timeout = timeout
        self._oauth_token: Optional[str] = None
        self.last_key_id: str = ""

    # -- plumbing -------------------------------------------------------------
    def describe_credential(self) -> str:
        return (
            "oauth client" if self.cred.is_oauth else "api token"
        ) + f" · tailnet {self.tailnet}"

    def _token(self) -> str:
        if not self.cred.is_oauth:
            return self.cred.api_token
        body = self._request(
            "POST", "/oauth/token", raw_body=b"grant_type=client_credentials",
            content_type="application/x-www-form-urlencoded", auth=False,
        )
        self._oauth_token = json.loads(body)["access_token"]
        return self._oauth_token

    def _request(self, method: str, path: str, payload: Any = None, *,
                 raw_body: bytes = b"", content_type: str = "application/json",
                 auth: bool = True, if_match: Optional[str] = None,
                 accept: Optional[str] = None) -> bytes:
        data = raw_body if payload is None else json.dumps(payload).encode()
        req = urllib.request.Request(API_BASE + path, data=data or None, method=method)
        req.add_header("User-Agent", USER_AGENT)
        if data:
            req.add_header("Content-Type", content_type)
        if auth:
            req.add_header("Authorization", "Bearer " + self._token())
        if accept:
            req.add_header("Accept", accept)
        if if_match:
            req.add_header("If-Match", json.dumps(if_match))  # quoted, per client-go policyfile.go
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return resp.read()
        except urllib.error.HTTPError as exc:
            raise TailnetAPIError(exc.code, exc.read().decode(errors="replace")) from exc
        except OSError as exc:
            raise TailnetAPIError(0, f"network: {exc}") from exc

    def _get(self, path: str) -> Any:
        return json.loads(self._request("GET", path))

    def _tn(self) -> str:
        # "-" means "the token's own tailnet": resolve it so later calls can use it.
        if self.tailnet == "-":
            raise TailnetAPIError(0, "tailnet handle not configured (config_schema: tailnet)")
        return urllib.parse.quote(self.tailnet, safe="")

    # -- probes the wizard runs -------------------------------------------------
    def validate(self) -> dict:
        """GET /tailnet/-/devices — 200 proves the credential AND discovers the tailnet."""
        data = self._get("/tailnet/-/devices")
        if self.tailnet == "-":
            self.tailnet = data.get("tailnet", "-")
        return data

    def dns_preferences(self) -> dict:
        return self._get(f"/tailnet/{self._tn()}/dns/preferences")

    def funnel_pref(self) -> dict:
        """Tailnet-level funnel toggle (separate from the ACL attr): the CLI on
        borealbytes.github measured DISABLED — so the wizard shows TWO funnel lights."""
        return self._get(f"/tailnet/{self._tn()}/prefs/funnel")

    def acl_full(self) -> tuple[str, Optional[str]]:
        """(policy text, etag). Tolerates both the JSON envelope {"acl","etag"} the API
        returns and a raw policy body (canned test payloads, hujson tailnets). We ASK
        for JSON: the console's JSON-editor tab proves the server can render a
        comment-free view of a commented HuJSON file (measured: owner's policy file is
        the legacy commented src/dst/ip form — raw GET would hand us HuJSON)."""
        body = self._request("GET", f"/tailnet/{self._tn()}/acl",
                             accept="application/json, text/plain;q=0.9").decode(errors="replace")
        try:
            parsed = json.loads(body)
        except ValueError:
            return body, None
        if isinstance(parsed, dict) and isinstance(parsed.get("acl"), str):
            etag = parsed.get("etag")
            return parsed["acl"], str(etag) if etag else None
        return body, None

    def acl_text(self) -> str:
        return self.acl_full()[0]

    def patch_acl(self, text: str, etag: Optional[str] = None) -> Any:
        """Full-file REPLACEMENT (measured: the verb is POST, there is no PATCH). The
        preview→approve discipline lives in the caller; passing the etag from acl_full()
        makes a concurrent console edit a conflict, not a silent overwrite."""
        return json.loads(self._request("POST", f"/tailnet/{self._tn()}/acl",
                                        raw_body=text.encode(), if_match=etag))

    def funnel_attr_present(self) -> tuple[bool, str]:
        """Funnel needs BOTH the tailnet toggle (funnel_pref) and a `funnel` node
        attribute in the ACL (KB 1223). We DETECT here; the fix verb (full-file replace
        behind preview→approve, or a printed console diff) is the wizard's call."""
        try:
            text, _ = self.acl_full()
        except TailnetAPIError as exc:
            if exc.status == 404:  # tailnet runs default policy — no ACL file at all
                return False, "no ACL file; default policy has no funnel attribute"
            raise
        try:
            acl = json.loads(text)
        except ValueError:
            return False, "huJSON ACLs not supported by this plugin; use the console diff"
        attrs = (acl.get("nodeAttrs") or {})
        for what in attrs.values():
            if isinstance(what, dict) and any(
                a == "funnel" or str(a).startswith("funnel:")  # tolerate both grant spellings
                for a in (what.get("attr") or [])
            ):
                return True, "funnel attribute present on at least one node"
        return False, "ACL lacks a funnel node attribute (hermes watch setup --fix funnel)"

    def add_funnel_attr(self, tag: str) -> Any:
        """Minimal grant: attr ["funnel"] (bare — the tagged nodes may funnel) scoped to
        hosts [tag]. Conflicts (stale etag) surface as TailnetAPIError; the wizard then
        prints the console diff instead of guessing."""
        try:
            text, etag = self.acl_full()
            acl = json.loads(text)
        except (ValueError, TailnetAPIError) as exc:
            raise TailnetAPIError(0, f"cannot replace a non-JSON/default ACL ({exc}); use the console") from exc
        if not isinstance(acl, dict):
            raise TailnetAPIError(0, "ACL policy is not a JSON object; use the console")
        attrs = acl.setdefault("nodeAttrs", {})
        attrs.setdefault(funnel_rule_key(tag), {"attr": ["funnel"], "hosts": [tag]})
        return self.patch_acl(json.dumps(acl, indent=2), etag)

    # -- enrollment keys (the pairing heart) ------------------------------------
    _EXPIRY_RE = re.compile(r"^(\d+)\s*([hd])$", re.IGNORECASE)

    def _expiry_seconds(self, expiry: str) -> int:
        """config_schema key_expiry "24h"/"3d" → seconds, clamped to the API window."""
        m = self._EXPIRY_RE.match((expiry or "").strip())
        secs = int(m.group(1)) * (3600 if m.group(2).lower() == "h" else 86400) if m else 86400
        return max(3600, min(secs, 90 * 86400))

    def mint_enrollment_key(self, tag: str, expiry: str = "24h") -> str:
        """Single-use (not reusable), pre-authorized (no browser on the watch — and a
        harmless no-op on approval-off tailnets, measured), tagged (ACL hygiene + the
        tagged-device expiry exemption), bounded expiry. Ephemeral stays OFF on purpose:
        a reinstalled app must be able to re-enroll with the saved identity (SPEC §11.4).
        Body/field shapes measured 2026-11-06 against tailscale-client-go-v2 keys.go —
        lowercase `preauthorized`, TOP-LEVEL `expirySeconds`, secret in "key"."""
        body = {
            "capabilities": {
                "devices": {
                    "create": {
                        "reusable": False,
                        "ephemeral": False,
                        "preauthorized": True,
                        "tags": [tag if tag.startswith("tag:") else "tag:" + tag],
                    }
                }
            },
            "expirySeconds": self._expiry_seconds(expiry),
            "description": "hermes-wearkit pairing (single-use)",
        }
        data = json.loads(self._request("POST", f"/tailnet/{self._tn()}/keys", payload=body))
        self.last_key_id = data.get("id") or ""  # expire an un-spent key after a lost claim
        return data["key"]

    def expire_key(self, key_id: str) -> Any:
        return self._request("POST", f"/tailnet/{self._tn()}/keys/{urllib.parse.quote(key_id)}/expire")
