package dev.hermes.wearos.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.hermes.wearos.WearApp
import java.time.Duration

/**
 * THE POCKET LIFELINE (#32). A cached Wear OS app loses background coroutines the moment
 * Samsung freezes it — wrist-verified 2026-11-01: the in-app away-watch loop stopped within a
 * minute of HOME once nothing kept the process warm, so desktop-session answers and approval
 * parks would silently miss the wrist in a real pocket. WorkManager is the platform answer:
 * the system wakes frozen/cached processes for scheduled work.
 *
 * Chained ONE-TIME work (not periodic — periodic can't go under 15 min): each sweep schedules
 * the next ~60 s out while the app stays backgrounded, giving ~1–2 min desktop-watch latency
 * instead of "whenever the app next opens". The in-app loop (20 s) takes over on resume and
 * cancels the chain; both share WearApp.Graph's SweepCursor, so handoff cannot double-buzz.
 *
 * Honest limits: while Doze seals the network (screen off, unplugged, still — the owner's
 * daily watch rarely qualifies after motion), CONNECTED-constrained work defers to the next
 * maintenance window; latency there is minutes, not seconds. Documented in SPEC §16.
 */
class AwayWatchWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Chain again FIRST: a throwing sweep must not end the lifeline.
        scheduleChain(applicationContext, STARTUP_GRACE)
        WearApp.Graph.awaySweep()
        return Result.success()
    }

    companion object {
        private const val UNIQUE = "hermes-away-watch"
        private val CHAIN_DELAY: Duration = Duration.ofSeconds(55)
        private val STARTUP_GRACE: Duration = Duration.ofSeconds(8)

        /** (Re)arm the chain. REPLACE keeps exactly one alive however many stop events fire. */
        fun schedule(context: Context) = scheduleChain(context, CHAIN_DELAY)

        private fun scheduleChain(context: Context, delay: Duration) {
            val req = OneTimeWorkRequestBuilder<AwayWatchWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInitialDelay(delay)
                .setInputData(workDataOf("why" to "desktop-watch-lifeline"))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, req)
        }

        /** Foreground again: the 20 s loop owns the sweep; stand the chain down. */
        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(UNIQUE) }
        }
    }
}
