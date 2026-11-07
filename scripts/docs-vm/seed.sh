#!/usr/bin/env bash
# Build the NoCloud seed ISO (CIDATA) for the docs VM: hostname/user/ssh-key +
# tailscaled pre-login. Uses xorriso (no sudo, no loop-mount).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VM_DIR="${VM_DIR:-/run/media/clay/Data/vms/hermes-docs}"
KEY="${VM_SSH_PUB:-$HERE/keys/id_ed25519_vm.pub}"
[ -f "$KEY" ] || { echo "seed.sh: no ssh key at $KEY — run: ssh-keygen -t ed25519 -f $HERE/keys/id_ed25519_vm -N '' -C hermes-docs-vm"; exit 1; }

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
sed "s|@SSH_PUB@|$(cat "$KEY")|" "$HERE/user-data.yaml.tmpl" > "$STAGE/user-data"
cat > "$STAGE/meta-data" <<EOF
instance-id: ${INSTANCE_ID:-hermes-docs-seed-1}
local-hostname: hermes-docs
EOF

xorriso -as mkisofs -o "$VM_DIR/seed.iso" -volid cidata -joliet -rock \
  "$STAGE/user-data" "$STAGE/meta-data" >/dev/null 2>&1
echo "seed.sh: wrote $VM_DIR/seed.iso"
