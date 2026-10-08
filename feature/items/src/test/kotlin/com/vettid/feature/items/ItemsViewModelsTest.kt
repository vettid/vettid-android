// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.items

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.items.FieldValue
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeItems
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Vault list and an item's detail (VAULT-MESSAGING §10.7): filters, reveal, the critical password path, delete and protection. */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemsViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val items = FakeItems().apply {
        add(FakeItems.item("01P", "Passport", tags = listOf("travel"), category = "identity_document"))
        add(FakeItems.item("01L", "Bank login", Sensitivity.SECRET, tags = listOf("money"), category = "login", fields = listOf("Password" to "hunter2")))
        add(FakeItems.item("01C", "Recovery phrase", Sensitivity.CRITICAL, category = "crypto_wallet", fields = listOf("Words" to "abandon ability")))
    }

    private fun detail(id: String) = ItemDetailViewModel(SavedStateHandle(mapOf(ItemDetailRoute.ARG to id)), items)

    @Test
    fun theCategoryFilterIncludesTheMembersOwnCategories() = runTest {
        // §10.7: any [a-z][a-z0-9_]{0,31} category; the recommended ones first, then the member's own.
        items.add(FakeItems.item("01K", "Coffee card", category = "loyalty_cards"))
        items.add(FakeItems.item("01G", "Gym card", category = "gym"))
        val vm = ItemsViewModel(items)
        advanceUntilIdle()
        assertEquals(listOf("identity_document", "login", "crypto_wallet", "gym", "loyalty_cards"), vm.uiState.value.categories)
        vm.setCategory("loyalty_cards")
        advanceUntilIdle()
        assertEquals(listOf("Coffee card"), vm.uiState.value.visible.map { it.name })
    }

    @Test
    fun theListFiltersOnThePhone() = runTest {
        val vm = ItemsViewModel(items)
        advanceUntilIdle()
        assertTrue("refresh" in items.calls)
        assertEquals(listOf("Bank login", "Passport", "Recovery phrase"), vm.uiState.value.visible.map { it.name })
        assertEquals(listOf("money", "travel"), vm.uiState.value.tags)
        vm.setTag("travel")
        advanceUntilIdle()
        assertEquals(listOf("Passport"), vm.uiState.value.visible.map { it.name })
        vm.setTag("travel") // the selected tag again clears it
        advanceUntilIdle()
        assertNull(vm.uiState.value.filter.tag)
        vm.setSensitivity(Sensitivity.CRITICAL)
        advanceUntilIdle()
        assertEquals(listOf("01C"), vm.uiState.value.visible.map { it.itemId })
        vm.setQuery("bank")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.visible.isEmpty())
        vm.clearFilters()
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.visible.size)
    }

    @Test
    fun aFailedListShowsTheError() = runTest {
        items.fail["refresh"] = VaultFailure(FailureKind.NETWORK)
        val vm = ItemsViewModel(items)
        advanceUntilIdle()
        assertEquals(FailureKind.NETWORK, vm.uiState.value.error)
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun aDataItemShowsItsValues() = runTest {
        val vm = detail("01P")
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.revealed)
        assertEquals(FieldValue.Text("123"), s.item!!.fields.single().value)
    }

    @Test
    fun aSecretItemIsHiddenUntilRevealedAndHiddenAgain() = runTest {
        val vm = detail("01L")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.revealed)
        assertNull(vm.uiState.value.item!!.fields.single().value)
        vm.reveal()
        advanceUntilIdle()
        assertTrue("reveal" in items.calls)
        assertEquals(FieldValue.Text("hunter2"), vm.uiState.value.item!!.fields.single().value)
        vm.toggleShown("f1")
        assertEquals(setOf("f1"), vm.uiState.value.shown)
        vm.hide()
        assertFalse(vm.uiState.value.revealed)
        assertNull(vm.uiState.value.item!!.fields.single().value)
        assertTrue(vm.uiState.value.shown.isEmpty())
    }

    @Test
    fun aCriticalItemOpensOnlyWithTheRightPassword() = runTest {
        val vm = detail("01C")
        advanceUntilIdle()
        vm.reveal() // a critical item is never revealed without the password
        assertEquals(PasswordPurpose.OPEN, vm.uiState.value.prompt?.purpose)
        assertFalse("revealCritical" in items.calls)
        vm.setPassword("wrong")
        vm.submitPassword()
        advanceUntilIdle()
        val p = vm.uiState.value.prompt!!
        assertEquals(FailureKind.BAD_PASSWORD, p.error)
        assertEquals("", p.password)
        assertFalse(vm.uiState.value.revealed)
        vm.setPassword("correct horse")
        vm.submitPassword()
        advanceUntilIdle()
        assertNull(vm.uiState.value.prompt)
        assertEquals(FieldValue.Text("abandon ability"), vm.uiState.value.item!!.fields.single().value)
    }

    @Test
    fun aBackoffKeepsTheSendButtonOff() = runTest {
        val vm = detail("01C")
        advanceUntilIdle()
        vm.open()
        items.fail["revealCritical"] = VaultFailure(FailureKind.BACKOFF, "backoff", retryAfterSeconds = 60)
        vm.setPassword("correct horse")
        vm.submitPassword()
        advanceUntilIdle()
        val p = vm.uiState.value.prompt!!
        assertNotNull(p.retryUntil)
        assertFalse(p.copy(password = "x").canSend(java.time.Instant.now()))
    }

    @Test
    fun deletingAStandardItemNeedsOnlyTheConfirmation() = runTest {
        val vm = detail("01P")
        advanceUntilIdle()
        vm.askDelete()
        assertEquals(DetailDialog.DELETE, vm.uiState.value.dialog)
        vm.confirmDelete()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.deleted)
        assertFalse("01P" in items.stored)
    }

    @Test
    fun deletingACriticalItemNeedsThePassword() = runTest {
        val vm = detail("01C")
        advanceUntilIdle()
        vm.askDelete()
        vm.confirmDelete()
        assertEquals(PasswordPurpose.DELETE, vm.uiState.value.prompt?.purpose)
        assertFalse("delete" in items.calls)
        vm.setPassword("correct horse")
        vm.submitPassword()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.deleted)
    }

    @Test
    fun standardAndSecretSwapWithoutThePassword() = runTest {
        val vm = detail("01P")
        advanceUntilIdle()
        vm.askProtection()
        vm.pickProtection(Sensitivity.SECRET)
        advanceUntilIdle()
        assertEquals(Sensitivity.SECRET, items.stored.getValue("01P").sensitivity)
        assertNull(vm.uiState.value.prompt)
        assertEquals(Sensitivity.SECRET, vm.uiState.value.item!!.sensitivity)
    }

    @Test
    fun movingIntoTheCredentialNeedsThePassword() = runTest {
        val vm = detail("01P")
        advanceUntilIdle()
        vm.pickProtection(Sensitivity.CRITICAL)
        assertEquals(PasswordPurpose.PROTECTION, vm.uiState.value.prompt?.purpose)
        vm.setPassword("correct horse")
        vm.submitPassword()
        advanceUntilIdle()
        assertEquals(Sensitivity.CRITICAL, items.stored.getValue("01P").sensitivity)
    }

    @Test
    fun leavingTheCredentialWarnsFirst() = runTest {
        val vm = detail("01C")
        advanceUntilIdle()
        vm.pickProtection(Sensitivity.DATA)
        assertEquals(DetailDialog.LEAVE_CRITICAL, vm.uiState.value.dialog)
        assertNull(vm.uiState.value.prompt)
        vm.confirmLeaveCritical()
        assertEquals(PasswordPurpose.PROTECTION, vm.uiState.value.prompt?.purpose)
        vm.setPassword("correct horse")
        vm.submitPassword()
        advanceUntilIdle()
        assertEquals(Sensitivity.DATA, items.stored.getValue("01C").sensitivity)
    }

    @Test
    fun aDeletedItemIsMissing() = runTest {
        val vm = detail("01GONE")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.missing)
        assertNull(vm.uiState.value.error)
    }
}
