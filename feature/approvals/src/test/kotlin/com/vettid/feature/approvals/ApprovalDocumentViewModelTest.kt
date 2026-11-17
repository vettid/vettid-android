package com.vettid.feature.approvals

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.ApprovalDocument
import com.vettid.core.data.social.DocumentApproval
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeSocial
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * VAULT-MESSAGING 0.89.0 §10.4 "Approval documents" on the approval screen's view model: the document is fetched and checked,
 * Approve waits for it to have been seen whole, only Deny while it is not shown, and the approval goes only over the document
 * the held request names now, never by the old password path. Robolectric for Android's Unicode data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ApprovalDocumentViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val r = DocumentSamples.request(DocumentSamples.EXTEND)
    private val social = FakeSocial().apply {
        approvals.value = listOf(r)
        authDocument = DocumentSamples.check(DocumentSamples.EXTEND, r)
    }

    private fun detail() = ApprovalDetailViewModel(
        SavedStateHandle(mapOf(ApprovalDetailRoute.ARG to r.key)), social, com.vettid.core.testing.FakeItems(),
        com.vettid.core.testing.FakeDevices(), FakeVault(), social, com.vettid.core.testing.FakePairing(),
    )

    private fun ApprovalDetailViewModel.seeWhole() {
        val s = uiState.value
        documentSeen((s.document as DocumentStep.Shown).document.sha256, s.documentGeneration, true)
    }

    @Test
    fun theDocumentIsFetchedOnceAndShownOnlyDenyUntilThen() = runTest {
        val vm = detail()
        assertEquals(DocumentStep.Loading, vm.uiState.value.document)
        assertTrue("only Deny while it loads", vm.uiState.value.onlyDeny)
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.document is DocumentStep.Shown)
        assertFalse(s.onlyDeny)
        assertEquals(1, social.calls.count { it == "authenticationDocument" })
        // The same request again fetches nothing.
        social.approvals.value = listOf(r.copy())
        advanceUntilIdle()
        assertEquals(1, social.calls.count { it == "authenticationDocument" })
    }

    @Test
    fun approveWaitsForTheWholeDocumentOnScreen() = runTest {
        val vm = detail()
        advanceUntilIdle()
        vm.setPassword("pw-fake-1")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canApprove)
        vm.approve()
        advanceUntilIdle()
        assertTrue(social.calls.none { it.startsWith("approveAuthentication") })
        // A report from an earlier showing, or about another hash, counts for nothing.
        val gen = vm.uiState.value.documentGeneration
        vm.documentSeen("other", gen, true)
        advanceUntilIdle()
        vm.documentSeen(r.documentSha256!!, gen - 1, true)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canApprove)
        vm.seeWhole()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canApprove)
        // Another text size or width starts again.
        vm.documentSeen(r.documentSha256!!, gen, false)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canApprove)
        vm.seeWhole()
        advanceUntilIdle()
        vm.approve()
        advanceUntilIdle()
        assertEquals(listOf("approveAuthenticationDocument"), social.calls.filter { it.startsWith("approveAuthentication") })
        assertEquals("pw-fake-1", social.lastPassword)
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun onlyDenyWhenTheFetchFailsOrTheDocumentCantBeShown() = runTest {
        social.fail["authenticationDocument"] = VaultFailure(FailureKind.NETWORK)
        val vm = detail()
        advanceUntilIdle()
        assertEquals(FailureKind.NETWORK, (vm.uiState.value.document as DocumentStep.Failed).kind)
        assertTrue(vm.uiState.value.onlyDeny)
        assertFalse(vm.uiState.value.canApprove)
        // Try again fetches again.
        vm.retryDocument()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.document is DocumentStep.Shown)
        val refused = detailWith(ApprovalDocument.Check.Refused(ApprovalDocument.Failure.HASH))
        advanceUntilIdle()
        assertEquals(DocumentStep.Unshowable(ApprovalDocument.Failure.HASH), refused.uiState.value.document)
        assertTrue(refused.uiState.value.onlyDeny)
        refused.setPassword("pw-fake-1")
        advanceUntilIdle()
        refused.approve()
        advanceUntilIdle()
        assertTrue(social.calls.none { it.startsWith("approveAuthentication") })
        // Deny is always offered.
        refused.deny()
        advanceUntilIdle()
        assertTrue("denyAuthentication" in social.calls)
    }

    private fun detailWith(check: ApprovalDocument.Check): ApprovalDetailViewModel {
        social.authDocument = check
        return detail()
    }

    @Test
    fun aRequestThatComesToNameAnotherDocumentFetchesItAgainAndStartsOver() = runTest {
        val vm = detail()
        advanceUntilIdle()
        vm.setPassword("pw-fake-1")
        advanceUntilIdle()
        vm.seeWhole()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canApprove)
        val other = DocumentSamples.request(DocumentSamples.PROPOSAL)
        social.authDocument = DocumentSamples.check(DocumentSamples.PROPOSAL, other)
        social.approvals.value = listOf(other)
        advanceUntilIdle()
        assertEquals(2, social.calls.count { it == "authenticationDocument" })
        val s = vm.uiState.value
        assertEquals(other.documentSha256, (s.document as DocumentStep.Shown).document.sha256)
        assertNull("not seen yet", s.documentToApprove)
        assertEquals("", s.password)
        assertFalse(s.canApprove)
    }

    @Test
    fun aStaleDocumentIsNeverApproved() = runTest {
        val vm = detail()
        advanceUntilIdle()
        vm.setPassword("pw-fake-1")
        advanceUntilIdle()
        vm.seeWhole()
        advanceUntilIdle()
        // The held request names another hash: what was shown is not approvable, whatever its fetch comes to.
        social.fail["authenticationDocument"] = VaultFailure(FailureKind.NETWORK)
        social.approvals.value = listOf(r.copy(documentSha256 = "AAAA"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.document is DocumentStep.Failed)
        assertNull(vm.uiState.value.documentToApprove)
        assertFalse(vm.uiState.value.canApprove)
        // Nor one shown with another line than the request's now.
        val moved = r.copy(context = "Something else")
        assertNull(vm.uiState.value.copy(approval = moved).documentToApprove)
    }

    @Test
    fun aChangedDocumentIsShownFromTheStartWithNoFailure() = runTest {
        val vm = detail()
        advanceUntilIdle()
        vm.setPassword("pw-fake-1")
        advanceUntilIdle()
        vm.seeWhole()
        advanceUntilIdle()
        val gen = vm.uiState.value.documentGeneration
        social.documentApproval = DocumentApproval.Changed(ApprovalDocument.Check.Refused(ApprovalDocument.Failure.HASH))
        vm.approve()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(DocumentStep.Unshowable(ApprovalDocument.Failure.HASH), s.document)
        assertEquals(gen + 1, s.documentGeneration)
        assertNull(s.error)
        assertFalse(s.done)
        assertEquals("", s.password)
        assertTrue(s.onlyDeny)
    }

    @Test
    fun theOldPathCanNoLongerApproveADocumentChallenge() = runTest {
        val vm = detail()
        advanceUntilIdle()
        vm.setPassword("pw-fake-1")
        advanceUntilIdle()
        vm.seeWhole()
        advanceUntilIdle()
        // The ask is no longer held in memory: the screen still decides from the request it shows, over its document.
        social.approvals.value = emptyList()
        vm.approve()
        advanceUntilIdle()
        assertTrue("approveAuthentication" !in social.calls)
        // And the repository's old path refuses an ask held with a document, required or not.
        for (required in listOf(true, false)) {
            social.approvals.value = listOf(r.copy(documentRequired = required))
            val f = runCatching { social.approveAuthentication(r.requestId, "pw-fake-1") }.exceptionOrNull() as VaultFailure
            assertEquals(FailureKind.OTHER, f.kind)
        }
        // A request without a document still goes by it, as before.
        val plain = Approval.Authentication("a2", "c1", "Pay 40", DocumentSamples.now, null, "Sam")
        social.approvals.value = listOf(plain)
        val p = ApprovalDetailViewModel(
            SavedStateHandle(mapOf(ApprovalDetailRoute.ARG to plain.key)), social, com.vettid.core.testing.FakeItems(),
            com.vettid.core.testing.FakeDevices(), FakeVault(), social, com.vettid.core.testing.FakePairing(),
        )
        advanceUntilIdle()
        assertNull(p.uiState.value.document)
        p.setPassword("pw-fake-1")
        advanceUntilIdle()
        p.approve()
        advanceUntilIdle()
        assertTrue(p.uiState.value.done)
    }
}
