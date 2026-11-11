// Hermes Wear Kit — Desktop pane (protocol v2). SDK-surface only (rule 8): imports are
// @hermes/plugin-sdk + react/jsx-runtime, no eval/new Function, no dynamic import(), no
// prototype writes, no core-UI reach-ins. `hermes plugins validate` enforces this.
//
// Three surface facts shaped it, all measured against the installed core (v0.23.0):
//   · No sibling assets: the loader evaluates plugin.js from a blob: URL, so the hero is
//     an inline SVG wordmark (zero assets, works everywhere).
//   · There is NO tool-call door for panes (src/sdk/index.ts `host` has none): the real
//     door is per-plugin REST — ctx.rest('/status') → dashboard/plugin_api.py, hot-mounted.
//   · The pane is a LOGIN surface (dashboard auth middleware), which is what makes its
//     Approve/Remove/Install buttons legitimate consent points (rules 12/13).
//
// v2 flow shown here: Pair button opens the window → the WATCH shows a gateway-issued
// code → the human types it here (or in a terminal). Enable checkboxes delegate to
// `hermes -p <profile> plugins enable` — no hand-rolled config editor anymore.
import { atom, host, haptic, useValue } from '@hermes/plugin-sdk'
import { jsx, jsxs } from 'react/jsx-runtime'
import { Fragment, useEffect } from 'react'

const FALLBACK_RELEASES = 'https://github.com/borealBytes/hermes-wearos/releases/latest'

// ON-WATCH VOICE-OUT IS PARKED (owner ruling 2026-11-10, issue #7): the watch-side
// TTS *surface* is pulled from the pane — the 'watch' quant dropdown, the wrist copy,
// and the recorder note — because there is no hardware test time before the deadline
// and the milestone focus is the GATEWAY lane (Settings ▸ Voice ▸ 'wearkit-gateway').
// The feature itself stays merged and tested: Kotlin player + GatewayTts lane,
// voiceout tiers, pane REST actions. Flip to true to restore every control AS-IS.
// SPEC §19.4(l).
const WATCH_TTS_UI = false
const REPO_URL = 'https://github.com/borealBytes/hermes-wearos'

const $status = atom(null)
const $stt = atom(null)
const $voice = atom(null)
const $voToken = atom('')
const $voText = atom('Your cron digest is ready: three farm alerts, no incidents, sprayer window opens Thursday at six ten.')
const $voNote = atom('')
const $speedDraft = atom(null)   // mid-drag thumb position; wins over pollers until the commit lands
const $sttAsk = atom(false)   // sudo-style approval card open (commands shown first)
const $sttTyped = atom('')    // the sudo gate: Run stays disabled until this reads "install"
const $sttErr = atom('')      // start-time refusal shown ON the card, not swallowed
const $busy = atom(false)
const $error = atom('')
const $code = atom('')
const $pairNote = atom('')
const $renaming = atom('')       // card_id whose label is being edited ('' = none)
let _renameDraft = ''            // UNCONTROLLED draft: typing never re-renders

let api = null
const rest = (path, opts) => {
  if (!api) return Promise.reject(new Error('wearos: register() has not run'))
  return api.rest(path, opts)
}

// Rows the owner just clicked whose state /status hasn't caught up with yet
// (the post-click refresh runs on a background thread; serving-stale answers
// for a few seconds). Overlay keeps the box honest to the CLICK, not the race.
const pendingEnable = {}
function overlayPending (s) {
  if (!s || !Array.isArray(s.profiles)) return s
  s.profiles = s.profiles.map(p => {
    if (!(p.name in pendingEnable)) return p
    // once the server confirms the click, the overlay has done its job
    if (p.enabled === pendingEnable[p.name]) { delete pendingEnable[p.name]; return p }
    return { ...p, enabled: pendingEnable[p.name] }
  })
  return s
}
function markPending (out, profile, on) {
  if (out && out.ok) {
    pendingEnable[profile] = !!on
    const cur = $status.get()
    if (cur && Array.isArray(cur.profiles)) {
      $status.set(overlayPending({ ...cur,
        profiles: cur.profiles.map(p => p.name === profile ? { ...p, enabled: !!on } : p) }))
    }
  }
}

async function refresh () {
  try {
    $status.set(overlayPending(await rest('/status')))
    $error.set('')
  } catch (error) {
    // Keep the last view, but SAY what's wrong. 404 ⇒ the backend half isn't mounted
    // yet under this window: Rescan fixes it (hot-mount), no restart.
    $error.set(String((error && error.message) || error))
  }
  try {
    $stt.set(await rest('/stt'))
  } catch { /* voice card hides itself without data */ }
}

async function refreshVoice () {
  try { $voice.set(await rest('/voice')) } catch { /* card hides itself */ }
}

async function voiceAct (fn) {
  haptic('tap')
  $busy.set(true)
  try { await fn(); await refreshVoice() } finally { $busy.set(false) }
}

// dropdown choice = POST /voice {action:select}; persists server-side, no reload needed
function voPick (slot) {
  return e => voiceAct(() => rest('/voice', {
    method: 'POST', body: { action: 'select', source: e.target.value, for: slot }
  }))
}

// 'Speak on' = CPU or one named GPU (SPEC 19.4m). The pick IS the consent to
// build the CUDA engine variant once; meanwhile the CPU engine keeps talking
// and the note says so — never a placebo switch.
function voSetDevice (ev) {
  const dev = ev.target.value
  $voNote.set(dev.startsWith('gpu:')
    ? 'engine still on CPU while the one-time CUDA build runs (see the row below)'
    : 'engine moving back to CPU on the next speak')
  voiceAct(() => rest('/voice', {
    method: 'POST', body: { action: 'set_engine_device', device: dev }
  }))
}

async function sttSelect (ev) {
  await act(() => rest('/stt', {
    method: 'POST', body: { action: 'select', variant: ev.target.value }
  }))
}

// Install = the consent dialog first (rule 12): steps, hosts, disk, per chosen row.
function voInstall () {
  const air = ($voice.get() || {}).air || {}
  const p = air.preview
  if (!p) return
  const yes = window.confirm(`${p.title}\n` +
    p.steps.map(s => `• ${s}`).join('\n') +
    `\n• needs: ${p.needs.join(', ')} · disk ~${p.disk_mb} MB` +
    `\n• hosts: ${p.hosts.join(', ')}`)
  if (!yes) return
  $voNote.set('')
  voiceAct(() => rest('/voice', { method: 'POST', body: {
    action: 'install', token: String($voToken.get() || '').trim() || undefined
  } }))
}

// Sound test: the lane returns a real wav, the pane plays it. No engine → the
// refusal text, never a silent button (issue #7 honesty rule).
async function voSoundTest () {
  // The note answers the click FIRST: a cold engine call reloads model weights and
  // can take minutes (owner report 2026-11-10: "I click play, nothing plays" — the
  // wait was invisible). Cached/hot calls overwrite it within a second. While the
  // POST is in flight we POLL the engine state every few seconds, so the spinner
  // always names WHICH wait it is: model loading, queued behind a render, speaking.
  $voNote.set('starting…')
  const poll = setInterval(async () => {
    try {
      const v = await rest('/voice')
      const e = ((v || {}).air || {}).engine || {}
      $voNote.set(e.phase === 'loading'
        ? 'loading voice model… (first call after boot — ≈2 minutes, once)'
        : (e.queued || 0) > 1
          ? `synthesizing… (queued: ${e.queued} in line, first in first out)`
          : 'synthesizing…')
    } catch { /* keep the last note */ }
  }, 4000)
  await voiceAct(async () => {
    try {
      const out = await rest('/voice', {
        method: 'POST', body: { action: 'soundtest', text: $voText.get() || '' } })
      if (out && out.audio_wav_b64) {
        const bin = window.atob(out.audio_wav_b64)
        const u8 = new Uint8Array(bin.length)
        for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i)
        const url = URL.createObjectURL(new window.Blob([u8], { type: 'audio/wav' }))
        // PACING AT PLAYBACK: the clip is the natural 1x take; the dial rides here.
        const a = new window.Audio(url)
        a.playbackRate = Number(out.speed) || 1
        a.preservesPitch = true
        await a.play()
        $voNote.set(`done ✓ played · engine ${out.engine || 'air'}${out.cached ? ' · cached' : ''}`)
      } else {
        $voNote.set((out && (out.error || out.refused)) || 'no audio')
      }
    } catch (e) {
      $voNote.set('synth request failed: ' + String((e && e.message) || e))
      throw e
    }
  }).finally(() => clearInterval(poll))
}

// PACING AT PLAYBACK + drag law (owner 2026-11-10, twice fixed): (1) the dial moved
// one notch at a time because every change POST went through voiceAct — busy=true
// disabled the slider mid-gesture; (2) it STILL snapped because the 2s/8s refreshVoice
// pollers re-seed $voice with the SERVER speed mid-drag (commit is debounced). Law:
// the thumb position lives in $speedDraft from the first move until the commit's
// refresh lands — pollers can overwrite everything else, the draft wins the slider.
let _speedT = null
const voSpeed = e => {
  const v = Number(e.target.value)
  $speedDraft.set(v)
  const cur = $voice.get()
  if (cur && cur.voice) {
    cur.voice = Object.assign({}, cur.voice, { speed: v })
    $voice.set(Object.assign({}, cur))
  }
  clearTimeout(_speedT)
  _speedT = setTimeout(() => {
    rest('/voice', { method: 'POST', body: { action: 'speed', speed: v } })
      .then(refreshVoice)
      .catch(() => { $voNote.set('speed commit failed — check the gateway') })
      .finally(() => { $speedDraft.set(null) })
  }, 250)
}
const voKaraoke = e => voiceAct(() => rest('/voice', {
  method: 'POST', body: { action: 'karaoke', karaoke: e.target.checked } }))
const $confirmDel = atom('')      // card_id armed for delete ('' = none)
// Default picker feedback must be UNMISTAKABLE (owner 2026-11-11: "the
// checkbox doesn't turn green"): theme-independent green accent, an optimistic
// tick the instant you touch it (the refresh confirms, a failed POST reverts
// it), and a green dot on the row itself — the truth never rides on a 12px
// glyph the mono theme can flatten.
const $defPick = atom('')
const _isDefault = (voicing, cid) =>
  ((((voicing && voicing.voices) || {}).__default__) === cid) || $defPick.get() === cid
const voDelete = cid => { $confirmDel.set(''); voiceAct(() => rest('/voice', {
  method: 'POST', body: { action: 'card_delete', card: cid } })) }
// Rename is label-only: the card_id (uuid-ish slug) is the identity every
// binding speaks; the name you see is display text and can change forever.
const voRenameStart = (cid, label) => { _renameDraft = label; $renaming.set(cid) }
const voRename = cid => {
  const label = String(_renameDraft || '').trim().slice(0, 48)
  $renaming.set('')
  if (!label) return
  voiceAct(() => rest('/voice', {
    method: 'POST', body: { action: 'card_rename', card: cid, label } }))
}


// ── PANE-SIDE ENROLLMENT (owner 2026-11-11: "we can add voices from the pane
// OR from the watch itself"). Same contract as the wrist: a freeform 3–12 s
// take from the system default mic; the pipeline then HEARS it (Parakeet ->
// real transcript -> measured pace) exactly like a watch enrollment. The take
// travels as 16 kHz mono PCM16 WAV — the format cards speak — resampled and
// mixed down in-page, so the server sees no format difference at all.
const $enrolling = atom(false)
const $recPhase = atom('idle')          // idle | rec | ready | send
const $recSecs = atom(0)
let _recEnd = null, _recT = null, _recBlob = null, _recUrl = '', _recName = ''
const $recFrames = atom(0)
function _f32ToWav16k (mono, srcRate) {   // Float32 mic capture -> 16k mono PCM16 WAV
  const rate = 16000, n = Math.round(mono.length * rate / srcRate)
  const f = new Float32Array(n)
  const sc = mono.length / n
  for (let i = 0; i < n; i++) {
    const pos = i * sc, i0 = Math.floor(pos), i1 = Math.min(i0 + 1, mono.length - 1), w = pos - i0
    f[i] = mono[i0] * (1 - w) + mono[i1] * w
  }
  const buf = new ArrayBuffer(44 + n * 2), dv = new DataView(buf)
  const tag = (o, t) => { for (let i = 0; i < t.length; i++) dv.setUint8(o + i, t.charCodeAt(i)) }
  tag(0, 'RIFF'); dv.setUint32(4, 36 + n * 2, true); tag(8, 'WAVE'); tag(12, 'fmt ')
  dv.setUint32(16, 16, true); dv.setUint16(20, 1, true); dv.setUint16(22, 1, true)
  dv.setUint32(24, rate, true); dv.setUint32(28, rate * 2, true)
  dv.setUint16(32, 2, true); dv.setUint16(34, 16, true); tag(36, 'data')
  dv.setUint32(40, n * 2, true)
  for (let i = 0; i < n; i++) {
    const v = Math.max(-1, Math.min(1, f[i]))
    dv.setInt16(44 + i * 2, v < 0 ? v * 0x8000 : v * 0x7fff, true)
  }
  return new Uint8Array(buf)
}
function _b64 (u8) {
  let bin = ''
  for (let i = 0; i < u8.length; i += 0x8000)
    bin += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000))
  return window.btoa(bin)
}
async function voRecStart () {
  $voNote.set('opening the microphone…')
  // RAW PCM capture (owner 2026-11-11: MediaRecorder froze between stop and
  // ready on the 12 s auto-stop — twice). No codec, no blob decode: a
  // ScriptProcessor taps the mic straight into Float32 buffers, and the WAV
  // is assembled synchronously at stop. The recorder can no longer have a
  // secret life; the row can only be idle, recording, or holding a take.
  let stream
  try {
    stream = await navigator.mediaDevices.getUserMedia(
      { audio: { channelCount: 1, echoCancellation: false,
                 noiseSuppression: false, autoGainControl: true } })
  } catch (e) {
    $voNote.set('microphone refused: ' + String((e && e.message) || e) +
                ' — or record the take from the watch')
    return
  }
  _recBlob = null
  const ctx = new (window.AudioContext || window.webkitAudioContext)()
  ctx.resume().catch(() => {})
  const src = ctx.createMediaStreamSource(stream)
  const mute = ctx.createGain()
  mute.gain.value = 0                    // monitor path stays silent, no echo
  const proc = ctx.createScriptProcessor(4096, 1, 1)
  const parts = []
  proc.onaudioprocess = (ev) => {
    if ($recPhase.get() === 'rec') {
      parts.push(new Float32Array(ev.inputBuffer.getChannelData(0)))
      $recFrames.set($recFrames.get() + ev.inputBuffer.length)
    }
  }
  src.connect(proc); proc.connect(mute); mute.connect(ctx.destination)
  _recEnd = () => {
    clearInterval(_recT)
    try { proc.disconnect(); src.disconnect(); mute.disconnect() } catch { /* gone */ }
    stream.getTracks().forEach(t => t.stop())
    let n = 0
    for (const q of parts) n += q.length
    if (!n) {
      $voNote.set('the microphone handed back nothing — check the input device')
      $recPhase.set('idle'); $recSecs.set(0); ctx.close().catch(() => {})
      return
    }
    const mono = new Float32Array(n)
    let off = 0
    for (const q of parts) { mono.set(q, off); off += q.length }
    _recBlob = _f32ToWav16k(mono, ctx.sampleRate)
    $recSecs.set(Math.round(n / ctx.sampleRate * 10) / 10)
    $recPhase.set('ready')
    ctx.close().catch(() => {})
  }
  $recPhase.set('rec')
  $recSecs.set(0)
  clearInterval(_recT)
  const tick = () => {
    const v = Number($recSecs.get() || 0) + 0.5
    $recSecs.set(v)
    if ($recFrames.get() > 0 && v >= 12.5) voRecStop()   // 3–12 s law; the gateway re-proves it
    else if (v >= 14) {                                   // 14 s with ZERO frames: dead pipe
      voRecStop()
      $voNote.set('the microphone stream carried no audio at all (0 samples in ' +
                  Math.floor(v) + ' s) — another app owns the device, or the OS ' +
                  'mic permission was revoked for this app')
    }
  }
  tick()                       // first increment lands with the click, not 500 ms later
  _recT = setInterval(tick, 500)
}
function voRecStop () {
  // synchronous handoff: _recEnd assembles the WAV on the spot — there is no
  // async decode step left that could fail to call back.
  if (_recEnd) { const f = _recEnd; _recEnd = null; f() }
}
function voRecPlay () {
  if (!_recBlob) return
  if (_recUrl) URL.revokeObjectURL(_recUrl)
  _recUrl = URL.createObjectURL(new window.Blob([_recBlob], { type: 'audio/wav' }))
  new window.Audio(_recUrl).play().catch(() => $voNote.set('playback blocked by the browser'))
}
function voRecRetry () {
  _recBlob = null
  if (_recUrl) { URL.revokeObjectURL(_recUrl); _recUrl = '' }
  $recPhase.set('idle'); $recSecs.set(0); $recFrames.set(0)
}
function voRecClose () { voRecStop(); voRecRetry(); $enrolling.set(false) }
async function voRecConfirm () {
  const label = String(_recName || '').trim().slice(0, 48)
  if (!label) { $voNote.set('give the voice a name first'); return }
  if (!_recBlob) return
  if (($recBlob.length - 44) / 2 / 16000 < 3) {
    $voNote.set('too short — talk for 3–12 seconds'); return
  }
  $recPhase.set('send')
  $voNote.set('sending your take… (compressed, then the gateway listens to it)')
  // A ✓ never answers with silence (doctrine): every failure mode of the
  // upload lands as a note line under the play button, not a dead row.
  const gz = (_recBlob && window.CompressionStream && ($voice.get() || {}).revision)
    ? await new Response(new Blob([_recBlob])
        .stream().pipeThrough(new window.CompressionStream('gzip'))).arrayBuffer()
    : null
  await voiceAct(async () => {
    const out = await rest('/voice', {
      method: 'POST',
      body: { action: 'card_create', label, transcript: '',
              gzip: !!gz, wav_b64: _b64(gz ? new Uint8Array(gz) : _recBlob) } })
    if (out && out.error) { $voNote.set('gateway refused: ' + out.error); throw new Error(out.error) }
    if (out && out.card_id) {
      $voNote.set('voice added' + (out.transcript ? ' — heard: "' +
        String(out.transcript).slice(0, 60) + '"' : ' (words arrive when the engine is up)'))
      voRecRetry()
    } else {
      $voNote.set('gateway answer made no sense: ' + JSON.stringify(out).slice(0, 120))
      voRecRetry()
    }
  }).catch(e => {
    $voNote.set('enroll upload failed: ' + String((e && e.message) || e).slice(0, 200))
    voRecRetry()
  })
}

async function act (fn) {
  haptic('tap')
  $busy.set(true)
  // A failed call must never be silent (pane doctrine): surface it in the
  // card's own error line before refresh() has a chance to overwrite it.
  try { await fn() } catch (e) { $error.set(`wearos: ${String((e && e.message) || e)}`) }
  try { await refresh() } finally { $busy.set(false) }
}

// One checkbox = one POST = one fixed-argv `hermes -p <profile> plugins enable|disable`
async function toggle (profile, on) {
  await act(async () => {
    const out = await rest('/enable', { method: 'POST', body: { agent: profile, enable: on } })
    if (out && out.ok === false) {
      host.notify({ kind: 'error', message: `wearos: ${out.detail || out.error}` })
    }
    markPending(out, profile, on)
  })
}

async function closePairWindow () {
  $pairNote.set('window closed')
  await act(() => rest('/pair/window', { method: 'POST', body: { action: 'close' } }))
}

async function openPairWindow () {
  $pairNote.set('opening…')
  await act(async () => {
    const out = await rest('/pair/window', { method: 'POST', body: { action: 'open' } })
    $pairNote.set(out && out.opened
      ? 'window open ~5 min — tap Pair on the watch now'
      : 'window open')
  })
}

async function claimCode () {
  const code = String($code.get() || '').trim()
  if (!/^[1-9][0-9]{5}$/.test(code)) { $pairNote.set('six digits, from the watch'); return }
  await act(async () => {
    const out = await rest('/pair/claim', { method: 'POST', body: { code } })
    if (out && out.error) {
      $pairNote.set(out.lock && out.lock.locked
        ? 'too many wrong codes — locked for a few minutes'
        : `claim failed: ${out.error}`)
    } else {
      $pairNote.set(`approved: ${out.approved.label} → agent ${out.approved.profile}`)
      $code.set('')
    }
  })
}

async function revoke (name, label, profile) {
  const restart = profile === 'default'
    ? '\n⚠ THE GATEWAY WILL RESTART — active chats on every agent are interrupted.' : ''
  const yes = window.confirm(
    `Remove "${label}" from agent ${profile}?\n` +
    '• deletes it from your tailnet\n' +
    '• invalidates its device token (queues purged)\n' +
    '• rotates the agent API key — other paired watches pick it up automatically;' +
    ' scripts that use the old value must update.' + restart)
  if (!yes) return
  await act(async () => {
    const out = await rest('/devices/revoke', { method: 'POST', body: { name } })
    const bad = (out.steps || []).filter(s => !s.ok)
    host.notify({
      kind: bad.length ? 'warn' : 'info',
      message: bad.length ? `removed with notes: ${bad.map(b => `${b.step}: ${b.detail}`).join(' · ')}`
                          : `removed ${name} (token, tailnet node, key rotation)`
    })
  })
}

async function setAlerts (profile, pref) {
  await act(() => rest('/alerts', { method: 'POST', body: { agent: profile, pref } }))
}

// STT install consent (sudo law, D16 rework): the button opens an APPROVAL CARD that
// shows the exact commands before anything runs. Two exits for the human: run it here
// (POST /stt — the POST is the permission, nothing fires on view) or copy the one-liner
// and drive it from a terminal yourself.
async function sttRun () {
  if (($sttTyped.get() || '').trim().toLowerCase() !== 'install') return
  $sttErr.set('')
  await act(async () => {
    const out = await rest('/stt', { method: 'POST', body: { action: 'install' } })
    if (out && out.started) { $sttAsk.set(false); $sttTyped.set('') }   // progress line takes over
    else $sttErr.set((out && out.error) || 'not started')
  })
}

function sttCopy () {
  const p = ($stt.get() || {}).preview
  if (!p) return
  navigator.clipboard.writeText(p.cli).then(
    () => $sttErr.set('copied — paste into any terminal; nothing runs until you press Enter'),
    () => $sttErr.set(`clipboard blocked — run manually: ${p.cli}`))
}

// The approval card: exact commands FIRST (from preview.commands — the same argv
// lists stt_install._run executes), disclosure line, then Run-here / Copy / Cancel.
function SttApproval ({ p, busy, typed }) {
  const armed = (typed || '').trim().toLowerCase() === 'install'
  return jsxs('div', {
    className: 'flex flex-col gap-1 rounded border border-(--ui-border) p-2',
    children: [
      jsx('div', { className: 'font-medium', children: 'This will run, in order — nothing starts until you choose:' }),
      jsx('pre', {
        className: 'overflow-x-auto rounded bg-(--ui-bg-secondary) p-1.5 text-[10px] leading-relaxed text-(--ui-text-secondary)',
        children: (p.commands || p.steps.map(s => `· ${s}`)).join('\n')
      }),
      jsx('div', {
        className: 'text-[10px] text-(--ui-text-tertiary)',
        children: `hosts: ${p.hosts.join(', ')} · needs: ${p.needs.join(', ')} · disk ~${p.disk_mb} MB` +
          ' · no sudo, no system Python — everything lands in the plugin data dir'
      }),
      // The sudo gate: typing the word is the password moment — intent proven AFTER
      // reading the commands, not a checkbox read at first glance.
      jsx('input', {
        className: 'rounded border border-(--ui-border) bg-transparent px-1.5 py-1 text-xs',
        type: 'text', placeholder: 'type “install” to arm Run', value: typed || '',
        onInput: (e) => $sttTyped.set(e.target.value)
      }),
      jsxs('div', {
        className: 'flex items-center gap-2',
        children: [
          jsx('button', {
            className: 'rounded border border-(--ui-border) px-2 py-1 text-xs disabled:opacity-50',
            type: 'button', disabled: busy || !armed, onClick: sttRun,
            children: armed ? '▶ Run it here' : 'Run it here (locked)'
          }),
          p.cli ? jsx('button', {
            className: 'rounded border border-(--ui-border) px-2 py-1 text-[10px]',
            type: 'button', onClick: sttCopy,
            children: 'Copy command'
          }) : null,
          jsx('button', {
            className: 'px-1 py-1 text-[10px] text-(--ui-text-tertiary)',
            type: 'button', onClick: () => $sttAsk.set(false),
            children: 'Cancel'
          })
        ]
      })
    ]
  }, 'stt-approval')
}

function Step ({ done, n, title, children }) {
  return jsxs('div', {
    className: 'flex flex-col gap-1 rounded border border-(--ui-border) p-2',
    children: [
      jsxs('div', {
        className: 'flex items-center gap-2',
        children: [
          jsx('span', {
            className: done ? 'text-(--ui-text-secondary)' : 'text-(--ui-text-tertiary)',
            children: done ? '✓' : `${n}.`
          }),
          jsx('span', { className: 'font-medium', children: title })
        ]
      }),
      jsx('div', { className: 'text-xs text-(--ui-text-tertiary)', children })
    ]
  }, `step-${n}`)
}

function WearKitPane () {
  const status = useValue($status)
  const stt = useValue($stt)
  const voice = useValue($voice)
  const speedDraft = useValue($speedDraft)
  const shownSpeed = speedDraft == null ? (voice && voice.voice && voice.voice.speed) : speedDraft
  const voToken = useValue($voToken)
  const voText = useValue($voText)
  const voNote = useValue($voNote)
  const ask = useValue($sttAsk)
  const sttTyped = useValue($sttTyped)
  const sttNote = useValue($sttErr)
  const busy = useValue($busy)
  const error = useValue($error)
  const code = useValue($code)
  const note = useValue($pairNote)
  const air = (voice && voice.air) || null
  const voicing = (voice && voice.voice) || null
  const installing = air && air.phase &&
    !['idle', 'done', 'failed', 'cancelled'].includes(air.phase)
  useEffect(() => {
    refresh()
    const timer = setInterval(() => { refresh(); refreshVoice() }, 8_000) // pairing completes while you watch this pane
    return () => clearInterval(timer)
  }, [])
  useEffect(() => {
    if (!installing) return undefined
    const fast = setInterval(refreshVoice, 2_000) // install progress deserves a live bar
    return () => clearInterval(fast)
  }, [installing])

  const checks = (status && status.checks) || {}
  const devices = Object.entries((status && status.devices) || {})
  const profiles = (status && status.profiles) || []
  const pairing = (status && status.pairing) || {}
  const windowOpen = !!pairing.window
  const pending = pairing.pending || []
  const lock = pairing.claim_lock || {}
  const tailnet = (status && status.tailnet) || {}
  const releases = (status && status.apk_release_url) || FALLBACK_RELEASES
  const enabledCount = profiles.filter(p => p.enabled).length
  // owner ruling 2026-11-10: enable implies the grant (one checkbox); each row's
  // dropdown overrides the single Voice-out card below for that agent (blank =
  // follow the global). Cards come from the /voice feed already in $voice.
  const voFeedDoc = (($voice.get() || {}).voice || {})
  const agentVoice = voFeedDoc.voices || {}
  const cardList = Object.entries(voFeedDoc.cards || {})
    .map(([id, m]) => ({ id, label: (m && m.label) || id }))
  const allEnabled = profiles.length > 0 && profiles.every(p => p.enabled)
  const enableAll = on => act(async () => {
    for (const p of profiles.filter(q => !!q.enabled !== on)) {
      const out = await rest('/enable', { method: 'POST', body: { agent: p.name, enable: on } })
      if (out && out.ok === false) {
        host.notify({ kind: 'error', message: `wearos ${p.name}: ${out.detail || out.error}` })
      }
      markPending(out, p.name, on)
    }
  })
  const setAgentVoice = (name, card) => voiceAct(() => rest('/voice', {
    method: 'POST', body: { action: 'voice', agent: name, card } }))

  // Health row, humanized (owner 2026-11-11: "explain them to me, aligned with
  // the open-window button"). Raw keys were internals leaking into UI. Each row
  // says what it MEANS; the pairing row carries the WINDOW truth, not the
  // always-on door truth — closed is the healthy default, so it is a calm grey,
  // never a red alarm. The button above opens it; this row now tells the truth
  // about that exact state.
  const LIGHT = {
    tailscaled: ['tailscale daemon', 'the private network this kit runs on'],
    claim_door: ['pairing window', 'new watches can pair only while this is open (~5 min, from the Pair button)'],
    private_lane: ['private lane', 'the watch-only door (42654): audio + pairing, never public'],
    funnel: ['tailscale funnel', 'https front that lets a watch reach the pairing door'],
    devices: ['paired watches', 'watches with a live pairing on this gateway'],
  }
  const lights = Object.entries(checks).map(([name, check]) => {
    const [label, tip] = LIGHT[name] || [name, '']
    if (name === 'claim_door')
      return jsxs('div', {
        className: 'flex items-center justify-between gap-2 text-sm',
        children: [
          jsxs('span', { title: tip, className: 'text-(--ui-text-secondary)',
                         children: [windowOpen ? '✅ ' : '⬜ ', label] }),
          jsx('span', { className: 'text-[10px] text-(--ui-text-tertiary)',
                        children: windowOpen ? `open — ${(pairing.window.seconds_left||0)}s left` : 'closed (normal)' })
        ]
      }, name)
    return jsx('div', {
      className: 'flex items-center justify-between gap-2 text-sm',
      children: jsxs('span', {
        title: tip,
        className: check.ok ? 'text-(--ui-text-secondary)' : 'text-(--ui-text-danger)',
        children: [check.ok ? '✅ ' : '❌ ', label, check.ok ? '' : ` — ${check.detail}`]
      })
    }, name)
  })

  return jsxs('div', {
    className: 'flex h-full flex-col gap-2 overflow-y-auto p-3 text-sm',
    children: [
      jsx('svg', {
        viewBox: '0 0 240 24', height: 22, role: 'img', 'aria-label': 'Hermes Wear Kit',
        className: 'text-(--ui-text-secondary)',
        children: jsx('text', {
          x: 1, y: 17, fill: 'currentColor',
          style: { fontSize: '13px', fontWeight: 600, letterSpacing: '0.26em' },
          children: 'HERMES WEAR KIT'
        })
      }),
      jsxs('div', {
        className: 'flex flex-col gap-1 rounded border border-(--ui-border) p-2 text-xs',
        children: [
          jsx('div', {
            className: 'font-medium text-(--ui-text-secondary)',
            children: '⌚ One watch. Your tailnet. No middleman.'
          }),
          jsxs('div', {
            className: 'text-(--ui-text-tertiary)',
            children: [
              'The watch app is ',
              jsx('a', { className: 'underline', href: REPO_URL, rel: 'noreferrer', target: '_blank', children: 'borealBytes/hermes-wearos' }),
              ' — this plugin is its pairing + provisioning half.'
            ]
          }),
          jsx('div', { className: 'text-(--ui-text-tertiary)', children: '• the gateway issues the code, the watch shows it, a human types it back — never through a model' }),
          jsx('div', { className: 'text-(--ui-text-tertiary)', children: '• every call rides the watch\u2019s own node — no relay, no phone app; the API key is handed over the tailnet only' }),
          jsx('div', { className: 'text-(--ui-text-tertiary)', children: '• full revoke: node, token and a rotated agent key in one confirm' }),
          jsx('div', {
            className: 'font-medium text-(--ui-text-secondary)',
            children: 'Open source forever: the Release APK is a courtesy — making it yours is the point.'
          })
        ]
      }),
      error
        ? jsx('div', {
            className: 'text-xs text-(--ui-text-danger)',
            children: `backend not answering: ${error} — hit Rescan on the Wear Kit card (no restart needed: the route hot-mounts).`
          })
        : null,

      // ── 1 · APK (owner ruling 2026-11-10: install first, THEN pair, THEN
      //    enable — the step order is the story; checked once a watch exists,
      //    because a paired watch proves the APK is on the wrist) ───────────
      jsx(Step, {
        done: devices.length > 0, n: 1, title: 'Install the APK on the watch',
        children: jsxs('span', {
          children: [
            'Get the Wear OS app from ',
            jsx('a', { className: 'underline', href: releases, rel: 'noreferrer', target: '_blank', children: 'GitHub Releases' }),
            ' (CI-built APK + SHA256SUMS), sideload it, open it once.'
          ]
        })
      }, 'apk-card'),

      // ── pairing card ──────────────────────────────────────────────────────────
      jsxs('div', {
        className: 'flex flex-col gap-1 rounded border border-(--ui-border) p-2',
        children: [
          jsxs('div', {
            className: 'flex items-center justify-between',
            children: [
              jsx('span', { className: 'font-medium', children: 'Pair a watch' }),
              jsxs('div', {
                className: 'flex items-center gap-2',
                children: [
                  windowOpen ? jsx('span', {
                    className: 'text-[10px] text-(--ui-text-tertiary)',
                    children: `${pairing.window.seconds_left}s`
                  }, 'pair-count') : null,
                  jsx('button', {
                    className: 'rounded border border-(--ui-border) px-2 py-1 text-xs disabled:opacity-50',
                    type: 'button', disabled: busy,
                    // owner ruling 2026-11-10: the same button that opened the
                    // window closes it — no dead-end 'open (575s)' label.
                    onClick: () => (windowOpen ? closePairWindow() : openPairWindow()),
                    children: windowOpen ? 'close' : 'open window'
                  })
                ]
              })
            ]
          }),
          // owner ruling 2026-11-10 ~22:2x: the host row sits DIRECTLY under the
          // card title, above the code/approve row — label 'wear kit host', value
          // bare hostname (no scheme, no port: the app completes them, minimal-input
          // law), explanation as a hover tooltip. Derived, never narrated.
          tailnet.host ? jsxs('div', {
            className: 'flex items-center gap-2 text-[10px]',
            title: 'type this on the watch — Settings ▸ Gateways ▸ add gateway (the app fills in http:// and the port)',
            children: [
              jsx('span', {
                className: 'text-(--ui-text-tertiary)',
                children: 'wear kit host'
              }),
              jsx('code', {
                className: 'rounded bg-(--ui-bg-secondary) px-1 py-0.5 select-all',
                children: tailnet.host
              }),
              jsx('button', {
                className: 'rounded border border-(--ui-border) px-1.5 py-0.5 disabled:opacity-50',
                type: 'button', disabled: busy,
                onClick: () => {
                  navigator.clipboard.writeText(tailnet.host)
                    .then(() => $pairNote.set('host copied'))
                    .catch(() => $pairNote.set('copy failed — select the host'))
                },
                children: 'copy'
              })
            ]
          }, 'tailnet-address') : jsx('div', {
            className: 'text-[10px] text-(--ui-text-tertiary)',
            children: tailnet.reason === 'tailscale-missing'
              ? 'tailscale not on this machine — the gateway address stays whatever URL you serve.'
              : tailnet.reason === 'not-logged-in'
                ? 'tailscale logged out on this machine — log in and this line fills itself.'
                : null
          }, 'tailnet-address'),
          pending.map(p => jsx('div', {
            className: 'text-xs text-(--ui-text-secondary)',
            // the code is a secret: the watch SCREEN is its only display (Nous #151546)
            children: `waiting: ${p.label} [${p.state}, ${p.seconds_left}s]`
          }, p.pair_id)),
          jsxs('div', {
            className: 'flex items-center gap-1',
            children: [
              jsx('input', {
                className: 'w-24 rounded border border-(--ui-border) bg-transparent px-1 py-1 text-xs',
                placeholder: '6-digit code', value: code, disabled: busy,
                onChange: e => $code.set(e.target.value)
              }),
              jsx('button', {
                className: 'rounded border border-(--ui-border) px-2 py-1 text-xs disabled:opacity-50',
                type: 'button', disabled: busy || lock.locked,
                onClick: claimCode,
                children: lock.locked ? 'locked…' : 'Approve'
              })
            ]
          }),
          note ? jsx('div', { className: 'text-[10px] text-(--ui-text-tertiary)', children: note }) : null,
          jsx('div', {
            className: 'text-[10px] text-(--ui-text-tertiary)',
            children: 'codes typed HERE or in a terminal (hermes watch pair <CODE>) — messaging platforms route you to your computer.'
          }),
          jsx('div', {
            className: 'flex gap-3 text-[10px]',
            children: [
              jsx('a', { className: 'underline', href: 'https://login.tailscale.com/admin/settings/keys', rel: 'noreferrer', target: '_blank', children: 'tailscale API key' }),
              jsx('a', { className: 'underline', href: 'https://login.tailscale.com/admin/settings/oauth', rel: 'noreferrer', target: '_blank', children: 'oauth client' }),
              jsx('a', { className: 'underline', href: 'https://huggingface.co/settings/tokens', rel: 'noreferrer', target: '_blank', children: 'HF token' })
            ]
          }, 'source-links'),
          // owner ruling 2026-11-10 ~16:55: the paired list lives INSIDE this
          // card — pair, then see them; one box for the whole pairing concern.
          devices.length ? jsxs('div', {
            className: 'flex flex-col gap-1 border-t border-(--ui-border) pt-1',
            children: [
              jsx('div', { className: 'text-[10px] font-medium text-(--ui-text-secondary)', children: 'Paired watches' }),
              ...devices.map(([name, dev]) => jsxs('div', {
                className: 'flex items-center justify-between gap-1 text-xs',
                children: [
                  jsxs('span', {
                    className: 'text-(--ui-text-secondary)',
                    // owner ruling: no agent name on the row — one watch is ONE
                    // pairing; which agents it may speak is the enable list's job.
                    children: [dev.label || name,
                               dev.node_id ? ' · tailnet node ✓' : ' · never connected']
                  }),
                  jsx('button', {
                    className: 'rounded border border-(--ui-border) px-1 py-0.5 text-[10px] disabled:opacity-50',
                    type: 'button', disabled: busy,
                    onClick: () => revoke(name, dev.label || name, dev.profile),
                    children: 'Remove'
                  })
                ]
              }, name))
            ]
          }, 'devices-card') : jsx('div', {
            className: 'text-xs text-(--ui-text-tertiary)',
            children: 'no watches paired yet — install the APK, tap Pair on the watch, approve the code above.'
          }, 'devices-empty')
        ]
      }, 'pair-card'),

      // ── enable checklist (ONE agent list — owner ruling 2026-11-10: enabling
      //    Wear Kit and granting the watch to use an agent are the same decision;
      //    one row per agent, both boxes, no duplicated list below) ─────────────
      jsx(Step, {
        done: enabledCount > 0, n: 2, title: 'Enable Wear Kit for your agents',
        children: jsxs('div', {
          className: 'flex flex-col gap-1',
          children: [
            jsx('div', {
              className: 'text-(--ui-text-tertiary)',
              children: profiles.length
                ? 'one box = the whole decision: enables the plugin (`hermes -p <agent> plugins enable wearos` — the verb you would type) AND lets paired watches use that agent. The dropdown overrides which voice that agent speaks; blank follows the Voice-out card below.'
                : error ? 'waiting on the backend — see the line above.' : 'no profiles on this gateway yet.'
            }),
            profiles.length ? jsxs('label', {
              className: 'flex cursor-pointer items-center gap-2 font-medium',
              children: [
                jsx('input', {
                  type: 'checkbox', checked: allEnabled, disabled: busy,
                  onChange: e => enableAll(e.target.checked)
                }),
                'Enable all'
              ]
            }, 'enable-all') : null,
            ...profiles.map(p => jsxs('div', {
              className: 'flex items-center gap-3',
              children: [
                jsxs('label', {
                  className: 'flex flex-1 cursor-pointer items-center gap-2',
                  children: [
                    jsx('input', {
                      type: 'checkbox', checked: !!p.enabled, disabled: busy,
                      onChange: e => toggle(p.name, e.target.checked)
                    }),
                    jsx('span', { children: p.name })
                  ]
                }, p.name),
                jsx('select', {
                  className: 'rounded border border-(--ui-border) bg-transparent px-1 py-0.5 text-[10px] text-(--ui-text-secondary) disabled:opacity-40',
                  disabled: busy || !p.enabled,
                  title: 'voice this agent speaks (blank = Voice-out default)',
                  value: agentVoice[p.name] || '',
                  onChange: e => setAgentVoice(p.name, e.target.value),
                  children: [
                    jsx('option', { value: '', children: 'voice-out default' }, 'global'),
                    ...cardList.map(c => jsx('option', { value: c.id, children: c.label }, c.id))
                  ]
                }, `voice-${p.name}`)
              ]
            }, `row-${p.name}`))
          ]
        })
      }, 'enable-card'),

      // ── agent key grants (D17) — merged INTO the enable list above (owner
      //    ruling 2026-11-10): one row per agent, enable + watch-may-use together.

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


      // ── voice-out (SPEC §19, slice 3) ───────────────────────────────────────
      // Gateway lane. WATCH_TTS_UI (top of file) parks the ON-WATCH surface: with
      // it false the 'watch' quant dropdown disappears and the copy stops naming
      // the wrist — the feature (player, karaoke, tiers, lane) stays implemented
      // and merged; flip the constant to true to restore every control AS-IS.
      air ? jsxs('div', {
        className: 'flex flex-col gap-1 rounded border border-(--ui-border) p-2',
        children: [
          jsxs('div', {
            className: 'flex items-center justify-between',
            children: [
              jsx('span', { className: 'font-medium', children: '🔊 Voice-out' }),
              // build stamp (owner debugging law 2026-11-11): the pane must
              // SAY which copy it is running — no more 'did my reopen take?'
              jsx('span', {
                className: 'text-[9px] text-(--ui-text-tertiary)',
                title: 'pane build — if this is older than the latest commit, quit and reopen',
                children: '· ' + (((voice || {}).revision) || 'build')
              }),
              air.installed && (air.model_verified || air.verified_pending)
                ? (air.engine || {}).phase === 'warm'
                  ? jsx('span', { className: 'text-xs text-(--ui-text-success, --ui-text-secondary)', children: '✓ model loaded — ready' })
                  : (air.engine || {}).phase === 'loading'
                    ? jsx('span', { className: 'text-xs text-(--ui-text-warning, --ui-text-secondary)', children: '⏳ loading model… (boot warm, ≈2 min once per gateway start)' })
                    : jsx('span', { className: 'text-xs text-(--ui-text-secondary)', children: '✓ installed · resident — model stays loaded (voice switching is free: zero-shot refs)' })
                : null
            ]
          }),
          jsx('div', {
            className: 'text-[10px] text-(--ui-text-tertiary)',
            children: WATCH_TTS_UI
              ? 'your agents speak on the watch — cloned voices, no cloud, audio only on your tailnet'
              : 'your agents speak with cloned voices — no cloud, audio only on your tailnet (Settings ▸ Voice ▸ WearKit Gateway)'
          }),

          // source dropdowns. PARKED (owner 2026-11-10, SPEC §19.4(l)): the 'watch'
          // quant selector is on-watch TTS surface — gateway-only focus hides it.
          ...['gateway', ...(WATCH_TTS_UI ? ['watch'] : [])].map(slot => jsxs('label', {
            className: 'flex flex-col gap-0.5 text-xs',
            children: [
              jsx('span', {
                className: 'text-[10px] text-(--ui-text-tertiary)',
                children: slot === 'gateway'
                  ? 'gateway model (the box runs this — best it can hold)'
                  : 'watch model (the wrist pulls this — Q4 fits)'
              }),
              jsx('select', {
                className: 'rounded border border-(--ui-border) bg-transparent px-1 py-1 text-xs',
                value: (air.slots || {})[slot], disabled: busy,
                onChange: voPick(slot),
                children: (air.sources || []).map(sRow => jsx('option', {
                  value: sRow.id,
                  children: `${sRow.id} · ${sRow.gated ? 'consent+token' : 'no account'}` +
                    (sRow.bytes ? ` · ${Math.round(sRow.bytes / 1e6)} MB` : '')
                }, sRow.id))
              })
            ]
          }, `vo-src-${slot}`)),

          // gated row chosen and no token yet: name the consent click, capture the key
          ((air.sources || []).find(sRow => (air.slots || {}).gateway === sRow.id) || {}).gated && !air.hf_token
            ? jsxs('div', {
                className: 'flex flex-col gap-1',
                children: [
                  jsx('a', {
                    className: 'text-[10px] underline text-(--ui-text-secondary)',
                    href: ((air.sources || []).find(sRow => (air.slots || {}).gateway === sRow.id) || {}).url || 'https://huggingface.co/neuphonic',
                    rel: 'noreferrer', target: '_blank',
                    children: '1. open the model page, click “Agree and access repository”'
                  }),
                  jsxs('div', {
                    className: 'flex items-center gap-1',
                    children: [
                      jsx('input', {
                        className: 'min-w-0 flex-1 rounded border border-(--ui-border) bg-transparent px-1 py-1 text-xs',
                        placeholder: '2. paste HF read token (stored 0600)', type: 'password',
                        value: voToken, disabled: busy,
                        onChange: e => $voToken.set(e.target.value)
                      }),
                      jsx('a', {
                        className: 'text-[10px] underline text-(--ui-text-tertiary)',
                        href: 'https://huggingface.co/settings/tokens', rel: 'noreferrer', target: '_blank',
                        children: 'keys'
                      })
                    ]
                  })
                ]
              }, 'vo-consent')
            : null,

          installing
            ? jsxs('div', {
                className: 'flex items-center gap-2 text-xs',
                children: [
                  jsx('span', { children: `${air.phase} ${air.percent || 0}% — ${air.detail || ''}` }),
                  jsx('button', {
                    className: 'rounded border border-(--ui-border) px-1 py-0.5 text-[10px]',
                    type: 'button',
                    onClick: () => voiceAct(() => rest('/voice', { method: 'POST', body: { action: 'cancel' } })),
                    children: 'Cancel'
                  })
                ]
              })
            : (!air.installed ? jsxs('div', {
                className: 'flex flex-col gap-1',
                children: [
                  jsx('button', {
                    className: 'self-start rounded border border-(--ui-border) px-2 py-1 text-xs disabled:opacity-50',
                    type: 'button', disabled: busy || !air.toolchain,
                    onClick: voInstall,
                    children: 'Review & install engine'
                  }),
                  !air.toolchain
                    ? jsx('div', { className: 'text-[10px] text-(--ui-text-danger)', children: 'needs git, cmake, g++ on this machine' })
                    : null
                ]
              }) : null),
          air.error ? jsx('div', { className: 'text-[10px] text-(--ui-text-danger)', children: air.error }) : null,

          // sound test: press play, hear the actual voice (the whole point of this card)
          jsxs('div', {
            className: 'flex items-center gap-1',
            children: [
              jsx('input', {
                className: 'min-w-0 flex-1 rounded border border-(--ui-border) bg-transparent px-1 py-1 text-xs',
                value: voText, disabled: busy,
                onChange: e => $voText.set(e.target.value)
              }),
              jsx('button', {
                className: 'rounded border border-(--ui-border) px-2 py-1 text-xs disabled:opacity-50',
                type: 'button', disabled: busy, onClick: voSoundTest, children: '▶ play'
              })
            ]
          }),
          voNote ? jsx('div', { className: 'text-[10px] text-(--ui-text-tertiary)', children: voNote }) : null,

          // 'Speak on' (SPEC 19.4m): CPU or any GPU nvidia-smi reveals — the
          // worker is fenced with CUDA_VISIBLE_DEVICES to the picked index,
          // so the card you don't pick can never be touched.
          (air.devices || []).length > 1 ? jsxs('label', {
            className: 'flex flex-col gap-0.5 text-xs',
            children: [
              jsx('span', {
                className: 'text-[10px] text-(--ui-text-tertiary)',
                children: 'speak on (GPU selection builds the CUDA engine once; ' +
                          'until it is done the engine stays honestly on CPU)'
              }),
              jsx('select', {
                className: 'rounded border border-(--ui-border) bg-transparent px-1 py-1 text-xs',
                value: (air.engine || {}).device || 'cpu', disabled: busy,
                onChange: voSetDevice,
                children: (air.devices || []).map(d => jsx('option', {
                  value: d.id,
                  children: d.label + (d.free_mb != null ? ` · ${Math.round(d.free_mb / 1024)} GB free` : '') +
                    (d.note ? ` · ${d.note}` : '') + (d.selected ? ' · chosen' : '')
                }, d.id))
              }),
              (air.engine || {}).build && (air.engine || {}).build.phase === 'building'
                ? jsx('div', { className: 'text-[10px] text-(--ui-text-secondary)', children: 'building: ' + ((air.engine || {}).build.detail || 'one CUDA build…') })
                : (air.engine || {}).build && (air.engine || {}).build.phase === 'failed'
                  ? jsx('div', { className: 'text-[10px] text-(--ui-text-danger)', children: 'CUDA build failed: ' + ((air.engine || {}).build.detail || '') })
                  : null
            ]
          }, 'vo-device') : null,

          // one global speed dial + karaoke toggle (canonical prefs, rev-bumped)
          voicing ? jsxs('div', {
            className: 'flex items-center gap-2 text-xs',
            children: [
              jsx('span', { className: 'text-[10px] text-(--ui-text-tertiary)', children: 'speed · at playback' }),
              jsx('input', {
                type: 'range', min: 0.5, max: 2, step: 0.05, value: shownSpeed,
                className: 'min-w-0 flex-1', onChange: voSpeed
              }),
              jsx('span', { children: `${Number(shownSpeed).toFixed(2)}×` }),
              jsxs('label', {
                className: 'flex cursor-pointer items-center gap-1',
                children: [
                  jsx('input', { type: 'checkbox', checked: !!voicing.karaoke, disabled: busy, onChange: voKaraoke }),
                  jsx('span', { className: 'text-[10px] text-(--ui-text-tertiary)', children: 'karaoke' })
                ]
              })
            ]
          }) : null,

          // voice cards (the founder's ships as the default; record whoever you want to hear)
          voicing && Object.keys(voicing.cards || {}).length
            ? jsxs('div', {
                className: 'flex flex-col gap-0.5',
                children: [
                  ...Object.entries(voicing.cards).map(([cid, m]) =>
                    $renaming.get() === cid ? jsxs('div', {
                      className: 'flex items-center gap-1',
                      children: [
                        jsx('input', {
                          className: 'min-w-0 flex-1 rounded border border-(--ui-border) bg-transparent px-1 py-0.5 text-xs',
                          type: 'text', defaultValue: _renameDraft, maxLength: 48,
                          // autoFocus: the box IS the click target's follow-up
                          autoFocus: true,
                          onInput: (e) => { _renameDraft = e.target.value },
                          onKeyDown: (e) => { if (e.key === 'Enter') voRename(cid); if (e.key === 'Escape') $renaming.set('') }
                        }),
                        jsx('button', {
                          className: 'rounded border border-(--ui-border) px-1 py-0.5 text-[10px] disabled:opacity-50',
                          type: 'button', disabled: busy, onClick: () => voRename(cid),
                          children: '✓'
                        }),
                        jsx('button', {
                          className: 'rounded border border-(--ui-border) px-1 py-0.5 text-[10px]',
                          type: 'button', onClick: () => $renaming.set(''),
                          children: '✕'
                        })
                      ]
                    }, cid) : jsxs('div', {
                      className: 'flex items-center justify-between gap-1 text-xs',
                      children: [
                        // DEFAULT PICKER (owner: "I need a checkbox to select default
                        // model — that went away"): same /voice action with the
                        // __default__ agent key = the Voice-out default every
                        // dropdown-less agent speaks with.
                        jsx('input', {
                          type: 'checkbox', disabled: busy,
                          style: { accentColor: '#4ade80' },
                          checked: _isDefault(voicing, cid),
                          title: 'Voice-out default (agents without their own pick speak this one)',
                          onClick: () => { $defPick.set(cid) },
                          onChange: () => setAgentVoice('__default__', cid)
                            .then(() => $defPick.set('')).catch(() => {})
                        }),
                        // The NAME IS THE RENAME HANDLE (owner: "click on a model or a
                        // voice sample name, rename it right there") — no separate
                        // pencil, one control per concern.
                        jsxs('span', {
                          className: 'min-w-0 flex-1 cursor-pointer truncate text-(--ui-text-secondary)',
                          title: 'click to rename (the id never changes)',
                          onClick: () => voRenameStart(cid, m.label || ''),
                          children: [m.label || cid.slice(0, 8),
                                     m.ref_rate ? ` · ${Math.round(m.ref_rate)} wpm` : '']
                        }),
                        _isDefault(voicing, cid) ? jsx('span', {
                          className: 'shrink-0 text-[10px]', style: { color: '#4ade80' },
                          children: '● default'
                        }) : null,
                        jsx('button', {
                          // A voice is irreplaceable audio — the ✕ asks first
                          // (owner 2026-11-11): one tap arms, the next deletes.
                          className: `rounded border px-1 py-0.5 text-[10px] disabled:opacity-50 ${
                            $confirmDel.get() === cid
                              ? 'border-(--ui-text-danger) text-(--ui-text-danger)'
                              : 'border-(--ui-border)'}`,
                          type: 'button', disabled: busy,
                          title: $confirmDel.get() === cid ? 'tap again to really delete'
                                                            : 'delete this voice',
                          onClick: () => $confirmDel.get() === cid ? voDelete(cid)
                                                                    : $confirmDel.set(cid),
                          children: $confirmDel.get() === cid ? 'sure?' : '✕'
                        })
                      ]
                    }, cid))
                ]
              })
            : null,
          // + ADD A VOICE (owner 2026-11-11): sits BELOW the list; clicking it
          // slides the enrollment row in ABOVE the button — name it, record,
          // listen, confirm. The take runs the exact watch pipeline.
          jsxs('div', {
            className: 'flex flex-col gap-0.5 pt-1',
            children: [
              $enrolling.get() ? jsxs('div', {
                className: 'flex items-center gap-1 text-xs',
                children: [
                  ($recPhase.get() === 'idle' || $recPhase.get() === 'rec')
                    ? jsx('input', {
                        className: 'min-w-0 flex-1 rounded border border-(--ui-border) bg-transparent px-1 py-0.5 text-xs',
                        type: 'text', maxLength: 48, placeholder: 'voice name',
                        defaultValue: _recName, autoFocus: true,
                        onInput: (e) => { _recName = e.target.value }
                      })
                    : jsxs('span', {
                        className: 'min-w-0 flex-1 truncate text-(--ui-text-secondary)',
                        children: [String(_recName || ''),
                                   ' · ', ($recSecs.get() || 0).toFixed(1), 's',
                                   $recPhase.get() === 'rec'
                                     ? ` · ${$recFrames.get()} samples`
                                     : '',
                                   _recBlob ? ` · ${Math.round(_recBlob.length / 1024)} KB` : '']
                      }),
                  $recPhase.get() === 'rec'
                    ? jsxs('span', {
                        className: 'shrink-0 tabular-nums text-(--ui-text-danger)',
                        children: [`${Math.floor($recSecs.get() || 0)}s`]
                      })
                    : null,
                  $recPhase.get() === 'idle'
                    ? jsx('button', {
                        className: 'shrink-0 rounded border border-(--ui-text-danger) px-1.5 py-0.5 text-[10px] text-(--ui-text-danger)',
                        type: 'button', disabled: busy, title: 'record 3–12 s from your mic',
                        onClick: voRecStart, children: '● record'
                      })
                    : null,
                  $recPhase.get() === 'rec'
                    ? jsx('button', {
                        className: 'shrink-0 rounded border border-(--ui-border) px-1.5 py-0.5 text-[10px]',
                        type: 'button', onClick: voRecStop, children: '■ stop'
                      })
                    : null,
                  $recPhase.get() === 'ready'
                    ? jsxs(Fragment, { children: [
                        jsx('button', {
                          className: 'shrink-0 rounded border border-(--ui-border) px-1.5 py-0.5 text-[10px]',
                          type: 'button', title: 'hear the raw take before it becomes a voice',
                          onClick: voRecPlay, children: '▶ play'
                        }),
                        jsx('button', {
                          className: 'shrink-0 rounded border border-(--ui-border) px-1.5 py-0.5 text-[10px]',
                          type: 'button', title: 'create the voice (Parakeet will transcribe the take)',
                          onClick: voRecConfirm, children: '✓ confirm'
                        }),
                        jsx('button', {
                          className: 'shrink-0 rounded border border-(--ui-border) px-1.5 py-0.5 text-[10px]',
                          type: 'button', title: 'throw this take away and record again',
                          onClick: voRecRetry, children: '↺ redo'
                        })
                      ] })
                    : null,
                  $recPhase.get() === 'send'
                    ? jsx('span', {
                        className: 'shrink-0 text-[10px] text-(--ui-text-tertiary)',
                        children: 'listening to your take…'
                      })
                    : null
                ]
              }) : null,
              jsx('button', {
                className: 'self-start rounded border border-(--ui-border) px-1.5 py-0.5 text-[10px] text-(--ui-text-secondary)',
                type: 'button',
                title: $enrolling.get() ? 'close the recorder'
                                        : 'enroll a new voice right here (system mic)',
                onClick: () => $enrolling.get() ? voRecClose() : $enrolling.set(true),
                children: $enrolling.get() ? '✕ close' : '＋ add a voice'
              })
            ]
          }),
          WATCH_TTS_UI ? jsx('div', {
            className: 'text-[10px] text-(--ui-text-tertiary)',
            children: 'record more voices from the watch — yours, or anyone’s (recorder ships with the player)'
          }) : null
        ]
      }, 'voiceout-card') : null,

      jsx('div', { className: 'flex flex-col gap-1', children: lights })
    ]
  })
}

export default {
  id: 'wearos',
  name: 'Wear Kit',
  register (ctx) {
    api = ctx
    ctx.register({
      id: 'wearos-pane',
      area: 'panes',
      title: 'wear kit',
      data: { placement: 'right', width: '320px' },
      render: () => jsx(WearKitPane, {})
    })
  }
}