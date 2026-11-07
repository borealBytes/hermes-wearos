#!/usr/bin/env bash
# docs-vm/mint-key.sh — mint an EPHEMERAL single-use tailscale auth key for the docs VM
# on the OWNER's tailnet, then print ONLY the key (one line). Token comes from
# HERMES_DOC_TOKEN or ~/.hermes/.env; it is never echoed, and the key is read-only
# usage of the API surface (POST /keys is the one write this pipeline does, by design).
#   tags:  tag:hermes-watch (matches the plugin's watch_tag default)
#   ephemeral + reusable:false -> the node dies when the VM is torn down.
# Optional: EXPIRATION_SECONDS (default 86400), PREAUTHORIZED=true (default: the
# tailnet's ACL may require it for tag assignment; set false to observe a 4xx honestly).
set -euo pipefail
TOKEN="${HERMES_DOC_TOKEN:-$(sed -n 's/^tailscale_api_token=//p' "$HOME/.hermes/.env" 2>/dev/null | head -1 | tr -d '"')}"
TAILNET="${TAILNET_HANDLE:-}"
if [ -z "$TAILNET" ]; then
  echo "mint-key.sh: set TAILNET_HANDLE (the console handle, e.g. from plugin state.json 'tailnet' device field)" >&2
  exit 2
fi
[ -n "$TOKEN" ] || { echo "mint-key.sh: no tailscale_api_token available" >&2; exit 2; }
EXPIRATION_SECONDS="${EXPIRATION_SECONDS:-86400}"
H=$(python3 -c 'import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1],safe=""))' "$TAILNET")

resp=$(curl -sS --fail-with-body -X POST \
  "https://api.tailscale.com/api/v2/tailnet/$H/keys" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"capabilities\":{\"devices\":{\"create\":{\"reusable\":false,\"ephemeral\":true,\"preauthorized\":true,\"tags\":[\"tag:hermes-watch\"]}}},\"expirySeconds\":$EXPIRATION_SECONDS,\"description\":\"hermes-docs-vm\"}")
# measured field shapes (tailscale.py, prod-verified): secret rides in "key"
key=$(printf '%s' "$resp" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("key",""))')
[ -n "$key" ] || { echo "mint-key.sh: key mint failed: $(printf '%s' "$resp" | head -c 200)" >&2; exit 1; }
echo "$key ok"
