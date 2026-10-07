// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength", "DestructuringDeclarationWithTooManyEntries")

package com.vettid.feature.items

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.items.AddressValue
import com.vettid.core.data.items.DraftProblem
import com.vettid.core.data.items.FieldKinds
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Adding from templates and editing (§10.7): checks before sending, the critical password path, conflicts. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ItemEditViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val items = FakeItems()
    private val context = RuntimeEnvironment.getApplication()

    private fun vm(itemId: String? = null, template: String? = null) = ItemEditViewModel(
        SavedStateHandle(listOfNotNull(itemId?.let { ItemEditRoute.ARG_ITEM to it }, template?.let { ItemEditRoute.ARG_TEMPLATE to it }).toMap()),
        items,
        context,
    )

    @Test
    fun aTemplatePrefillsTheDraft() {
        val s = vm(template = "passport").uiState.value
        assertEquals("Passport", s.draft.name)
        assertEquals("identity_document", s.draft.category)
        assertEquals("passport", s.draft.template)
        assertEquals(listOf("identity", "travel"), s.draft.tags)
        assertEquals(7, s.draft.fields.size)
        assertEquals(FieldKinds.DATE, s.draft.fields[3].kind)
        assertTrue(s.isNew)
        assertFalse(s.dirty)
    }

    @Test
    fun templatesSuggestTheirSensitivity() {
        assertEquals(Sensitivity.SECRET, vm(template = "login").uiState.value.draft.sensitivity)
        assertEquals(Sensitivity.CRITICAL, vm(template = "recovery_phrase").uiState.value.draft.sensitivity)
        assertEquals(Sensitivity.DATA, vm().uiState.value.draft.sensitivity)
    }

    @Test
    fun everyRegistryTemplatePassesTheChecksOnceNamed() {
        ItemTemplates.all.forEach { t ->
            val d = t.draft(context)
            assertTrue(t.id, com.vettid.core.data.items.ItemChecks.check(d).ok)
            assertTrue(t.id, d.fields.size <= 16 || t.sensitivity != Sensitivity.CRITICAL)
        }
    }

    @Test
    fun problemsShowOnlyAfterTheFirstSaveAndNothingIsSent() = runTest {
        val vm = vm(template = "passport")
        vm.setName("")
        assertFalse(vm.uiState.value.showErrors)
        vm.setFieldText(3, "1st of May")
        vm.save()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.showErrors)
        assertTrue(DraftProblem.NAME_EMPTY in s.check.problems)
        assertTrue(DraftProblem.VALUE_INVALID in s.check.fieldProblems.getValue(3))
        assertFalse("create" in items.calls)
    }

    @Test
    fun aStandardItemIsCreated() = runTest {
        val vm = vm(template = "contact_card")
        vm.setFieldText(0, "ada@example.com")
        vm.setFieldAddress(2, AddressValue(city = "London", country = "GB"))
        vm.addTag()
        vm.setTagInput(" Family ")
        vm.addTag()
        vm.save()
        advanceUntilIdle()
        val id = vm.uiState.value.savedId!!
        val saved = items.stored.getValue(id)
        assertEquals(listOf("@profile", "family"), saved.tags)
        assertEquals(FieldValue.Address(AddressValue(city = "London", country = "GB")), saved.fields[2].value)
        assertTrue(vm.uiState.value.inProfile)
    }

    @Test
    fun theProfileTagKeepsANewItemStandard() {
        val vm = vm(template = "contact_card")
        vm.setSensitivity(Sensitivity.SECRET)
        vm.save()
        assertTrue(DraftProblem.PROFILE_NOT_DATA in vm.uiState.value.check.problems)
        vm.removeTag("@profile")
        assertFalse(vm.uiState.value.inProfile)
        assertTrue(vm.uiState.value.check.ok)
    }

    @Test
    fun aCriticalItemIsSavedWithThePassword() = runTest {
        val vm = vm(template = "recovery_phrase")
        vm.setFieldText(1, "abandon ability able")
        vm.save()
        assertEquals(PasswordPurpose.SAVE, vm.uiState.value.prompt?.purpose)
        assertTrue(items.calls.isEmpty())
        vm.setPassword("wrong")
        vm.submitPassword()
        advanceUntilIdle()
        assertEquals(FailureKind.BAD_PASSWORD, vm.uiState.value.prompt?.error)
        vm.setPassword("correct horse")
        vm.submitPassword()
        advanceUntilIdle()
        val id = vm.uiState.value.savedId!!
        assertEquals(Sensitivity.CRITICAL, items.stored.getValue(id).sensitivity)
        assertNull(vm.uiState.value.prompt)
    }

    @Test
    fun editingACriticalItemOpensItFirstUnlessHandedOver() = runTest {
        items.add(FakeItems.item("01C", "Phrase", Sensitivity.CRITICAL, fields = listOf("Words" to "a b")))
        val vm = vm(itemId = "01C")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.needsOpen)
        assertEquals(PasswordPurpose.OPEN, vm.uiState.value.prompt?.purpose)
        vm.setPassword("correct horse")
        vm.submitPassword()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.needsOpen)
        assertEquals("a b", vm.uiState.value.draft.fields.single().text)
        items.keepOpened(items.stored.getValue("01C"))
        val handed = vm(itemId = "01C")
        advanceUntilIdle()
        assertFalse(handed.uiState.value.needsOpen)
        assertNull(handed.uiState.value.prompt)
    }

    @Test
    fun editingASecretItemRevealsIt() = runTest {
        items.add(FakeItems.item("01S", "Login", Sensitivity.SECRET, fields = listOf("Password" to "hunter2")))
        val vm = vm(itemId = "01S")
        advanceUntilIdle()
        assertTrue("reveal" in items.calls)
        assertEquals("hunter2", vm.uiState.value.draft.fields.single().text)
        vm.setSensitivity(Sensitivity.DATA) // only for a new item
        assertEquals(Sensitivity.SECRET, vm.uiState.value.draft.sensitivity)
        vm.setFieldText(0, "hunter3")
        vm.save()
        advanceUntilIdle()
        assertEquals(FieldValue.Text("hunter3"), items.stored.getValue("01S").fields.single().value)
    }

    @Test
    fun aConflictIsShown() = runTest {
        items.add(FakeItems.item("01P", "Passport"))
        val vm = vm(itemId = "01P")
        advanceUntilIdle()
        items.fail["update"] = VaultFailure(FailureKind.CONFLICT, "conflict")
        vm.setName("Passport (old)")
        vm.save()
        advanceUntilIdle()
        assertEquals(FailureKind.CONFLICT, vm.uiState.value.error)
        assertNull(vm.uiState.value.savedId)
    }

    @Test
    fun fieldsAreAddedMovedAndRemoved() {
        val vm = vm()
        vm.addField("PIN", FieldKinds.PASSWORD)
        vm.addField("Site", FieldKinds.URL)
        vm.moveField(2, -1)
        assertEquals(listOf("Text", "Site", "PIN"), vm.uiState.value.draft.fields.map { it.label })
        vm.moveField(0, -1) // out of range: nothing moves
        vm.removeField(0)
        assertEquals(listOf("Site", "PIN"), vm.uiState.value.draft.fields.map { it.label })
        vm.setFieldKind(0, FieldKinds.EMAIL)
        assertEquals(FieldKinds.EMAIL, vm.uiState.value.draft.fields[0].kind)
        assertTrue(vm.uiState.value.dirty)
    }

    @Test
    fun anInvalidTagStaysInTheField() {
        val vm = vm()
        vm.setTagInput("-nope")
        vm.addTag()
        assertEquals("-nope", vm.uiState.value.tagInput)
        assertTrue(vm.uiState.value.draft.tags.isEmpty())
    }
}
