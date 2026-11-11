#!/usr/bin/env bash
# Launch (or relaunch) the pairing-ceremony rig gateway container, then bring
# up tailnet + plugin. Idempotent: recreates wearos-gw from the baked image.
# Secrets live in ~/wearos-rig/secrets (0600 authkey); volume wearos-rig-data
# persists node identity + gateway state across relaunches.
#
# Ceremony notes (2026-11-11, earned the hard way):
#  - the base image is hermes-film:dev; its default CMD is the film recorder
#    (up.sh), so WITHOUT a CMD override s6 boots the whole tree, the recorder
#    CMD exits, and /init tears everything down ~5 s in — a restart loop that
#    leaves only fontconfig noise in the logs. Pass `sleep infinity` as CMD.
#  - keep the stock --entrypoint dispatch (PID 1 rule); the tailnet daemon is
#    exec'd in afterwards (rig-tailscale.sh, idempotent, setsid'd so it does
#    not hold the exec pipe open).
#  - API_SERVER_KEY must be in the profile .env BEFORE the gateway service
#    first boots, else :8642 never binds — so the .env is seeded on the volume
#    in a throwaway pre-container.
set -eu
REPO="${1:-$PWD}"
SECRETS="$HOME/wearos-rig/secrets"
ENVFILE="$HOME/.hermes/.env"
ORK=$(grep '^OPENROUTER_API_KEY' "$ENVFILE" | cut -d= -f2- | tr -d '"'"'"' \n')
TST=$(grep '^tailscale_api_token' "$ENVFILE" | cut -d= -f2- | tr -d '"'"'"' \n')
[ -f "$SECRETS/api_server_key" ] || (umask 177; head -c 24 /dev/urandom | base64 | tr -d '+/=' | head -c 32 > "$SECRETS/api_server_key")
ASK=$(cat "$SECRETS/api_server_key")
DSW=$(cat "$SECRETS/session_token")
DSW=${DSW%$'\n'}
chmod 755 "$SECRETS" 2>/dev/null || true
chmod 644 "$SECRETS/session_token" 2>/dev/null || true
docker rm -f wearos-gw >/dev/null 2>&1 || true

# Seed the profile .env on the VOLUME before the container starts.
docker run --rm -v wearos-rig-data:/opt/data --entrypoint bash hermes-wearos:voiceout -c \
  "umask 137; touch /opt/data/.env; grep -q '^TAILSCALE_API_TOKEN=' /opt/data/.env || echo 'TAILSCALE_API_TOKEN=$TST' >> /opt/data/.env; grep -q '^API_SERVER_KEY=' /opt/data/.env || echo 'API_SERVER_KEY=$ASK' >> /opt/data/.env; chown 10000:10000 /opt/data/.env"

# (plugins install git-clones its source — the running container inits the
# baked dir into a throwaway repo just before installing, see the enable step.)

docker run -d --name wearos-gw --restart unless-stopped \
  --cap-add NET_ADMIN --cap-add NET_RAW --device /dev/net/tun \
  -v wearos-rig-data:/opt/data \
  -v "$SECRETS":/run/secrets:ro \
  -v "$REPO/docker/wearos-demo":/rig:ro \
  -p 18642:8642 -p 19119:9119 \
  -e HERMES_DASHBOARD=1 \
  -e HERMES_DASHBOARD_SESSION_TOKEN="$DSW" \
  -e HERMES_UID=10000 -e HERMES_GID=10000 \
  -e OPENROUTER_API_KEY="$ORK" \
  -e TS_HOSTNAME=wearos-demo \
  --entrypoint /opt/hermes/docker/entrypoint-dispatch.sh \
  hermes-wearos:voiceout sleep infinity
echo "wearos-gw created"

# Wait for the API (unauthenticated /health; the gateway can take a couple of
# minutes on first boot). Any HTTP answer counts; docker exec can leave a
# multi-line CODE, so test with a glob, not equality.
CODE=000
for i in $(seq 1 120); do
  CODE=$(docker exec wearos-gw curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8642/health 2>/dev/null || true)
  case "$CODE" in *[1-5][0-5][0-9]*) break;; esac
  sleep 3
done
echo "gateway API: $(echo "$CODE" | tail -1)"
case "$CODE" in *[1-5][0-5][0-9]*) ;; *) docker logs --tail 30 wearos-gw; exit 1;; esac

# Tailnet node: tailscaled + login (serve/funnel need the ACME cert, which
# needs the claim door listening — so that pass runs again AFTER the plugin
# mounts its routes; the script is idempotent).
docker exec wearos-gw bash /rig/rig-tailscale.sh

# Enable the plugin for the profiles we show in the pane, then restart the
# REAL gateway (it lives under /run/hermes-service/gateway-default, supervised
# by profile-gateways — a -t on main-hermes restarts nothing). Routes mount
# at boot: one restart.
docker exec wearos-gw bash -c 'chown -R 10000:10000 /opt/data/profiles; chmod -R u+rwX /opt/data/profiles/wear-demo/plugins 2>/dev/null; rm -rf /opt/data/profiles/wear-demo/plugins/.install-* 2>/dev/null
  cd /opt/wearos/plugin && git init -q 2>/dev/null; git add -A 2>/dev/null
  git -c user.email=rig@local -c user.name=rig commit -qm rig 2>/dev/null
  chown -R 10000:10000 /opt/wearos/plugin/.git 2>/dev/null; true'
docker exec -u hermes wearos-gw bash -c '
  HOME=/opt/data /opt/hermes/bin/hermes plugins enable wearos 2>&1 | tail -1
  HOME=/opt/data /opt/hermes/bin/hermes plugins install -p wear-demo file:///opt/wearos/plugin 2>&1 | tail -1
  HOME=/opt/data /opt/hermes/bin/hermes plugins enable wearos -p wear-demo 2>&1 | tail -1
  /command/s6-svc -t /run/hermes-service/gateway-default
'
sleep 15
docker exec wearos-gw bash /rig/rig-tailscale.sh   # now serve + funnel
echo "== probes =="
echo -n "funnel door /pair/begin: "; curl -sk -X POST https://wearos-demo.tailb12aa5.ts.net/pair/begin -H 'Content-Type: application/json' -d '{}' -o /dev/null -w "%{http_code} (426 = healthy v2 greeting)\n"
echo -n "lane /voice/cap:         "; curl -sk -o /dev/null -w "%{http_code} (403 = who-is gate, healthy)\n" http://100.113.32.103:42654/voice/cap
echo -n "dashboard /status:       "; docker exec wearos-gw curl -s -o - -w " [%{http_code}]\n" http://127.0.0.1:9119/api/plugins/wearos/status -H "Authorization: Bearer $(printf '%s' "$(cat "$SECRETS/session_token")")" | head -c 300; echo
