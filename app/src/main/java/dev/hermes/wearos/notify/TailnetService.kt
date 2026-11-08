package dev.hermes.wearos.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.hermes.wearos.MainActivity
import dev.hermes.wearos.R
import dev.hermes.wearos.data.AppSettings
import dev.hermes.wearos.domain.TailnetLogic
import dev.hermes.wearos.domain.TailnetPhase
import dev.hermes.wearos.net.TailnetEngine
import dev.hermes.wearos.net.TailnetRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * THE TUNNEL KEEPER (SPEC §11.4): a permanent foreground service owning the embedded
 * tailnet node. Why always-foreground is non-negotiable: Samsung's cached-process FREEZE
 * (RunKeeperService forensics, 2026-10-29) and the Doze network exemption that keeps the
 * WireGuard keepalive + SSE streams honest with the screen off. FGS type is **specialUse**
 * — NOT dataSync, whose 6h/day cap on API 34 would drop the tunnel nightly; the silent,
 * ongoing notification is the honest cost of "tailnet only, always".
 *
 * Lifecycle: START_STICKY + the boot receiver; reads the key/hostname/enabled prefs at
 * every start (Settings edits restart the service to pick them up). NO KEY IS NOT "OFF":
 * the engine starts anyway and idles at NEEDS_AUTH — the Pair screen is the answer, and
 * silent plain-socket operation was deleted with the doctrine ("tailnet only, always",
 * SPEC §11.4). Only `enabled=false` — a DEBUGGABLE-BUILD-ONLY state (AppSettings force-
 * returns true on release) — stops the service back to plain sockets, the dev escape hatch.
 *
 * Battery doctrine: a partial wake lock is held while the node lives — userspace
 * WireGuard timers must not sleep to a dead keepalive; the real-watch battery pass
 * (milestone 5) owns tuning the idle cadence, not this service skipping the lock.
 */
class TailnetService : Service() {

    companion object {
        const val CHANNEL = "hermes_tailnet"
        const val ACTION_STOP = "dev.hermes.wearos.TAILNET_STOP"
        private const val NOTIF_ID = 4243
        private const val TAG = "HermesWear"
    }

    private var scope: CoroutineScope? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hermes tailnet", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "The watch's always-on tailnet node" }
        )
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hermes:tailnet")
            .apply { setReferenceCounted(false); acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, build("starting the tailnet…"))
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = sc
        val settings = AppSettings(this)
        sc.launch {
            val enabled = settings.tailnetEnabled()
            val key = settings.tailnetKey()
            if (!enabled) {
                // Debuggable builds only (AppSettings force-ON): the `-e tailnet_on off` hatch.
                Log.i(TAG, "tailnet service: disabled (dev escape hatch) — plain sockets")
                TailnetEngine.stop()
                TailnetRoute.socksPort = 0
                shutdown()
                return@launch
            }
            // Keyless is NOT a reason to stop: the node starts, idles at NEEDS_AUTH, and the
            // notification below reads "tailnet needs a key" — the Pair screen is the door.
            if (key.isBlank()) Log.i(TAG, "tailnet service: no key yet — engine runs NEEDS_AUTH (Pair screen)")
            TailnetEngine.startOnce(
                this@TailnetService,
                hostname = settings.tailnetHostname(),
                key = key,
            )
            // Live status → the notification (phase transitions are the user-visible story).
            var lastText = ""
            TailnetEngine.status.collect { st ->
                val gatewayHost = runCatching { java.net.URI(settings.baseUrl()).host }.getOrDefault("")
                val phase = TailnetLogic.phase(st, enabled = true, gatewayHost = gatewayHost)
                val text = TailnetLogic.describe(phase, st, gatewayHost)
                if (text != lastText) {
                    lastText = text
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(text))
                    Log.i(TAG, "tailnet ${phase.name.lowercase()}: $text")
                }
            }
        }
        return START_STICKY
    }

    private fun shutdown() {
        scope?.cancel(); scope = null
        TailnetEngine.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope?.cancel(); scope = null
        TailnetEngine.stop()
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }

    private fun build(text: String): Notification {
        val open = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle("Hermes tailnet")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
