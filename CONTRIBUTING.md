# CONTRIBUTING.md — developer notes (read this before driving a watch)

AGENTS.md is the **install contract** (clone → working watch). This file is the **developer
contract**: the rules that only matter once you're changing code and proving it.

## The one hard rule: all testing runs through `wear-demo`, never `wear`

> Owner, 2026-10-30: *"from this point forward, all of your testing should be done in that
> wear demo agent."*

`wear` is the **owner's live agent** — their history, voice, cron deliveries, and inbox live
there. Every synthetic ask an agent fires (`"count from 1 to 20…"`, approval demos, failover
probes) must land on the **`wear-demo`** profile instead, a copy of `wear` prepared for
branch verification with its own `API_SERVER_KEY`.

### Set the demo agent up (gateway box, once)

```bash
cp -r ~/.hermes/profiles/wear ~/.hermes/profiles/wear-demo
# own key — never reuse wear's, so the gateway can't cross the streams (SPEC §12.2):
openssl rand -hex 24
#   → write API_SERVER_KEY=<new key> into profiles/wear-demo/.env
$EDITOR ~/.hermes/profiles/wear-demo/SOUL.md   # start from wear's, add: scratch/demo agent,
                                               # short answers, safe to reset any time
# reload the gateway so the new profile is served, then verify:
curl -s -o /dev/null -w "%{http_code}\n" http://<host>:8642/p/wear-demo/v1/health   # → 200
```

### Point the watch (or emulator) at it

```bash
scripts/setup.sh --api http://<host>:8642/p/wear-demo --token <wear-demo key>
# or on-device: Settings ▸ Gateways (add endpoint + token); seed every profile's key map with
scripts/setup.sh --agent-keys   # watchkit then serves GET /agent_keys to the watch too
```

The app itself is agent-agnostic — the profile lives in the endpoint path (`/p/wear-demo`) and
the per-profile bearer picks it server-side. Nothing in the APK is baked to `wear`.

### Manners on the demo agent

- Firefrequently, reset freely: sessions created by tests get archived/deleted without mercy.
- The owner's spot checks (Settings, voice-out dial, real conversation) still ride `wear` —
  when in doubt, `wear-demo`.
- If a dev session borrows the OWNER's watch, it points the endpoint at `wear-demo` for the
  session (`set_base`/`set_token`) and **hands it back on `/p/wear`** when the session ends —
  the owner's voice-out replies and cron deliveries belong to `wear`.

## Dev workflow quick card

```bash
source scripts/env.sh                                    # every shell (devkit lives in ../devkit/)
gradle --offline --console=plain :app:assembleDebug :app:testDebugUnitTest   # JVM suite must stay green
scripts/setup.sh --dry-run && scripts/setup.sh           # build → chunked install → config → health
scripts/circle-shot.sh /tmp/opencode/shots/x_c.png       # MANDATORY before ANY visual analysis
scripts/watchkit.sh status                               # helper daemon (mermaid/img/whisper + /stt/stream)
```

Screenshots from `adb screencap` are the SQUARE panel buffer — mask them (`circle-shot.sh`) or
they lie about the round glass (SPEC §14, owner rule).

## Watch connection gotchas (real hardware)

- Wireless-debugging `IP:PORT` **rotates every reconnect** — offline serial = reconnect to the
  NEW port, no re-pair needed (AGENTS.md §1).
- Re-apply dev hardening after every reconnect: `svc power stayon true` + `dumpsys deviceidle disable`.
- Installs ride the 14-chunk push recipe (one-shot pushes EOF on the BT-tethered link) —
  `setup.sh` already does this.
