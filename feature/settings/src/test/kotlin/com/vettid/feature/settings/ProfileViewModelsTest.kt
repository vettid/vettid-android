package com.vettid.feature.settings

import com.vettid.core.data.vault.AccountInfo
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.NameChangeOutcome
import com.vettid.core.data.vault.NameRequestState
import com.vettid.core.data.vault.NameRequestView
import com.vettid.core.data.vault.OwnProfile
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** The shared profile and the name change (VAULT-MESSAGING 0.18.0 §10.8, ANDROID-PLAN 0.1.10). */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val vault = FakeVault(AppPhase.Unlocked).apply {
        account.value = AccountInfo("a***@example.org", state = "member", firstName = "Ada", lastName = "Lovelace")
        profile.value = OwnProfile(2, "", "Ada", "Lovelace", "9a1f bb7d 873e eafb 494b ef94 f072 7b25")
    }

    private val items = com.vettid.core.testing.FakeItems()

    // --- the shared profile ---

    @Test
    fun aChosenPhotoIsPreviewedThenSaved() = runTest {
        val vm = SharedProfileViewModel(vault, vault, items)
        advanceUntilIdle()
        assertNull(vm.uiState.value.shownPhoto)
        vm.photoEncoding()
        assertTrue(vm.uiState.value.photoBusy)
        vm.photoPicked("/9j/4AAQ")
        assertEquals("/9j/4AAQ", vm.uiState.value.shownPhoto)
        assertNull(vault.lastPhoto) // a preview only
        vm.savePhoto()
        advanceUntilIdle()
        assertEquals("/9j/4AAQ", vault.lastPhoto)
        assertEquals("/9j/4AAQ", vault.profile.value!!.photo)
        assertTrue(vm.uiState.value.photoSaved)
        assertNull(vm.uiState.value.pendingPhoto)
    }

    @Test
    fun anUnreadablePictureIsSaidAndNothingSent() = runTest {
        val vm = SharedProfileViewModel(vault, vault, items)
        advanceUntilIdle()
        vm.photoEncoding()
        vm.photoPicked(null)
        assertTrue(vm.uiState.value.photoUnreadable)
        vm.savePhoto()
        advanceUntilIdle()
        assertNull(vault.lastPhoto)
    }

    @Test
    fun removingThePhotoSendsEmpty() = runTest {
        vault.profile.value = vault.profile.value!!.copy(photo = "/9j/old")
        val vm = SharedProfileViewModel(vault, vault, items)
        advanceUntilIdle()
        assertEquals("/9j/old", vm.uiState.value.shownPhoto)
        vm.removePhoto()
        advanceUntilIdle()
        assertEquals("", vault.lastPhoto)
        assertNull(vault.profile.value!!.photo)
    }

    @Test
    fun theDisplayNameIsEditedAndSaved() = runTest {
        val vm = SharedProfileViewModel(vault, vault, items)
        advanceUntilIdle()
        assertEquals("Ada Lovelace", vm.uiState.value.fullName)
        assertFalse(vm.uiState.value.saveAllowed) // nothing changed yet
        vm.setDisplayName("  Countess ")
        assertTrue(vm.uiState.value.saveAllowed)
        vm.save()
        advanceUntilIdle()
        assertEquals("Countess", vault.profile.value!!.displayName)
        assertTrue(vm.uiState.value.saved)
        assertFalse(vm.uiState.value.edited)
    }

    @Test
    fun aDisplayNameOverTheLimitIsNotSent() = runTest {
        val vm = SharedProfileViewModel(vault, vault, items)
        advanceUntilIdle()
        vm.setDisplayName("é".repeat(65)) // 130 bytes
        assertTrue(vm.uiState.value.tooLong)
        assertFalse(vm.uiState.value.saveAllowed)
        vm.save()
        advanceUntilIdle()
        assertFalse("setDisplayName" in vault.calls)
    }

    @Test
    fun aRefusedSaveShowsWhy() = runTest {
        vault.fail["setDisplayName"] = FakeVault.failure(FailureKind.CONFLICT, "conflict")
        val vm = SharedProfileViewModel(vault, vault, items)
        advanceUntilIdle()
        vm.setDisplayName("Countess")
        vm.save()
        advanceUntilIdle()
        assertEquals(FailureKind.CONFLICT, vm.uiState.value.error)
    }

    // --- the name change: the names step ---

    @Test
    fun theNamesStartAsTheCurrentOnesAndTheRuleIsCheckedBeforeThePin() = runTest {
        val vm = ChangeNameViewModel(vault, vault)
        advanceUntilIdle()
        assertEquals("Ada", vm.uiState.value.first)
        assertEquals("Lovelace", vm.uiState.value.last)
        // Unchanged names are refused by the vault (bad_request): not sent.
        vm.next()
        assertEquals(ChangeNameStep.NAMES, vm.uiState.value.step)
        assertTrue(vm.uiState.value.same)
        vm.setLast("King1")
        vm.next()
        assertEquals(ChangeNameStep.NAMES, vm.uiState.value.step)
        vm.setLast("")
        vm.next()
        assertEquals(ChangeNameStep.NAMES, vm.uiState.value.step)
        vm.setLast("a".repeat(41))
        vm.next()
        assertEquals(ChangeNameStep.NAMES, vm.uiState.value.step)
        vm.setLast(" King ")
        vm.next()
        assertEquals(ChangeNameStep.CONFIRM, vm.uiState.value.step)
        assertEquals("Ada King", vm.uiState.value.requestedName)
        assertFalse("changeName" in vault.calls)
    }

    @Test
    fun theSnapshotsAllowedAfterStopsTheFlowBeforeThePin() = runTest {
        vault.account.value = vault.account.value!!.copy(nameAllowedAfter = Instant.now().plusSeconds(86_400))
        val vm = ChangeNameViewModel(vault, vault)
        advanceUntilIdle()
        vm.setLast("King")
        vm.next()
        assertEquals(ChangeNameStep.NAMES, vm.uiState.value.step)
        assertTrue(vm.tooSoonBySnapshot())
    }

    // --- the name change: the PIN and password ---

    private fun atConfirm(): ChangeNameViewModel = ChangeNameViewModel(vault, vault).apply {
        setLast("King")
        next()
        setPin("246810")
        setPassword("correct horse")
    }

    @Test
    fun aRequestIsSentWithBothSecretsAndThenForgetsThem() = runTest {
        val vm = atConfirm()
        assertTrue(vm.uiState.value.submitAllowed)
        vm.submit()
        advanceUntilIdle()
        assertEquals("246810", vault.lastPin)
        assertEquals("correct horse", vault.lastPassword)
        assertEquals("Ada" to "King", vault.lastNames)
        val s = vm.uiState.value
        assertEquals(ChangeNameStep.SENT, s.step)
        assertEquals(NameRequestState.PENDING, s.request!!.state)
        assertEquals("", s.pin)
        assertEquals("", s.password)
    }

    @Test
    fun theRequestFollowsTheAccountToAppliedOrRefused() = runTest {
        val vm = atConfirm()
        vm.submit()
        advanceUntilIdle()
        val seq = vm.uiState.value.request!!.seq
        // Another request's outcome is not this one's.
        vault.account.value = vault.account.value!!.copy(
            nameRequest = NameRequestView(seq + 5, "X", "Y", null, NameRequestState.APPLIED),
        )
        advanceUntilIdle()
        assertEquals(NameRequestState.PENDING, vm.uiState.value.request!!.state)
        // account.changed with the same snapshot version: the request alone changed (0.19.0).
        vault.account.value = vault.account.value!!.copy(
            nameRequest = NameRequestView(seq, "Ada", "King", null, NameRequestState.APPLIED),
        )
        advanceUntilIdle()
        assertEquals(NameRequestState.APPLIED, vm.uiState.value.request!!.state)
        vault.account.value = vault.account.value!!.copy(
            nameRequest = NameRequestView(seq, "Ada", "King", null, NameRequestState.REFUSED, NameRequestView.REASON_INVALID),
        )
        advanceUntilIdle()
        assertEquals(NameRequestView.REASON_INVALID, vm.uiState.value.request!!.reason)
    }

    @Test
    fun wrongEntriesStayOnTheConfirmStep() = runTest {
        vault.nameResults += NameChangeOutcome.BadPin(7)
        vault.nameResults += NameChangeOutcome.BadPassword(6)
        val vm = atConfirm()
        vm.submit()
        advanceUntilIdle()
        assertEquals(ChangeNameStep.CONFIRM, vm.uiState.value.step)
        assertEquals(ChangeNameMessage.BadPin(7), vm.uiState.value.message)
        assertEquals("", vm.uiState.value.pin)
        assertFalse(vm.uiState.value.submitAllowed)
        vm.setPin("246810")
        vm.setPassword("wrong")
        vm.submit()
        advanceUntilIdle()
        assertEquals(ChangeNameMessage.BadPassword(6), vm.uiState.value.message)
    }

    @Test
    fun aBackoffCountsDown() = runTest {
        vault.nameResults += NameChangeOutcome.Backoff(3)
        val vm = atConfirm()
        vm.submit()
        runCurrent()
        assertEquals(3L, vm.uiState.value.waitSeconds)
        vm.setPin("246810")
        vm.setPassword("pw")
        assertFalse(vm.uiState.value.submitAllowed)
        advanceTimeBy(3_100)
        assertEquals(0L, vm.uiState.value.waitSeconds)
        assertTrue(vm.uiState.value.submitAllowed)
    }

    @Test
    fun tooSoonFromTheVaultReturnsToTheNamesWithTheDate() = runTest {
        val after = Instant.parse("2026-11-06T00:00:00Z")
        vault.nameResults += NameChangeOutcome.TooSoon(after)
        val vm = atConfirm()
        vm.submit()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(ChangeNameStep.NAMES, s.step)
        assertTrue(s.tooSoon)
        assertEquals(after, s.allowedAfter)
    }

    @Test
    fun invalidAndFailedAnswers() = runTest {
        vault.nameResults += NameChangeOutcome.Invalid
        val vm = atConfirm()
        vm.submit()
        advanceUntilIdle()
        assertEquals(ChangeNameStep.NAMES, vm.uiState.value.step)
        assertEquals(ChangeNameMessage.Invalid, vm.uiState.value.message)

        vault.nameResults += NameChangeOutcome.Failed(FailureKind.OWNER_CHECK_REQUIRED, "owner_check_required")
        vm.next()
        vm.setPin("246810")
        vm.setPassword("pw")
        vm.submit()
        advanceUntilIdle()
        assertEquals(ChangeNameMessage.Failed(FailureKind.OWNER_CHECK_REQUIRED), vm.uiState.value.message)
    }

    @Test
    fun backAndCancelForgetTheSecrets() = runTest {
        val vm = atConfirm()
        vm.back()
        assertEquals(ChangeNameStep.NAMES, vm.uiState.value.step)
        assertEquals("", vm.uiState.value.pin)
        assertEquals("", vm.uiState.value.password)
        assertEquals("King", vm.uiState.value.last) // the names stay
        vm.next()
        vm.setPin("246810")
        vm.cancel()
        assertEquals("", vm.uiState.value.pin)
        assertNull(vm.uiState.value.message)
    }
}
