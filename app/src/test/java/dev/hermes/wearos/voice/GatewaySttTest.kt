package dev.hermes.wearos.voice

import dev.hermes.wearos.voice.GatewayStt.Companion.wsUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH-4 #9 pure halves (the socket/mic halves are hardware-proven): the JSONL protocol the
 * watchkit engine speaks, and the http→ws URL fold. The protocol must NEVER throw at junk —
 * a chatty future engine cannot crash the mic flow.
 */
class GatewaySttTest {

    @Test fun `engine protocol lines map to events, final wins, junk is ignored`() {
        val p = SttStreamProtocol::parse
        assertEquals(VoskEvent.Partial("hello"), p("""{"partial":"hello"}"""))
        assertEquals(VoskEvent.Final("hello world"), p("""{"final":"hello world"}"""))
        assertTrue(p("""{"error":"nemo missing"}""") is VoskEvent.Error)
        // final beats partial when an engine crams both into one line
        assertTrue(p("""{"final":"x","partial":"y"}""") is VoskEvent.Final)
        // junk / future fields / malformed → null, never an exception
        assertNull(p("not json"))
        assertNull(p("""{"engine":"whisper","ready":true}"""))
        assertNull(p(""))
        assertNull(p("""{"partial":""}"""))          // blank partials are noise
        assertTrue(p("""{"final":42}""") is VoskEvent.Final) // wrong type → never a crash
    }

    @Test fun `final with empty text is still a final (silence answer is an answer)`() {
        val ev = SttStreamProtocol.parse("""{"final":""}""")
        assertTrue(ev is VoskEvent.Final && (ev as VoskEvent.Final).text.isEmpty())
    }

    @Test fun `ws url folds the scheme and keeps the port`() {
        assertEquals("ws://192.168.10.62:8651/stt/stream", wsUrl("http://192.168.10.62:8651"))
        assertEquals("wss://box.ts.net:8651/stt/stream", wsUrl("https://box.ts.net:8651/"))
        assertEquals("ws://h:8651/stt/stream", wsUrl("  ws://h:8651  "))
    }

    @Test fun `engine pick rides as a query param blank stays clean`() {
        // #16: the wearer's Settings ▸ Voice in pick reaches watchkit as ?engine=…
        assertEquals(
            "ws://box:8651/stt/stream?engine=parakeet",
            wsUrl("http://box:8651", "parakeet"),
        )
        assertEquals("ws://box:8651/stt/stream", wsUrl("http://box:8651", ""))
        assertEquals("ws://box:8651/stt/stream", wsUrl("http://box:8651", null))
        assertEquals("ws://box:8651/stt/stream", wsUrl("http://box:8651", "   "))
        // odd ids (shouldn't happen — watchkit validates) must not break the URL
        assertTrue(wsUrl("http://box:8651", "a b").startsWith("ws://box:8651/stt/stream?engine="))
    }
}
