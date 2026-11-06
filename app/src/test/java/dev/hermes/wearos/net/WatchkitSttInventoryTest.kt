package dev.hermes.wearos.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BATCH-4 #16: /health `stt.engines[]` → picker rows. The watch must list exactly what the
 * gateway box has (owner: *"select … installed models"*) — never invent, never blank on junk.
 */
class WatchkitSttInventoryTest {

    private fun obj(text: String) = Json.parseToJsonElement(text).let { it as JsonObject }

    @Test fun `real health shape becomes honest rows`() {
        val stt = obj(
            """{"engine":"parakeet","ready":true,"missing":null,"engines":[
               {"engine":"whisper","ready":true,"streaming":false,"missing":""},
               {"engine":"parakeet","ready":true,"streaming":true,"missing":""}]}"""
        )
        val rows = Watchkit.parseSttEngines(stt)
        assertEquals(2, rows.size)
        assertEquals("whisper", rows[0].id); assertEquals(false, rows[0].streaming); assertEquals(true, rows[0].ready)
        assertEquals("parakeet", rows[1].id); assertEquals(true, rows[1].streaming)
    }

    @Test fun `not-installed engines keep their flag and reason`() {        val stt = obj(
            """{"engines":[{"engine":"k2","ready":false,"streaming":true,"missing":"model missing"}]}"""
        )
        val rows = Watchkit.parseSttEngines(stt)
        assertEquals(1, rows.size)
        assertEquals(false, rows[0].ready)
        assertEquals("model missing", rows[0].missing)
    }

    @Test fun `missing junk and old servers parse to empty, never a crash`() {
        assertEquals(emptyList<Any>(), Watchkit.parseSttEngines(null))
        assertEquals(emptyList<Any>(), Watchkit.parseSttEngines(obj("{}")))
        assertEquals(emptyList<Any>(), Watchkit.parseSttEngines(obj("""{"engines":"whisper"}""")))
        assertEquals(
            emptyList<Any>(),
            Watchkit.parseSttEngines(obj("""{"engines":[{"ready":true}]}""")), // no id → dropped
        )
    }

    @Test fun `vosk model field parses size and pre-cache state`() {
        val cached = Watchkit.parseVoskModel(
            obj("""{"engine":"parakeet","vosk_model":{"ready":true,"size":41205931}}"""),
        )
        assertEquals(true, cached?.ready)
        assertEquals(41_205_931L, cached?.sizeBytes)
        val pending = Watchkit.parseVoskModel(obj("""{"vosk_model":{"ready":false,"size":null}}"""))
        assertEquals(false, pending?.ready)
        assertEquals(0L, pending?.sizeBytes)
        // Old watchkit without the field must read as "unknown", not "absent".
        assertEquals(null, Watchkit.parseVoskModel(obj("""{"engine":"whisper"}""")))
        assertEquals(null, Watchkit.parseVoskModel(null))
    }
}
