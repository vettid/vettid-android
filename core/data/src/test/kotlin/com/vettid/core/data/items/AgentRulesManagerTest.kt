// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.items

import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.GrantFetched
import com.vettid.core.vault.TagPage
import com.vettid.core.vault.VaultJson
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/**
 * An agent's share rule (ANDROID-PLAN 0.1.116 §4 AA4, decisions 4 and 17): the password asked for every save, the unlock
 * window opened by the save and locked again only when the save opened it, the body of `share.rule.set` for an agent
 * subject, and `auto` refused for a new rule on a vault without `item_ways`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class AgentRulesManagerTest {
    private val now = Instant.parse("2026-11-02T12:00:00Z")

    /** The vault side and the window, in one log so that the order of the calls can be asserted. */
    private class Log {
        val events = mutableListOf<String>()
        var windowUntil: Instant? = null
        var unlockFailure: VaultFailure? = null
        var setFailure: Throwable? = null
        var lockFailure: VaultFailure? = null
        var setGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var unlockGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var unlockThrows: Throwable? = null
        val bodies = mutableListOf<JsonObject>()
        val passwords = mutableListOf<String>()
    }

    private val log = Log()

    private val ops = object : SharingOps {
        override suspend fun tagList(after: String?): TagPage = TagPage(1)

        override suspend fun tagSet(version: Long, tag: String, color: String?, icon: String?, description: String?): Long = version

        override suspend fun tagMerge(version: Long, from: List<String>, into: String, dryRun: Boolean): JsonObject = TODO()

        override suspend fun tagDelete(version: Long, tag: String, dryRun: Boolean): JsonObject = TODO()

        override suspend fun ruleList(connectionId: String?, after: String?): JsonObject = TODO()

        override suspend fun agentRuleList(agentId: String, after: String?): JsonObject {
            log.events += "list:$agentId"
            return o(
                """{"rules":[
                  {"rule_id":"r1","version":3,"subject":{"agent_id":"a1"},"tags":["api"],"match":"any","access":"read","mode":"ask","per_hour":60,"per_day":1000,"status_ttl":900,"expires_at":"2026-12-02T12:00:00.000Z","included":["i1","i2"],"pending":["i3"]},
                  {"rule_id":"r9","version":1,"subject":{"agent_id":"other"},"tags":["x"]}]}""",
            )
        }

        override suspend fun ruleSet(body: JsonObject): JsonObject {
            log.events += "set"
            log.bodies += body
            log.setGate?.await()
            log.setFailure?.let { throw it }
            return o(
                """{"rule_id":"r1","version":4,"subject":{"agent_id":"a1"},"tags":["api"],"mode":"ask","per_hour":60,"per_day":1000,"status_ttl":900}""",
            )
        }

        override suspend fun ruleDelete(ruleId: String) = Unit

        override suspend fun grantList(): JsonObject = TODO()

        override suspend fun grantRevoke(grantId: String) = Unit

        override suspend fun grantRequest(connectionId: String, items: JsonArray, reason: String?): String = TODO()

        override suspend fun grantFetch(grantId: String): GrantFetched = TODO()
    }

    private fun manager(itemWays: Boolean = false) = SharingManager(
        ops = { ops },
        hasItemWays = { itemWays },
        windowUntil = { log.windowUntil },
        openWindow = { pw ->
            log.events += "unlock"
            log.passwords += pw
            log.unlockGate?.await()
            log.unlockThrows?.let { throw it }
            log.unlockFailure?.let { throw it }
            log.windowUntil = now.plusSeconds(300)
        },
        closeWindow = {
            log.events += "lock"
            log.lockFailure?.let { throw it }
            log.windowUntil = null
        },
        unlockTtl = { 300 },
        now = { now },
    )

    private fun o(s: String) = VaultJson.json.parseToJsonElement(s).jsonObject

    private fun draft(mode: ShareMode = ShareMode.ASK, ruleId: String? = null) = RuleDraft(
        "", tags = listOf("api"), mode = mode, agentId = "a1", perHour = 60, perDay = 1000, statusTtl = 900,
        expiresAt = now.plusSeconds(86_400 * 30L), ruleId = ruleId, version = ruleId?.let { 3 },
    )

    @Test
    fun aSaveUnlocksWithThePasswordSendsTheRuleThenLocksTheWindowItOpened() = runTest {
        val r = manager().saveAgentRule(draft(), "pw-fake-1")
        assertEquals(listOf("unlock", "set", "lock"), log.events)
        assertEquals(listOf("pw-fake-1"), log.passwords)
        assertEquals("r1", r.ruleId)
        assertEquals("a1", r.agentId)
        assertNull(log.windowUntil)
    }

    @Test
    fun theBodyNamesTheAgentAndAlwaysCarriesTheLimitsAndTheStatusLifetime() = runTest {
        manager().saveAgentRule(draft(), "pw-fake-1")
        val b = log.bodies.single()
        assertEquals("a1", b["subject"]!!.jsonObject["agent_id"]!!.jsonPrimitive.content)
        assertFalse("connection_id" in b["subject"]!!.jsonObject)
        assertEquals("60", b["per_hour"]!!.jsonPrimitive.content)
        assertEquals("1000", b["per_day"]!!.jsonPrimitive.content)
        assertEquals("900", b["status_ttl"]!!.jsonPrimitive.content)
        assertEquals("ask", b["mode"]!!.jsonPrimitive.content)
        assertEquals("api", b["tags"]!!.jsonArray.single().jsonPrimitive.content)
        assertFalse("dry_run" in b)
    }

    @Test
    fun thePasswordIsAskedForEverySaveEvenWithTheWindowOpenAndAnOpenWindowIsLeftAsItWas() = runTest {
        log.windowUntil = now.plusSeconds(120)
        val m = manager()
        m.saveAgentRule(draft(), "pw-fake-1")
        m.saveAgentRule(draft(ruleId = "r1"), "pw-fake-2")
        assertEquals(listOf("unlock", "set", "unlock", "set"), log.events)
        assertEquals(listOf("pw-fake-1", "pw-fake-2"), log.passwords)
        assertTrue(log.windowUntil != null)
    }

    @Test
    fun twoSavesWithAClosedWindowEachOpenAndLockIt() = runTest {
        val m = manager()
        m.saveAgentRule(draft(), "pw-fake-1")
        m.saveAgentRule(draft(ruleId = "r1"), "pw-fake-2")
        assertEquals(listOf("unlock", "set", "lock", "unlock", "set", "lock"), log.events)
    }

    /** VAULT-MESSAGING 0.83.11 section 11.14.10: before "Finish the move" the save says so, not the unlock's text. */
    @Test
    fun transferIncompleteIsTheMoveNotFinishedTextAndTheWindowIsLocked() = runTest {
        log.setFailure = com.vettid.core.vault.VaultOpException("share.rule.set", "transfer_incomplete")
        try {
            manager().saveAgentRule(draft(), "pw-fake-1")
            fail()
        } catch (e: VaultFailure) {
            com.vettid.core.data.vault.assertMoveNotFinished(e.kind)
        }
        assertEquals(listOf("unlock", "set", "lock"), log.events)
    }

    @Test
    fun aRefusedSaveStillLocksTheWindowThePromptOpened() = runTest {
        log.setFailure = VaultFailure(FailureKind.LIMIT, "limit")
        try {
            manager().saveAgentRule(draft(), "pw-fake-1")
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.LIMIT, e.kind)
        }
        assertEquals(listOf("unlock", "set", "lock"), log.events)
    }

    @Test
    fun credentialLockedAfterTheUnlockIsRefusedAndTheWindowIsStillLocked() = runTest {
        log.setFailure = VaultFailure(FailureKind.CREDENTIAL_LOCKED, "credential_locked")
        try {
            manager().saveAgentRule(draft(), "pw-fake-1")
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.CREDENTIAL_LOCKED, e.kind)
        }
        assertEquals(listOf("unlock", "set", "lock"), log.events)
    }

    @Test
    fun aDefiniteRefusalOfTheUnlockSendsNothingAndLocksNothing() = runTest {
        for (kind in listOf(
            FailureKind.BAD_PASSWORD, FailureKind.BACKOFF, FailureKind.CREDENTIAL_FROZEN, FailureKind.ROTATION_REQUIRED,
            FailureKind.OWNER_CHECK_REQUIRED, FailureKind.CREDENTIAL_UNSETTLED,
        )) {
            log.events.clear()
            log.unlockFailure = VaultFailure(kind, "x")
            try {
                manager().saveAgentRule(draft(), "pw-fake-1")
                fail()
            } catch (e: VaultFailure) {
                assertEquals(kind, e.kind)
            }
            assertEquals("no set and no lock after $kind", listOf("unlock"), log.events)
        }
    }

    @Test
    fun anUnlockWhoseAnswerWasLostIsLockedBecauseItMayHaveOpenedTheWindow() = runTest {
        log.unlockFailure = VaultFailure(FailureKind.NO_RESPONSE, "no_response")
        try {
            manager().saveAgentRule(draft(), "pw-fake-1")
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.NO_RESPONSE, e.kind)
        }
        assertEquals(listOf("unlock", "lock"), log.events)
    }

    @Test
    fun aLockThatFailsLeavesTheSavedRuleAndTellsTheWindowStaysOpenUntilCloseItNow() = runTest {
        log.lockFailure = VaultFailure(FailureKind.NO_RESPONSE, "no_response")
        val m = manager()
        val r = m.saveAgentRule(draft(), "pw-fake-1")
        assertEquals("r1", r.ruleId)
        assertEquals(300L, m.windowNotClosed.value?.ttlSeconds)
        log.lockFailure = null
        m.closeWindowNow()
        assertNull(m.windowNotClosed.value)
        assertEquals("lock", log.events.last())
    }

    @Test
    fun aLockThatFailsAfterARefusedSaveIsToldToo() = runTest {
        log.setFailure = VaultFailure(FailureKind.CONFLICT, "conflict")
        log.lockFailure = VaultFailure(FailureKind.NO_RESPONSE, "no_response")
        val m = manager()
        try {
            m.saveAgentRule(draft(ruleId = "r1"), "pw-fake-1")
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.CONFLICT, e.kind)
        }
        assertNotNull(m.windowNotClosed.value)
    }

    @Test
    fun aRuleTheAppWouldRefuseSpendsNoPassword() = runTest {
        val m = manager()
        for (bad in listOf(
            draft().copy(perHour = null),
            draft().copy(perDay = 86_401),
            draft().copy(statusTtl = 59),
            draft().copy(statusTtl = 3_601),
            draft().copy(tags = emptyList()),
            draft().copy(tags = listOf("@profile")),
            draft().copy(uses = 10_001),
            draft().copy(expiresAt = now.minusSeconds(60)),
            draft().copy(agentId = null, connectionId = "c1"),
        )) {
            try {
                m.saveAgentRule(bad, "pw-fake-1")
                fail("sent $bad")
            } catch (e: VaultFailure) {
                assertEquals("bad_request", e.code)
            }
        }
        assertTrue(log.events.isEmpty())
    }

    @Test
    fun anAutoRuleIsRefusedBeforeAnythingIsUnlockedWithoutItemWaysAndSentWithIt() = runTest {
        try {
            manager(itemWays = false).saveAgentRule(draft(ShareMode.AUTO), "pw-fake-1")
            fail()
        } catch (e: VaultFailure) {
            assertEquals("bad_request", e.code)
        }
        assertTrue(log.events.isEmpty())
        manager(itemWays = true).saveAgentRule(draft(ShareMode.AUTO), "pw-fake-1")
        assertEquals("auto", log.bodies.single()["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun withoutItemWaysNoAutoRuleIsSavedNewOrExisting() = runTest {
        for (stored in listOf(null, ShareMode.ASK, ShareMode.AUTO)) {
            try {
                manager(itemWays = false).saveAgentRule(
                    draft(ShareMode.AUTO, ruleId = "r1".takeIf { stored != null }).copy(storedMode = stored), "pw-fake-1",
                )
                fail("saved auto, stored $stored")
            } catch (e: VaultFailure) {
                assertEquals("bad_request", e.code)
            }
        }
        assertTrue(log.events.isEmpty())
    }

    @Test
    fun anExistingAutoRuleIsSavedAsAskWithoutItemWays() = runTest {
        manager(itemWays = false).saveAgentRule(draft(ShareMode.ASK, ruleId = "r1").copy(storedMode = ShareMode.AUTO), "pw-fake-1")
        assertEquals("ask", log.bodies.single()["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun theSavedNoticeIsSetOnlyAfterASaveAndClearsOnce() = runTest {
        val m = manager()
        assertNull(m.ruleSaved.value)
        m.saveAgentRule(draft(), "pw-fake-1")
        assertEquals("a1", m.ruleSaved.value)
        m.ruleSavedShown("other")
        assertEquals("a1", m.ruleSaved.value)
        m.ruleSavedShown("a1")
        assertNull(m.ruleSaved.value)
    }

    @Test
    fun itemWaysComesFromTheVaultsCapabilities() {
        assertTrue(com.vettid.core.data.vault.ReleasePath.itemWays(listOf("attachments", "item_ways")))
        assertFalse(com.vettid.core.data.vault.ReleasePath.itemWays(listOf("attachments", "recovery_r")))
        assertFalse(com.vettid.core.data.vault.ReleasePath.itemWays(emptyList()))
        assertTrue(manager(itemWays = true).itemWays)
        assertFalse(manager().itemWays)
    }

    @Test
    fun anAgentsRulesAreListedWithTheirLimitsAndOnlyThoseOfThatAgent() = runTest {
        val rules = manager().agentRules("a1")
        val r = rules.single()
        assertEquals("r1", r.ruleId)
        assertEquals("a1", r.agentId)
        assertTrue(r.forAgent)
        assertEquals(60, r.perHour)
        assertEquals(1000, r.perDay)
        assertEquals(900, r.statusTtl)
        assertEquals(2, r.included.size)
        assertEquals(listOf("i3"), r.pending)
        assertEquals(ShareMode.ASK, r.mode)
    }

    @Test
    fun aDryRunForAnAgentSendsNoUnlockAndNoPassword() = runTest {
        manager().preview(draft())
        // The preview is the dry run: it signs nothing (§10.12, 0.83.0), so the window is never touched.
        assertEquals(listOf("set"), log.events)
        assertEquals("true", log.bodies.single()["dry_run"]!!.jsonPrimitive.content)
        assertEquals("a1", log.bodies.single()["subject"]!!.jsonObject["agent_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun anExistingAskRuleCannotBecomeAutoWithoutItemWaysAndNothingIsUnlocked() = runTest {
        try {
            manager(itemWays = false).saveAgentRule(draft(ShareMode.AUTO, ruleId = "r1").copy(storedMode = ShareMode.ASK), "pw-fake-1")
            fail()
        } catch (e: VaultFailure) {
            assertEquals("bad_request", e.code)
        }
        assertTrue(log.events.isEmpty())
        manager(itemWays = true).saveAgentRule(draft(ShareMode.AUTO, ruleId = "r1").copy(storedMode = ShareMode.ASK), "pw-fake-1")
        assertEquals("auto", log.bodies.single()["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun theNoPasswordSavePathRefusesAnAgentRule() = runTest {
        try {
            manager().saveRule(draft())
            fail()
        } catch (e: VaultFailure) {
            assertEquals("bad_request", e.code)
        }
        assertTrue(log.events.isEmpty())
    }

    @Test
    fun aWindowThatEndsWithinThirtySecondsIsNotTakenForOpenSoThisSaveLocksIt() = runTest {
        log.windowUntil = now.plusSeconds(20)
        manager().saveAgentRule(draft(), "pw-fake-1")
        assertEquals(listOf("unlock", "set", "lock"), log.events)
        log.events.clear()
        log.windowUntil = now.plusSeconds(31)
        manager().saveAgentRule(draft(), "pw-fake-1")
        assertEquals(listOf("unlock", "set"), log.events)
    }

    @Test
    fun cancellingDuringTheUnlockStillLocksTheWindow() = runTest {
        log.unlockGate = kotlinx.coroutines.CompletableDeferred()
        val job = launch { manager().saveAgentRule(draft(), "pw-fake-1") }
        runCurrent()
        assertEquals(listOf("unlock"), log.events)
        job.cancel()
        job.join()
        assertEquals(listOf("unlock", "lock"), log.events)
    }

    @Test
    fun cancellingDuringTheSaveStillLocksTheWindow() = runTest {
        log.setGate = kotlinx.coroutines.CompletableDeferred()
        val job = launch { manager().saveAgentRule(draft(), "pw-fake-1") }
        // The vault call runs off the test scheduler: wait for it for real.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { while ("set" !in log.events) kotlinx.coroutines.delay(5) }
        }
        assertEquals(listOf("unlock", "set"), log.events)
        job.cancel()
        job.join()
        assertEquals(listOf("unlock", "set", "lock"), log.events)
    }

    @Test
    fun anUnlockThatThrowsSomethingElseStillLocksAndPropagates() = runTest {
        log.unlockThrows = IllegalStateException("boom")
        try {
            manager().saveAgentRule(draft(), "pw-fake-1")
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals(listOf("unlock", "lock"), log.events)
    }

    @Test
    fun theShownStatusLifetimeIsAlwaysSentAReplacementIncluded() = runTest {
        val m = manager()
        m.saveAgentRule(draft().copy(statusTtl = 300), "pw-fake-1")
        m.saveAgentRule(draft(ruleId = "r1").copy(statusTtl = 3_600), "pw-fake-1")
        m.saveAgentRule(draft(ruleId = "r1").copy(statusTtl = null), "pw-fake-1")
        assertEquals(listOf("300", "3600", "900"), log.bodies.map { it["status_ttl"]!!.jsonPrimitive.content })
    }
}
