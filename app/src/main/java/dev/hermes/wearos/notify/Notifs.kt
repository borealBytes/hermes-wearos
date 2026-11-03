package dev.hermes.wearos.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import dev.hermes.wearos.MainActivity
import dev.hermes.wearos.R
import dev.hermes.wearos.domain.ApprovalInfo

/**
 * Watch-native notifications (SPEC §5 U1, no FCM): we only notify for events that land while
 * the app is backgrounded (foreground settle/approval is rendered directly by the UI).
 *
 * - [CHANNEL_SETTLE] "hermes" (IMPORTANCE_DEFAULT → buzzes): a run finished while backgrounded.
 * - [CHANNEL_APPROVAL] "hermes_approval" (HIGH): the agent is waiting for an approval, with
 *   inline Approve/Deny actions backed by [ApprovalNotificationReceiver].
 *
 * Notification ids are derived (hashCode) from chat/run ids so a chat's notifications can be
 * cancelled precisely when that chat is opened or foregrounded.
 */
object Notifs {
    const val CHANNEL_SETTLE = "hermes"
    const val CHANNEL_APPROVAL = "hermes_approval"

    /** Action-broadcast extras (also the adb test surface — see ApprovalNotificationReceiver). */
    const val ACTION_APPROVE = "dev.hermes.wearos.APPROVE"
    const val ACTION_DENY = "dev.hermes.wearos.DENY"
    const val EXTRA_ACTION = "action"
    const val EXTRA_RUN = "run"
    const val EXTRA_OPEN_CHAT = "open_chat"
    /** Agent (profile) a deep-link chat belongs to — set for gateway-session ids, whose owning
     *  agent is NOT inferable from the local store (the chat may not be adopted yet). */
    const val EXTRA_OPEN_PROFILE = "open_profile"

    private const val TAG = "HermesWear"
    private const val SNIPPET = 120

    fun ensureChannels(app: Context) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SETTLE, "Hermes", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Finished answers"; enableVibration(true) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVAL, "Hermes approvals", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Agent waiting for approval"; enableVibration(true) }
        )
    }

    /** Background-settle notification: tap deep-links into the chat's transcript. */
    fun settle(app: Context, chatId: String, label: String, answer: String, error: Boolean, profile: String = "") {
        val text = if (error) "⚠ failed" else answer.replace('\n', ' ').trim().take(SNIPPET)
        val n = base(app, CHANNEL_SETTLE, titleFor(label), text)
            .setContentIntent(openChatIntent(app, chatId, profile))
            .addAction(R.drawable.ic_stat_hermes, "Open session", openChatIntent(app, chatId, profile))
            .build()
        notify(app, settleId(chatId), n)
    }

    /** Approval-waiting notification with inline Approve / Deny. */
    fun approval(app: Context, chatId: String, info: ApprovalInfo) {
        val b = base(app, CHANNEL_APPROVAL, "⚠ " + titleFor(labelOf(app, chatId)), info.question.replace('\n', ' ').take(SNIPPET))
            .setContentIntent(openChatIntent(app, chatId))
            .addAction(R.drawable.ic_stat_hermes, "Approve", responseIntent(app, ACTION_APPROVE, info.runId))
            .addAction(R.drawable.ic_stat_hermes, "Deny", responseIntent(app, ACTION_DENY, info.runId))
            .addAction(R.drawable.ic_stat_hermes, "Open session", openChatIntent(app, chatId))
        notify(app, approvalId(info.runId), b.build())
    }

    /**
     * BATCH-6 #25: an approval parked on a DESKTOP session (watchkit activity-beacon inbox).
     * The desk run's approval answers on the desktop's own lane — the wrist's honest action is
     * OPEN (adopt & read the session); watch-started runs keep inline Approve/Deny. Same
     * channel as a watch approval, so it arrives with the same attention buzz.
     */
    fun approvalRemote(app: Context, chatId: String, title: String, tool: String, arguments: String, profile: String = "") {
        val what = listOf(tool, arguments).filter { it.isNotBlank() }.joinToString(" ")
        val text = ("waiting for approval · " + what.ifBlank { "a tool call" })
            .replace('\n', ' ').take(SNIPPET)
        val b = base(app, CHANNEL_APPROVAL, "\u26a0 " + titleFor(title.ifBlank { chatId }), text)
            .setContentIntent(openChatIntent(app, chatId, profile))
            .addAction(R.drawable.ic_stat_hermes, "Open session", openChatIntent(app, chatId, profile))
        notify(app, approvalId(chatId), b.build())
    }

    fun cancelApproval(app: Context, runId: String) =
        NotificationManagerCompat.from(app).cancel(approvalId(runId))

    /** Cancel everything tied to a chat once it is opened/foregrounded. */
    fun cancelChat(app: Context, chatId: String, lastRunId: String = "") {
        val nm = NotificationManagerCompat.from(app)
        nm.cancel(settleId(chatId))
        if (lastRunId.isNotEmpty()) nm.cancel(approvalId(lastRunId))
    }

    fun canPost(app: Context): Boolean = NotificationManagerCompat.from(app).areNotificationsEnabled()

    private fun titleFor(label: String) = label.ifEmpty { "Hermes" }
    private fun labelOf(app: Context, chatId: String) =
        dev.hermes.wearos.WearApp.Graph.store.get(chatId)?.label ?: ""

    private fun settleId(chatId: String) = ("s" + chatId).hashCode()
    private fun approvalId(runId: String) = ("a" + runId).hashCode()

    private fun openChatIntent(app: Context, chatId: String, profile: String = "") = PendingIntent.getActivity(
        app, (chatId + "|" + profile).hashCode(),
        Intent(app, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_CHAT, chatId)
            .putExtra(EXTRA_OPEN_PROFILE, profile)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun responseIntent(app: Context, action: String, runId: String) = PendingIntent.getBroadcast(
        app, runId.hashCode() + action.hashCode(),
        Intent(action)
            .setClass(app, ApprovalNotificationReceiver::class.java)
            .putExtra(EXTRA_ACTION, action)
            .putExtra(EXTRA_RUN, runId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The orb spirit on a black disc (drawable-nodpi) — the expanded card shows the real orb.
     *  Decoded once, lazily: webp is small, but a bitmap per post on a watch is still silly. */
    private var largeIcon: android.graphics.Bitmap? = null
    private fun orbLargeIcon(app: Context): android.graphics.Bitmap? {
        largeIcon?.let { return it }
        return try {
            val d = androidx.core.content.ContextCompat.getDrawable(app, R.drawable.ic_notif_large)
                ?: return null
            d.toBitmap(288, 288).also { largeIcon = it }
        } catch (e: Exception) {
            Log.w(TAG, "notif large icon unavailable: $e"); null
        }
    }

    private fun base(app: Context, channel: String, title: String, text: String) =
        NotificationCompat.Builder(app, channel)
            .setSmallIcon(R.drawable.ic_stat_hermes) // orb-spirit silhouette (white stencil, system tints)
            .setLargeIcon(orbLargeIcon(app))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setGroup("hermes")

    private fun notify(app: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(app).notify(id, n)
            Log.i(TAG, "notif posted id=$id")
        } catch (e: SecurityException) {
            Log.w(TAG, "notifs denied: $e") // POST_NOTIFICATIONS revoked mid-flight
        }
    }
}
