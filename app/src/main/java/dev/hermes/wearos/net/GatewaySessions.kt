package dev.hermes.wearos.net

import dev.hermes.wearos.net.HermesApi.Companion.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pure parsers for the two gateway/watchkit list surfaces the sessions + agent-switcher screens
 * consume. `GET {base}/api/sessions` (verified 0.21.7): `{object:"list", data:[{id,title,
 * last_active:<unix-seconds float>, message_count, ...}]}`. `GET {watchkit}/agents`:
 * `{agents:["default", ...]}`. No network here — HermesApi/Watchkit do the fetch, tests do the shape.
 */
object GatewaySessions {

    /** Owner: "don't cache too many, gets out of hand" — the app keeps the newest 30 rows, no transcripts. */
    const val MAX_ROWS = 30

    /** Filter-chip sentinel for "every profile" (owner: continue my computer chats + agent chats). */
    const val FILTER_ALL = ""

    /**
     * Filter-chip sentinel for the ARCHIVED list (owner batch 5: "archive instead of delete"):
     * archived rows ride their own fetch (the normal lists hide them, desktop-style), and this
     * NUL-prefixed sentinel selects that list in the chip row. No real profile name can collide.
     */
    const val FILTER_ARCHIVED = "\u0000archived"

    /**
     * Chip sentinel for the ⏰ CRON-RUNS list (pairing v2 / D14): every alert the plugin's outbox
     * delivers opens INTO its run session, so the runs themselves must be listable — fetched with
     * watchkit `?source=cron` exactly like 🗃 rides its own fetch. Same sentinel grammar.
     */
    const val FILTER_CRON = "\u0000cron"

    data class Info(
        val id: String,
        val title: String,
        /** Last activity in epoch millis (0 when the gateway didn't say). */
        val lastActiveMs: Long,
        val messages: Int,
        /** Gateway profile the row came from ("" when the source didn't say — pre-cross-profile caches). */
        val profile: String = "",
        /** True only on rows from the archived fetch — the 🗃 chip's exclusive content. */
        val archived: Boolean = false,
        /** The store's OWN row source ("chat"/"cron"/"api_server"/…); "" on pre-v2 payloads/caches. */
        val source: String = "",
    )

    /** Parse + newest-first sort; malformed JSON throws (caller decides cache-vs-error). */
    fun parse(text: String): List<Info> {
        val data = HermesApi.parse(text)["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val sec = (o["last_active"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0
            val msgs = (o["message_count"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            val arch = (o["archived"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
            Info(id, o.str("title") ?: "", (sec * 1000).toLong(), msgs, o.str("profile") ?: "", arch,
                o.str("source") ?: "")
        }.sortedByDescending { it.lastActiveMs }
    }

    /** Parse a per-profile list and tag every row with [profileTag] unless the payload already says. */
    fun parseProfiled(text: String, profileTag: String): List<Info> =
        parse(text).map { if (it.profile.isEmpty()) it.copy(profile = profileTag) else it }

    /**
     * Cross-profile merge for the sessions screen: dedupe by id (a compressed chat can surface in
     * both stores), preferring the profile-tagged / fresher row, then newest-first capped to [cap]
     * (≤ [MAX_ROWS]). Pure: sessions-screen merge tests live in GatewaySessionsTest.
     */
    fun merge(rows: List<Info>, cap: Int = MAX_ROWS): List<Info> =
        rows.groupBy { it.id }
            .map { (_, dup) -> dup.maxWith(compareBy({ it.profile.isNotEmpty() }, { it.lastActiveMs })) }
            .sortedByDescending { it.lastActiveMs }
            .take(cap.coerceIn(0, MAX_ROWS))

    /** Chip filter: [FILTER_ALL] passes everything; otherwise only that profile's rows. */
    fun filterProfile(rows: List<Info>, profile: String): List<Info> =
        if (profile == FILTER_ALL) rows else rows.filter { it.profile == profile }

    /** Profiles present in the merged list (chip row), in newest-seen order. */
    fun profilesIn(rows: List<Info>): List<String> =
        rows.map { it.profile }.filter { it.isNotEmpty() }.distinct()

    /** Cache serialization — the exact wire shape [parse] reads back (profile included). */
    fun encode(rows: List<Info>): String {
        val data = rows.joinToString(",") {
            "{\"id\":${jstr(it.id)},\"title\":${jstr(it.title)},\"last_active\":${it.lastActiveMs / 1000.0}," +
                "\"message_count\":${it.messages},\"profile\":${jstr(it.profile)},\"archived\":${if (it.archived) "true" else "false"}" +
                (if (it.source.isEmpty()) "" else ",\"source\":${jstr(it.source)}") + "}"
        }
        return "{\"object\":\"list\",\"data\":[$data]}"
    }

    private fun jstr(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t") + "\""

    /** Parse watchkit `GET /agents` → names, deduped, blank-free. Malformed JSON throws. */
    fun parseAgents(text: String): List<String> {
        val arr = HermesApi.parse(text)["agents"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
