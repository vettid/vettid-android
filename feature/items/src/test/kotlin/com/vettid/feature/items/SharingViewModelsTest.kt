// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.items

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.items.FieldValue
import com.vettid.core.data.items.GrantDirection
import com.vettid.core.data.items.GrantView
import com.vettid.core.data.items.ItemFieldView
import com.vettid.core.data.items.RuleMatch
import com.vettid.core.data.items.RulePreview
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.items.ShareMode
import com.vettid.core.data.items.ShareRule
import com.vettid.core.data.items.SharedContent
import com.vettid.core.data.items.TagChange
import com.vettid.core.data.items.TagMatch
import com.vettid.core.data.items.TagRegistry
import com.vettid.core.data.items.TagView
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.testing.FakeItems
import com.vettid.core.testing.FakeSharing
import com.vettid.core.testing.FakeSocial
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Tags, share rules and what connections share (VAULT-MESSAGING §10.8, §10.12). */
@OptIn(ExperimentalCoroutinesApi::class)
class SharingViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val sharing = FakeSharing().apply {
        registry = TagRegistry(3, listOf(TagView("@profile", 1), TagView("medical", 2, rules = listOf("r1")), TagView("travel", 1)))
    }
    private val social = FakeSocial().apply {
        connections.value = listOf(ConnectionInfo("c1", "", ConnectionState.ACTIVE, firstName = "Dana", lastName = "Lee"))
    }
    private val items = FakeItems().apply {
        add(FakeItems.item("i1", "Allergies", tags = listOf("medical")))
        add(FakeItems.item("i9", "Signing key", Sensitivity.CRITICAL, tags = listOf("medical")))
    }

    @Test
    fun tagsAreListedProfileFirst() = runTest {
        val vm = TagsViewModel(sharing)
        advanceUntilIdle()
        assertEquals(listOf("@profile", "medical", "travel"), vm.uiState.value.tags.map { it.tag })
        vm.edit(vm.uiState.value.tags.first())
        assertNull(vm.uiState.value.dialog) // @profile is reserved
    }

    @Test
    fun aNewTagIsNormalised() = runTest {
        val vm = TagsViewModel(sharing)
        advanceUntilIdle()
        vm.askCreate()
        vm.setName("  Home  Office ")
        vm.setDescription("Desk things")
        vm.save()
        advanceUntilIdle()
        assertTrue("setTag:home office" in sharing.calls)
        assertNull(vm.uiState.value.dialog)
    }

    @Test
    fun aRenameIsPreviewedThenDone() = runTest {
        sharing.change = TagChange(3, items = 1, sharesTotal = 1)
        val vm = TagsViewModel(sharing)
        advanceUntilIdle()
        vm.edit(vm.uiState.value.tags.first { it.tag == "travel" })
        vm.setName("trips")
        vm.save()
        advanceUntilIdle()
        assertTrue("renameDry:travel>trips" in sharing.calls)
        assertTrue(vm.uiState.value.dialog is TagDialog.ConfirmRename)
        vm.confirmRename()
        advanceUntilIdle()
        assertTrue("rename:travel>trips" in sharing.calls)
        assertNull(vm.uiState.value.dialog)
    }

    @Test
    fun aTagARuleUsesCannotBeDeleted() = runTest {
        val vm = TagsViewModel(sharing)
        advanceUntilIdle()
        vm.edit(vm.uiState.value.tags.first { it.tag == "medical" })
        vm.askDelete()
        advanceUntilIdle()
        assertEquals(FailureKind.IN_USE, vm.uiState.value.error)
        assertNull(vm.uiState.value.dialog)
    }

    private fun ruleVm(ruleId: String? = null) = RuleEditViewModel(
        SavedStateHandle(listOfNotNull(RuleEditRoute.ARG_CONNECTION to "c1", ruleId?.let { RuleEditRoute.ARG_RULE to it }).toMap()),
        sharing,
        social,
    )

    @Test
    fun aNewRuleAsksForEachNewItemByDefaultAndPreviews() = runTest {
        sharing.preview = RulePreview(listOf(RuleMatch("i1", "Allergies", "medical", Sensitivity.DATA)), 1)
        val vm = ruleVm()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(ShareMode.ASK, s.draft.mode)
        assertTrue(s.draft.includeExisting)
        assertEquals(listOf("medical", "travel"), s.tags) // no reserved tag
        assertEquals("Dana Lee", s.connectionName)
        assertFalse(s.canSave)
        vm.toggleTag("medical")
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.preview?.total)
        vm.setUses("0")
        assertFalse(vm.uiState.value.canSave)
        vm.setUses("12")
        vm.setMode(ShareMode.AUTO)
        vm.setExpiry(RuleExpiry.MONTH)
        vm.save()
        advanceUntilIdle()
        val d = sharing.saved.single()
        assertEquals(listOf("medical"), d.tags)
        assertEquals(12, d.uses)
        assertEquals(ShareMode.AUTO, d.mode)
        assertTrue(d.expiresAt!!.isAfter(java.time.Instant.now().plusSeconds(29L * 86_400)))
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun anExistingRuleIsEditedAndDeleted() = runTest {
        sharing.rulesStored += ShareRule("r1", 2, "c1", tags = listOf("medical"), match = TagMatch.ANY, mode = ShareMode.AUTO, uses = 3)
        val vm = ruleVm("r1")
        advanceUntilIdle()
        assertEquals(ShareMode.AUTO, vm.uiState.value.draft.mode)
        assertEquals("3", vm.uiState.value.usesText)
        assertEquals(2L, vm.uiState.value.draft.version)
        vm.askDelete(true)
        vm.delete()
        advanceUntilIdle()
        assertTrue("deleteRule" in sharing.calls)
        assertTrue(vm.uiState.value.done)
    }

    private val given = GrantView("g1", "c1", GrantDirection.GIVEN, "i1", "Allergies", "medical", ruleId = "r1", uses = 3, used = 1)

    @Test
    fun aConnectionSeesItsRulesAndGrants() = runTest {
        sharing.rulesStored += ShareRule("r1", 1, "c1", tags = listOf("medical"), included = listOf("i1", "i9"), pending = listOf("i5"))
        sharing.given += given
        sharing.given += given.copy(grantId = "g0", connectionId = "c2")
        sharing.given += given.copy(grantId = "g2", state = "revoked")
        val vm = ConnectionSharingViewModel(SavedStateHandle(mapOf(ConnectionSharingRoute.ARG to "c1")), sharing, items, social)
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(listOf("g1"), s.active.map { it.grantId })
        assertEquals(listOf("i9"), s.usable)
        assertEquals(1, s.pendingCount)
        vm.askRevoke(s.active.single())
        vm.revoke()
        advanceUntilIdle()
        assertTrue("revoke:g1" in sharing.calls)
        assertTrue(vm.uiState.value.active.isEmpty())
    }

    @Test
    fun sharedWithYouIsFetchedOnPurposeAndForgottenOnStop() = runTest {
        sharing.received += GrantView("g5", "c1", GrantDirection.RECEIVED, "x1", "Insurance card", "insurance")
        sharing.received += GrantView("g6", "c1", GrantDirection.RECEIVED, "x2", "Old address", "contact")
        sharing.contents["g5"] = SharedContent("x1", "Insurance card", "insurance", listOf(ItemFieldView("f1", "Policy", "text", FieldValue.Text("P-42"))), null, 2)
        sharing.refusals["g6"] = "revoked"
        val vm = SharedWithYouViewModel(SavedStateHandle(mapOf(SharedWithYouRoute.ARG to "c1")), sharing, social)
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.received.size)
        assertTrue(sharing.calls.none { it.startsWith("fetch") })
        vm.fetch("g5")
        advanceUntilIdle()
        assertEquals("P-42", (vm.uiState.value.opened.getValue("g5").fields.single().value as FieldValue.Text).text)
        vm.fetch("g6")
        advanceUntilIdle()
        assertEquals("revoked", vm.uiState.value.refused["g6"])
        vm.hide()
        assertTrue(vm.uiState.value.opened.isEmpty())
    }

    /** §10.12 `grant.request`: a category entry the other member answers. */
    @Test
    fun aConnectionIsAskedForACategory() = runTest {
        sharing.requests.clear()
        val vm = SharedWithYouViewModel(SavedStateHandle(mapOf(SharedWithYouRoute.ARG to "c1")), sharing, social)
        advanceUntilIdle()
        vm.openAsk(true)
        vm.setAsk(GrantAskForm(category = "insurance", label = "Your insurance card", reason = "For the trip"))
        vm.sendAsk()
        advanceUntilIdle()
        assertEquals(listOf("c1", "insurance", "Your insurance card", "For the trip"), sharing.requests.single())
        assertNull(vm.uiState.value.ask)
        assertTrue(vm.uiState.value.asked)
    }
}
