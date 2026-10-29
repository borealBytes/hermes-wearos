package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-chat approval modes (owner 2026-10-29): the persisted ids survive junk reads as [ApprovalMode.Inherit]
 * (today's behavior), and the one decision — auto-answer or not, given whether the human is
 * watching that chat — matches the four labels exactly.
 */
class ApprovalModeTest {

    @Test
    fun `ids round-trip and garbage inherits`() {
        for (m in ApprovalMode.entries) assertEquals(m, ApprovalMode.fromId(m.id))
        assertEquals(ApprovalMode.Inherit, ApprovalMode.fromId(""))
        assertEquals(ApprovalMode.Inherit, ApprovalMode.fromId(null))
        assertEquals(ApprovalMode.Inherit, ApprovalMode.fromId("yolo-ish"))
        // Case/whitespace tolerant — the picker and DataStore could hand back either.
        assertEquals(ApprovalMode.Smart, ApprovalMode.fromId(" SMART "))
    }

    @Test
    fun `off always auto-answers, manual never does`() {
        assertTrue(ApprovalMode.Off.autoApprove(viewing = true))
        assertTrue(ApprovalMode.Off.autoApprove(viewing = false))
        assertFalse(ApprovalMode.Manual.autoApprove(viewing = true))
        assertFalse(ApprovalMode.Manual.autoApprove(viewing = false))
    }

    @Test
    fun `inherit never takes over and smart answers only the absent`() {
        assertFalse(ApprovalMode.Inherit.autoApprove(viewing = true))
        assertFalse(ApprovalMode.Inherit.autoApprove(viewing = false))
        // Watching it = the human answers; away = the simple thing keeps moving.
        assertTrue(ApprovalMode.Smart.autoApprove(viewing = false))
        assertFalse(ApprovalMode.Smart.autoApprove(viewing = true))
    }
}
