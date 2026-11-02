#!/usr/bin/env bash
# setup.sh — one-command install for another machine (built for AI agents; contract in AGENTS.md).
#
#   scripts/setup.sh [--dry-run] [--config FILE] [--serial SERIAL] [--skip-build] [--no-device]
#                    [--gateway-label L] [--api URL]... [--watchkit URL]...
#                    [--token T] [--watchkit-token T] [--install-soul] [--agent-keys]
#                    [--tailnet-key TSKEY-…]
#
# Reads watchos.config.json (cp watchos.config.example.json first); flags override config.
# Flow: build APK → install on the watch (chunked+md5-verified push for wireless devices) →
# push token/gateways/tailnet-key via DevAskReceiver (debug builds) → health-check the
# gateway → next steps.
# Idempotent: every step is overwrite-or-skip, run it again freely. --dry-run prints the plan.
#
# NO hidden deps: bash (≥4.4) + python3 (config parse) + adb/gradle via scripts/env.sh
# (folder-local ../devkit when present, system PATH otherwise).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/env.sh" 2>/dev/null || true   # folder-local toolchain; soft: system adb/gradle still work

PKG=dev.hermes.wearos
RECV="$PKG/$PKG.DevAskReceiver"           # debug-build receiver: the dev-only config channel (S5 pairing replaces it)
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
PROFILE=wear                              # wear-agent profile segment (/p/wear)

DRY=0; SKIP_BUILD=0; NO_DEVICE=0; INSTALL_SOUL=0
CONFIG="$ROOT/watchos.config.json"
SERIAL=""; LABEL=""; TOKEN=""; WK_TOKEN=""
TAILNET_KEY=""; TAILNET_HOST=""
APIS=(); WKS=()

say()  { printf '\033[1;36m==\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mXX\033[0m %s\n' "$*" >&2; exit 1; }
plan() { printf '   + %s\n' "$*"; }
run()  { plan "$*"; [ "$DRY" = 1 ] || "$@"; }

# ── flags ────────────────────────────────────────────────────────────────────
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run)        DRY=1 ;;
    --config)         CONFIG="$2"; shift ;;
    --serial)         SERIAL="$2"; shift ;;
    --gateway-label)  LABEL="$2"; shift ;;
    --api)            APIS+=("$2"); shift ;;
    --watchkit)       WKS+=("$2"); shift ;;
    --token)          TOKEN="$2"; shift ;;
    --agent-keys)     AGENT_KEYS=1 ;;
    --watchkit-token) WK_TOKEN="$2"; shift ;;
    --tailnet-key)    TAILNET_KEY="$2"; shift ;;
    --skip-build)     SKIP_BUILD=1 ;;
    --no-device)      NO_DEVICE=1 ;;
    --install-soul)   INSTALL_SOUL=1 ;;
    -h|--help)        sed -n '3,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)                die "unknown flag: $1 (see --help)" ;;
  esac
  shift
done

# ── config file (watchos.config.json; example ships placeholders) ────────────
CFG_LABEL=""; CFG_TOKEN=""; CFG_WK_TOKEN=""; CFG_SERIAL=""
CFG_TAILNET_KEY=""; CFG_TAILNET_HOST=""
CFG_APIS=(); CFG_WKS=()
if [ -f "$CONFIG" ]; then
  say "config: $CONFIG"
  # python3 emits eval-safe bash assignments (shlex.quote ⇒ no injection from the file)
  eval "$(python3 - "$CONFIG" <<'PY'
import json, shlex, sys
c = json.load(open(sys.argv[1]))
def s(k): return shlex.quote(str(c.get(k, "")))
def a(k): return "(" + " ".join(shlex.quote(str(u)) for u in c.get(k, [])) + ")"
print(f"CFG_LABEL={s('gatewayLabel')}")
print(f"CFG_TOKEN={s('token')}")
print(f"CFG_WK_TOKEN={s('watchkitToken')}")
print(f"CFG_SERIAL={s('watchSerial')}")
print(f"CFG_APIS={a('apiEndpoints')}")
print(f"CFG_WKS={a('watchkitEndpoints')}")
t = c.get("tailnet") or {}
print(f"CFG_TAILNET_KEY={shlex.quote(str(t.get('authKey','')))}")
print(f"CFG_TAILNET_HOST={shlex.quote(str(t.get('hostname','')))}")
PY
  )"
else
  warn "no $CONFIG — flags/placeholders only (cp watchos.config.example.json watchos.config.json)"
fi
# flags win over config
LABEL="${LABEL:-$CFG_LABEL}"; TOKEN="${TOKEN:-$CFG_TOKEN}"
WK_TOKEN="${WK_TOKEN:-$CFG_WK_TOKEN}"; SERIAL="${SERIAL:-$CFG_SERIAL}"
TAILNET_KEY="${TAILNET_KEY:-$CFG_TAILNET_KEY}"; TAILNET_HOST="${TAILNET_HOST:-$CFG_TAILNET_HOST}"
[ ${#APIS[@]} -gt 0 ] || APIS=("${CFG_APIS[@]}")
[ ${#WKS[@]}  -gt 0 ] || WKS=("${CFG_WKS[@]}")

# token auto-detect: a same-machine gateway keeps it in the wear profile env file
if [ -z "$TOKEN" ] && [ -f "$HOME/.hermes/profiles/$PROFILE/.env" ]; then
  TOKEN="$(grep '^API_SERVER_KEY=' "$HOME/.hermes/profiles/$PROFILE/.env" 2>/dev/null | cut -d= -f2- || true)"
  [ -n "$TOKEN" ] && say "token auto-detected from ~/.hermes/profiles/$PROFILE/.env"
fi
if [ ${#APIS[@]} -eq 0 ]; then
  APIS=("http://hermes.tailnet.ts.net:8642" "http://192.168.1.50:8642")
  warn "no apiEndpoints configured — using example placeholders (edit $CONFIG)"
fi
case "$TOKEN" in REPLACE*|"") warn "token missing/placeholder — the watch will 401 until it's set" ;; esac

say "plan: gateway '${LABEL:-hermes}'"
printf '   api      : %s\n' "${APIS[@]}"
printf '   watchkit : %s\n' "${WKS[@]:-<none — optional, needed for mermaid/images/whisper>}"
printf '   token    : %s\n' "$([ -n "$TOKEN" ] && echo "set (${#TOKEN} chars)" || echo "<missing>")"
printf '   tailnet  : %s\n' "$([ -n "$TAILNET_KEY" ] && echo "key set (${#TAILNET_KEY} chars)${TAILNET_HOST:+ as $TAILNET_HOST}" || echo "<no key — tunnel stays off (dev-direct)>")"

# ── adb ──────────────────────────────────────────────────────────────────────
ADB="$(command -v adb 2>/dev/null || true)"
[ -n "$ADB" ] && [ -x "$ADB" ] || ADB="${ANDROID_HOME:-}/platform-tools/adb"
ADB_X=("$ADB"); [ -n "$SERIAL" ] && ADB_X+=(-s "$SERIAL")   # rebuilt in resolve_serial once the serial is known
adb_set_serial() { ADB_X=("$ADB"); [ -n "$SERIAL" ] && ADB_X+=(-s "$SERIAL"); }

resolve_serial() {
  [ "$NO_DEVICE" = 1 ] && return 0
  [ -n "$SERIAL" ] && { say "device: $SERIAL (--serial)"; adb_set_serial; return 0; }
  if [ "$DRY" = 1 ]; then plan "(adb devices — pick the only attached device; override with --serial)"; return 0; fi
  [ -x "$ADB" ] || die "adb not found — install platform-tools (AGENTS.md §prereqs) or pass --no-device"
  local devs; devs=$("$ADB" devices | tail -n +2 | awk '$2=="device"{print $1}' || true)
  local n; n=$(printf '%s' "$devs" | grep -c . || true)
  case "$n" in
    1) SERIAL="$devs"; say "device: $SERIAL (only device attached)"; adb_set_serial ;;
    0) die "no adb device — pair the watch (AGENTS.md §watch) or boot the emulator (scripts/emulator.sh up); or re-run --no-device to only build" ;;
    *) die "multiple devices — pass --serial (attached: $(echo "$devs" | tr '\n' ' '))" ;;
  esac
}

# ── build ────────────────────────────────────────────────────────────────────
if [ "$SKIP_BUILD" = 0 ]; then
  say "build :app:assembleDebug"
  if [ "$DRY" = 1 ]; then plan "(cd $ROOT && ${GRADLE:-gradle} --console=plain :app:assembleDebug)"
  else ( cd "$ROOT" && "${GRADLE:-gradle}" --console=plain :app:assembleDebug ); fi
fi
if [ "$DRY" = 0 ] && [ ! -f "$APK" ]; then die "APK missing ($APK) — drop --skip-build or build first"; fi

# ── install ──────────────────────────────────────────────────────────────────
install_plain() { # emulator / USB: one-shot install is reliable there
  run "${ADB_X[@]}" install -r "$APK"
}
install_chunked() { # wireless (host:port serial): the recipe that survives REAL watches.
  # Tethered/BT links drop big single pushes (adb "EOF"-style failures); small chunks that
  # each get retried, reassembled on-device and md5-VERIFIED before pm install, don't.
  local tmp="$ROOT/build/setup-push"
  if [ "$DRY" = 1 ]; then
    plan "split -n l/14 -d $APK → $tmp/part-*"
    plan "${ADB_X[*]} push <each chunk> /data/local/tmp/hermes_part_NN   (×≤14, 3 retries each)"
    plan "${ADB_X[*]} shell cat /data/local/tmp/hermes_part_* > /data/local/tmp/hermes.apk"
    plan "${ADB_X[*]} shell md5sum /data/local/tmp/hermes.apk   (must equal local md5, else abort)"
    plan "${ADB_X[*]} shell pm install -r /data/local/tmp/hermes.apk && cleanup"
    return 0
  fi
  mkdir -p "$tmp"; rm -f "$tmp"/part-*
  local size; size=$(wc -c < "$APK")
  # -n l/14 = ≤14 line-aware chunks; a too-binary APK falls back to byte chunks.
  split -n l/14 -d "$APK" "$tmp/part-" 2>/dev/null || split -b "$(( size / 14 + 1 ))" -d "$APK" "$tmp/part-"
  local chunks=( "$tmp"/part-* )
  say "chunked install: $size bytes → ${#chunks[@]} chunk(s) over $SERIAL"
  local c dest attempt ok
  for c in "${chunks[@]}"; do
    dest="/data/local/tmp/hermes_part_$(basename "$c" | sed 's/^part-//')"
    ok=0
    for attempt in 1 2 3; do
      if plan "${ADB_X[*]} push $c $dest" && "${ADB_X[@]}" push "$c" "$dest" >/dev/null 2>&1; then ok=1; break; fi
      warn "chunk $(basename "$c") attempt $attempt failed — retrying in 2 s"; sleep 2
    done
    [ "$ok" = 1 ] || die "chunk $c never pushed after 3 attempts — check the link, re-run setup.sh"
  done
  "${ADB_X[@]}" shell "cat /data/local/tmp/hermes_part_* > /data/local/tmp/hermes.apk"
  local want got
  want=$(md5sum "$APK" 2>/dev/null | awk '{print $1}') || want=$(md5 -q "$APK")   # linux / mac
  got=$("${ADB_X[@]}" shell md5sum /data/local/tmp/hermes.apk | tr -d '\r ' | sed 's/\/data.*//')
  [ -n "$want" ] && [ "$got" = "$want" ] || die "md5 mismatch on device (got '$got', want '$want') — re-run setup.sh"
  say "md5 verified on device: $got"
  "${ADB_X[@]}" shell pm install -r /data/local/tmp/hermes.apk </dev/null
  "${ADB_X[@]}" shell "rm -f /data/local/tmp/hermes_part_* /data/local/tmp/hermes.apk"
}

if [ "$NO_DEVICE" = 0 ]; then
  resolve_serial
  if [ "$DRY" = 1 ]; then
    plan "(hardening: adb shell svc power stayon true && adb shell dumpsys deviceidle disable)"
    plan "(serial 'host:port' → chunked path; emulator-NNNN/USB → direct install)"
    install_plain
    install_chunked
  else
    "${ADB_X[@]}" shell svc power stayon true                     # dev: keep the watch screen awake
    "${ADB_X[@]}" shell dumpsys deviceidle disable >/dev/null 2>&1 || true   # dev: no Doze while we install
    case "$SERIAL" in
      *[0-9]:*[0-9]) say "wireless device ($SERIAL) — chunked md5-verified install"; install_chunked ;;
      *)             say "installing directly ($SERIAL)"; install_plain ;;
    esac
  fi
fi

# ── push token + gateways via DevAskReceiver (debug builds) ──────────────────
# Extras the receiver understands (app/src/debug/.../DevAskReceiver.kt):
#   set_token <gateway API key> · set_base <url/p/wear> · set_watchkit_base <url> · set_watchkit <wk token>
# There is NO gateway-list extra → chain set_base: base_url edits FOLD into the registry and
# unknown endpoints PREPEND, so push LAST endpoint first — the final push (APIS[0]) lands as the
# DEFAULT entry with the rest behind it in failover order (SPEC §11.1).
broadcast() { # <extra-name> <value>
  plan "${ADB_X[*]} shell am broadcast -n $RECV -e $1 '$2'"
  [ "$DRY" = 1 ] && return 0
  "${ADB_X[@]}" shell am broadcast -n "$RECV" -e "$1" "'$2'" >/dev/null
  sleep 1
}
if [ "$NO_DEVICE" = 0 ]; then
  say "push gateways + token (${#APIS[@]} api, ${#WKS[@]} watchkit)"
  i=$(( ${#APIS[@]} - 1 ))
  while [ "$i" -ge 0 ]; do broadcast set_base "${APIS[$i]}/p/$PROFILE"; i=$((i-1)); done
  i=$(( ${#WKS[@]} - 1 ))
  while [ "$i" -ge 0 ]; do broadcast set_watchkit_base "${WKS[$i]}"; i=$((i-1)); done
  [ -n "$TOKEN" ]    && broadcast set_token "$TOKEN"
  # EMBEDDED TAILNET (SPEC §11.4): the key rides the same debug channel as the tokens. The
  # watch's own tsnet node enrolls with it and every call thereafter goes through the tunnel.
  [ -n "$TAILNET_KEY" ] && broadcast set_tailnet_key "$TAILNET_KEY"
  # --agent-keys (SPEC §12.2): a multiplexed gateway resolves API_SERVER_KEY PER PROFILE, so one
  # token only works for the profile it came from. When the gateway lives on THIS box we can read
  # every profiles/<p>/.env and push the whole map — otherwise "switch agent" 401s on every agent
  # that lacks a key (and /v1/health, being unauthenticated, never warns you).
  if [ "${AGENT_KEYS:-0}" = "1" ]; then
    # One-login convention (docs above): on the OWNER's box every profile shares the default
    # profile's API_SERVER_KEY from ~/.hermes/.env. Agents created AFTER first setup miss that
    # copy — the gateway then 401s them closed while unauthenticated /health still answers 200,
    # and "learn from watchkit" correctly reports no key (owner 2026-10-29). So: seed the missing
    # line (the gateway re-reads .env live — verified 2026-10-29), then push the whole map.
    HOME_KEY="$(grep '^API_SERVER_KEY=' "$HOME/.hermes/.env" 2>/dev/null | head -1 | cut -d= -f2- | tr -d "\"'" || true)"
    for envf in "$HOME"/.hermes/profiles/*/.env; do
      [ -f "$envf" ] || continue
      prof="$(basename "$(dirname "$envf")")"
      akey="$(grep '^API_SERVER_KEY=' "$envf" 2>/dev/null | cut -d= -f2- | tr -d '\"'"'"'"' || true)"
      if [ -z "$akey" ] && [ -n "$HOME_KEY" ]; then
        printf '%s\n' "API_SERVER_KEY=$HOME_KEY" >> "$envf"
        akey="$HOME_KEY"
        echo "  agent key: $prof (seeded from ~/.hermes/.env)"
      fi
      [ -n "$akey" ] || continue
      echo "  agent key: $prof"
      broadcast set_agent_key "${prof}=${akey}"
    done
  fi
  [ -n "$WK_TOKEN" ] && broadcast set_watchkit "$WK_TOKEN"
  plan "${ADB_X[*]} shell am start --ez dev_no_intro 1 -n $PKG/.MainActivity"
  [ "$DRY" = 1 ] || "${ADB_X[@]}" shell am start --ez dev_no_intro 1 -n "$PKG/.MainActivity" >/dev/null
fi

# ── wear SOUL seed (optional: --install-soul) ────────────────────────────────
if [ "$INSTALL_SOUL" = 1 ]; then
  SRC="$ROOT/docs/wear-SOUL.example.md"; DST="$HOME/.hermes/profiles/$PROFILE/SOUL.md"
  if [ ! -f "$SRC" ]; then warn "missing $SRC"
  elif [ ! -d "$HOME/.hermes/profiles/$PROFILE" ]; then
    warn "no ~/.hermes/profiles/$PROFILE here — copy $SRC to the GATEWAY machine's wear profile manually"
  elif [ -f "$DST" ] && cmp -s "$SRC" "$DST"; then say "wear SOUL.md already current — skip"
  elif [ -f "$DST" ]; then
    BAK="$DST.bak-$(date +%Y%m%d-%H%M%S)"
    say "wear SOUL: backing up existing → $BAK, installing example"
    run cp "$DST" "$BAK"; run cp "$SRC" "$DST"
  else
    say "wear SOUL: installing example → $DST"
    run cp "$SRC" "$DST"
  fi
fi

# ── gateway health check (from THIS machine — proves the URLs you configured) ─
if [ ${#APIS[@]} -gt 0 ]; then
  say "gateway health check"
  for ep in "${APIS[@]}"; do
    if [ "$DRY" = 1 ]; then plan "curl -s -m 5 $ep/p/$PROFILE/v1/health"; continue; fi
    code=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$ep/p/$PROFILE/v1/health" 2>/dev/null || echo 000)
    [ "$code" = "000" ] && code=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$ep/health" 2>/dev/null || echo 000)
    case "$code" in
      000) warn "$ep — no reply from this machine" ;;
      2*)  say "$ep — HEALTHY (HTTP $code)" ;;
      *)   warn "$ep — HTTP $code (answering, but unexpected; 401 = token, 404 = path)" ;;
    esac
  done
fi

say "done. Next steps:"
echo "  1. On the watch: open Hermes — Settings ▸ Gateways lists '${LABEL:-hermes}' with endpoint probe dots (● winner)."
echo "  2. Settings ▸ Body tools: flip the switches you want (all default OFF)."
echo "  3. Press & HOLD the orb → say something → the streamed answer arrives. A tap opens the keyboard instead."
echo "  4. watchkit extras (mermaid/images/whisper/health MCP) need server/watchkit on the gateway:"
echo "       cp server/watchkit/config.example.json server/watchkit/config.json  # set host+token"
echo "       scripts/watchkit.sh setup && scripts/watchkit.sh start"
echo "  5. Verify end-to-end: scripts/verify-chat.sh (ask via DevAskReceiver + screenshots)."
echo "  Re-run setup.sh any time — it is idempotent."
