// Expected answers stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.feature.onboarding

import com.vettid.core.data.vault.RecoveryOpenOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The recovery open's answers (ANDROID-PLAN 0.1.40's table), its warnings and the check step. */
class RecoveryOpenTextTest {
    private val at = Instant.parse("2026-10-17T12:00:00Z")

    @Test
    fun theAnswersOfTheTable() {
        assertEquals(OpenAnswerText(R.string.onboarding_open_wrong, listOf(9)), openAnswerText(RecoveryOpenOutcome("wrong", 1, null), false))
        assertEquals(OpenAnswerText(R.string.onboarding_open_wrong_wait, listOf(8), true), openAnswerText(RecoveryOpenOutcome("wrong", 2, at), false))
        assertEquals(R.string.onboarding_open_wrong_again, openAnswerText(RecoveryOpenOutcome("wrong_again", 2, null), false).res)
        assertEquals(OpenAnswerText(R.string.onboarding_open_attempt_lost, listOf(7)), openAnswerText(RecoveryOpenOutcome("attempt_lost", 3, null), false))
        // quiet and wait say how many attempts are left, never whether this request used one (the result does not tell).
        assertEquals(
            OpenAnswerText(R.string.onboarding_open_quiet, emptyList(), withRetryAt = true, attemptsLeft = null),
            openAnswerText(RecoveryOpenOutcome("quiet", null, at), false),
        )
        assertEquals(
            OpenAnswerText(R.string.onboarding_open_quiet, emptyList(), withRetryAt = true, attemptsLeft = 6),
            openAnswerText(RecoveryOpenOutcome("quiet", 4, at), false),
        )
        assertEquals(OpenAnswerText(R.string.onboarding_open_quiet_nodate, attemptsLeft = 6), openAnswerText(RecoveryOpenOutcome("quiet", 4, null), false))
        assertEquals(OpenAnswerText(R.string.onboarding_open_wait, withRetryAt = true, attemptsLeft = 8), openAnswerText(RecoveryOpenOutcome("wait", 2, at), false))
        assertEquals(OpenAnswerText(R.string.onboarding_open_wait_nodate, attemptsLeft = 8), openAnswerText(RecoveryOpenOutcome("wait", 2, null), false))
        assertEquals(R.string.onboarding_open_busy, openAnswerText(RecoveryOpenOutcome("busy"), false).res)
        assertEquals(R.string.onboarding_open_retry, openAnswerText(RecoveryOpenOutcome("retry"), false).res)
        assertEquals(R.string.onboarding_open_stored_mismatch, openAnswerText(RecoveryOpenOutcome("stored_mismatch"), false).res)
        assertEquals(R.string.onboarding_open_stored_mismatch_right, openAnswerText(RecoveryOpenOutcome("stored_mismatch", 3, null), false).res)
        assertEquals(R.string.onboarding_open_exhausted, openAnswerText(RecoveryOpenOutcome("exhausted", 10, null), false).res)
        assertEquals(R.string.onboarding_open_closed, openAnswerText(RecoveryOpenOutcome("closed"), false).res)
        assertEquals(R.string.onboarding_open_not_armed, openAnswerText(RecoveryOpenOutcome("not_armed"), false).res)
        assertEquals(R.string.onboarding_open_counter_unavailable, openAnswerText(RecoveryOpenOutcome("counter_unavailable"), false).res)
        assertEquals(OpenAnswerText(R.string.onboarding_open_ok, listOf(3)), openAnswerText(RecoveryOpenOutcome("ok", 3, null), false))
        assertEquals(R.string.onboarding_open_ok_plain, openAnswerText(RecoveryOpenOutcome("ok"), false).res)
        // The refusals before anything is counted have their own texts.
        assertEquals(R.string.onboarding_open_no_recovery, openAnswerText(RecoveryOpenOutcome("no_recovery"), false).res)
        assertEquals(R.string.onboarding_open_no_recovery_unanswered, openAnswerText(RecoveryOpenOutcome("no_recovery", afterUnanswered = true), false).res)
        assertEquals(R.string.onboarding_open_expired, openAnswerText(RecoveryOpenOutcome("expired"), false).res)
        assertEquals(R.string.onboarding_open_attestation, openAnswerText(RecoveryOpenOutcome("attestation"), false).res)
        assertEquals(R.string.onboarding_open_manifest, openAnswerText(RecoveryOpenOutcome("manifest"), false).res)
        assertEquals(R.string.onboarding_open_wrong_release, openAnswerText(RecoveryOpenOutcome("wrong_release"), false).res)
        assertEquals(R.string.onboarding_open_bad_request, openAnswerText(RecoveryOpenOutcome("bad_request"), false).res)
        // This phone's own unlock while it finishes an ok recovery.
        assertEquals(R.string.onboarding_open_finish_bad_pin, openAnswerText(RecoveryOpenOutcome("bad_pin", opened = true), false).res)
        assertEquals(R.string.onboarding_open_finish_failed, openAnswerText(RecoveryOpenOutcome("device_secret", opened = true), false).res)
        // A right entry that could not be completed, then answered wait.
        assertEquals(OpenAnswerText(R.string.onboarding_open_right_incomplete, listOf(7), true), openAnswerText(RecoveryOpenOutcome("wait", 3, at), true))
    }

    /** (0.34.0) Whether this request used an attempt comes only from attempt_used (ANDROID-PLAN 0.1.41's table). */
    @Test
    fun theAttemptTextsFollowAttemptUsed() {
        // quiet: nothing used (with the date), one used, unknown (neutral).
        assertEquals(
            OpenAnswerText(R.string.onboarding_open_quiet_nothing, withRetryAt = true),
            openAnswerText(RecoveryOpenOutcome("quiet", 3, at, attemptUsed = false), false),
        )
        assertEquals(OpenAnswerText(R.string.onboarding_open_quiet_used, listOf(6)), openAnswerText(RecoveryOpenOutcome("quiet", 4, at, attemptUsed = true), false))
        assertEquals(R.string.onboarding_open_quiet, openAnswerText(RecoveryOpenOutcome("quiet", 4, at), false).res)
        // wait: nothing used; one used is shown as attempt_lost.
        assertEquals(OpenAnswerText(R.string.onboarding_open_wait_nothing, withRetryAt = true), openAnswerText(RecoveryOpenOutcome("wait", 2, at, attemptUsed = false), false))
        assertEquals(OpenAnswerText(R.string.onboarding_open_attempt_lost, listOf(7)), openAnswerText(RecoveryOpenOutcome("wait", 3, at, attemptUsed = true), false))
        // retry after the spend.
        assertEquals(OpenAnswerText(R.string.onboarding_open_retry_used, listOf(5)), openAnswerText(RecoveryOpenOutcome("retry", 5, null, attemptUsed = true), false))
        assertEquals(R.string.onboarding_open_retry, openAnswerText(RecoveryOpenOutcome("retry", 5, null, attemptUsed = false), false).res)
        // stored_mismatch and vault_missing: before or after the spend, by attempt_used, not by attempts.
        assertEquals(R.string.onboarding_open_stored_mismatch, openAnswerText(RecoveryOpenOutcome("stored_mismatch", 3, null, attemptUsed = false), false).res)
        assertEquals(R.string.onboarding_open_stored_mismatch_right, openAnswerText(RecoveryOpenOutcome("vault_missing", 4, null, attemptUsed = true), false).res)
        // The resume of a lost ok that found no recovery here.
        assertEquals(R.string.onboarding_open_lost_not_here, openAnswerText(RecoveryOpenOutcome("no_bundle", opened = true), false).res)
    }

    @Test
    fun whichAnswersEndTheRecovery() {
        assertTrue(openCanTryAgain("wrong"))
        assertTrue(openCanTryAgain("busy"))
        assertTrue(openCanTryAgain("manifest"))
        assertTrue(openCanTryAgain("wrong_release"))
        listOf("exhausted", "closed", "not_armed", "stored_mismatch", "no_recovery", "expired", "attestation", "bad_request")
            .forEach { assertFalse(it, openCanTryAgain(it)) }
        assertTrue(openNeedsNewRecovery("no_recovery"))
        assertTrue(openNeedsNewRecovery("expired"))
        assertFalse(openNeedsNewRecovery("exhausted"))
    }

    /** The quiet text names only retry_at: the vault's quiet period is not known to the app, so no "last used" date. */
    @Test
    fun theQuietTextNamesOnlyTheEarliestRecovery() {
        val o = RecoveryOpenOutcome("quiet", 2, Instant.parse("2026-10-20T09:30:00Z"), attemptUsed = false)
        val args = openAnswerArgs(openAnswerText(o, false), o, java.time.ZoneOffset.UTC)
        assertEquals(1, args.size)
        assertTrue(args[0].toString(), args[0].toString().contains("20"))
    }

    @Test
    fun warningsBeforeTheSecondAttemptAndFromThreeLeft() {
        assertNull(RecoveryOpenUiState(used = null).warning)
        assertNull(RecoveryOpenUiState(used = 0).warning)
        assertEquals(OpenWarning.SECOND, RecoveryOpenUiState(used = 1).warning)
        assertNull(RecoveryOpenUiState(used = 6).warning)
        assertEquals(OpenWarning.FEW_LEFT, RecoveryOpenUiState(used = 7).warning)
    }

    @Test
    fun entriesStayOutOfToStringAndTheCountdownReads() {
        val s = RecoveryOpenUiState(pin = "135792", password = "correct horse battery staple")
        assertFalse(s.toString().contains("135792"))
        assertFalse(s.toString().contains("horse"))
        assertTrue(s.canContinue)
        assertEquals("1 d 01:01:01", countdown(Instant.ofEpochSecond(90_061), Instant.EPOCH))
        assertEquals("00:00:00", countdown(Instant.EPOCH, Instant.ofEpochSecond(5)))
    }
}
