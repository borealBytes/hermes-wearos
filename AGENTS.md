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
install` of the large APK (~105 MB since the embedded tailnet node joined it) over the
BT-tethered link frequently dies mid-push (EOF/socket
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
# tailnet{ hostname, authKey } — the watch's own tsnet node (SPEC §11.4, see below)
```

**EMBEDDED TAILNET — the tunnel IS the transport (SPEC §11.4, owner doctrine "tailnet only,
always"):** the app ships a real Tailscale node (`tsnet` bound via gomobile, ~17 MB AAR at
`app/libs/hermes-tailnet.aar`); every REST/SSE/WebSocket call rides it through a loopback
SOCKS5 shim — no LAN path, no phone-tailnet dependency. What an installer must do:

1. **Get an authkey from the tailnet admin console** (Keys ▸ generate): *reusable* and
   *non-ephemeral* (an ephemeral key dies with the first enrollment and a reinstall then
   strands the app in NEEDS_AUTH). Never commit it, never echo it into logs.
2. `scripts/setup.sh --tailnet-key tskey-auth-…` (or the `tailnet{}` config block; also
   `-e set_tailnet_key` over the debug channel). The node enrolls once as `tailnet.hostname`
   (suffix it per owner — `hermes-wearos-pilot` — every Re-enroll creates a NEW admin-console
   node row to prune); identity then lives in app data, the key is not needed again.
3. **Gateway box:** run `tailscale` + enable MagicDNS; nothing else. ufw needs NO rule for
   tunnel traffic — tailscaled installs its own `ts-input` nft chain that ACCEPTS everything
   arriving on `tailscale0` (verified live 2026-11-02: a hand-added ufw allow rule saw zero
   packets because tailscale already handled it). Watchkit listens on **:42651**.
4. Dev escape hatch when you must bypass the tunnel to debug: `-e tailnet_on off` → plain
   sockets (this is a dev switch, not a user mode).

**Multi-agent = per-profile keys (SPEC §12.2, this is the #1 gotcha):** a Hermes gateway multiplexes
every profile on one port, but `API_SERVER_KEY` resolves **per profile** and fails *closed* for a
named profile that has none — while `/v1/health` (unauthenticated) keeps answering 200, so the
in-app Test and the failover probe look fine before every real call 401s. If the gateway is on this
box, `scripts/setup.sh --agent-keys` reads every `profiles/<p>/.env` and pushes the whole map
(`set_agent_key`); otherwise the watch learns each agent's key **over its private lane** (pairing
v2 / D17 — watchkit's old `GET /agent_keys` answers 410), gated by the grant law: the paired
agent's key always flows, a sibling agent needs a grant on the Wear Kit pane. Settings ▸
Gateways ▸ Agent keys shows ✓/— per agent. Agents with no key are NOT broken forever — watchkit
reads `state.db` key-free, so their sessions still list — but asks need a key.

On the owner's box every profile shares **one login**: the same `API_SERVER_KEY` (the `default`
profile's, in `~/.hermes/.env`) sits in every `profiles/<p>/.env`. Per-profile keys still work
everywhere else — the watch resolves agent key → gateway token → mirror token and re-learns at
Settings ▸ Gateways ▸ Agent keys ▸ ⟳ (over the lane).

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

The tailnet AAR is a COMMITTED artifact — rebuild only when `tailnet/go` changes:
`scripts/tailnet-build.sh` (folder-local Go + NDK r27 in `../devkit/`; ABIs = the app's
`armeabi-v7a`+`x86_64`; `-tags=version_android` is NOT optional — without it tsnet dies at
Start with `netlinkrib: permission denied`, Android SELinux forbids app netlink route reads).

**Plugin tests (python, pytest):** `python3 -m pytest hermes-plugin/tests/` — stdlib + pytest
only (plugin rule 10), no device, no network: the suite sandboxes `HERMES_HOME` into a tmp
dir, fakes the Tailscale API, and speaks real HTTP to an ephemeral claim door. Hermes host
modules resolve from `~/.hermes/hermes-agent` or `$HERMES_AGENT_ROOT`. Keep green alongside
the Gradle tests; admission re-check: `hermes plugins validate hermes-plugin/ --install-deps`.

Setup pushes config via the **debug-only** `DevAskReceiver` (extras: `set_token`, `set_base`,
`set_watchkit_base`, `set_watchkit`, `set_tailnet_key`, `tailnet_status`, `tailnet_on`,
`tailnet_reenroll`, plus the pairing-v2 ceremony hooks: `pair_knock <handle>` (runs the exact
UI knock; the code lands in logcat), `approve 1`, `unpair 1`). Chain `set_base` LAST-endpoint-first so the DEFAULT entry
lands first (URL edits fold into the registry by prepending). Re-runnable: every step is
overwrite-or-skip. Tests: `gradle :app:testDebugUnitTest` (must stay green, JVM-only, no device).

## 4. Interview the human ONLY for unknowables

- **Gateway URLs** — if a Hermes gateway runs on THIS machine, check Tailscale first:
  `tailscale status --json | jq -r '.Self.DNSName'` → your FQDN (use `http://<fqdn>:8642`;
  MagicDNS resolves `.ts.net` only inside the tailnet — and since §11.4 the watch reaches it
  THROUGH its own node, so a tailnet URL is the only one that works; LAN literals are
  reachable through the tunnel too if you must use one).
- **Token** — same machine: `grep API_SERVER_KEY ~/.hermes/profiles/wear/.env` (setup.sh
  auto-detects this). Elsewhere: ask; it's that profile's `API_SERVER_KEY`.
- **Tailnet authkey** — undetectable by design: it lives in the admin console, not on the
  box. Ask the human to mint/reuse one (reusable + non-ephemeral, §2). If `watchos.config.json`
  already carries `tailnet.authKey`, you have it — never print the value anywhere.
- **Watch present or emulator?** — `adb devices`; zero devices + no watch → run the emulator
  path and report that instead of stalling on hardware.

Do NOT ask about: build flags, ports (8642 API / 42651 watchkit / 9119 dashboard are fixed
defaults), where files go — everything else is detectable or in `docs/SPEC.md`.

## 5. Wear agent persona (optional but the fun part)

The watch talks to the `wear` profile (`/p/wear`). That Hermes profile should carry the watch
persona in `~/.hermes/profiles/wear/SOUL.md` — a ready example ships here:

```bash
scripts/setup.sh --install-soul    # copies docs/wear-SOUL.example.md in (backs up any existing SOUL.md)
```

It teaches short replies, unicode math, `![desc](/abs/path.png)` image lines (the watch renders
them via watchkit `/img`), CONFIRM-line approvals, and `bot-chat:wear` cron delivery.

## 6. Verify (all four must pass before you claim success)

1. **Tunnel:** `adb -s "$SERIAL" shell am broadcast -n dev.hermes.wearos/dev.hermes.wearos.DevAskReceiver -e tailnet_status 1`
   → logcat shows `tailnet_status phase=ACTIVE … gateway <ip> online`. Not ACTIVE? the
   `tailnet_log` ring lines right after it name the reason (needs a key? node down? engine
   error?). The ask cannot work before this — the tunnel is the only transport (SPEC §11.4).
2. **Health:** `curl http://<api-endpoint>/p/wear/v1/health` (or `/health`) → HTTP 200 from the
   dev machine AND the watch badge flips 🏠/🌐 in Settings ▸ Gateways (● winner dot).
3. **One ask:** `adb -s "$SERIAL" shell "am broadcast -n dev.hermes.wearos/dev.hermes.wearos.DevAskReceiver -e text 'what is 12 times 12'"`
   → streamed answer lands (watch screen + `logcat -s HermesWear` shows `run accepted` → final).
   ⚠ **Quote trap:** the text value MUST survive to the device shell quoted (note the outer
   double quotes above — same trick `setup.sh` uses). Unquoted, `am` takes only the first
   word and your "answer" proves nothing about streaming a real message.
4. **Screenshot proof:** `adb -s "$SERIAL" exec-out screencap -p > verify.png` shows the answer
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
| Ask times out but `phase=ACTIVE` | the tunnel is up but the leg to the gateway is not — dump `-e tailnet_status 1` and read `tailnet_log`: `socks dial …: connection was refused` = nothing listening (watchkit on **:42651** down? `scripts/watchkit.sh start`); `timed out`/no dial line = tailnet data path (from the gateway box: `tailscale status` — is the watch node online? `tailscale ping <it>`?) |
| `phase=NEEDS_AUTH` | key spent/identity wiped — mint a fresh REUSABLE key, `-e set_tailnet_key` (Settings ▸ Gateways ▸ Tailnet works too, Re-enroll needs two taps) |
| Watch node absent from `tailscale status` on the gateway box | node never enrolled (NEEDS_AUTH above) or enrolled under a different tailnet — compare `tailnet_status` self FQDN with the gateway's `tailscale status --json` |
| `am broadcast -e text` only delivered the first word | quote trap (§6.3) — outer double quotes, inner single |
| Repeated `hermes-wearos*` rows in the admin console | each Re-enroll enrolls a NEW node — expected; prune the stale rows there |
| No mermaid/images/whisper | watchkit daemon down → `scripts/watchkit.sh start` (check `status`/`logs`) |
| Voice-in ▸ Gateway shows "not installed" | engine weights missing on the gateway box → `server/watchkit/.venv/bin/python stt_stream.py --check` names the exact gap; default engine = config `stt_stream_engine` (use `"parakeet"` for live partials; `"whisper"` = final-only) |
| Offline STT says "model missing" | Settings ▸ Voice in ▸ offline model ▸ ⬇ install (gateway-cached, resume-safe; `scripts/get-vosk-model.sh` remains the adb path) |
| A session row "vanished" after long-press | It was 🗃 **archived**, not lost — Sessions ▸ 🗃 archived chip ▸ hold the row ▸ ♻️ unarchive |
| `--offline` build fails on a fresh clone | Run once **online** to populate the devkit, then go offline |
| `Toast`/UI glitch in screenshots | debug-only DevAskReceiver ran without the app open — launch the app first |

## 8. Where things live

- `docs/SPEC.md` — full architecture + decisions (the ONLY design doc; §11 explains endpoints,
  §11.4 the embedded tailnet node — the transport;
  §19 the voice-out milestone + its amendment log).
- `hermes-plugin/voiceout.py` — voice-out canonical prefs (`voiceout.yaml`, rev'd two-way merge), the
  measured-`ref_rate` card store, and the tier-aware `synthesize()` contract (§19; stdlib-only tests).
- `hermes-plugin/tts_air.py` — NeuTTS Air engine: pinned installer into a private venv (rule 17),
  subprocess runner, size-gated gated-HF fetch, stdlib time-scale; registers into voiceout when
  `register_if_ready()` finds it installed.
- `.github/workflows/plugin-sync.yml` — publishes `borealBytes/hermes-wearos-plugin`, the
  catalog-pinned subtree mirror of `hermes-plugin/`. Zero human commits; plugin code is edited
  ONLY here. Needs the `MIRROR_PAT` secret (fine-grained, single-repo, contents RW) — without
  it this job reddens and harms nothing.
- `tailnet/go/` — the Go side: tsnet node + CONNECT-only SOCKS5 shim (gomobile surface
  `start/stop/statusJSON/logsJSON/wipeState`). `tailnet/go/cmd/smoketest` = host-side proof.
- `scripts/tailnet-build.sh` — rebuilds `app/libs/hermes-tailnet.aar` from `tailnet/go`
  (committed artifact; see §3 for when/why).
- `CONTRIBUTING.md` — developer rules: the `wear-demo` test-agent policy, dev workflow quick card,
  mask-before-analysis discipline, watch connection gotchas.
- `scripts/parakeet.sh` — one-shot installer for watchkit's streaming STT engine (transcribe.cpp +
  its own parakeet GGUF); idempotent, folder-local, no GPU required.
- `app/src/debug/java/dev/hermes/wearos/DevAskReceiver.kt` — every dev broadcast hook (the comment lists them).
- `server/watchkit/watchkit.mjs` — helper daemon; its header comment is the endpoint reference (also `scripts/watchkit.sh`).
- `scripts/` — `setup.sh` (this contract) · `env.sh` · `bootstrap.sh` · `doctor.sh` · `emulator.sh` ·
  `build.sh` · `install-watch.sh` · `verify-chat.sh` · `watch-health.sh` (body-tool perms) · `watchkit.sh`.
- License MIT (`LICENSE`). Not bundled: the Hermes gateway itself (server side) and the `wear` profile.
