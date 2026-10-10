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
import com.vettid.core.data.items.RuleDraft
import com.vettid.core.data.items.SharingManager
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.VaultLimit
import com.vettid.core.vault.VaultJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.vettid.core.ui.theme.TagColors
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

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

    // --- tag colours (owner decision 2026-10-09) ---

    @Test
    fun theColourPickerStoresTheChosenPaletteColour() = runTest {
        sharing.registry = sharing.registry.copy(tags = sharing.registry.tags.map { if (it.tag == "travel") it.copy(color = TagColors.stored[2], description = "Trips") else it })
        val vm = TagsViewModel(sharing)
        advanceUntilIdle()
        val travel = vm.uiState.value.tags.first { it.tag == "travel" }
        vm.askColour(travel)
        assertEquals(TagDialog.Colour(travel), vm.uiState.value.dialog)
        // The current colour again: nothing to store.
        vm.pickColour(2)
        assertNull(vm.uiState.value.dialog)
        assertTrue(sharing.calls.none { it.startsWith("setTagColor") })
        vm.askColour(travel)
        vm.pickColour(4)
        advanceUntilIdle()
        assertTrue("setTagColor:travel:${TagColors.stored[4]}" in sharing.calls)
        assertNull(vm.uiState.value.dialog)
        val stored = vm.uiState.value.tags.first { it.tag == "travel" }
        assertEquals(TagColors.stored[4], stored.color)
        assertEquals("Trips", stored.description)
        // Out of range: ignored.
        vm.askColour(stored)
        vm.pickColour(10)
        assertEquals(1, sharing.calls.count { it.startsWith("setTagColor") })
    }

    @Test
    fun theSharedProfileSaysWhyItHasNoColourToPick() = runTest {
        val vm = TagsViewModel(sharing)
        advanceUntilIdle()
        vm.askColour(vm.uiState.value.tags.first { it.tag == "@profile" })
        assertEquals(TagDialog.ProfileColour, vm.uiState.value.dialog)
        vm.pickColour(0)
        assertTrue(sharing.calls.none { it.startsWith("setTagColor") })
    }

    @Test
    fun aRefusedColourKeepsThePickerAndSaysWhy() = runTest {
        sharing.fail["setTagColor:travel:${TagColors.stored[1]}"] = VaultFailure(FailureKind.OWNER_CHECK_REQUIRED, "owner_check_required")
        val vm = TagsViewModel(sharing)
        advanceUntilIdle()
        vm.askColour(vm.uiState.value.tags.first { it.tag == "travel" })
        vm.pickColour(1)
        advanceUntilIdle()
        assertEquals(FailureKind.OWNER_CHECK_REQUIRED, vm.uiState.value.error)
        assertTrue(vm.uiState.value.dialog is TagDialog.Colour)
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
        vm.selectTag("medical")
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.preview?.total)
        vm.setUses("0")
        assertFalse(vm.uiState.value.canSave)
        vm.setUses("12")
        vm.setMode(ShareMode.AUTO)
        vm.setExpiry(RuleExpiry.MONTH, Instant.parse("2026-10-09T10:00:00Z"), ZoneOffset.UTC)
        vm.save()
        advanceUntilIdle()
        val d = sharing.saved.single()
        assertEquals(listOf("medical"), d.tags)
        assertEquals(12, d.uses)
        assertEquals(ShareMode.AUTO, d.mode)
        assertEquals(Instant.parse("2026-11-09T10:00:00Z"), d.expiresAt)
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

    // --- owner feedback 2026-10-09: rules per tag, each with its own settings ---

    private val now = Instant.parse("2026-10-09T10:00:00Z")

    @Test
    fun eachEndPresetCountsFromNowInTheMembersZone() = runTest {
        val vm = ruleVm()
        advanceUntilIdle()
        vm.selectTag("medical")
        val ny = ZoneId.of("America/New_York")
        val expected = mapOf(
            RuleExpiry.NEVER to null,
            RuleExpiry.DAY to "2026-10-10T10:00:00Z",
            RuleExpiry.WEEK to "2026-10-16T10:00:00Z",
            // Calendar months in the member's zone: across the clock change of 2026-11-01 the local time (06:00) stays.
            RuleExpiry.MONTH to "2026-11-09T11:00:00Z",
            RuleExpiry.THREE_MONTHS to "2027-01-09T11:00:00Z",
            RuleExpiry.YEAR to "2027-10-09T10:00:00Z",
        )
        expected.forEach { (e, at) ->
            vm.setExpiry(e, now, ny)
            assertEquals(e, vm.uiState.value.expiry)
            assertEquals("$e", at?.let(Instant::parse), vm.uiState.value.draft.expiresAt)
        }
        assertEquals(RuleExpiry.PRESETS + RuleExpiry.CUSTOM, RuleExpiry.entries.filter { it != RuleExpiry.KEEP })
    }

    /** "Custom…": the date picker's day (UTC midnight) and a local time become one UTC `expires_at` (§10.12). */
    @Test
    fun aCustomEndIsAPickedDayAndTimeInLocalTimeSentAsUtc() = runTest {
        val vm = ruleVm()
        advanceUntilIdle()
        vm.selectTag("medical")
        vm.setExpiry(RuleExpiry.DAY, now, ZoneOffset.UTC)
        vm.setExpiry(RuleExpiry.CUSTOM)
        assertEquals(EndPicker.Date, vm.uiState.value.endPicker)
        assertEquals(RuleExpiry.DAY, vm.uiState.value.expiry) // nothing changes until a time is picked
        val day = LocalDate.of(2026, 12, 31).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        vm.pickEndDate(day)
        assertEquals(EndPicker.Time(day), vm.uiState.value.endPicker)
        vm.pickEndTime(23, 59, ZoneId.of("America/New_York"), now)
        val s = vm.uiState.value
        assertNull(s.endPicker)
        assertFalse(s.endInvalid)
        assertEquals(RuleExpiry.CUSTOM, s.expiry)
        assertEquals(Instant.parse("2027-01-01T04:59:00Z"), s.draft.expiresAt)
        assertEquals("2027-01-01T04:59:00.000Z", VaultJson.str(SharingManager.ruleBody(s.draft, dryRun = false), "expires_at"))
        // Cancelling a second pick keeps the end.
        vm.setExpiry(RuleExpiry.CUSTOM)
        vm.cancelEndPicker()
        assertNull(vm.uiState.value.endPicker)
        assertEquals(Instant.parse("2027-01-01T04:59:00Z"), vm.uiState.value.draft.expiresAt)
    }

    @Test
    fun aCustomEndMustBeInTheFutureAndWithinTenYears() = runTest {
        val vm = ruleVm()
        advanceUntilIdle()
        vm.selectTag("medical")
        val today = LocalDate.of(2026, 10, 9).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        vm.setExpiry(RuleExpiry.CUSTOM)
        vm.pickEndDate(today)
        vm.pickEndTime(9, 0, ZoneOffset.UTC, now) // an hour ago
        assertTrue(vm.uiState.value.endInvalid)
        assertNull(vm.uiState.value.draft.expiresAt)
        assertEquals(RuleExpiry.NEVER, vm.uiState.value.expiry)
        vm.setExpiry(RuleExpiry.CUSTOM)
        vm.pickEndDate(today)
        vm.pickEndTime(10, 30, ZoneOffset.UTC, now) // later today
        assertFalse(vm.uiState.value.endInvalid)
        assertEquals(Instant.parse("2026-10-09T10:30:00Z"), vm.uiState.value.draft.expiresAt)
        // The calendar offers today up to 3,650 days ahead, in the member's zone.
        val day = { d: LocalDate -> d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        assertTrue(RuleEnds.selectable(day(LocalDate.of(2026, 10, 9)), now, ZoneOffset.UTC))
        assertFalse(RuleEnds.selectable(day(LocalDate.of(2026, 10, 8)), now, ZoneOffset.UTC))
        assertTrue(RuleEnds.selectable(day(LocalDate.of(2036, 10, 6)), now, ZoneOffset.UTC))
        assertFalse(RuleEnds.selectable(day(LocalDate.of(2036, 10, 7)), now, ZoneOffset.UTC))
        assertFalse(RuleEnds.allowed(now.plusSeconds(3_650L * 86_400), now))
        assertTrue(RuleEnds.allowed(now.plusSeconds(3_650L * 86_400 - 60), now))
        assertEquals(day(LocalDate.of(2026, 10, 8)), RuleEnds.pickerDay(Instant.parse("2026-10-09T02:00:00Z"), ZoneId.of("America/New_York")))
    }

    @Test
    fun fetchesOfEachItemAreDigitsWithinTheSpecRange() = runTest {
        val vm = ruleVm()
        advanceUntilIdle()
        vm.selectTag("medical")
        val cases = mapOf("" to true, "0" to false, "1" to true, "10000" to true, "10001" to false, "5a0" to true, "123456" to false)
        cases.forEach { (typed, ok) ->
            vm.setUses(typed)
            assertEquals(typed, ok, vm.uiState.value.usesValid)
            assertEquals(typed, ok, vm.uiState.value.canSave)
        }
        vm.setUses("5a0")
        assertEquals("50", vm.uiState.value.usesText)
        assertEquals(50, vm.uiState.value.draft.uses)
        vm.setUses("123456")
        assertEquals("12345", vm.uiState.value.usesText)
        vm.setUses("")
        assertNull(vm.uiState.value.draft.uses)
    }

    /** Every field of the editor reaches `share.rule.set` (§10.12), and an edit keeps the rule's id and version. */
    @Test
    fun everyFieldIsSavedAndAnEditReplacesTheRule() = runTest {
        sharing.registry = sharing.registry.copy(tags = sharing.registry.tags + TagView("address") + TagView("drivers-license"))
        val vm = ruleVm()
        advanceUntilIdle()
        vm.selectTag("travel")
        // One tag per rule (owner decision 2026-10-09): choosing another replaces it.
        vm.selectTag("drivers-license")
        vm.setMode(ShareMode.AUTO)
        vm.setMode(ShareMode.ASK)
        vm.setUses("3")
        vm.setPerHour("5")
        vm.setPerDay("20")
        vm.setIncludeExisting(false)
        vm.setExpiry(RuleExpiry.WEEK, now, ZoneOffset.UTC)
        vm.save()
        advanceUntilIdle()
        assertEquals(
            """{"subject":{"connection_id":"c1"},"tags":["drivers-license"],"match":"any","access":"read","mode":"ask","uses":3,"per_hour":5,"per_day":20,"expires_at":"2026-10-16T10:00:00.000Z","include_existing":false}""",
            VaultJson.json.encodeToString(JsonObject.serializer(), SharingManager.ruleBody(sharing.saved.single(), dryRun = false)),
        )
        // Edit it: the end is kept unless changed; mode and uses change; the id and version go with it.
        val saved = sharing.rulesStored.single()
        val edit = ruleVm(saved.ruleId)
        advanceUntilIdle()
        assertEquals(RuleExpiry.KEEP, edit.uiState.value.expiry)
        assertFalse(edit.uiState.value.isNew)
        edit.setMode(ShareMode.AUTO)
        edit.setUses("")
        edit.save()
        advanceUntilIdle()
        val d = sharing.saved.last()
        assertEquals(saved.ruleId, d.ruleId)
        assertEquals(saved.version, d.version)
        assertEquals(ShareMode.AUTO, d.mode)
        assertNull(d.uses)
        assertEquals(Instant.parse("2026-10-16T10:00:00Z"), d.expiresAt)
        assertEquals(1, sharing.rulesStored.size)
    }

    @Test
    fun theEditorShowsTheConnectionsOtherRulesCoveringTheSameTagsOrItems() = runTest {
        sharing.rulesStored += ShareRule("r1", 1, "c1", tags = listOf("medical"), mode = ShareMode.AUTO, included = listOf("i1"))
        sharing.rulesStored += ShareRule("r2", 1, "c1", tags = listOf("travel"), included = listOf("i5"))
        sharing.rulesStored += ShareRule("r3", 1, "c1", tags = listOf("money"), pending = listOf("i7"))
        // A rule from before one tag per rule: it names "id" too.
        sharing.rulesStored += ShareRule("r4", 1, "c1", tags = listOf("medical", "id"), match = TagMatch.ALL)
        sharing.rulesStored += ShareRule("r9", 1, "c2", tags = listOf("medical"), included = listOf("i1")) // another connection
        sharing.registry = sharing.registry.copy(tags = sharing.registry.tags + TagView("id"))
        sharing.preview = RulePreview(listOf(RuleMatch("i5", "Passport", "identity_document", Sensitivity.DATA)), 1)
        val vm = ruleVm()
        advanceUntilIdle()
        assertEquals(listOf("r1", "r2", "r3", "r4"), vm.uiState.value.rules.map { it.ruleId })
        assertTrue(vm.uiState.value.overlaps.isEmpty())
        vm.selectTag("id")
        advanceUntilIdle()
        // r4 names the tag; r2 holds the matched item; r1 and r3 neither.
        assertEquals(listOf("r2", "r4"), vm.uiState.value.overlaps.map { it.ruleId })
        // Editing r1 itself: it is not its own overlap (r4 names its tag, r2 holds the item).
        val edit = ruleVm("r1")
        advanceUntilIdle()
        assertEquals(listOf("r2", "r4"), edit.uiState.value.overlaps.map { it.ruleId })
    }

    @Test
    fun theDryRunPreviewFollowsTheDraft() = runTest {
        sharing.preview = RulePreview(listOf(RuleMatch("i1", "Allergies", "medical", Sensitivity.DATA), RuleMatch("i9", "Signing key", "crypto_wallet", Sensitivity.CRITICAL)), 4)
        val vm = ruleVm()
        advanceUntilIdle()
        assertNull(vm.uiState.value.preview)
        vm.selectTag("medical")
        vm.setMode(ShareMode.AUTO) // debounced: one dry run for both changes
        advanceUntilIdle()
        assertEquals(1, sharing.calls.count { it == "preview" })
        assertEquals(4, vm.uiState.value.preview?.total)
        assertFalse(vm.uiState.value.previewing)
        vm.selectTag("medical") // the same tag again changes nothing
        advanceUntilIdle()
        assertEquals(1, sharing.calls.count { it == "preview" })
    }

    /** §10.12: 64 rules per connection (`share_rules_subject`); the vault's named limit is shown as it says. */
    @Test
    fun aConnectionAtSixtyFourRulesTakesNoNewOneAndTheLimitIsNamed() = runTest {
        repeat(RuleDraft.MAX_RULES_PER_SUBJECT) { sharing.rulesStored += ShareRule("f$it", 1, "c1", tags = listOf("medical")) }
        val vm = ruleVm()
        advanceUntilIdle()
        vm.selectTag("travel")
        assertTrue(vm.uiState.value.atRuleLimit)
        assertFalse(vm.uiState.value.canSave)
        vm.save()
        advanceUntilIdle()
        assertTrue("saveRule" !in sharing.calls)
        // An existing rule can still be changed.
        val edit = ruleVm("f0")
        advanceUntilIdle()
        assertFalse(edit.uiState.value.atRuleLimit)
        assertTrue(edit.uiState.value.canSave)
        // The vault's own refusal (512 in all) names its limit.
        sharing.rulesStored.clear()
        sharing.fail["saveRule"] = VaultFailure(FailureKind.LIMIT, "limit", limit = VaultLimit("share_rules", 512))
        val again = ruleVm()
        advanceUntilIdle()
        again.selectTag("travel")
        again.save()
        advanceUntilIdle()
        assertEquals(FailureKind.LIMIT, again.uiState.value.error)
        assertEquals(VaultLimit("share_rules", 512), again.uiState.value.limit)
        assertFalse(again.uiState.value.done)
    }

    @Test
    fun theManageScreenNamesOverlapsAndTheRuleLimit() = runTest {
        sharing.rulesStored += ShareRule("r1", 1, "c1", tags = listOf("medical"), included = listOf("i1"))
        sharing.rulesStored += ShareRule("r2", 1, "c1", tags = listOf("medical", "travel"), match = TagMatch.ALL)
        sharing.rulesStored += ShareRule("r3", 1, "c1", tags = listOf("money"))
        val vm = ConnectionSharingViewModel(SavedStateHandle(mapOf(ConnectionSharingRoute.ARG to "c1")), sharing, items, social)
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(listOf("r2"), s.overlaps.getValue("r1").map { it.ruleId })
        assertTrue(s.overlaps.getValue("r3").isEmpty())
        assertFalse(s.atRuleLimit)
        assertTrue(s.copy(rules = (1..64).map { ShareRule("f$it", 1, "c1", tags = listOf("x$it")) }).atRuleLimit)
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

    // --- one tag per rule (owner decision 2026-10-09) and VAULT-MESSAGING 0.23.0 ---

    @Test
    fun aRuleSharesOneTagAndATagAlreadySharedOpensItsRule() = runTest {
        sharing.rulesStored += ShareRule("r1", 1, "c1", tags = listOf("medical"))
        sharing.rulesStored += ShareRule("r9", 1, "c2", tags = listOf("travel")) // another connection's
        val vm = ruleVm()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(mapOf("medical" to "r1"), s.taken)
        assertEquals(listOf("travel"), s.freeTags)
        assertNull(s.tag)
        assertFalse(s.canSave) // Save waits for a tag
        vm.selectTag("medical") // already shared: the screen opens r1 instead
        assertNull(vm.uiState.value.tag)
        assertFalse(vm.uiState.value.dirty)
        vm.selectTag("travel")
        assertEquals(listOf("travel"), vm.uiState.value.draft.tags)
        assertEquals(TagMatch.ANY, vm.uiState.value.draft.match)
        assertTrue(vm.uiState.value.canSave)
        // Its own rule's tag is free to its editor.
        val edit = ruleVm("r1")
        advanceUntilIdle()
        assertTrue(edit.uiState.value.taken.isEmpty())
        assertEquals("medical", edit.uiState.value.tag)
    }

    /** A rule naming several tags (made before one tag per rule) is shown read-only: it can be deleted, not changed. */
    @Test
    fun aMultiTagRuleIsReadOnlyAndCanBeDeleted() = runTest {
        sharing.rulesStored += ShareRule("r2", 1, "c1", tags = listOf("medical", "travel"), match = TagMatch.ALL)
        val vm = ruleVm("r2")
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.readOnly)
        assertFalse(s.canSave)
        // Its tags do not block a single-tag rule of either: only a rule of exactly one tag does.
        assertTrue(s.taken.isEmpty())
        vm.setMode(ShareMode.AUTO)
        vm.setUses("3")
        vm.setExpiry(RuleExpiry.WEEK, now, ZoneOffset.UTC)
        assertEquals(ShareMode.ASK, vm.uiState.value.draft.mode)
        assertNull(vm.uiState.value.draft.uses)
        assertNull(vm.uiState.value.draft.expiresAt)
        vm.save()
        advanceUntilIdle()
        assertTrue("saveRule" !in sharing.calls)
        vm.askDelete(true)
        vm.delete()
        advanceUntilIdle()
        assertTrue("deleteRule" in sharing.calls)
        assertTrue(vm.uiState.value.done)
    }

    /** 0.23.0 §10.12: `per_hour` 1–3,600 and `per_day` 1–86,400 on a connection rule, digits only, optional. */
    @Test
    fun perHourAndPerDayAreDigitsWithinTheSpecRanges() = runTest {
        val vm = ruleVm()
        advanceUntilIdle()
        vm.selectTag("medical")
        mapOf("" to true, "0" to false, "1" to true, "3600" to true, "3601" to false, "6a0" to true).forEach { (typed, ok) ->
            vm.setPerHour(typed)
            assertEquals(typed, ok, vm.uiState.value.perHourValid)
            assertEquals(typed, ok, vm.uiState.value.canSave)
        }
        vm.setPerHour("")
        mapOf("0" to false, "86400" to true, "86401" to false, "20" to true).forEach { (typed, ok) ->
            vm.setPerDay(typed)
            assertEquals(typed, ok, vm.uiState.value.perDayValid)
        }
        assertEquals(20, vm.uiState.value.draft.perDay)
        assertNull(vm.uiState.value.draft.perHour)
        vm.setPerHour("123456")
        assertEquals("1234", vm.uiState.value.perHourText)
        // An existing rule's limits are shown.
        sharing.rulesStored += ShareRule("r1", 1, "c1", tags = listOf("travel"), perHour = 5, perDay = 20)
        val edit = ruleVm("r1")
        advanceUntilIdle()
        assertEquals("5", edit.uiState.value.perHourText)
        assertEquals("20", edit.uiState.value.perDayText)
    }

    /** "Save rule" in the top bar: leaving with changes asks first; nothing changed, nothing to discard. */
    @Test
    fun leavingWithChangesAsksToDiscard() = runTest {
        sharing.rulesStored += ShareRule("r1", 1, "c1", tags = listOf("medical"))
        val vm = ruleVm("r1")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.dirty)
        vm.setMode(ShareMode.AUTO)
        assertTrue(vm.uiState.value.dirty)
        vm.askDiscard(true)
        assertTrue(vm.uiState.value.confirmDiscard)
        vm.askDiscard(false)
        vm.save()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.dirty)
        assertTrue(vm.uiState.value.done)
    }

    /** 0.23.0 §10.12: a `rate_limited` refusal says when the item can be fetched again. */
    @Test
    fun aRateLimitedFetchSaysWhenToTryAgain() = runTest {
        sharing.received += GrantView("g6", "c1", GrantDirection.RECEIVED, "x2", "Card", "insurance", perHour = 5)
        sharing.refusals["g6"] = "rate_limited"
        sharing.retryAfter["g6"] = 720
        val vm = SharedWithYouViewModel(SavedStateHandle(mapOf(SharedWithYouRoute.ARG to "c1")), sharing, social)
        vm.now = { now }
        advanceUntilIdle()
        assertEquals(5, vm.uiState.value.received.single().perHour)
        vm.fetch("g6")
        advanceUntilIdle()
        assertEquals("rate_limited", vm.uiState.value.refused["g6"])
        assertEquals(now.plusSeconds(720), vm.uiState.value.retryAt["g6"])
        // Rounded up to whole minutes, in hours from 90 minutes.
        assertEquals(RetryIn.Scale.MINUTES to 12, RetryIn.of(720))
        assertEquals(RetryIn.Scale.MINUTES to 1, RetryIn.of(1))
        assertEquals(RetryIn.Scale.MINUTES to 1, RetryIn.of(0))
        assertEquals(RetryIn.Scale.MINUTES to 2, RetryIn.of(61))
        assertEquals(RetryIn.Scale.MINUTES to 89, RetryIn.of(89 * 60))
        assertEquals(RetryIn.Scale.HOURS to 2, RetryIn.of(90 * 60))
        assertEquals(RetryIn.Scale.HOURS to 24, RetryIn.of(86_400))
        // Another refusal has no retry.
        sharing.refusals["g6"] = "revoked"
        sharing.retryAfter.clear()
        vm.fetch("g6")
        advanceUntilIdle()
        assertNull(vm.uiState.value.retryAt["g6"])
    }
}
