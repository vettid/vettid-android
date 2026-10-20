// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** The recovery entries' `ref`s as the vault writes them (VAULT-MESSAGING 0.49.1 §11.14.11; ANDROID-PLAN 0.1.52). */
class RecoveryRefsTest {
    private val t0 = Instant.parse("2026-10-19T12:00:00Z")

    @Test
    fun anAttemptsRefIsGenerationNumberOutcomeAndSource() {
        assertEquals(RecoveryRefs.Attempt(2, 3, success = false), RecoveryRefs.attempt("2/3/not_success/counter"))
        assertEquals(RecoveryRefs.Attempt(1, 10, success = true), RecoveryRefs.attempt("1/10/success/counter"))
        // The spec's old form, other sources, outcomes and numbers do not read.
        assertNull(RecoveryRefs.attempt("2/3"))
        assertNull(RecoveryRefs.attempt("2/3/not_success/enclave"))
        assertNull(RecoveryRefs.attempt("2/3/maybe/counter"))
        assertNull(RecoveryRefs.attempt("2/0/success/counter"))
        assertNull(RecoveryRefs.attempt("2/11/success/counter"))
        assertNull(RecoveryRefs.attempt("0/1/success/counter"))
        assertNull(RecoveryRefs.attempt("x/1/success/counter"))
        assertNull(RecoveryRefs.attempt(null))
    }

    @Test
    fun attemptsUsedIsGenerationAndCount() {
        assertEquals(4, RecoveryRefs.attemptsUsed("2/4"))
        assertNull(RecoveryRefs.attemptsUsed("2/0"))
        assertNull(RecoveryRefs.attemptsUsed("2/11"))
        assertNull(RecoveryRefs.attemptsUsed("4"))
        assertNull(RecoveryRefs.attemptsUsed("0/4"))
        assertNull(RecoveryRefs.attemptsUsed(null))
    }

    @Test
    fun aClosureIsGenerationReasonAndTheUnconfirmedSuffix() {
        assertEquals(RecoveryRefs.Closure(3, "pin", unconfirmed = false), RecoveryRefs.closure("3/pin"))
        assertEquals(RecoveryRefs.Closure(5, "stale", unconfirmed = false), RecoveryRefs.closure("5/stale"))
        assertEquals(RecoveryRefs.Closure(7, "delete", unconfirmed = true), RecoveryRefs.closure("7/delete/unconfirmed"))
        assertEquals(RecoveryRefs.Closure(7, "newer_reason", unconfirmed = false), RecoveryRefs.closure("7/newer_reason"))
        assertNull(RecoveryRefs.closure("7/delete/other"))
        assertNull(RecoveryRefs.closure("7/"))
        assertNull(RecoveryRefs.closure("pin"))
        assertNull(RecoveryRefs.closure("x/pin"))
        assertNull(RecoveryRefs.closure(null))
    }

    @Test
    fun anOpenIsRecoveryIdAndOutcome() {
        assertEquals(RecoveryRefs.Open("01JREC", "wrong"), RecoveryRefs.open("01JREC/wrong"))
        assertEquals(RecoveryRefs.Open("01JREC", "attempt_lost"), RecoveryRefs.open("01JREC/attempt_lost"))
        assertNull(RecoveryRefs.open("wrong"))
        assertNull(RecoveryRefs.open("/wrong"))
        assertNull(RecoveryRefs.open("01JREC/"))
        // VAULT-MESSAGING 0.50.0: the attempting phone's self-reported name, base64url without padding.
        assertEquals(RecoveryRefs.Open("01JREC", "wrong", "Pixel 7"), RecoveryRefs.open("01JREC/wrong/UGl4ZWwgNw"))
        // A name with a slash, controls or direction overrides: decoded, then cleaned.
        val tricky = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("a/b\u0007\u202Ec".toByteArray())
        assertEquals(RecoveryRefs.Open("01JREC", "wrong", "a/bc"), RecoveryRefs.open("01JREC/wrong/$tricky"))
        // Not base64url, or empty after cleaning: no name, the outcome still reads.
        assertEquals(RecoveryRefs.Open("01JREC", "wrong", null), RecoveryRefs.open("01JREC/wrong/!!!"))
        assertNull(RecoveryRefs.open("01JREC/wrong/UGl4ZWwgNw/x"))
        assertTrue(RecoveryRefs.rollback("recovery.generation_closed", "4/rollback"))
        assertFalse(RecoveryRefs.rollback("recovery.generation_closed", "4/stale"))
        assertFalse(RecoveryRefs.rollback("recovery.attempt", "4/rollback"))
        assertNull(RecoveryRefs.open(null))
    }

    private fun attempt(seq: Long, minutes: Long, n: Int, success: Boolean = false) =
        AuditRecord("a$seq", seq, t0.plusSeconds(minutes * 60), "recovery.attempt", ref = "2/$n/${if (success) "success" else "not_success"}/counter")

    private fun open(seq: Long, minutes: Long, outcome: String) =
        AuditRecord("o$seq", seq, t0.plusSeconds(minutes * 60), "recovery.open", ref = "01JREC/$outcome")

    @Test
    fun outcomesMatchTheNearestCountedOpenEachUsedOnce() {
        val entries = listOf(
            attempt(20, 0, 1),
            open(10, 0, "wrong"),
            // Not counted: never an attempt's outcome, however near.
            open(11, 0, "busy"),
            open(12, 1, "wrong_again"),
            attempt(21, 30, 2),
            open(13, 29, "attempt_lost"),
            // Attempt 3 is nearest to the open of attempt 2, which is taken: the next nearest within the window.
            attempt(22, 31, 3),
            open(14, 38, "quiet"),
            // A success has no outcome line; an attempt with no open within 10 minutes has none.
            attempt(23, 60, 4, success = true),
            attempt(24, 120, 5),
            open(15, 135, "wait"),
        )
        assertEquals(mapOf(20L to "wrong", 21L to "attempt_lost", 22L to "quiet"), RecoveryRefs.outcomes(entries))
    }

    /** An attempt the vault wrote again as `success` (same generation and number) takes no outcome and is "later successful". */
    @Test
    fun anAttemptWrittenAgainAsSuccessIsNotMatched() {
        val first = attempt(40, 0, 3)
        val again = attempt(41, 0, 3, success = true)
        val other = attempt(42, 20, 4)
        val entries = listOf(first, again, other, open(43, 0, "wrong"), open(44, 20, "attempt_lost"))
        assertEquals(mapOf(42L to "attempt_lost"), RecoveryRefs.outcomes(entries))
        assertTrue(RecoveryRefs.laterSuccessful(first, entries))
        assertFalse(RecoveryRefs.laterSuccessful(other, entries))
        assertFalse(RecoveryRefs.laterSuccessful(again, entries))
        assertFalse(RecoveryRefs.laterSuccessful(first, listOf(first)))
    }

    @Test
    fun outcomesAreFoundInEitherOrderOfTime() {
        // The counter's clock may run ahead of or behind the enclave's.
        val entries = listOf(attempt(30, 10, 1), open(31, 15, "closed"), attempt(32, 40, 2), open(33, 35, "exhausted"))
        assertEquals(mapOf(30L to "closed", 32L to "exhausted"), RecoveryRefs.outcomes(entries))
        assertEquals(emptyMap<Long, String>(), RecoveryRefs.outcomes(listOf(attempt(30, 10, 1))))
    }
}
