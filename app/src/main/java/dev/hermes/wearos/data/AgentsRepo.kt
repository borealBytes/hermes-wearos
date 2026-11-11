package dev.hermes.wearos.data

import android.util.Log
import dev.hermes.wearos.domain.ProfileLogic
import dev.hermes.wearos.net.GatewaySessions
import dev.hermes.wearos.net.HermesApi
import dev.hermes.wearos.net.Watchkit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Agent list for the switcher (owner ask #1). Source: watchkit `GET /agents` (["default"] + every
 * gateway profile dir with a config.yaml). The last good list is cached in DataStore so the dialog
 * opens instantly offline; the first cold fetch failure falls back to ["wear", "default"] —
 * never an empty switcher.
 */
class AgentsRepo(private val settings: AppSettings) {
    companion object { private const val TAG = "HermesWear" }

    private val _agents = MutableStateFlow<List<String>>(emptyList())
    val agents: StateFlow<List<String>> = _agents.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Restore the cached list at app start (before any network). */
    suspend fun loadCache() {
        try {
            val raw = settings.agentsCache()
            if (raw.isNotEmpty()) {
                val list = GatewaySessions.parseAgents(raw)
                if (list.isNotEmpty()) _agents.value = list
            }
        } catch (e: Exception) { Log.w(TAG, "agents cache unreadable: ${e.message}") }
    }

    /** Best-effort refresh; on failure the cache/fallback stands (the list is NEVER cleared). */
    suspend fun refresh(): List<String> {
        _refreshing.value = true
        try {
            val list = Watchkit.fetchAgentList()
            if (list.isNotEmpty()) {
                _agents.value = list
                val root = JsonObject(mapOf<String, JsonElement>("agents" to JsonArray(list.map { JsonPrimitive(it) })))
                settings.setAgentsCache(root.toString())
                // ENABLE⇒LEARN (v0.10.1, owner's fresh-person pass): an agent enabled on the
                // Wear Kit pane must be talkative on the wrist WITHOUT the manual ⟳. /agents is
                // grant-filtered by the plugin, so a listed agent missing a key is one lane
                // session call away — and refreshAgentKeys is grant-law-correct, idempotent,
                // write-only-on-change, and quiet when the lane is unconfigured.
                if (list.any { settings.agentKeysSync()[it]?.isNotBlank() != true })
                    runCatching { settings.refreshAgentKeys() }
                        .onSuccess { n -> if (n > 0) Log.i(TAG, "auto-learn: $n agent key(s) learned") }
            }
        } catch (e: Exception) {
            Log.w(TAG, "agents refresh failed (cache/fallback shown): ${e.message}")
        } finally {
            _refreshing.value = false
        }
        return display(settings.profileSync())
    }

    /** What the dialog offers: fetched-or-fallback list, current agent always present. */
    fun display(current: String = ""): List<String> =
        ProfileLogic.displayAgents(_agents.value, current.ifEmpty { settings.profileSync() })

    /** True when even the cache is empty and /agents hasn't answered — the fallback is showing. */
    fun usingFallback(): Boolean = _agents.value.isEmpty()
}
