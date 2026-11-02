// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.items

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.devices.AgentRuleRoute
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.items.RuleMatch
import com.vettid.core.data.items.RulePreview
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.items.ShareMode
import com.vettid.core.data.items.ShareRule
import com.vettid.core.data.items.TagRegistry
import com.vettid.core.data.items.TagView
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.VaultLimit
import com.vettid.core.data.votes.WindowNotClosed
import com.vettid.core.testing.FakeDevices
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
import java.time.Instant

/**
 * An agent's share rule in the editor (ANDROID-PLAN 0.1.116 §4 AA4, decisions 4 and 17): defaults, validation, `auto`
 * hidden without `item_ways`, the password asked for every save, each answer, and nothing secret kept.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRuleEditViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val sharing = FakeSharing().apply {
        registry = TagRegistry(3, listOf(TagView("@profile", 1), TagView("api", 2), TagView("travel", 1)))
    }
    private val devices = FakeDevices().apply {
        this.devices.value = listOf(DeviceInfo("a1", "agent", "Claude", null, null, null, null))
    }

    private fun vm(ruleId: String? = null) = RuleEditViewModel(
        SavedStateHandle(listOfNotNull(AgentRuleRoute.ARG_AGENT to "a1", ruleId?.let { AgentRuleRoute.ARG_RULE to it }).toMap()),
        sharing,
        FakeSocial(),
        devices,
    )

    private fun existing(mode: ShareMode = ShareMode.ASK) = ShareRule(
        "r1", 3, null, "a1", listOf("api"), mode = mode, perHour = 30, perDay = 500, statusTtl = 300,
        expiresAt = Instant.now().plusSeconds(86_400 * 5L), included = listOf("i1"),
    )

    @Test
    fun aNewRuleStartsAsAskWithTheDefaultLimitsAMonthAndFifteenMinutes() = runTest {
        val v = vm()
        advanceUntilIdle()
        val s = v.uiState.value
        assertTrue(s.isAgent)
        assertEquals("Claude", s.connectionName)
        assertEquals(ShareMode.ASK, s.draft.mode)
        assertEquals("60", s.perHourText)
        assertEquals("1000", s.perDayText)
        assertEquals(900, s.draft.statusTtl)
        assertEquals(RuleExpiry.MONTH, s.expiry)
        assertTrue(s.draft.expiresAt!!.isAfter(Instant.now().plusSeconds(86_400 * 27L)))
        assertEquals(listOf("api", "travel"), s.tags)
        assertFalse(s.canSave)
    }

    @Test
    fun autoIsHiddenAndRefusedWithoutItemWaysAndOfferedWithIt() = runTest {
        val without = vm()
        advanceUntilIdle()
        assertFalse(without.uiState.value.autoOffered)
        assertTrue(without.uiState.value.waysWarning)
        without.setMode(ShareMode.AUTO)
        assertEquals(ShareMode.ASK, without.uiState.value.draft.mode)

        sharing.itemWays = true
        val with = vm()
        advanceUntilIdle()
        assertTrue(with.uiState.value.autoOffered)
        assertFalse(with.uiState.value.waysWarning)
        with.setMode(ShareMode.AUTO)
        assertEquals(ShareMode.AUTO, with.uiState.value.draft.mode)
    }

    @Test
    fun aRuleThatIsAlreadyAutoStaysVisibleAndSelectedWithoutItemWays() = runTest {
        sharing.rulesStored += existing(ShareMode.AUTO)
        val v = vm("r1")
        advanceUntilIdle()
        assertTrue(v.uiState.value.autoOffered)
        assertTrue(v.uiState.value.waysWarning)
        assertEquals(ShareMode.AUTO, v.uiState.value.draft.mode)
    }

    @Test
    fun theEditorsValidationAnAgentRuleAlwaysHasBothLimits() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.selectTag("api")
        advanceUntilIdle()
        assertTrue(v.uiState.value.canSave)
        v.setPerHour("")
        assertFalse(v.uiState.value.perHourValid)
        assertFalse(v.uiState.value.canSave)
        v.setPerHour("3601")
        assertFalse(v.uiState.value.canSave)
        v.setPerHour("3600")
        v.setPerDay("86401")
        assertFalse(v.uiState.value.canSave)
        v.setPerDay("0")
        assertFalse(v.uiState.value.canSave)
        v.setPerDay("86400")
        v.setUses("10001")
        assertFalse(v.uiState.value.usesValid)
        assertFalse(v.uiState.value.canSave)
        v.setUses("")
        assertTrue(v.uiState.value.canSave)
        v.setStatusTtl(60)
        assertEquals(60, v.uiState.value.draft.statusTtl)
    }

    @Test
    fun savingAsksForThePasswordFirstAndSendsNothingUntilItIsGiven() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.selectTag("api")
        advanceUntilIdle()
        v.save()
        assertEquals(RulePrompt.PASSWORD, v.uiState.value.prompt)
        assertTrue("saveAgentRule" !in sharing.calls)
        v.submitPassword()
        assertTrue("saveAgentRule" !in sharing.calls) // an empty password is not sent
        v.setPassword("pw-fake-1")
        v.submitPassword()
        advanceUntilIdle()
        assertEquals(listOf("pw-fake-1"), sharing.agentSaves.map { it.second })
        assertEquals("a1", sharing.agentSaves.single().first.agentId)
        assertTrue(v.uiState.value.done)
        assertEquals("", v.uiState.value.password)
        assertEquals(RulePrompt.NONE, v.uiState.value.prompt)
    }

    @Test
    fun theSecondSaveAsksAgainEvenRightAfterTheFirst() = runTest {
        sharing.rulesStored += existing()
        val v = vm("r1")
        advanceUntilIdle()
        v.setPerHour("20")
        repeat(2) { n ->
            v.save()
            assertEquals(RulePrompt.PASSWORD, v.uiState.value.prompt)
            v.setPassword("pw-fake-$n")
            v.submitPassword()
            advanceUntilIdle()
        }
        assertEquals(listOf("pw-fake-0", "pw-fake-1"), sharing.agentSaves.map { it.second })
    }

    @Test
    fun aWrongPasswordKeepsThePromptAndNeverClosesTheEditor() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.selectTag("api")
        advanceUntilIdle()
        v.save()
        v.setPassword("pw-fake-1")
        sharing.fail["saveAgentRule"] = VaultFailure(FailureKind.BAD_PASSWORD, "bad_password")
        v.submitPassword()
        advanceUntilIdle()
        val s = v.uiState.value
        assertEquals(RulePrompt.PASSWORD, s.prompt)
        assertEquals(FailureKind.BAD_PASSWORD, s.passwordError)
        assertEquals("", s.password)
        assertFalse(s.done)
    }

    @Test
    fun aBackoffBlocksConfirmUntilItsEnd() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.selectTag("api")
        advanceUntilIdle()
        v.save()
        v.setPassword("pw-fake-1")
        sharing.fail["saveAgentRule"] = VaultFailure(FailureKind.BACKOFF, "backoff", retryAfterSeconds = 60)
        v.submitPassword()
        advanceUntilIdle()
        assertTrue(v.uiState.value.retryUntil!!.isAfter(Instant.now()))
        v.setPassword("pw-fake-2")
        v.submitPassword()
        advanceUntilIdle()
        assertTrue(sharing.agentSaves.isEmpty())
        assertEquals(1, sharing.calls.count { it == "saveAgentRule" })
    }

    @Test
    fun anAlarmRefusesThePromptAndNothingIsSaved() = runTest {
        for (kind in listOf(FailureKind.CREDENTIAL_FROZEN, FailureKind.ROTATION_REQUIRED)) {
            val v = vm()
            advanceUntilIdle()
            v.selectTag("api")
            advanceUntilIdle()
            v.save()
            v.setPassword("pw-fake-1")
            sharing.fail["saveAgentRule"] = VaultFailure(kind, "x")
            v.submitPassword()
            advanceUntilIdle()
            assertEquals(kind, v.uiState.value.passwordError)
            assertFalse(v.uiState.value.done)
        }
        assertTrue(sharing.agentSaves.isEmpty())
    }

    @Test
    fun credentialLockedAsksForThePasswordAgainOnceAndTheSecondTimeSaysSo() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.selectTag("api")
        advanceUntilIdle()
        v.save()
        v.setPassword("pw-fake-1")
        sharing.fail["saveAgentRule"] = VaultFailure(FailureKind.CREDENTIAL_LOCKED, "credential_locked")
        v.submitPassword()
        advanceUntilIdle()
        assertEquals(RulePrompt.PASSWORD, v.uiState.value.prompt)
        assertFalse(v.uiState.value.lockedAgain)
        // The prompt, then the same request once: it works this time.
        v.setPassword("pw-fake-2")
        v.submitPassword()
        advanceUntilIdle()
        assertTrue(v.uiState.value.done)
        assertEquals(listOf("pw-fake-2"), sharing.agentSaves.map { it.second })
    }

    @Test
    fun aSecondCredentialLockedSaysYourCredentialLockedAgain() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.selectTag("api")
        advanceUntilIdle()
        v.save()
        repeat(2) {
            v.setPassword("pw-fake-1")
            sharing.fail["saveAgentRule"] = VaultFailure(FailureKind.CREDENTIAL_LOCKED, "credential_locked")
            v.submitPassword()
            advanceUntilIdle()
        }
        val s = v.uiState.value
        assertTrue(s.lockedAgain)
        assertEquals(RulePrompt.NONE, s.prompt)
        assertFalse(s.done)
        assertEquals("", s.password)
        // The next save starts again: the prompt, and one more chance.
        v.save()
        assertFalse(v.uiState.value.lockedAgain)
        assertEquals(RulePrompt.PASSWORD, v.uiState.value.prompt)
    }

    @Test
    fun aConflictReloadsTheCurrentRuleAndSaysSo() = runTest {
        sharing.rulesStored += existing()
        val v = vm("r1")
        advanceUntilIdle()
        v.setPerHour("20")
        v.save()
        v.setPassword("pw-fake-1")
        // Another device changed the rule meanwhile.
        sharing.rulesStored.clear()
        sharing.rulesStored += existing().copy(version = 4, perHour = 45)
        sharing.fail["saveAgentRule"] = VaultFailure(FailureKind.CONFLICT, "conflict")
        v.submitPassword()
        advanceUntilIdle()
        val s = v.uiState.value
        assertTrue(s.conflict)
        assertEquals(45, s.draft.perHour)
        assertEquals("45", s.perHourText)
        assertEquals(4L, s.draft.version)
        assertFalse(s.done)
        assertFalse(s.dirty)
    }

    @Test
    fun theVaultsLimitsAndNotFoundAreShownNotClosed() = runTest {
        for ((failure, name) in listOf(
            VaultFailure(FailureKind.LIMIT, "limit", limit = VaultLimit("agent_grants", 32)) to "agent_grants",
            VaultFailure(FailureKind.LIMIT, "limit", limit = VaultLimit("share_rules_subject", 64)) to "share_rules_subject",
            VaultFailure(FailureKind.LIMIT, "limit", limit = VaultLimit("share_rules", 512)) to "share_rules",
            VaultFailure(FailureKind.NOT_FOUND, "not_found") to "",
            VaultFailure(FailureKind.OWNER_CHECK_REQUIRED, "owner_check_required") to "",
        )) {
            val v = vm()
            advanceUntilIdle()
            v.selectTag("api")
            advanceUntilIdle()
            v.save()
            v.setPassword("pw-fake-1")
            sharing.fail["saveAgentRule"] = failure
            v.submitPassword()
            advanceUntilIdle()
            val s = v.uiState.value
            assertEquals(failure.kind, s.error)
            assertEquals(name, s.limit?.name.orEmpty())
            assertEquals(RulePrompt.NONE, s.prompt)
            assertFalse(s.done)
            assertEquals("", s.password)
        }
    }

    @Test
    fun aWindowThatStayedOpenIsToldAndClosedOnRequest() = runTest {
        val v = vm()
        advanceUntilIdle()
        sharing.windowNotClosedFlow.value = WindowNotClosed(300)
        advanceUntilIdle()
        assertEquals(300L, v.uiState.value.windowNotClosed?.ttlSeconds)
        v.closeWindowNow()
        advanceUntilIdle()
        assertNull(v.uiState.value.windowNotClosed)
        assertTrue("closeWindowNow" in sharing.calls)
    }

    @Test
    fun theDryRunNeedsNoPasswordAndShowsTheOutcomes() = runTest {
        sharing.preview = RulePreview(
            listOf(
                RuleMatch("i1", "Key A", "secret", Sensitivity.SECRET, outcome = RuleMatch.OUTCOME_ASK),
                RuleMatch("i2", "Key B", "secret", Sensitivity.SECRET, outcome = RuleMatch.OUTCOME_INCLUDE),
            ),
            2,
        )
        val v = vm()
        advanceUntilIdle()
        v.selectTag("api")
        advanceUntilIdle()
        assertEquals(2, v.uiState.value.preview?.total)
        assertTrue("preview" in sharing.calls)
        assertTrue(sharing.agentSaves.isEmpty())
        assertEquals(RulePrompt.NONE, v.uiState.value.prompt)
    }

    @Test
    fun deletingARuleAsksNoPassword() = runTest {
        sharing.rulesStored += existing()
        val v = vm("r1")
        advanceUntilIdle()
        v.askDelete(true)
        v.delete()
        advanceUntilIdle()
        assertTrue("deleteRule" in sharing.calls)
        assertTrue(sharing.agentSaves.isEmpty())
        assertEquals(RulePrompt.NONE, v.uiState.value.prompt)
        assertTrue(v.uiState.value.done)
    }

    @Test
    fun theStateNeverPrintsThePasswordAndOnlyTheTypeName() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.selectTag("api")
        advanceUntilIdle()
        v.save()
        v.setPassword("pw-fake-secret")
        assertFalse("${v.uiState.value}".contains("pw-fake-secret"))
        assertFalse("${v.uiState.value}".contains("Claude"))
        assertFalse("${v.uiState.value}".contains("api"))
    }

    @Test
    fun cancellingThePromptDropsThePasswordAndSendsNothing() = runTest {
        val v = vm()
        advanceUntilIdle()
        v.selectTag("api")
        advanceUntilIdle()
        v.save()
        v.setPassword("pw-fake-1")
        v.dismissPrompt()
        assertEquals(RulePrompt.NONE, v.uiState.value.prompt)
        assertEquals("", v.uiState.value.password)
        assertTrue(sharing.agentSaves.isEmpty())
    }
}
