package dev.hermes.wearos.domain

import dev.hermes.wearos.net.HermesApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pure adapter for watchkit `GET /session_messages` (see server/watchkit) — the transcript
 * backfill that makes an ADOPTED gateway session open with its history instead of empty
 * (owner bug: computer sessions opened blank on adopt). The endpoint already drops tool rows
 * and inactive content; this layer owns the app-side truth: role mapping to [ChatMessage],
 * residual noise stripping, the newest-[cap] window, the caption line, and the two rules —
 * [shouldBackfill] (never clobber a locally-owned transcript) and [profileProbes] (whose
 * state.db to knock on when the adopted id's profile is unknown or wrong).
 * No android imports — unit-tested.
 */
object SessionHistory {

    /** Same cap the endpoint clamps to: ~120 newest turns on a watch transcript. */
    const val MAX_MESSAGES = 120

    /**
     * Belt-and-suspenders trim mirroring watchkit's `MSGS_CONTENT_MAX` (owner: "long replies are
     * getting cut off … we need the full replies" — the old 2 KB clamp was the whole bug). Chat
     * answers run to pages now; anything past a 20 KB single message is a log dump the watch
     * still shows in full, clipped only in the absurd case.
     */
    const val MAX_TEXT = 20_000

    /** Compression-summary rows render as one assistant line with this marker. */
    const val SUMMARY_PREFIX = "🗂 summary · "

    /**
     * Parse `{messages:[{role,content,ts}]}` → chat rows, oldest-first. Roles: "user"→User,
     * "assistant"/"summary"→Hermes (summaries prefixed), anything else dropped; blank text and
     * background-process noise dropped; over [MAX_TEXT] trimmed. When more than [cap] rows come
     * back the NEWEST [cap] are kept (a long desktop chat opens on its tail). Malformed JSON
     * throws — the caller fail-softs the whole backfill.
     */
    fun parse(text: String, cap: Int = MAX_MESSAGES): List<ChatMessage> {
        val arr = (HermesApi.parse(text)["messages"] as? JsonArray) ?: return emptyList()
        val rows = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val role = (o["role"] as? JsonPrimitive?)?.contentOrNull ?: return@mapNotNull null
            val raw = (o["content"] as? JsonPrimitive?)?.contentOrNull ?: return@mapNotNull null
            val body = raw.trim()
            if (body.isEmpty() || isNoise(body)) return@mapNotNull null
            val mapped = when (role) {
                "user" -> ChatMessage.Role.User
                "summary" -> ChatMessage.Role.Hermes
                "assistant" -> ChatMessage.Role.Hermes
                else -> return@mapNotNull null
            }
            val full = if (role == "summary") SUMMARY_PREFIX + body else body
            ChatMessage(0L, mapped, if (full.length > MAX_TEXT) full.take(MAX_TEXT) + "…" else full)
        }
        return rows.takeLast(cap.coerceAtLeast(0))
    }

    /**
     * GATEWAY transcript shape (SPEC §12.9): `GET {lane}/api/sessions/{id}/messages?limit=&order=latest`
     * → `{object:"list", data:[{role,content,display_kind,…}]}`, the NEWEST page in ascending order
     * (verified live). Same role mapping/noise rules as [parse]; the gateway route has no
     * summary column, so `_compressed_summary` rows arrive as ordinary users and are dropped by
     * the display_kind/blank filters exactly as watchkit drops them. tool/system/session_meta
     * rows are dropped here — the desktop transcript keeps them, the wrist shows the conversation.
     */
    fun parseGateway(text: String, cap: Int = MAX_MESSAGES): List<ChatMessage> {
        val arr = (HermesApi.parse(text)["data"] as? JsonArray) ?: return emptyList()
        val rows = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val role = (o["role"] as? JsonPrimitive?)?.contentOrNull ?: return@mapNotNull null
            val dk = (o["display_kind"] as? JsonPrimitive?)?.contentOrNull
            if (dk == "hidden" || dk == "process_complete" || dk == "internal_notification") return@mapNotNull null
            val raw = (o["content"] as? JsonPrimitive?)?.contentOrNull ?: return@mapNotNull null
            val body = raw.trim()
            if (body.isEmpty() || isNoise(body)) return@mapNotNull null
            val mapped = when (role) {
                "user" -> ChatMessage.Role.User
                "assistant" -> ChatMessage.Role.Hermes
                else -> return@mapNotNull null // tool/system/etc: the watch shows the conversation, not the machinery
            }
            ChatMessage(0L, mapped, if (body.length > MAX_TEXT) body.take(MAX_TEXT) + "…" else body)
        }
        return rows.takeLast(cap.coerceAtLeast(0))
    }

    /** Answer of watchkit `POST /session_promote` (see server/watchkit) — pure, unit-tested. */
    data class Promote(val ok: Boolean, val was: String, val changed: Boolean, val reason: String)

    /** Parse `{was, changed, reason?}`; anything unparseable = not ok (retry the plain path). */
    fun parsePromote(text: String): Promote = try {
        val o = HermesApi.parse(text)
        val was = (o["was"] as? JsonPrimitive?)?.contentOrNull ?: ""
        val changed = (o["changed"] as? JsonPrimitive?)?.contentOrNull == "true"
        val reason = (o["reason"] as? JsonPrimitive?)?.contentOrNull ?: ""
        Promote(was.isNotEmpty(), was, changed, reason)
    } catch (e: Exception) {
        Promote(false, "", false, "unparsable answer")
    }

    /** Residual noise the endpoint can still leak: injected process notifications, bare markers. */
    private fun isNoise(body: String): Boolean =
        body.startsWith("[IMPORTANT: Background process") ||
            body.startsWith("[SYSTEM: Background process") ||
            body == "—"

    /** Cheap-context caption at the top of a backfilled transcript. */
    fun captionLine(count: Int, profile: String): String =
        "history · $count messages · from ${ProfileLogic.chipLabel(profile)}"

    /**
     * BACKFILL RULE (owner): a chat only stands on its LOCAL transcript — backfill exactly when
     * the chat has no local rows (fresh adoption, an older adoption whose transcript was empty,
     * or a session created elsewhere: curl, another device). A ⌚ watch-created chat gains rows
     * with its first message, so its transcript is never empty and always survives untouched.
     */
    fun shouldBackfill(sessionId: String, localTranscriptSize: Int): Boolean =
        sessionId.isNotBlank() && localTranscriptSize == 0

    /**
     * Profiles to try for [sessionId], in order: [primary] first, then the computer home —
     * session rows live in their profile's state.db and a dev adoption (or a stale chip tag)
     * may not know the real one. Deduped; `default` last since it's the biggest store.
     */
    fun profileProbes(primary: String): List<String> =
        listOf(primary.trim().ifEmpty { ProfileLogic.PROFILE_COMPUTER }, ProfileLogic.PROFILE_COMPUTER).distinct()

    /**
     * CATCH-UP PLAN (owner: a session opened on the wrist showed a *half-cut* answer — the desktop
     * kept talking after the backfill snapshot): returns the gateway turns the local copy has
     * never seen, to be appended, or null when there is nothing to do. Alignment rides the
     * longest common PREFIX of (role, text) — local staleness is "the desktop appended", so the
     * fronts match and the tail is what's new. System rows (the watch's caption/fork notes) don't
     * participate. Refuses to guess: no anchor (nothing matches with both sides non-empty) or an
     * empty local transcript (that's [shouldBackfill]'s job — never double-seed). NEVER truncates,
     * reorders, or deletes a local row.
     */
    fun catchUpPlan(gateway: List<ChatMessage>, local: List<ChatMessage>): List<ChatMessage>? {
        if (gateway.isEmpty() || local.isEmpty()) return null
        val loc = local.filter { it.role != ChatMessage.Role.System }.map { it.role to it.text }
        if (loc.isEmpty()) return null
        var i = 0
        while (i < gateway.size && i < loc.size &&
            (gateway[i].role to gateway[i].text) == loc[i]
        ) i++
        if (i == 0) return null // unanchored: different windows — refuse rather than dupe
        if (i == gateway.size) return null // up to date (or the watch is simply ahead)
        return gateway.subList(i, gateway.size)
    }
}
