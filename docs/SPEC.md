# Hermes Wear OS — Design Spec v3

Standalone, first-party-quality Wear OS app turning a Galaxy Watch Ultra into a voice-first client for a
Hermes gateway, reachable **only over the tailnet** (LAN fallback at home). The watch is an edge device:
it hears you, speaks back, senses your body, and shows the agent's live work — **all intelligence and
heavy processing runs on the gateway**. No cloud, no companion phone app, no third-party servers.

- Marketing name: **Hermes Wear OS** · repo/git: `hermes-wearos`
- License: MIT (our code)

## 1. Architecture

```
Galaxy Watch Ultra (Wear OS, 480×480 round) — Hermes Wear OS app
  Vosk offline STT · low-confidence → audio→gateway whisper · Android TTS (default ON)
  Streaming rich replies: markdown · code-collapsed · MERMAID→image · scaled images
  Session-aware TALK button (continue / new / last / choose) + full agent & session browser
  Health Services body tools — per-tool opt-in switches
  Watch notifications: jobs done, approvals waiting (inline Approve/Deny)
  ALL configuration editable in-app — nothing baked into the APK
      │
      │ Bearer token · HTTPS where TLS applies · connection badge in UI
      │   🌐 tailnet: myhost.example.ts.net:8642  (DEFAULT entry, SPEC §11 — WireGuard-
      │      encrypted; from the watch it is reachable only over a tailnet-routed link today:
      │      the paired phone's tailnet over BT/tether — no Tailscale client exists for Wear OS)
      │   🏠 home LAN: 192.168.1.50:8642   (watch's own Wi-Fi — the FALLBACK; endpoint
      │      failover (§11.2) lands here ~2.5 s after the tailnet entry proves unreachable)
      ▼
Hermes gateway (your gateway box) — Hermes API server :8642
  · OpenAI-compat + Runs API: SSE streaming, durable idempotency, approvals,
    session chat/stream, /p/<profile>/ multi-profile routing (key bound per profile)
  · `wear` profile = default watch agent (persona in SOUL.md)
  · (later, optional) watchkit helper: mermaid render, image scale-to-watch, whisper
    STT fallback, health snapshot cache + MCP, proactive event queue, diag sink
```

**Server surface notes (verified live, Hermes 0.21.7):**
`/v1/capabilities` advertises `run_submission, runs_idempotency (durable, 24 h), run_status,
run_events_sse, run_stop, run_steer, run_approval_response, approval_events, session_chat_streaming,
model_options, session_resources`. Per-profile routing requires `gateway.multiplex_profiles=true` and
each profile's own `API_SERVER_KEY`. Idempotency replay proven (`Idempotency-Key` → same `run_id`,
`replayed: true`). Watch v1 dials `/p/wear/v1/...` directly; **watchkit is deferred** until v1 features
need it (mermaid, image scaling, STT fallback, proactive queue).

## 2. Network paths & leak guarantees

| Situation | Path | Badge |
|---|---|---|
| BT range of paired phone (OnePlus 12) | Watch traffic proxies through phone → phone's Tailscale → tailnet | 🌐 tailnet |
| Home Wi-Fi | Watch → LAN direct, TLS (added in M3), mDNS-discovered | 🏠 LAN |
| Public Wi-Fi / LTE, phone absent | Watch's own network — can't reach tailnet | ⛔ offline (parked) |

Guarantees everywhere: Bearer token on every request · TLS/pinning where applicable (tailnet path is
WireGuard-encrypted end to end) · **no Google/cloud STT ever** (Vosk offline; fallback → your gateway) ·
no analytics, no third-party endpoints, no Telegram in the reply path · **tailnet-only paranoid switch**
disables 🏠 and hard-fails rather than touch the internet.

## 3. Sessions: continuity is the default

The Gemini complaint: every press = new chat, no history. Hermes Wear OS flips it:

| Gesture / screen | Behavior |
|---|---|
| Tap the orb (default) | RECORD on the **current `wear` session — context continues**; hands-free VAD ends itself on a pause, a second tap mid-speech cancels; header shows `● wear · 12 min` vs `○ wear · new` |
| Drag the orb any of 8 ways | The **orb dial** (§12 D1–D8): keyboard · mute · record-to-gateway · voice notes · slash commands · chats · sessions · agents. Replaces every satellite button — the orb is the only control on the chat screen |
| "start new session" (voice or chip) | Fresh wear session; old lands in history instantly |
| Chats tab → Agents | Every profile on the gateway (`wear`, `research`, `weather`, …) via the gateway |
| Agent → Sessions | **Cross-profile** list: current agent + the DESKTOP (`default`) profile's sessions newest-first (cap 30), profile chips `all / <agents> / 💻 computer`, ⌚ on watch-made ids; **NEW SESSION pinned top**; tap any row → continue from the wrist (foreign-profile row also swaps the agent) |
| Idle rollover | Continuous until explicitly new or 6 h idle; resume is silent with summary continuity |

## 4. Settings & pairing (all on-device)

**Onboarding:** (1) mDNS auto-discovery of the gateway on home Wi-Fi (no typing) · (2) PC runs
`hermes watch pair` (or equivalent CLI) → 6-digit one-shot code (5-min TTL) · (3) watch taps digits →
receives token + endpoints, Keystore-encrypted. Fallbacks: manual entry with keyboard/paste, `adb push`
config for dev. (No QR scan — Ultra has no usable camera.)

**Settings tree:** Gateways (registry rows, endpoint winner dots, add/edit/delete, row-tap select,
the live link readout of §11.6) ·
Connection (endpoints, test, tailnet-only, cert pin, badge) · Agents & sessions
(default agent, idle rollover) · Voice-in (STT mode: on-watch only / +auto-fallback / gateway) ·
Voice-out (master ON/OFF + pinned chat-screen mute, rate, chunking, TTS engine) · Display (mermaid,
image quality, collapsed code, font scale, launch video) · Body tools (every switch + test button, digest cadence) ·
Storage (cache meter, cap, TTL, pinned sessions) · Notifications (jobs, approvals, haptics, quiet hours) ·
Advanced (diag ring buffer + upload, poll interval, dev options).

## 5. Features

### Conversation
| # | Feature |
|---|---|
| F1 | Voice (Vosk streaming partials, push-to-talk) or typed; auto audio→gateway whisper on low confidence |
| F2 | SSE streaming replies (server provides keepalive + tool-progress events) |
| F3 | Markdown-lite: headings, bold/italic, lists, blockquotes, compact tables |
| F4 | Code blocks collapsed → tap-expand, monospace, syntax-hidden |
| F5 | Mermaid: fence detected → server-rendered watch-sized image, crown-zoom/pan, long-press source |
| F6 | Inline images: fetched, re-scaled to viewport (WebP), placeholder→image, tap full-screen |
| F7 | Unicode math enforced by `wear` profile prompt (no LaTeX) |
| F8 | Speak-while-streaming: sentence-chunked Android TTS starts ~1–2 s in; pinned mute; tap interrupt; volume boost; on-device |
| F9 | Approve/Deny/Reply cards from Hermes approval events (approve-once, spoken + shown) |
| F10 | Long turns → jobs; watch notification on completion; exact resume on reopen |
| F11 | Session model per §3 (continuity + browser + last-session jump) |
| F12 | Reliability: `Idempotency-Key` retries (server-side dedupe — never double-runs), timeouts, last-state resume |
| F13 | Budgeted Keystore cache: active session pinned; others evicted after 7 days unseen; ~100 MB cap (media evicts first); agents/titles persist; full offline read incl. images; no offline send (v2) |
| F14 | Instant local answers (`pc health`, `gateway health`) without waking the agent |
| F15 | Slash-command overlay (dial lane) — command catalog + usage-ranked recents, voice-or-type filter, every entry mapped to a real action (§12.3) |
| F16 | Edit & resend **forks** the session (`/api/sessions/{id}/fork`) and truncates the local transcript at the edited turn — desktop branching semantics, no gateway change (§12.5) |
| F17 | Multi-agent switching that WORKS: per-agent `API_SERVER_KEY`s learned from the gateway box, sessions preloaded per agent through watchkit (§12.2 — the bug this branch exists for) |
| F18 | Session search: title/id offline, message BODIES via watchkit's FTS5 read of `state.db` (`GET /search`), voice-or-type query (§12.4) |
| F19 | Session management: LONG-PRESS a session → ␂ fork-into-this-agent-and-open, or 🗑 delete with a real double-check — for EVERY profile, key or not (§12.6) |
| F19 | Record → gateway STT lane (dial 3:00): watch records WAV, gateway transcribes; backend configurable (branch 2 — §13.4) |
| F20 | Voice-note ring buffer (dial 4:30): last N on-device transcripts, long-press copy, tap-paste into the draft (branch 2 for the on-device-vs-gateway quality swipe — §13.4) |

### Body tools (all OFF until switched)
W1 `watch_heart_rate` · W2 `watch_steps`/rings · W3 `watch_sleep` · W4 `watch_spo2` · W5 `watch_workout` ·
W6 `watch_location` (watch GPS) · W7 `wear_health_digest` auto-attach (opt-in, battery-aware) ·
W8 plumbing: watch → cache → MCP tools on the `wear` profile · W9 live pull-style calls (v2) ·
phone actions (`phone_*`) → future companion, toggle screen reserved.

### Watch UX
U1 onboarding/pairing (mDNS + 6-digit code) · U2 full in-app settings · U3 watch notifications with inline
Approve/Deny + deep-link · U4 Ask tile + pending-approval complication · U5 proactive event queue (SSE
while open; batched Doze-friendly poll while closed) · U6 crown scroll, swipe-back, true-black OLED,
screen-on only while TALKing/speaking · U7 diagnostics ring buffer · U8 **orb dial** — 8 drag directions,
remappable, contextual rise for the crowded lower arc (§12.1; the only touch target on the chat screen) ·
U9 voice-or-type field — one component, 🎙 Vosk partials or system IME, used by search + the command
overlay (§12.4).

### Server side
S1 Hermes API server on tailnet bind with token (DONE) · S2 `wear` profile + watch persona (DONE) ·
S3 tailnet access (DONE via tailnet bind; `tailscale serve` hostname+TLS optional once enabled in admin
console) · S4 mDNS beacon + LAN TLS (M3) · S5 pairing CLI (`6-digit code` → token handout) (M1/M3) ·
S6 watchkit helper — mermaid render, image scaling, whisper fallback, snapshot cache + MCP, event queue
 (comes online as features need it; v1 talks to the API server directly) · S7 watchkit `GET /agent_keys` —
 every profile's `API_SERVER_KEY` read from `profiles/<p>/.env`, so the watch LEARNS keys instead of
 typing them (§12.2) · S8 watchkit `GET /search?profile=&q=` — FTS5 message-body search over `state.db`
 (§12.4).

## 6. Reference repos → what's taken

| Repo | License | Take |
|---|---|---|
| hermes-watch | MIT | retry/dedupe/job semantics, last-answer resume, watch-prompt port, whisper worker, diag style |
| herald | MIT | opt-in tool switches, inline-approve notifications, session/agent browsing shape, protocol research docs |
| ClawWatch | AGPL-3 | reimpl only: Vosk pipeline, mic perms, TTS volume boost; Galaxy Watch ADB lore |
| TGwear | GPL-3 | patterns only: crown scroll, round chat layout, notification quick-reply |

## 7. Milestones

| M | Deliverable | Done when |
|---|---|---|
| **M0** ✅ | Toolchain + gateway live | doctor green; APK builds; API server on tailnet; `wear` profile answers; idempotent replay proven (all verified 2026-10-23) |
| M1 | Emulator text chat | pairing flow end-to-end; streamed <3 s; kill-mid-ask → no double-run; cache survives kill; settings tree usable |
| M2 | Voice + media | speak→partials→answer; mermaid→image in-frame; speech mid-stream; mute instant; low-confidence→whisper |
| M3 | Real watch (human in loop) | dev mode + wireless ADB; 🌐 and 🏠 both answer; pcap shows only the gateway host as destination |
| M4 | Session model + browser | interrupt → one-tap continue; resume desktop-started session; last-session jump |
| M5 | Body tools + approvals + notifications | approve from wrist; real health answers; toggles respected |
| M6 | Ship-shape | tile/complication, signing, README, one-command install |

## 8. Installs

**Sudo, once:** Android Studio · KVM group add (`usermod -aG kvm <user>`) · udev rules only if USB adb misbehaves.
**Physical, human:** watch dev-mode, pairing codes, every APK install.
**No root, folder-local (`../devkit/`):** Android SDK (platform 35, build-tools 35), Gradle 8.13, JDK pinned
(system 17), gradle caches, AVDs, whisper models, Node server venvs.
**App deps:** Compose-for-Wear + Horologist (Apache-2), OkHttp/SSE, kotlinx-serialization,
androidx.health.services, **vosk-android (Apache-2)**, Android TTS built-in.

## 9. Defaults (locked with owner)

Voice ON + pinned mute · default agent `wear`, browse-to-switch others · session continuity default,
new-session a gesture, sessions always resumable · in-app settings + 6-digit pairing (no APK-baked config)
· no Telegram reply channel · cache 7 d/100 MB pinned-active · mermaid+images server-rendered, crown-zoom ·
body-digest off by default · offline-send later · Vosk small-en-us · v1 approvals = tool approvals only.

## 10. Known risks

1. Phone-proxied tailnet on Galaxy Wear — flakiest link; M3 proves/hardens it.
2. Samsung Health data exposure to third-party watch apps (sleep stages especially) — verify at M4.
3. ~~API-server surface gaps~~ — resolved: `/v1/capabilities` + Runs/approval/session routes cover the spec.
4. Watch-profile token cost — wear profile prompt currently loads a huge AGENTS.md (35k tokens/turn);
   trim for the watch context (M1).
5. Vosk accuracy on watch mic — mitigated by day-one gateway fallback.

## 11. Gateways, endpoint failover & the tailnet (owner multi-gateway epic, 2026-10-24)

The owner runs MULTIPLE Hermes gateways (gateway box + a VPS box) and wants them all reachable —
including over Tailscale from anywhere. **Owner directive, locked: the tailnet URL is the DEFAULT
entry for the home gateway; the home LAN URL is the fallback.**

### 11.1 Gateway registry (data)

`domain/GatewayModel.kt` — pure, unit-tested. One gateway = `{id, label, apiEndpoints[],
watchkitEndpoints[], token}`. Endpoints are ordered bases WITHOUT the `/p/<agent>` suffix — the
profile segment stays derived exactly as before (ProfileLogic), so one gateway serves every agent.
Persisted under NEW DataStore keys `gateways_json` + `gateway_selected`; the legacy keys
(`base_url` / `token` / `watchkit_url`) are **never removed — they are the live MIRROR** every
consumer (ChatSession/Watchkit/pusher/UI) reads, rewritten by `syncMirror()` whenever the selected
gateway or the winning endpoint changes. Upgrade is additive and rollback-safe.

First run / untouched stock defaults seed `hermes` with

```
api:      [http://hermes.tailnet.ts.net:8642,  http://192.168.1.50:8642]
watchkit: [http://hermes.tailnet.ts.net:42651, http://192.168.1.50:42651]
```

— placeholder tailnet identity FIRST (MagicDNS publishes `.ts.net` names → CGNAT 100.64.0.0/10
addresses routable only inside your tailnet). A migration
from explicit LEGACY values keeps a CUSTOM host first (what worked today works first); stock
defaults migrate to the tailnet-first order. Settings edits fold into the registry: a known
endpoint keeps its position (agent switches re-set base_url constantly and must never reorder),
an unknown host prepends. Token = the gateway's `API_SERVER_KEY`; the watchkit bearer stays a
separate global setting.
- **Registry can never lose a gateway (owner 2026-10-29: *"I clicked the tux-LAN one and the other
  one totally disappeared"*):** every mutator (upsert/delete/folds) rewrites the whole `gateways_json`
  from what it read, so three silent-prune paths got closed: **(a)** `AppSettings.gateways()` keeps the
  last good decode and returns it when a NON-blank blob fails to parse (a parse blip reading as empty
  was the one way a later save could overwrite N gateways with 1), and `ensureGateways()` migrates
  ONLY on a blank blob — a present-but-hurt registry is never overwritten; **(b)** the gateway EDITOR
  carries endpoints beyond its two visible API rows hidden in `GwDraft.extraApis/extraWks` and merges
  them on save — before this, saving an edit on a 3-path gateway silently dropped the 3rd path (the
  editor shows a small `+N api / +N wk kept` line so the carry is visible); **(c)** `watchkit_url`
  edits (dev hook or Settings) now fold into the selected gateway's wk list exactly like `base_url`
  folds into the api list (`GatewayModel.foldWatchkit`) — before, they hit only the mirror, so a wk
  fallback could never be added without re-adding the whole gateway.

### 11.2 Endpoint failover (`domain/Failover.kt` + `net/EndpointResolver.kt`)

Ordered probes with a 2.5 s leash: `GET {endpoint}/p/<agent>/v1/health`, falling back to bare
`GET {endpoint}/health` — first 2xx wins. **This is what makes "tailnet default" true AT HOME
TODAY even though the watch is not on the tailnet:** from the home Wi-Fi the tailnet connect dies
in ~2.5 s, LAN answers instantly, and LAN carries the session (`endpoint winner: …` in logcat).
Winner is remembered for the session (sticky, revalidated every 5 min); a fallback winner gives
the primary a short-backoff retry (60 s, one probe per window — a dead primary never costs the
user per-request); a mid-session I/O failure dethrones the winner and the gateway's next endpoint
is probed and retried **with the same `Idempotency-Key`** (never a double run). When every probe
is dead the stale winner stands (requests fail naturally with a clear error; no null-pointer
storm). **What it dials when everything is dead is the last endpoint that ANSWERED, not the
primary** (`lastKnownGood` / `fallbackPointer`, §11.5 — the primary is often a name the watch can
never resolve). State machine is pure (fake prober + fake clock, 19 tests); the resolver is the
thin android shell + 2.5 s OkHttp prober. **Routing is per GATEWAY, never per agent:** one machine
is bound per gateway and the winner is the endpoint, while the agent only swaps the `/p/<profile>`
suffix — so every agent shares one route and no profile can pick its own or poison another's
(proved live 2026-10-29: identical winner and identical failure text for `comfyui` and `wear`).

### 11.3 Gateway UI + the IME rule

Settings ▸ **Gateways**: a row per gateway (label, selected marker, per-endpoint probe dots —
green ● winner / red ✕ failed / gray ○ unprobed), inline add/edit/delete editor (label, API URL +
backup, watchkit URL, token), row tap = SELECT → re-probe + reload chats/agents/sessions through
the SAME `switchAgent` plumbing (the runOnUi toast gate). The agent-switcher dialog header shows
the current gateway label (`on myhost`).

**IME RULE — NO FIELD EVER DEPENDS ON ENTER** (owner: "typing a URL, enter made a space, no way
to submit"): every single-line input is `singleLine` + `ImeAction.Done/Send` wired to the SAME
action as its explicit button, and the explicit Save/Send button is always visible — chat compose
(`send ➤`), settings (Save), gateway editor (Save/Cancel), rename (Save), prompt editor (send ➤).
A misbehaving IME can slow input, never strand it.

### 11.4 In-app tailnet (tsnet) — STATUS: SHIPPED (2026-11-02, lane (a))

**Doctrine (owner, 2026-11-02): "tailnet only, always."** The watch's own embedded node is THE
transport — the §11.2 tailnet-first/LAN-fallback doctrine is retired as a *transport* story
(endpoint failover between listed endpoints survives, but every one of them is reached THROUGH
the tunnel; LAN-direct exists only as the `-e tailnet_on off` dev escape hatch).

**Shipped shape (lane (a) from the research below):** `tailnet/go/` — a Go shim wrapping
`tailscale.com/tsnet` v1.104.4 + a CONNECT-only SOCKS5 server on loopback (tsnet has no native
SOCKS listener), exposed via `gomobile bind` as the COMMITTED `app/libs/hermes-tailnet.aar`
(rebuild: `scripts/tailnet-build.sh`). All four OkHttp clients install one
`TailnetRoute.SELECTOR` proxy — non-loopback URI ⇒ SOCKS to `127.0.0.1:<port>` ⇒ hostnames
resolve inside the tailnet (MagicDNS truth). Keeper: permanent `specialUse` FGS (NOT dataSync —
its 6 h/day cap on API 34 would tear the tunnel down nightly) + partial wake lock + boot
receiver, so the tunnel predates the first ask. Health doctrine: node Running is NOT health —
the gateway peer answering is (`domain/TailnetState` phase rules; DEGRADED covers
node-up/gateway-down). Enrollment: reusable, non-ephemeral authkey (an ephemeral key strands a
reinstalled app in NEEDS_AUTH); identity persists in `filesDir/tailnet`; Re-enroll = wipe +
NEW admin-console node.

**Android app-sandbox survival (all measured live on the Wear emulator, 2026-11-02 — none of
this guessable from docs):**

1. `route ip+net: netlinkrib: permission denied` — SELinux denies apps the NETLINK_ROUTE
   socket, so Go's `net.Interfaces` always fails and tsnet dies at Start. `feature/androidbin`
   is excluded from `android && cgo` builds (gomobile!), so we register the same
   synthetic-interface fallback ourselves via `netmon.RegisterInterfaceGetter` (addresses from
   kernel UDP route selection, permitted) + blank-import `androiddns` (dnsproxyd resolver).
2. `panic: no safe place found to store log state` — logpolicy's fallback chain ends at a `/tmp`
   that doesn't exist in the sandbox and PANICS in an internal goroutine (fatal under gomobile).
   `TS_LOGS_DIR` is honored only if the dir ALREADY EXISTS (LogsDir Stats, never creates) —
   create first, then set (JVM-side `Os.setenv` mirrors both before the Go runtime snapshots env).
   `TS_NO_LOGS_NO_SUPPORT=1`: no log.tailscale.com upload from a personal watch node.
3. `-tags=version_android` on the AAR build (the official Android client's build profile).
4. **SOCKS5 parser off-by-one:** the domain length IS the 5th CONNECT byte
   (`[VER,CMD,RSV,ATYP,DOMLEN,…]`); reading it as a separate byte behind a 5-byte header
   misreads `t` as length 0x74 and strands every domain CONNECT (IP targets pass — the bug
   hid behind working loopback tests until a live ask). Parser accumulates any TCP
   segmentation: Android libcore is NOT curl.

**Gateway-box truth (measured with nft counters):** tailscaled installs its own `ts-input`
chain that ACCEPTS all traffic arriving on `tailscale0` — **no ufw rule is needed for tunnel
traffic** (a hand-added `allow from 100.64.0.0/10` rule counted zero packets; the SYN was
already accepted upstream of it). The only real requirements: tailscaled running + MagicDNS on
+ services listening (`:8642`, watchkit `:42651`). "Connection refused" through the tunnel =
service not listening; timeout = node data-path problem — read `tailnet_log`.

**Known costs:** APK 40 → ~105 MB (Go runtime × 2 ABIs; packaging squeeze open:
`useLegacyPackaging`); permanent silent ongoing notification; battery impact to be measured on
real metal (milestone 5). `TS_DEBUG_DISCO=1` in the engine env turns on disco tracing in
`tailnet_log` when endpoint discovery is the suspect.

**Research history (2026-10-24, kept as decision record).** Plan was
`github.com/tailscale/tsnet-go` embedded via gomobile. **VERIFIED 2026-10-24: that repository
does not exist** (GitHub 404; Go module proxy `not found` for both `tailscale/tsnet-go` and
`tailscale/tsnet`). The real landscape:

| Option | Shape | Fit here |
|---|---|---|
| `tailscale/tailscale/tsnet` (in-repo) | server-style Go lib; no mobile story, no SOCKS5 | wrap-able: our own Go shim = tsnet node + local SOCKS5 server (`Dial` through the node resolves `.ts.net` via MagicDNS) → `gomobile bind` → .aar → `Proxy(socks5 127.0.0.1:port)` per OkHttp client. Feasible, but: Go+wireguard ≈ +15–30 MB APK across our pinned ABIs, NDK/cgo 32-bit toolchain, authkey/usercode flow, battery/Doze story all ours to build (est. ≥ a full day, not the 1.5 h toolchain budget) |
| `tailscale/libtailscale` + `tailscale-android` | the OFFICIAL userspace story: Go .so (gomobile `libgojni.so` + NDK) driven by Kotlin `Tailscale.kt`, traffic via **VpnService** (per-app "non-system" VPN, one user consent dialog) — what the Tailscale app itself ships | closest to "shipped": Kotlin bridge exists to copy, needs our auth UX (browser → `tailscale.com/key`) + Wear validation + `RECEIVE_BOOT`/Doze work; SDK is built-from-source (no clean maven AAR) |
| `leondgarse/tailscale_mobile` | community fork exposing tsnet as c-shared + C API | JNI/JNA glue + same size/ABI/auth caveats; no SOCKS5 |
| exit-node fallback (ships TODAY, §2 badge 🌐) | BT-tethered phone holds the tailnet; watch default-routes through it | zero APK size, works; costs the phone radio; the endpoint system already handles it — LAN/phone-absent failover picks the tailnet URL when reachable |

**Stop decision (2026-10-24, superseded 2026-11-02):** the owner picked lane (a) and it shipped —
see the SHIPPED block above; the authkey flow is `-e set_tailnet_key`/Settings ▸ Tailnet, and
the "Phase B artifact" this section was is now `tailnet/go/` + `TailnetService`.


### 11.5 What "tailnet endpoint" can and cannot mean on the watch (owner bug, 2026-10-29)

Owner report: *"it said it couldn't connect over tailnet to gateway when I was on wifi. I even had
the phone on tailnet so I thought it should have worked. It was in non-`wear` agent profile… want
to make sure lan/tailnet routing is automatic in all agents."* Reproduced on the SM-L705U by
selecting a gateway whose only API endpoint is the MagicDNS name:

```
submit failed (Unable to resolve host "labhost…ts.net": No address associated with hostname)
```

**Root cause, measured on the device:** `adb shell ping <tailnet FQDN>` → `ping: unknown host`.
A `.ts.net` name is resolved by MagicDNS, which only exists on machines that are IN the tailnet,
and the watch has no tailscaled (§11.4 is unresearched, not shipped). So the phone's tailnet
membership is irrelevant to the watch: separate network stack, no MagicDNS, no route to the
100.64.0.0/10 range from an ordinary LAN. A tailnet entry in the endpoint list is therefore a
**contingency that only pays off when the watch itself gets a tailnet route** — the BT-tethered
exit-node path (§11.4's last row) or a real tsnet node — and on its home Wi-Fi the watch lives on
the LAN entries. Nothing agent-related: the failure text and the winning endpoint were byte-identical
for `comfyui` and `wear`, because failover is bound per gateway (§11.2).

Two real bugs surfaced while reproducing it, both fixed:

- **A network hiccup used to re-aim the watch at the endpoint that can never resolve.** On a mid-session
  I/O failure `advanceFrom` dethrones the winner, probes the rest once, and — when nothing answered —
  fell back to `effective()`, i.e. the PRIMARY. With a tailnet primary that pointer is a hostname DNS
  cannot answer, so the mirror was rewritten to it, the retry died on DNS, and the owner's screen said
  "…tailca7ee1.ts.net…" for what was just a LAN blip. Now `Failover` remembers `lastKnownGood` (the last
  endpoint that answered, never cleared) and hands callers `fallbackPointer()`, which returns **null**
  when nothing is known better than the base already in use — the caller keeps its endpoint and retries
  (which is what the function always promised in its own doc comment).
- **The error text was OkHttp's, so it was unactionable and misleading** (`domain/RouteReport.kt`,
  pure + 9 tests): DNS miss / connect timeout / refused are translated into a wrist-length line that
  names the fix ("tailnet name won't resolve on the watch — Settings ▸ Gateways ▸ add this Wi-Fi's LAN
  IP", "no answer from 3 endpoints…"), counts endpoints only from the failover machine's own probe
  record, and leaves every non-routing failure's own message intact. The gateway and watchkit **Test**
  buttons and the learn-keys chip ride the same translation, so one truth is told everywhere.
  `-e del_gw <id>` (delete + re-land the selection) ships with it: the cleanup half of `add_gw`, so a
  gateway handed to the watch for a demo can be removed without touching the IME.

**Operator consequence:** put at least one address the watch can route to *from wherever it is* first
in the list. At home that is the LAN IP; away from home, a tailnet entry only helps through a
tethered phone that holds the tailnet — which is why the app's job here is to say plainly, in one
line, which route it needs.


### 11.6 The route follows the link (owner: *"make it flawless for discovering what's available network wise"*)

Until now failover was **demand-driven**: the app learned the network had moved by failing one ask,
so a winner learned on home Wi-Fi stayed "fresh" for up to 5 minutes while the watch was already on
a tether where that address means nothing. ConnectivityManager knows the instant the link changes —
so the watch now re-picks its route before the owner taps anything.

| Piece | Job |
|---|---|
| `domain/NetworkWatch.kt` (pure, 12 tests) | the POLICY: which observations are a route change. Identity = **interface** + link kinds + vpn + resolvers, so a connect's callback BURST (available → capabilities → validated → dns) costs one probe round; a change suppressed by the debounce window is remembered as a **debt** and paid by the next settled report (flaps cost one probe, real changes are never dropped); `describe()` renders the whole truth in one line with no SSID and no address literals |
| `net/NetworkMonitor.kt` | the framework shell: ONE `registerDefaultNetworkCallback` on the Application graph (never an Activity — the change we care about happens screen-off in a pocket), `NET_CAPABILITY_VPN` read via `VpnTransportInfo` because the capability constant is @SystemApi, plus `ACCESS_NETWORK_STATE` in the manifest |
| `Failover.invalidate()` | dethrone the winner, keep `lastKnownGood` as the pointer, clear the primary backoff (a new link is a fair chance for the tailnet entry), and make the next `ensure` probe instead of riding a TTL learned on a dead network |
| `EndpointResolver.onNetworkChanged()` | invalidate **both** machines (the ask and the images/mermaid/whisper/sessions path ride different lists) and re-probe them under one lock, then the winner callback rewrites the mirror as usual |
| Settings ▸ **Gateways** header line | the field-test readout: `wifi(wlan0) · validated`, `bt(bt-pan) · not validated`, `wifi(tun0) · vpn · validated · ts-resolver`, plus the two-tier tailnet clause of finding 3 (`… unreachable from here` when measured dead, `… no tailnet route on this device` when the device has no tunnel/MagicDNS and nothing has been measured) |

**Three things only measurement taught** (all now in the code as evidence rules, not opinions):

1. **The boot opening swallows everything, not just the first callback.** `start()` primes the
   readout with the current network, and the framework refines it ~44 ms later on an SM-L705U; that
   refinement looked like "the watch moved" and fired a second probe round on top of the boot probe.
2. **A `Network` object is not an identity.** The first build keyed networks on
   `System.identityHashCode(network)` — but ConnectivityManager constructs a **new `Network` object
   for every callback**, so one unchanged home Wi-Fi looked like a *new network* each time: the
   emulator re-routed **every 3 s, forever**. The stable identity is `LinkProperties.getInterfaceName()`
   (`wlan0` / `rmnet0` / `tun0` — which usefully names a tunnel too; `LinkProperties.getInterfaces()`
   would be nicer but is @SystemApi). Measured after the fix: **0 re-routes across 30 s of idling**
   on a stationary network, two per radio cycle (restore, then the validation flip), then silence.
3. **The tailnet clause has two tiers, and measured evidence outranks the structural one in BOTH
   directions.** The Wear emulator has no tunnel and no Tailscale resolver in its own
   `LinkProperties`, yet `.ts.net` resolves there because slirp forwards the HOST's resolver (this
   dev box is on the tailnet) — so a capability-only reading warned "can't resolve" on a device
   where it resolves fine. Tier 1 is measurement: `… — tailnet entries unreachable from here`,
   printed only when the failover machine holds a DEAD probe for a tailnet endpoint. Tier 2 is
   structural and worded as such — `… — no tailnet route on this device`, true of the emulator too,
   which borrows the host's route rather than having one. And if a tailnet endpoint **answered**, no
   clause prints at all: the emulator wins on exactly such an endpoint, and printing "no tailnet
   route" beside a `ts.net` winner said two opposite things (caught by reading the emulator's own
   Settings screen). Tier 2 exists because a LAN-first list never probes the tailnet entry at all —
   a probe round stops at the first 2xx — so an evidence-only clause would stay silent forever on
   the owner's watch, where the claim is nevertheless true.

**What this buys the tailnet question (§11.5):** the readout answers *"is my phone's tailnet reaching
the watch?"* on the wrist. It is `vpn`/`ts-resolver` when a tunnel is genuinely in front of the watch
(exit-node-for-LAN devices, or a future tsnet node, §11.4) and plain `wifi` when it is not — so the
claim is checked in 20 seconds instead of by bug report. That is also why the endpoint list should
carry the **tailnet IP** (`http://100.99.1.42:8642`), not only the MagicDNS name: forwarding to
100/10 is the reliable half of exit-node, resolving `.ts.net` for a tethered client is the flaky half.

**Verified:** emulator (deterministic) — Wi-Fi off → `network change: network lost · no network` →
re-route finds nothing → `route after network change: none — nothing answered`; Wi-Fi back →
`route restored · wifi(wlan0) · not validated` → `endpoint winner: …` **before any tap**; the
validation flip re-routes once, then 0 further events across 30 s idle. Real SM-L705U — monitor
registers, one re-route at boot, and the Gateways header reads
`wifi(wlan0) · validated` — plain, because its tailnet entry was never probed and tier 2 needs the gateway's endpoint list (which it now gets, so a LAN-first list still says `no tailnet route on this device`) (screenshots). Asks stay green
through the churn (`finish run=… error=false`), and routing is still per-gateway, so every agent
rides the new route without knowing it changed (§11.2).
**Not captured on hardware:** the watch-side radio cycle — cycling its Wi-Fi drops adb with the link
(Samsung also leaves wireless debugging off afterward), so the walk-out capture belongs to the
owner's own test: `adb logcat -s HermesWear | grep -E "network|route"`, then walk out of Wi-Fi
range and send one message — expect `network lost` → `route restored · bt(bt-pan)…` (or `no network`
when the tether is not carrying the watch, which is the exit-node finding in §11.5) and the winner
already re-picked by the time the ask is typed.

## 12. Orb dial, agent keys, session search (owner batch 3, 2026-10-26)

Owner's frame: *"would it simplify the interface even more to get all but the orb button off the
main screen?"* One control, nine directions (since 2026-10-29; eight until ✅ approvals joined the
default map), plus the two bugs that surfaced while using it
(agent switch 401s, sessions never preloading) and session search. Verified on the owner's
hardware this batch: **Galaxy Watch Ultra (SM-L705U), API 36 / Android 16, 480×480 @ 340dpi** —
byte-identical to `BottomLayout.REF`, so dial geometry is unit-testable against real numbers.

### 12.1 The dial (D1–D9) — `domain/OrbDial.kt` + `ui/OrbDialOverlay.kt`

Nine 40° sectors, default map (every slot remappable in Settings ▸ Orb). Keyboard is PINNED to
12:00 by owner rule (2026-10-29: *"that allows for fast access to the most commonly used option"*) —
slot 0 is always straight up no matter how the ring is remapped or resized:

| Clock | Lane | Action id | Why |
|---|---|---|---|
| 12:00 | keyboard | `keyboard` | the drag-up that already ships (`OrbGesture.DRAG_UP` becomes this sector — semantics unchanged); owner-pinned |
| 1:20 | mute / unmute | `mute` | the mute satellite's job; same way again = the inverse; DWELL enters volume mode (§12.8) |
| 2:40 | settings | `settings` | one drag to Settings (owner: "tough to get to settings"); replaced the unshipped `record_hi` stub, which remains a remap lane (F19) |
| 4:00 | model picker | `model` | 🧠 per-session model selector — replaced `voice_notes` (F20 stub nobody fired), SPEC §12.6 |
| 5:20 | slash commands | `commands` | the overlay (F15) |
| 6:40 | chats | `chats` | local chats for the active agent (the header chip's job) |
| 8:00 | sessions | `sessions` | gateway/computer sessions |
| 9:20 | agents | `agents` | agent switcher |
| 10:40 | approvals | `approvals` | ✅ the per-chat approval-mode sheet (§12.9) — the 9th wedge (owner 2026-10-29); a saved EIGHT-slot map decodes onto the new dial by inheriting this default (no reset) |

**Geometry decision (owner, locked):** the orb sits 15 ref-px off the bottom edge, so the four
below-horizon wedges have no room for a ring. The dial therefore uses **contextual rise**: dragging
up, left, right or either upper diagonal never moves the orb; when the drag vector enters the LOWER
arc (`100°…260°` on the nine-wedge face) the orb animates toward face center (~110 ms) so the
crowded slots are reachable, and it settles back when the drag leaves. Ring icons are drawn **past
the fingertip** (`ICON_RING_REF` > activation radius)
— you are dragging *to* an icon you can see, not to an invisible angle.

**Speed discipline:** the ring fades in ≤ 80 ms, sector changes cost one haptic tick, release
commits. Once the direction is memorized the whole gesture is a blind flick — the icons are for
learning, not for aiming. Sectors activate past `ACTIVATE_RADIUS_REF` (never a jitter) and the
lane only arms on a drag that crosses before the long-press timeout, so **tap = record is untouched**
(owner batch 6 retired hold-to-talk so the hold belongs to the dial; batch 7 closed the loop — a
stationary hold now BLOOMS THE DIAL on the idle orb too, it was only wired for thinking/speaking.
`OrbGesture` keeps the race, `OrbDial` classifies the direction).

**Re-aim (owner batch 5: "if I go up I should still be able to move over to one of the others"):**
arming the dial BEATS the hold timer outright. Once the gate is crossed the gesture belongs to the
dial until the finger lifts — time passing never freezes the vector — and the lane is decided by
the finger's angle **at release**, so up-then-slide-left-then-release picks 10:40. (The old bug:
`Tracker.move` froze dx/dy when the long-press timeout expired mid-drag, ~0.4 s in.)

**SPIN mode (`domain/DialSpin.kt`, owner batch 5: "slide it up, go right or left, the little
icons turn around you, come back to the middle and let go").** While the finger holds the TOP arc
(`SPIN_UP_ARC_DEG`), lateral travel accumulates; every `SPIN_STEP_REF` px is one DETENT that walks
the lit slot one position toward the sweep (haptic tick each) and rotates the whole ring
(`phaseDeg`, 130 ms tween) so the chosen icon travels under the finger. Design locks, all pinned
in `DialSpinTest`: below one detent of lean nothing engages (pure aim unchanged); spin arms only
from the top arc (side/bottom drags aim directly like always); the walk home FREEZES the steps
(approaching the commit spot must not un-step the selection); while spinning, release commits the
LIT slot from anywhere — the middle included. The crowded lower slots are the ones this reaches
without the finger ever leaving the comfort zone.

**The back button never quits (owner batch 5: "back from the keyboard exited Hermes"):** one
enabled-when-anything-is-open `BackHandler` closes the top layer first — editor → prompt sheet
→ delete-confirm → action sheet → agent picker → ⌘ overlay → media viewer; only with all closed do
the screen handlers (Sessions/Chats/Settings → Chat) or the plain system exit take the press.

**Screen after the change:** the three satellites (agent / sessions / mute) are gone from
`BottomLayout`+`OrbCluster`, the two header chips are gone; a single passive caption line (agent ·
chat · status, no touch target) is all that stays. Mute state reads on the ring glyph + that caption.

### 12.2 Why agent switching was broken (root cause, proven live)

Only `wear` carries a profile-scoped `API_SERVER_KEY` on the owner's gateway; `comfyui` · `elvtr` ·
`esp32` · `k-dense` · `merge` · `redridge-treasurer` · `research` · `weather` have **none**, and
`default` has its own different key. `api_server.py::_expected_api_key()` resolves the key **per
URL-selected profile** and `_check_auth()` **fails closed** for named profiles — so the wear token
that works perfectly on `/p/wear` is a 401 everywhere else:

**RESOLVED 2026-10-26 — one login for the whole gateway (owner: *“it should just be like one
login for the whole of my Hermes gateway”*).** The gateway's own doctrine (config_defaults +
keygen hooks) is a plain `API_SERVER_KEY` per profile `.env`, so the box was unified the boring
way: the `default` profile's key (`~/.hermes/.env`) rolled out as `API_SERVER_ENABLED=true` +
`API_SERVER_KEY=<master>` into EVERY `profiles/<p>/.env` (symlinks skipped, never clobbering a
later operator key). The gateway hot-reads env — verified live: a profile went key-required
without a restart and authenticated calls answered right after; an ask on `default` from the
wrist then streamed `error=false len=606` into the very desktop session the owner was watching.
Two follow-throughs: watchkit's `agentKeys()` looked for the home key at `~/.env` — wrong path,
it must be `~/.hermes/.env` (same `HERMES_HOME` rule `openStore` uses for `state.db`); fixed, and
`GET /agent_keys` now reports all 11 profiles key-bearing. And the watch re-learns the map +
`set_token <master>` — its resolve chain (agent key → gateway token → mirror token, §12.2)
means a stale learned key self-heals via fallback on the next ⟳. Per-profile keys remain fully
supported; they just all happen to be the same string now.

```
profile=wear      health=200  sessions=200
profile=research  health=200  sessions=401  "Invalid gateway API key (API_SERVER_KEY)"
profile=comfyui   health=200  sessions=401
profile=default   health=200  sessions=401
```

**The trap:** `/v1/health` is unauthenticated, so the Settings ▸ Test probe and
`EndpointResolver`'s 2.5 s failover probe BOTH pass for a profile the watch can actually use for
nothing. That is why the failure looked like "some agents just don't work" instead of a clean
auth error. `multiplex_profiles: true` is already set, so this is purely a key problem.

Fix, three layers:

1. **Per-agent keys in the registry** — `Gateway.agentKeys: Map<String,String>` (`GatewayModel`
   decodes with `ignoreUnknownKeys = true`, so the new field is rollback-safe: an older build
   reading the new JSON simply ignores it). `AppSettings.tokenFor(agent)` resolves
   agentKey → gateway token → mirror token, and `ChatSession.api()` builds `HermesApi` with the
   key for the ACTIVE agent.
2. **Auto-learn, zero typing** — new watchkit `GET /agent_keys` reads
   `~/.hermes/profiles/<p>/.env` (plus the home `.env` for `default`) and returns
   `{agents:[{profile, has_key}]}` with the keys themselves, guarded by the existing watchkit
   bearer on a tailnet-only port. The watch refreshes them with the agent list and caches them,
   so switching agents works the first time on a machine that already has keys configured.
3. **Fail loud, degrade kind** — a 401 on submit says `no key for <agent> — Settings ▸ Gateways ▸
   Agent keys`, not `run submit 401: {…}`; `setup.sh --agent-keys` + the `set_agent_key` dev hook
   push every locally-detected key for scripted installs; AGENTS.md §5 documents the trap.

**Sessions preload (the second half of the bug):** the old 401 also starved the sessions screen —
its gateway fetch failed and it served the stale cache, so a new agent showed the PREVIOUS agent's
list. `switchAgent` now triggers `refreshGatewaySessions()` and that refresh reads the **current**
agent's list through watchkit `/sessions?profile=<p>` (state.db, no profile key needed) in
addition to the gateway route — so a fresh agent always shows its own recent sessions, key or no key.

**AUTO-KEY NEW AGENTS (2026-11-02, watchkit `seedProfileKeys`).** The one-login roll-out above was
a one-time sweep, so agents created AFTER it came up keyless — the gateway fails closed for them,
and "Learn from watchkit" honestly answered *no key* (owner: "watchkit should look for new agents
and get that key in there"). Now the recipe is automatic: every `/agent_keys` Learn force-seeds
every keyless profile (appends `~/.hermes/.env`'s key to its `.env`, append-only, never clobbering
an operator key, missing `.env` created 0600 — the `setup.sh --agent-keys` recipe), and a 60 s
unref'd timer plus the watch's own 20 s away-poll keep sweeping so a brand-new agent is keyed
within a minute of appearing, with no human step and no gateway restart (hot-read verified live:
`chef-agent` + `delx-wellness` went — → ✓ and an ask streamed end to end). Key values never log.

### 12.3 Slash commands (F15) — `domain/SlashCommands.kt`

**The constraint that shapes this feature** (read `gateway/platforms/api_server_runs.py` before
changing it): the Runs API does **not** dispatch slash commands. `_handle_runs` turns `input` into
a user message for the model; command dispatch (`run_inbound.py` →
`hermes_cli.commands.is_gateway_known_command`) lives in the platform inbound path (Telegram/Slack/
CLI). So posting `/steer fix it` to `/v1/runs` today asks the MODEL a question that happens to
start with a slash. The catalog therefore declares **how the watch honors each entry**:

| kind | Behavior | Backing |
|---|---|---|
| `steer` | inject guidance into the run in flight | `POST /v1/runs/{id}/steer` (`run_steer: true`) |
| `stop` | detach + settle + best-effort cancel | `POST /v1/runs/{id}/stop` |
| `fork` | branch the session | `POST /api/sessions/{id}/fork` |
| `model` | provider/model picker, persisted lock | `GET /api/model/options` + `POST /api/sessions/{id}/model` |
| `skill` | run one of the agent's skills by name | skill rows ride the watchkit catalog (it reads `profiles/<p>/skills/` off disk; the gateway's own `GET /v1/skills` 500s on the owner's `wear` profile — treated as empty, never fatal) |
| `side` | `/btw` = fork-then-ask so the main session stays clean | `session_fork` + run in the fork |
| `client` | watch-side (new chat, rename, open sessions…) | local |
| `text` | genuinely a prompt (`/help`-ish, CLI-only ones) | `/v1/runs` — labeled as such in the UI |

`domain/CommandUsage.kt` ranks the overlay by frequency + recency in DataStore (owner: *"I like to
use the slash BTW and the slash steer a lot"* — that has to be learned, not hardcoded).

**Where rows actually come from (three sources, merged in `domain/CommandCatalog.kt`):** the
curated built-ins (a floor that can never blank the overlay) ⊕ watchkit `GET /catalog` (an operator
file `command_catalog.json` or watchkit's defaults, PLUS that profile's skill rows read off disk —
capped at 120 server-side; the overlay previews 12 with a *"+N more — type to filter"* title,
because this owner's `wear` agent carries 184 skills and a 480-face is not a 200-row menu) ⊕
**live capability flags read by the WATCH itself** (`GET {base}/v1/capabilities` — the watch holds
the active profile's key, so watchkit never has to guess; its `capabilities` field is only the
fallback for an unreachable gateway). Rows whose `requires` gate is false are dimmed with the
reason, never hidden. Refresh: Settings ▸ Interface ▸ refresh (or automatically when the ⌘ overlay
opens); `-e slash_catalog <json>` pushes a hand-written one. **Gateway ask (small, filed §13.5):**
`GET /v1/commands` off `COMMAND_REGISTRY`, which is already gateway-importable — then the catalog
self-updates instead of being curated by hand here.

### 12.4 Session search (F18) — `ui/VoiceOrTypeField.kt` + watchkit `GET /search`

Search shares one field component (`ui/VoiceOrTypeField.kt`) between the Sessions top row (under ＋
new session) and the command overlay's filter — Chats rides the same component when it needs it.
Tap → real focused field with 🎙 and ⌨ chips BELOW it (system IME, same ladder `PromptEditor`
already fights; a chip row above gets covered). Dictation reuses the orb's Vosk lane through a
**voice sink**: MainActivity routes partials/finals to whichever field asked for the mic, and a
final in sink mode never auto-asks — dictating a query must not fire a turn. Titles/ids filter
locally and offline (transcript previews via `SessionSearch.preview`, matched words bracketed);
message BODIES come from watchkit `GET /search?profile=&q=`, an FTS5 MATCH over the trigger-
maintained `messages_fts` joined to `messages`/`sessions` with `snippet()` (verified live: ranked
hits in **4 ms**, per-profile store — `default` = `~/.hermes/state.db`, agents =
`profiles/<p>/state.db`). Same visibility rules as `/sessions` (no cron/tool/subagent noise,
archived hidden); title-LIKE matches ride along with zero message hits; ranking rules — name >
mentions > recency, one row per session — are unit-tested in `SessionSearchTest`. MainActivity
debounces 300 ms so dictated sentences search once, not per word.

### 12.5 Edit & resend = fork-on-edit (F16)

Desktop edit truncates the server history and re-runs. The watch cannot (no truncate route exists),
so — owner's pick — editing **forks** (`ChatSession.editAndResend`): an in-flight answer to the old
text is detached first, the local transcript rolls back to just before the edited turn,
`POST /api/sessions/{id}/fork` branches the session and the chat RE-POINTS at the child id (a
fresh client-side id would start an empty session — only the child carries the copied history),
and the edited text is asked there. The wrist sees exactly one version of the turn.
Honest limit, printed as a system line in the transcript instead of hidden: the copy is the
**whole** history (measured — `up_to_message_id` is accepted and ignored; the child is titled
"… #2"), so the superseded turn still sits in the model's context; true rewind needs the gateway
`truncate` route (§13.5). Fork failure (offline, key-less agent) degrades to the old append WITH a
visible note — a vanished edit would be worse than a piled one. The same primitives honor `/fork`
in place (`forkActive`) and `/btw <aside>` as a real side thread (`sideAsk`: a NEW watch chat on a
forked session, parent never sees it, dial 7:30 switches back).


### 12.6 Session management (F19) — long-press → fork / delete

Owner: *“click and hold on the session itself… fork or delete as options, and delete should double
check.”* Both actions run through watchkit **writing `state.db`** — the one path that works for
EVERY session: the gateway's `DELETE /api/sessions/{id}` (verified live, 200) needs the source
profile's key, and most profiles have none (§12.2) — the desktop `default` store never will.

- **`POST /session_fork {profile, session_id, to_profile?, new_id}`** copies session + messages
  column-wise over the **intersection** of the two stores' schemas (a version-skewed pair degrades
  instead of crashing), sets `parent_session_id` to the origin (lineage the desktop understands),
  suffixes the title “ (fork)”, lands the child `hidden=0 archived=0` so it shows immediately, and
  INSERTs through the store's own FTS triggers — a fork is searchable the moment it exists
  (verified: snippet hit before the next delete pulled it out). The watch forks INTO the current
  agent under a fresh `<profile>-watch-x` id — a fork of a desktop chat becomes a watch-owned chat
  you can keep talking in; `resumeGatewaySession` opens it and backfills the copied transcript.
- **`POST /session_delete {profile, session_id}`** is REAL (messages then session; FTS delete
  triggers keep the index honest). “Already gone” answers 200 — the button means *get it off my
  list*. The watch also removes the ⌚ mirror chat (`SessionManage.localMirror` joins on
  `sessionTitle`, the SPEC §3 key); if the doomed row is the OPEN chat, a sibling takes the seat
  first, because `ChatStore.delete` refuses the active one.
- The double check is a second sheet saying what dies (“every message goes with it — no undo”);
  confirm sits where a mis-tap hits “keep”. Dev twins: `-e session_fork/-e session_delete <id>`
  (profile resolved from the cached Sessions list).
- **🗃 ARCHIVE is the polite verb (owner batch 5: *“archive instead of delete”*).** The sheet reads
  fork / **archive** / delete-quiet-line: hide by flipping `sessions.archived`, the exact flag the
  desktop sidebar and watchkit `/sessions` already filter on — nothing is destroyed and the undo
  lives on the wrist: `GET /sessions?archived=1` feeds a **🗃 archived** chip that appears only
  while anything is hidden, and long-pressing one of those rows offers ♻️ unarchive. The watch's
  own CHAT of an archived session deliberately survives — hiding history shouldn't vaporize the
  conversation open under your thumb. Delete keeps its double check for the sure thing.

### 12.7 The model selector (F22) — orb 🧠 lane, per-session model, gateway-owned catalog

Owner: *"instead of that little thing on the orb for notes — I've never used the notes — make that
the model selector… it'll just remember that model within that session… whatever our default model
is set to in the gateway we want for NEW sessions… only models enabled within the gateway should be
shown… no endpoint editing here, that's the gateway's job."*

**Gateway contract (all three verified live, Hermes 0.21.7, before a line of app code was written):**

- `GET /api/model/options` — provider catalog; **enabled = `authenticated` AND `models` non-empty**;
  top-level `model`/`provider` = the profile's configured default. Advertised via `model_options`
  in `/v1/capabilities`. `domain/ModelCatalog.kt` filters + orders (current provider first) and
  caches per base URL for 3 min — it's per-profile, so agent switches refetch.
- `POST /v1/runs {model}` — a real model id ROUTES the run (`runtime.model` comes back as asked),
  and `"hermes-agent"` (the virtual alias the watch already sent) means profile default. This is
  the ONE lever the watch uses.
- The run **stamps** `sessions.model` with whatever it served; `GET /api/sessions/{id} → model`
  reads it back. So the watch: picks are stored per-chat (`ChatMeta.model`) and RESENT with every
  ask; entering an un-picked chat adopts the stamped model (adopted/desktop sessions keep their
  model, cross-device continuity for free); a never-run session adopts nothing and rides the
  default. `POST /api/sessions/{id}/model` (the lock endpoint) is deliberately NOT used: `/v1/runs`
  ignores it (only `/api/sessions/{id}/chat` honors it) — proven by running a locked session and
  watching `runtime.model` ignore the lock.

**UI:** two-stage dialog (`ui/ModelPickerDialog.kt`) — providers, then that provider's models —
because an authenticated catalog here is 7 providers × up to 62 ids and a 170-row scroll is a
wrist-killer. A top row "gateway default" un-pins the chat. ● marks what will actually run.
Landing the pick takes effect on the NEXT ask (a running turn keeps its model — same semantics as
the gateway itself). Lane migration is free: old saves carry `voice_notes`, an id that no longer
decodes, so [OrbDial.decode] hands 4:30 the new default `model` (§12.1 decode rule).

**Also from the same owner batch:** backfill no longer clips answers — watchkit's `MSGS_CONTENT_MAX`
and the app's mirror `SessionHistory.MAX_TEXT` went 2 KB → 20 KB ("we need the full replies"); and
markdown/bare URLs are clickable in chat (`MdSpan.Link` → `hermes.url` annotation → ACTION_VIEW on
the watch's default browser, honest toast when none exists).

---

### 12.8 Voice-out config — voices, the speed slider, per-agent volume (owner 2026-10-29)

Owner asked for two things on a call: *"the watch might have a lot more voices"* (change the TTS
voice) *and change speed — a slider would do me better* (the speed chips clipped off the round
face). Both are engine-side config, so `voice/SpeakAloud.kt` owns applying them and `domain/TtsConfig.kt`
owns the pure mapping (rate 0.5–2.0 ↔ slider fraction, clamps, `SPEED_CHIPS` retired).

- **Voices**: `availableVoices()` returns `VoiceOption(name, quality, builtin, local)` from
  `TextToSpeech.onVoiceAvailable`. `Voice.isDefault()/isPresent()` are `@hide` — read via
  reflection for *cosmetic flags only*; nothing branches on them, so a ROM that hides them harder
  just shows fewer decorations, never fewer voices. Pick persists to `AppSettings` `tts_voice`
  (empty = engine default) and applies live (mid-queue), previewing when voice-out is on.
- **Engines + transport filters + fallback (owner: *"are there google voices or just samsung?
  would be cool to have both… choose google/samsung/(other if exists), then local/network, then
  it shows the list… also default and fallback so someone could choose network first and a local
  fallback"*).** The watch ships BOTH: `com.google.android.tts` (system default) and
  `com.samsung.SMT`. `availableEngines()` lists them (`★ system default` head entry; short
  vendor labels via `VoiceCatalog.friendlyEngine`); picking one rebuilds the live engine
  (`setEngine` → `restartEngine`, main-looper posted because the TTS init callback binds to the
  creating looper — Application scope is not main). Each voice is classified
  `local|network` by `Voice.getNetworkConnectionTier()` where the ROM exposes it, else the
  `-local`/`-network` name convention (`domain/VoiceCatalog`, JVM-tested) — the picker's
  `any|local|🌐 net` chips filter on it. Proof on hardware: Google engine → hundreds of
  language voices, `-network` ones tagged 🌐; Samsung → SMT voices classified local, and the
  🌐 chip honestly says *"no network voices on this engine"*. `tts_fallback` names a second
  voice; when the
  preferred one ERRORS mid-utterance (the usual network voice off-wifi), `SpeakAloud.fail`
  requeues the SAME sentence on the fallback (QUEUE_ADD keeps stream order; per-utterance
  registry `id → (text, step)`), and queue-drain snaps the engine-global voice back to
  preferred. Blank fallback = one attempt — the chain rule lives in `VoiceCatalog.chain`.
- **Speed**: a custom drag slider (`ui/SettingsScreen.kt` → `RateSlider`, awaitEachGesture jump+
  drag — wear-compose has no Slider) persists `tts_rate`; `setSpeechRate` applies to the running
  engine immediately so the release-ear-test hears the new rate without a restart.
- **Volume = per-agent, three faces, one store (owner: *"dwell ~2 seconds on the mute lane and…
  swipe back and forth… a double slider"*, then *"make the volume be per-agent"* + a Voice-page
  slider)**: `domain/DialVolume.kt` —
  dwell `DWELL_MS=2000` on the 🔇 lane arms volume mode; while armed, dial swipes move reply
  volume on a 5% grid (`FULL_SWEEP_REF=240` px for a comfortable full rotation), the lane caption
  is replaced by a `🔊 xx%` badge (`ui/OrbDialOverlay.kt` `volumeBadge`), and dwell-timeout
  commits. The Voice page carries a matching drag slider ABOVE the speed one, labeled
  `reply volume · <agent> · xx%` — it edits the ACTIVE AGENT's override; agents with no entry
  ride the global. Storage is `tts_volumes` (`agent → 5%-grid percent`, codec + quantization in
  `TtsConfig`, JVM-tested) resolved by `AppSettings.ttsVolumeFor(agent)`, which re-resolves when
  the agent switches — the dial, the satellite and the slider all read and write the SAME map, so
  they can never disagree. Volume rides **per-utterance `KEY_PARAM_VOLUME`** in `speak()` — the
  app never touches `STREAM_MUSIC`, so watch media volume and Hermes replies stay independent
  (owner-locked: Hermes-app-only).

### 12.9 Per-chat approval mode (owner 2026-10-29) — the watch enforces it client-side

Owner wants per-session approvals (*"manual/smart/off… inherit the agent default"*) but the
gateway cannot offer it: verified in `hermes-agent` source, `approvals.mode` is **profile-scoped**
(`/approvals [manual|smart|off]`), the only per-session lever is the in-memory `/yolo`, and there
is no per-chat store. So the watch does it where it can: a new orb lane (`Lane.Approvals`, lanes
may exceed 8) opens `ui/ApprovalModeDialog.kt`; the choice persists per chat (`ChatMeta.approvalMode`,
default = Inherit). At approval-arrival time `domain/ApprovalMode.kt` + `ChatSession` decide:
Manual never auto-answers; **Smart auto-answers only while that chat is NOT the open front
screen**; Off always auto-answers (`respondApproval(choices.first())`, deduped by request id —
run-status re-reports pending approvals every ~3 s). `MainActivity` publishes
`WearApp.Graph.viewingChatId` (screen + active chat) — the single source the gate reads.

The same `viewingChatId` is the whole notification-routing rule (owner: notify *"for ANY case
except the notification's own chat being the actively-open front chat screen"*): settled replies
and approvals post standard Wear notifications whenever the chat isn't front-and-center — other
chat, other screen, watch face, another app, or screen off all notify; the open chat streams
silently into the transcript.

### 12.10 Settings is a tree now (owner: *"not overloading with a forever-scrolling single screen"*)

`SettingsScreen` became hub + pages (`enum SettingsPage`): **🌐 Gateways** (registry list, add,
active Base URL + Token, watchkit test, and — one level down, owner: *"shouldn't agent keys be
under gateways? … too high up in the tree"* — a **🔑 Agent keys** page whose back row returns to
Gateways, not the hub), **🗣️ Voice out** (toggle, speed slider, engine/voice/fallback pickers,
engine status), **🎛️ Display** (markdown, splash, pinch, orb lanes), **⌚ Watch** (body tools,
push, health/location status), **ℹ️ About** (stt model, version). Every section's logic is
UNCHANGED — the blocks are wrapped in `if (page == …)`; nav rows carry live subtitles (selected
gateway, rate, …); each page gets a `‹ …` back row and hardware/back returns to its parent
(`BackHandler` + `SettingsPage.up()`).

---

### 12.11 The orb state machine, karaoke follow, bottom-anchored streaming (owner 2026-10-29)

One batch answering three complaints about the ANSWER experience — the orb lying about what it's
doing, having to chase the spoken sentence with my eyes, and replies "streaming from the top of the
thing instead of just above the orb".

- **Orb state machine (`voice/TalkOrb.kt` + `SpeakAloud.speaking`)**: the orb wears the red disc
  (pulse ring + border included) in TWO states with DIFFERENT glyphs (owner same-day fix: *"by the
  time you're speaking you've already completed returning information … it should be a mute button
  on the orb"*): THINKING (run in flight) shows the **white ⏹ square**, SPEAKING (TTS queue live;
  `pending > 0` in `SpeakAloud`) shows a **🔇 speaker** — each promise matches exactly what the tap
  does. Tap always means "stop what the orb is doing": thinking →
  cancel the run AND flush the voice queue in one breath (`stopActiveRun` + `speak.stop()`);
  speaking → MUTE only (the answer already landed; orb returns to the mic). A stationary HOLD
  BLOOMS THE DIAL IN PLACE (hold-timeout routes to `onDialArm`): grab the keyboard, a `/steer`, any
  lane mid-answer — the run is never disturbed by the hold (short tap does that), and
  release-at-home cancels the bloom. **Batch 7 on real glass closed two holes in this promise:**
  the bloom fired ONLY while thinking/speaking (an IDLE stationary hold fell to the no-op
  `onHoldStart` lane — "the orb don't show up"), and `Tracker.move` froze dx/dy once the hold
  locked, so even a bloomed ring could not be aimed or spun ("it's not letting me select, it's not
  spinning"). Now: the hold-timeout arms the dial in EVERY state, and the vector is live until the
  pointer lifts — HOLD freezes the VERDICT, never the stream (JVM-tested). The detector reads no
  orb state at all, so a flip mid-hold (queue drains while the finger sits) can never restart the
  gesture without `onDialEnd` and freeze the ring open. Idle orb semantics otherwise untouched
  (tap-to-talk, come-home-cancel, drag-dial, dwell-volume).
- **Karaoke follow (`SpeakAloud.speakingOffset` + `MainActivity` ChatBody effect)**: the utterance
  registry carries each sentence's STREAM OFFSET (`Utt(text, step, offset)`); `onStart` publishes
  it, and while speaking + tail-follow is engaged the chat anchors the spoken line ~34 % down the
  viewport. The char→px ratio SELF-CALIBRATES from the measured live-item height (no font/markdown
  math); a line already in the 22–72 % band is never nudged; scrolling away disengages exactly like
  the tail-pin (same `ChatFollow` escape hatch — no yanking); mute stops the feed and frees the
  scroll. `offset < 0` marks standalone speech (previews/notices) — nothing to follow.
  **Day-two fixes (owner watch-proof: *"when you're counting and stuff for the audio test I am not
  seeing the line that you're saying be at the center of the screen"*).** Three compounding bugs,
  all now pinned:
  1. **One utterance ≠ one line.** Replies without `.!?\n` inside (number lists, tables) ride the
     TTS queue as ONE utterance, so `onStart` alone pinned line 1 for a minute. `domain/
     KaraokeCursor` adds the IN-UTTERANCE cursor: a 180 ms ticker estimates the playhead by
     chars-elapsed × chars/sec, and the speed model SELF-CALIBRATES — every finished utterance
     feeds an EMA of the voice's real seconds-per-char, so rate settings and slow voices converge
     in one sentence (JVM-tested; `onProgress` is absent from the compileSdk's listener, and
     engine bookend 0-values would re-create the pin anyway).
  2. **The voice outlives the stream.** Reading mostly happens AFTER settle, and the live item
     vanishes into the transcript then — the first version read that as "nothing to follow". The
     effect now follows the SETTLED last-Hermes row (same char offsets; the settled text IS the
     live text), index `2+size-1` regardless of a trailing approval card.
  3. **Karaoke disengaged itself.** The `ChatFollow` edge detector reads any RISE-then-fall of
     `isScrollInProgress` as the user grabbing the list — and wear-compose raises it for OUR
     scrolls too (animated AND instant `scrollToItem`, measured). The first nudge flipped
     tail-follow off and froze the view (which is exactly the static-window frames the owner saw).
     Karaoke now STAMPS its own jumps (`programmaticScrollAt`) and the detector ignores edges
     settling within 400 ms of a stamp. Watch-proof (SM-L705U, 45-number list, owner's 1.60×):
     the visible window marches with the voice — 13–33 → 21–41 → 29–45, spoken line inside the
     band the whole way, view settling to the tail when speech ends.
- **Bottom-anchor (`ChatScreen` spacer item 0 + `ChatAnchor` baseline rows=2)**: measured on-device,
  the ROOT CAUSE was wear-compose: its list "end" parks the LAST item at the MIDDLE anchor, so a
  transcript SHORTER than the circle sits pinned at the top (and the tail-pin rocket
  `scrollToItem(idx, 4096)` shoves short content off the top entirely). Fix: a top SPACER sized from
  ONE clean fully-composed frame (bounded wait for lazy tail; Σ item heights − spacer = content
  height; pad = slack under it, 10 dp clear of the orb crown — flush reads as "sliding under").
  Fits ⇒ pad > 0 + rest at offset 0: newest line just above the orb from SENTENCE ONE, reply grows
  UPWARD (max scroll-back while waiting — same intent as `ChatFollow`). Doesn't fit ⇒ pad 0 and the
  classic tail-pin owns the view unchanged. Idle hint and approval centering never compete (slack
  forced 0); all row indices shifted +1 (tests rebased). Emulator-proven on all three paths: short
  ask rests above the orb from the thinking state on; long lists tail-pin unchanged; a swipe-back
  mid-stream holds through 6 s of deltas.

### 12.12 The tool-activity card (verbosity, desktop-style) — `domain/ToolActivity.kt` + `ui/md/MdText.kt`

Owner 2026-11-02: *“things go straight to the answers and I feel like I'm missing what's actually
going on — code edits and terminal outputs, running and when done, in a collapsable box like
hermes desktop.”* The wire already carried it: `tool.started {tool, preview, tool_call_id}`
(preview = the command / file path / “cmd + N more”) and `tool.completed {duration, error,
tool_call_id}` — the watch dropped it into a transient `🔧 terminal…` line. Now:

- **Live:** `ChatSession` keeps a `steps: List<ToolStep>` ledger (correlated by `tool_call_id`,
  falling back to tool-name + first-unmatched), and `ChatScreen` renders `ActivityRunCard`
  **while the run streams** — in the transcript list and in the FOCUS-HOLD layout (a long
  terminal command streams no deltas; that is exactly when the box matters). Header
  `⚙ N steps · running…`, amber current row, per-step monospace preview, tap = collapse.
- **Settled:** `finishWith` folds the ledger into the transcript as a ` ```hermes-steps ` fence
  (one `tool|durS|run|ok|err|preview|result` line per step, pipes/newlines sanitized) placed
  BEFORE the answer, and the live card clears so the card never doubles. The fence persists in
  the transcript file, parses back via `WatchMarkdown.parseStepsFence` → `MdBlock.ActivityRun`,
  and re-renders collapsed — reload-stable. `StepsFenceTest` round-trips encode→parse.
- **Result previews:** today's gateway sends `tool.completed.preview = ""`; the newer source
  (`api_server_runs.py::_tool_completed_preview`, 500 chars, redacted) fills it — the watch
  parser takes it with zero protocol change (row shows up to 4 result lines on expand).
- Dev fixture: `-e render steps`. Proof (emulator, wear-demo ask “du -sh /usr”): live card
  appeared 3 s in showing the running command; on settle one collapsed card
  `⚙ 1 step · 1 failed ‹expand›` (the ⚠ came from the gateway's OWN `_detect_tool_failure` on
  du's permission-denied partial — the card is honest, not decorative).

**Watch ⇄ desktop session continuity (same owner, same day).** Adoption is never a fork:
`resumeGatewaySession` binds the next asks to the SAME gateway session id — proven live both
ways (an `/v1/runs`-created session asked from desktop-API then from the watch shows one
session row, interleaved transcript, zero new rows). The gateway's session-binding doctrine
(`gateway/session_api.py`: stored `source` must be `api_server` to run from the API lane)
makes a **desktop-CLI** session (`source='local'`) API-readable but API-RUN-REFUSED → 409
`permission_denied`. The watch surfaces that as a one-line read-only explanation and does NOT
auto-fork (owner: “an auto-fork makes a fucking mess”). Gateway ask below (§13.5 #4).

### 12.13 Flawless hand-off — the same chat from either place (`domain/ChatSession.kt` + watchkit `/session_promote`)

Owner 2026-11-02: *"I want to pick up a chat from the desktop on my watch, and a chat from the
watch on the desktop, and continue it from **either** place — same chat. WatchKit is just there to
render images/video/audio for the wrist; that should not be what blocks hand-off."* The doctrine:

- **The gateway is the ONLY session/run authority.** The watch talks to the gateway directly over
  the embedded tailnet node (`/v1/runs`, `/api/sessions`, `/v1/events` SSE) — never through
  watchkit. Adoption binds the SAME gateway session id (`resumeGatewaySession`), so a desktop chat
  and a watch chat are already the same transcript once a run is admitted. This is what makes
  hand-off symmetric: there is no second copy to keep in sync.
- **History backfill goes GATEWAY-FIRST** (`HermesApi.sessionMessagesJson` →
  `GET {lane}/api/sessions/{id}/messages`, bearer-authed, cross-source). It rides the SAME key an
  ask uses, so it survives a watchkit outage — the 2026-11-02 “history unavailable — continuing
  live” wave was one dead sidecar taking history down with it; watchkit's keyless
  `/session_messages` is now the FALLBACK, not the only door (it still wins for profiles the watch
  can't sign for — SPEC §12.2).
- **WatchKit is a render sidecar, not a proxy.** It does the things the gateway does NOT: `/img`
  (on-watch-decoded scaling), `/media` (H.264+AAC / Ogg-Opus pre-shrink), `/render/mermaid`, STT,
  the health store, and the keyless `state.db` reads. It has no bearing on whether a run is
  admitted — it is the same reason the DESKTOP renders the same markdown without it.
- **The cross-source door is PROMOTE, not fork.** A desktop-CLI chat refuses an API-lane run
  (`session_api.py::bind_api_steps` → 409 `permission_denied`) until it is on the API lane. The
  watch promotes it — a ONE-COLUMN `UPDATE sessions SET source='api_server'` through watchkit
  (`POST /session_promote`, this repo) — then resubmits under the SAME `Idempotency-Key`. Same row,
  same id, same transcript; the chat simply appears in the desktop's API spot like a watch chat
  always did. **Never a fork** (owner doctrine). If the desktop still holds the session live in
  memory, the promote's resubmit 409s and the watch REVERTS its own flip, telling the owner to
  close it on the desktop first — the flip is always reversible, so the desktop's own view never
  drifts. Owner surfaces only (`desktop/tui/webui/cli/local`); cron/subagent rows are machine-owned
  and refuse.
- **The gate has TWO checks, and promote only answers the persisted one** (curl-proven 2026-11-03:
  row hand-flipped to `api_server`, pure gateway-side `POST /p/<p>/v1/runs` → still 409).
  `bind_api_steps` first refuses on the gateway's **live in-memory map**: `session_id in
  authority.sessions` with `source.platform != API_SERVER` — and the desktop app HYDRATES a chat
  into that map by having it open, run or no run. So a desktop chat that is NOT currently open on
  the desktop promotes and runs from the wrist on the spot (proven: a seeded desktop-starter
  session, adopted, promoted `local`→`api_server`, answered from its own history — one row, zero
  forks); a chat OPEN on the desktop needs “close it there” first, which is exactly what the
  watch's revert message says. Entries leave the map when the desktop closes the chat, the app
  exits, or the gateway restarts. Real relief is §13.5 #4.
- **`-e resume_session <id>` + a real desktop session, proven both ways:** a fresh desktop session
  adopted-and-asked from the wrist promoted `local`→`api_server` and ran (interleaved user/
  assistant, count 4→6, zero new rows, source `api_server` after). A session the watch ALREADY owns
  (`desk-continue-probe-1`) answered from both ends as ONE row with no fork at all. The gateway's
  native door (§13.5 #4: `bind_api_steps` admitting a `local` row with no live holder, or a
  `POST /api/sessions/{id}/continue` CHAIN) would retire the watch-side flip; the watch is
  client-ready — adoption binds the same id today and only needs the 409 to become a 200.

### 12.14 The media lane — animated GIFs, pinch video, bezel volume (`ui/md/AnimatedImage.kt`,
`PinchPlayerView.kt`, `domain/RotaryVolume.kt`)

Owner 2026-11-02, three asks on the media surface, all rendered by the watch (gateway never
transforms media on the wrist path):

- **Animated GIFs** were frozen because every image path decoded to a still `Bitmap`
  (BitmapFactory = first frame only). Now the ORIGINAL container is decoded via
  `ImageDecoder.decodeDrawable` → an `AnimatedImageDrawable` when the bytes move, drawn through
  `AnimatedDrawablePainter`. Both newer-than-API-27 animation hooks
  (`setInvalidateCallback`, `advance`) are `@hide`, so frames are driven the classic PUBLIC way:
  `start()` on the drawable + a `Drawable.Callback` (public since API 1) that bumps an `Int` the
  painter reads in `onDraw` — one state read = one redraw, not a tree recompose. A cheap
  header sniff (`looksAnimated`: any GIF, or WebP with the VP8X animation bit) decides inline
  ANIMATED vs sampled-still without decoding a full Bitmap for every photo. The card shows a
  `GIF` chip; tap opens the animated viewer. Playback is bound to composition — a GIF scrolled
  off the transcript stops costing anything.
- **Pinch-to-zoom VIDEO.** The image viewer already had a Compose gesture lane, but a
  media3 `PlayerView` is an interop'd child that consumes every touch for its controller, so a
  `pointerInput` above it only sees crumbs. The lane therefore lives IN the view: `PinchPlayerView`
  intercepts on the 2nd finger (`ACTION_POINTER_DOWN`) and single-finger drags once zoomed, and
  transforms the View's own `scaleX`/`translation` (GPU, no relayout). Untouched, a tap still
  shows/hides the transport. Scale ∈ [1,3], pivot at center, pan clamped by the SAME
  `MediaZoom.clampOffset` math the image viewer uses; double-tap toggles fit↔2.5×.
- **Bezel volume.** *"enable bezel scrolling for volume up and down — if we're playing music on
  the wrist that's how we do it."* On Galaxy Watch the rotating bezel arrives as
  KEYCODE_VOLUME_UP/DOWN; `MainActivity.onKeyDown` routes those to `RotaryVolume` while a player
  sheet owns the screen, so the bezel steers the PLAYER's `volume` (5 % detents on the dial grid,
  one haptic tick per step, a `🔊 NN%` readout that fades) and the system stream never moves.
  Close the player, unbind, and the bezel is plain system volume again. `-e volume <±N>` drives the
  exact same path for the bezel-less emulator.

## 13. Next steps — branch 2 (media + voice quality), scoped 2026-10-26

Agreed split: dial + agent switching + search land first and get a wrist review; everything below
is the next worktree. Every item here is already researched — the "verified" lines are from the
owner's Watch Ultra and gateway box, not assumptions.

### 13.1 Tables: 5 columns + tap-to-open viewer

Inline grid today covers exactly 2 labelled columns (`MdBlock.Table.gridable`); wider tables fall
back to `· col: cell` lines. Work: widen `gridable` to 2–5 columns with a width budget derived from
the measured viewport (480 ref, `BottomLayout.scaleFor`), column weights from measured cell text,
bold header + hairline kept; past 5 columns keep the readable-row degradation. New
`ui/md/TableViewer.kt`: tap any table → full-screen, both axes scroll, pinch re-scales type — the
same gesture lane as `ZoomableMediaViewer` (raw `awaitEachGesture`; wear-compose 1.4.1/foundation
1.7.6 have no `TransformState`). Parser tests for 3/4/5-col + ragged fixtures.

### 13.2 Mermaid: native on-device vector renderer (no PNG conversion)

Owner's ask: stop receiving a converted bitmap, render **all** diagram types natively. The two
facts that decide the design:

- **This watch has NO WebView.** `pm list packages | grep webview` → empty and
  `android.software.webview` is absent from `pm list features` (Samsung Internet is its own engine,
  not a shared provider). "Just run mermaid.js on the watch" is impossible, and shipping a JS+DOM
  runtime for one feature is not worth ~15 MB.
- **mermaid's own layout engine is what makes all the diagram types work** (dagre/ELK under the
  hood). Re-implementing it in Kotlin for sequence/state/class/gantt/journey/… is a research
  project, not a feature.

So the pipeline is **vector to the watch, rasterize on the watch**: `mmdc -o diagram.svg` (its CLI
already emits png/svg/pdf — today `watchkit handleRender` hardcodes `.png` and a `.png` cache key,
so this is a `format` parameter, not a new service) → watchkit returns `image/svg+xml` → the watch
paints the SVG through a Compose-Canvas painter (or an Apache-2 SVG view lib) and rasterizes at
whatever scale the pinch lands on, inside the existing `ZoomableMediaViewer`. Caps that keep the
Watch Ultra safe: element-count + byte ceilings on the SVG, viewport-sized rasterization
(`MemAvailable` measured ~412 MB), and the fence source always stays one tap away when a diagram
type the painter can't draw shows up — the message text is never lost. This also finally gives
mermaid the same full-res treatment images got in batch 2 (full bytes to the watch, scaling on the
watch), and shrinks watchkit's mermaid endpoint to a cache + pass-through.

### 13.3 PDFs + links

- **PDF**: two verified doors on this watch — `android.graphics.pdf.PdfRenderer` is on-device at
  API 36 (pages → Bitmap, zero dependencies), and `com.dertefter.wearfiles` registers a
  `PdfViewerActivity` for `application/pdf`. Plan: a lightweight inline paged viewer (renderer +
  page stepper + the existing sub-fit zoom) with **Download** to MediaStore and a handoff to
  WearFiles for anything we'd rather not render locally.
- **Links**: markdown links currently render as literal `[text](url)` — `WatchMarkdown.parseInline`
  handles `*` and backticks but has no link case. Add the span + click lane, then the
  `ACTION_VIEW` ladder: **Samsung Internet is installed and resolves `http://` VIEW intents**
  (`resolve-activity` → `com.sec.android.app.sbrowser…LauncherActivity`), so tap-to-open is real;
  no-resolver fallback copies the URL + says so, and a "open on my computer" option rides the
  agent's browser tools. Long-press = choose.
- **Test harness**: a dev hook (`-e render_md <case>`) that injects a canned answer containing a
  5-column table, a mermaid fence, a PDF path, a link and an image — so all five surfaces are
  provable over adb + `screencap` without asking the model five times.

### 13.4 Voice quality: configurable backend + the history ring

Branch 1 ships the record→gateway lane against the **existing** `watchkit POST /stt/whisper`.
Branch 2 makes it configurable and adds the comparison the owner asked for:

| backend | shape | note |
|---|---|---|
| `whisper` | faster-whisper in watchkit's folder-local venv | ships today; **cold-starts the python process per request (~2–4 s)** — warm-worker TODO is in the watchkit header |
| `openai` | any OpenAI-compatible `/v1/audio/transcriptions` | this is how **Parakeet TDT 0.6B on the owner's 4070** arrives: a small CUDA runner (NeMo/ONNX) behind that URL. The gateway's own `audio_api` is `false` on this box, so we point at the runner, not at Hermes |
| `cmd` | run a configured command with the WAV path | the escape hatch for whatever transcribes well locally, no HTTP server needed |

**Handy specifically has no HTTP transcription endpoint** (its docs expose CLI toggles/signals +
history only — verified on handy.computer/docs), so "route it to my Handy model" means reusing the
MODEL via one of the backends above, not talking to Handy. Settings: backend, model, endpoint,
token, test button. The 4:30 ring buffer then stores `{ts, vosk_text, gateway_text?, wav?}` for the
last N (default 8, settings 0/5/10/20), long-press copies the whole note, tap pastes it into the
draft, and **swipe left/right toggles that note between on-device and gateway text** — no automatic
always-send-every-clip policy; the owner decides per note (auto-quality is a later policy on top).
Mic discipline: `VoskStt` holds `AudioRecord` under a mutex, so the record lane takes that same
mutex rather than opening a second capture.

**THE MIC STOPPED SHIPPING RAW WAVE (owner 2026-11-02: "why'd you send the audio as a wave? use
the best current one").** The streamed lane (`/stt/stream`) used to push raw 16 kHz mono PCM16 —
256 kbit/s, ~32 KB/s through the tailnet. `voice/MicCodec.kt` now picks the best encoder the
device carries: **Opus 24k** (VoIP application — the best current speech codec; ~10x lighter, and
the Wear emulator image proved to have it even where `media_codecs.xml` lists no encoder — query
MediaCodec, not the XML), **AAC-LC 32k** as the every-Android fallback (ADTS-framed by hand —
`voice/CodecMuxers.kt`, MP4's moov-at-end is the wrong shape for a live stream), or raw PCM when
the device has neither. The wire format is **sniffed from the first bytes** server-side
(`OggS` → Ogg/Opus, exact ADTS shape → AAC, else PCM), and encoded lanes are demuxed by watchkit
through ffmpeg to engine PCM — the engines never changed, and an old watch ↔ new watchkit (or
vice versa) just works. RFC 7845 gotcha that cost a debug cycle: **OpusHead must END the BOS
page** — sharing it with OpusTags makes ffmpeg read the tags as a second OpusHead and reject the
stream ("Channel change is not supported"). Proof: the same ATC speech clip transcribed to the
same words over all three wires (Opus at 29.9 KB vs the wave's 320 KB for 10 s, partials included);
`CodecMuxersTest` byte-compares the pages against an independent Python encoder and ffmpeg's own
ADTS bytes. Also fixed by the same probe: the engine child was awaited on `exit`, which can fire
before the final JSONL leaves stdout — cleanup then cut it; it awaits `close` now.

### 13.5 Asks for the Hermes gateway (not this repo)

1. `GET /v1/commands` — expose `hermes_cli.commands.COMMAND_REGISTRY` (already gateway-importable,
   prompt_toolkit-free) so the watch catalog self-updates.
2. `POST /api/sessions/{id}/truncate {before:<message_id>}` — `SessionDB.replace_messages` already
   exists (fork uses it); ~20 lines. Unlocks exact desktop edit-and-resend and retires the
   fork-per-edit workaround in F16.
3. Consider one `API_SERVER_KEY` per profile in the install docs — the fail-closed 401 with an
   open `/v1/health` is a trap for every client, not just the watch (§12.2).
4. **Cross-platform session continuation** (owner 2026-11-02: "a desktop session must continue on
   the watch AND the desktop — the watch must NOT auto-fork, that makes a mess"): today
   `gateway/session_api.py::bind_api_session` refuses any run whose target session's stored
   `source` isn't `api_server` — a desktop-CLI session (`source='local'`) is API-RUN-REFUSED with
   a bare 409 `permission_denied`. The watch adopted-and-asked live: 409, no fork (correct per
   owner doctrine). Ask: admit an API turn onto a `local` session when no live writer holds it
   (the Bot-Chat FIFO door in `api_server_bot_chat.py` is precedent for exactly this hand-off),
   or expose `POST /api/sessions/{id}/continue {from:<session_id>}` that CHAINS (not forks:
   `parent_session_id` lineage, transcript carried, one conversation in both UIs — what
   `continued_api_session` already does across API generations). The watch is client-ready:
   adoption binds the same id today and simply needs the 409 to become a 200. (Interim shipped:
   the watch promotes the row itself — one reversible `source` UPDATE through watchkit
   `/session_promote`, §12.13 — and would retire that flip the day this door lands.
   2026-11-03 precision for whoever picks this up: `bind_api_session` refuses on EITHER check —
   `session_id in authority.sessions` with in-memory `platform != API_SERVER` runs FIRST, and the
   desktop app hydrates an OPEN chat into that map without any active run; the persisted-source
   re-check in the write step only gets a vote when the map is clear. A hand-flipped row + pure
   gateway-side `POST /v1/runs` → still 409 (curl-proven). The ask narrows to: admit when the
   in-memory entry is IDLE (no active execution/lease — FIFO-door precedent again), because that
   is the case the watch's flip visibly cannot fix.)


## 14. Owner batch 4 (2026-10-29/30 night): circle-meat geometry, orb-flanking approvals, STT backends, edge scroll

Reviewed by the owner as a numbered table (agree/veto by number, 2026-10-30); the numbers below
ARE the contract. One commit per item; `:app:testDebugUnitTest` stays green throughout;
emulator-proven where possible; one chunked watch install at the end with screenshot proof each.
Item 11 (rename the Hermes "API server" platform to "WatchKit") was **CANCELLED by the owner** —
*"out of scope… cancel that shit"* — zero Hermes-gateway changes ride with this batch.

| # | Status | Ask (owner language distilled) | Where it lands |
|---|--------|------------------------------|----------------|
| 1 | ✅ | Newest streamed text's bottom-left corner rests at the **"8:20 spot"** — the circle rim ∩ top-of-orb line — so nothing clips on round faces; derived from orb position + screen shape so SQUARE screens (and any vendor round size) work unchanged | FIXED 2026-10-30: `BottomLayout.circleSafeBottomPad` = max(orb-crown, physical-rim at the text gutter) feeds the transcript pad; growing-row lag settled by delayed re-asserts (wear's clamp uses the PREVIOUS layout pass). Circle-masked captures: mid-stream digits fully readable, none shaved (pre-fix: "29"→"9") |
| 2 | ✅ | Karaoke's spoken line anchors at the **10-o'clock tick height** (~25 % down — wide rim up there, left edge never clips); no-nudge band re-tuned around it | SHIPPED 2026-10-30, HARDWARE-PROVEN on the SM-L705U: anchor = `RoundScreen.tenOclockY` (y≈120 on the 480 face), band 12 %–35 % around it. Masked mid-read captures of a 1-to-20 voice-out: the spoken line ("6" at that moment) rode y≈125 at the left text edge — ON the tick; later captures showed the viewport advancing WITH the speech, then clamping at the answer's tail (spoken line inside the no-nudge band → no yank; last number at the 8:20 spot per #1, which wins where the list cannot scroll further) |
| 3 | ✅ | The submitted question anchors at that same 10:00 height (ChatAnchor baseline re-base) | FIXED 2026-10-30: while a question awaits its answer the bottom spacer shrinks by the MEASURED row-top minus tick (derived geometry undercounted header chrome — measured 120 vs rendered 258), clamped to `[0, slack]`. Masked capture: question top-left lands upper-left (≈10:00–10:20 depending on header mid-composition), never under the orb; exact-pixel polish logged below |
| 4 | ✅ | **Follow-up replies scroll-from-orb like the first chat in a new session** — every answer rises from the 8:20 corner regardless of history length (pad recomputed per reply; over-long replies switch to in-reply tail-follow) | PROVEN 2026-10-30: same-session follow-up streamed with the first-chat geometry (masked mid-stream: digits fully readable left of the orb) and settled showing the TRUE end (41–45) clear of the rim — the late-settle re-asserts land the tail at wear's padding-honoring end |
| 5 | ✅ | Approval ✅ **OK = left of the orb**, ❌ **NO = right of the orb** — but **only while scrolled to the chat bottom** (no buttons permanently hanging by the orb → no mis-clicks); also fixes: buttons never appeared when the approval arrived mid-scroll | SHIPPED 2026-10-30: `ApprovalFlank` pair rides the old agent/sessions spots at 1.45× (big thumb targets), shown only while a live approval waits AND `chatFollow` is true. The in-list card became pure context (its permanent OK/No buttons WERE the vetoed pair); forced-centering survives only as the scrolled-away RESCUE. Mid-scroll arrival now works: scroll to the bottom → card rests on the safe line + flanks appear. `-e approval_demo 1` dev hook drives it. Masked shots: bottom (flanks flank, card readable) / scrolled (both gone) / tap (responds, clears) |
| 6 | ✅ | **Top/bottom buffer padding on EVERY list** (Settings tree, Voice lists, Sessions, Chats, Commands, search) — any last row scrolls into the meat of the circle | SHIPPED 2026-10-30: `roundListPadding` folds the #1 rim math into every SLC it feeds (Chat/Sessions/Chats); Settings + Agent/Model/Approval dialogs got rim clearance as CONTENT padding AFTER `verticalScroll` (the first cut put it before the scroll — a viewport band that amputated the settings tree's lower rows, caught by the masked sweep and reverted); `circleSafeBottomDp` reads the VIEW's display metrics so it is right inside Dialog windows too; Commands sheet padded. Proofs (masked): settings root whole again + scrollable; About page LAST line resting in the meat with the buffer band beneath it |
| 7 | ✅ | **Edge-circle scrolling everywhere** — finger circling the screen edge CW/CCW scrolls; chat first as the pattern, then every #6 surface; `edgeRotate` + `rotaryScrollable` covers touch-only round AND square via Wear's bezel-emulation | SHIPPED 2026-10-30: Wear's `edgeRotate` does NOT exist in compose-foundation 1.4.1 (verified against the shipped jar), so `domain/EdgeScroll` owns the math (ring ≥ 0.80 r, 7°/step, LOCK at 2.5°/event separating circles from rim-drags) and `ui/edgeCircleScroll` the gesture — **CW = forward, CCW = back, verified end-to-end on the SM-L705U**. The hard-won part is the RELEASE-APPLY rule: wear 1.4.1 cannot scroll an SLC mid-touch (`scrollBy` returns `used=0` while the finger is down; `animateScrollBy` reports full distance yet lands only after UP), so the circle banks travel and lands it on release via `edgeScrollWalk` — absolute ≤120px steps in `centerItemScrollOffset` space (the info `visibleItemsInfo.offset` is its SIGN MIRROR for anchored items, caught on-device) against the fixed center item, one frame apart. Wired: Chat (hands off the tail-follow per `ChatFollow` before walking), Sessions, Chats via the walk; Settings + Agent/Model/Approval dialogs via plain `ScrollState.scrollBy`. Proofs (masked): chat 86-90 → CCW 74-81 → CW 86-90 → CW clamps at tail; sessions bottom ↔ top; settings both ways; agent dialog scrolls; straight drags untouched. 373 green (+6 EdgeScroll tests) |
| 8 | ✅ | Settings ▸ Voice ▸ **Speech input provider picker**: Local Vosk default/offline · System (Google/Samsung) via `SpeechRecognizer`, honestly labeled **OFF-DEVICE** when networked, auto-detected per vendor (Pixel/OnePlus → Google) | SHIPPED 2026-10-30: `voice/SttSource` seam (`start(scope): Flow<SttEvent>` + `stop()` with flush-close semantics) + `voice/SystemStt` (`SpeechRecognizer`, main-thread, silence errors quiet, stop=stopListening→FINAL-on-results); `domain/SttProviders` owns the pure labelling (6 tests): default LOCAL even when the system engine exists (airplane-mode rule), system row reads **OFF-DEVICE · <vendor>**. Vendor auto-detect needed a real fix: the activity action resolves to NOTHING on real watches (measured SM-L705U) — the lookup iterates `android.speech.RecognitionService` → activity action → `SpeechRecognition` over `queryIntentServices`, with manifest `<queries>` for Android 11+ visibility. Resolved on every `startListening` (`WearApp.Graph.sttNow`), so picks are live, no restart. On-device: picker honest (`Local Vosk · offline · on-watch` / `System · OFF-DEVICE · Google`), highlight follows the pick, mic overlays + recognizer session on SYSTEM, Vosk reloads clean on the way back |
| 9 | ✅ | **Streaming gateway STT** — mic frames ride a WS to watchkit while speaking; engine (whisper/parakeet) streams partials back where capable; auto-fallback to local Vosk with full-buffer replay, never a lost sentence | SHIPPED 2026-10-30 (gateway + emulator + SM-L705U hardware-proven): watchkit `WS /stt/stream` (hand-rolled RFC6455 in `stt_stream_ws.mjs` — zero framework holds) takes BINARY 16 kHz mono PCM16 frames + `{"end":true}`, answers JSONL `{"partial"}* / {"final"} / {"error"}` from an engine adapter (`stt_stream.py`: whisper = buffered final-only on the existing venv; parakeet = NeMo per-window re-decode partials, honest error when nemo is absent). config.json `stt_stream_engine` picks it, `/health` reports `stt:{engine,ready}` and Settings ▸ Voice in NAMES it in the third picker row. Watch side `voice/GatewayStt`: one WS + the same 100 ms frames, buffered from the first read — if the socket is not OPEN in 2.5 s or dies unheard, the WHOLE buffer replays into `VoskStt.eventsFromFrames` (nothing spoken lost; measured takeover 450 ms, overlay never notices). Proofs: node WS probe → final + clean close 1000; **real watch** streamed listens → 630,400 B and 230,400 B engine-received, both `exit 0` (masked capture: red listening orb live on the SM-L705U); watchkit STOPPED → ws-failed→local-fallback log pair with vosk feed live; 401 on bad token. 384 tests green (+5). |
| 10 | ✅ | About ▸ **"MIT · borealBytes (github.com/borealBytes, tappable) · Superior ByteWorks, LLC · Clayton Young"** | SHIPPED 2026-10-30: About reads `MIT · 🌐 borealBytes` (ACTION_VIEW to github.com/borealBytes, toast fail-soft on watches with no browser) + `made by: Superior ByteWorks, LLC` + `author: Clayton Young`. Masked capture: full block resting clear of the rim |
| 12 | ✅ | **Video splash must actually play on a real app-drawer cold start** when VIDEO is selected (and STATIC must work when selected) — *"I close out all the apps and then I open Hermes… it shows the static image instead of the video"* | FIXED 2026-10-30: `SplashIntro.launcherShaped` + gate log now carries `launcher=`/`deepLink=`. Watch-proven: MAIN-alone launch → `launcher=true` → pocket-watch film frame captured; bare `am start -n` → `intro skipped: NotLauncher`; static mode holds then lifts |
| 13 | ✅ | **Nested Voice-in settings page + listening-overlay honesty** — *"we need a voice in settings so we can set the default one we want to use on the orb"* + *"on the screen where you are entering voice … very top small … voice input provider listed, and … if it's streaming the text back … or not … really simple, like maybe a checkbox … I don't know if it's hearing me or not"* | SHIPPED 2026-10-30, watch-proven: Settings ▸ **Voice in** is its own nested page (picker moved out of Voice out; root row names the orb's engine). The listening overlay prints a top line — masked capture: **"🛰 Gateway · whisper · text streaming ✗ · final when you stop"** at y 64–111 live on the SM-L705U; labels pure (`SttProviders.overlayName/streamsText/streamsLabel/navSummary` — honesty JVM-tested, unknown engine prints `engine ?`). Proof-of-life meter: `domain/MicLevel` (√-compressed RMS + dB mapping) published by ALL THREE engines' mic loops into `voice/SttLevel`; the overlay dot rides it — a buffered engine (whisper) still visibly proves the mic reads. Masked proofs: root row ("Voice in · Gateway · streaming"), picker page, gateway pick + honest preview, listening overlay. 392 green (+8) |
| 14 | ✅ | **Streaming-from-orb is the default for every new message** — *"anytime we get a brand new message in that we're waiting on it to complete, the text will stream from the bottom, including that little thinking thing … the only time you turn that off is within the chat session turn if I start scrolling"* | LANDED (re-arm) 2026-10-30: a run start (busy false→true) re-engages tail-follow — a mid-run scroll still owns the current turn, the NEXT run takes the tail back. The "including that little thinking thing" half (status row resting at the ORB line while awaiting, question staying on the 10:00 tick) is the hard part: three on-device iterations proved wear renders mid-clamp `layoutInfo` offsets at different PIXELS than reported (§14.13 has the measurements + the stash pointer). SHIPPED HALF 2 (same day): FOCUS HOLD §14.14 — during the wait the transcript collapses to question+status so the shipped slack rule puts both bottom-aligned on the orb crown with the whole upper circle as the reply's runway (masked `p14_hold_ship_c.png`: live "🔧 terminal…" on the crown, empty runway above; `p14_after_c.png`: answer lands, history keyed back). No offset measurement anywhere — the layoutInfo-fiction pin approach is documented dead in §14.13/14.14 |
| 15 | ✅ | **Developer testing rides the `wear-demo` agent** — *"from this point forward, all of your testing should be done in that wear demo agent… put the developer notes in the contributions stuff"* | SHIPPED 2026-10-30: `CONTRIBUTING.md` carries the developer contract (demo-agent setup recipe with its own key, the hand-back rule, dev quick card, mask discipline); AGENTS.md §0 points developers there and keeps the file as the pure install contract. All §14 hardware work after this line ran against `/p/wear-demo` |
| 16 | ✅ | **Gateway STT engine selectable + parakeet streaming on watchkit** — *"Parakeet Unified EN .6b streaming is what I'm running in Handy. I want watchkit to have its own version, not reuse my handy version, cause I want to make this generic"* + *"I think we should have the option to select the model… if you click on it, it should let you … select … installed models … Whisper is working okay, I just don't like that it doesn't live transcribe … at least we have two and we're set up to expand in the future"* | SHIPPED 2026-10-30, watch-proven: watchkit runs the **handy-computer transcribe.cpp** engine (MIT) with its OWN `parakeet-unified-en-0.6b-Q8_0.gguf` (731 MB, `.models/` — never the owner's Handy files) — CPU build, ~15x realtime, punctuation+caps. `stt_stream.py` parakeet branch = true streaming session (`stream.text().committed` append-only + tentative suffix → `{"partial"}` while talking); whisper kept as the honest buffered option; `--check` probes readiness per engine. `/health` now reports `stt.engines[]` (background-cached probe); `WS /stt/stream?engine=<id>` per-session override (unknown ids fold to config default). `scripts/parakeet.sh` = the generic installer (clone→cmake shared→bindings pip→hf download, idempotent). Watch: Settings ▸ Voice in ▸ Gateway row clicks OPEN the installed-engine picker (`★ gateway default · parakeet` / `whisper · text streaming ✗ · final when you stop` / `parakeet · text streaming ✓` — masked capture on SM-L705U), pick persists (`stt_gateway_engine`) and rides as `?engine=`; the listening overlay names the EFFECTIVE engine + inventory-truth streaming flag, refreshed when the mic opens (cold-start "engine ?" beat proven then fixed same day). Proofs: WS probe → **8 live partials before the end frame**, final 200 ms after stop (parakeet); whisper override → final-only (honest); watch logcat `ws=…/stt/stream?engine=parakeet`; overlay `"🛰 Gateway · parakeet · text streaming ✓"` + mic dot on round glass. 400 green (+8). FUTURE SCOPE (owner asked, deferred): Hermes DESKTOP mic (Settings ▸ Voice ▸ Local Transcription Model) could add this same GGUF as a selector entry — same installer, server-side only |

**Build order:** 12 → 1→3 (one geometry family, JVM rim math) → 4 → 5 → 6 → 10 → 7 → 8→9.
(12 leads because review nailed its root cause with a system-log line, below — one-line fix, and
the owner's next launch should already look right.)

**Visual-verification discipline (owner 2026-10-30):** *"screenshots … come in as the square and
so I think that's causing you to think things are working… you need to apply the circle mask
first and then look at that circle-masked image because that's what I see on screen."* A Wear
screencap is the SQUARE panel buffer; everything outside the inscribed circle never exists on
the round glass. **Every screenshot used for visual analysis is circle-masked first** —
`scripts/circle-shot.sh out.png` captures + masks in one step, `--mask in.png out.png` masks an
existing capture. An unmasked capture may only be cited for pixel-exact geometry (measuring
text x/y), never as proof a user sees something.

### 14.12 Why the film skipped the owner's launch (ROOT CAUSE, proven 2026-10-30)

The gate (§Appendix A, night audit) required `ACTION_MAIN + CATEGORY_LAUNCHER` to call a launch
"from the app grid". The One UI Watch app grid does not send the category — its cold start is,
verbatim from the watch's own logcat:

```
ActivityTaskManager: START u0 {act=android.intent.action.MAIN flg=0x10200000 cmp=dev.hermes.wearos/.MainActivity}
HermesWear: intro gate: mode=video custom= source=built-in
HermesWear: intro skipped: NotLauncher
```

So the SETTING WAS READ CORRECTLY (`mode=video`) and the gate still skipped the film into the
static spirit flash on every real drawer launch. The adb proofs that "worked" launched with an
explicit `-c android.intent.category.LAUNCHER` — a fingerprint the real launcher never produces.
(The same log ruled out the warm-start suspect: close-all really does kill the process —
`Killing 25243:dev.hermes.wearos … remove task` — so `IntroClaimed` was fresh.)

Fix: a launch is **launcher-shaped unless it proves otherwise** — the dev-harness marker
(intent extra `dev_no_intro`) or a bare component start (`action == null`, what `am start -n`
= every `scripts/*.sh` + docs/screens shot produces) decline; any `ACTION_MAIN` (with or without
the category — AOSP launcher, One UI grid, monkey) plays the owner's chosen mode. Classifier is
pure (`SplashIntro.launcherShaped`) and JVM-tested.


### 14.13 The thinking-runway investigation — wear renders mid-clamp offsets at different
pixels than they report (measurements 2026-10-30; WIP in `git stash` "thinking-runway WIP")

Goal (#14): while an answer is awaited, the status ("thinking…") row rests on the orb-crown
line and the question holds the 10:00 tick, so the reply grows upward from exactly where the
dots sit (*"including that little thinking thing"*). Findings, all SM-L705U, all on
`/p/wear-demo`:

- **The mega-anchor effect is recompose-driven and goes SILENT through the whole wait.**
  `anchor-pass` logs stopped at run-accept and resumed only at the first streamed delta
  (12 s / 31 s / 44 s gaps measured) — no composition key changes while the engine thinks, so
  a key-driven pass can never hold a waiting view. Fix: a self-healing ticker (120 ms clock,
  `rememberUpdatedState` for fresh state, live `layoutInfo` reads).
- **Mid-clamp `visibleItemsInfo.offset` is NOT pixel truth.** The ticker converged to
  `offset=119, tick=120` and HELD it (stable for seconds across `runway-sees` logs) while
  uiautomator reported the same row rendered at `bounds=[54,309]…` — the row draws ~90–190 px
  away from its reported offset depending on scroll regime. `scrollBy` converges the LAYOUT,
  not the pixels. Regimes where layoutInfo IS pixel-truthful (both hardware-proven earlier):
  top-rest (item 0 at viewport top — the #3 q-lift proof) and the settled end-clamp of
  VIEWPORT-TALLER rows (#1/#4 padding-honoring rest).
- **The 4096 tail-push overshoots for SHORT tail rows**: status row measured resting at
  y≈479 (half off-glass) after the mega push — wear's clamp honors contentPadding only in the
  settled-tall-row case; a short final row lets it blow past the padding-honoring end.
- **Top-rest SPACER pin** (the one regime whose pixels are truthful) is right for SHORT chats —
  but for long chats the history above the question cannot fit above the tick, so blindly
  growing the head spacer blanks the hold. Candidate next moves: hide-above-question during
  the hold (make every hold a short-chat case by construction), or keep the end-clamp and
  nudge BACK by (drawnBottom − crown) with the drawn position captured through the
  one-calibrated regime constant. Re-arm itself (runStarted → ChatFollow.reengaged) shipped,
  confirmed on hardware: every ask after a mid-run scroll streamed from the orb by itself.

### 14.14 The thinking-runway RESOLUTION (2026-10-30, shipped) — FOCUS HOLD

The pin approach §14.13 chased (drive rows to tick/crown by measured offsets) is DEAD on this
runtime: the second attempt proved offsets lie even in what looked like top-rest — a settled
focused hold reported `first=0@−124` with a 136 px spacer, and rows at 83/146 drew at
272/378 (uiautomator, SM-L705U). Nothing that MEASURES layoutInfo offsets can converge to
pixels here.

What ships instead needs NO offset: **focus hold**. While an answer is awaited (busy + newest
message is the wearer's question + nothing streamed + following), the transcript renders only
`spacer · caption · QUESTION · status` — the history hides. That collapsed list is short
content, so the SHIPPED slack rule (#1/#4: spacer = viewport − bottomPad − content, rest at
list top — never measures a row offset) puts question+thinking bottom-aligned on the orb crown
and leaves the whole upper circle as the runway the reply grows into. The status row is
PERMANENT during a hold — engine/tool updates BLANK the text between phases ("thinking…" → ""
→ "🔧 terminal…" measured mid-run: keying the hold on a non-empty status FRAGMENTS it and the
history flickers back); a blank line keeps its height, so the slack math never jitters.
Scrolling mid-hold releases the hold (follow off — the owner's rule). The first streamed delta
ends the hold: history returns keyed and intact, and the proven streaming paths take over.

Hardware (SM-L705U, wear-demo): masked `p14_hold_ship_c.png` — question + "🔧 terminal…"
bottom-aligned on the crown, upper circle empty, tool status LIVE inside the hold; the answer
then lands with history restored (`p14_after_c.png`). Known pre-existing caveat, untouched by
this: a SHORT final answer in a TALL chat settles via the end-clamp, which over-scrolls short
tail rows (§14.13 bullet 3) — that is the tail-push's problem, not the hold's, and it predates
#14. 402 green (+2 hold-row tests).

---


## 15. Owner batch 5 (2026-10-30): the live-review fix list — mute is mute, scroll everywhere,
   hold seated at pixels

Batch 4's review ran on the owner's own wrist, live, and produced six numbered defects (17–22 —
the table continues batch 4's numbering; 11 stays cancelled). Every one shipped hardware-proven
on the SM-L705U over the `wear-demo` profile.

| # | Defect (owner's live words) | Fix | Proof |
|---|---|---|---|
| 17 | *"if we have video selected, the static image should not show at all… it's still fine to have that noose icon that is on the orb"* | VIDEO-mode curtain = the system-splash twin (orb icon on `#07070C`), never the self-portrait; the portrait stays reserved for STATIC mode | `p17_curtain_c.png` (orb curtain) → `p17_film_c.png` (film frame 1, first-frame log 4759 ms) |
| 18 | *"it's not working on the agent screen… the settings screens… it's not working there… voice out… not working there either"* | The edge gesture lived ON the scrolled Column — a CONTENT-tall node, so the ring ellipse was computed against a ~900 px box on a 480 px face. Re-hosted on viewport-sized Box wrappers (Settings tree, Agent/Approval/Model dialogs); Chat/Sessions already rode viewport-sized lists | rim-arc `input swipe 345 58 345 422 350` → `edge circle release: 324px` + row-set change on Settings root, Voice-out, Agents dialog |
| 19 | *"I have it muted, but it still seems like it's talking to me from the orb… if it's muted, the talking shit shouldn't happen"* | `TtsConfig.muted(volume)` (0 % = mute, the 5 % grid makes equality honest): sentences are CONSUMED but never QUEUED — no `_speaking`, no orb mouth, no karaoke cursor; dialing to 0 mid-answer stops the live queue | ask at `set_vol 0`: finish logged, ZERO karaoke lines, silent TTS queue (log-proven); `-e set_vol` dev hook |
| 20 | *"it stays exactly and scrolls line by line… kind of like a block of text that's speaking is visible"* | Teleprompter: the no-nudge band (half the face) is gone — every cursor tick walks the spoken line to the 10-o'clock tick via the on-device-proven `edgeScrollWalk` (relative px, honest clamps); off-screen target rows are brought into view first; the tail-pin effect stands down entirely while `speaking`; a NEW user ask cuts the previous answer's speech queue | `p20_f_a_c.png` vs `p20_f_b_c.png` — rows flow upward WITH the voice (numbers/sec matches speech rate) |
| 21 | *"the question should start at that 10 o'clock spot… the answer gets the runway… I don't think it quite worked"* | THE HOLD SCRIM (§15.1): the seat is DRAWN, not scrolled — question at the tick px, engine status at the crown px, empty runway between | `p21_scrim_c.png`: uiautomator truth — question top y=120 = tenOclockY, status bottom y=294 = crown; haiku hold `p21_haiku_hold_c.png`, settled `p21_after_c.png` (answer on the crown, unclipped) |
| 22 | *"we should also have a tappable link to this repo… I like having one go my main borealBytes GitHub profile first though"* | About: profile chip stays first/primary; new chip opens `github.com/borealBytes/hermes-wearos` (root = default branch), browser-or-toast fallback | `p22_about_c.png` — both chips, credits intact |

### 15.1 The hold seat moved from scroll math to pixels (#21, the batch's hardest)

Three scroll-engine shapes chased the 10-o'clock seat through the collapsed hold list —
the slack-pad formula (batch-4 #14 shape), a dedicated RUNWAY row + fixed-point integrator,
and a measured-derivative integrator on pixel probes (`onGloballyPositioned`). All three
converged 60–180 px OFF on glass, and the device explained why: with the transcript collapsed,
wear-compose 1.4.1 centers its own short content — `layoutInfo` row offsets report container
positions the ink never occupies (uiautomator: container y=121, ink y=299), and the auto-centering
filler makes pad/runway derivatives regime-dependent (pad 0 → ink 295; pad 90 → ink ≈120 — a −2
slope that no static formula models across BOTH regimes). `scrollToItem(0, 0)` parks row 0's TOP
at the viewport CENTER; the min-scroll clamp swallows spacer growth whole.

The seat therefore stopped being a scroll problem. While the hold is active (the transcript was
hidden anyway), ChatScreen paints an opaque panel on the face: the question's text block starts
exactly on `RoundScreen.tenOclockY` (x = the 14 dp left pad, top = 120 px of 480), the engine
status row's BOTTOM rests exactly on the crown line (`viewport − circle-safe bottom`, the same
line settled answers rest on — batch-4 #1), and everything between is the owner's empty runway.
The orb and clock ride ABOVE the panel (MainActivity's own overlay), the panel passes touches,
and the first streamed delta lifts it into the proven streaming paths — the look the owner
accepted in #14, finally seated where #3 always asked. `ChatAnchor` lost its hold-row constants;
`ChatAnchorTest` keeps only the transcript-math contracts. 401 green.

Hardware (SM-L705U): `p21_scrim_c.png` — question ink top at y=120 (tick=120), "🔧 terminal…"
bottom at y=294 (crown=293), orb pulsing between, clock legible above; after the answer, the
tail rests unclipped (`p21_after_c.png`); speech teleprompters through it (`p21_haiku_after_c.png`
caught mid-read, last line riding the tick) and the tail-pin recovers when speech ends.

---


## 16. Owner batch 6 (2026-10-30/31): cold start is one cut, volume is dB, the wrist sees
   the desktop, comfy photos land, one tap is a keyboard, swipes are swipes

The wrist review kept rolling: six more numbered items (23–28). Every one shipped
hardware-proven on the SM-L705U over `wear-demo` (or server-proven where the fix lives in
watchkit); 423 green at ship.

| # | Defect (owner's words) | Fix | Proof |
|---|---|---|---|
| 23 | *"the icon from orb like zoomed to full screen — don't expect this — straight to video per launch selection"* | The zoom was the OS: Android 12+ exits the system splash by scaling the icon OUT the first moment the app draws. The SYSTEM SPLASH becomes the curtain — core-splashscreen's `setKeepOnScreenCondition` holds the small launcher icon until the chosen media PAINTS ITS FIRST FRAME (`SplashGate` in domain/SplashIntro, fed by IntroVideo on paint), and the default scale-out is replaced by a 140 ms alpha fade (§16.1) | cold-launch burst: 7 byte-identical held-icon PNGs (`cs_01–07`) → film frames (`cs_08+`, girl WITH headphone band — different artwork); ZERO intermediate scale frames |
| 24 | *"the audio slider for voice out is linear, fix that… audio taper / log-law volume curve — map the slider to dBFS"* | `TtsConfig.gainFor(position)` = 10^(`TAPER_FLOOR_DB`·(1−x)/20): even grid steps are even dB steps (−60 floor, hard zero at 0 %); stored values re-read as POSITIONS, so old settings land where the ear expects | `TtsConfigTest` dB-ladder + monotonicity + endpoints; the grid that was 5 % per linear-step is now 3 dB per step in the useful range |
| 25 | *"I'm not getting notifications or approvals from sessions started in the desktop, only stuff I start on the watch… the ONLY time I don't get one is the session that is actively the top thing on my watch"* | DESKTOP WATCH (§16.1): watchkit `/approvals_waiting` reads the truth straight off the state stores — approval PARKS via the gateway's `last_activity_description = "waiting for user approval"` activity beacon (heartbeat ~10 s, `approval_gateway_wait`) + FINISHED answers via message_count/last_role pulses; the watch polls every 20 s (`AwayWatch.POLL_MS`), diffs, dedups, and applies the ONE silence (viewing chat + watch-driven chats); desk parks notify OPEN-session only (their approval answers on the desktop's own ws lane — no fake inline buttons) | wrist buzzes logged: `away-watch: answer finished default/api-6117ff4b92044a5b msgs=2` (two asks curl'd at the OpenAI lane) and `away-watch: approval parked default/53199106a25d terminal` (beacon stamped in state.db); `AwayWatchTest` pins anti-stampede/dedup/silence rules |
| 26 | *the ComfyUI photo rendered on the desktop but the watch card said `path outside img_roots`* | watchkit `/img`: HOME + /tmp + /var/tmp are ALWAYS roots (the daemon already serves every session BODY via /sessions — image-typed files under the daemon user's home are strictly less sensitive); `img_roots` config adds mounts (`/run/media/<user>/Data/comfy`, `…/dev`). The extension whitelist + 15 MB cap + realpath re-checks remain the boundary | `curl /img?path=/run/…/comfy/outputs/keweenaw_aurora.png → 200`; the same chat line that 403'd now paints as an image card on the wrist (`comfy_img9.png`) |
| 27 | *"I hit the keyboard button and… it doesn't [work]"* + the keyboard's search key dead | TWO root causes: (1) the passive tap swapped IN a BasicTextField but nothing ever REQUESTED focus — the ⌨ chip's "already focused" comment was a lie; the proven PromptEditor ladder (focusRequester → frame → 120 ms → `show()` → `imm.showSoftInput`) now runs on every focus edge and the chip re-raises. (2) `maxLines = 2` made the field multiline, so the IME mapped its action key to a NEWLINE and `onSearch` never fired — `singleLine = true` hands the key back; onSearch hides the IME, sends, and collapses to the passive field with the term in it | one tap → keyboard UP (`s28_i_tap.png`); the IME action key is now the magnifier; tapping it logs `field search action typed=haikus`, dismisses, and applies (`Search · 7` via `-e search haiku`) |
| 28 | *"Agent Scroll's normal way, not bezel way, and same with sessions… Want the speed on the sessions to go faster with bezel scroll"* | The circle-lock was EATING swipes: angular velocity alone declares a rim→center SWIPE a circle (angles explode near the center), so the gesture consumed it and the list never dragged. The lock now demands velocity AND radial steadiness (`EdgeScroll.radialDrift ≤ LOCK_MAX_DRIFT_RATIO`); plus a per-call `speed` gear — Sessions rides 2f, chat/settings/dialogs stay 1f (§16.2) | normal swipe scrolls Sessions a full screen (`s28_t1_*`) AND the Agents dialog (`s28_ag_*`), gesture silent; same synthetic circle banks 76 px vs the 38 px baseline (2× exactly); a real-finger circle inside Agents logs `agents edge apply: want=38 used=38 max=1425 at=38` — the dialog DOES move |

| 29 | *"the agent screen, also the main settings screen, does not work with the edge scrolling… the voice out one… when I go select the model… those ones I'm having to do normal swiping — I want both types"* | They DID circle — at the chat's 1f reading gear, ~40–70 px per real-finger stroke: invisible on a button list, so it felt dead (Sessions only "worked" because #28 gave it 2f). The gear rule is now explicit: **prose reads at 1f (chat), lists circle at 2f** — Settings tree (every page), Agents, Model picker, Approvals mode, Chats all ride 2f; the lock's radius-wobble budget widened 10 %→14 % of face radius (~34 px) for real thumb circles (rim→center swipes still blow it ≥ 0.27) | synthetic rim circle on Settings root banks 76 px (the 1f baseline ×2 exactly) and the row-set changes (`s29_set_*`); same math drives every 2f surface |
| 30 | *"I see the small Hermes icon… but still it zooms that in all the way to take up the whole screen, static, before it shows the video… that needs to be solved"* | NOT a reversion — two compound causes, one fix: (a) the built-in film OPENS on a still shot (closed pocket watch = an orb ellipse) so fading onto the decoder's first frame lands on a big static orb; (b) the 8 s hold cap (tuned to a 4.7 s first frame) fired during an 11.5 s busy boot, releasing the splash onto the full-screen orb CURTAIN for seconds. Now: `revealIsMoving` holds the splash until the film passes its lid-opening motion (built-in only — owner media releases on its first frame), the cap became a true last resort above the intro's own 12 s give-up deadline, and EVERY overlay exit (cut/ended/budget/error/still) lifts the hold through done() | cold boot with a slow decoder: held-icon PNGs straight THROUGH the old cap point onto a MOVING frame (`cr2_08` icon held → `cr2_18` lid already opening, `splash reveal on moving frame at pos=2298ms`); zero curtain frames, zero still-orb frames |
| 31 | *"it's starting to talk at me… I went back to my watch face and got a reply where it just talked back — I should get a NOTIFICATION, not it talking… the ONLY time it should not go to notice is when the chat session is in front of me open live"* | THE ATTENTION POLICY as ONE pure brain (§16.3): `AttentionPolicy.shouldSpeak(foreground, viewingChatId, chatId)` ⇔ `!shouldNotify(…)`. VoiceHost (the TTS bridge that was answering from the pocket because its effects run while the app is backgrounded) now speaks ONLY the live-on-screen chat — and walking away mid-answer cuts the queue (no replay on return; the notification already carried it). ChatSession's notification half delegates to the same function, so the two channels can never disagree again. The two Graph signals became Compose-observable so the gate flips the instant the wearer leaves | JVM: `AttentionPolicyTest` pins every situation the owner named (watch face, other chat open, other screen, viewing-while-live); wrist: off-screen settle logged `voice suppressed — answer settled off-screen` + `notif posted`, no speech, watch-face focus held; staying on the chat logged the complement `settle suppressed — viewing chat` with NO notification and the speech path taken (audible only while Voice out is on — the policy and the owner's mute lane compose) |

### 16.1 The desktop watch: the beacon is the truth source (#25, the design-review save)

First cut polled a `/approval_decide` proxy against a `run_approval_pending_v1` table —
design review caught that the table does not exist: the gateway keeps pending approvals in
PROCESS MEMORY (`_pending` dicts), which has no HTTP surface and no durability. The honest
durable signal was already being written: a parked run heartbeats
`sessions.last_activity_description = "waiting for user approval"` every ~10 s
(`approval_gateway_wait.activity_heartbeat`), so *beacon text + fresh `last_activity_at`*
IS a parked approval, right now, per profile. The parked tool call itself is recovered from
the session's last assistant message `tool_calls` (persisted before execution); what it lacks
is a request_id — and desk parks answer decisions on their own ws lane anyway — so the wrist
notification carries one honest action: OPEN SESSION (watch-started runs keep inline
Approve/Deny through their own stream). `default` matters most (that's where desktop sessions
live) and has no directory — its store is the HOME `state.db`, the same resolution `/sessions`
uses. Client side, `domain/AwayWatch` (pure, JVM-tested): first sweep SEEDS cursors silently
(a launch must not stampede the wrist with everything that aged while the watch slept), parks
dedup by chatId|tool|args and are FORGOTTEN when their beacon ages out (a re-park buzzes
again), pulses buzz only when the count grew AND the tail message is an ASSISTANT one
("an answer exists", not "someone typed"), and the suppression set is exactly the owner's
rule: the chat top-of-screen on the chat page, plus chats whose runs the watch itself drives.

### 16.2 A circle is a radius, not just an angle (#28)

`LOCK_DELTA_DEG` (angular velocity) separated rim-circles from rim-DRAGS, but not from
SWIPES: a normal swipe that STARTS on the rim crosses the face, and angle-vs-center spins
fast near the middle — velocity screamed "circle", `consume()` ate the gesture, and both
Sessions and Agents felt dead to a normal thumb (batch-4 #7's gesture had been quietly
stealing the ordinary scroll it was supposed to complement). `radialDrift` closes it: a
circle holds its distance-from-center (≤ 10 % of the face radius at the lock moment ≈ 24 px
on the 480 face); a rim→center swipe blows the budget by event two and keeps its drag.
Telemetry makes both failure modes answerable from logcat: a locked stroke banking 0 px logs
`banked 0 (locked, N events, M deg)` (a jitter-cancelled short touch, not a dead gesture),
and the Agents apply site logs `want/used/max/at` — which settled the batch-5 "dialog banks
but never moves" mystery: with a real finger the apply runs (`used = want = 38`,
max = 1425 px of range), and adb's standalone `motionevent MOVE` injections simply never
reach a Dialog window (only the window that caught the DOWN receives the rest of the
sequence — a lab limit, not a user bug). 423 green (+3 drift/gear tests).

**#29 follow-through:** the 10 % budget was a synthetic-finger number — real thumb circles
wobble ~34 px on the 480 face, so `LOCK_MAX_DRIFT_RATIO` went to 14 % (rim→center swipes
still blow it by 2×). And the gear rule became explicit where #28 left it implicit:
**prose surfaces (chat) read at 1f, every LIST surface circles at 2f** — Settings tree,
Agents, Model, Approvals and Chats were circling at 1f (~40–70 px a stroke: imperceptible on
a button list, felt dead) and all moved to 2f.

---

### 16.3 THE ATTENTION POLICY — voice is for the wrist, buzz is for the pocket (#31)

The owner stated this as a PERMANENT REQUIREMENT after noticing behavior flip-flop across
batches ("get this type of stuff also into our spec doc… we need to really get that clear"):

> **Any completion or approval, from ANY lane — watch, desktop, web UI, plain API — must reach
> me. What decides the CHANNEL is one fact: is that session live in front of me?**
>
> | Situation | Voice (TTS) | System notification |
> |---|---|---|
> | Chat open on the chat screen, app foreground | ✅ speaks (as configured: engine, dB volume, mute) | ❌ none — redundant |
> | Watch face / ambient / another app / screen off | ❌ silent | ✅ answer-ready or approval buzz |
> | Sessions / Settings / Chats / any other screen | ❌ silent | ✅ |
> | A DIFFERENT chat open on the chat screen | ❌ silent (voice from an invisible conversation is "talking in my pocket") | ✅ |

Both halves read `domain/AttentionPolicy` — `shouldSpeak(foreground, viewingChatId, chatId)`
is the single decision and `shouldNotify` is its exact complement. The historical bug in each
direction is named in code: `VoiceHost` runs inside the activity's composition, so its effects
fire while the wearer is backgrounded (that's the watch-face voice the policy kills); and a
notification fired from the front chat is double-signalling a message already on the glass.
The third twin, caught on the wrist during verification: the DESKTOP WATCH loop (AwayWatch)
silenced by LOCAL chat ids while watchkit keys its pulses by the GATEWAY `session_id` — the
viewed session buzzed while being read. `AwayWatch.watchSilence` now builds the set in the
session-key space and asks [AttentionPolicy] for the viewing branch (JVM-pinned). Walking away
mid-answer CUTS the speech queue and the skipped tail is not replayed on return — returning to
a chat to find it re-reading a finished answer is the same violation wearing a different hat.
`AttentionPolicyTest` pins every row of the table above; changing the policy means changing
that test, this section, and the KDoc together — never code alone.

**The buzz must SURVIVE the pocket (#32).** A wrist-verified failure the media round caught:
backgrounded with nothing keeping the process warm, the in-app 20 s away-watch loop died within
a minute of `onActivityStopped` (Samsung caches/freezes the app and its coroutines with it) —
so the policy's whole off-screen column silently evaporated in a real pocket. `AwayWatchWorker`
(now THE POCKET LIFELINE) chains ONE-TIME WorkManager work (~55 s hops, CONNECTED constraint)
armed on the last activity stop and cancelled on resume; foreground keeps the 20 s loop. Both
callers share ONE `SweepCursor` (anti-stampede first-sweep, park dedup, message-count cursors)
so the handoff cannot double-buzz (`SweepCursorTest`). Wrist proof: chain requests at 55-60 s
cadence after the loop went quiet; an unattended off-wrist settle buzzed; 4 minutes of
screen-off darkness delivered 3 sweeps (old build: zero). Honest limit: under real Doze,
CONNECTED-constrained work defers to maintenance windows — minutes of latency, not seconds.

## 17. MEDIA EPIC — generated video/audio play in chat (owner 2026-11-02)

Owner doctrine: *anything the desktop Hermes app shows, the wrist shows.* Generated A/V arriving
in a session transcript (from comfy, from skills, from any lane) must appear as a card and PLAY
on the watch, like images already do.

**Parsing (`ui/md/WatchMarkdown.kt`).** Media targets are extension-routed across all three
transport shapes the gateway produces: `![desc](target)`, `MEDIA:<target>` lines, and BARE
whole-line paths/URLs. `.mp4/.mov/.mkv/.webm…` → `MdBlock.Media(video=true)`;
`.mp3/.wav/.m4a/.ogg…` → `Media(video=false)`; image extensions keep the F6 `MdBlock.Image`
lane untouched. Two safety rails: a media path *inside* a sentence stays prose (the card is for
whole-line attachments only), and a half-arrived extension (`.mp` mid-stream) stays a paragraph
until streaming completes — no flicker mid-token. Nine routing tests pin all of it.

**The card + player (`ui/md/MdText.kt` MediaBlock, `ui/md/MediaPlay.kt`).** Video cards render a
poster JPEG and open a fullscreen `Dialog` ExoPlayer on tap (play/pause, ±15/−5, scrubber,
hardware back → straight back into the session). Audio cards play in place with a progress line.
Remote http(s) targets play DIRECT (no bearer — a public URL needs no secret); gateway-host
absolute paths stream through watchkit `/media` with the bearer header.

**Server side (watchkit `/media`, SPEC §13 surface).** ffmpeg pre-shrink before the wrist ever
sees bytes: H.264 faststart mp4 (AAC audio — hardware decode) / **Ogg/Opus for standalone audio**
(the best current codec; falls back to AAC m4a on boxes whose ffmpeg lacks libopus — probed once),
quality presets from the watch's `q=0..100` (q<34 →
≤360p/350k, <67 → ≤480p/700k, else ≤540p/1.2M), `mono=0/1`, `ab=<kbps>`; `poster=1` returns a
JPEG frame, `probe=1` metadata; HTTP Range responses so ExoPlayer seeks. Cache key =
realpath|mtime|params under `.cache/media` — same file + same knobs is a <5 ms hit; a knob
change is a fresh key and a re-transcode (verified live: sharp+stereo produced a new 4.66 MB
derivative beside the balanced/mono 2.83 MB). Roots are the same `img_roots` policy as `/img`.

**The proxy problem.** Every leg rides the embedded tailnet node (§11.4) through a loopback
SOCKS shim — but media3 1.4.1 has no `DataSource.Factory.setProxy` (added upstream in 1.5) and
its `DefaultHttpDataSource` rides libcore `HttpURLConnection`, which consults
`java.net.ProxySelector.getDefault()`. So the player installs `TailnetRoute.SELECTOR` as the JVM
default selector for its lifetime and restores the prior one on dispose — the player reaches the
tailnet exactly like OkHttp does, and nothing else in the process ever sees the swap.

**Settings ▸ Media (owner: "a quality slider… and let me pick stereo for my headphones").**
`QualitySlider` (custom — wear-compose 1.4.1 ships no Slider; same drag physics as the proven
RateSlider, commit-on-release), a Stereo audio switch (off = mono for the watch speaker), and a
2×2 audio-bitrate chip grid (32/64/96/128k). All persist in `AppSettings`
(`media_quality`/`media_stereo`/`media_audio_kbps`), mirror into `Watchkit.mediaPrefs`, and ride
every `/media` URL the watch requests. `WearApp.loadMediaPrefs` rehydrates at boot.

**Proof.** `-e render av` (RenderCases.Av — real gateway-box files); Watch Ultra screenshots show
the poster card, the playing fullscreen player, and the session restored on back; the emulator
UI pass shows the full Media settings page and the knob→server round trip end to end.

## 18. DISTRIBUTION — every PR ships an APK; @rc rolls; tags are the release (owner doctrine, 2026-11-06)

Three lanes, one build contract. Every lane runs `gradle :app:assembleRelease` with the
ci.yml pins (temurin JDK 17 · gradle 8.13 · platforms;android-35 · build-tools;35.0.0) and
zipalign+apksigner CI-side — the tailnet node rides the committed AAR (§11.4), so CI has no
Go/NDK leg anywhere. One deliberate asymmetry: the PR lane assembles with
`-x lintVitalRelease`, because the release-target fatal-lint gate is a judgment the @rc and
v-tag lanes enforce verbatim (as of this writing it aborts on
`InvalidFragmentVersionForActivityResult` — `androidx.wear` resolves fragment 1.2.4; the fix
is an explicit fragment ≥ 1.3.0 dependency in app/, tracked there, not here). A preview you
can install should not be hostage to a gate whose whole job is guarding the Release.

| Lane | Trigger | Signed with | Where it lands | Verify |
|---|---|---|---|---|
| **PR preview** | any PR to main (`pr-apk.yml`) | **ephemeral CI debug key** — keytool mints a fresh keypair in-job; the release secrets are never read | workflow artifact `wearos-pr<N>` (APK + SHA256SUMS.txt, 30 d retention); one idempotent marker-comment on the PR carries the exact run id + sha256 | `sha256sum -c SHA256SUMS.txt` |
| **Rolling @rc** | every push to main (`apk-release.yml`, `preview` job) | **real release keystore** — same cert as the v-tags | one mutable GitHub **prerelease** on the moving tag `@rc`: the tag is force-moved to the new main SHA and the assets replaced in place (`overwrite_files`) — no cruft pile-up | `gh attestation verify` + `sha256sum -c` |
| **Release** | `v*` tag (unchanged — the shipped v0.6/v0.7 chain) | real release keystore | GitHub Release on the version tag, SLSA-attested | same |

**Who signs what, and why it matters.** One key (`HERMES_KEYSTORE_B64`, header of
apk-release.yml) defines the upgrade chain: Play ↔ @rc ↔ v-tags all carry its certificate, so
each replaces the previous without an uninstall. PR previews deliberately sit OUTSIDE that
chain: same applicationId `dev.hermes.wearos` + different signature ⇒ uninstall-before-swap,
and every re-run mints a new key, so a preview is a try-it, never an upgrade path. That is
the honest trade that lets the APK-on-every-PR rule exist without ever exposing — or risking
rotation of — the release key in a PR-scope job.

**@rc is a moving target by design.** It answers "what does current main feel like on my
wrist?" and nothing else: it can be rewritten under you by the next merge (job-level
concurrency serializes back-to-back merges so two runs never fight over the tag), it is
`prerelease: true` + `make_latest: false` so it never poses as Latest, and its one stable
asset name (`hermes-wearos-@rc.apk`) always means "latest main". Consumers wanting stability
pin tags. Docs/markdown-only pushes don't even trigger the build (`paths-ignore` on main
pushes; a real release commit always edits VERSION, so tags are never gated by it, and
`workflow_dispatch` remains the escape hatch).

**Verify commands (copy-paste):**

```bash
gh run download <run-id> -n wearos-pr<N> --repo borealBytes/hermes-wearos -D wearos-pr<N>  # PR preview
cd wearos-pr<N> && sha256sum -c SHA256SUMS.txt                                              # every lane
gh attestation verify --repo borealBytes/hermes-wearos hermes-wearos-@rc.apk # @rc + v-tags
apksigner verify --print-certs <apk>   # who signed it: release key vs CI debug key
```

## Appendix A — Landed slices (build log, newest first)

-- **Batch 7 — the tunnel IS the transport, agents key themselves, A/V plays on the wrist
  (2026-11-01/02, watch-proven on the Watch Ultra AND emulator).** EMBEDDED TAILNET (§11.4): a
  real tsnet node inside the app (gomobile AAR + CONNECT-only SOCKS5 shim, `TailnetRoute.SELECTOR`
  injected into OkHttp) — every REST/SSE/WebSocket call rides the tailnet, no LAN path, no
  phone-tailnet; the root cause of every early tunnel timeout was a SOCKS parser bug (the domain
  length IS the 5th CONNECT byte). NOTIF DEEP LINK: "Open session" switches to the owning agent
  AND opens that exact session. SETTINGS rows got leading chevrons. AUTO-KEY NEW AGENTS (§12.2
  addendum): watchkit force-seeds on Learn and sweeps every 60 s, so agents created after setup
  are keyed within a minute, no human step. MEDIA EPIC (§17): media cards + fullscreen
  ExoPlayer + watchkit `/media` pre-shrink + Settings ▸ Media (quality slider, stereo, bitrate);
  pinch zoom-out floor lowered to 0.55 (owner reads small text fine).

-- **Batch 6 — cold-start cuts, dB volume, the desktop watch (#23–#28, 2026-10-30/31,
  watch-proven).** The system splash holds the small launcher icon until the chosen intro media
  paints its first frame, then a 140 ms alpha fade replaces the Android-12 icon zoom-out (the
  cold start is ONE cut: icon → film; `SplashGate`); reply volume became a dB taper
  (`TtsConfig.gainFor`: log-law curve, −60 dB floor, hard zero at mute); DESKTOP WATCH — desktop
  sessions now reach the wrist via watchkit `/approvals_waiting` (approval parks read from the
  gateway's `waiting for user approval` activity beacon + finished answers from message-count
  pulses over every profile's state store) and a 20 s watch-side diff (`domain/AwayWatch`,
  anti-stampede seed, park dedup, assistant-only buzzes, the ONE viewing-chat silence); watchkit
  `/img` roots widened (home + tmp implicit, mounts in config) so comfy photos render on the
  wrist like on the desktop; the Sessions search field got the proven keyboard ladder (one tap
  raises the IME, ⌨ chip re-raises) and `singleLine` (the dead search key is the field's
  onSearch now — it hides, sends, lands the term); the edge-circle lock now demands radial
  steadiness (normal swipes scroll Agents/Sessions again) with a per-surface speed gear.
  Follow-through on the same wrist review (#29–#31): every LIST surface circles at 2f (Settings
  pages, Agents, Model, Approvals, Chats — prose chat stays 1f) and the lock's wobble budget
  widened 10 %→14 % for real thumbs; the cold start reveals on a MOVING film frame
  (`revealIsMoving` — the built-in's still closed-watch opening + the too-short 8 s hold cap
  were the "icon zoomed and sat there static" the owner vetoed), the cap realigned above the
  intro's own 12 s give-up deadline, every overlay exit lifts the hold; and THE ATTENTION
  POLICY (§16.3) became one pure brain — voice only for the chat live on screen, everything
  else buzzes, walking away mid-answer cuts the speech queue, `AttentionPolicyTest` pinning
  every row — and the wrist review caught the third twin: the desktop-watch loop silenced by LOCAL chat
  ids while watchkit keys pulses by GATEWAY session_id, so the VIEWED session buzzed while being
  read; `AwayWatch.watchSilence` now speaks session-keys and asks the policy for the viewing
  branch. 437 green.

-- **Batch 5 — the live-review fix list (#17–#22, 2026-10-30, watch-proven).** Video splash shows
  the orb-icon system-splash twin instead of the static portrait; the edge-circle gesture re-hosted
  on viewport-sized hosts so Settings/Voice-out/Agents/Approvals/Models scroll by rim as chat does;
  0 % reply volume now speaks NOTHING (queue skipped, live queue cut, orb face silent —
  `TtsConfig.muted` + gates in `SpeakAloud`); karaoke became a teleprompter (relative
  `edgeScrollWalk` toward the tick each cursor tick, tail-pin yields while `speaking`, a new ask
  cuts the previous answer's speech); the focus-hold seat became the DRAWN HOLD SCRIM — question
  ink pixel-exact on the 10-o'clock tick, engine status bottom pixel-exact on the 8:20 crown,
  runway empty between (SPEC §15.1); About gained the repo chip under the profile chip.
  Proofs: `p17_*`, `p19_*` (log), `p20_f_*`, `p21_scrim_c`/`p21_haiku_hold_c`/`p21_after_c`,
  `p22_about_c` in the session shots dir. 401 green (hold-row row-math tests retired with their
  layout; +1 `TtsConfig.muted` test).

 **Voice-in page + listening honesty (batch item 13, 2026-10-30, watch-proven).** Settings
  gained a nested **Voice in** page (the STT picker moved out of Voice out — the owner's framing:
  the orb's default engine is a VOICE-IN decision). The listening overlay now carries a very-top
  status line — provider name (+ the live gateway engine) and the streaming truth as a checkbox
  sentence: `text streaming ✓` vs `text streaming ✗ · final when you stop` (buffered whisper is
  honestly labelled, and the wearer learns to expect the silence). Below it rides the
  proof-of-life dot: `domain/MicLevel` √-compressed RMS from the real mic frames (Vosk/Gateway
  AudioRecord loops; SpeechRecognizer's `onRmsChanged` dB window for System) published through
  `voice/SttLevel` — the dot moves while you speak even when the engine withholds text until
  the stop, which is the exact complaint this entry closes ("I don't know if it's hearing me").
  Labels are pure and JVM-tested; unknown engine prints `engine ?`, never a guess. Masked proofs:
  root row, picker page, gateway preview, live overlay line (uiautomator bounds y 64–111).
  392 green (+8: 4 MicLevel, 4 SttProviders).

- **Developer contract split (batch item 15, 2026-10-30).** CONTRIBUTING.md now carries the
  developer rules — headline: **test traffic rides the `wear-demo` profile** (a copy of `wear`
  with its own key, reset freely; setup recipe included), the dev-session hand-back rule, and
  the circle-mask discipline. AGENTS.md keeps the install contract and §0 points at it.

- **Thinking runway (§14 #14, 2026-10-30, WIP stashed — full measurements in §14.13).** Goal:
  status row on the orb crown while awaiting, question on the 10:00 tick. The mega pass cannot
  hold it (silent through the whole wait — key-driven recompose), the ticker proved
  wear's mid-clamp offsets are NOT pixel truth (stable `offset=119` while the row drew at
  y≈309), and the 4096 tail-push over-scrolls past the padding-honoring end when the tail row
  is short. Re-arm half shipped + hardware-confirmed (new run → orb-streaming by itself); the
  runway half lives in `git stash` "thinking-runway WIP" with the candidate regimes next to the
  numbers. All traffic on `/p/wear-demo` per CONTRIBUTING.

- **Thinking runway, shipped as FOCUS HOLD (§14 #14, 2026-10-30, watch-proven).** The re-arm
  half shipped first (`40e1cf5`): a run start re-engages tail-follow, so streaming-from-orb is
  every new message's default and a mid-run scroll only owns the CURRENT turn. The held-window
  half went through three offset-tracking attempts (all documented dead in §14.13: wear renders
  mid-clamp layoutInfo offsets at different pixels — even a settled list reported `first=0@−124`
  over a 136 px spacer). The shipped answer abandons measuring entirely: during the wait the
  transcript renders only spacer·caption·question·status, making the hold SHORT CONTENT by
  construction, and the proven slack pad rule (spacer = viewport − bottomPad − content) seats
  question + thinking bottom-aligned on the orb crown with the whole upper circle as the reply's
  runway. A second hardware lesson shipped with it: engine/tool updates BLANK the status between
  phases, so the hold keys on busy+unanswered-question and the status row stays (blank) all
  through — keying on status text fragments the hold mid-run. Masked proofs: `p14_hold_ship_c.png`
  (question + live "🔧 terminal…" on the crown, empty runway), `p14_after_c.png` (answer lands,
  history returns keyed). 402 green (+2).

- **Selectable STT engines + watchkit-native parakeet streaming (batch item 16, 2026-10-30,
  watch-proven).** The owner runs Handy on their Mac and wants the same feel on the watch — but
  GENERIC: watchkit gets its own `parakeet-unified-en-0.6b-Q8_0.gguf` (Q8_0, 731 MB) driven by
  transcribe.cpp (MIT, the engine Handy itself is built on), CPU build (~15x realtime, no GPU
  needed), installed by `scripts/parakeet.sh` on any Linux box (clone → cmake shared → pip
  bindings → hf download, every step skip-if-present). `stt_stream.py` learned the real
  streaming shape: the unified parakeet's `stream.text().committed` is append-only, so partials
  are HONEST text, not a re-decode guess — whisper stays as the buffered option and the overlay
  tells you which you're holding. Engine selection end to end: `/health stt.engines[]` (probe
  cached in the background — a failover probe must not pay a 731 MB model load), `?engine=`
  per WS session (unknown → config default, never an error mid-sentence), watch picker under
  Settings ▸ Voice in ▸ Gateway listing EXACTLY what the box reports (not-installed reads
  "not installed"), pick persisted and sent on the URL. Hardware proof chain: WS probe 8
  partials BEFORE the end frame; watch overlay "🛰 Gateway · parakeet · text streaming ✓" with
  the live mic dot on the SM-L705U (masked); logcat `?engine=parakeet`; picker page masked
  capture; whisper override final-only. Cold start exposed the last lie (overlay said
  "engine ?" until the Voice-in page had been opened) — fixed by refreshing /health when the
  mic opens. 400 green (+8). Hermes DESKTOP mic integration (same GGUF as a Local
  Transcription Model entry) recorded as future scope.

- **Gateway streaming STT (batch item 9, 2026-10-30, gateway+emulator+watch-proven).** The mic frames
  the watch already captures now ride a WebSocket to the gateway while the wearer talks:
  watchkit `WS /stt/stream` (hand-rolled RFC6455 subset — masked text/binary + close — to keep
  the zero-framework vow) pipes binary PCM16 into an engine adapter process and relays its JSONL.
  `stt_stream.py` speaks two engines: whisper (existing venv, buffered — final only after the
  watch's `{"end":true}`) and parakeet (NeMo, honest error when nemo is absent);
  `config.json stt_stream_engine` selects, `/health` reports `stt:{engine,ready}`, and the
  Settings ▸ Voice picker row names the live engine. `voice/GatewayStt` keeps a 20 s frame
  buffer from the first mic read: no-open-in-2.5 s, socket death, or an unheard `{"error"}`
  replays EVERYTHING through `VoskStt.eventsFromFrames` — measured 450 ms takeover with the
  overlay still listening, so the wearer never loses a sentence to a dead gateway. The provider
  normalize bug this work exposed (setSttProvider collapsing gateway→local) is pinned by a test.
  Proof set: node WS probe (final + close 1000, 401 for bad token), emulator streamed listen
  (630,400 bytes engine-received, whisper final, clean client-close), watchkit-stopped fallback
  (masked screenshot: still `listening…`), and the REAL WATCH closed the set: two streamed
  listens from the SM-L705U — 630,400 B and 230,400 B engine-received, both `exit 0`, one with
  the masked red listening orb on the round glass. 384 green (+5).

- **Speech-input provider picker (batch item 8, 2026-10-30, watch-proven).** Settings ▸ Voice
  grew a `🎙 speech input` section: **Local Vosk** (default, `offline · on-watch`) and
  **System** (`SpeechRecognizer`, labelled `OFF-DEVICE · <vendor>` — the owner's word was
  HONESTLY: the wearer must never assume airplane-mode cloud recognition). `voice/SttSource` is
  the seam both engines implement (MainActivity's loop is provider-blind); `voice/SystemStt`
  encodes the engine's quirks (main-thread, silence NO_MATCH/TIMEOUT close quietly, stop() =
  stopListening so the FINAL flushes before the flow closes). Vendor auto-detect found the
  hard way: `resolveService(ACTION_RECOGNIZE_SPEECH)` returns null on real watches even when
  recognition is available — vendors declare the SERVICE action, so the lookup iterates
  `android.speech.RecognitionService` first (plus manifest `<queries>` for visibility); this
  watch names Google. Picks persist (`AppSettings.stt_provider`) and apply on the next
  `startListening` via `WearApp.Graph.sttNow()` — offline-safe fallback to Vosk if the picked
  engine vanished. Masked proofs: default state, System picked (highlight follows), listening
  on SYSTEM, Local restored with Vosk reloading. 379 green (+6 SttProviders).

- **Edge-circle scrolling (batch item 7, 2026-10-30, watch-proven).** Circling the rim scrolls
  every list: `domain/EdgeScroll` (pure math: ring ≥ 0.80 r, `ANGLE_STEP_DEG` 7°, gain 0.65,
  cap 60 px/event, `LOCK_DELTA_DEG` 2.5°/event) + `ui/edgeCircleScroll` (Initial-pass pointer
  claim, angular-velocity lock so straight rim-drags stay with the list) + `edgeScrollWalk`
  (the release-apply mechanism). Three on-device findings are encoded in their KDoc the hard
  way: mid-touch `scrollBy` is dead (SLC touch channel, `used=0` per tick), `animateScrollBy`
  lies (full distance, lands only after UP), and `visibleItemsInfo.offset` is the SIGN MIRROR
  of `scrollToItem`'s space for anchored items (`got=-want`, 8/8 samples) — so the walk issues
  absolute steps against the CENTER item in `centerItemScrollOffset` space, last-write-wins,
  one frame apart. Sign truth: screen-CW = positive = forward. Chat hands off the tail-follow
  (`ChatFollow` rule) before walking. Dev hook `-e agents 1` opens the picker for dialog proofs.
  Masked proofs: chat round-trip 86-90 ↔ 74-81 + tail clamp, sessions both ends, settings both
  ends, agent dialog, straight drags untouched. 373 green (+6 EdgeScroll).
- **#6 owner-correction: buffer = CONTENT, not viewport (2026-10-30, watch-proven).** The first
  #6 cut still shrank the scroll *window* on Settings root + the three dialogs (`roundSafe()` /
  pre-scroll `padding(top=30,bottom=18)`). Owner veto ("cutting off stuff at the top and bottom
  … like you did on the chat window"): the scroll strip is now the WHOLE face — content padding
  only, after `verticalScroll` (settings root: `.padding(horizontal=20)` → `verticalScroll` →
  `.padding(top=12, bottom=circleSafeBottomDp(20,12))`; dialogs top 34 / bottom circle-safe).
  Masked proofs: settings root, Watch page, About page END (last row rests in the meat with the
  blank buffer beneath), agent dialog top + end.

- **Orb-flanking approvals (batch item 5, 2026-10-30, watch-proven).** A live approval while the
  transcript rests at its tail paints ✅ on the old agent spot and ❌ on the sessions spot at
  1.45× radius (`ApprovalFlank`), driven by `chatAtBottom = chatFollow` — so the buttons exist
  exactly when they can be understood and never dangle by the orb (owner: *"no permanent
  mis-click bait"*). The in-list card became pure context — its permanent OK/No pair WAS the
  vetoed pattern — and forced-centering now runs only as the scrolled-away rescue, which is also
  the fix for "buttons never appeared when the approval arrived mid-scroll": scroll to the
  bottom and the card + flanks appear together. Both UIs resolve the gateway verb vocabulary
  through one pure helper (`domain/ApprovalChoices`, 3 tests). Dev hook: `-e approval_demo 1`.
  Proof trio (circle-masked): bottom (✅ left, orb, ❌ right, question fully readable), scrolled
  away (both gone), ✅ tapped (approval clears, chat returns). 367 green (was 364).

- **The circle-meat geometry family + the round-detection bug batch 4 was hiding (items 1–3,
  2026-10-30).** `BottomLayout.circleSafeBottomPad` is the new one-source-of-truth bottom line —
  the larger of the orb-crown rule and the physical rim at the text gutter (`RIM_INSET_RATIO`
  0.98), square faces falling through to the old rule unchanged; `RoundScreen.tenOclockY` is the
  matching upper anchor (y = h/2 − min/4 — the 10:00 tick, universal across shapes). Karaoke
  re-based onto it (band 12–35 %), and the transcript's bottom pad now rides the rim rule.
  **Latent bug proven and fixed on the way:** `isRoundScreen()` used the bit trick
  `(screenLayout and 0x03) == 0x02` — WRONG mask (round lives under 0x0C/0x08) — and had been
  silently FALSE on the owner's SM-L705U all along, i.e. every "round" tuning in the app had run
  in square mode; replaced with the platform's own `isScreenRound`. That flipped roundInsets on
  for the first time (chat rows +64 px down, lines re-wrapping), so the chat list deliberately
  keeps `topOverrideDp = 0` (its true clearance is the bottom-cluster pad + the spacer math);
  per-surface retune of the OTHER lists is item 6. A padding lever for the mid-stream tail
  over-corrected (black transcript, caught mid-oscillation); the honest fix is delayed re-asserts
  of the end — wear computes the clamped END from the row's PREVIOUS layout pass, and re-asking
  after 70–150 ms settles the growing tail on the safe line. Question-lift = measured-offset
  (spacer − (rowTop − tick)), state-based, stable at its result. Owner's verification rule now
  tool-enforced: **`scripts/circle-shot.sh`** masks every analysis screenshot to the inscribed
  circle (unmasked square caps show pixels the round glass never had). 6 new pure tests
  (**364 green**, was 358).

- **The film answers the real launcher (batch item 12, 2026-10-30).** `SplashIntro.launcherShaped`
  inverts the fromLauncher rule (full root cause + the watch's own log lines in §14.12): any
  ACTION_MAIN launch plays the owner's chosen mode — the One UI Watch grid sends MAIN with no
  CATEGORY_LAUNCHER — while the harness declines as before (bare `am start -n` = no action) and
  gains an explicit `--ez dev_no_intro 1` in `setup.sh`/`verify-chat.sh`. The gate log line grew
  `launcher=`/`deepLink=` so the next splash mystery names itself. Proof on SM-L705U: grid-shaped
  cold start → `intro gate: mode=video … launcher=true` → `intro: first frame 5846ms in` → the
  pocket-watch frame screenshotted mid-film; bare `am start -n` → `intro skipped: NotLauncher`;
  `mode=static` cold start → still holds, lifts to chat by +5 s. Owner's setting restored to
  VIDEO. 2 new pure tests (**358 green**, was 356).

- **The run survives the wrist — notification wake-tier trio (2026-10-29 night, owner live-fire:
  *"wait thirty seconds, then send a haiku and immediately went to the watchface… i did not get a
  notification"*).** Three stacked causes, each proven by forensics then fixed: **(1) the gate
  never knew the wrist left** — `viewingChatId`/`isForeground` cleared only on STOP, but a watch
  going to the face/screen-off just PAUSES (Samsung ambient can hold STOP off for minutes) ⇒
  `onActivityPaused` now clears the published view and MainActivity's lifecycle observer
  re-publishes on ON_RESUME (wrist-up-over-the-same-chat still correctly stays silent);
  **(2) the radio slept with the screen** — the SSE died at the transition and status-poll ate
  `failed to connect … after 15000ms` rounds: a PARTIAL_WAKE_LOCK keeps DeviceIdle-ACTIVE but does
  NOT stop Wi-Fi power-save ⇒ `RunWakeLock` now holds `WIFI_MODE_FULL_LOW_LATENCY` alongside the
  CPU lock, same run scope, same 10-min auto-expiry. **(3) Samsung FREEZES the cached process** —
  post-transition a dev broadcast sat enqueued, unprocessed: a frozen process cannot poll, stream,
  or notify, and no lock unfreezes it ⇒ **`notify/RunKeeperService`** (silent ongoing
  "answer in flight" card, `dataSync` FGS type) started by `RunWakeLock.acquire` and stopped by
  `release` — run-scoped by construction. Watch-proven end-to-end with screen off: `wakelock
  acquired` → `run keeper in foreground` → `finish run` → `notif posted`, card living in Wear's
  `Aggregate_AlertingSection` (DND verified off).

- **Karaoke day-two + the film's patience (2026-10-29 evening).** The spoken line now FOLLOWS the
  voice: self-calibrating in-utterance cursor (`domain/KaraokeCursor`, EMA chars/sec, JVM-tested),
  hand-off to the SETTLED message row when the live item vanishes, and `programmaticScrollAt`
  (400 ms stamp) so karaoke's own scrolls never trip ChatFollow's user-scroll detector
  (wear-compose raises `isScrollInProgress` even for instant `scrollToItem` — measured). Splash:
  `FIRST_FRAME_BUDGET_MS` 8 s → **12 s** after a same-session cold start logged a 5.49 s first
  frame that WOULD have played — the poster makes waiting invisible, so the only thing a bigger
  ceiling costs is patience on a bad boot. Both modes hardware-proven via adb LAUNCHER cold
  starts (`mode=video` blue film; `mode=static` holds 1.2 s then lifts).

- **Voice-out config + dial volume + per-chat approvals + notif routing + the settings tree

- **Voice-out config + dial volume + per-chat approvals + notif routing + the settings tree
  (SPEC §12.8–§12.10, owner batch 2026-10-29, all emulator-proven).** Engine voice picker +
  drag speed slider (`domain/TtsConfig`, `SpeakAloud.applyConfig` live mid-queue); dwell 2 s on
  the 🔇 dial lane arms reply-volume mode — swipes ride per-utterance `KEY_PARAM_VOLUME` on a 5%
  grid with a `🔊 xx%` badge, `STREAM_MUSIC` untouched (`domain/DialVolume`); new ✅ approvals
  lane + `ApprovalModeDialog` persisting Inherit/Manual/Smart/Off per chat, enforced CLIENT-side
  at approval arrival because gateway approvals are profile-scoped (`domain/ApprovalMode`);
  notifications for every case except the chat being the open front screen, gated on
  `Graph.viewingChatId` (MainActivity publishes, `ChatSession.viewingThisChat` decides); and the
  flat settings screen became a hub + 6 pages with zero logic change (`if (page == …)` wrappers,
  live-subtitle nav rows, BackHandler). Same-day follow-ups: Agent keys nested UNDER Gateways
  (its parent is the gateway it was learned from — `SettingsPage.up()` drives back), and the
  voice picker grew into ENGINE → local/network → voice with a per-utterance FALLBACK chain
  (`domain/VoiceCatalog`, engine restart on package switch, proven against the watch's real
  Google + Samsung engines). +19 tests → 350 green.

- **The route follows the link (SPEC §11.6, owner: *"let's make it flawless for discovering what's
  available network wise"*).** Failover was demand-driven: the app discovered a network change by
  failing one ask, so a winner learned at home stayed authoritative for minutes after the watch had
  left. Now `net/NetworkMonitor` holds ONE default-network callback on the Application graph and a
  pure policy (`domain/NetworkWatch.kt`) decides what counts as a change — identity is the network
  **interface** + link kinds + vpn + resolvers (a connect is a burst of callbacks; it costs one probe
  round), a debounce-suppressed change is remembered as a debt and paid by the next settled report,
  and a lost link is never debounced. `Failover.invalidate()` dethrones the winner while
  `lastKnownGood` (§11.5) stays as the pointer and clears the primary backoff, and
  `EndpointResolver.onNetworkChanged` re-picks API **and** watchkit under one lock, so the ask and
  the image/mermaid/sessions path move together. Settings ▸ Gateways now states the link truth out
  loud (`wifi(wlan0) · validated`, `bt(bt-pan) · not validated`, `wifi(tun0) · vpn · validated ·
  ts-resolver`) — the answer to "is my phone's tailnet reaching the watch?" without a bug report.
  Three measurement-forced rules: the boot opening swallows the framework's 44 ms refinement of the
  primed read (it was double-probing every cold start); **a `Network` object is not an identity**
  (ConnectivityManager hands out a new object per callback, so keying on its identity hash made the
  emulator re-route every 3 s forever — the interface name is the stable key, and it also names a
  tunnel: `tun0`); and the tailnet clause is built from a **dead probe**, not capability flags,
  because the Wear emulator resolves `.ts.net` through the host's resolver while its own capabilities
  say otherwise. Measured: 0 re-routes across 30 s idle (was one every 3 s), full lost/restored cycle
  with the winner re-picked before any tap, monitor + readout captured on the real SM-L705U; the
  watch's own radio cycle left for the owner's walk test (cycling watch Wi-Fi drops adb with it).
  15 new pure tests (**326 green**, was 311).

- **"Couldn't connect over tailnet" — routing made honest (owner bug, SPEC §11.5).** Reproduced on the
  SM-L705U by handing it a gateway whose only endpoint is the MagicDNS name: the watch cannot resolve
  `.ts.net` at all (`adb shell ping` → `ping: unknown host`), because MagicDNS exists only inside the
  tailnet and Wear OS runs no tailscaled — so *the phone's tailnet membership does nothing for the
  watch*, exactly the assumption the old error text invited. Two fixes: `Failover.lastKnownGood` +
  `fallbackPointer()` (when every probe is dead, `advanceFrom` no longer falls back to the PRIMARY —
  with a tailnet primary that is a hostname DNS can never answer, so an ordinary LAN blip got the
  mirror rewritten to a doomed host and the retry died on DNS: the sentence the owner read) — it now
  returns "" and the caller keeps its own base, exactly as its doc comment always promised; and
  `domain/RouteReport.kt`, a pure translator that turns a DNS miss / timeout / refusal into a
  wrist-length line naming the fix ("tailnet name won't resolve on the watch — Settings ▸ Gateways ▸
  add this Wi-Fi's LAN IP"), counting endpoints only from the failover machine's probe record and
  leaving non-routing errors' own words intact; the gateway + watchkit **Test** buttons and the
  learn-keys chip now tell the same truth. Not agent-based, verified live rather than argued:
  `comfyui` and `wear` produced the identical winner and the identical failure text, because failover
  binds per gateway and the agent only swaps the `/p/<profile>` suffix (now pinned by a test).
  `-e del_gw <id>` arrives with it (delete + re-land selection) — the cleanup half of `add_gw`.
  Proof: on-watch before/after shots of the same failure (raw OkHttp text → the actionable line),
  `endpoint failover found no route` in logcat, asks green on both agents on watch + emulator.
  13 new pure tests (**311 green**, was 298).

- **The launch film (owner 2026-10-29: *"it's only on the very first load of the app… if the user
  clicks anywhere on the screen it stops playing and loads the rest — we're loading the rest of the
  app in the background while the video plays"*)** — the 450 ms spirit flash is replaced on cold
  starts by `app/src/main/assets/hermes-watch-intro.mp4`: 14.3 s, **soundtrack kept**, re-encoded
  640×640 → **480×480 (exactly the panel)**, H.264 High + AAC-LC, crf 26 + `+faststart`,
  **3.11 MB → 859,675 B** (72% off; SSIM 0.977 / PSNR 40.6 dB against the master, worst-SSIM frame
  eyeballed too). Master is NOT in git (repo policy: generated video masters are untracked) — it
  lives at `../hermes-watch-intro.mp4`, one level above the worktrees.
  **Gate:** `domain/SplashIntro.kt` (pure) plays it on a **launcher cold start** and nothing else —
  warm starts, notification/tile deep links, and `am start` (which is every `scripts/*.sh` and
  `docs/DEMO.md` capture path, so screenshots stay clean) all fall back to the launch that shipped
  before.
- **LAUNCH SPLASH CHOOSER — static or video, and the owner's own media (owner 2026-10-29: *"I only
  want static or video, video by default. let the user choose video or splash screen in the
  settings. also let the user choose custom image/video for splash screen usage from image/video on
  their watch"*):** **Settings ▸ Display ▸ Launch splash** is a two-chip chooser (VIDEO default /
  STATIC) replacing the old Launch-video switch; a legacy OFF migrates to STATIC (`SplashIntro.choiceOf`,
  tested), ON/untouched to the film. Each mode may show the owner's OWN media: the media row runs the
  SAF picker (`OpenDocument`, mime-filtered by the visible mode) and CLASSIFIES the pick by resolver
  MIME (video → film slot, image → still slot) so switching modes never loses a pick. Storage is the
  content URI under a **persistable read grant** (`takePersistableUriPermission`, released when the
  slot is replaced/cleared); a stored pick is only honored while `AppSettings.splashGrantLive` can
  still OPEN it — a dead URI falls back to the built-in art silently rather than breaking the launch
  (`file://` is also accepted: the dev hook's shape, since `am broadcast` cannot hand out a
  persistable grant, and a sideload affordance). A static hold is `SplashIntro.STATIC_HOLD_MS` —
  deliberately UNDER the film's own worst measured first-frame wait, so switching to static always
  makes a launch feel faster. Custom stills decode off-thread downsampled to
  `SPLASH_DECODE_EDGE_PX`; an undecodable pick keeps the built-in portrait. Verified end-to-end on
  the emulator with a real gallery image and video: still renders Crop-full in static, custom film
  plays in video (`intro: film source = custom media`), built-ins intact. The chat composes *under* the film (the
  film decorates a warming app, it never gates composition), any touch cuts it and the touch is
  consumed so the transcript never sees that same finger, and the exit fade is the hand-off. The
  poster (`splash_spirit.webp`, already shipped) doubles as a curtain from the first frame so no
  launch leaks a frame of chat between the system splash and the film. **Night audit on the owner's
  watch (2026-10-29):** both modes proven by real launcher cold starts — `intro gate: mode=video`
  (blue film renders; a 5.49 s first frame was measured mid-test) and `intro gate: mode=static`
  (portrait holds `STATIC_HOLD_MS` then lifts into the chat). The owner's *"it showed the static
  instead of the video"* was the POSTER reading as a static splash during the slow decode — and on
  launches where the first frame passed the 8 s budget the film really WAS dropped — so
  `FIRST_FRAME_BUDGET_MS` is now 12 s: the wait is invisible (the poster is the same face the
  system splash shows) while a premature cut silently kills the launch story.
  **Three bugs only hardware taught** (each with the fix in `ui/IntroVideo.kt`): `res/raw` + media3
  throws `RawResourceDataSourceException: Resource not found` ~3 s in, when the mp4 reader reopens
  the URI at a byte offset ⇒ **asset URI, stored uncompressed**; media3 renders **no frame** until
  it holds audio focus ⇒ ask politely for `AUDIO_GRACE_MS`, then play silent rather than black;
  a `SurfaceView` hole-punches the poster out of the window ⇒ **TextureView**. And the budgets are
  measurements: first frame landed **1.9 s / 3.1 s / 4.1 s** after the overlay opened on an
  SM-L705U (whose system splash holds her face ~8 s before the app draws anything), so
  `FIRST_FRAME_BUDGET_MS` sits at 6 s — the first attempt's 1.5 s silently ate a film that was
  about to play. Proof: frame-by-frame cold-start captures on SM-L705U + Wear OS 5 emulator
  (curtain → film → chat-on-tap → chat-after-natural-end), plus one log line per path
  (`intro skipped: DeepLink|OwnerOff|NotLauncher`, `intro: first frame 3149ms in`).
  6 new pure tests (**298 green**, was 292).

- **Multi-gateway epic + IME no-Enter rule (SPEC §11)** — gateway registry (seed
  tailnet-first `hermes.tailnet.ts.net` → LAN fallback; legacy-key migration that keeps
  the old keys as the live mirror — an updated watch keeps working either way), endpoint
  failover state machine (2.5 s probes `/p/<agent>/v1/health` else `/health`, sticky session
  winner, 60 s primary-retry backoff, mid-session dethrone + advance-and-retry under the same
  `Idempotency-Key`), Settings ▸ Gateways (rows + winner dots + inline add/edit/delete; row tap
  = select reusing the switchAgent reload plumbing), agent dialog header names its gateway.
  **IME rule locked: no field depends on Enter** — every single-line field got singleLine +
  Done/Send wired to the same action as its now-always-visible button; the prompt editor also
  prefills the chat draft. Emulator proof: cold-start `endpoint winner: hermes.tailnet.ts.net:8642`;
  iptables-DROP the tailnet IP → `endpoint winner: 192.168.1.50:8642 · … (primary
  hermes.tailnet.ts.net:8642 down)` and base_url mirror flips with it; un-DROP → the
  60 s backoff retries the primary and tailnet reclaims the crown (no hammering proven);
  gateway add (`gateway saved id=vps-example-com`) / delete (`remaining=1`) / dot states
  screenshotted (`oz-gw-*.png`, `oz-ime-send.png`: typed URL → keyboard send ➤ → `run accepted`).
  27 new pure tests (139 green, was 109). **tsnet research (§11.4): `tailscale/tsnet-go` does
  not exist** (404 + module-proxy not-found); in-app tailnet stop-decision + the two viable
  lanes documented for the owner's pick.
- **Walkthrough fixes #2 — crash, cross-profile sessions, overlay readability, bottom anchor,
  pinch-zoom** — 85 unit tests (was 60). **Toast crash (root fix)**: `Graph`'s scope is
  `CoroutineScope(SupervisorJob())` with NO dispatcher ⇒ every `ChatSession` callback (switchAgent
  onDone …) fires on a Default worker thread ⇒ `Toast.show()` died with "Can't toast on a thread
  that has not called Looper.prepare()". New `Graph.runOnUi{}` main-Looper gate wraps every UI
  touch in a callback (audit: it was the only UI-touching callback; Log.i/goAsync ones are safe).
  **Cross-profile sessions**: new watchkit `GET /sessions?profile=<p>&limit<30` reads the profile's
  **state.db** directly with node:sqlite (the dashboard's own store — `_session_db_path_for_profile`:
  `default` = `~/.hermes/state.db`, else `profiles/<p>/state.db`; dashboard scrypt basic-auth has no
  plaintext to proxy with, and watchkit lives beside the files). Same wire shape as gateway
  `/api/sessions` (+`profile` per row) so `GatewaySessions.parse` serves both; archived/hidden/cron/
  tool/subagent rows and compression children filtered (sidebar semantics). App merges
  gateway(current) + watchkit(default) newest-first, **caps 30, caches zero transcripts** (owner:
  "don't cache too many"). UI: NEW SESSION pinned top (also in Chats header), filter chips, foreign
  rows labeled `💻 computer`, tap → switch agent + adopt (the crash path above). 401s from the
  `default` gateway under the wear token are expected + cache-served, never fatal.
  **PTT overlay redesign** (owner: "font way too big? … show the last few sentences"):
  bottom-of-safe-circle stack of ≤3 lines (`SttFeed`, pure+tested — sentence-split + word-wrap the
  cumulative partial, history on top, freshest LAST in title3, older dimmed caption2), small mic
  dot-ring. `-e ptt_demo` is the forced-listen shot hook. **Bottom anchor** (owner: "after stt …
  it scrolls to the top"): list state HOISTED above the overlay if/else + `ChatAnchor.bottomIndex`
  row-count (pure+tested) + scroll loop in ChatScreen. wear 1.4.1 gotchas measured on-device: the
  effect can fire BEFORE first layout (scroll silently lost → retry per frame); `scrollToItem(last,0)`
  lands short; end-of-list = last row at the Middle anchor with auto-centering padding below (wear
  "bottom" ≠ phone bottom-hugging — latest stays on screen, which was the ask).
  **Pinch-zoom media viewer**: tap image/mermaid card → `ZoomableMediaViewer` (Dialog, full-screen)
  — pinch+pan via raw `awaitEachGesture` (foundation 1.7.6 REMOVED the `TransformState`
  `transformable` overload — compile-time surprise), double-tap toggles 1×↔2.5×, tap/✕ chip closes,
  caption caption3 at bottom. Scope (owner "maybe everything"): images+mermaid now; text keeps
  crown scroll — SLC handles rotary natively on Wear; **follow-up: crown = font-scale** (needs
  RotaryService injection; `adb input` can't synthesize AXIS_ROTATION, verify with the owner's crown).
- **UI epic — owner's watch walkthrough** — five asks + two follow-ups, all on-device proven on
  the emulator (60 unit tests, was 26). **Agent switcher**: round button left of the orb,
  caption3 label = current profile (ellipsized to the circle); dialog lists profiles from the new
  watchkit `GET /agents` (`"default"` + every `profiles/<name>/` with a config.yaml, dot-dirs
  skipped), cached in DataStore, fallback `["wear","default"]`. Switch = swap the `/p/<segment>`
  of the base URL (pure `ProfileLogic`, everything before `/p/` stands); chats carry a `profile`,
  list + new session ids (`<profile>-watch-xxxx`) follow the agent. **Sessions fast-path**: right
  button → gateway `GET /api/sessions` (raw body cached ⇒ readable offline w/ stale hint), watch
  ids matched by `<profile>-watch…` get a ⌚ marker, tapping a row ADOPTS its session id (M4
  resume — desktop chat continues from the wrist). **Stop control** — cancel-route finding: the
  gateway DOES have `POST /v1/runs/{run_id}/stop` (`_http_routes` in
  `gateway/platforms/api_server_runs.py`, `run_stop` capability); app stops client-side first
  (detach SSE+poll via stopped-run guard, settle partial, `— stopped —` marker) then best-effort
  server cancel — proven: `resp {status:"stopping"}` → run `cancelled`. Orb morphs red **STOP**
  while streaming; the bare hold starts nothing any more (owner batch 6 — hold belongs to the
  dial, hold-to-talk retired). **PTT overlay**: while listening the
  transcript is REPLACED by a full-screen pulsing-mic view with the big live partial (STT text
  never starts above the fold anymore). **Round insets**: Wear Compose **1.4.1 has no `Scaffold`
  for insets and no `ROUND_SCAFFOLD_INSETS`** (checked the shipped AARs) — own `RoundScreen` math
  (clock top 30 / chin 16 / edge 20 dp + Now-in-Edge arc-lift for the side buttons) applied via
  `windowInsetsPadding` + SLC `contentPadding` on every screen. Gotchas: window insets are NOT
  delivered inside `Dialog` windows (spell paddings out), soft-wrap `Text` in a wrap-content
  centered `Column` can render as nothing (`fillMaxWidth`), Middle-anchored SLC needs the header
  ITEM itself to carry the top inset. **Tap = hands-free** (follow-up A, owner batch 6): tap starts
  Vosk capture that auto-finalizes — `Vad` watchdog (pure, tested): ≥1 word + 1.2 s of no-new-word
  partials → flush & send, 20 s hard cap, zero words = cancel. **A second tap splits by `Vad.speakingNow`**
  — words landed within 900 ms means the wearer is still talking, so the tap CANCELS the whole capture
  (the flushed Final is swallowed, never half-sent); past the window it is just an impatient send-now.
  `keyboard` chip flips to
  text input without leaving the listen feel. **Location tool** (follow-up B): Settings ▸ Body
  tools ▸ Location (default OFF); `LocationTool` = `LocationManager.getCurrentLocation(provider,…)`
  (API 30 overload — the Criteria+ProviderRequest one is 31), last-known first, 10 s cap, fail-soft
  status strings; snapshot gains `values.location {lat,lng,accuracy_m,ts}` when on (fresh fix on
  chat-ask pushes, last-known on interval); watchkit serves `watch_location` with a **30-min**
  freshness cap (a 2-h-old position is a wrong position) + MCP `tools/list` entry. Emulator proof:
  `perms=granted providers=passive,fused,gps` → fix `lat=37.4226 lng=-122.0847 acc=5m` (Android emulator mock default) →
  `/health/tool/watch_location` `ok:true`; 45-min-old fix → `stale:true`. Per-profile gateway keys
  reject the on-device wear token with a clean surfaced 401 (expected); E2E on `default` used that
  profile's key via `set_token` — ask answered, `session=default-watch-mvmbxfov`, sessions_dump
  shows it `watch=true`. Watch pending: physical reinstall + bar/round shots (`ui-watch-*`), GPS
  reality vs mock, hands-free VAD in real speech.

- **W1–W4+W8 body tools** (`e070675`) — `androidx.health:health-services-client` **1.0.0**
  (the `…services:services-client` artifact doesn't exist; 1.1.0 needs Kotlin 2.1 — pinned like
  wear-tiles). MeasureClient live HR + PassiveMonitoring steps; sleep/SpO2 probed by data-type
  name so a backing device that exposes them activates them **without recompile**. Snapshot push
  (15-min + on-ask refresh, drop-not-queue) → watchkit `POST /health/snapshot` (atomic
  `.state/snapshot.json`) → `GET /health/tool/<name>` (stale >2 h ⇒ `ok:false stale:true`) →
  **stdio MCP server** (`watchkit.mjs mcp`, zero-dep JSON-RPC) registered under
  `mcp_servers.hermes-watchkit` in the wear profile — hot-reconciled, no gateway restart.
  E2E proof: agent replied "67 bpm, measured about 2 minutes ago" from the synthetic snapshot.
  4 Settings toggles default **OFF** (owner-locked). Watch pending: `scripts/watch-health.sh grant`,
  real data-type probe (`-e body_status`), Doze/battery cost of the cadence.
- **Machine dashboard** (gateway-box side) — `hermes-dashboard.service` (systemd user unit) on
  `0.0.0.0:9119`; sidebar profile switcher → `wear` lists every watch session live (verified,
  9 sessions); desktop app attaches via same URL. Basic auth: provider verifies
  `password_hash` **first** (malformed hash ⇒ "Invalid credentials" even with correct plaintext
  `password`); hash = `scrypt$16384$8$1$<b64salt>$<b64dk>` (stdlib scrypt, 16-B salt, 32-B dk —
  precompute via plugin's `hash_password`); `HERMES_DASHBOARD_BASIC_AUTH_*` env vars **win over
  yaml** (`.env` username overrode `config.yaml` admin — root cause of the login mystery).
  LAN reach needs ufw allow 9119 from 192.168.1.0/24.
- **F6 inline images** (`eeaa8e4`) — watchkit `GET+POST /img {path,width≤1200}`:
  `img_roots` containment **checked before existence** (403 never leaks what exists) → realpath
  + symlink re-check → ext whitelist → 15 MB cap → Pillow Lanczos fit-within → WebP q82,
  cache key `sha256(realpath|mtime|width)`, GIF→first frame. App: `MdBlock.Image` (image-only
  lines), http(s) targets fetched direct (no bearer leak), paths via `/img?path=` with bearer;
  states loading-caption → rounded card → error card (alt + tap-revealed target). SOUL rule:
  emit `![desc](/abs/path.png)`, never raw paths. Proven: agent-written red PNG → card on screen.
- **TALK orb** (`7727e7c`) — floating bottom-center (fell below the fold at 480×480);
  hold = Vosk push-to-talk, tap = keyboard; physical buttons belong to other apps.
