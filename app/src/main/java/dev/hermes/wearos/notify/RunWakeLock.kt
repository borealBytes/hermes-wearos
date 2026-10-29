package dev.hermes.wearos.notify

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

/**
 * Background-run keeper (M5): a run that outlives the foreground needs its SSE stream / status
 * polling to survive screen-off. On Wear, doze suspends network for non-foreground apps; the
 * process holding a wakelock is classified ACTIVE by DeviceIdle, which keeps data flowing —
 * without it a backgrounded run just stalls in pollUntilTerminal's catch-loop (observed on the
 * emulator as 15 s connect timeouts on an 18 s cycle).
 *
 * Scope: acquired when we attach to a run, released on settle, auto-times-out at 10 min so a
 * crashed listener can never strand it. (A foreground service is the heavier evolution if
 * runs ever exceed a few minutes — documented tradeoff, SPEC §5 U1.) → the evolution is BUILT:
 * the same acquire/release pair now also starts/stops notify/RunKeeperService, because on this
 * watch the CPU lock and the wifi lock BOTH lost to the cached-process freeze (see its docs).
 *
 * THE CPU LOCK IS NOT ENOUGH (owner 2026-10-29 night: *"i told it wait thirty seconds, then send
 * a haiku and immediately went to the watchface while it was thinking. i did not get a
 * notification"*): the run settled fine while he was away, but only after the status poll ate a
 * `failed to connect … after 15000ms` round — the CPU awake does not mean the RADIO awake. With
 * the screen off, Wi-Fi enters power-save and both the SSE socket and the poll stall until the
 * wrist comes back. So the run now holds BOTH: PARTIAL_WAKE_LOCK (DeviceIdle-ACTIVE) and a
 * `WIFI_MODE_FULL_LOW_LATENCY` wifi lock — the exact pairing for "waiting on a server response
 * with the screen off". Same scope, same 10-min auto-expiry, released together on settle.
 */
object RunWakeLock {
    private const val TAG = "HermesWear"
    private const val TIMEOUT_MS = 10 * 60 * 1000L

    @Volatile private var lock: PowerManager.WakeLock? = null
    @Volatile private var wifiLock: WifiManager.WifiLock? = null

    @Synchronized
    fun acquire(context: Context, reason: String) {
        if (lock?.isHeld == true) return
        context_stop = context.applicationContext
        val pm = context.getSystemService(PowerManager::class.java)
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hermes:run").apply {
            setReferenceCounted(false)
            acquire(TIMEOUT_MS)
            Log.i(TAG, "wakelock acquired ($reason)")
        }
        // Wi-Fi power-save is a SEPARATE sleep from the CPU's (measured on the SM-L705U: a
        // backgrounded run with the CPU held still ate 15 s TCP timeouts to the LAN gateway).
        runCatching {
            val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            wifiLock = (
                if (android.os.Build.VERSION.SDK_INT >= 29)
                    wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "hermes:wifi")
                else @Suppress("DEPRECATION") wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hermes:wifi")
                ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "wifi lock refused: $it") }
        // …and the FREEZE is a third sleep (RunKeeperService has the full forensic story: the
        // cached process simply stopped receiving its own broadcasts). The service outranks it.
        runCatching {
            context.startForegroundService(Intent(context, RunKeeperService::class.java))
        }.onFailure { Log.w(TAG, "run keeper refused (FGS background start?): $it") }
    }

    @Synchronized
    fun release(reason: String) {
        lock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "wakelock released ($reason)")
            }
        }
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        wifiLock = null
        runCatching {
            context_stop?.let { ctx ->
                ctx.startService(Intent(ctx, RunKeeperService::class.java).setAction(RunKeeperService.ACTION_STOP))
            }
        }
    }

    // release() has no Context arg (call sites pass only a reason) — stash the acquire-side app ctx.
    @Volatile private var context_stop: Context? = null
}
