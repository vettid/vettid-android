// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.approvals

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.GrantEntry
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeSocial
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalsViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private val request = Approval.ConnectionRequest("p1", "i1", "042817", false, "Morgan", null, now, null)
    private val auth = Approval.Authentication("a1", "c1", null, now, null, "Sam")
    private val grant = Approval.GrantRequest("g1", "c1", listOf(GrantEntry("category", "insurance", null, false)), 1, null, null, now, null)
    private val critical = Approval.CriticalUse("u1", "c1", "Key", "Seed", "sign", "", "x", null, now, null)
    private val device = Approval.DeviceRequest("approval.pending", "d1", "Laptop", "desktop", "item.reveal", now, null)
    private val social = FakeSocial().apply { approvals.value = listOf(request, auth, grant, critical, device) }

    private fun detail(a: Approval) = ApprovalDetailViewModel(SavedStateHandle(mapOf(ApprovalDetailRoute.ARG to a.key)), social)

    @Test
    fun listsWhatTheRepositoryHolds() = runTest {
        val vm = ApprovalsViewModel(social)
        advanceUntilIdle()
        assertEquals(5, vm.uiState.value.approvals.size)
        assertTrue("refreshApprovals" in social.calls)
    }

    @Test
    fun approvesAConnectionRequestAndClosesWithoutCallingItGone() = runTest {
        val vm = detail(request)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canApprove)
        vm.approve()
        advanceUntilIdle()
        assertTrue("approveConnection" in social.calls)
        assertTrue(vm.uiState.value.done)
        assertFalse(vm.uiState.value.gone)
    }

    @Test
    fun authenticationNeedsThePasswordAndAWrongOneIsCleared() = runTest {
        val vm = detail(auth)
        advanceUntilIdle()
        assertEquals(Needs.PASSWORD, vm.uiState.value.needs)
        assertFalse(vm.uiState.value.canApprove)
        social.fail["approveAuthentication"] = VaultFailure(FailureKind.BAD_PASSWORD, "bad_password")
        vm.setPassword("wrong")
        vm.approve()
        advanceUntilIdle()
        assertEquals(FailureKind.BAD_PASSWORD, vm.uiState.value.error)
        assertEquals("", vm.uiState.value.password)
        vm.setPassword("right")
        vm.approve()
        advanceUntilIdle()
        assertEquals("right", social.lastPassword)
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun aGrantWithNothingGrantableCanOnlyBeDenied() = runTest {
        val vm = detail(grant)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canApprove)
        vm.approve()
        advanceUntilIdle()
        assertFalse("approveGrant" in social.calls)
        vm.deny()
        advanceUntilIdle()
        assertTrue("denyGrant" in social.calls)
    }

    @Test
    fun criticalUseAndDeviceRequests() = runTest {
        val c = detail(critical)
        advanceUntilIdle()
        c.setPassword("pw")
        c.approve()
        advanceUntilIdle()
        assertTrue("approveCriticalUse" in social.calls)

        val d = detail(device)
        advanceUntilIdle()
        assertFalse(d.uiState.value.canApprove)
        d.deny()
        advanceUntilIdle()
        assertTrue("declineDeviceRequest" in social.calls)
    }

    @Test
    fun decidedElsewhereIsShownAsGone() = runTest {
        val vm = detail(request)
        advanceUntilIdle()
        social.approvals.value = emptyList()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.gone)
    }
}
