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
scripts/setup.sh --agent-keys   # the whole per-profile key map rides set_agent_key (lane re-learns per D17)
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
scripts/parakeet.sh                                      # install the streaming parakeet engine (watchkit-owned GGUF)
```

Screenshots from `adb screencap` are the SQUARE panel buffer — mask them (`circle-shot.sh`) or
they lie about the round glass (SPEC §14, owner rule).

## The alignment rule: one worktree owns the loop (owner rule, 2026-10-31)

While a worktree is under test, **it must own both ends of the loop**: the watchkit serving
port 42651 is started from THIS worktree's `scripts/watchkit.sh`, and the APK on the watch is
byte-identical to THIS worktree's build. A stale daemon (or one from another checkout — the
watch is indifferent to which repo answered) plus a newer APK is a phantom test harness:
features get proven against the wrong code. Owner caught this exact drift — main-checkout
watchkit squatting on 42651 while a splash-video worktree build rode the watch. Check both
before any hardware claim:

```bash
scripts/watchkit.sh status && ps aux | grep watchkit.mjs | grep -v grep   # pid path = THIS worktree
P=$(adb -s "$SERIAL" shell pm path dev.hermes.wearos | tr -d '\r' | sed 's/package://')
[ "$(adb -s "$SERIAL" shell md5sum "$P" | cut -d' ' -f1 | tr -d '\r')" = \
  "$(md5sum app/build/outputs/apk/debug/app-debug.apk | cut -d' ' -f1)" ] && echo ALIGNED
```

If the daemon is from another checkout: kill its pid, `scripts/watchkit.sh start` here. If the
md5s differ: reinstall (chunked push, AGENTS.md §1) before trusting a single screenshot or
logcat line. A stray `watchkit.mjs mcp` child belongs to the gateway (stdio, no port) — leave
it; only the 42651 listener must be yours.

## THE MEDIA RULE: pixels ship with the PR (owner rule, 2026-11-01)

**The last step before pushing a PR is re-aligning the demo media** — every screenshot, the demo
film, and the GIF. They are not decoration; they are how the world sees how good this thing is,
and they must match the features that actually ship. A PR that adds or changes a user-visible
feature and does not refresh its pixels is incomplete. The whole media pipeline, the feature⇄
screenshot⇄beat⇄README ledger, and the verification gates live in [`docs/DEMO.md`](docs/DEMO.md) —
that file is the plan; this section is the discipline to never skip it.

Before every push:

1. **Update `docs/DEMO.md` first** (§2 feature ledger + §3 README checklist + §10 changelog):
   list every feature the PR adds/changes/removes, with a screenshot row AND a video beat. This
   file is the source of truth for what the demo can show — code changes update it before pixels.
2. **Re-shoot every changed feature** (`wear-demo` only, per the hard rule + DEMO.md §4 capture
   recipes; keep each existing filename, circle-mask it, then pass the vision gate). Re-cut the
   film + GIF so every §2 row is `shot ✓` and appears in the cut list.
2b. **Version everything you ship**: bump repo-root `VERSION` with `scripts/release.sh <x.y.z>`
   (app, watchkit, and the demo pipeline all read it — never hand-edit a version string), and
   cut the film/GIF from `tools/demo/` so their filenames + on-screen bug carry `v<VERSION>`.
   The mp4 master stays OUT of the repo (owner scores it and uploads to YouTube); the versioned
   GIF + the screenshots are the tracked pixels.
3. **Walk the gates** (DEMO.md §8): coverage ledger complete, vision review per `<img>`, privacy
   pass, captions `refreshed`, tests green.
4. **Purge the old media generation** (DEMO.md §9) before pushing so the branch carries exactly
   one generation of tracked pixels.

Miss this and the README silently lies about the app (this repo has been burned by reversions
before — SPEC §16.3). Treat stale media as a failing test, because it is one.

## The build policy: PRs ship an APK, merges roll @rc, tags are the release (owner doctrine, 2026-11-06)

**Every PR to main gets an installable preview APK built by CI** (`pr-apk.yml`): artifact
`wearos-pr<N>` + an auto-comment on your PR with the `gh run download` line and the sha256 —
signed with a throwaway CI debug key, so a preview is a try-it (uninstall a release/@rc
build first), not an upgrade path. **Every merge to main replaces the rolling GitHub
prerelease on the moving tag `@rc`** (`apk-release.yml` → `preview` job), signed with the real
release keystore exactly like the v-tags — current main, installable, rewritten under you by
the next merge. **A `v*` tag is the only immutable Release** (the `release` job, unchanged;
the full table + verify commands: SPEC §18). This adds nothing to your PR checklist — the
lanes fire on their own; just don't be surprised when your PR shows an APK download comment,
and don't force-push `@rc` by hand.

## Watch connection gotchas (real hardware)

- Wireless-debugging `IP:PORT` **rotates every reconnect** — offline serial = reconnect to the
  NEW port, no re-pair needed (AGENTS.md §1).
- Re-apply dev hardening after every reconnect: `svc power stayon true` + `dumpsys deviceidle disable`.
- Installs ride the 14-chunk push recipe (one-shot pushes EOF on the BT-tethered link) —
  `setup.sh` already does this.
