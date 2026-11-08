// Hermes Wear Kit — Desktop page (handoff §4). SDK-surface only (rule 8): imports are
// @hermes/plugin-sdk + react/jsx-runtime, no eval/new Function, no dynamic import(), no
// prototype writes, no core-UI reach-ins. `hermes plugins validate` enforces this.
//
// The pane is a 4-step install checklist, then the read-only status half (paired devices +
// readiness lights). Three surface facts shaped it, all measured against the installed
// core (v0.23.0) — the first draft guessed wrong on #2 and that guess is why:
//   · No sibling assets. The loader evaluates plugin.js from a blob: URL (runtime-loader.ts:
//     "a relative path cannot resolve against the blob: base"), and nothing serves files next
//     to the published plugin.js — so <img src="./wearkit-hero.png"> would 404. The hero is an
//     inline SVG wordmark instead: zero assets, works everywhere.
//   · There is NO tool-call door for panes. `host` (src/sdk/index.ts:558) has no callTool,
//     and the gateway RPC table has no generic tools.call — plugin TOOLS only answer inside
//     an agent turn. The real door is per-plugin REST: `ctx.rest('/status')` →
//     GET /api/plugins/wearos/status (api/plugins.ts pluginRest), served by our
//     dashboard/plugin_api.py — and core's own hot-mount test proves that route answers on
//     the FIRST request after install/update: no app restart, ever.
//   · Panes get no plugin-config door, so /status mirrors apk_release_url (and the profile
//     inventory) in its answer; the URL below is only the config default's mirror, for the
//     first paint before the backend answers.
import { atom, host, haptic, useValue } from '@hermes/plugin-sdk'
import { jsx, jsxs } from 'react/jsx-runtime'
import { useEffect } from 'react'

const FALLBACK_RELEASES = 'https://github.com/borealBytes/hermes-wearos/releases/latest'
const REPO_URL = 'https://github.com/borealBytes/hermes-wearos'

const $status = atom(null)
const $busy = atom(false)
const $error = atom('')

// The ctx handed to register() owns the namespace-scoped REST door; capture it once.
let api = null
const rest = (path, opts) => {
  if (!api) return Promise.reject(new Error('wearos: register() has not run'))
  return api.rest(path, opts)
}

// /status answers with the readiness checklist, paired devices, the profile
// inventory (checkbox state) and the configured Releases URL — same JSON the CLI prints.
async function refresh() {
  try {
    $status.set(await rest('/status'))
    $error.set('')
  } catch (error) {
    // Keep the last view, but SAY what's wrong — a silent catch here once read
    // as "no profiles" forever. 404 ⇒ the gateway hasn't mounted the backend
    // half yet (older install pinned under this window): Rescan fixes it, no restart.
    $error.set(String((error && error.message) || error))
  }
}

// One toggle = one POST = the same line-level config.yaml patch the CLI runs.
async function toggle(agents, on) {
  haptic('tap')
  $busy.set(true)
  try {
    await rest('/enable', { method: 'POST', body: { agents, enable: on } })
    await refresh()
  } catch (error) {
    host.notify({ kind: 'error', message: `wearos: enable failed — ${String(error)}` })
  } finally {
    $busy.set(false)
  }
}

function showPin() {
  haptic('tap')
  rest('/pin')
    .then(result => {
      if (result && result.pin) {
        host.notify({ kind: 'info', message: `pair with PIN ${result.pin} (5 min): /pair ${result.pin}` })
      }
    })
    .catch(error => host.notify({ kind: 'error', message: `wearos: no PIN — ${String(error)}` }))
}

function Step({ done, n, title, children }) {
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

function WearKitPane() {
  const status = useValue($status)
  const busy = useValue($busy)
  const error = useValue($error)
  useEffect(() => {
    refresh()
    const timer = setInterval(refresh, 10_000) // pairing completes while you watch this pane
    return () => clearInterval(timer)
  }, [])

  const checks = (status && status.checks) || {}
  const devices = Object.keys((status && status.devices) || {})
  const profiles = (status && status.profiles) || []
  const releases = (status && status.apk_release_url) || FALLBACK_RELEASES
  const enabledCount = profiles.filter(p => p.enabled).length
  const allOn = profiles.length > 0 && enabledCount === profiles.length

  const lights = Object.entries(checks).map(([name, check]) =>
    jsx('div', {
      className: 'flex items-center justify-between gap-2 text-sm',
      children: jsxs('span', {
        className: check.ok ? 'text-(--ui-text-secondary)' : 'text-(--ui-text-danger)',
        children: [check.ok ? '● ' : '○ ', name, check.ok ? '' : ` — ${check.detail}`]
      })
    }, name)
  )

  const enableRows = [
    jsxs('label', {
      className: 'flex cursor-pointer items-center gap-2',
      children: [
        jsx('input', {
          type: 'checkbox',
          checked: allOn,
          disabled: busy || profiles.length === 0,
          onChange: e => toggle('all', e.target.checked)
        }),
        jsx('span', { className: 'font-medium', children: 'ENABLE ALL' })
      ]
    }, 'enable-all'),
    ...profiles.map(p =>
      jsxs('label', {
        className: 'flex cursor-pointer items-center gap-2 pl-5',
        children: [
          jsx('input', {
            type: 'checkbox',
            checked: !!p.enabled,
            disabled: busy,
            onChange: e => toggle([p.name], e.target.checked)
          }),
          jsx('span', { children: p.name })
        ]
      }, p.name)
    )
  ]

  return jsxs('div', {
    className: 'flex h-full flex-col gap-2 overflow-y-auto p-3 text-sm',
    children: [
      // Inline SVG wordmark — the hero without an asset the blob-evaluated pane could not load.
      jsx('svg', {
        viewBox: '0 0 240 24',
        height: 22,
        role: 'img',
        'aria-label': 'Hermes Wear Kit',
        className: 'text-(--ui-text-secondary)',
        children: jsx('text', {
          x: 1,
          y: 17,
          fill: 'currentColor',
          style: { fontSize: '13px', fontWeight: 600, letterSpacing: '0.26em' },
          children: 'HERMES WEAR KIT'
        })
      }),
      // The about block the plugin-card paragraph can't have: core renders description as
      // a single plain-text node (no whitespace-pre-line, no markdown) — so typography
      // lives HERE, where we own the JSX.
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
          jsx('div', { className: 'text-(--ui-text-tertiary)', children: '• PIN on the glass → say /pair → enrolled' }),
          jsx('div', { className: 'text-(--ui-text-tertiary)', children: '• every call rides the watch\u2019s own node — no relay, no phone app' }),
          jsx('div', { className: 'text-(--ui-text-tertiary)', children: '• enable below, per agent — same ledger as the card\u2019s toggle' }),
          jsx('div', {
            className: 'font-medium text-(--ui-text-secondary)',
            children: 'Open source forever: the Release APK is a courtesy — making it yours is the point.'
          })
        ]
      }),
      // The REST half speaks for itself — one honest line beats a mystery checklist.
      error
        ? jsx('div', {
            className: 'text-xs text-(--ui-text-danger)',
            children: `backend not answering: ${error} — hit Rescan on the Wear Kit card (no restart needed: the route hot-mounts).`
          })
        : null,
      jsx(Step, {
        done: devices.length > 0,
        n: 1,
        title: 'Install the APK',
        children: jsxs('span', {
          children: [
            'Get the Wear OS app from ',
            jsx('a', {
              className: 'underline',
              href: releases,
              rel: 'noreferrer',
              target: '_blank',
              children: 'GitHub Releases' // CI-built APK + SHA256SUMS + provenance
            }),
            ' and open it on the watch. Repo: ',
            jsx('a', { className: 'underline', href: REPO_URL, rel: 'noreferrer', target: '_blank', children: 'borealBytes/hermes-wearos' }),
            ' — CI-built Release, open source, MIT.'
          ]
        })
      }),
      jsx(Step, {
        done: enabledCount > 0,
        n: 2,
        title: 'Enable Wear Kit for your agents',
        children: jsxs('div', {
          className: 'flex flex-col gap-1',
          children: [
            jsx('div', {
              className: 'text-(--ui-text-tertiary)',
              children: profiles.length
                ? 'Enable all, then uncheck who should NOT talk to the watch. Same ledger as the card\u2019s one-at-a-time Agent toggle.'
                : error
                  ? 'waiting on the backend — see the line above.'
                  : 'no profiles on this gateway yet.'
            }),
            jsxs('div', { className: 'flex flex-col gap-1', children: enableRows })
          ]
        })
      }),
      jsx(Step, {
        n: 3,
        title: 'Open the app on the watch',
        children: 'It greets you with a big 6-digit PIN (5-minute window, single use).'
      }),
      jsx(Step, {
        done: devices.length > 0,
        n: 4,
        title: 'Say or type: /pair 123456',
        children: jsxs('span', {
          children: [
            'Swap in the PIN on the watch and say it to Hermes on ANY surface — or run ',
            jsx('code', { children: 'hermes watch pair' }),
            '. The watch gets its tunnel enrollment packet and is yours.'
          ]
        })
      }),
      // No watch PIN in hand? Mint one plugin-side (same 5-min rules).
      jsx('button', {
        className: 'self-start rounded border border-(--ui-border) px-2 py-1 text-xs',
        onClick: showPin,
        type: 'button',
        children: 'show PIN'
      }),
      devices.length
        ? jsxs('div', {
            className: 'text-(--ui-text-secondary)',
            children: ['paired watches: ', devices.join(', ')]
          })
        : jsx('div', {
            className: 'text-(--ui-text-tertiary)',
            children: 'no watches paired yet'
          }),
      jsx('div', { className: 'flex flex-col gap-1', children: lights })
    ]
  })
}

export default {
  id: 'wearos',
  name: 'Wear Kit',
  register(ctx) {
    api = ctx
    ctx.register({
      id: 'wearos-pane',
      area: 'panes',
      title: 'wear kit',
      data: { placement: 'right', width: '300px' },
      render: () => jsx(WearKitPane, {})
    })
  }
}
