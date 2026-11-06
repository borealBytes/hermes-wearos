"""Tailscale Management API client — stdlib only (rule 10: zero pip deps).

Endpoints verified against the live API by the Friday probe (2026-11-05, results folded
back from the handoff's open-questions list):
  OAuth   POST /api/v2/oauth/token            grant_type=client_credentials
  Devices GET  /api/v2/tailnet/-/devices      credential validation (any readable tailnet)
  Keys    POST /api/v2/tailnet/{t}/keys       one-use, pre-authorized, tagged enrollment keys
            POST .../keys/{id}/expire         always expire what you mint
  DNS     GET  /api/v2/tailnet/{t}/dns/preferences   MagicDNS truth for the wizard
  ACL     GET/PATCH /api/v2/tailnet/{t}/acl   funnel node-attr detection + preview→approve patch

Rule 11: the credential arrives ONLY through config_schema secrets read via
agent.secret_scope.get_secret (see __init__._get_secret) — this module never touches
os.environ or .env files itself.
"""

from __future__ import annotations

import base64
import json
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Optional

API_BASE = "https://api.tailscale.com/api/v2"
USER_AGENT = "hermes-wearkit/0.1 (Hermes Wear Kit plugin)"


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
                 auth: bool = True) -> bytes:
        data = raw_body if payload is None else json.dumps(payload).encode()
        req = urllib.request.Request(API_BASE + path, data=data or None, method=method)
        req.add_header("User-Agent", USER_AGENT)
        if data:
            req.add_header("Content-Type", content_type)
        if auth:
            req.add_header("Authorization", "Bearer " + self._token())
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

    def acl_text(self) -> str:
        return self._request("GET", f"/tailnet/{self._tn()}/acl").decode(errors="replace")

    def patch_acl(self, text: str) -> Any:
        """Preview→approve discipline lives in the caller; we only ever PATCH the full
        policy text back (measured semantics: full-text PATCH, JSON ACLs only)."""
        return json.loads(self._request("PATCH", f"/tailnet/{self._tn()}/acl", raw_body=text.encode()))

    def funnel_attr_present(self) -> tuple[bool, str]:
        """Funnel needs a `funnel` node attribute in the ACL (KB 1223). We DETECT here;
        the fix verb (PATCH or printed console diff) is the wizard's call."""
        try:
            text = self.acl_text()
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
            if isinstance(what, dict) and "funnel" in (what.get("attr") or []):
                return True, "funnel attribute present on at least one node"
        return False, "ACL lacks a funnel node attribute (hermes watch setup --fix funnel)"

    def add_funnel_attr(self, tag: str) -> Any:
        """Minimal funnel grant: tag := funnel:<tag> on the tag set (preview→approve by
        the caller). PATCH conflict (etag) → TailnetAPIError with the console link."""
        try:
            acl = json.loads(self.acl_text())
        except (ValueError, TailnetAPIError) as exc:
            raise TailnetAPIError(0, f"cannot PATCH a non-JSON/default ACL ({exc}); use the console") from exc
        attrs = acl.setdefault("nodeAttrs", {})
        key = f"hermes-wearkit-funnel-{abs(hash(tag)) % 10**6:06d}"
        attrs[key] = {"attr": ["funnel:" + tag.split(":")[-1]], "hosts": [tag]}
        return self.patch_acl(json.dumps(acl, indent=2))

    # -- enrollment keys (the pairing heart) ------------------------------------
    def mint_enrollment_key(self, tag: str, expiry: str = "24h") -> str:
        """Single-use (not reusable), pre-authorized (no browser on the watch), tagged
        (ACL hygiene), bounded expiry. Ephemeral stays OFF on purpose: a reinstalled app
        must be able to re-enroll with the saved identity (SPEC §11.4)."""
        key_tag = tag.split(":")[-1]
        body = {
            "capabilities": {
                "devices": {
                    "create": {
                        "reusable": False,
                        "ephemeral": False,
                        "preAuthorized": True,
                        "tags": [key_tag],
                        "deviceExpiry": "_key_expiry_from_option",
                    }
                },
                "expiration": {"expireAfter": expiry},
            }
        }
        data = json.loads(self._request("POST", f"/tailnet/{self._tn()}/keys", payload=body))
        return data["secret"]

    def expire_key(self, key_id: str) -> Any:
        return self._request("POST", f"/tailnet/{self._tn()}/keys/{urllib.parse.quote(key_id)}/expire")


import urllib.parse  # noqa: E402  (used by _tn/expire paths; kept adjacent to use sites)
