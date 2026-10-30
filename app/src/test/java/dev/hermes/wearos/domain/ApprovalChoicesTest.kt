package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THE VERB SENT FOR ✅/❌ (batch-4 #5). The orb-flanking buttons and the in-list card share this
 * resolver — the batch was born from a mis-click fear, and the worst mis-click is the RIGHT
 * button sending the WRONG verb. Vendor choice vocabularies vary, hence: deny-detection is
 * case-insensitive substring ("deny", "No", "no thanks"), approve is whatever is not a deny,
 * and an empty/garbage list falls back to the protocol defaults instead of sending "".
 */
class ApprovalChoicesTest {

    @Test fun `standard approve-deny pair resolves both ways`() {
        val c = listOf("approve", "deny")
        assertEquals("approve", ApprovalChoices.approveName(c))
        assertEquals("deny", ApprovalChoices.denyName(c))
    }

    @Test fun `yes-no vocabulary maps correctly, case and phrasing aside`() {
        val c = listOf("Yes", "no")
        assertEquals("Yes", ApprovalChoices.approveName(c))
        assertEquals("no", ApprovalChoices.denyName(c))
        assertEquals("No", ApprovalChoices.denyName(listOf("approve", "No")))
    }

    @Test fun `junk or empty lists answer with the protocol defaults, never empty strings`() {
        assertEquals("approve", ApprovalChoices.approveName(emptyList()))
        assertEquals("deny", ApprovalChoices.denyName(emptyList()))
        // A list with no recognizable deny: the FIRST choice is the affirmative by convention.
        assertEquals("ship it", ApprovalChoices.approveName(listOf("ship it", "ship it")))
        assertEquals("deny", ApprovalChoices.denyName(listOf("ship it")))
    }
}
