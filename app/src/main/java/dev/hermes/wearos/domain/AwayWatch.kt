package dev.hermes.wearos.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * DESKTOP WATCH (batch-6 #25, owner 2026-10-30: *"I'm not getting notifications or approvals
 * from sessions started in the desktop, only stuff I start on the watch… the only time I don't
 * get a notification on watch should be if I have that actual session open on the chat screen
 * that's actively the top thing on my watch"*).
 *
 * A desktop session has no watch-side stream, so nothing pushes to the watch — it POLLS the
 * watchkit inbox (net/Watchkit.approvalsWaiting: approval parks read from the gateway's
 * `last_activity_description` heartbeat + recently-active sessions read from the state stores)
 * and this file turns two consecutive sweeps into buzzes:
 *
 *  • **Approval** — a session whose beacon says "waiting for user approval" and the watch has
 *    not announced yet (dedup by session+tool; an answered park's beacon goes stale and is
 *    forgotten, so a NEW park buzzes again).
 *  • **Finished** — a session whose `message_count` GREW since the last look and whose newest
 *    message is an ASSISTANT one (the answer landed on the desktop). Count bumps whose tail is
 *    user/tool stay quiet: the owner wants "an answer exists", not "someone typed".
 *
 * Silence rules: [suppressed] chats never buzz — the caller passes the chat top-of-screen on
 * the chat page (the owner's one allowed silence) plus chats whose runs the watch itself drives
 * (their approvals and settles already notify through the watch-run paths). The FIRST sweep
 * after launch only SEEDS cursors (a launch must not stampede the wrist with everything that
 * aged while the watch was asleep). All cursor state is caller-owned maps: pure, unit-tested.
 */
object AwayWatch {

    /** Poll cadence: well under "stepped away" patience, gentle on the tailnet. */
    const val POLL_MS = 20_000L

    /**
     * The chats that must NEVER buzz the wrist, as GATEWAY SESSION KEYS. Key-space discipline
     * (bug caught by the #31 review): watchkit keys its parks/pulses by the Hermes `session_id`
     * (`wear-watch-mvwebl2s`), but the local registry id is a shortId (`b95e49ad`) — the batch-6
     * caller passed local ids and the owner's ONE allowed silence (chat live on the chat screen)
     * NEVER matched: the viewed session buzzed while being read on the wrist. Suppression must
     * therefore be built from [ChatMeta.sessionTitle] (the submitted session_id). Two silences:
     * the viewing chat (via [AttentionPolicy] — the SAME brain as voice/inline-notifications, so
     * the channels cannot drift) and chats whose runs the watch itself drives (their approvals
     * and settles already notify through the watch-run paths).
     */
    fun watchSilence(chats: List<dev.hermes.wearos.data.ChatMeta>, foreground: Boolean, viewingChatId: String): Set<String> =
        chats.mapNotNull { c ->
            when {
                c.lastRunId.isNotEmpty() -> c.sessionTitle
                AttentionPolicy.shouldSpeak(foreground, viewingChatId, c.id) -> c.sessionTitle
                else -> null
            }
        }.toSet()


    data class Parked(val profile: String, val chatId: String, val title: String, val tool: String, val arguments: String)

    data class Pulse(
        val profile: String, val chatId: String, val title: String,
        val messageCount: Int, val lastRole: String,
    )

    data class Inbox(val parked: List<Parked>, val pulses: List<Pulse>)

    enum class Kind { Approval, Finished }

    data class Alert(
        val kind: Kind, val profile: String, val chatId: String, val title: String,
        val tool: String = "", val arguments: String = "", val messageCount: Int = 0,
    )

    /** Garbage-tolerant: a field missing is one row skipped, a body unparseable is NO inbox. */
    fun parse(body: String?): Inbox {
        if (body.isNullOrBlank()) return Inbox(emptyList(), emptyList())
        return try {
            val root = Json.parseToJsonElement(body) as? JsonObject ?: return Inbox(emptyList(), emptyList())
            fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            fun num(o: JsonObject, k: String): Long = when (val v = o[k]) {
                is JsonPrimitive -> v.longOrNull ?: v.doubleOrNull?.toLong() ?: 0L
                else -> 0L
            }
            val parked = (root["waiting"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val chat = str(o, "chat_id")
                if (chat.isEmpty()) null else Parked(
                    profile = str(o, "profile"), chatId = chat, title = str(o, "title"),
                    tool = str(o, "tool"), arguments = str(o, "arguments"),
                )
            }
            val pulses = (root["pulses"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = str(o, "id")
                if (id.isEmpty()) null else Pulse(
                    profile = str(o, "profile"), chatId = id, title = str(o, "title"),
                    messageCount = num(o, "message_count").toInt(), lastRole = str(o, "last_role"),
                )
            }
            Inbox(parked, pulses)
        } catch (e: Exception) {
            Inbox(emptyList(), emptyList())
        }
    }

    /** Park dedup key: session + what is being asked. Same beacon across polls = one buzz;
     *  answered (beacon ages out) then a NEW park — even the same tool — buzzes again because
     *  the key left the seen set with the beacon. */
    fun parkKey(p: Parked): String = p.chatId + "|" + p.tool + "|" + p.arguments.take(80)

    /**
     * What to buzz about, given the last cursors. Mutates [seenParks]/[counts] to the new state
     * EXCEPT that the first sweep raises no alerts (the anti-stampede rule). [suppressed] chats
     * are invisible to BOTH families, and their cursors still ride along — leaving the chat
     * screen mid-answer must not retroactively buzz about what was already watched.
     */
    fun diff(
        inbox: Inbox,
        seenParks: MutableSet<String>,
        counts: MutableMap<String, Int>,
        suppressed: Set<String>,
        firstSweep: Boolean,
    ): List<Alert> {
        val out = ArrayList<Alert>()
        val liveKeys = inbox.parked.map(::parkKey).toSet()
        for (p in inbox.parked) {
            val key = parkKey(p)
            if (key in seenParks) continue
            seenParks += key
            if (firstSweep || p.chatId in suppressed) continue
            out += Alert(Kind.Approval, p.profile, p.chatId, p.title, p.tool, p.arguments)
        }
        // A park whose beacon went stale/left the inbox was answered (or expired) elsewhere.
        seenParks.retainAll(liveKeys)
        for (pu in inbox.pulses) {
            val prev = counts[pu.chatId]
            counts[pu.chatId] = pu.messageCount
            if (firstSweep || pu.chatId in suppressed) continue
            if (prev != null && pu.messageCount > prev && pu.lastRole == "assistant") {
                out += Alert(Kind.Finished, pu.profile, pu.chatId, pu.title, messageCount = pu.messageCount)
            }
        }
        return out
    }
}
