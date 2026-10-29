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
watchkit: [http://hermes.tailnet.ts.net:8651,  http://192.168.1.50:8651]
```

— placeholder tailnet identity FIRST (MagicDNS publishes `.ts.net` names → CGNAT 100.64.0.0/10
addresses routable only inside your tailnet). A migration
from explicit LEGACY values keeps a CUSTOM host first (what worked today works first); stock
defaults migrate to the tailnet-first order. Settings edits fold into the registry: a known
endpoint keeps its position (agent switches re-set base_url constantly and must never reorder),
an unknown host prepends. Token = the gateway's `API_SERVER_KEY`; the watchkit bearer stays a
separate global setting.

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

### 11.4 In-app tailnet (tsnet) — STATUS: researched, NOT shipped (timebox stop)

Plan was `github.com/tailscale/tsnet-go` embedded via gomobile (tsnet node in-process + SOCKS5,
no system VPN). **VERIFIED 2026-10-24: that repository does not exist** (GitHub 404; Go module
proxy `not found` for both `tailscale/tsnet-go` and `tailscale/tsnet`). The real landscape:

| Option | Shape | Fit here |
|---|---|---|
| `tailscale/tailscale/tsnet` (in-repo) | server-style Go lib; no mobile story, no SOCKS5 | wrap-able: our own Go shim = tsnet node + local SOCKS5 server (`Dial` through the node resolves `.ts.net` via MagicDNS) → `gomobile bind` → .aar → `Proxy(socks5 127.0.0.1:port)` per OkHttp client. Feasible, but: Go+wireguard ≈ +15–30 MB APK across our pinned ABIs, NDK/cgo 32-bit toolchain, authkey/usercode flow, battery/Doze story all ours to build (est. ≥ a full day, not the 1.5 h toolchain budget) |
| `tailscale/libtailscale` + `tailscale-android` | the OFFICIAL userspace story: Go .so (gomobile `libgojni.so` + NDK) driven by Kotlin `Tailscale.kt`, traffic via **VpnService** (per-app "non-system" VPN, one user consent dialog) — what the Tailscale app itself ships | closest to "shipped": Kotlin bridge exists to copy, needs our auth UX (browser → `tailscale.com/key`) + Wear validation + `RECEIVE_BOOT`/Doze work; SDK is built-from-source (no clean maven AAR) |
| `leondgarse/tailscale_mobile` | community fork exposing tsnet as c-shared + C API | JNI/JNA glue + same size/ABI/auth caveats; no SOCKS5 |
| exit-node fallback (ships TODAY, §2 badge 🌐) | BT-tethered phone holds the tailnet; watch default-routes through it | zero APK size, works; costs the phone radio; the endpoint system already handles it — LAN/phone-absent failover picks the tailnet URL when reachable |

**Stop decision (per this ticket's own timebox):** ship the gateway system; the tailnet DEFAULT
already behaves correctly at home via failover, and away-from-home works via the tethered-phone
path. To make the watch a first-class tailnet node, the owner picks the lane: (a) gomobile tsnet
shim (SOCKS5, +size, battery note: goroutine lives in Graph scope, state on
`filesDir/tailnet` re-auths silently — WorkManager-gated cadence is the follow-up), or (b) ride
libtailscale/VpnService (official, consent dialog, heavier on Wear). Either needs a tsnet
**authkey** (or once-per-node approval on his phone: AuthURL → tailscale.com/key). Dev hook
today: none — this section IS the Phase B artifact.


### 11.5 What "tailnet endpoint" can and cannot mean on the watch (owner bug, 2026-10-29)

Owner report: *"it said it couldn't connect over tailnet to gateway when I was on wifi. I even had
the phone on tailnet so I thought it should have worked. It was in non-`wear` agent profile… want
to make sure lan/tailnet routing is automatic in all agents."* Reproduced on the SM-L705U by
selecting a gateway whose only API endpoint is the MagicDNS name:

```
submit failed (Unable to resolve host "tuxdesktop…ts.net": No address associated with hostname)
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
  "…tailb12aa5.ts.net…" for what was just a LAN blip. Now `Failover` remembers `lastKnownGood` (the last
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
carry the **tailnet IP** (`http://100.69.144.33:8642`), not only the MagicDNS name: forwarding to
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
main screen?"* One control, eight directions, plus the two bugs that surfaced while using it
(agent switch 401s, sessions never preloading) and session search. Verified on the owner's
hardware this batch: **Galaxy Watch Ultra (SM-L705U), API 36 / Android 16, 480×480 @ 340dpi** —
byte-identical to `BottomLayout.REF`, so dial geometry is unit-testable against real numbers.

### 12.1 The dial (D1–D8) — `domain/OrbDial.kt` + `ui/OrbDialOverlay.kt`

Eight 45° sectors, default map (every slot remappable in Settings ▸ Orb):

| Clock | Lane | Action id | Why |
|---|---|---|---|
| 12:00 | keyboard | `keyboard` | the drag-up that already ships (`OrbGesture.DRAG_UP` becomes this sector — semantics unchanged) |
| 1:30 | mute / unmute | `mute` | the mute satellite's job; same way again = the inverse |
| 3:00 | settings | `settings` | one drag to Settings (owner: "tough to get to settings"); replaced the unshipped `record_hi` stub, which remains a remap lane (F19) |
| 4:30 | model picker | `model` | 🧠 per-session model selector — replaced `voice_notes` (F20 stub nobody fired), SPEC §12.6 |
| 6:00 | slash commands | `commands` | the overlay (F15) |
| 7:30 | chats | `chats` | local chats for the active agent (the header chip's job) |
| 9:00 | sessions | `sessions` | gateway/computer sessions |
| 10:30 | agents | `agents` | agent switcher |

**Geometry decision (owner, locked):** the orb sits 15 ref-px off the bottom edge, so down / 4:30 /
7:30 have no room for a ring. The dial therefore uses **contextual rise**: dragging up, left, right
or either upper diagonal never moves the orb; when the drag vector enters the LOWER arc the orb
animates toward face center (~110 ms) so the crowded slots are reachable, and it settles back when
the drag leaves. Ring icons are drawn **past the fingertip** (`ICON_RING_REF` > activation radius)
— you are dragging *to* an icon you can see, not to an invisible angle.

**Speed discipline:** the ring fades in ≤ 80 ms, sector changes cost one haptic tick, release
commits. Once the direction is memorized the whole gesture is a blind flick — the icons are for
learning, not for aiming. Sectors activate past `ACTIVATE_RADIUS_REF` (never a jitter) and the
lane only arms on a drag that crosses before the long-press timeout, so **tap = record is untouched**
(owner batch 6 made the bare hold inert — hold-to-talk retired so the hold belongs to the dial;
`OrbGesture` keeps the race, `OrbDial` classifies the direction).

**Re-aim (owner batch 5: "if I go up I should still be able to move over to one of the others"):**
arming the dial BEATS the hold timer outright. Once the gate is crossed the gesture belongs to the
dial until the finger lifts — time passing never freezes the vector — and the lane is decided by
the finger's angle **at release**, so up-then-slide-left-then-release picks 10:30. (The old bug:
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

### 13.5 Asks for the Hermes gateway (not this repo)

1. `GET /v1/commands` — expose `hermes_cli.commands.COMMAND_REGISTRY` (already gateway-importable,
   prompt_toolkit-free) so the watch catalog self-updates.
2. `POST /api/sessions/{id}/truncate {before:<message_id>}` — `SessionDB.replace_messages` already
   exists (fork uses it); ~20 lines. Unlocks exact desktop edit-and-resend and retires the
   fork-per-edit workaround in F16.
3. Consider one `API_SERVER_KEY` per profile in the install docs — the fail-closed 401 with an
   open `/v1/health` is a trap for every client, not just the watch (§12.2).


---


## Appendix A — Landed slices (build log, newest first)

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
  before, as does **Settings ▸ Display ▸ Launch video**. The chat composes *under* the film (the
  film decorates a warming app, it never gates composition), any touch cuts it and the touch is
  consumed so the transcript never sees that same finger, and the exit fade is the hand-off. The
  poster (`splash_spirit.webp`, already shipped) doubles as a curtain from the first frame so no
  launch leaks a frame of chat between the system splash and the film.
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
