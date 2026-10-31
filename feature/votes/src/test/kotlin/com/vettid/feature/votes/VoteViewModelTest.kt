// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.votes

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.votes.BallotState
import com.vettid.core.data.votes.CheckState
import com.vettid.core.data.votes.VoteBallot
import com.vettid.core.data.votes.VoteChoice
import com.vettid.core.data.votes.VoteCheck
import com.vettid.core.data.votes.VoteDetail
import com.vettid.core.data.votes.VoteState
import com.vettid.core.data.votes.VoteSummary
import com.vettid.core.testing.FakeSocial
import com.vettid.core.testing.FakeVault
import com.vettid.core.testing.FakeVotes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.OutputStream

private const val CONN = "c1"
private const val PID = "01JBQZ0000000000000000000A"

/** Casting, the states and the proof of one vote (ANDROID-PLAN 0.1.102 to 0.1.108 §4 "Votes"). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoteViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val votes = FakeVotes()
    private val files = FakeProofDocuments()

    private class FakeProofDocuments : ProofDocuments {
        val written = mutableMapOf<String, String>()
        var fail = false
        val discarded = mutableListOf<String>()

        override fun write(uri: String, block: (OutputStream) -> Unit) {
            if (fail) throw java.io.IOException("no space")
            val out = java.io.ByteArrayOutputStream()
            block(out)
            written[uri] = out.toString(Charsets.UTF_8)
        }

        override fun discard(uri: String) {
            discarded += uri
        }
    }

    private fun summary(
        conn: String = CONN,
        id: String = PID,
        state: VoteState = VoteState.OPEN,
        ballot: VoteBallot? = null,
        check: VoteCheck? = null,
        canCast: Boolean = ballot == null && state == VoteState.OPEN,
        cannotCast: String? = null,
        confirmPending: Boolean = false,
        conflictEvidence: Boolean = false,
    ) = VoteSummary(
        proposalId = id, connectionId = conn, title = "Adopt the budget", state = state, ballot = ballot, check = check,
        canCast = canCast, cannotCast = cannotCast, confirmPending = confirmPending, conflictEvidence = conflictEvidence,
    )

    private fun detail(s: VoteSummary, choiceId: String? = null, service: String? = null) = VoteDetail(
        summary = s, text = "Text", choices = listOf(VoteChoice("yes", "Yes"), VoteChoice("no", "No"), VoteChoice("abstain", "Abstain")),
        quorumText = null, minBallots = null, binding = null, recordTag = null, choiceId = choiceId, extensions = 0, tally = null,
        counts = null, serviceBallotChoiceId = service,
    )

    private fun publish(s: VoteSummary, d: VoteDetail = detail(s)) {
        votes.votes.value = votes.votes.value.filterNot { it.key == s.key } + s
        votes.details[s.key] = d
    }

    private fun vm(conn: String = CONN, id: String = PID) = VoteViewModel(
        SavedStateHandle(mapOf(VoteRoute.CONNECTION to conn, VoteRoute.PROPOSAL to id)),
        votes,
        FakeSocial(),
        files,
        FakeVault(),
    )

    @Test
    fun theVoteIsReadWhenItOpensWithItsPublicSiteCopy() = runTest {
        publish(summary())
        val vm = vm()
        advanceUntilIdle()
        assertEquals("Text", vm.uiState.value.detail?.text)
        assertTrue("siteCheck:$CONN/$PID" in votes.calls)
        assertTrue(vm.uiState.value.loaded)
    }

    @Test
    fun aVoteTheVaultDoesNotHoldIsGoneAfterTheListWasRead() = runTest {
        val vm = vm()
        advanceUntilIdle()
        assertNull(vm.uiState.value.summary)
        assertTrue(vm.uiState.value.loaded)
    }

    @Test
    fun castNeedsAChoiceAndTheVaultsYes() = runTest {
        publish(summary())
        val vm = vm()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canCast)
        vm.askCast()
        assertEquals(CastStep.NONE, vm.uiState.value.cast)
        vm.select("yes")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canCast)
        vm.askCast()
        assertEquals(CastStep.CONFIRM, vm.uiState.value.cast)
        // Nothing is sent by asking.
        assertTrue(votes.calls.none { it.startsWith("cast") })
    }

    @Test
    fun noCastForACodeTheVaultGivesAndNoCastForAChoiceNotInTheList() = runTest {
        for (code in listOf("checking", "not_open", "closed", "closing", "already_voted", "site_mismatch", "connection_unavailable", "not_signed_in")) {
            votes.votes.value = emptyList()
            publish(summary(canCast = false, cannotCast = code))
            val vm = vm()
            advanceUntilIdle()
            vm.select("yes")
            assertFalse(code, vm.uiState.value.canCast)
            vm.askCast()
            assertEquals(code, CastStep.NONE, vm.uiState.value.cast)
        }
        votes.votes.value = emptyList()
        publish(summary())
        val vm = vm()
        advanceUntilIdle()
        vm.select("maybe")
        assertFalse(vm.uiState.value.canCast)
    }

    @Test
    fun noCastWhileARestoreWaitsForTheMembersAnswer() = runTest {
        publish(summary(confirmPending = true), detail(summary(confirmPending = true), service = "yes"))
        val vm = vm()
        advanceUntilIdle()
        vm.select("no")
        assertFalse(vm.uiState.value.canCast)
        vm.askCast()
        assertEquals(CastStep.NONE, vm.uiState.value.cast)
        assertTrue(vm.uiState.value.restoreAsked)
        assertEquals("Yes", vm.uiState.value.restoreLabel)
    }

    @Test
    fun anOpenWindowCastsAtOnceWithoutAPasswordPrompt() = runTest {
        publish(summary())
        votes.windowIsOpen = true
        val vm = vm()
        advanceUntilIdle()
        vm.select("yes")
        vm.askCast()
        vm.confirmCast()
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), votes.castPasswords)
        assertEquals(1, votes.calls.count { it == "cast:$CONN/$PID:yes" })
        assertEquals(CastStep.NONE, vm.uiState.value.cast)
        assertNull(vm.uiState.value.selected)
    }

    @Test
    fun aClosedWindowAsksForThePasswordAndSendsTheRequestOnce() = runTest {
        publish(summary())
        val vm = vm()
        advanceUntilIdle()
        vm.select("no")
        vm.askCast()
        vm.confirmCast()
        assertEquals(CastStep.PASSWORD, vm.uiState.value.cast)
        assertTrue(votes.calls.none { it.startsWith("cast") })
        vm.submitPassword() // empty: nothing
        assertTrue(votes.calls.none { it.startsWith("cast") })
        vm.setPassword("hunter2")
        vm.submitPassword()
        advanceUntilIdle()
        assertEquals(listOf<String?>("hunter2"), votes.castPasswords)
        assertEquals(1, votes.calls.count { it == "cast:$CONN/$PID:no" })
        // The password is held only while the request runs.
        assertEquals("", vm.uiState.value.password)
    }

    @Test
    fun aWrongPasswordStaysInThePromptAndCastsNothing() = runTest {
        publish(summary())
        votes.fail["cast:$CONN/$PID:yes"] = VaultFailure(FailureKind.BAD_PASSWORD, "bad_password")
        val vm = vm()
        advanceUntilIdle()
        vm.select("yes")
        vm.askCast()
        vm.confirmCast()
        vm.setPassword("wrong")
        vm.submitPassword()
        advanceUntilIdle()
        assertEquals(CastStep.PASSWORD, vm.uiState.value.cast)
        assertEquals(FailureKind.BAD_PASSWORD, vm.uiState.value.passwordError?.kind)
        assertEquals("", vm.uiState.value.password)
    }

    @Test
    fun aClosedWindowAfterAllAsksForThePasswordAndNothingIsRetriedByTheApp() = runTest {
        publish(summary())
        votes.windowIsOpen = true
        votes.fail["cast:$CONN/$PID:yes"] = VaultFailure(FailureKind.CREDENTIAL_LOCKED, "credential_locked")
        val vm = vm()
        advanceUntilIdle()
        vm.select("yes")
        vm.askCast()
        vm.confirmCast()
        advanceUntilIdle()
        assertEquals(CastStep.PASSWORD, vm.uiState.value.cast)
        assertEquals(1, votes.calls.count { it.startsWith("cast") })
    }

    @Test
    fun noAnswerIsSaidPlainlyAndNotRetriedByTheApp() = runTest {
        publish(summary())
        votes.windowIsOpen = true
        votes.fail["cast:$CONN/$PID:yes"] = VaultFailure(FailureKind.NO_RESPONSE)
        val vm = vm()
        advanceUntilIdle()
        vm.select("yes")
        vm.askCast()
        vm.confirmCast()
        advanceUntilIdle()
        assertEquals(FailureKind.NO_RESPONSE, vm.uiState.value.castProblem?.kind)
        assertEquals(1, votes.calls.count { it.startsWith("cast") })
        assertEquals(CastStep.NONE, vm.uiState.value.cast)
    }

    @Test
    fun aRefusalOfTheVaultIsShownWithItsCodeAndTheVoteIsReadAgain() = runTest {
        publish(summary())
        votes.windowIsOpen = true
        votes.fail["cast:$CONN/$PID:yes"] = VaultFailure(FailureKind.OTHER, "closing")
        val vm = vm()
        advanceUntilIdle()
        val reads = votes.calls.count { it == "refresh" }
        vm.select("yes")
        vm.askCast()
        vm.confirmCast()
        advanceUntilIdle()
        assertEquals("closing", vm.uiState.value.castProblem?.code)
        assertTrue(votes.calls.count { it == "refresh" } > reads)
    }

    @Test
    fun aCastGoesToTheConnectionOfThisVoteWhenTwoServicesShareAProposalId() = runTest {
        publish(summary(conn = "c1"))
        publish(summary(conn = "c2"))
        votes.windowIsOpen = true
        val vm = vm(conn = "c2")
        advanceUntilIdle()
        vm.select("yes")
        vm.askCast()
        vm.confirmCast()
        advanceUntilIdle()
        assertEquals(listOf("cast:c2/$PID:yes"), votes.calls.filter { it.startsWith("cast") })
        assertEquals("c2", vm.uiState.value.summary?.connectionId)
    }

    @Test
    fun theRestoreDialogAsksOnlyWhenTheVaultNamedABallot() = runTest {
        publish(summary(confirmPending = true))
        val vm = vm()
        advanceUntilIdle()
        // confirm_pending without a service ballot: no question (any other unsigned ballot is a problem); Cast stays blocked.
        assertFalse(vm.uiState.value.restoreAsked)
        publish(summary(confirmPending = true), detail(summary(confirmPending = true), service = "no"))
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.restoreAsked)
        vm.answerRestore(true)
        advanceUntilIdle()
        assertTrue("confirm:$CONN/$PID:true" in votes.calls)
        vm.answerRestore(false)
        advanceUntilIdle()
        assertTrue("confirm:$CONN/$PID:false" in votes.calls)
    }

    @Test
    fun theProofIsWrittenUnchangedOnlyWhereTheMemberChooses() = runTest {
        val s = summary(state = VoteState.CLOSED, ballot = VoteBallot(BallotState.CONFLICT))
        publish(s)
        votes.evidenceText = """{"proposal":"AAAA","member":{"ballot":{"choice_id":"yes"}}}"""
        val vm = vm()
        advanceUntilIdle()
        vm.askProof()
        assertTrue(vm.uiState.value.proofWarning)
        // Nothing is asked of the vault before the warning is accepted.
        assertTrue(votes.calls.none { it.startsWith("evidence") })
        vm.fetchProof()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.proofReady)
        assertEquals("vettid-vote-$PID.json", vm.proofFileName())
        assertTrue(files.written.isEmpty())
        vm.saveProofTo("content://documents/1")
        advanceUntilIdle()
        assertEquals(votes.evidenceText, files.written["content://documents/1"])
        assertTrue(vm.uiState.value.proofSaved)
        assertTrue(vm.uiState.value.proofExported)
        // Nothing is kept: a second save has nothing to write.
        vm.saveProofTo("content://documents/2")
        advanceUntilIdle()
        assertNull(files.written["content://documents/2"])
    }

    @Test
    fun cancellingTheSaveDialogWritesNothingAndForgetsTheProof() = runTest {
        publish(summary(state = VoteState.CLOSED, ballot = VoteBallot(BallotState.CONFLICT)))
        val vm = vm()
        advanceUntilIdle()
        vm.askProof()
        vm.fetchProof()
        advanceUntilIdle()
        vm.saveProofTo(null)
        assertTrue(files.written.isEmpty())
        assertFalse(vm.uiState.value.proofReady)
        assertFalse(vm.uiState.value.proofExported)
    }

    @Test
    fun aFailedWriteIsSaidAndLeavesNothingBehind() = runTest {
        publish(summary(state = VoteState.CLOSED, ballot = VoteBallot(BallotState.CONFLICT)))
        files.fail = true
        val vm = vm()
        advanceUntilIdle()
        vm.askProof()
        vm.fetchProof()
        advanceUntilIdle()
        vm.saveProofTo("content://documents/1")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.proofSaveFailed)
        assertFalse(vm.uiState.value.proofExported)
        assertEquals(listOf("content://documents/1"), files.discarded)
    }

    @Test
    fun aFailedEvidenceRequestIsShownAndNothingIsOffered() = runTest {
        publish(summary(state = VoteState.CLOSED, ballot = VoteBallot(BallotState.CONFLICT)))
        votes.fail["evidence:$CONN/$PID"] = VaultFailure(FailureKind.OTHER, "forbidden")
        val vm = vm()
        advanceUntilIdle()
        vm.askProof()
        vm.fetchProof()
        advanceUntilIdle()
        assertEquals("forbidden", vm.uiState.value.proofProblem?.code)
        assertFalse(vm.uiState.value.proofReady)
    }

    @Test
    fun releaseIsOfferedOnlyAfterTheProofWasSavedAndAsksForThePin() = runTest {
        publish(summary(state = VoteState.CLOSED, ballot = VoteBallot(BallotState.CONFLICT)))
        val vm = vm()
        advanceUntilIdle()
        vm.askRelease()
        assertFalse(vm.uiState.value.releaseDialog)
        vm.askProof()
        vm.fetchProof()
        advanceUntilIdle()
        vm.saveProofTo("content://documents/1")
        advanceUntilIdle()
        vm.askRelease()
        assertTrue(vm.uiState.value.releaseDialog)
        vm.release() // no PIN: nothing
        assertTrue(votes.releasePins.isEmpty())
        vm.setPin("12a34")
        assertEquals("1234", vm.uiState.value.pin)
        vm.release()
        advanceUntilIdle()
        assertEquals(listOf("1234"), votes.releasePins)
        assertTrue(vm.uiState.value.released)
        assertEquals("", vm.uiState.value.pin)
    }

    @Test
    fun aReleaseTheVaultRefusesIsShownAndThePinIsForgotten() = runTest {
        publish(summary(state = VoteState.CLOSED, ballot = VoteBallot(BallotState.CONFLICT)))
        votes.fail["release:$CONN/$PID"] = VaultFailure(FailureKind.OTHER, "evidence_not_exported")
        val vm = vm()
        advanceUntilIdle()
        vm.askProof()
        vm.fetchProof()
        advanceUntilIdle()
        vm.saveProofTo("content://documents/1")
        advanceUntilIdle()
        vm.askRelease()
        vm.setPin("1234")
        vm.release()
        advanceUntilIdle()
        assertEquals("evidence_not_exported", vm.uiState.value.releaseProblem?.code)
        assertEquals("", vm.uiState.value.pin)
        assertFalse(vm.uiState.value.released)
    }

    @Test
    fun checkWithTheServiceAsksTheServiceAndCheckAgainTakesTheResultsForAClosedVote() = runTest {
        publish(summary(ballot = VoteBallot(BallotState.SENDING)))
        val open = vm()
        advanceUntilIdle()
        open.checkWithService()
        advanceUntilIdle()
        assertTrue("check:$CONN/$PID" in votes.calls)

        votes.votes.value = emptyList()
        publish(summary(id = "P2", state = VoteState.CLOSED, ballot = VoteBallot(BallotState.RECORDED), check = VoteCheck(CheckState.PENDING)))
        val closed = vm(id = "P2")
        advanceUntilIdle()
        closed.checkWithService()
        advanceUntilIdle()
        assertTrue("checkResults:$CONN/P2" in votes.calls)
    }

    @Test
    fun aGaveUpCheckLooksAtTheSiteOnce() = runTest {
        publish(summary(state = VoteState.CLOSED, ballot = VoteBallot(BallotState.RECORDED), check = VoteCheck(CheckState.GAVE_UP)))
        vm()
        advanceUntilIdle()
        assertTrue("diagnose:$CONN/$PID" in votes.calls)
    }

    @Test
    fun noAnswerReadsTheVoteAgainOnEveryPathAndNeverSaysNotSent() = runTest {
        publish(summary())
        votes.windowIsOpen = true
        votes.fail["cast:$CONN/$PID:yes"] = VaultFailure(FailureKind.NO_RESPONSE)
        val vm = vm()
        advanceUntilIdle()
        val reads = votes.calls.count { it == "refresh" }
        vm.select("yes")
        vm.askCast()
        vm.confirmCast()
        advanceUntilIdle()
        assertTrue(votes.calls.count { it == "refresh" } > reads)
        assertEquals(FailureKind.NO_RESPONSE, vm.uiState.value.castProblem?.kind)

        // The password-prompted path reads it again too.
        votes.windowIsOpen = false
        votes.fail["cast:$CONN/$PID:yes"] = VaultFailure(FailureKind.NETWORK)
        val reads2 = votes.calls.count { it == "refresh" }
        vm.select("yes")
        vm.askCast()
        vm.confirmCast()
        vm.setPassword("pw")
        vm.submitPassword()
        advanceUntilIdle()
        assertTrue(votes.calls.count { it == "refresh" } > reads2)
        assertEquals(FailureKind.NETWORK, vm.uiState.value.castProblem?.kind)
    }

    @Test
    fun alreadyVotedReadsTheVoteAgainAndTellsNoFailure() = runTest {
        publish(summary())
        votes.windowIsOpen = true
        votes.fail["cast:$CONN/$PID:no"] = VaultFailure(FailureKind.OTHER, "already_voted")
        val vm = vm()
        advanceUntilIdle()
        val reads = votes.calls.count { it == "refresh" }
        vm.select("no")
        vm.askCast()
        vm.confirmCast()
        advanceUntilIdle()
        assertTrue(votes.calls.count { it == "refresh" } > reads)
        assertNull(vm.uiState.value.castProblem)
    }

    @Test
    fun aLockThatFailedIsPassedToTheScreen() = runTest {
        publish(summary())
        val vm = vm()
        votes.windowNotClosed.value = com.vettid.core.data.votes.WindowNotClosed(300)
        advanceUntilIdle()
        assertEquals(300L, vm.uiState.value.windowNotClosed?.ttlSeconds)
        // Close it now: the notice stays when the lock fails and goes when it went through.
        votes.fail["closeWindowNow"] = VaultFailure(FailureKind.NO_RESPONSE)
        vm.closeWindowNow()
        advanceUntilIdle()
        assertEquals(FailureKind.NO_RESPONSE, vm.uiState.value.windowCloseProblem?.kind)
        assertEquals(300L, vm.uiState.value.windowNotClosed?.ttlSeconds)
        vm.closeWindowNow()
        advanceUntilIdle()
        assertNull(vm.uiState.value.windowNotClosed)
    }

    @Test
    fun aRefusalOfARetryWhileTheVoteIsRecordedIsNotShownAsNotSent() = runTest {
        publish(summary())
        votes.windowIsOpen = true
        votes.fail["cast:$CONN/$PID:yes"] = VaultFailure(FailureKind.OTHER, "closing")
        val vm = vm()
        advanceUntilIdle()
        // The vault checks closed/closing before a repeat of the same choice; meanwhile the ballot is held.
        votes.onRefresh = { votes.votes.value = listOf(summary(ballot = VoteBallot(BallotState.SENDING))) }
        vm.select("yes")
        vm.askCast()
        vm.confirmCast()
        advanceUntilIdle()
        assertNull(vm.uiState.value.castProblem)
        assertEquals(BallotState.SENDING, vm.uiState.value.summary?.ballot?.state)
    }

    @Test
    fun alreadyVotedIsToldWithTheRecordedChoice() = runTest {
        val s = summary()
        publish(s)
        votes.windowIsOpen = true
        votes.fail["cast:$CONN/$PID:no"] = VaultFailure(FailureKind.OTHER, "already_voted")
        val vm = vm()
        advanceUntilIdle()
        votes.onRefresh = {
            val held = summary(ballot = VoteBallot(BallotState.RECORDED))
            votes.votes.value = listOf(held)
            votes.details[held.key] = detail(held, choiceId = "yes")
        }
        vm.select("no")
        vm.askCast()
        vm.confirmCast()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.alreadyVoted)
        assertNull(vm.uiState.value.castProblem)
        assertEquals("Yes", vm.uiState.value.choiceLabel)
        vm.dismissAlreadyVoted()
        assertFalse(vm.uiState.value.alreadyVoted)
    }
}
