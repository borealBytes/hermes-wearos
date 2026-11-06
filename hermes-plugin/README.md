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
  door: `pair.<tailnet>.ts.net` serving `/pair/*`.
- `requires_hermes >= 0.22` (floor re-measured at PR time).

## Disclosure (rule 13)

- **Network calls to third parties:** the plugin calls `api.tailscale.com` (device
  list, DNS prefs, ACL read, key mint/expire) whenever you run `hermes watch
  pair|status|setup`, and at gateway startup for the readiness pass. It PATCHes your
  tailnet **ACL** only behind an explicit preview→approve (`hermes watch setup --fix
  funnel`); otherwise it prints the console diff.
- **Public exposure:** Funnel exposes **`/pair/*` only** on the gateway box — the claim
  endpoint takes `{pin, tailnet}` and answers an enrollment packet once. All other
  traffic (every chat call, media, notifications) rides the **tailnet**, not the funnel.
- **Stored credentials/secrets:** the Tailscale API token / OAuth client secret live as
  declared plugin **secrets** (config_schema; stored in the profile's `.env`, read via
  Hermes's `get_secret`). Per-watch enrollment keys are single-use and zeroed after the
  packet is acknowledged. Plugin state (device registry, PIN ledger) is one
  profile-scoped JSON file at `~/.hermes/plugin-data/wearos/state.json`, mode 0600.
- **Background processes:** none started by the plugin itself. It *uses* your existing
  tailscaled; at setup time you may approve the official Tailscale installer (fetched
  from the upstream's official source, pinned by checksum, disclosed before running).
- **Optional Node sidecar:** the media lane (mermaid/images/audio to the watch) still
  falls back to the documented watchkit Node sidecar (`scripts/watchkit.sh`) until the
  in-process port lands; the plugin does not install or update it.

## Layout

| File | Role |
|---|---|
| `plugin.yaml` | manifest (capabilities verified against hermes-agent v0.22.5) |
| `__init__.py` | pairing state machine, claim + registry, tools, `/pair`, `hermes watch` |
| `tailscale.py` | stdlib-only Management API client (key mint/expire, DNS, ACL + funnel attr) |
| `media.py` | in-process media lane (porting; sidecar is the documented fallback) |
| `desktop/plugin.js` | Wear Kit page: devices, PIN, readiness lights |
