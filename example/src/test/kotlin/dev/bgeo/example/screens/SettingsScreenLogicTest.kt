package dev.bgeo.example.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [decideNumberCommit] is the one bit of `SettingsScreen`'s Compose logic
 * pulled out into a plain function specifically so it can be unit-tested —
 * this module has no Compose instrumentation harness. It is the decision
 * that used to be missing entirely: a NUMBER field's committed draft text
 * that fails to parse must produce a [NumberCommitDecision.Reject], not be
 * silently swallowed.
 */
class SettingsScreenLogicTest {

    @Test
    fun `a well-formed integer text commits as an Int for an Int-kind field`() {
        val decision = decideNumberCommit("42", isIntKind = true)
        assertEquals(NumberCommitDecision.Accept(42), decision)
    }

    @Test
    fun `a well-formed decimal text commits as a Double for a Double-kind field`() {
        val decision = decideNumberCommit("12.5", isIntKind = false)
        assertEquals(NumberCommitDecision.Accept(12.5), decision)
    }

    // ---- the exact failure the review found: an HTTP-timeout-shaped field
    // (Int-kind) given "1e400" — parses fine as a Double, comes out
    // infinite, and must be rejected rather than silently ignored. ----

    @Test
    fun `1e400 on an Int-kind field is rejected, not silently dropped`() {
        val decision = decideNumberCommit("1e400", isIntKind = true)
        assertTrue(decision is NumberCommitDecision.Reject)
        assertTrue((decision as NumberCommitDecision.Reject).message.contains("1e400"))
    }

    @Test
    fun `1e400 on a Double-kind field is also rejected`() {
        val decision = decideNumberCommit("1e400", isIntKind = false)
        assertTrue(decision is NumberCommitDecision.Reject)
    }

    @Test
    fun `unparsable text is rejected`() {
        val decision = decideNumberCommit("not a number", isIntKind = false)
        assertTrue(decision is NumberCommitDecision.Reject)
    }

    @Test
    fun `a Double too large to round into an Int is rejected for an Int-kind field`() {
        val decision = decideNumberCommit("99999999999", isIntKind = true)
        assertTrue(decision is NumberCommitDecision.Reject)
    }

    @Test
    fun `blank text is rejected`() {
        val decision = decideNumberCommit("", isIntKind = true)
        assertTrue(decision is NumberCommitDecision.Reject)
    }
}
