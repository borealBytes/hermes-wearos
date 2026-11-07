#!/usr/bin/env bash
# docs-vm/provision.sh — idempotent in-VM provisioning (runs on the HOST, drives ssh):
#   1. Fresh Hermes install via the OFFICIAL installer (curl … install.sh | bash -s --
#      --non-interactive). The needs_user_input stages (setup: API keys, gateway:
#      service) skip — nothing account-bound is created. If this ever breaks headless,
#      the documented fallback is: tar the host's ~/.hermes/hermes-agent checkout
#      (strip .git/credentials) into the VM with a fresh empty ~/.hermes.
#   2. Plugin: rsync this repo's hermes-plugin/ into ~/.hermes/plugins/wearos
#      (docs shortcut; the blessed path is `hermes plugins install` from the catalog).
#   3. OPTIONAL SET B (env-driven, see run.sh): HERMES_DOC_TOKEN (owner's existing API
#      token) -> ~/.hermes/.env as the plugin credential; TAILSCALE_DOCS_KEY (freshly
#      minted ephemeral auth key) -> tailscale login. Values are piped over stdin and
#      never printed.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
VM_DIR="${VM_DIR:-/run/media/clay/Data/vms/hermes-docs}"
SSHOPTS="-i $HERE/keys/id_ed25519_vm -p 2201 -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR"
SSH="ssh $SSHOPTS docs@127.0.0.1"

say(){ printf '\n\033[1;36m== provision: %s\033[0m\n' "$*"; }

say "fresh hermes via official installer (non-interactive)"
$SSH 'test -x "$HOME/.local/bin/hermes" && echo hermes-already-installed || {
  curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash -s -- --non-interactive 2>&1 | tail -5
}'
$SSH 'export PATH="$HOME/.local/bin:$PATH"; hermes --version'

say "plugin: rsync hermes-plugin/ -> ~/.hermes/plugins/wearos"
$SSH 'mkdir -p ~/.hermes/plugins'
rsync -az --delete -e "ssh $SSHOPTS" \
  --exclude __pycache__ --exclude .git \
  "$REPO/hermes-plugin/" docs@127.0.0.1:.hermes/plugins/wearos/

say "config: enable wearos + webapp (still NO credential = SET A red-light state)"
# `hermes config set` writes the right keys into the installer's heavily-commented
# config.yaml — no hand-editing YAML.
$SSH 'export PATH="$HOME/.local/bin:$PATH"
  hermes plugins enable wearos
  hermes config set plugins.entries.wearos.settings.gateway_profile wear
  hermes config set webapp.enabled true'

if [ -n "${HERMES_DOC_TOKEN:-}" ]; then
  say "SET B: tailscale API token -> ~/.hermes/.env (piped via stdin, never logged)"
  $SSH 'umask 077; mkdir -p ~/.hermes; touch ~/.hermes/.env; sed -i "/^tailscale_api_token=/d" ~/.hermes/.env'
  printf 'tailscale_api_token=%s\n' "$HERMES_DOC_TOKEN" | $SSH 'cat >> ~/.hermes/.env'
  # Keep plugins.entries.wearos.settings.tailnet EMPTY: the plugin validates the token
  # against /tailnet/- (the API's own "my tailnet"), so everything still goes green —
  # and the webapp settings form renders an empty Tailnet box instead of the owner's
  # handle, keeping committed pixels PII-clean BY CONSTRUCTION.
  $SSH 'python3 - <<PY
import pathlib, re
p = pathlib.Path.home()/".hermes/config.yaml"
lines = (p.read_text().splitlines(keepends=True) if p.exists() else [])
out = [l for l in lines if not re.match(r"^\s+tailnet:", l)]
p.write_text("".join(out))
print("config: wearos tailnet left empty (PII-clean form; plugin self-detects)")
PY'
fi

if [ -n "${TAILSCALE_DOCS_KEY:-}" ]; then
  say "SET B: tailscale login as node 'hermes-docs' (ephemeral single-use key)"
  # Ephemeral key dies when the node is deleted/24h — safe to hand over the command
  # line of a non-interactive remote shell (no VM bash history exists here).
  $SSH "sudo tailscale up --hostname=hermes-docs --auth-key='$TAILSCALE_DOCS_KEY' >/dev/null && echo tailscale-joined"
  $SSH 'tailscale status --peers=false | head -2'
fi

echo "provision.sh: done"
