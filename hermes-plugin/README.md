# Hermes Wear Kit (plugin)

The Hermes-side of [hermes-wearos](../README.md): install once, pair a watch with a
6-digit PIN, done. The watch app lives on the watch and enrolls into **your own
tailnet** with its embedded Tailscale node — no LAN transport, no phone relay, no
reusable keys lying around. Everything after pairing rides the tunnel (SPEC §11.4).

```bash
hermes plugins install wearos     # catalog entry → pinned, reviewed SHA
hermes watch setup                # wizard: tailscaled → credential → MagicDNS → funnel
hermes watch pair                 # or: /pair <PIN> in any Hermes chat surface
```

## Install the watch app

The Wear OS APK is **not bundled here**: it ships as a CI-built GitHub Release
(`gradle assembleRelease` → Release assets + `SHA256SUMS.txt` + build provenance via
`actions/attest-build-provenance`). Verify with
`gh attestation verify --repo borealBytes/hermes-wearos <apk>`.

## Requirements

- A **Tailscale account + tailnet** on the Hermes host machine. Don't have one? The
  setup wizard walks you through it: create an account (free Personal plan covers this)
  → install tailscaled via the official installer script (pinned version, checksum
  disclosed at run time) → `tailscale up` → rerun the wizard.
- Tailscale API credential: an **OAuth client** (recommended; restrict it to the
  `auth_keys` + `devices:core` scopes and to the watch tag) **or** an Owner/Admin API
  token. Both are entered as plugin config secrets (`hermes plugins config wearos` or
  `plugins.entries.wearos.settings` in config.yaml).
- MagicDNS on (wizard checks and links the console). Funnel used only for the pairing
  door: the gateway node's MagicDNS name (e.g. `box.tailb12aa5.ts.net`) serving `/pair/*`
  — `hermes watch setup --fix serve` prints the exact line to type on the watch.
- `requires_hermes >= 0.22` (floor re-measured at PR time).

## The claim door (pairing API — the watch client is built against exactly this)

A loopback HTTP listener (`claim_server.py`, port `claim_port`, default **42653**,
bound to 127.0.0.1 only) is the ONE pre-enrollment network surface. On the box,
`tailscale serve --bg --https=443 --funnel /pair/*` fronts it; nothing else is exposed.
No bearer: the 6-digit PIN + single-claim + rate limits ARE the auth.

```
POST /pair/claim   body {"pin":"123456","tailnet":"<handle>","hostname":"<watch name>"}
                   → 202 {"queued":true};  wrong/expired/consumed → 410;  rate-hot → 429
                   (all non-2xx bodies are JSON {"error": "..."})
GET  /pair/claim?pin=123456
                   → 204 while the user hasn't claimed yet;
                   → 200 + the enrollment packet ONCE, then 410 forever (single-claim)

Packet = {"authkey", "tailnet", "gateway_fqdn", "gateway_port": 8642,
          "watchkit_port": 42651, "profile", "api_key", "watchkit_token"}   # token nullable
```

The watch-minted PIN is adopted on its first POST; the packet appears when the user
claims the PIN (`hermes watch pair <PIN>`, `/pair <PIN>` in chat, or the `watch_pair`
tool). The authkey is zeroed out of the ledger the moment the watch collects it, and the
device lands in the registry under the hostname **the watch sent** (re-pair updates the
record in place and notes `re_enrolled_at`).

`api_key` resolution order: the **gateway profile's own** `API_SERVER_KEY` (the plugin
binds that profile's secret scope explicitly — `agent.secret_scope.get_secret` is
context-scoped, not profile-scoped, and fails closed on the door's own threads), then
the ambient scope (single-profile deployments), then the declared `gateway_api_key`
plugin secret as a fallback. If you run the gateway as a different profile than the
watch binds to and the profile home is unresolvable, set `gateway_api_key`.

## Wizard fix-verbs

`hermes watch setup` prints the readiness checklist; every red light names its fix:

- `--fix credential` — live-validates the OAuth pair / API token, explains 401/403/network
  causes, and shows the discovered tailnet handle to copy into settings.
- `--fix funnel` — detects the `funnel` node attr in the ACL; when absent, prints the
  exact one-rule diff and the console URL. Re-run `--fix funnel --yes` to preview→approve
  the same rule via an ACL PATCH (JSON policies only; huJSON tails print the diff, never a
  blind PATCH).
- `--fix serve` — reads `tailscale serve status` (read-only) and prints the exact command
  to run — the wizard never executes system commands itself:
  `tailscale serve --bg --https=443 --funnel /pair/*`

## Tests

`python3 -m pytest hermes-plugin/tests/` (stdlib + pytest; the suite sandboxes
`HERMES_HOME` into a tmp dir, fakes the Tailscale API, and speaks real HTTP to an
ephemeral claim door). Host modules resolve from `~/.hermes/hermes-agent` or
`$HERMES_AGENT_ROOT`.

## Disclosure (rule 13)

- **Network calls to third parties:** the plugin calls `api.tailscale.com` (device
  list, DNS prefs, ACL read, key mint/expire) whenever you run `hermes watch
  pair|status|setup`, and at gateway startup for the readiness pass. It PATCHes your
  tailnet **ACL** only behind an explicit preview→approve (`hermes watch setup --fix
  funnel`); otherwise it prints the console diff.
- **Public exposure:** Funnel exposes **`/pair/*` only** on the gateway box — the claim
  endpoint takes `{pin, tailnet, hostname}` and answers an enrollment packet once. All
  other traffic (every chat call, media, notifications) rides the **tailnet**, not the
  funnel.
- **Stored credentials/secrets:** the Tailscale API token / OAuth client secret live as
  declared plugin **secrets** (config_schema; stored in the profile's `.env`, read via
  Hermes's `get_secret`). Per-watch enrollment keys are single-use and zeroed from the
  ledger the moment the watch collects its packet; the device registry never stores one.
  Plugin state (device registry, PIN ledger, claimed-packet tombstone) is one
  profile-scoped JSON file at `~/.hermes/plugin-data/wearos/state.json`, mode 0600.
- **Background processes:** the plugin starts ONE in-process daemon thread — the loopback
  claim door (`127.0.0.1:claim_port`, opened at `gateway:startup`, closed on plugin
  unload; `hermes watch pair` re-opens it if nothing is answering). No subprocesses are
  *run* by pairing; `--fix serve` reads `tailscale serve status` and PRINTS commands, it
  never executes system changes. It *uses* your existing tailscaled; at setup time you
  may approve the official Tailscale installer (fetched from the upstream's official
  source, pinned by checksum, disclosed before running).
- **Optional Node sidecar:** the media lane (mermaid/images/audio to the watch) still
  falls back to the documented watchkit Node sidecar (`scripts/watchkit.sh`) until the
  in-process port lands; the plugin does not install or update it.

## Layout

| File | Role |
|---|---|
| `plugin.yaml` | manifest (capabilities verified against hermes-agent v0.22.5) |
| `__init__.py` | pairing state machine, claim + registry, tools, `/pair`, `hermes watch` |
| `claim_server.py` | the funnel claim door (stdlib ThreadingHTTPServer, 127.0.0.1) |
| `tailscale.py` | stdlib-only Management API client (key mint/expire, DNS, ACL + funnel attr) |
| `media.py` | in-process media lane (porting; sidecar is the documented fallback) |
| `tests/` | pytest suite (state machine, claim API over real HTTP, ACL detect, registry) |
| `desktop/plugin.js` | Wear Kit page: devices, PIN, readiness lights |
