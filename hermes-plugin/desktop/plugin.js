// Hermes Wear Kit — Desktop page (handoff §4). SDK-surface only (rule 8): imports are
// @hermes/plugin-sdk + react/jsx-runtime, no eval/new Function, no dynamic import(),
// no prototype writes, no core-UI reach-ins. `hermes plugins validate` enforces this.
import { host, haptic, useValue } from '@hermes/plugin-sdk'
import { jsx, jsxs } from 'react/jsx-runtime'

// The plugin's tool lane is the data source: watch_status answers with the same
// readiness checklist the CLI prints (checks + paired devices).
async function refresh() {
  try {
    return await host.callTool('watch_status', {})
  } catch {
    return null
  }
}

function WearKitPane() {
  const state = useValue(host.state)
  const status = useValue(refresh)

  const checks = (status && status.checks) || {}
  const lights = Object.entries(checks).map(([name, check]) =>
    jsx('div', {
      className: 'flex items-center justify-between gap-2 text-sm',
      children: jsxs('span', {
        className: check.ok ? 'text-(--ui-text-secondary)' : 'text-(--ui-text-danger)',
        children: [check.ok ? '● ' : '○ ', name, check.ok ? '' : ` — ${check.detail}`]
      })
    }, name)
  )

  const devices = Object.keys((status && status.devices) || {})

  return jsxs('div', {
    className: 'flex h-full flex-col gap-3 p-3 text-sm',
    children: [
      jsx('div', { className: 'font-medium', children: 'Wear Kit' }),
      jsx('div', {
        className: 'text-(--ui-text-tertiary)',
        children: devices.length
          ? `paired watches: ${devices.join(', ')}`
          : 'no watches paired yet'
      }),
      jsx('div', { className: 'flex flex-col gap-1', children: lights }),
      jsx('button', {
        type: 'button',
        className: 'self-start rounded border border-(--ui-border) px-2 py-1 text-xs',
        onClick: () => {
          haptic('tap')
          host.callTool('watch_pair', {}).then((result) => {
            if (result && result.pin) {
              host.notify({ kind: 'info', message: `pair with PIN ${result.pin} (5 min)` })
            }
          })
        },
        children: 'show PIN'
      }),
      jsx('div', {
        className: 'text-(--ui-text-tertiary) text-xs',
        children: 'APK: GitHub Releases (CI-built, SHA256SUMS + provenance) — hermes watch status'
      })
    ]
  })
}

export default {
  id: 'wearos',
  name: 'Wear Kit',
  register(ctx) {
    ctx.register({
      id: 'wearos-pane',
      area: 'panes',
      title: 'wear kit',
      data: { placement: 'right', width: '300px' },
      render: () => jsx(WearKitPane, {})
    })
  }
}
