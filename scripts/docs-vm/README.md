# scripts/docs-vm — reproducible PII-clean doc screenshots from a BRAND-NEW Hermes install

The owner's daily box has 23 profiles and hostname PII (`tuxdesktop`), useless for
docs. This pipeline boots a throwaway Ubuntu 24.04 cloud-image VM, installs Hermes
FRESH (official installer), rsyncs this repo's `hermes-plugin/` in as the only
user-source plugin, and regenerates every `docs/screens/6*-vm-*.png` shot twice:
SET A with no account/credential at all, then SET B joined to the owner's real
tailnet with an EPHEMERAL single-use node key + the owner's existing API token.

## One command

```bash
scripts/docs-vm/run.sh          # create → provision → capture; VM LEFT RUNNING
scripts/docs-vm/run.sh --destroy        # …then kill + delete the VM (disk)
scripts/docs-vm/run.sh --capture-only   # re-shoot against an already-provisioned VM
scripts/docs-vm/capture.sh              # re-shoot only (iterate on the browser flow)
scripts/docs-vm/capture.sh --only-a     # SET A shots, no tailnet secrets needed
```

Host prereqs: `qemu-system-x86_64`, `ssh`/`rsync`, `xorriso`, `curl`, `python3`,
`playwright-cli`. SET B reads (READ-ONLY, never printed): the owner's
`tailscale_api_token` from `~/.hermes/.env` and the tailnet handle from
`~/.hermes/plugin-data/agent-plugin-wearos-*/state.json` — export
`HERMES_DOC_TOKEN` / `TAILNET_HANDLE` to override. The host's own gateway is never
touched (no restarts, no `--stop`, no PATCH calls — one POST /keys mint is the
only tailnet write, and `tailscale funnel` from the VM node fronts the door).

## What you get (and why each is trustworthy)

| Shot | State | Truth |
|---|---|---|
| `61-vm-plugins-page.png` | fresh install | the wearos plugin row in the DESKTOP-IN-WEB plugins list (Settings ▸ Plugins) — `hermes webapp`, not the old `hermes dashboard` UI (owner rule 2026-11-07) |
| `62-vm-wizard-red.png` | no tailscale login, no credential | verbatim `hermes watch setup` JSON, red lights + their fix hints |
| `63-vm-wizard-green.png` | joined tailnet + funnel, NO watch | all green except `devices_online: 0 paired` — honest |
| `64-vm-pairing-ceremony.png` | pairing simulated over curl | mint PIN → watch knocks `POST /pair/claim` → `hermes watch pair <PIN>` claims → packet collected ONCE (second GET = 410) |
| `65-vm-wizard-paired.png` | after the ceremony | all green, `1 paired`, the device row from `hermes watch devices` |
| `66-vm-webapp-plugin-settings.png` | webapp (`hermes serve` + one-use ticket) | the REAL plugin config form (Settings ▸ Plugins ▸ wearos) rendered from `config_schema` — secret fields empty by design |

**Terminal shots are RE-RENDERINGS, not photos of a terminal.** capture.sh pipes the
real CLI/curl output through `render_terminal.py`, which themes the VERBATIM text
(no flags invented, nothing reflowed) into a dark page matching the repo's docs
theme (60-plugin-card.png), and screenshots THAT with playwright-cli (file:// is
blocked, so pages are served over a localhost http.server).

**PII strategy: born clean, not masked over.** VM hostname `hermes-docs`, user
`docs`, plain slirp NAT IP; the tailnet handle is sed-replaced to `<your-tailnet>`
(and any `*.ts.net` label, and the just-spent auth key to
`tskey-auth-‹spent by this very GET›`) in the TEXT before rendering — so no
rectangles or blur ever go near a PNG. The `plugins.entries.wearos.settings.tailnet`
config is deliberately left EMPTY: the plugin then validates via the API's
`/tailnet/-` ("my tailnet") path and everything still goes green, and the settings
form shows an empty Tailnet box instead of the owner's handle. Nothing token-shaped
ever reaches a screenshot.

## How the VM works (all unprivileged, no libvirt)

* **Plain `qemu-system-x86_64 -netdev user`** — virt-install/system libvirt needs
  sudo (prompts here), and session libvirt 12 refused `forward mode='user'` and
  can't create a NAT bridge without CAP_NET_ADMIN. Slirp forwards only ssh
  (`host:2201`); the dashboard/webapp ports ride `ssh -L` tunnels because the
  servers bind the guest's loopback, which slirp can't reach.
* **cloud-init NoCloud seed** built by `seed.sh` (xorriso, label `cidata`; the
  template's first line MUST be literally `#cloud-config`). It creates the `docs`
  user + `keys/id_ed25519_vm` (throwaway, committed), installs tailscaled
  (logged-OUT = SET A), runs on every fresh disk.
* **VM lives at** `/run/media/clay/Data/vms/hermes-docs/`
  (`hermes-docs.qcow2` grown to 16 G, `seed.iso`, `vm.pid`, `console.log`).
  Teardown: `run.sh --destroy`, or `kill $(cat vm.pid); rm hermes-docs.qcow2`.
* **Provisioning** (`provision.sh`, idempotent): official installer
  `curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash -s --
  --non-interactive` — the needs-input stages (model keys, gateway service) skip,
  which is exactly what "fresh install, nothing account-bound" should look like.
  Plugin arrives by `rsync hermes-plugin/ → ~/.hermes/plugins/wearos` +
  `hermes plugins enable wearos` (docs shortcut; the blessed path is the catalog —
  a catalog-installed plugin differs only by provenance metadata).

## Iterating on SET B

`capture.sh` makes every run deterministic: it LOGS THE NODE OUT (`tailscale
logout` deletes the ephemeral node), parks the token, wipes plugin state, shoots
SET A, then mints a FRESH ephemeral key, re-joins, `hermes watch setup --fix serve`
funnels the door, pairs, and shoots. A re-run spends one new single-use key and
leaves the tailnet as it found it (the node + its key expire in 24 h regardless).

## Known limitations (documented, not hidden)

* **Plugin/config doc shots ride the DESKTOP-IN-WEB lane** (`hermes webapp`), not the old
  `hermes dashboard` web UI (owner rule 2026-11-07). Mechanics, as encoded in
  `capture.sh::webapp_open`: run `hermes serve` (the loopback serve itself is a headless
  stub — its web UI is disabled and a non-loopback bind 400s the Host guard), mint a
  ONE-USE 60 s ticket with `hermes webapp --no-open --skip-build` (bundle built once by
  `hermes webapp --build-only`; the git installer ships no prebuilt bundle), decline the
  provider setup screen, Settings ▸ Plugins ▸ wearos. The old dashboard card only ever
  said "No dashboard tab" — one more reason it is not the proof surface.
* The Desktop Wear Kit PANE (checklist + enable checkboxes,
  `hermes-plugin/desktop/plugin.js`) is a right-sidebar layout pane of the **desktop APP
  shell only** — the dist-webapp renderer does NOT carry it, and adding a pane to the
  layout is a drag-and-drop action. So the web lane stops at the settings form (shot 66);
  the pane shot comes from the running Desktop app itself.
* `watchkit_token`/`gateway_api_key` are null in the packet: the docs VM runs no
  watchkit and its `wear` profile has no API_SERVER_KEY — a genuinely fresh box
  would look the same.
* JSON `\u2014` escapes in the wizard shots are verbatim: the plugin's
  `json.dumps` keeps ensure_ascii.
