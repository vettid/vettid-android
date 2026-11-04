// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.vault

import com.vettid.core.data.devices.GrantIssueAnswers
import com.vettid.core.data.devices.GrantIssueResult
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The text a screen shows for [kind] (`messageRes`). */
internal fun shownText(kind: FailureKind): String = RuntimeEnvironment.getApplication().getString(kind.messageRes())

/** The owner-approved text (2026-11-04, VAULT-MESSAGING 0.83.11 section 11.14.10), word for word. */
internal const val MOVE_NOT_FINISHED_TEXT = "The move to this phone isn't finished. Use Finish the move on this phone, then try again."

/** The unlock flow's text, which these four requests must never show. */
internal const val UNLOCK_TRANSFER_TEXT = "The move to this phone did not finish. Approve it again on your old phone."

/** Asserts that [kind] is the move-not-finished kind and shows the approved text, never the unlock's. */
internal fun assertMoveNotFinished(kind: FailureKind) {
    assertEquals(FailureKind.MOVE_NOT_FINISHED, kind)
    assertEquals(MOVE_NOT_FINISHED_TEXT, shownText(kind))
    assertNotEquals(UNLOCK_TRANSFER_TEXT, shownText(kind))
}

/** VAULT-MESSAGING 0.83.11 section 11.14.10: `transfer_incomplete` to four requests before "Finish the move". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MoveNotFinishedTest {
    private suspend fun failureOf(type: String, code: String): VaultFailure = try {
        vaultGuard { throw VaultOpException(type, code) }
        fail("no failure")
        error("unreachable")
    } catch (f: VaultFailure) {
        f
    }

    @Test
    fun theApprovedTextIsWordForWord() {
        assertEquals(MOVE_NOT_FINISHED_TEXT, shownText(FailureKind.MOVE_NOT_FINISHED))
    }

    @Test
    fun eachOfTheFourRequestsGivesTheNewKindAndText() = runTest {
        assertEquals(
            setOf("connection.authenticate.approve", "leash.grant.issue", "device.pair.approve", "share.rule.set"),
            VaultCodes.MOVE_GATED,
        )
        for (type in VaultCodes.MOVE_GATED) {
            val f = failureOf(type, "transfer_incomplete")
            assertMoveNotFinished(f.kind)
            assertEquals(type, "transfer_incomplete", f.code)
        }
    }

    @Test
    fun aGrantIssueKeepsTheKindAndNeverShowsTheUnlockText() = runTest {
        val f = failureOf("leash.grant.issue", "transfer_incomplete")
        val r = GrantIssueAnswers.refused(f, resent = false)
        assertTrue(r is GrantIssueResult.Failed)
        assertMoveNotFinished((r as GrantIssueResult.Failed).failure.kind)
    }

    @Test
    fun otherCodesOfThoseRequestsAreUnchanged() = runTest {
        for (type in VaultCodes.MOVE_GATED) {
            assertEquals(FailureKind.CONFLICT, failureOf(type, "conflict").kind)
        }
    }

    @Test
    fun theUnlockAndEveryOtherRequestKeepTheOldKindAndText() = runTest {
        assertEquals(FailureKind.TRANSFER_INCOMPLETE, VaultManager.opFailure("transfer_incomplete"))
        for (type in listOf("credential.unlock", "device.pair.create", "share.rule.list", null)) {
            val kind = VaultManager.opFailure("transfer_incomplete", type)
            assertEquals(FailureKind.TRANSFER_INCOMPLETE, kind)
            assertEquals(UNLOCK_TRANSFER_TEXT, shownText(kind))
        }
    }
}
