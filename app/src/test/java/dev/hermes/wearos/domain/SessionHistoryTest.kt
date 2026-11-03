package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transcript-backfill adapter (owner bug: adopted computer sessions opened EMPTY). Pinned
 * against the REAL watchkit `GET /session_messages` payload shape from the live state.db
 * (session 20261023_185354_f6d464, the SSH-troubleshoot chat, 2026-10-24).
 */
class SessionHistoryTest {

    private val sample = """
        {"messages":[
          {"role":"user","content":"my ssh connection to this computer from my laptop ain't working","ts":1792839369},
          {"role":"assistant","content":"sshd is alive and listening, and it *was* authenticating you","ts":1792839400},
          {"role":"summary","content":"[CONTEXT COMPACTION — REFERENCE ONLY] Earlier discussion…","ts":1792839450},
          {"role":"tool","content":"{\"output\": \"nope\"}","ts":1792839460},
          {"role":"user","content":"   ","ts":1792839470},
          {"role":"user","content":"[IMPORTANT: Background process proc_4529 completed]","ts":1792839480},
          {"role":"assistant","content":"tail check","ts":1792839500}
        ],"session_id":"20261023_185354_f6d464","profile":"default","count":7}
    """.trimIndent()

    @Test fun `maps endpoint rows to chat roles oldest-first`() {
        val msgs = SessionHistory.parse(sample)
        assertEquals(
            listOf(
                ChatMessage.Role.User, ChatMessage.Role.Hermes, ChatMessage.Role.Hermes, ChatMessage.Role.Hermes,
            ),
            msgs.map { it.role },
        )
        assertEquals("my ssh connection to this computer from my laptop ain't working", msgs[0].text)
    }

    @Test fun `summary rows surface as one marked assistant line`() {
        val summary = SessionHistory.parse(sample).filter { it.text.startsWith(SessionHistory.SUMMARY_PREFIX) }
        assertEquals(1, summary.size)
        assertEquals(ChatMessage.Role.Hermes, summary.single().role)
    }

    @Test fun `noise rows drop — tool roles blank text background-process notices unknown roles`() {
        val texts = SessionHistory.parse(sample).map { it.text }
        assertTrue(texts.none { it.startsWith("[IMPORTANT: Background process") })
        assertTrue(texts.none { it.isBlank() })
        assertTrue(texts.none { it.contains("\"output\"") }) // the tool row is gone
        assertEquals(4, texts.size)
    }

    @Test fun `cap keeps the NEWEST window of a long chat`() {
        val many = """{"messages":[${(1..200).joinToString(",") { """{"role":"user","content":"m$it","ts":$it}""" }}]}"""
        val msgs = SessionHistory.parse(many, cap = 3)
        assertEquals(listOf("m198", "m199", "m200"), msgs.map { it.text }) // oldest 197 dropped
        assertEquals(emptyList<ChatMessage>(), SessionHistory.parse(many, cap = 0))
    }

    @Test fun `oversized content trims to the watch cap with ellipsis`() {
        val big = """{"messages":[{"role":"assistant","content":"${"x".repeat(SessionHistory.MAX_TEXT + 500)}","ts":1}]}"""
        val msg = SessionHistory.parse(big).single()
        assertEquals(SessionHistory.MAX_TEXT + 1, msg.text.length) // cap chars + the … marker
        assertTrue(msg.text.endsWith("…"))
    }

    @Test fun `a long chat answer survives backfill whole — the 2 KB clamp bug (owner)`() {
        // The owner's complaint: pages-long replies came back ending in "…". Anything up to the
        // 20 KB wall must now arrive UNCLIPPED — 6 KB is a very real essay.
        val essay = "行".repeat(6_000) // CJK: worst case for byte-minded caps, fine for char caps
        val json = """{"messages":[{"role":"assistant","content":"$essay","ts":1}]}"""
        val msg = SessionHistory.parse(json).single()
        assertEquals(6_000, msg.text.length)
        assertFalse(msg.text.endsWith("…"))
    }

    @Test fun `empty and malformed payloads — missing key parses empty junk throws`() {
        assertEquals(emptyList<ChatMessage>(), SessionHistory.parse("""{"messages":[]}"""))
        assertEquals(emptyList<ChatMessage>(), SessionHistory.parse("""{"count":0}"""))
        assertEquals(emptyList<ChatMessage>(), SessionHistory.parse("""{"messages":[42,null]}"""))
        assertThrows(Exception::class.java) { SessionHistory.parse("<html>502</html>") }
    }

    @Test fun `messages are id-zero for the caller to stamp`() {
        // Seeding re-ids rows against the live chat's counter — parse must not invent ids.
        assertTrue(SessionHistory.parse(sample).all { it.id == 0L })
    }

    // ── the backfill rule + probe order (pure owners of the two behaviors) ────────

    @Test fun `backfill fires only where no local transcript stands`() {
        // fresh adoption (computer/desktop chat): zero local rows
        assertTrue(SessionHistory.shouldBackfill("20261023_185354_f6d464", localTranscriptSize = 0))
        // wear-profile session created via curl/another device — same: nothing local
        assertTrue(SessionHistory.shouldBackfill("wear-watch-curl99", localTranscriptSize = 0))
        // ⌚ watch-created chat WITH rows: the local transcript stands, no backfill — ever
        assertFalse(SessionHistory.shouldBackfill("wear-watch-mvma9on0", localTranscriptSize = 7))
        // blank id never backfills, whatever the transcript says
        assertFalse(SessionHistory.shouldBackfill("", localTranscriptSize = 0))
    }

    @Test fun `profileProbes tries the claimed profile then the computer home`() {
        assertEquals(listOf("wear", "default"), SessionHistory.profileProbes("wear"))
        assertEquals(listOf("default"), SessionHistory.profileProbes("default")) // no double-knock
        assertEquals(listOf("default"), SessionHistory.profileProbes(""))         // unknown → home first
        assertEquals(listOf("research", "default"), SessionHistory.profileProbes("  research "))
    }

    @Test fun `caption reads like the sessions chip — computer not default`() {
        assertEquals("history · 12 messages · from 💻 computer", SessionHistory.captionLine(12, "default"))
        assertEquals("history · 3 messages · from research", SessionHistory.captionLine(3, "research"))
    }

    // ── CATCH-UP PLAN (owner: half-cut desktop answer; "it should pretty much do it on its own") ──
    private fun user(t: String) = ChatMessage(0L, ChatMessage.Role.User, t)
    private fun hermes(t: String) = ChatMessage(0L, ChatMessage.Role.Hermes, t)
    private fun sys(t: String) = ChatMessage(0L, ChatMessage.Role.System, t)

    @Test fun `desktop appended — the plan is exactly the missing tail`() {
        val local = listOf(user("q"), hermes("a1"))
        val gateway = listOf(user("q"), hermes("a1"), user("again"), hermes("a2"))
        assertEquals(listOf(user("again"), hermes("a2")), SessionHistory.catchUpPlan(gateway, local))
    }

    @Test fun `up to date and watch-ahead both mean nothing to do`() {
        val same = listOf(user("q"), hermes("a"))
        assertNull(SessionHistory.catchUpPlan(same, same))
        assertNull(SessionHistory.catchUpPlan(same, same + hermes("watch kept talking")))
    }

    @Test fun `watch caption and fork notes never break alignment`() {
        val local = listOf(
            sys(SessionHistory.captionLine(2, "default")),
            user("q"),
            sys("— edited fork —"),
            hermes("a1"),
        )
        val gateway = listOf(user("q"), hermes("a1"), hermes("late desktop answer"))
        assertEquals(listOf(hermes("late desktop answer")), SessionHistory.catchUpPlan(gateway, local))
    }

    @Test fun `unanchored windows refuse and empty sides are backfills job not a double-seed`() {
        // Slid windows / different chats: no common front → refuse rather than duplicate a wall.
        assertNull(SessionHistory.catchUpPlan(listOf(user("x")), listOf(user("q"), hermes("a"))))
        assertNull(SessionHistory.catchUpPlan(emptyList(), listOf(user("q"))))
        assertNull(SessionHistory.catchUpPlan(listOf(user("q")), emptyList()))
    }

    // ── gateway messages route (SPEC §12.13, verified live 2026-11-02 against /p/default) ──

    private val gwSample = """
        {"object":"list","session_id":"local-abc","data":[
          {"id":1,"role":"user","content":"first q","timestamp":1},
          {"id":2,"role":"tool","content":"{\"output\": \"machinery\"}","tool_name":"terminal"},
          {"id":3,"role":"assistant","content":"first a"},
          {"id":4,"role":"user","content":"hidden q","display_kind":"hidden"},
          {"id":5,"role":"assistant","content":""},
          {"id":6,"role":"system","content":"noise"},
          {"id":7,"role":"assistant","content":"tail a"}
        ]}
    """.trimIndent()

    @Test fun `gateway shape drops tool system and hidden rows keeps conversation order`() {
        val msgs = SessionHistory.parseGateway(gwSample)
        assertEquals(
            listOf(ChatMessage.Role.User, ChatMessage.Role.Hermes, ChatMessage.Role.Hermes),
            msgs.map { it.role },
        )
        assertEquals(listOf("first q", "first a", "tail a"), msgs.map { it.text })
    }

    @Test fun `gateway shape windows to the newest cap like the watchkit path`() {
        val many = (1..20).joinToString(",") { """{"role":"user","content":"q$it"}""" }
        val msgs = SessionHistory.parseGateway("""{"data":[$many]}""", cap = 5)
        assertEquals(5, msgs.size)
        assertEquals("q16", msgs[0].text) // NEWEST 5 kept, ascending order preserved
    }

    @Test fun `malformed gateway json throws like the watchkit path and the caller fail-softs`() {
        // Same contract as parse(): a dead endpoint answer sinks THIS probe, not the app.
        assertThrows(Exception::class.java) { SessionHistory.parseGateway("not json") }
        assertEquals(emptyList<ChatMessage>(), SessionHistory.parseGateway("""{"data":"nope"}"""))
    }

    @Test fun `promote answers parse the lane answer of every flavor`() {
        val flipped = SessionHistory.parsePromote("""{"was":"local","changed":true}""")
        assertTrue(flipped.ok && flipped.changed && flipped.was == "local")
        val held = SessionHistory.parsePromote("""{"was":"api_server","changed":false}""")
        assertTrue(held.ok && !held.changed && held.reason.isEmpty())
        val machine = SessionHistory.parsePromote("""{"was":"cron","changed":false,"reason":"machine"}""")
        assertTrue(machine.ok && machine.reason == "machine")
        val junk = SessionHistory.parsePromote("<html>502</html>")
        assertFalse(junk.ok)
    }
}
