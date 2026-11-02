package dev.hermes.wearos.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.hermes.wearos.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Boot / replace lifeline (SPEC §11.4): the tunnel must be up BEFORE the first ask, not
 * after the first launch. BOOT_COMPLETED + MY_PACKAGE_REPLACED start [TailnetService]
 * when the feature is on and a key exists; both conditions are checked off the main
 * thread (DataStore read) inside a goAsync window.
 */
class TailnetBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = AppSettings(context)
                if (settings.tailnetEnabled() && settings.tailnetKey().isNotEmpty()) {
                    androidx.core.content.ContextCompat.startForegroundService(
                        context, Intent(context, TailnetService::class.java),
                    )
                }
            } catch (e: Exception) {
                Log.w("HermesWear", "tailnet boot start failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
