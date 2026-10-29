package dev.hermes.wearos.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.hermes.wearos.MainActivity
import dev.hermes.wearos.R

/**
 * THE RUN KEEPER (owner 2026-10-29 night, live-fire: *"wait thirty seconds, then send a haiku and
 * immediately went to the watchface… i did not get a notification"*). The forensics: after the
 * screen-off transition the app did not merely lose network — it stopped processing EVERYTHING
 * (a dev broadcast sat enqueued, and the process never picked it up: Samsung's cached-process
 * FREEZE). A wakelock keeps the CPU out of doze; a wifi lock keeps the radio honest; neither
 * unfreezes a cached process, and a frozen process cannot see its own run settle. The only
 * primitive that survives the freeze is a FOREGROUND SERVICE, exactly the "heavier evolution" the
 * RunWakeLock comment predicted — now built.
 *
 * Scope matches the run, to the call: RunWakeLock.acquire starts us, release stops us. The
 * notification is silent + ongoing (an answer in flight is worth a dot; it must not buzz twice),
 * and it deep-links home.
 */
class RunKeeperService : Service() {

    companion object {
        const val CHANNEL = "hermes_run"
        const val ACTION_STOP = "dev.hermes.wearos.KEEPER_STOP"
        private const val NOTIF_ID = 4242
        private const val TAG = "HermesWear"
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(
            android.app.NotificationChannel(CHANNEL, "Hermes run", android.app.NotificationManager.IMPORTANCE_LOW)
                .apply { description = "An answer is in flight" }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, build())
        Log.i(TAG, "run keeper in foreground")
        return START_NOT_STICKY
    }

    private fun build(): Notification {
        val open = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle("Hermes")
            .setContentText("answer in flight")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
