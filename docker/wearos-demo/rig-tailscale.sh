#!/usr/bin/env bash
# Rig tailnet bring-up (docker-pairing-ceremony, 2026-11-11). Run INSIDE the
# stock-entrypoint gateway container (docker exec). The image's s6 tree owns
# the gateway; the tailnet node is a deliberate side-daemon so the claim door
# gets a REAL MagicDNS name and Funnel front, and the private lane binds a
# real tailnet IP — an honest ceremony, no fake whois. Userspace networking
# (same mode the watch tsnet node runs). State lives on the /opt/data volume
# so the node identity survives container restarts; this script is idempotent
# (safe to run on every boot).
set -eu
SOCK=/run/tailscale/tailscaled.sock
TS="tailscale --socket=$SOCK"
mkdir -p /var/lib/tailscale
pgrep -x tailscaled >/dev/null || { mkdir -p /opt/data/tailscale-state; setsid tailscaled --state=/opt/data/tailscaled.state \
  --statedir=/opt/data/tailscale-state \
  --socket=$SOCK --tun=userspace-networking </dev/null >>/var/log/tailscaled-rig.log 2>&1 & }
# The wearos plugin probes the STOCK socket path; bridge ours to it.
mkdir -p /var/run/tailscale && ln -sfn "$SOCK" /var/run/tailscale/tailscaled.sock
for i in $(seq 1 40); do $TS status >/dev/null 2>&1 || $TS ping 127.0.0.1 >/dev/null 2>&1; pgrep -x tailscaled >/dev/null && break; sleep 0.5; done
$TS status | grep -q "100\." || $TS up --hostname "${TS_HOSTNAME:-wearos-demo}" --authkey="$(cat /run/secrets/ts_authkey)" --timeout=60s
IP=$($TS ip | awk '{print $1}' | head -1)
echo "rig: node up as ${TS_HOSTNAME:-wearos-demo} (${IP})"
# --yes: serve/funnel prompt before overwriting an existing handler, and the
# non-interactive run used to quietly keep the old (self-proxy) config.
$TS serve --yes --bg --https=443 "http://127.0.0.1:${RIG_CLAIM_PORT:-42653}" >/dev/null
$TS funnel --yes --bg --https=443 "http://127.0.0.1:${RIG_CLAIM_PORT:-42653}" >/dev/null || echo "rig: funnel refused (needs Funnel attr on the join tag)"
echo "rig: funnel: $(curl -s "https://api.tailscale.com/api/v2/tailnet/tailb12aa5.ts.net/devices" -H "Accept: application/json" 2>/dev/null | head -c 1 >/dev/null; echo "https://${TS_HOSTNAME:-wearos-demo}.tailb12aa5.ts.net")"
