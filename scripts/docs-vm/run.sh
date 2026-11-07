#!/usr/bin/env bash
# docs-vm/run.sh — full pipeline: fresh Ubuntu VM → fresh Hermes + wearos plugin →
# all docs/screens/6*-vm-*.png shots. Safe to re-run: every step is create-or-reuse.
#
#   scripts/docs-vm/run.sh              # create/boot/provision/capture; VM LEFT RUNNING
#   scripts/docs-vm/run.sh --destroy    # capture done, then kill + delete the VM
#   scripts/docs-vm/run.sh --capture-only   # assume VM already provisioned
#
# Requires on the HOST: qemu-system-x86_64 (no root — slirp user-net), ssh, rsync,
# xorriso, curl, python3(+PIL), playwright-cli. A tailscale API token in
# ~/.hermes/.env (key tailscale_api_token) for the SET B ephemeral node key; the
# token is read into the environment once and NEVER echoed.
#
# Why plain qemu (not virt-install/libvirt): system libvirt needs sudo (prompts here)
# and session-mode libvirt 12 refuses both NAT bridge creation (no CAP_NET_ADMIN) and
# `forward mode='user'`. -netdev user + one hostfwd for ssh needs nothing and is fully
# scriptable; the dashboard/webapp ride `ssh -L` tunnels (capture.sh) because those
# servers bind the guest loopback, which slirp forwards cannot reach.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
VM_DIR="${VM_DIR:-/run/media/clay/Data/vms/hermes-docs}"
IMAGE_URL="${IMAGE_URL:-https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-amd64.img}"
BASE="$VM_DIR/noble-server-cloudimg-amd64.img"
DISK="$VM_DIR/hermes-docs.qcow2"
PIDF="$VM_DIR/vm.pid"
SSH="ssh -i $HERE/keys/id_ed25519_vm -p 2201 -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR docs@127.0.0.1"
SCP="scp -i $HERE/keys/id_ed25519_vm -P 2201 -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -q"
DESTROY=false; CAPTURE_ONLY=false
for a in "$@"; do case "$a" in
  --destroy) DESTROY=true ;;
  --capture-only) CAPTURE_ONLY=true ;;
  *) echo "usage: run.sh [--destroy|--capture-only]"; exit 2 ;;
esac; done
mkdir -p "$VM_DIR"
say(){ printf '\n\033[1;36m== %s\033[0m\n' "$*"; }

vm_running(){ [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null; }
boot_vm(){
  say "boot VM (qemu slirp, host:2201→ssh, host:19119→dashboard)"
  rm -f "$VM_DIR/known_hosts"   # fresh VM identities rotate the host key; keep ssh quiet
  qemu-system-x86_64 -enable-kvm -m 4096 -smp 4 -cpu host \
    -drive if=virtio,file="$DISK" -cdrom "$VM_DIR/seed.iso" \
    -netdev user,id=net0,hostfwd=tcp:127.0.0.1:2201-:22 \
    -device virtio-net-pci,netdev=net0 \
    -display none -daemonize -pidfile "$PIDF" -serial file:"$VM_DIR/console.log"
}
wait_ssh(){
  say "waiting for ssh (cloud-init first run)"
  for i in $(seq 1 120); do $SSH true 2>/dev/null && return 0; sleep 5; done
  echo "run.sh: ssh never came up — see the console: virsh/qemu log or $VM_DIR"; exit 1
}

if ! $CAPTURE_ONLY; then
  [ -f "$BASE" ] || { say "fetch Ubuntu 24.04 cloud image (~620 MB)"; curl -fL --retry 3 -o "$BASE.part" "$IMAGE_URL" && mv "$BASE.part" "$BASE"; }
  [ -f "$HERE/keys/id_ed25519_vm" ] || ssh-keygen -t ed25519 -f "$HERE/keys/id_ed25519_vm" -N '' -C 'hermes-docs-vm' -q
  say "cloud-init seed"
  "$HERE/seed.sh"
  if [ ! -f "$DISK" ]; then
    say "create disk"
    cp --reflink=auto "$BASE" "$DISK"
    qemu-img resize "$DISK" 16G >/dev/null   # first boot: cloud-init growparts to fill
  fi
  vm_running || boot_vm
  wait_ssh
  say "provision: fresh hermes install + plugin + tailscaled"
  "$HERE/provision.sh"
fi

vm_running || boot_vm
wait_ssh
say "capture all screenshots"
"$HERE/capture.sh"

if $DESTROY; then
  say "destroy VM"
  vm_running && { kill "$(cat "$PIDF")"; sleep 3; }
  rm -f "$DISK" "$PIDF"
  echo "VM removed ($DISK). Keep $BASE for the next run."
else
  echo "VM left RUNNING (pid $(cat "$PIDF" 2>/dev/null || echo '?'), disk $DISK)."
  echo "  iterate:  $HERE/capture.sh"
  echo "  teardown: virsh-independent: kill \$(cat $PIDF) ; rm $DISK"
fi
