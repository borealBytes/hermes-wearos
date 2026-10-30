# AGENTS.md — install this repo from an agent

You (an AI agent) can take this repo from clone to a working watch conversation. Follow this
file top to bottom; only interview the human for what you genuinely cannot detect (§4).

**What this is:** a standalone Wear OS app (Wear Compose, min SDK 30) that turns a Galaxy Watch
into a voice-first client for a self-hosted [Hermes](https://github.com/borealBytes)
gateway — streaming chat, approvals, images/mermaid, on-watch Vosk STT, body tools. The watch
talks directly to your gateway (`API_SERVER_KEY` bearer); **server/watchkit** (node, zero
framework) is an optional helper on the gateway box for mermaid renders, image scaling, whisper
STT fallback, and the health MCP. Architecture: `docs/SPEC.md` (read §1, §11 before changing
networking).

## 0. Developing? Read CONTRIBUTING.md first

If you are only INSTALLING, follow this file top to bottom. If you are DEVELOPING (changing
code, driving a watch to prove it), `CONTRIBUTING.md` holds the developer rules — starting with
the hard one: **every test ask rides the `wear-demo` profile, never the owner's `wear`** (it's a
copy of `wear` with its own key, made to be shouted at and reset). CONTRIBUTING.md also carries
the demo-agent setup recipe, the dev workflow quick card, and the circle-mask rule.

## 1. Prereqs (detect, don't assume)

| Need | Detect | Install |
|---|---|---|
| JDK 17 | `"$JAVA_HOME/bin/java" -version` / `java -version` | any JDK 17 (env.sh pins `/usr/lib/jvm/java-17-openjdk-amd64`) |
| Gradle + Android SDK + adb | `source scripts/env.sh; command -v gradle adb; ls "$ANDROID_HOME"` | `scripts/bootstrap.sh` — installs EVERYTHING folder-local into `../devkit/` (no sudo) |
| python3 | `python3 --version` | required (config parsing, whisper venv, image scaler) |
| node ≥ 18 (watchkit only) | `node --version` | only for `server/watchkit`; also `scripts/watchkit.sh setup` builds a folder-local venv |

`scripts/env.sh` is the single env entry point — source it in every shell you run for this repo
(it keeps SDK/gradle/AVD state in `../devkit/`, never touches `~`). Health check: `scripts/doctor.sh`.

**Emulator (optional, best proof-of-life without hardware):** `scripts/emulator.sh up` boots a
folder-local Wear OS 5 AVD (headless, x86_64). Everything works except Health Services/real GPS.

**Real watch (optional):** pair once over **wireless debugging** (Settings ▸ Developer options ▸
Wireless debugging ▸ Pair device with pairing code — pair the IP:PORT the dialog shows, NOT the
mDNS auto-entry):

```bash
adb pair 192.168.1.X:PORT      # then: adb connect 192.168.1.X:NEW_PORT   (from Pair dialog + its pairing code)
```

⚠️ **PORT ROTATION WARNING:** the `IP:PORT` serial of a Wi-Fi/tethered watch **changes every
reconnect**. `adb devices` showing a serial as *offline* almost always means the port rotated —
`adb disconnect` + `adb connect IP:NEW_PORT` (check the watch's wireless-debugging screen), do
not re-pair. Set `ANDROID_SERIAL` or `--serial` once you have the live one.

Dev hardening while an agent drives the watch (screen + doze are the two things that eat runs):

```bash
adb -s "$SERIAL" shell svc power stayon true          # screen stays on while plugged/tethered
adb -s "$SERIAL" shell dumpsys deviceidle disable     # no Doze mid-install (re-enable: enable)
```

**Chunked-push install (this is what makes installs survive REAL watches):** one-shot `adb
install` of the ~40 MB APK over the BT-tethered link frequently dies mid-push (EOF/socket
errors). Instead split into ≤14 chunks, push each with retries, reassemble on-device, and only
`pm install` after an md5 match:

```bash
split -n l/14 -d app/build/outputs/apk/debug/app-debug.apk /tmp/hw/part-
for p in /tmp/hw/part-*; do
  for i in 1 2 3; do adb -s "$SERIAL" push "$p" "/data/local/tmp/$(basename "$p")" && break || sleep 2; done
done
adb -s "$SERIAL" shell 'cat /data/local/tmp/part_* > /data/local/tmp/app.apk'
[ "$(adb -s "$SERIAL" shell md5sum /data/local/tmp/app.apk | cut -d' ' -f1 | tr -d '\r')" \
  = "$(md5sum app/build/outputs/apk/debug/app-debug.apk | cut -d' ' -f1)" ] \
  && adb -s "$SERIAL" shell pm install -r /data/local/tmp/app.apk
```

`scripts/setup.sh` does exactly this automatically for `host:port` serials.

## 2. Configure (file or flags — never edit app code)

```bash
cp watchos.config.example.json watchos.config.json   # gitignored; the ONLY thing to edit here
# gatewayLabel · apiEndpoints[] (first = DEFAULT entry, rest = failover fallbacks, SPEC §11)
# watchkitEndpoints[] · token · watchkitToken? · watchSerial?
```

**Multi-agent = per-profile keys (SPEC §12.2, this is the #1 gotcha):** a Hermes gateway multiplexes
every profile on one port, but `API_SERVER_KEY` resolves **per profile** and fails *closed* for a
named profile that has none — while `/v1/health` (unauthenticated) keeps answering 200, so the
in-app Test and the failover probe look fine before every real call 401s. If the gateway is on this
box, `scripts/setup.sh --agent-keys` reads every `profiles/<p>/.env` and pushes the whole map
(`set_agent_key`); otherwise the watch learns it from watchkit `GET /agent_keys`, and Settings ▸
Gateways ▸ Agent keys shows ✓/— per agent. Agents with no key are NOT broken forever — watchkit
reads `state.db` key-free, so their sessions still list — but asks need a key.

On the owner's box every profile shares **one login**: the same `API_SERVER_KEY` (the `default`
profile's, in `~/.hermes/.env`) sits in every `profiles/<p>/.env`, and watchkit's `GET /agent_keys`
serves the whole map (including `default`, read from `~/.hermes/.env`). Per-profile keys still work
everywhere else — the watch resolves agent key → gateway token → mirror token and re-learns at
Settings ▸ Gateways ▸ Agent keys ▸ ⟳.

Every value is also a `setup.sh` flag (`--api`/`--token`/`--serial`… see `--help`). The app
itself edits the same values at runtime: Settings ▸ Gateways (all in-app, nothing baked).

## 3. Build + install

```bash
source scripts/env.sh
scripts/setup.sh --dry-run     # prints the whole plan; run it first
scripts/setup.sh               # build → install (chunked for wireless) → push config → health check
```

Many screens (session search, fork/archive/delete, images, mermaid) ride the watchkit helper:
`scripts/watchkit.sh setup && scripts/watchkit.sh start` on the gateway box — `setup.sh` doesn't own it.
The first build needs network; once `../devkit/` is warm, `gradle --offline` is the fast lane.

Setup pushes config via the **debug-only** `DevAskReceiver` (extras: `set_token`, `set_base`,
`set_watchkit_base`, `set_watchkit`) — chain `set_base` LAST-endpoint-first so the DEFAULT entry
lands first (URL edits fold into the registry by prepending). Re-runnable: every step is
overwrite-or-skip. Tests: `gradle :app:testDebugUnitTest` (must stay green, JVM-only, no device).

## 4. Interview the human ONLY for unknowables

- **Gateway URLs** — if a Hermes gateway runs on THIS machine, check Tailscale first:
  `tailscale status --json | jq -r '.Self.DnsName'` → your FQDN (use `http://<fqdn>:8642`;
  MagicDNS resolves `.ts.net` only inside the tailnet). LAN: `ip -4 addr` for the local IP.
  Remote gateway: ask for both URLs (tailnet + LAN fallback).
- **Token** — same machine: `grep API_SERVER_KEY ~/.hermes/profiles/wear/.env` (setup.sh
  auto-detects this). Elsewhere: ask; it's that profile's `API_SERVER_KEY`.
- **Watch present or emulator?** — `adb devices`; zero devices + no watch → run the emulator
  path and report that instead of stalling on hardware.

Do NOT ask about: build flags, ports (8642 API / 8651 watchkit / 9119 dashboard are fixed
defaults), where files go — everything else is detectable or in `docs/SPEC.md`.

## 5. Wear agent persona (optional but the fun part)

The watch talks to the `wear` profile (`/p/wear`). That Hermes profile should carry the watch
persona in `~/.hermes/profiles/wear/SOUL.md` — a ready example ships here:

```bash
scripts/setup.sh --install-soul    # copies docs/wear-SOUL.example.md in (backs up any existing SOUL.md)
```

It teaches short replies, unicode math, `![desc](/abs/path.png)` image lines (the watch renders
them via watchkit `/img`), CONFIRM-line approvals, and `bot-chat:wear` cron delivery.

## 6. Verify (all three must pass before you claim success)

1. **Health:** `curl http://<api-endpoint>/p/wear/v1/health` (or `/health`) → HTTP 200 from the
   dev machine AND the watch badge flips 🏠/🌐 in Settings ▸ Gateways (● winner dot).
2. **One ask:** `adb -s "$SERIAL" shell am broadcast -n dev.hermes.wearos/dev.hermes.wearos.DevAskReceiver -e text "what is 12 times 12"`
   → streamed answer lands (watch screen + `logcat -s HermesWear` shows `run accepted` → final).
3. **Screenshot proof:** `adb -s "$SERIAL" exec-out screencap -p > verify.png` shows the answer
   (or `scripts/verify-chat.sh` for the whole ask+capture+logcat dance in one command).

## 7. Troubleshooting

| Symptom | Cause → fix |
|---|---|
| `adb push` EOF / "failed to push" | Flaky wireless link → chunked push recipe (§1) or just `setup.sh` |
| device shows *offline* | Port rotated (§1 warning) → `adb disconnect` + `adb connect IP:NEW_PORT` |
| Watch answers then goes silent between asks | Doze — `dumpsys deviceidle disable`, `svc power stayon true` (§1) |
| 401 from gateway | Wrong profile token → `set_token` the **wear** profile's key (per-profile keys are a thing) |
| One agent works, others 401 on every call (Test still says ✅) | That profile has no `API_SERVER_KEY` in its `.env` — `setup.sh --agent-keys` (on a gateway box it also SEEDS missing profile keys from `~/.hermes/.env`, the one-login convention), or Settings ▸ Gateways ▸ Agent keys ▸ ⟳ learn (SPEC §12.2) |
| 401 from watchkit but gateway fine | watchkit has its OWN bearer (`server/watchkit/config.json token`) — set `watchkitToken`/`set_watchkit` |
| tailnet entry always fails at home | Expected while the watch is off-tailnet: failover lands LAN in ~2.5 s (SPEC §11.2) — that's working |
| No mermaid/images/whisper | watchkit daemon down → `scripts/watchkit.sh start` (check `status`/`logs`) |
| A session row "vanished" after long-press | It was 🗃 **archived**, not lost — Sessions ▸ 🗃 archived chip ▸ hold the row ▸ ♻️ unarchive |
| `--offline` build fails on a fresh clone | Run once **online** to populate the devkit, then go offline |
| `Toast`/UI glitch in screenshots | debug-only DevAskReceiver ran without the app open — launch the app first |

## 8. Where things live

- `docs/SPEC.md` — full architecture + decisions (the ONLY design doc; §11 explains endpoints/failover).
- `CONTRIBUTING.md` — developer rules: the `wear-demo` test-agent policy, dev workflow quick card,
  mask-before-analysis discipline, watch connection gotchas.
- `app/src/debug/java/dev/hermes/wearos/DevAskReceiver.kt` — every dev broadcast hook (the comment lists them).
- `server/watchkit/watchkit.mjs` — helper daemon; its header comment is the endpoint reference (also `scripts/watchkit.sh`).
- `scripts/` — `setup.sh` (this contract) · `env.sh` · `bootstrap.sh` · `doctor.sh` · `emulator.sh` ·
  `build.sh` · `install-watch.sh` · `verify-chat.sh` · `watch-health.sh` (body-tool perms) · `watchkit.sh`.
- License MIT (`LICENSE`). Not bundled: the Hermes gateway itself (server side) and the `wear` profile.
