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
import { useEffect } from 'react'

const FALLBACK_RELEASES = 'https://github.com/borealBytes/hermes-wearos/releases/latest'
const REPO_URL = 'https://github.com/borealBytes/hermes-wearos'

const $status = atom(null)
const $stt = atom(null)
const $busy = atom(false)
const $error = atom('')
const $code = atom('')
const $pairNote = atom('')

let api = null
const rest = (path, opts) => {
  if (!api) return Promise.reject(new Error('wearos: register() has not run'))
  return api.rest(path, opts)
}

async function refresh () {
  try {
    $status.set(await rest('/status'))
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

async function act (fn) {
  haptic('tap')
  $busy.set(true)
  try { await fn(); await refresh() } finally { $busy.set(false) }
}

// One checkbox = one POST = one fixed-argv `hermes -p <profile> plugins enable|disable`
async function toggle (profile, on) {
  await act(async () => {
    const out = await rest('/enable', { method: 'POST', body: { profile, enable: on } })
    if (out && out.ok === false) {
      host.notify({ kind: 'error', message: `wearos: ${out.detail || out.error}` })
    }
  })
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

async function setGrant (profile, allow) {
  await act(() => rest('/grants', { method: 'POST', body: { profile, allow } }))
}

async function setAlerts (profile, pref) {
  await act(() => rest('/alerts', { method: 'POST', body: { profile, pref } }))
}

async function sttInstall () {
  const p = ($stt.get() || {}).preview
  if (!p) return
  const yes = window.confirm('Install streaming speech-to-text (Parakeet)?\n' +
    p.steps.map(s => `• ${s}`).join('\n') +
    `\n• needs: ${p.needs.join(', ')} · disk ~${p.disk_mb} MB` +
    `\n• hosts: ${p.hosts.join(', ')}`)
  if (!yes) return
  await act(() => rest('/stt', { method: 'POST', body: { action: 'install' } }))
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
  const busy = useValue($busy)
  const error = useValue($error)
  const code = useValue($code)
  const note = useValue($pairNote)
  useEffect(() => {
    refresh()
    const timer = setInterval(refresh, 8_000) // pairing completes while you watch this pane
    return () => clearInterval(timer)
  }, [])

  const checks = (status && status.checks) || {}
  const devices = Object.entries((status && status.devices) || {})
  const profiles = (status && status.profiles) || []
  const grants = (status && status.grants) || {}
  const pairing = (status && status.pairing) || {}
  const windowOpen = !!pairing.window
  const pending = pairing.pending || []
  const lock = pairing.claim_lock || {}
  const releases = (status && status.apk_release_url) || FALLBACK_RELEASES
  const enabledCount = profiles.filter(p => p.enabled).length

  const lights = Object.entries(checks).map(([name, check]) =>
    jsx('div', {
      className: 'flex items-center justify-between gap-2 text-sm',
      children: jsxs('span', {
        className: check.ok ? 'text-(--ui-text-secondary)' : 'text-(--ui-text-danger)',
        children: [check.ok ? '● ' : '○ ', name, check.ok ? '' : ` — ${check.detail}`]
      })
    }, name))

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

      // ── pairing card ──────────────────────────────────────────────────────────
      jsxs('div', {
        className: 'flex flex-col gap-1 rounded border border-(--ui-border) p-2',
        children: [
          jsxs('div', {
            className: 'flex items-center justify-between',
            children: [
              jsx('span', { className: 'font-medium', children: 'Pair a watch' }),
              jsx('button', {
                className: 'rounded border border-(--ui-border) px-2 py-1 text-xs disabled:opacity-50',
                type: 'button', disabled: busy || windowOpen,
                onClick: openPairWindow,
                children: windowOpen ? `open (${pairing.window.seconds_left}s)` : 'open window'
              })
            ]
          }),
          pending.map(p => jsx('div', {
            className: 'text-xs text-(--ui-text-secondary)',
            children: `watch shows ${p.code} — ${p.label} [${p.state}]`
          }, p.code)),
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
          })
        ]
      }, 'pair-card'),

      // ── enable checklist ──────────────────────────────────────────────────────
      jsx(Step, {
        done: enabledCount > 0, n: 1, title: 'Enable Wear Kit for your agents',
        children: jsxs('div', {
          className: 'flex flex-col gap-1',
          children: [
            jsx('div', {
              className: 'text-(--ui-text-tertiary)',
              children: profiles.length
                ? 'each box runs `hermes -p <agent> plugins enable wearos` — the same verb you would type'
                : error ? 'waiting on the backend — see the line above.' : 'no profiles on this gateway yet.'
            }),
            ...profiles.map(p => jsxs('label', {
              className: 'flex cursor-pointer items-center gap-2',
              children: [
                jsx('input', {
                  type: 'checkbox', checked: !!p.enabled, disabled: busy,
                  onChange: e => toggle(p.name, e.target.checked)
                }),
                jsx('span', { children: p.name }),
                p.visible && !p.enabled
                  ? null
                  : null
              ]
            }, p.name))
          ]
        })
      }),

      // ── devices ───────────────────────────────────────────────────────────────
      devices.length
        ? jsxs('div', {
            className: 'flex flex-col gap-1 rounded border border-(--ui-border) p-2',
            children: [
              jsx('div', { className: 'font-medium', children: 'Paired watches' }),
              ...devices.map(([name, dev]) => jsxs('div', {
                className: 'flex items-center justify-between gap-1 text-xs',
                children: [
                  jsxs('span', {
                    className: 'text-(--ui-text-secondary)',
                    children: [dev.label || name, ' · ', dev.profile,
                               dev.node_id ? ' · node ✓' : ' · never connected']
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
          }, 'devices-card')
        : jsx(Step, {
            n: 2, title: 'Install the APK, then pair',
            children: jsxs('span', {
              children: [
                'Get the Wear OS app from ',
                jsx('a', { className: 'underline', href: releases, rel: 'noreferrer', target: '_blank', children: 'GitHub Releases' }),
                ' (CI-built APK + SHA256SUMS), open it, tap Pair.'
              ]
            })
          }, 'devices-empty'),

      // ── agent key grants (D17) ────────────────────────────────────────────────
      jsxs('div', {
        className: 'flex flex-col gap-1 rounded border border-(--ui-border) p-2',
        children: [
          jsx('div', { className: 'font-medium', children: 'Agents the watch may use' }),
          jsx('div', {
            className: 'text-[10px] text-(--ui-text-tertiary)',
            children: 'a granted agent\u2019s API key is delivered to enrolled watches over the private tailnet lane, read in that agent\u2019s own scope'
          }),
          ...profiles.filter(p => p.name !== 'default').map(p => jsxs('label', {
            className: 'flex cursor-pointer items-center gap-2 text-xs',
            children: [
              jsx('input', {
                type: 'checkbox', checked: !!grants[p.name], disabled: busy,
                onChange: e => setGrant(p.name, e.target.checked)
              }),
              jsx('span', { children: p.name })
            ]
          }, `grant-${p.name}`))
        ]
      }, 'grants-card'),

      // ── voice add-ons (D16) ───────────────────────────────────────────────────
      stt && !stt.installed
        ? jsxs('div', {
            className: 'flex flex-col gap-1 rounded border border-(--ui-border) p-2',
            children: [
              jsx('div', { className: 'font-medium', children: 'Streaming speech-to-text (Parakeet)' }),
              stt.toolchain
                ? null
                : jsx('div', { className: 'text-[10px] text-(--ui-text-danger)', children: 'needs git, cmake, g++ on this machine' }),
              jsx('div', {
                className: 'text-[10px] text-(--ui-text-tertiary)',
                children: `built from a pinned commit, model verified by SHA-256, installed into ${stt.dir} — ~731 MB download + a few-minute CPU build`
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
                : jsx('button', {
                    className: 'self-start rounded border border-(--ui-border) px-2 py-1 text-xs disabled:opacity-50',
                    type: 'button', disabled: busy || !stt.toolchain,
                    onClick: sttInstall,
                    children: 'Review & install'
                  }),
              stt.error ? jsx('div', { className: 'text-[10px] text-(--ui-text-danger)', children: stt.error }) : null
            ]
          }, 'stt-card')
        : (stt && stt.installed && stt.model_verified)
            ? jsxs('div', {
                className: 'flex flex-col rounded border border-(--ui-border) p-2 text-xs',
                children: [
                  jsx('div', { className: 'text-(--ui-text-secondary)', children: '✓ Streaming speech-to-text installed & verified' }),
                  jsx('div', { className: 'text-[10px] text-(--ui-text-tertiary)', children: 'watchkit serves it after its next health probe' })
                ]
              }, 'stt-card')
            : null,

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
