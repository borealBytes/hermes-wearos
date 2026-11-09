"""Tailscale Management API client — stdlib only (rule 10: zero pip deps).

Endpoints verified LIVE against the API surface and the official client
`tailscale/tailscale-client-go-v2` (probe 2026-11-06, /tmp/opencode/tsprobe/REPORT.md):
  OAuth   POST /api/v2/oauth/token            grant_type=client_credentials
  Devices GET  /api/v2/tailnet/-/devices      credential validation (any readable tailnet)
  Keys    POST /api/v2/tailnet/{t}/keys       one-use, pre-authorized, tagged enrollment keys
            body: capabilities.devices.create{reusable:false, ephemeral:false,
                  preauthorized:true, tags:["tag:x"]} + TOP-LEVEL expirySeconds
            (description: plain words only — parentheses 400)
            response: secret field is "key", id field is "id" (one-time keys spend on
            enrollment; last_key_id lets us remove an UN-spent key after a lost claim)
            DELETE .../keys/{id}               remove/expire a key (the .../expire route 404s)
  DNS     GET  /api/v2/tailnet/{t}/dns/preferences   MagicDNS truth for the wizard
  Funnel  (no API endpoint exists — measured 2026-11-06: /prefs/funnel and friends all
            404; the tailnet-level toggle is CONSOLE-ONLY, Settings ▸ General ▸ Funnel.
            The wizard learns its truth empirically: --fix serve RUNS `tailscale serve
            --funnel …` and parses the CLI's "Funnel is not enabled" error + link.)
  ACL     GET  /api/v2/tailnet/{t}/acl        policy text + ETag (the etag is the HTTP
            ETag header, not a body field — measured; Accept: application/json renders
            even a commented legacy file as clean {"grants","ssh"} JSON)
        POST /api/v2/tailnet/{t}/acl          FULL-FILE REPLACEMENT — there is no PATCH —
            with If-Match: "<etag>" so a concurrent console edit conflicts instead of
            being silently overwritten (measured via client source, policyfile.go)

Rule 11: the credential arrives ONLY through config_schema secrets read via
agent.secret_scope.get_secret (see __init__._get_secret) — this module never touches
os.environ or .env files itself.
"""

from __future__ import annotations

import base64
import json
import re
import urllib.error
import urllib.parse
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
                 accept: Optional[str] = None, want_headers: bool = False):
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
                body = resp.read()
                return (body, dict(resp.headers)) if want_headers else body
        except urllib.error.HTTPError as exc:
            raise TailnetAPIError(exc.code, exc.read().decode(errors="replace")) from exc
        except OSError as exc:
            raise TailnetAPIError(0, f"network: {exc}") from exc

    def _get(self, path: str) -> Any:
        return json.loads(self._request("GET", path))

    def _tn(self) -> str:
        # "-" is the API's own "this token's tailnet" spelling (measured 2026-11-06) —
        # a configured handle is welcome (multi-tailnet futures) but never required.
        return urllib.parse.quote(self.tailnet or "-", safe="")

    # -- probes the wizard runs -------------------------------------------------
    def validate(self) -> dict:
        """GET /tailnet/-/devices — 200 proves the credential AND discovers the tailnet."""
        data = self._get("/tailnet/-/devices")
        # Measured 2026-11-06: the devices response carries NO "tailnet" field; every
        # endpoint accepts the literal "-" (the token's own tailnet), so "-" is the
        # first-class answer and config stays optional.
        self.tailnet = str(data.get("tailnet") or self.tailnet)
        return data

    def dns_preferences(self) -> dict:
        return self._get(f"/tailnet/{self._tn()}/dns/preferences")

    def acl_full(self) -> tuple[str, Optional[str]]:
        """(policy text, etag). We ASK for JSON and MEASURED (2026-11-06, owner's
        commented legacy file): the API honors it and returns clean {"grants","ssh"}
        JSON — the auto-patch path therefore works on comment-heavy files too. The
        etag is NOT in the body; it is the HTTP ETag header (measured), so we grab
        headers here. Tolerates a bare policy body for canned test payloads."""
        body, headers = self._request("GET", f"/tailnet/{self._tn()}/acl",
                                      accept="application/json, text/plain;q=0.9",
                                      want_headers=True)
        text = body.decode(errors="replace")
        etag = headers.get("ETag") or headers.get("etag")
        try:
            parsed = json.loads(text)
        except ValueError:
            return text, str(etag).strip('"') if etag else None
        if isinstance(parsed, dict) and isinstance(parsed.get("acl"), str):
            etag = etag or parsed.get("etag")
            return parsed["acl"], str(etag).strip('"') if etag else None
        return text, str(etag).strip('"') if etag else None

    def acl_text(self) -> str:
        return self.acl_full()[0]

    def patch_acl(self, text: str, etag: Optional[str] = None) -> Any:
        """Full-file REPLACEMENT (measured: the verb is POST, there is no PATCH). The
        preview→approve discipline lives in the caller; passing the etag from acl_full()
        makes a concurrent console edit a conflict, not a silent overwrite."""
        return json.loads(self._request("POST", f"/tailnet/{self._tn()}/acl",
                                        raw_body=text.encode(), if_match=etag))

    def funnel_attr_present(self, want_target: str = "") -> tuple[bool, str]:
        """Funnel needs BOTH the tailnet enable (the per-node link — discovered live by
        --fix serve) and a `funnel` node attribute in the ACL (KB 1223). We DETECT
        here; with ``want_target`` the answer is per-node ("does the GATEWAY have it"),
        which is what pairing actually requires (review fix: the grant belongs on the
        gateway node, not the watch tag). The fix verb (full-file replace behind
        preview→approve, or a printed console diff) is the wizard's call."""
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
        attrs = acl.get("nodeAttrs") or []
        # Detect BOTH spellings: grants-era array [{src,hosts,prop}] (what a GET after
        # our write returns — measured) and legacy map {name:{attr,hosts}} (old files).
        groups = attrs.values() if isinstance(attrs, dict) else (attrs if isinstance(attrs, list) else [])
        for what in groups:
            if not isinstance(what, dict):
                continue
            if not any(str(a) == "funnel" or str(a).startswith("funnel:")
                       for a in (what.get("prop") or what.get("attr") or [])):
                continue
            if not want_target:
                return True, "funnel attribute present on at least one node"
            targets = [str(t) for t in (what.get("hosts") or what.get("target") or [])]
            if want_target in targets:
                return True, f"funnel attribute granted to {want_target} (this gateway)"
        if want_target:
            return False, (f"no funnel attribute for THIS gateway node ({want_target}) "
                           "— hermes watch setup --fix funnel")
        return False, "ACL lacks a funnel node attribute (hermes watch setup --fix funnel)"

    def add_funnel_attr(self, target: str, also_tag: str = "") -> Any:
        """Grant the funnel node attribute to ONE node — the GATEWAY itself (review
        fix: v0.1 pointed this at the watch tag, which was the wrong node entirely:
        Funnel terminates on whoever fronts the door). ``target`` is a policy-spellable
        node id: the gateway's tailscale IP (100.x) is the stable spelling the wizard
        uses. The tag rule is kept only so tag-carrying watch keys still validate:
        (a) the tag must EXIST before any key can carry it (mint 400s 'tags ... invalid
        or not permitted' otherwise), so a tagOwners rule defines it for members.
        Conflicts surface as TailnetAPIError; the wizard then prints the console diff
        instead of guessing. CAVEAT (measured): replacing a commented legacy policy
        with this render loses the comments — the file becomes clean JSON."""
        try:
            text, etag = self.acl_full()
            acl = json.loads(text)
        except (ValueError, TailnetAPIError) as exc:
            raise TailnetAPIError(0, f"cannot replace a non-JSON/default ACL ({exc}); use the console") from exc
        if not isinstance(acl, dict):
            raise TailnetAPIError(0, "ACL policy is not a JSON object; use the console")
        target = str(target or "").strip()
        if not target:
            raise TailnetAPIError(0, "empty funnel target")
        # Shapes measured live (each 400 taught one): tagOwners = LEGACY MAP form
        # ({tag: [owners]}); nodeAttrs = ARRAY of {target, attr} grants — NOT prop,
        # NOT hosts, NOT src (all rejected); the legacy map-of-{attr,hosts} spelling
        # is refused on write.
        owners = acl.setdefault("tagOwners", {})
        for tag in (t for t in (target, also_tag) if t.startswith("tag:")):
            if not owners.get(tag):
                owners[tag] = ["autogroup:member"]
        attrs = acl.setdefault("nodeAttrs", [])
        if not isinstance(attrs, list):
            raise TailnetAPIError(0, "nodeAttrs is legacy-map form; convert to grants-array in the console first")
        if not any(isinstance(g, dict) and target in (g.get("target") or g.get("hosts") or [])
                   and any(str(a) == "funnel" or str(a).startswith("funnel:")
                           for a in (g.get("attr") or g.get("prop") or []))
                   for g in attrs):
            attrs.append({"target": [target], "attr": ["funnel"]})
        return self.patch_acl(json.dumps(acl, indent=2), etag)

    def delete_device(self, node_id: str) -> Any:
        """Remove a node from the tailnet (full-revoke, D12). 404 afterwards is the
        proof of removal. Route measured against the v2 API: DELETE devices/{id}.

        The engine self-reports the NUMERIC nodeId (first-session bootstrap), but the
        device route is keyed by the object id (neaoq…-style) — a live revoke on a real
        watch 404ed on the numeric ref (2026-11-09). So a bare 404 resolves the ref
        through the device list (id, nodeId, or hostname) and retries once; unknown
        refs keep the original 404 (which revoke already reports as a clear step)."""
        try:
            self._request("DELETE", f"/tailnet/{self._tn()}/devices/{urllib.parse.quote(node_id)}")
        except TailnetAPIError as exc:
            if exc.status != 404:
                raise
            want = str(node_id)
            for d in (self._get("/tailnet/-/devices") or {}).get("devices", []):
                if want in (str(d.get("id", "")), str(d.get("nodeId", "")),
                            str(d.get("name", "")), str(d.get("hostname", ""))):
                    self._request("DELETE",
                                  f"/tailnet/{self._tn()}/devices/{urllib.parse.quote(str(d['id']))}")
                    return {"deleted": str(d["id"]), "resolved_from": want}
            raise
        return {"deleted": node_id}

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
            "description": "hermes wearkit pairing",  # measured: parentheses are rejected
        }
        data = json.loads(self._request("POST", f"/tailnet/{self._tn()}/keys", payload=body))
        self.last_key_id = data.get("id") or ""  # expire an un-spent key after a lost claim
        return data["key"]

    def expire_key(self, key_id: str) -> Any:
        """Cleanup for an UN-spent enrollment key. Measured 2026-11-06: the verb is
        DELETE /keys/{id} (→ 200 'null'); POST .../keys/{id}/expire is not a route."""
        self._request("DELETE", f"/tailnet/{self._tn()}/keys/{urllib.parse.quote(key_id)}")
        return {"expired": key_id}
