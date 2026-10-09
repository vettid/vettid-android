// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength", "DestructuringDeclarationWithTooManyEntries", "LargeClass")

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
    private val sharing = com.vettid.core.testing.FakeSharing()
    private val social = com.vettid.core.testing.FakeSocial()
    private val context = RuntimeEnvironment.getApplication()

    private fun vm(itemId: String? = null, template: String? = null) = ItemEditViewModel(
        SavedStateHandle(listOfNotNull(itemId?.let { ItemEditRoute.ARG_ITEM to it }, template?.let { ItemEditRoute.ARG_TEMPLATE to it }).toMap()),
        items,
        context,
        sharing,
        social,
    )

    @Test
    fun aTemplatePrefillsTheDraft() {
        val s = vm(template = "passport").uiState.value
        // Owner request 2026-10-08: the template's name is the placeholder, never pre-typed.
        assertEquals("", s.draft.name)
        assertEquals("Passport", s.nameHint)
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
            val d = t.draft(context).copy(name = context.getString(t.name))
            assertTrue(t.id, com.vettid.core.data.items.ItemChecks.check(d).ok)
            assertTrue(t.id, d.fields.size <= 16 || t.sensitivity != Sensitivity.CRITICAL)
        }
    }

    @Test
    fun problemsShowOnlyAfterTheFirstSaveAndNothingIsSent() = runTest {
        // A blank item (no template) still needs a name.
        val vm = vm()
        vm.addField("Expires", FieldKinds.DATE)
        assertFalse(vm.uiState.value.showErrors)
        vm.setFieldText(0, "1st of May")
        vm.save()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.showErrors)
        assertTrue(DraftProblem.NAME_EMPTY in s.check.problems)
        assertTrue(DraftProblem.VALUE_INVALID in s.check.fieldProblems.getValue(0))
        assertFalse("create" in items.calls)
    }

    @Test
    fun aStandardItemIsCreated() = runTest {
        val vm = vm(template = "postal_address")
        vm.setFieldAddress(0, AddressValue(city = "London", country = "GB"))
        vm.setTagInput(" Family ")
        vm.addTag()
        vm.save()
        advanceUntilIdle()
        val id = vm.uiState.value.savedId!!
        val saved = items.stored.getValue(id)
        assertEquals(listOf("family"), saved.tags)
        assertEquals("postal_address", saved.template)
        assertEquals(FieldValue.Address(AddressValue(city = "London", country = "GB")), saved.fields.single().value)
        assertFalse(vm.uiState.value.inProfile)
    }

    @Test
    fun aPhoneIsStoredInInternationalFormatAndAnUnknownOneAsTyped() = runTest {
        // Owner request 2026-10-09: the input holds the dialable characters; saving formats, never blocks.
        val vm = vm(template = "phone_number")
        vm.setFieldText(0, PhoneInput.raw("+44 20 7946 0958"))
        vm.save()
        advanceUntilIdle()
        assertEquals(FieldValue.Text("+44 20 7946 0958"), items.stored.getValue(vm.uiState.value.savedId!!).fields.single().value)

        val unknown = vm(template = "phone_number")
        unknown.setFieldText(0, "55512")
        unknown.save()
        advanceUntilIdle()
        // Kept as the member saw it while typing.
        assertEquals(FieldValue.Text("555-12"), items.stored.getValue(unknown.uiState.value.savedId!!).fields.single().value)
    }

    @Test
    fun noTemplateSuggestsAReservedTag() {
        // VAULT-ITEMS 0.1.1 (owner decision 2026-10-08): the shared profile holds only what the member tags.
        ItemTemplates.all.forEach { t ->
            assertTrue(t.id, t.tags.none { it.startsWith("@") })
            assertFalse(t.id, vm(template = t.id).uiState.value.inProfile)
        }
    }

    @Test
    fun contactInformationIsOneItemPerContactPoint() {
        assertNull(ItemTemplates.template("contact_card"))
        val kinds = mapOf("email_address" to FieldKinds.EMAIL, "phone_number" to FieldKinds.PHONE, "postal_address" to FieldKinds.ADDRESS, "website" to FieldKinds.URL)
        kinds.forEach { (id, kind) ->
            val d = vm(template = id).uiState.value.draft
            assertEquals(id, d.template)
            assertEquals("contact", d.category)
            assertEquals(Sensitivity.DATA, d.sensitivity)
            assertEquals(emptyList<String>(), d.tags)
            assertEquals(listOf(kind), d.fields.map { it.kind })
        }
    }

    @Test
    fun anOldContactCardStillEdits() = runTest {
        items.add(FakeItems.item("01K", "Contact details", category = "contact", tags = listOf("@profile"), fields = listOf("Email" to "sam@example.org")).copy(template = "contact_card"))
        val vm = vm(itemId = "01K")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.inProfile)
        vm.setFieldText(0, "sam@example.com")
        vm.save()
        advanceUntilIdle()
        val stored = items.stored.getValue("01K")
        assertEquals("contact_card", stored.template)
        assertEquals(listOf("@profile"), stored.tags)
        assertEquals(FieldValue.Text("sam@example.com"), stored.fields.single().value)
    }

    @Test
    fun theSharedProfileIsAChoiceForStandardItemsOnly() {
        val vm = vm(template = "email_address")
        vm.setInProfile(true)
        assertTrue(vm.uiState.value.inProfile)
        vm.setSensitivity(Sensitivity.SECRET)
        vm.save()
        assertTrue(DraftProblem.PROFILE_NOT_DATA in vm.uiState.value.check.problems)
        vm.setInProfile(false)
        assertFalse(vm.uiState.value.inProfile)
        assertTrue(vm.uiState.value.check.ok)
        vm.setInProfile(true) // a secret item cannot carry it
        assertFalse(vm.uiState.value.inProfile)
        vm.setSensitivity(Sensitivity.DATA)
        vm.setInProfile(true)
        assertEquals(listOf("@profile"), vm.uiState.value.draft.tags)
    }

    @Test
    fun aBlankItemStartsWithoutFields() {
        val d = vm().uiState.value.draft
        assertEquals(emptyList<com.vettid.core.data.items.DraftField>(), d.fields)
        assertNull(d.template)
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
    fun editingACriticalItemKeepsItsValuesAndTakesOnePassword() = runTest {
        // VAULT-MESSAGING 0.21.0 §10.7 Kept values: nothing is opened to edit; the save is one credential operation.
        items.add(FakeItems.item("01C", "Phrase", Sensitivity.CRITICAL, fields = listOf("Words" to "a b", "Passphrase" to "p")).copy(notes = "cold"))
        val vm = vm(itemId = "01C")
        advanceUntilIdle()
        val s = vm.uiState.value
        assertNull(s.prompt)
        assertTrue(s.draft.fields.all { it.kept && it.text.isEmpty() })
        assertTrue(s.draft.keepNotes)
        vm.setFieldLabel(0, "Recovery words")
        vm.setFieldText(1, "new passphrase")
        vm.save()
        assertEquals(PasswordPurpose.SAVE, vm.uiState.value.prompt?.purpose)
        vm.setPassword("correct horse")
        vm.submitPassword()
        advanceUntilIdle()
        assertEquals("01C", vm.uiState.value.savedId)
        assertEquals(listOf("get", "updateCritical"), items.calls.filter { it != "refresh" })
        val sent = items.lastDraft!!
        assertTrue(sent.fields[0].kept)
        assertFalse(sent.fields[1].kept)
        val stored = items.stored.getValue("01C")
        assertEquals(FieldValue.Text("a b"), stored.fields[0].value)
        assertEquals("Recovery words", stored.fields[0].label)
        assertEquals(FieldValue.Text("new passphrase"), stored.fields[1].value)
        assertEquals("cold", stored.notes)
    }

    @Test
    fun editingASecretItemRevealsNothing() = runTest {
        items.add(FakeItems.item("01S", "Login", Sensitivity.SECRET, fields = listOf("Password" to "hunter2")))
        val vm = vm(itemId = "01S")
        advanceUntilIdle()
        assertFalse("reveal" in items.calls)
        assertTrue(vm.uiState.value.draft.fields.single().kept)
        assertEquals("", vm.uiState.value.draft.fields.single().text)
        vm.setFieldText(0, "hunter3")
        assertFalse(vm.uiState.value.draft.fields.single().kept)
        vm.save()
        advanceUntilIdle()
        assertFalse("reveal" in items.calls)
        assertEquals(FieldValue.Text("hunter3"), items.stored.getValue("01S").fields.single().value)
    }

    @Test
    fun keptNotesAreReplacedByTypingOrRemoved() = runTest {
        items.add(FakeItems.item("01S", "Login", Sensitivity.SECRET).copy(notes = "branch"))
        val vm = vm(itemId = "01S")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.draft.keepNotes)
        vm.removeNotes()
        assertFalse(vm.uiState.value.draft.keepNotes)
        vm.save()
        advanceUntilIdle()
        assertNull(items.stored.getValue("01S").notes)
    }

    @Test
    fun theRoomLeftComesFromTheStoredSize() = runTest {
        items.add(FakeItems.item("01S", "Login", Sensitivity.SECRET, fields = listOf("Password" to "x".repeat(5_000))))
        val expected = com.vettid.core.data.items.ItemChecks.check(
            com.vettid.core.data.items.ItemDraft.of(items.stored.getValue("01S")),
        ).size
        val vm = vm(itemId = "01S")
        advanceUntilIdle()
        val c = vm.uiState.value.check
        assertEquals(expected, c.size)
        assertTrue(c.exact)
        assertEquals(com.vettid.core.data.items.ItemChecks.MAX_ITEM_BYTES - expected, c.roomLeft)
        // An older vault gives no size: nothing is claimed about the room left.
        items.reportSize = false
        val older = vm(itemId = "01S")
        advanceUntilIdle()
        assertNull(older.uiState.value.check.roomLeft)
    }

    @Test
    fun aNamedLimitIsKept() = runTest {
        items.add(FakeItems.item("01P", "Passport"))
        val vm = vm(itemId = "01P")
        advanceUntilIdle()
        val limit = com.vettid.core.data.vault.VaultLimit("share_pending", 4_096)
        items.fail["update"] = VaultFailure(FailureKind.LIMIT, "limit", limit = limit)
        vm.setTagInput("medical")
        vm.addTag()
        vm.save()
        advanceUntilIdle()
        assertEquals(FailureKind.LIMIT, vm.uiState.value.error)
        assertEquals(limit, vm.uiState.value.limit)
        vm.dismissError()
        assertNull(vm.uiState.value.limit)
    }

    @Test
    fun aFileFieldIsNeverOffered() {
        val vm = vm()
        val before = vm.uiState.value.draft.fields.size
        vm.addField("Scan", FieldKinds.FILE)
        assertEquals(before, vm.uiState.value.draft.fields.size)
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
        vm.addField("Text", FieldKinds.TEXT)
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
    fun theSharingNoticeIsTheVaultsDryRun() = runTest {
        social.connections.value = listOf(
            com.vettid.core.data.social.ConnectionInfo("c1", "", com.vettid.core.data.social.ConnectionState.ACTIVE, firstName = "Dana", lastName = "Lee"),
            com.vettid.core.data.social.ConnectionInfo("c2", "", com.vettid.core.data.social.ConnectionState.ACTIVE, firstName = "Jo", lastName = "Park"),
        )
        items.effect = com.vettid.core.data.items.ShareEffect(
            shares = listOf(
                com.vettid.core.data.items.EffectShare("r1", com.vettid.core.data.items.ShareSubject("c1"), com.vettid.core.data.items.ShareMode.AUTO),
                com.vettid.core.data.items.EffectShare("r9", com.vettid.core.data.items.ShareSubject(agentId = "a1"), com.vettid.core.data.items.ShareMode.ASK),
            ),
            withdrawals = listOf(com.vettid.core.data.items.EffectWithdrawal("r2", com.vettid.core.data.items.ShareSubject("c2"), "included")),
        )
        // The local rules would say otherwise: they are not read while the vault answers the dry run.
        sharing.rulesStored += com.vettid.core.data.items.ShareRule("r3", 1, "c2", tags = listOf("medical"))
        val vm = vm(template = "allergies")
        advanceUntilIdle()
        assertEquals(listOf(listOf("medical")), items.effectAsked)
        assertEquals(
            listOf(ShareImpact("Dana Lee", com.vettid.core.data.items.ShareMode.AUTO), ShareImpact("Jo Park", com.vettid.core.data.items.ShareMode.ASK, withdrawn = true)),
            vm.uiState.value.shareImpact,
        )
        assertFalse("rules" in sharing.calls)
        // A change of tags asks again (once the member pauses); the same tags do not.
        vm.setTagInput("travel")
        vm.addTag()
        vm.setName("Allergy list")
        advanceUntilIdle()
        assertEquals(listOf(listOf("medical"), listOf("medical", "travel")), items.effectAsked)
    }

    @Test
    fun anExistingItemsUnchangedTagsAskNothing() = runTest {
        items.add(FakeItems.item("01P", "Passport", tags = listOf("travel")))
        val vm = vm(itemId = "01P")
        advanceUntilIdle()
        assertTrue(items.effectAsked.isEmpty())
        vm.setTagInput("id")
        vm.addTag()
        advanceUntilIdle()
        assertEquals(listOf(listOf("id", "travel")), items.effectAsked)
    }

    @Test
    fun anOlderVaultsRulesShowWhatSavingWouldShare() = runTest {
        // Before 0.21.0 the vault refuses the dry run (bad_request): the app matches the rules itself.
        items.fail["shareEffect"] = VaultFailure(FailureKind.OTHER, "bad_request")
        social.connections.value = listOf(
            com.vettid.core.data.social.ConnectionInfo("c1", "", com.vettid.core.data.social.ConnectionState.ACTIVE, firstName = "Dana", lastName = "Lee"),
        )
        sharing.rulesStored += com.vettid.core.data.items.ShareRule("r1", 1, "c1", tags = listOf("medical"))
        sharing.rulesStored += com.vettid.core.data.items.ShareRule(
            "r2", 1, "c1", tags = listOf("medical", "travel"), match = com.vettid.core.data.items.TagMatch.ALL,
            mode = com.vettid.core.data.items.ShareMode.AUTO,
        )
        val vm = vm(template = "allergies")
        advanceUntilIdle()
        assertEquals(listOf("Dana Lee"), vm.uiState.value.shareImpact.map { it.connectionName })
        assertEquals(com.vettid.core.data.items.ShareMode.ASK, vm.uiState.value.shareImpact.single().mode)
        vm.setTagInput("travel")
        vm.addTag()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.shareImpact.size)
        vm.removeTag("medical")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.shareImpact.isEmpty())
        // The fallback stays: no second dry run.
        assertEquals(1, items.calls.count { it == "shareEffect" })
    }

    @Test
    fun anInvalidTagStaysInTheField() {
        val vm = vm()
        vm.setTagInput("-nope")
        vm.addTag()
        assertEquals("-nope", vm.uiState.value.tagInput)
        assertTrue(vm.uiState.value.draft.tags.isEmpty())
    }

    @Test
    fun addingAFieldAsksForItsLabelAndTypeFirst() {
        // Value-first fields (owner feedback 2026-10-08): the label is asked for once, then the value is typed.
        val vm = vm()
        val before = vm.uiState.value.draft.fields.size
        vm.askAddField()
        assertEquals(EditDialog.AddField(), vm.uiState.value.dialog)
        vm.confirmDialog() // no label: it stays open, nothing is added
        assertEquals(before, vm.uiState.value.draft.fields.size)
        vm.setDialogText("é".repeat(33)) // 66 bytes: over §10.7's 64
        vm.confirmDialog()
        assertEquals(before, vm.uiState.value.draft.fields.size)
        vm.setDialogText("  Cardholder ")
        vm.setDialogKind(FieldKinds.FILE) // never offered
        assertEquals(FieldKinds.TEXT, (vm.uiState.value.dialog as EditDialog.AddField).kind)
        vm.setDialogKind(FieldKinds.DATE)
        vm.confirmDialog()
        val s = vm.uiState.value
        assertNull(s.dialog)
        assertEquals(before + 1, s.draft.fields.size)
        assertEquals("Cardholder", s.draft.fields.last().label)
        assertEquals(FieldKinds.DATE, s.draft.fields.last().kind)
        assertEquals(before, s.focusField)
        vm.fieldFocused()
        assertNull(vm.uiState.value.focusField)
    }

    @Test
    fun aDismissedAddDialogAddsNothing() {
        val vm = vm()
        val before = vm.uiState.value.draft.fields
        vm.askAddField()
        vm.setDialogText("PIN")
        vm.dismissDialog()
        assertNull(vm.uiState.value.dialog)
        assertEquals(before, vm.uiState.value.draft.fields)
    }

    @Test
    fun aFieldIsRenamedThroughItsDialog() {
        val vm = vm(template = "passport")
        vm.askRenameField(1)
        assertEquals(EditDialog.RenameField(1, "Full name"), vm.uiState.value.dialog)
        vm.setDialogText("")
        vm.confirmDialog()
        assertEquals("Full name", vm.uiState.value.draft.fields[1].label)
        vm.setDialogText("Name on passport ")
        vm.confirmDialog()
        assertNull(vm.uiState.value.dialog)
        assertEquals("Name on passport", vm.uiState.value.draft.fields[1].label)
        assertTrue(vm.uiState.value.dirty)
    }

    @Test
    fun onlyAnUnsavedFieldChangesItsType() = runTest {
        items.add(FakeItems.item("01S", "Login", Sensitivity.SECRET, fields = listOf("Password" to "hunter2")))
        val vm = vm(itemId = "01S")
        advanceUntilIdle()
        vm.askFieldKind(0) // saved (§10.7): its kind stays
        assertNull(vm.uiState.value.dialog)
        vm.askAddField()
        vm.setDialogText("Site")
        vm.confirmDialog()
        vm.askFieldKind(1)
        assertEquals(EditDialog.FieldKind(1, FieldKinds.TEXT), vm.uiState.value.dialog)
        vm.setDialogKind(FieldKinds.URL)
        vm.confirmDialog()
        assertEquals(FieldKinds.URL, vm.uiState.value.draft.fields[1].kind)
        assertTrue(vm.uiState.value.draft.fields[0].kept)
    }

    @Test
    fun aNewCategoryIsDerivedFromItsName() {
        val vm = vm()
        vm.askNewCategory()
        vm.setDialogText("2fa codes")
        vm.confirmDialog() // no identifier: it stays open
        assertEquals(EditDialog.NewCategory("2fa codes"), vm.uiState.value.dialog)
        vm.setDialogText("Loyalty cards")
        vm.confirmDialog()
        assertNull(vm.uiState.value.dialog)
        assertEquals("loyalty_cards", vm.uiState.value.draft.category)
        assertEquals(listOf("loyalty_cards"), vm.uiState.value.pickerCustoms)
        assertFalse(DraftProblem.CATEGORY_INVALID in vm.uiState.value.check.problems)
        // A name that is a recommended category selects it.
        vm.askNewCategory()
        vm.setDialogText("Payment card")
        vm.confirmDialog()
        assertEquals("payment_card", vm.uiState.value.draft.category)
        assertEquals(emptyList<String>(), vm.uiState.value.pickerCustoms)
    }

    @Test
    fun thePickerOffersTheMembersOwnCategories() = runTest {
        items.add(FakeItems.item("01G", "Gym card", category = "gym"))
        items.add(FakeItems.item("01L", "Coffee card", category = "loyalty_cards"))
        items.add(FakeItems.item("01M", "Bonus card", category = "loyalty_cards"))
        items.add(FakeItems.item("01P", "Passport", category = "identity_document"))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf("gym", "loyalty_cards"), vm.uiState.value.customCategories)
        items.add(FakeItems.item("01T", "Ticket", category = "tickets"))
        advanceUntilIdle()
        assertEquals(listOf("gym", "loyalty_cards", "tickets"), vm.uiState.value.pickerCustoms)
    }

    // --- owner requests 2026-10-08: no pre-typed example text; the protection changes in the editor ---

    @Test
    fun anEmptyNameSavesUnderTheTemplatesName() = runTest {
        val vm = vm(template = "payment_card")
        val s = vm.uiState.value
        assertEquals("", s.draft.name)
        assertEquals("Payment card", s.nameHint)
        assertTrue(s.check.ok) // the default name counts: no NAME_EMPTY
        vm.save()
        advanceUntilIdle()
        assertEquals("Payment card", items.stored.getValue(vm.uiState.value.savedId!!).name)
    }

    @Test
    fun aTypedNameWins() = runTest {
        val vm = vm(template = "payment_card")
        vm.setName("Visa")
        vm.save()
        advanceUntilIdle()
        assertEquals("Visa", items.stored.getValue(vm.uiState.value.savedId!!).name)
    }

    @Test
    fun aBlankItemStillNeedsAName() = runTest {
        val vm = vm()
        assertNull(vm.uiState.value.nameHint)
        vm.save()
        advanceUntilIdle()
        assertTrue(DraftProblem.NAME_EMPTY in vm.uiState.value.check.problems)
        assertFalse("create" in items.calls)
    }

    @Test
    fun noTemplatePreTypesAnything() {
        ItemTemplates.all.forEach { t ->
            val s = vm(template = t.id).uiState.value
            assertEquals(t.id, "", s.draft.name)
            assertEquals(t.id, "", s.draft.notes)
            assertTrue(t.id, s.draft.fields.all { it.text.isEmpty() && it.address == AddressValue() && !it.kept })
            assertEquals(t.id, "", s.tagInput)
        }
        val vm = vm(template = "login")
        vm.askAddField()
        assertEquals(EditDialog.AddField(), vm.uiState.value.dialog)
        assertEquals("", (vm.uiState.value.dialog as EditDialog.AddField).label)
        vm.dismissDialog()
        vm.askNewCategory()
        assertEquals("", (vm.uiState.value.dialog as EditDialog.NewCategory).name)
    }

    @Test
    fun aCardsExpiryIsAMonthAndYear() = runTest {
        // §10.7 allows `YYYY-MM`: a card's expiry is entered and stored so.
        val vm = vm(template = "payment_card")
        val i = vm.uiState.value.draft.fields.indexOfFirst { it.label == "Expires" }
        assertTrue(vm.uiState.value.draft.fields[i].monthYear)
        assertFalse(vm(template = "passport").uiState.value.draft.fields.any { it.monthYear })
        vm.setFieldText(i, "2031-04")
        vm.save()
        advanceUntilIdle()
        val id = vm.uiState.value.savedId!!
        assertEquals(FieldValue.Text("2031-04"), items.stored.getValue(id).fields[i].value)
        // Edited again (its values kept: a secret item), it is still a month and year.
        val again = vm(itemId = id)
        advanceUntilIdle()
        assertTrue(again.uiState.value.draft.fields[i].kept)
        assertTrue(again.uiState.value.draft.fields[i].monthYear)
    }

    private fun editing(id: String, sensitivity: Sensitivity, tags: List<String> = emptyList()): ItemEditViewModel {
        items.add(FakeItems.item(id, "Item", sensitivity, tags = tags))
        return vm(itemId = id)
    }

    @Test
    fun standardToSecretNeedsNothingMore() = runTest {
        val vm = editing("01A", Sensitivity.DATA)
        advanceUntilIdle()
        vm.setSensitivity(Sensitivity.SECRET)
        assertEquals(Sensitivity.SECRET, vm.uiState.value.protection)
        assertEquals(Sensitivity.DATA, vm.uiState.value.draft.sensitivity)
        assertTrue(vm.uiState.value.dirty)
        vm.save()
        advanceUntilIdle()
        assertNull(vm.uiState.value.prompt)
        assertEquals("01A", vm.uiState.value.savedId)
        // Only the protection changed: no content is sent.
        assertEquals(listOf("get", "setSensitivity"), items.calls.filter { it != "refresh" && it != "shareEffect" })
        assertEquals(Sensitivity.SECRET, items.stored.getValue("01A").sensitivity)
    }

    @Test
    fun theContentIsSavedBeforeTheProtection() = runTest {
        val vm = editing("01A", Sensitivity.SECRET)
        advanceUntilIdle()
        vm.setName("Renamed")
        vm.setSensitivity(Sensitivity.DATA)
        vm.save()
        advanceUntilIdle()
        assertEquals(listOf("get", "update", "setSensitivity"), items.calls.filter { it != "refresh" && it != "shareEffect" })
        val stored = items.stored.getValue("01A")
        assertEquals("Renamed", stored.name)
        assertEquals(Sensitivity.DATA, stored.sensitivity)
    }

    @Test
    fun movingToCriticalAsksForThePasswordOnce() = runTest {
        val vm = editing("01A", Sensitivity.DATA)
        advanceUntilIdle()
        vm.setName("Seed")
        vm.setSensitivity(Sensitivity.CRITICAL)
        vm.save()
        assertEquals(PasswordPurpose.SAVE, vm.uiState.value.prompt?.purpose)
        assertFalse("update" in items.calls)
        vm.setPassword("correct horse")
        vm.submitPassword()
        advanceUntilIdle()
        assertEquals("01A", vm.uiState.value.savedId)
        assertEquals(listOf("update", "setSensitivity"), items.calls.filter { it == "update" || it == "setSensitivity" })
        assertEquals(Sensitivity.CRITICAL, items.stored.getValue("01A").sensitivity)
        assertEquals("Seed", items.stored.getValue("01A").name)
    }

    @Test
    fun leavingCriticalWarnsFirst() = runTest {
        val vm = editing("01C", Sensitivity.CRITICAL)
        advanceUntilIdle()
        vm.setSensitivity(Sensitivity.SECRET)
        vm.save()
        assertTrue(vm.uiState.value.confirmLeaveCritical)
        assertNull(vm.uiState.value.prompt)
        vm.dismissLeaveCritical()
        assertFalse(vm.uiState.value.confirmLeaveCritical)
        vm.save()
        vm.confirmLeaveCritical()
        assertEquals(PasswordPurpose.SAVE, vm.uiState.value.prompt?.purpose)
        vm.setPassword("correct horse")
        vm.submitPassword()
        advanceUntilIdle()
        assertEquals(Sensitivity.SECRET, items.stored.getValue("01C").sensitivity)
        // Nothing typed: only the protection operation.
        assertFalse("updateCritical" in items.calls)
    }

    @Test
    fun aRefusedProtectionChangeKeepsTheSavedContentAndRetriesOnlyIt() = runTest {
        val vm = editing("01A", Sensitivity.DATA)
        advanceUntilIdle()
        vm.setName("Renamed")
        vm.addField("Code", FieldKinds.TEXT)
        vm.setFieldText(1, "1234")
        vm.setSensitivity(Sensitivity.CRITICAL)
        val limit = com.vettid.core.data.vault.VaultLimit("critical_items", 1_000)
        items.fail["setSensitivity"] = VaultFailure(FailureKind.LIMIT, "limit", limit = limit)
        vm.save()
        vm.setPassword("correct horse")
        vm.submitPassword()
        advanceUntilIdle()
        var s = vm.uiState.value
        // The content is stored; the editor shows it as stored, with the new protection still picked.
        assertEquals("Renamed", items.stored.getValue("01A").name)
        assertEquals(Sensitivity.DATA, items.stored.getValue("01A").sensitivity)
        assertNull(s.savedId)
        assertEquals(FailureKind.LIMIT, s.error)
        assertEquals(limit, s.limit)
        assertTrue(s.savedButProtection)
        assertEquals(Sensitivity.CRITICAL, s.protectionTo)
        assertTrue(s.draft.fields.all { it.fieldId != null })
        // Saving again only retries the protection (no duplicate fields).
        val updates = items.calls.count { it == "update" }
        vm.save()
        vm.setPassword("correct horse")
        vm.submitPassword()
        advanceUntilIdle()
        s = vm.uiState.value
        assertEquals("01A", s.savedId)
        assertEquals(updates, items.calls.count { it == "update" })
        assertEquals(2, items.stored.getValue("01A").fields.size)
        assertEquals(Sensitivity.CRITICAL, items.stored.getValue("01A").sensitivity)
    }

    @Test
    fun aWrongPasswordForACriticalContentSaveSavesNothing() = runTest {
        val vm = editing("01C", Sensitivity.CRITICAL)
        advanceUntilIdle()
        vm.setName("Renamed")
        vm.setSensitivity(Sensitivity.SECRET)
        vm.save()
        vm.confirmLeaveCritical()
        vm.setPassword("wrong")
        vm.submitPassword()
        advanceUntilIdle()
        assertEquals(FailureKind.BAD_PASSWORD, vm.uiState.value.prompt?.error)
        assertFalse(vm.uiState.value.savedButProtection)
        assertEquals(Sensitivity.CRITICAL, items.stored.getValue("01C").sensitivity)
        assertFalse("setSensitivity" in items.calls)
        vm.setPassword("correct horse")
        vm.submitPassword()
        advanceUntilIdle()
        assertEquals("Renamed", items.stored.getValue("01C").name)
        assertEquals(Sensitivity.SECRET, items.stored.getValue("01C").sensitivity)
    }

    @Test
    fun aSharedProfileItemStaysStandard() = runTest {
        val vm = editing("01P", Sensitivity.DATA, tags = listOf("@profile"))
        advanceUntilIdle()
        vm.setSensitivity(Sensitivity.SECRET)
        assertNull(vm.uiState.value.protectionTo)
        vm.setInProfile(false)
        vm.setSensitivity(Sensitivity.SECRET)
        assertEquals(Sensitivity.SECRET, vm.uiState.value.protectionTo)
        vm.setSensitivity(Sensitivity.DATA) // back to what it is: no change
        assertNull(vm.uiState.value.protectionTo)
    }

    @Test
    fun aCriticalTargetChecksTheCriticalSizeLimit() = runTest {
        items.add(FakeItems.item("01B", "Big", fields = listOf("Text" to "x".repeat(13_000))))
        val vm = vm(itemId = "01B")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.check.ok)
        vm.setSensitivity(Sensitivity.CRITICAL)
        assertTrue(DraftProblem.TOO_LARGE in vm.uiState.value.check.problems)
        vm.save()
        assertNull(vm.uiState.value.prompt)
    }
}
