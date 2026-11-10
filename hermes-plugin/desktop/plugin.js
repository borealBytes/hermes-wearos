1|// Hermes Wear Kit — Desktop pane (protocol v2). SDK-surface only (rule 8): imports are
2|// @hermes/plugin-sdk + react/jsx-runtime, no eval/new Function, no dynamic import(), no
3|// prototype writes, no core-UI reach-ins. `hermes plugins validate` enforces this.
4|//
5|// Three surface facts shaped it, all measured against the installed core (v0.23.0):
6|//   · No sibling assets: the loader evaluates plugin.js from a blob: URL, so the hero is
7|//     an inline SVG wordmark (zero assets, works everywhere).
8|//   · There is NO tool-call door for panes (src/sdk/index.ts `host` has none): the real
9|//     door is per-plugin REST — ctx.rest('/status') → dashboard/plugin_api.py, hot-mounted.
10|//   · The pane is a LOGIN surface (dashboard auth middleware), which is what makes its
11|//     Approve/Remove/Install buttons legitimate consent points (rules 12/13).
12|//
13|// v2 flow shown here: Pair button opens the window → the WATCH shows a gateway-issued
14|// code → the human types it here (or in a terminal). Enable checkboxes delegate to
15|// `hermes -p <profile> plugins enable` — no hand-rolled config editor anymore.
16|import { atom, host, haptic, useValue } from '@hermes/plugin-sdk'
17|import { jsx, jsxs } from 'react/jsx-runtime'
18|import { useEffect } from 'react'
19|
20|const FALLBACK_RELEASES = 'https://github.com/borealBytes/hermes-wearos/releases/latest'
21|
22|// ON-WATCH VOICE-OUT IS PARKED (owner ruling 2026-11-10, issue #7): the watch-side
23|// TTS *surface* is pulled from the pane — the 'watch' quant dropdown, the wrist copy,
24|// and the recorder note — because there is no hardware test time before the deadline
25|// and the milestone focus is the GATEWAY lane (Settings ▸ Voice ▸ 'wearkit-gateway').
26|// The feature itself stays merged and tested: Kotlin player + GatewayTts lane,
27|// voiceout tiers, pane REST actions. Flip to true to restore every control AS-IS.
28|// SPEC §19.4(l).
29|const WATCH_TTS_UI = false
30|const REPO_URL = 'https://github.com/borealBytes/hermes-wearos'
31|
32|const $status = atom(null)
33|const $stt = atom(null)
34|const $voice = atom(null)
35|const $voToken = atom('')
36|const $voText = atom('Your cron digest is ready: three farm alerts, no incidents, sprayer window opens Thursday at six ten.')
37|const $voNote = atom('')
38|const $speedDraft = atom(null)   // mid-drag thumb position; wins over pollers until the commit lands
39|const $sttAsk = atom(false)   // sudo-style approval card open (commands shown first)
40|const $sttTyped = atom('')    // the sudo gate: Run stays disabled until this reads "install"
41|const $sttErr = atom('')      // start-time refusal shown ON the card, not swallowed
42|const $busy = atom(false)
43|const $error = atom('')
44|const $code = atom('')
45|const $pairNote = atom('')
46|
47|let api = null
48|const rest = (path, opts) => {
49|  if (!api) return Promise.reject(new Error('wearos: register() has not run'))
50|  return api.rest(path, opts)
51|}
52|
53|async function refresh () {
54|  try {
55|    $status.set(await rest('/status'))
56|    $error.set('')
57|  } catch (error) {
58|    // Keep the last view, but SAY what's wrong. 404 ⇒ the backend half isn't mounted
59|    // yet under this window: Rescan fixes it (hot-mount), no restart.
60|    $error.set(String((error && error.message) || error))
61|  }
62|  try {
63|    $stt.set(await rest('/stt'))
64|  } catch { /* voice card hides itself without data */ }
65|}
66|
67|async function refreshVoice () {
68|  try { $voice.set(await rest('/voice')) } catch { /* card hides itself */ }
69|}
70|
71|async function voiceAct (fn) {
72|  haptic('tap')
73|  $busy.set(true)
74|  try { await fn(); await refreshVoice() } finally { $busy.set(false) }
75|}
76|
77|// dropdown choice = POST /voice {action:select}; persists server-side, no reload needed
78|function voPick (slot) {
79|  return e => voiceAct(() => rest('/voice', {
80|    method: 'POST', body: { action: 'select', source: e.target.value, for: slot }
81|  }))
82|}
83|
84|// 'Speak on' = CPU or one named GPU (SPEC 19.4m). The pick IS the consent to
85|// build the CUDA engine variant once; meanwhile the CPU engine keeps talking
86|// and the note says so — never a placebo switch.
87|function voSetDevice (ev) {
88|  const dev = ev.target.value
89|  $voNote.set(dev.startsWith('gpu:')
90|    ? 'engine still on CPU while the one-time CUDA build runs (see the row below)'
91|    : 'engine moving back to CPU on the next speak')
92|  voiceAct(() => rest('/voice', {
93|    method: 'POST', body: { action: 'set_engine_device', device: dev }
94|  }))
95|}
96|
97|async function sttSelect (ev) {
98|  await act(() => rest('/stt', {
99|    method: 'POST', body: { action: 'select', variant: ev.target.value }
100|  }))
101|}
102|
103|// Install = the consent dialog first (rule 12): steps, hosts, disk, per chosen row.
104|function voInstall () {
105|  const air = ($voice.get() || {}).air || {}
106|  const p = air.preview
107|  if (!p) return
108|  const yes = window.confirm(`${p.title}\n` +
109|    p.steps.map(s => `• ${s}`).join('\n') +
110|    `\n• needs: ${p.needs.join(', ')} · disk ~${p.disk_mb} MB` +
111|    `\n• hosts: ${p.hosts.join(', ')}`)
112|  if (!yes) return
113|  $voNote.set('')
114|  voiceAct(() => rest('/voice', { method: 'POST', body: {
115|    action: 'install', token: String($voToken.get() || '').trim() || undefined
116|  } }))
117|}
118|
119|// Sound test: the lane returns a real wav, the pane plays it. No engine → the
120|// refusal text, never a silent button (issue #7 honesty rule).
121|async function voSoundTest () {
122|  // The note answers the click FIRST: a cold engine call reloads model weights and
123|  // can take minutes (owner report 2026-11-10: "I click play, nothing plays" — the
124|  // wait was invisible). Cached/hot calls overwrite it within a second. While the
125|  // POST is in flight we POLL the engine state every few seconds, so the spinner
126|  // always names WHICH wait it is: model loading, queued behind a render, speaking.
127|  $voNote.set('starting…')
128|  const poll = setInterval(async () => {
129|    try {
130|      const v = await rest('/voice')
131|      const e = ((v || {}).air || {}).engine || {}
132|      $voNote.set(e.phase === 'loading'
133|        ? 'loading voice model… (first call after boot — ≈2 minutes, once)'
134|        : (e.queued || 0) > 1
135|          ? `synthesizing… (queued: ${e.queued} in line, first in first out)`
136|          : 'synthesizing…')
137|    } catch { /* keep the last note */ }
138|  }, 4000)
139|  await voiceAct(async () => {
140|    try {
141|      const out = await rest('/voice', {
142|        method: 'POST', body: { action: 'soundtest', text: $voText.get() || '' } })
143|      if (out && out.audio_wav_b64) {
144|        const bin = window.atob(out.audio_wav_b64)
145|        const u8 = new Uint8Array(bin.length)
146|        for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i)
147|        const url = URL.createObjectURL(new window.Blob([u8], { type: 'audio/wav' }))
148|        // PACING AT PLAYBACK: the clip is the natural 1x take; the dial rides here.
149|        const a = new window.Audio(url)
150|        a.playbackRate = Number(out.speed) || 1
151|        a.preservesPitch = true
152|        await a.play()
153|        $voNote.set(`done ✓ played · engine ${out.engine || 'air'}${out.cached ? ' · cached' : ''}`)
154|      } else {
155|        $voNote.set((out && (out.error || out.refused)) || 'no audio')
156|      }
157|    } catch (e) {
158|      $voNote.set('synth request failed: ' + String((e && e.message) || e))
159|      throw e
160|    }
161|  }).finally(() => clearInterval(poll))
162|}
163|
164|// PACING AT PLAYBACK + drag law (owner 2026-11-10, twice fixed): (1) the dial moved
165|// one notch at a time because every change POST went through voiceAct — busy=true
166|// disabled the slider mid-gesture; (2) it STILL snapped because the 2s/8s refreshVoice
167|// pollers re-seed $voice with the SERVER speed mid-drag (commit is debounced). Law:
168|// the thumb position lives in $speedDraft from the first move until the commit's
169|// refresh lands — pollers can overwrite everything else, the draft wins the slider.
170|let _speedT = null
171|const voSpeed = e => {
172|  const v = Number(e.target.value)
173|  $speedDraft.set(v)
174|  const cur = $voice.get()
175|  if (cur && cur.voice) {
176|    cur.voice = Object.assign({}, cur.voice, { speed: v })
177|    $voice.set(Object.assign({}, cur))
178|  }
179|  clearTimeout(_speedT)
180|  _speedT = setTimeout(() => {
181|    rest('/voice', { method: 'POST', body: { action: 'speed', speed: v } })
182|      .then(refreshVoice)
183|      .catch(() => { $voNote.set('speed commit failed — check the gateway') })
184|      .finally(() => { $speedDraft.set(null) })
185|  }, 250)
186|}
187|const voKaraoke = e => voiceAct(() => rest('/voice', {
188|  method: 'POST', body: { action: 'karaoke', karaoke: e.target.checked } }))
189|const voDelete = cid => voiceAct(() => rest('/voice', {
190|  method: 'POST', body: { action: 'card_delete', card: cid } }))
191|
192|async function act (fn) {
193|  haptic('tap')
194|  $busy.set(true)
195|  try { await fn(); await refresh() } finally { $busy.set(false) }
196|}
197|
198|// One checkbox = one POST = one fixed-argv `hermes -p <profile> plugins enable|disable`
199|async function toggle (profile, on) {
200|  await act(async () => {
201|    const out = await rest('/enable', { method: 'POST', body: { profile, enable: on } })
202|    if (out && out.ok === false) {
203|      host.notify({ kind: 'error', message: `wearos: ${out.detail || out.error}` })
204|    }
205|  })
206|}
207|
208|async function openPairWindow () {
209|  $pairNote.set('opening…')
210|  await act(async () => {
211|    const out = await rest('/pair/window', { method: 'POST', body: { action: 'open' } })
212|    $pairNote.set(out && out.opened
213|      ? 'window open ~5 min — tap Pair on the watch now'
214|      : 'window open')
215|  })
216|}
217|
218|async function claimCode () {
219|  const code = String($code.get() || '').trim()
220|  if (!/^[1-9][0-9]{5}$/.test(code)) { $pairNote.set('six digits, from the watch'); return }
221|  await act(async () => {
222|    const out = await rest('/pair/claim', { method: 'POST', body: { code } })
223|    if (out && out.error) {
224|      $pairNote.set(out.lock && out.lock.locked
225|        ? 'too many wrong codes — locked for a few minutes'
226|        : `claim failed: ${out.error}`)
227|    } else {
228|      $pairNote.set(`approved: ${out.approved.label} → agent ${out.approved.profile}`)
229|      $code.set('')
230|    }
231|  })
232|}
233|
234|async function revoke (name, label, profile) {
235|  const restart = profile === 'default'
236|    ? '\n⚠ THE GATEWAY WILL RESTART — active chats on every agent are interrupted.' : ''
237|  const yes = window.confirm(
238|    `Remove "${label}" from agent ${profile}?\n` +
239|    '• deletes it from your tailnet\n' +
240|    '• invalidates its device token (queues purged)\n' +
241|    '• rotates the agent API key — other paired watches pick it up automatically;' +
242|    ' scripts that use the old value must update.' + restart)
243|  if (!yes) return
244|  await act(async () => {
245|    const out = await rest('/devices/revoke', { method: 'POST', body: { name } })
246|    const bad = (out.steps || []).filter(s => !s.ok)
247|    host.notify({
248|      kind: bad.length ? 'warn' : 'info',
249|      message: bad.length ? `removed with notes: ${bad.map(b => `${b.step}: ${b.detail}`).join(' · ')}`
250|                          : `removed ${name} (token, tailnet node, key rotation)`
251|    })
252|  })
253|}
254|
255|async function setGrant (profile, allow) {
256|  await act(() => rest('/grants', { method: 'POST', body: { profile, allow } }))
257|}
258|
259|async function setAlerts (profile, pref) {
260|  await act(() => rest('/alerts', { method: 'POST', body: { profile, pref } }))
261|}
262|
263|// STT install consent (sudo law, D16 rework): the button opens an APPROVAL CARD that
264|// shows the exact commands before anything runs. Two exits for the human: run it here
265|// (POST /stt — the POST is the permission, nothing fires on view) or copy the one-liner
266|// and drive it from a terminal yourself.
267|async function sttRun () {
268|  if (($sttTyped.get() || '').trim().toLowerCase() !== 'install') return
269|  $sttErr.set('')
270|  await act(async () => {
271|    const out = await rest('/stt', { method: 'POST', body: { action: 'install' } })
272|    if (out && out.started) { $sttAsk.set(false); $sttTyped.set('') }   // progress line takes over
273|    else $sttErr.set((out && out.error) || 'not started')
274|  })
275|}
276|
277|function sttCopy () {
278|  const p = ($stt.get() || {}).preview
279|  if (!p) return
280|  navigator.clipboard.writeText(p.cli).then(
281|    () => $sttErr.set('copied — paste into any terminal; nothing runs until you press Enter'),
282|    () => $sttErr.set(`clipboard blocked — run manually: ${p.cli}`))
283|}
284|
285|// The approval card: exact commands FIRST (from preview.commands — the same argv
286|// lists stt_install._run executes), disclosure line, then Run-here / Copy / Cancel.
287|function SttApproval ({ p, busy, typed }) {
288|  const armed = (typed || '').trim().toLowerCase() === 'install'
289|  return jsxs('div', {
290|    className: 'flex flex-col gap-1 rounded border border-(--ui-border) p-2',
291|    children: [
292|      jsx('div', { className: 'font-medium', children: 'This will run, in order — nothing starts until you choose:' }),
293|      jsx('pre', {
294|        className: 'overflow-x-auto rounded bg-(--ui-bg-secondary) p-1.5 text-[10px] leading-relaxed text-(--ui-text-secondary)',
295|        children: (p.commands || p.steps.map(s => `· ${s}`)).join('\n')
296|      }),
297|      jsx('div', {
298|        className: 'text-[10px] text-(--ui-text-tertiary)',
299|        children: `hosts: ${p.hosts.join(', ')} · needs: ${p.needs.join(', ')} · disk ~${p.disk_mb} MB` +
300|          ' · no sudo, no system Python — everything lands in the plugin data dir'
301|      }),
302|      // The sudo gate: typing the word is the password moment — intent proven AFTER
303|      // reading the commands, not a checkbox read at first glance.
304|      jsx('input', {
305|        className: 'rounded border border-(--ui-border) bg-transparent px-1.5 py-1 text-xs',
306|        type: 'text', placeholder: 'type “install” to arm Run', value: typed || '',
307|        onInput: (e) => $sttTyped.set(e.target.value)
308|      }),
309|      jsxs('div', {
310|        className: 'flex items-center gap-2',
311|        children: [
312|          jsx('button', {
313|            className: 'rounded border border-(--ui-border) px-2 py-1 text-xs disabled:opacity-50',
314|            type: 'button', disabled: busy || !armed, onClick: sttRun,
315|            children: armed ? '▶ Run it here' : 'Run it here (locked)'
316|          }),
317|          p.cli ? jsx('button', {
318|            className: 'rounded border border-(--ui-border) px-2 py-1 text-[10px]',
319|            type: 'button', onClick: sttCopy,
320|            children: 'Copy command'
321|          }) : null,
322|          jsx('button', {
323|            className: 'px-1 py-1 text-[10px] text-(--ui-text-tertiary)',
324|            type: 'button', onClick: () => $sttAsk.set(false),
325|            children: 'Cancel'
326|          })
327|        ]
328|      })
329|    ]
330|  }, 'stt-approval')
331|}
332|
333|function Step ({ done, n, title, children }) {
334|  return jsxs('div', {
335|    className: 'flex flex-col gap-1 rounded border border-(--ui-border) p-2',
336|    children: [
337|      jsxs('div', {
338|        className: 'flex items-center gap-2',
339|        children: [
340|          jsx('span', {
341|            className: done ? 'text-(--ui-text-secondary)' : 'text-(--ui-text-tertiary)',
342|            children: done ? '✓' : `${n}.`
343|          }),
344|          jsx('span', { className: 'font-medium', children: title })
345|        ]
346|      }),
347|      jsx('div', { className: 'text-xs text-(--ui-text-tertiary)', children })
348|    ]
349|  }, `step-${n}`)
350|}
351|
352|function WearKitPane () {
353|  const status = useValue($status)
354|  const stt = useValue($stt)
355|  const voice = useValue($voice)
356|  const speedDraft = useValue($speedDraft)
357|  const shownSpeed = speedDraft == null ? (voice && voice.voice && voice.voice.speed) : speedDraft
358|  const voToken = useValue($voToken)
359|  const voText = useValue($voText)
360|  const voNote = useValue($voNote)
361|  const ask = useValue($sttAsk)
362|  const sttTyped = useValue($sttTyped)
363|  const sttNote = useValue($sttErr)
364|  const busy = useValue($busy)
365|  const error = useValue($error)
366|  const code = useValue($code)
367|  const note = useValue($pairNote)
368|  const air = (voice && voice.air) || null
369|  const voicing = (voice && voice.voice) || null
370|  const installing = air && air.phase &&
371|    !['idle', 'done', 'failed', 'cancelled'].includes(air.phase)
372|  useEffect(() => {
373|    refresh()
374|    const timer = setInterval(() => { refresh(); refreshVoice() }, 8_000) // pairing completes while you watch this pane
375|    return () => clearInterval(timer)
376|  }, [])
377|  useEffect(() => {
378|    if (!installing) return undefined
379|    const fast = setInterval(refreshVoice, 2_000) // install progress deserves a live bar
380|    return () => clearInterval(fast)
381|  }, [installing])
382|
383|  const checks = (status && status.checks) || {}
384|  const devices = Object.entries((status && status.devices) || {})
385|  const profiles = (status && status.profiles) || []
386|  const grants = (status && status.grants) || {}
387|  const pairing = (status && status.pairing) || {}
388|  const windowOpen = !!pairing.window
389|  const pending = pairing.pending || []
390|  const lock = pairing.claim_lock || {}
391|  const releases = (status && status.apk_release_url) || FALLBACK_RELEASES
392|  const enabledCount = profiles.filter(p => p.enabled).length
393|
394|  const lights = Object.entries(checks).map(([name, check]) =>
395|    jsx('div', {
396|      className: 'flex items-center justify-between gap-2 text-sm',
397|      children: jsxs('span', {
398|        className: check.ok ? 'text-(--ui-text-secondary)' : 'text-(--ui-text-danger)',
399|        children: [check.ok ? '● ' : '○ ', name, check.ok ? '' : ` — ${check.detail}`]
400|      })
401|    }, name))
402|
403|  return jsxs('div', {
404|    className: 'flex h-full flex-col gap-2 overflow-y-auto p-3 text-sm',
405|    children: [
406|      jsx('svg', {
407|        viewBox: '0 0 240 24', height: 22, role: 'img', 'aria-label': 'Hermes Wear Kit',
408|        className: 'text-(--ui-text-secondary)',
409|        children: jsx('text', {
410|          x: 1, y: 17, fill: 'currentColor',
411|          style: { fontSize: '13px', fontWeight: 600, letterSpacing: '0.26em' },
412|          children: 'HERMES WEAR KIT'
413|        })
414|      }),
415|      jsxs('div', {
416|        className: 'flex flex-col gap-1 rounded border border-(--ui-border) p-2 text-xs',
417|        children: [
418|          jsx('div', {
419|            className: 'font-medium text-(--ui-text-secondary)',
420|            children: '⌚ One watch. Your tailnet. No middleman.'
421|          }),
422|          jsxs('div', {
423|            className: 'text-(--ui-text-tertiary)',
424|            children: [
425|              'The watch app is ',
426|              jsx('a', { className: 'underline', href: REPO_URL, rel: 'noreferrer', target: '_blank', children: 'borealBytes/hermes-wearos' }),
427|              ' — this plugin is its pairing + provisioning half.'
428|            ]
429|          }),
430|          jsx('div', { className: 'text-(--ui-text-tertiary)', children: '• the gateway issues the code, the watch shows it, a human types it back — never through a model' }),
431|          jsx('div', { className: 'text-(--ui-text-tertiary)', children: '• every call rides the watch\u2019s own node — no relay, no phone app; the API key is handed over the tailnet only' }),
432|          jsx('div', { className: 'text-(--ui-text-tertiary)', children: '• full revoke: node, token and a rotated agent key in one confirm' }),
433|          jsx('div', {
434|            className: 'font-medium text-(--ui-text-secondary)',
435|            children: 'Open source forever: the Release APK is a courtesy — making it yours is the point.'
436|          })
437|        ]
438|      }),
439|      error
440|        ? jsx('div', {
441|            className: 'text-xs text-(--ui-text-danger)',
442|            children: `backend not answering: ${error} — hit Rescan on the Wear Kit card (no restart needed: the route hot-mounts).`
443|          })
444|        : null,
445|
446|      // ── pairing card ──────────────────────────────────────────────────────────
447|      jsxs('div', {
448|        className: 'flex flex-col gap-1 rounded border border-(--ui-border) p-2',
449|        children: [
450|          jsxs('div', {
451|            className: 'flex items-center justify-between',
452|            children: [
453|              jsx('span', { className: 'font-medium', children: 'Pair a watch' }),
454|              jsx('button', {
455|                className: 'rounded border border-(--ui-border) px-2 py-1 text-xs disabled:opacity-50',
456|                type: 'button', disabled: busy || windowOpen,
457|                onClick: openPairWindow,
458|                children: windowOpen ? `open (${pairing.window.seconds_left}s)` : 'open window'
459|              })
460|            ]
461|          }),
462|          pending.map(p => jsx('div', {
463|            className: 'text-xs text-(--ui-text-secondary)',
464|            children: `watch shows ${p.code} — ${p.label} [${p.state}]`
465|          }, p.code)),
466|          jsxs('div', {
467|            className: 'flex items-center gap-1',
468|            children: [
469|              jsx('input', {
470|                className: 'w-24 rounded border border-(--ui-border) bg-transparent px-1 py-1 text-xs',
471|                placeholder: '6-digit code', value: code, disabled: busy,
472|                onChange: e => $code.set(e.target.value)
473|              }),
474|              jsx('button', {
475|                className: 'rounded border border-(--ui-border) px-2 py-1 text-xs disabled:opacity-50',
476|                type: 'button', disabled: busy || lock.locked,
477|                onClick: claimCode,
478|                children: lock.locked ? 'locked…' : 'Approve'
479|              })
480|            ]
481|          }),
482|          note ? jsx('div', { className: 'text-[10px] text-(--ui-text-tertiary)', children: note }) : null,
483|          jsx('div', {
484|            className: 'text-[10px] text-(--ui-text-tertiary)',
485|            children: 'codes typed HERE or in a terminal (hermes watch pair <CODE>) — messaging platforms route you to your computer.'
486|          })
487|        ]
488|      }, 'pair-card'),
489|
490|      // ── enable checklist (ONE agent list — owner ruling 2026-11-10: enabling
491|      //    Wear Kit and granting the watch to use an agent are the same decision;
492|      //    one row per agent, both boxes, no duplicated list below) ─────────────
493|      jsx(Step, {
494|        done: enabledCount > 0, n: 1, title: 'Enable Wear Kit for your agents',
495|        children: jsxs('div', {
496|          className: 'flex flex-col gap-1',
497|          children: [
498|            jsx('div', {
499|              className: 'text-(--ui-text-tertiary)',
500|              children: profiles.length
501|                ? 'each box runs `hermes -p <agent> plugins enable wearos` — the same verb you would type; the second box lets paired watches use that agent (its API key rides the private lane, read in its own scope)'
502|                : error ? 'waiting on the backend — see the line above.' : 'no profiles on this gateway yet.'
503|            }),
504|            ...profiles.map(p => jsxs('div', {
505|              className: 'flex items-center gap-3',
506|              children: [
507|                jsxs('label', {
508|                  className: 'flex flex-1 cursor-pointer items-center gap-2',
509|                  children: [
510|                    jsx('input', {
511|                      type: 'checkbox', checked: !!p.enabled, disabled: busy,
512|                      onChange: e => toggle(p.name, e.target.checked)
513|                    }),
514|                    jsx('span', { children: p.name })
515|                  ]
516|                }, p.name),
517|                p.name === 'default'
518|                  ? null
519|                  : jsx('label', {
520|                      className: 'flex cursor-pointer items-center gap-1 text-[10px] text-(--ui-text-secondary)',
521|                      children: jsxs('span', {
522|                        className: 'flex items-center gap-1',
523|                        children: [
524|                          jsx('input', {
525|                            type: 'checkbox', checked: !!grants[p.name], disabled: busy,
526|                            onChange: e => setGrant(p.name, e.target.checked)
527|                          }),
528|                          'watch may use'
529|                        ]
530|                      })
531|                    }, `grant-${p.name}`)
532|              ]
533|            }, `row-${p.name}`))
534|          ]
535|        })
536|      }, 'enable-card'),
537|
538|      // ── devices ───────────────────────────────────────────────────────────────
539|      devices.length
540|        ? jsxs('div', {
541|            className: 'flex flex-col gap-1 rounded border border-(--ui-border) p-2',
542|            children: [
543|              jsx('div', { className: 'font-medium', children: 'Paired watches' }),
544|              ...devices.map(([name, dev]) => jsxs('div', {
545|                className: 'flex items-center justify-between gap-1 text-xs',
546|                children: [
547|                  jsxs('span', {
548|                    className: 'text-(--ui-text-secondary)',
549|                    children: [dev.label || name, ' · ', dev.profile,
550|                               dev.node_id ? ' · node ✓' : ' · never connected']
551|                  }),
552|                  jsx('button', {
553|                    className: 'rounded border border-(--ui-border) px-1 py-0.5 text-[10px] disabled:opacity-50',
554|                    type: 'button', disabled: busy,
555|                    onClick: () => revoke(name, dev.label || name, dev.profile),
556|                    children: 'Remove'
557|                  })
558|                ]
559|              }, name))
560|            ]
561|          }, 'devices-card')
562|        : jsx(Step, {
563|            n: 2, title: 'Install the APK, then pair',
564|            children: jsxs('span', {
565|              children: [
566|                'Get the Wear OS app from ',
567|                jsx('a', { className: 'underline', href: releases, rel: 'noreferrer', target: '_blank', children: 'GitHub Releases' }),
568|                ' (CI-built APK + SHA256SUMS), open it, tap Pair.'
569|              ]
570|            })
571|          }, 'devices-empty'),
572|
573|      // ── agent key grants (D17) — merged INTO the enable list above (owner
574|      //    ruling 2026-11-10): one row per agent, enable + watch-may-use together.
575|
      // ── voice-in (D16) — ONE card mirroring Voice-out's anatomy (owner
      //    ruling 2026-11-10): 🎤 header, model-loaded/ready chip on the right,
      //    quant selector, honest states. The chip promises only what
      //    stt_install._probe() proves — installed = lib+weights on disk,
      //    ready = SHA-256 verified; a mismatch never shows a silent gap
      //    (the old branch rendered NOTHING when installed-but-unverified).
      stt ? jsxs('div', {
        className: 'flex flex-col gap-1 rounded border border-(--ui-border) p-2',
        children: [
          jsxs('div', {
            className: 'flex items-center justify-between',
            children: [
              jsx('span', { className: 'font-medium', children: '🎤 Voice-in' }),
              (stt.phase && stt.phase !== 'idle' && stt.phase !== 'done' && stt.phase !== 'failed')
                ? jsx('span', {
                    className: 'text-xs text-(--ui-text-warning, --ui-text-secondary)',
                    children: '⏳ ' + (stt.percent != null ? stt.percent + '% — ' : '') + stt.phase + '… ready when the build finishes'
                  })
                : stt.installed && stt.model_verified
                  ? jsx('span', { className: 'text-xs text-(--ui-text-success)', children: `✓ model ready — ${(stt.variant || 'q8_0').toUpperCase()}` })
                  : stt.installed
                    ? jsx('span', { className: 'text-xs text-(--ui-text-warning, --ui-text-secondary)', children: '⚠ installed — checksum re-verify pending' })
                    : jsx('span', { className: 'text-xs text-(--ui-text-secondary)', children: 'not installed' })
            ]
          }),
          jsx('div', {
            className: 'text-[10px] text-(--ui-text-tertiary)',
            children: 'your agents hear you — Parakeet streams words on this box, no cloud, nothing leaves your machine'
          }),
          stt.toolchain || stt.installed
            ? null
            : jsx('div', { className: 'text-[10px] text-(--ui-text-danger)', children: 'needs git, cmake, g++ on this machine' }),
          stt.variants ? jsx('select', {
            className: 'w-full rounded border border-(--ui-border) bg-transparent px-1 py-0.5 text-xs',
            value: stt.variant,
            onChange: sttSelect,
            children: stt.variants.map (v =>
              jsx('option', { value: v.id, children:
                `${v.label} — ${(v.bytes / 1048576).toFixed(0)} MB` +
                (v.sha_pinned ? ' · SHA-pinned' : '') }, v.id))
          }) : null,
          stt.installed
            ? jsx('div', {
                className: 'text-[10px] text-(--ui-text-tertiary)',
                children: 'watchkit serves it after its next health probe' + (stt.variant && stt.variant !== 'q8_0' ? ' · switching the quant asks for one more verified fetch — the engine build is reused' : ' · quant switches reuse the weight cache — the same bytes never re-download')
              })
            : jsx('div', {
                className: 'text-[10px] text-(--ui-text-tertiary)',
                children: `built from a pinned commit, model verified by SHA-256, installed into ${stt.dir} — ~${Math.round((((stt.variants || []).find(v => v.selected) || {}).bytes || 731000000) / 1048576)} MB download + a few-minute CPU build${stt.variants ? ' · quant switches reuse the weight cache — the same bytes never re-download' : ''}`
              }),
          (stt.phase && stt.phase !== 'idle' && stt.phase !== 'done' && stt.phase !== 'failed')
            ? jsxs('div', {
                className: 'flex items-center gap-2 text-xs',
                children: [
                  jsx('span', { children: `${stt.phase} ${stt.percent || 0}% — ${stt.detail || ''}` }),
                  jsx('button', {
                    className: 'rounded border border-(--ui-border) px-1 py-0.5 text-[10px]',
                    type: 'button', onClick: () => act(() => rest('/stt', { method: 'POST', body: { action: 'cancel' } })),
                    children: 'Cancel'
                  })
                ]
              })
            : !stt.installed && (ask && stt.preview)
              ? jsx(SttApproval, { p: stt.preview, busy, typed: sttTyped })
              : !stt.installed
                ? jsx('button', {
                    className: 'self-start rounded border border-(--ui-border) px-2 py-1 text-xs disabled:opacity-50',
                    type: 'button', disabled: busy || !stt.toolchain,
                    onClick: () => { $sttErr.set(''); $sttTyped.set(''); $sttAsk.set(true) },
                    children: 'Review & install'
                  })
                : null,
          sttNote ? jsx('div', { className: 'text-[10px]', children: sttNote }) : null,
          stt.error ? jsx('div', { className: 'text-[10px] text-(--ui-text-danger)', children: stt.error }) : null
        ]
      }, 'stt-card') : null,


644|      // ── voice-out (SPEC §19, slice 3) ───────────────────────────────────────
645|      // Gateway lane. WATCH_TTS_UI (top of file) parks the ON-WATCH surface: with
646|      // it false the 'watch' quant dropdown disappears and the copy stops naming
647|      // the wrist — the feature (player, karaoke, tiers, lane) stays implemented
648|      // and merged; flip the constant to true to restore every control AS-IS.
649|      air ? jsxs('div', {
650|        className: 'flex flex-col gap-1 rounded border border-(--ui-border) p-2',
651|        children: [
652|          jsxs('div', {
653|            className: 'flex items-center justify-between',
654|            children: [
655|              jsx('span', { className: 'font-medium', children: '🔊 Voice-out' }),
656|              air.installed && (air.model_verified || air.verified_pending)
657|                ? (air.engine || {}).phase === 'warm'
658|                  ? jsx('span', { className: 'text-xs text-(--ui-text-success, --ui-text-secondary)', children: '✓ model loaded — ready' })
659|                  : (air.engine || {}).phase === 'loading'
660|                    ? jsx('span', { className: 'text-xs text-(--ui-text-warning, --ui-text-secondary)', children: '⏳ loading model… (boot warm, ≈2 min once per gateway start)' })
661|                    : jsx('span', { className: 'text-xs text-(--ui-text-secondary)', children: '✓ installed · resident — model stays loaded (voice switching is free: zero-shot refs)' })
662|                : null
663|            ]
664|          }),
665|          jsx('div', {
666|            className: 'text-[10px] text-(--ui-text-tertiary)',
667|            children: WATCH_TTS_UI
668|              ? 'your agents speak on the watch — cloned voices, no cloud, audio only on your tailnet'
669|              : 'your agents speak with cloned voices — no cloud, audio only on your tailnet (Settings ▸ Voice ▸ WearKit Gateway)'
670|          }),
671|
672|          // source dropdowns. PARKED (owner 2026-11-10, SPEC §19.4(l)): the 'watch'
673|          // quant selector is on-watch TTS surface — gateway-only focus hides it.
674|          ...['gateway', ...(WATCH_TTS_UI ? ['watch'] : [])].map(slot => jsxs('label', {
675|            className: 'flex flex-col gap-0.5 text-xs',
676|            children: [
677|              jsx('span', {
678|                className: 'text-[10px] text-(--ui-text-tertiary)',
679|                children: slot === 'gateway'
680|                  ? 'gateway model (the box runs this — best it can hold)'
681|                  : 'watch model (the wrist pulls this — Q4 fits)'
682|              }),
683|              jsx('select', {
684|                className: 'rounded border border-(--ui-border) bg-transparent px-1 py-1 text-xs',
685|                value: (air.slots || {})[slot], disabled: busy,
686|                onChange: voPick(slot),
687|                children: (air.sources || []).map(sRow => jsx('option', {
688|                  value: sRow.id,
689|                  children: `${sRow.id} · ${sRow.gated ? 'consent+token' : 'no account'}` +
690|                    (sRow.bytes ? ` · ${Math.round(sRow.bytes / 1e6)} MB` : '')
691|                }, sRow.id))
692|              })
693|            ]
694|          }, `vo-src-${slot}`)),
695|
696|          // gated row chosen and no token yet: name the consent click, capture the key
697|          ((air.sources || []).find(sRow => (air.slots || {}).gateway === sRow.id) || {}).gated && !air.hf_token
698|            ? jsxs('div', {
699|                className: 'flex flex-col gap-1',
700|                children: [
701|                  jsx('a', {
702|                    className: 'text-[10px] underline text-(--ui-text-secondary)',
703|                    href: ((air.sources || []).find(sRow => (air.slots || {}).gateway === sRow.id) || {}).url || 'https://huggingface.co/neuphonic',
704|                    rel: 'noreferrer', target: '_blank',
705|                    children: '1. open the model page, click “Agree and access repository”'
706|                  }),
707|                  jsxs('div', {
708|                    className: 'flex items-center gap-1',
709|                    children: [
710|                      jsx('input', {
711|                        className: 'min-w-0 flex-1 rounded border border-(--ui-border) bg-transparent px-1 py-1 text-xs',
712|                        placeholder: '2. paste HF read token (stored 0600)', type: 'password',
713|                        value: voToken, disabled: busy,
714|                        onChange: e => $voToken.set(e.target.value)
715|                      }),
716|                      jsx('a', {
717|                        className: 'text-[10px] underline text-(--ui-text-tertiary)',
718|                        href: 'https://huggingface.co/settings/tokens', rel: 'noreferrer', target: '_blank',
719|                        children: 'keys'
720|                      })
721|                    ]
722|                  })
723|                ]
724|              }, 'vo-consent')
725|            : null,
726|
727|          installing
728|            ? jsxs('div', {
729|                className: 'flex items-center gap-2 text-xs',
730|                children: [
731|                  jsx('span', { children: `${air.phase} ${air.percent || 0}% — ${air.detail || ''}` }),
732|                  jsx('button', {
733|                    className: 'rounded border border-(--ui-border) px-1 py-0.5 text-[10px]',
734|                    type: 'button',
735|                    onClick: () => voiceAct(() => rest('/voice', { method: 'POST', body: { action: 'cancel' } })),
736|                    children: 'Cancel'
737|                  })
738|                ]
739|              })
740|            : (!air.installed ? jsxs('div', {
741|                className: 'flex flex-col gap-1',
742|                children: [
743|                  jsx('button', {
744|                    className: 'self-start rounded border border-(--ui-border) px-2 py-1 text-xs disabled:opacity-50',
745|                    type: 'button', disabled: busy || !air.toolchain,
746|                    onClick: voInstall,
747|                    children: 'Review & install engine'
748|                  }),
749|                  !air.toolchain
750|                    ? jsx('div', { className: 'text-[10px] text-(--ui-text-danger)', children: 'needs git, cmake, g++ on this machine' })
751|                    : null
752|                ]
753|              }) : null),
754|          air.error ? jsx('div', { className: 'text-[10px] text-(--ui-text-danger)', children: air.error }) : null,
755|
756|          // sound test: press play, hear the actual voice (the whole point of this card)
757|          jsxs('div', {
758|            className: 'flex items-center gap-1',
759|            children: [
760|              jsx('input', {
761|                className: 'min-w-0 flex-1 rounded border border-(--ui-border) bg-transparent px-1 py-1 text-xs',
762|                value: voText, disabled: busy,
763|                onChange: e => $voText.set(e.target.value)
764|              }),
765|              jsx('button', {
766|                className: 'rounded border border-(--ui-border) px-2 py-1 text-xs disabled:opacity-50',
767|                type: 'button', disabled: busy, onClick: voSoundTest, children: '▶ play'
768|              })
769|            ]
770|          }),
771|          voNote ? jsx('div', { className: 'text-[10px] text-(--ui-text-tertiary)', children: voNote }) : null,
772|
773|          // 'Speak on' (SPEC 19.4m): CPU or any GPU nvidia-smi reveals — the
774|          // worker is fenced with CUDA_VISIBLE_DEVICES to the picked index,
775|          // so the card you don't pick can never be touched.
776|          (air.devices || []).length > 1 ? jsxs('label', {
777|            className: 'flex flex-col gap-0.5 text-xs',
778|            children: [
779|              jsx('span', {
780|                className: 'text-[10px] text-(--ui-text-tertiary)',
781|                children: 'speak on (GPU selection builds the CUDA engine once; ' +
782|                          'until it is done the engine stays honestly on CPU)'
783|              }),
784|              jsx('select', {
785|                className: 'rounded border border-(--ui-border) bg-transparent px-1 py-1 text-xs',
786|                value: (air.engine || {}).device || 'cpu', disabled: busy,
787|                onChange: voSetDevice,
788|                children: (air.devices || []).map(d => jsx('option', {
789|                  value: d.id,
790|                  children: d.label + (d.free_mb != null ? ` · ${Math.round(d.free_mb / 1024)} GB free` : '') +
791|                    (d.note ? ` · ${d.note}` : '') + (d.selected ? ' · chosen' : '')
792|                }, d.id))
793|              }),
794|              (air.engine || {}).build && (air.engine || {}).build.phase === 'building'
795|                ? jsx('div', { className: 'text-[10px] text-(--ui-text-secondary)', children: 'building: ' + ((air.engine || {}).build.detail || 'one CUDA build…') })
796|                : (air.engine || {}).build && (air.engine || {}).build.phase === 'failed'
797|                  ? jsx('div', { className: 'text-[10px] text-(--ui-text-danger)', children: 'CUDA build failed: ' + ((air.engine || {}).build.detail || '') })
798|                  : null
799|            ]
800|          }, 'vo-device') : null,
801|
802|          // one global speed dial + karaoke toggle (canonical prefs, rev-bumped)
803|          voicing ? jsxs('div', {
804|            className: 'flex items-center gap-2 text-xs',
805|            children: [
806|              jsx('span', { className: 'text-[10px] text-(--ui-text-tertiary)', children: 'speed · at playback' }),
807|              jsx('input', {
808|                type: 'range', min: 0.5, max: 2, step: 0.05, value: shownSpeed,
809|                className: 'min-w-0 flex-1', onChange: voSpeed
810|              }),
811|              jsx('span', { children: `${Number(shownSpeed).toFixed(2)}×` }),
812|              jsxs('label', {
813|                className: 'flex cursor-pointer items-center gap-1',
814|                children: [
815|                  jsx('input', { type: 'checkbox', checked: !!voicing.karaoke, disabled: busy, onChange: voKaraoke }),
816|                  jsx('span', { className: 'text-[10px] text-(--ui-text-tertiary)', children: 'karaoke' })
817|                ]
818|              })
819|            ]
820|          }) : null,
821|
822|          // voice cards (the founder's ships as the default; record whoever you want to hear)
823|          voicing && Object.keys(voicing.cards || {}).length
824|            ? jsxs('div', {
825|                className: 'flex flex-col gap-0.5',
826|                children: [
827|                  ...Object.entries(voicing.cards).map(([cid, m]) => jsxs('div', {
828|                    className: 'flex items-center justify-between gap-1 text-xs',
829|                    children: [
830|                      jsxs('span', {
831|                        className: 'text-(--ui-text-secondary)',
832|                        children: [m.label || cid.slice(0, 8),
833|                                   m.ref_rate ? ` · ${Math.round(m.ref_rate)} wpm` : '']
834|                      }),
835|                      jsx('button', {
836|                        className: 'rounded border border-(--ui-border) px-1 py-0.5 text-[10px] disabled:opacity-50',
837|                        type: 'button', disabled: busy, onClick: () => voDelete(cid),
838|                        children: '✕'
839|                      })
840|                    ]
841|                  }, cid))
842|                ]
843|              })
844|            : null,
845|          WATCH_TTS_UI ? jsx('div', {
846|            className: 'text-[10px] text-(--ui-text-tertiary)',
847|            children: 'record more voices from the watch — yours, or anyone’s (recorder ships with the player)'
848|          }) : null
849|        ]
850|      }, 'voiceout-card') : null,
851|
852|      jsx('div', { className: 'flex flex-col gap-1', children: lights })
853|    ]
854|  })
855|}
856|
857|export default {
858|  id: 'wearos',
859|  name: 'Wear Kit',
860|  register (ctx) {
861|    api = ctx
862|    ctx.register({
863|      id: 'wearos-pane',
864|      area: 'panes',
865|      title: 'wear kit',
866|      data: { placement: 'right', width: '320px' },
867|      render: () => jsx(WearKitPane, {})
868|    })
869|  }
870|}