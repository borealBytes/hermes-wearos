#!/usr/bin/env bash
# docs-vm/capture.sh — drive the docs VM and (re)generate every docs/screens/6*-vm-*.png.
#
#   scripts/docs-vm/capture.sh            # SET A + SET B (SET B needs the two host
#                                         # secrets below; without them it stops after
#                                         # SET A and says so)
#   scripts/docs-vm/capture.sh --only-a
#
# Secrets (read-only on the owner's box; never printed, never committed):
#   * tailscale_api_token in ~/.hermes/.env            (the plugin credential)
#   * tailnet handle in ~/.hermes/plugin-data/agent-plugin-wearos-*/state.json
# Override by exporting HERMES_DOC_TOKEN / TAILNET_HANDLE before the call.
#
# PII discipline: the tailnet handle and any *.ts.net label are sed-replaced in the
# captured TEXT before the terminal page is rendered — PNGs are born clean; nothing
# is masked over afterwards. VM hostname/user (hermes-docs/docs) and the slirp IP
# are generic by construction. Terminal shots are re-renderings of VERBATIM captured
# text via render_terminal.py (see README — honesty rule).
#
# Shot order on purpose:
#   61 plugins page (web dashboard, no account)   62 wizard RED (fresh, pre-credential)
#   63 wizard GREEN but devices_online (joined, funneled, no watch — honest)
#   64 pairing ceremony over curl (claim door)    65 wizard ALL GREEN + device row
#   66 webapp Wear Kit page (Desktop-workspace surface)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
VM_DIR="${VM_DIR:-/run/media/clay/Data/vms/hermes-docs}"
WORK="${TMPDIR:-/tmp}/opencode/vmshots"; mkdir -p "$WORK"
SHOTS="$REPO/docs/screens"
KEY="$HERE/keys/id_ed25519_vm"
SSHOPTS="-i $KEY -p 2201 -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR"
SSH="ssh $SSHOPTS docs@127.0.0.1"
DASH=http://127.0.0.1:19119          # ssh -L tunnel -> VM dashboard (loopback bind)
ONLY_A=false; [ "${1:-}" = "--only-a" ] && ONLY_A=true
cd "$WORK"                            # playwright-cli yml junk lands here, gitignored

say(){ printf '\n\033[1;36m== capture: %s\033[0m\n' "$*"; }
shot(){ # shot <file> <selector>
  playwright-cli screenshot "$2" --filename="$SHOTS/$1" >/dev/null 2>&1 || \
  playwright-cli screenshot --filename="$SHOTS/$1" >/dev/null 2>&1
  echo "  shot: docs/screens/$1"
}
# THE redactions for captured text (before rendering — pixels are born clean):
#   tailnet handle (console login)       -> <your-tailnet>
#   any ts.net middle label (FQDN form)  -> hermes-docs.<your-tailnet>
redact(){ sed -E -e "s|$TAILNET|<your-tailnet>|g" \
              -e 's/\.[a-z0-9-]+\.ts\.net/.<your-tailnet>/g' \
              -e 's/("authkey":[ ]*)"tskey-auth-[A-Za-z0-9-]+"/\1"tskey-auth-‹spent by this very GET›"/g'; }
render(){ # render <txt> <html> <cmd> <title>
  python3 "$HERE/render_terminal.py" --cmd "$3" --title "$4" --text-file "$1" > "$2"
}
shoot_terminal(){ # shoot_terminal <name> <txt> <cmd> <title>
  render "$WORK/$2.txt" "$WORK/$2.html" "$3" "$4"
  playwright-cli goto "http://127.0.0.1:$RPORT/$2.html" >/dev/null 2>&1; sleep 1
  shot "$1" "body"
}

# ── secrets (host, read-only) ──────────────────────────────────────────────────
if [ -z "${HERMES_DOC_TOKEN:-}" ]; then
  HERMES_DOC_TOKEN="$(sed -n 's/^tailscale_api_token=//p' "$HOME/.hermes/.env" 2>/dev/null | head -1 | tr -d '"')" || true
fi
if [ -z "${TAILNET:-}" ]; then
  TAILNET="$(python3 - "$HOME" <<'PY'
import json, glob, sys
for f in glob.glob(sys.argv[1]+"/.hermes/plugin-data/agent-plugin-wearos-*/state.json"):
    for d in json.load(open(f)).get("devices", {}).values():
        t = d.get("tailnet") or ""
        if len(t) > 3 and "." in t:
            print(t); sys.exit(0)
PY
)" || true
fi

# ── VM is up, tunnel open, dashboard serving ──────────────────────────────────
say "ssh + tunnel"
$SSH true || { echo "capture.sh: VM ssh down — run run.sh first"; exit 1; }
pkill -f "19119:127.0.0.1:9119" 2>/dev/null || true; sleep 1
setsid nohup ssh $SSHOPTS -o ServerAliveInterval=30 -N \
  -L 127.0.0.1:19119:127.0.0.1:9119 docs@127.0.0.1 >/dev/null 2>&1 < /dev/null &
TUNNEL=$!
sleep 2
if ! $SSH 'ss -tln | grep -q ":9119 "'; then
  say "start VM dashboard (web UI)"
  $SSH 'export PATH=$HOME/.local/bin:$PATH
        hermes serve --stop >/dev/null 2>&1 || true
        setsid nohup hermes dashboard --no-open --skip-build --port 9119 >/tmp/dash.log 2>&1 < /dev/null &
        sleep 6'
fi
# tiny static server so render_terminal.py pages load without the file:// protocol
RPORT=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')
python3 -m http.server "$RPORT" --bind 127.0.0.1 --directory "$WORK" >/dev/null 2>&1 &
RSERVER=$!
cleanup(){ kill "$TUNNEL" "$RSERVER" 2>/dev/null || true; }
trap cleanup EXIT

# ── SET A ──────────────────────────────────────────────────────────────────────
# Deterministic fresh state: log the (ephemeral) node out — logout also tears the
# funnel down — and park the token file. A first-ever run is already in this shape.
say "SET A: reset to fresh state (tailscale logout + token parked + plugin state wiped)"
$SSH 'sudo tailscale logout 2>/dev/null || true
      mv ~/.hermes/.env ~/.hermes/.env.setb 2>/dev/null || true
      rm -rf ~/.hermes/plugin-data/agent-plugin-wearos-* 2>/dev/null || true'
# the dashboard process still holds the OLD env; restart it so nothing stale loads
$SSH 'export PATH=$HOME/.local/bin:$PATH
      hermes dashboard --stop >/dev/null 2>&1 || pkill -f "hermes dashboard" || true
      sleep 2
      setsid nohup hermes dashboard --no-open --skip-build --port 9119 >/tmp/dash.log 2>&1 < /dev/null &
      sleep 6'

say "SET A: hermes watch setup — red lights (verbatim text -> render_terminal.py)"
$SSH 'export PATH=$HOME/.local/bin:$PATH; hermes watch setup 2>&1' | redact > "$WORK/wizard-red.txt"
shoot_terminal 62-vm-wizard-red.png wizard-red \
  "docs@hermes-docs:~ \$ hermes watch setup" "hermes-docs — fresh install, no credential"

say "SET A: dashboard Plugins page (wearos card)"
playwright-cli open "$DASH/plugins?profile=default" >/dev/null 2>&1 || \
  playwright-cli goto "$DASH/plugins?profile=default" >/dev/null 2>&1
playwright-cli resize 1360 900 >/dev/null 2>&1
sleep 3
# telemetry banner, if this build shows it, gets declined like any user would, so it
# never appears in the shot
playwright-cli eval "[...document.querySelectorAll('button')].find(b=>/^(No thanks|Dismiss)$/.test(b.textContent.trim()))?.click()" >/dev/null 2>&1 || true
sleep 1
# center the wearos card in the viewport, then shoot
playwright-cli eval "(()=>{const els=[...document.querySelectorAll('*')].filter(e=>e.children.length===0&&e.textContent.trim()==='wearos');if(els[0])els[0].closest('li').scrollIntoView({block:'center'});return !!els[0]})()"
sleep 1
shot 61-vm-plugins-page.png ""

if $ONLY_A; then
  $SSH 'mv ~/.hermes/.env.setb ~/.hermes/.env 2>/dev/null || true'
  say "--only-a done (SET A shots in docs/screens/)"
  exit 0
fi
if [ -z "${HERMES_DOC_TOKEN:-}" ] || [ -z "${TAILNET:-}" ]; then
  $SSH 'mv ~/.hermes/.env.setb ~/.hermes/.env 2>/dev/null || true'
  say "SET B skipped (no token/tailnet on this host) — SET A shots are in docs/screens/"
  exit 0
fi

# ── SET B ──────────────────────────────────────────────────────────────────────
say "SET B: credential + tailnet + fresh ephemeral single-use key + login"
export HERMES_DOC_TOKEN TAILNET_HANDLE="$TAILNET"
DOCS_KEY=$("$HERE/mint-key.sh" | cut -d' ' -f1)
export TAILSCALE_DOCS_KEY="$DOCS_KEY"
$SSH 'mv ~/.hermes/.env.setb ~/.hermes/.env 2>/dev/null || true'
"$HERE/provision.sh" >/dev/null
$SSH 'sudo tailscale set --operator=docs 2>/dev/null || true'

say "SET B: front the claim door (tailscale funnel via the wizard fix-verb)"
# restart dashboard so the plugin re-reads .env + config BEFORE the fix verb
$SSH 'export PATH=$HOME/.local/bin:$PATH
      hermes dashboard --stop >/dev/null 2>&1 || pkill -f "hermes dashboard" || true
      sleep 2
      setsid nohup hermes dashboard --no-open --skip-build --port 9119 >/tmp/dash.log 2>&1 < /dev/null &
      sleep 6'
$SSH 'export PATH=$HOME/.local/bin:$PATH; hermes watch setup --fix serve 2>&1' \
  | redact > "$WORK/fix-serve.txt"
grep -q "funnel route applied\|already funneled" "$WORK/fix-serve.txt" \
  || { echo "capture.sh: funnel serve not applied — see $WORK/fix-serve.txt"; cat "$WORK/fix-serve.txt"; }

say "SET B: wizard (green path, devices_online red — honest, no watch yet)"
$SSH 'export PATH=$HOME/.local/bin:$PATH; hermes watch setup 2>&1' | redact > "$WORK/wizard-green.txt"
shoot_terminal 63-vm-wizard-green.png wizard-green \
  "docs@hermes-docs:~ \$ hermes watch setup" "hermes-docs — joined tailnet, funnel up, no watch yet"

say "SET B: pairing ceremony (mint PIN -> POST claim -> claim -> collect packet once)"
PIN_JSON=$($SSH 'export PATH=$HOME/.local/bin:$PATH; hermes watch pair --show 2>&1')
PIN=$(printf '%s' "$PIN_JSON" | python3 -c "import json,sys;print(json.load(sys.stdin)['pin'])")
{
  echo "\$ hermes watch pair --show"
  printf '%s\n' "$PIN_JSON" | redact
  echo
  echo "\$ curl -s -X POST http://127.0.0.1:42653/pair/claim \\"
  echo "    -H 'content-type: application/json' \\"
  echo "    -d '{\"pin\":\"$PIN\",\"tailnet\":\"<your-tailnet>\",\"hostname\":\"docs-watch\"}'   # the watch knocks"
  $SSH "curl -s -X POST http://127.0.0.1:42653/pair/claim -H 'content-type: application/json' -d '{\"pin\":\"$PIN\",\"tailnet\":\"$TAILNET\",\"hostname\":\"docs-watch\"}'"
  echo
  echo "\$ hermes watch pair $PIN                                                   # the user says the PIN"
  $SSH "export PATH=\$HOME/.local/bin:\$PATH; hermes watch pair $PIN" 2>&1 | redact
  echo
  echo "\$ curl -s 'http://127.0.0.1:42653/pair/claim?pin=$PIN'                     # the watch polls (~3 s)"
  $SSH "curl -s 'http://127.0.0.1:42653/pair/claim?pin=$PIN'" | redact
  echo
  echo "\$ curl -s 'http://127.0.0.1:42653/pair/claim?pin=$PIN'                     # again: single-claim door"
  $SSH "curl -s 'http://127.0.0.1:42653/pair/claim?pin=$PIN'" | redact
} > "$WORK/pairing.txt"
shoot_terminal 64-vm-pairing-ceremony.png pairing \
  "the claim door in action — 127.0.0.1:42653" "hermes-docs — pairing ceremony"

say "SET B: wizard again — all green, 1 device paired"
{
  $SSH 'export PATH=$HOME/.local/bin:$PATH; hermes watch setup 2>&1' | redact
  echo
  echo "\$ hermes watch devices"
  $SSH 'export PATH=$HOME/.local/bin:$PATH; hermes watch devices' | redact
} > "$WORK/wizard-paired.txt"
shoot_terminal 65-vm-wizard-paired.png wizard-paired \
  "docs@hermes-docs:~ \$ hermes watch setup" "hermes-docs — after pairing"

say "SET B: webapp Wear Kit page (Desktop-workspace surface)"
if $SSH 'test -d ~/.hermes/hermes-agent/apps/desktop/dist-webapp'; then
  WEBTXT=$($SSH 'export PATH=$HOME/.local/bin:$PATH
    hermes dashboard --stop >/dev/null 2>&1 || pkill -f "hermes dashboard" || true
    sleep 2
    setsid nohup hermes serve >/tmp/serve.log 2>&1 < /dev/null &
    sleep 8
    hermes webapp --no-open --skip-build 2>&1 | tail -4' || true)
  GPORT=$(printf '%s' "$WEBTXT" | sed -nE 's|.*http://127.0.0.1:([0-9]+).*|\1|p' | head -1)
  if [ -n "$GPORT" ]; then
    setsid nohup ssh $SSHOPTS -o ServerAliveInterval=30 -N \
      -L 127.0.0.1:19120:127.0.0.1:"$GPORT" docs@127.0.0.1 >/dev/null 2>&1 < /dev/null &
    WEBFWD=$!; sleep 2
    playwright-cli goto "http://127.0.0.1:19120/webapp/launch$(printf '%s' "$WEBTXT" | sed -nE 's|.*launch(#ticket=[^ "]*).*|\1|p' | head -1)" >/dev/null 2>&1; sleep 6
    # first run lands on the provider-setup screen — decline like any keyboard user
    playwright-cli eval "[...document.querySelectorAll('button,a')].find(b=>/choose a provider later/i.test(b.textContent))?.click()" >/dev/null 2>&1 || true
    sleep 3
    # Settings drawer → Plugins → wearos = the real config_schema form (the rich
    # plugin-config surface; the web dashboard card only says 'No dashboard tab')
    for step in "Open settings" "Plugins" "wearos"; do
      playwright-cli snapshot > "$WORK/.snap" 2>&1
      R=$(grep -o "button \"$step[^\"]*\" \[ref=[^]]*\]" "$WORK/.snap" | head -1 | sed -E 's/.*ref=([^]]*)\]/\1/' || true)
      [ -n "$R" ] && playwright-cli click "$R" >/dev/null 2>&1 && sleep 1 || \
        { echo "capture.sh: webapp step '$step' not found — see $WORK/.snap"; break; }
    done
    shot 66-vm-webapp-plugin-settings.png ""
    kill "$WEBFWD" 2>/dev/null || true
  else
    echo "capture.sh: no sign-in link from hermes webapp — limitation noted in README"
    printf '%s\n' "$WEBTXT"
  fi
else
  echo "capture.sh: webapp bundle missing (hermes webapp --build-only failed?) — 66 skipped"
fi

say "leave VM ready (dashboard serving again)"
$SSH 'export PATH=$HOME/.local/bin:$PATH; hermes serve --stop >/dev/null 2>&1 || true
      sleep 1
      setsid nohup hermes dashboard --no-open --skip-build --port 9119 >/tmp/dash.log 2>&1 < /dev/null & sleep 3' || true
echo "capture.sh: done"
