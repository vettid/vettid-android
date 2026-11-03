// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.devices

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.VaultLimit
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

private fun obj(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject

/** An agent's permissions: the list, its events, Remove and Resume, the answers to `leash.grant.issue` (ANDROID-PLAN 0.1.116 §4 AA3). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentPermissionsTest {
    private val now = Instant.parse("2026-11-03T10:00:00Z")

    private val body = obj(
        """{"grants":[
          {"grant_id":"r1","agent_id":"a1","version":1,"scope":"items.read","approval":"ask","per_hour":60,"per_day":1000,"status_ttl":900},
          {"grant_id":"g1","agent_id":"a1","version":3,"scope":"connection.list","approval":"ask","status_ttl":300},
          {"grant_id":"g2","agent_id":"a1","version":1,"scope":"message.send","approval":"auto","connections":["c1","c2"],"per_hour":5,"per_day":50,"expires_at":"2026-12-03T10:00:00Z","status_ttl":3600},
          {"grant_id":"g3","agent_id":"a1","scope":"wallet.send","approval":"auto"},
          {"grant_id":"x1","agent_id":"a2","scope":"profile.get"},
          {"agent_id":"a1","scope":"profile.get"},
          {"grant_id":"g4","agent_id":"a1"},
          "junk"
        ],"suspended":["a1"]}""",
    )

    @Test
    fun itemsReadIsASplitOutShareRuleAndTheOthersAreListedInOrder() {
        val p = PermissionWire.permissions(body, "a1")
        assertEquals(listOf("g1", "g2", "g3"), p.others.map { it.grantId })
        assertTrue("an items.read grant is a share rule: listed under What it may use", p.others.none { it.scope == PermissionScopes.ITEMS_READ })
    }

    @Test
    fun aGrantOfAnotherAgentOrWithoutAnIdOrAScopeIsNotListed() {
        val ids = PermissionWire.permissions(body, "a1").others.map { it.grantId }
        assertFalse("x1" in ids)
        assertEquals(listOf("x1"), PermissionWire.permissions(body, "a2").others.map { it.grantId })
    }

    @Test
    fun anAskGrantAsksAnAutoGrantCarriesItsLimitsAndDefaultsTo60And1000() {
        val (g1, g2, g3) = PermissionWire.permissions(body, "a1").others
        assertEquals(GrantApproval.ASK, g1.approval)
        assertEquals(GrantApproval.AUTO, g2.approval)
        assertEquals(5L to 50L, g2.perHour to g2.perDay)
        assertEquals(GrantApproval.AUTO, g3.approval)
        assertEquals(60L to 1_000L, g3.perHour to g3.perDay)
    }

    @Test
    fun theEndTheConnectionsAndTheStatusLifetimeAreRead() {
        val (g1, g2, g3) = PermissionWire.permissions(body, "a1").others
        assertEquals(Instant.parse("2026-12-03T10:00:00Z"), g2.expiresAt)
        assertNull(g1.expiresAt)
        assertEquals(listOf("c1", "c2"), g2.connections)
        assertTrue(g1.connections.isEmpty())
        assertEquals(listOf(300L, 3_600L, 900L), listOf(g1.statusTtl, g2.statusTtl, g3.statusTtl))
        assertEquals(3L, g1.version)
    }

    @Test
    fun aScopeThisAppHasNoLabelForIsStillListed() {
        val g3 = PermissionWire.permissions(body, "a1").others.first { it.grantId == "g3" }
        assertEquals("wallet.send", g3.scope)
        assertFalse(g3.scope in PermissionScopes.known)
        assertEquals(9, PermissionScopes.known.size)
    }

    @Test
    fun anApprovalThisAppDoesNotKnowIsReadAsAsk() {
        val p = PermissionWire.permissions(obj("""{"grants":[{"grant_id":"g","scope":"profile.get","approval":"maybe","per_hour":"x","per_day":-4}],"suspended":[]}"""), "a1")
        assertEquals(GrantApproval.ASK, p.others.single().approval)
        assertEquals(60L to 1_000L, p.others.single().perHour to p.others.single().perDay)
    }

    @Test
    fun suspendedIsWhetherThisAgentIsInTheList() {
        assertTrue(PermissionWire.permissions(body, "a1").suspended)
        assertFalse(PermissionWire.permissions(body, "a2").suspended)
        assertFalse(PermissionWire.permissions(obj("""{"grants":[],"suspended":["other"]}"""), "a1").suspended)
    }

    @Test
    fun aBodyThatIsNotAListOfGrantsIsAFailureNeverNoPermissions() {
        for (b in listOf("""{}""", """{"grants":"x"}""", """{"suspended":["a1"]}""")) {
            try {
                PermissionWire.permissions(obj(b), "a1")
                fail("read as no permissions: $b")
            } catch (e: VaultFailure) {
                assertEquals("unreadable_grants", e.code)
            }
        }
    }

    @Test
    fun nothingIsPrinted() {
        val p = PermissionWire.permissions(body, "a1")
        assertEquals("AgentPermissions", p.toString())
        assertEquals("AgentGrant", p.others.first().toString())
        assertFalse(p.toString().contains("c1"))
    }

    // --- the re-read triggers ---

    private fun sync(json: String) = VaultMessage(Inner(id = Ulid.new(), type = "sync.event", ts = now, body = json.toByteArray()))

    @Test
    fun grantAndSuspensionEventsNameTheirAgent() {
        for (kind in listOf("leash.grant.changed", "leash.grant.revoked", "leash.agent.suspended")) {
            assertEquals(AgentChange.Grants("a1"), AgentChange.of(sync("""{"kind":"$kind","agent_id":"a1","grant_id":"g1"}""")))
        }
        assertNull("no agent, nothing to read for", AgentChange.of(sync("""{"kind":"leash.grant.changed"}""")))
    }

    @Test
    fun ruleEventsCarryOnlyTheRuleId() {
        assertEquals(AgentChange.Rule("r9"), AgentChange.of(sync("""{"kind":"share.rule.changed","rule_id":"r9","version":2}""")))
        assertEquals(AgentChange.Rule("r9"), AgentChange.of(sync("""{"kind":"share.rule.deleted","rule_id":"r9"}""")))
        assertEquals(AgentChange.Rule(null), AgentChange.of(sync("""{"kind":"share.rule.deleted"}""")))
    }

    @Test
    fun otherEventsAndOtherMessagesAreNotTriggers() {
        assertNull(AgentChange.of(sync("""{"kind":"device.session","device_id":"a1"}""")))
        assertNull(AgentChange.of(sync("""{"kind":"leash.item.changed","agent_id":"a1"}""")))
        assertNull(AgentChange.of(VaultMessage(Inner(id = Ulid.new(), type = "feed.item", ts = now, body = """{"kind":"leash.grant.changed","agent_id":"a1"}""".toByteArray()))))
    }

    @Test
    fun aGrantEventConcernsOnlyItsAgentAndARuleEventAnyPage() {
        assertTrue(AgentChange.Grants("a1").concerns("a1"))
        assertFalse(AgentChange.Grants("a2").concerns("a1"))
        assertTrue(AgentChange.Rule("known").concerns("a1"))
        assertTrue(AgentChange.Rule("unknown").concerns("a1"))
        assertTrue(AgentChange.Rule(null).concerns("a1"))
    }

    // --- the manager ---

    private class Ops : DevicesOps {
        val devices = obj("""{"devices":[{"id":"a1","kind":"agent","name":"Claude"},{"id":"me","kind":"app","name":"Pixel"}]}""")
        var grants = mutableMapOf<String, JsonObject>()
        val calls = mutableListOf<String>()
        val refuse = mutableMapOf<String, String>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun list() = devices

        override suspend fun grants(agentId: String?): JsonObject {
            synchronized(this) { calls += "grants:${agentId ?: "*"}" }
            gate?.await()
            refuse["grants"]?.let { throw VaultOpException("leash.grant.list", it) }
            return if (agentId == null) obj("""{"grants":[],"suspended":[]}""") else grants[agentId] ?: obj("""{"grants":[],"suspended":[]}""")
        }

        override suspend fun endAccess(deviceId: String) = Unit

        override suspend fun unlink(deviceId: String) = Unit

        override suspend fun revokeGrant(grantId: String) {
            synchronized(this) { calls += "revoke:$grantId" }
            refuse["revoke"]?.let { throw VaultOpException("leash.grant.revoke", it) }
        }

        override suspend fun resumeAgent(agentId: String) {
            synchronized(this) { calls += "resume:$agentId" }
            refuse["resume"]?.let { throw VaultOpException("leash.agent.resume", it) }
        }
    }

    private fun TestScope.manager(ops: Ops) = DevicesManager(this, ops = { ops }, self = { "me" })

    @Test
    fun readingPutsTheGrantsInMemoryAndFollowsThePausedFlagOntoTheAgent() = runTest {
        val ops = Ops().apply { grants["a1"] = body }
        val m = manager(ops)
        m.refresh()
        assertFalse(m.devices.value.first { it.id == "a1" }.paused)
        m.refreshPermissions("a1")
        assertEquals(listOf("g1", "g2", "g3"), m.permissions.value.getValue("a1").others.map { it.grantId })
        assertTrue(m.devices.value.first { it.id == "a1" }.paused)
        // It is read with `agent_id`, never for every agent.
        assertTrue("grants:a1" in ops.calls)
    }

    @Test
    fun aLockClearsThePermissionsWithTheList() = runTest {
        val ops = Ops().apply { grants["a1"] = body }
        val m = manager(ops)
        m.refresh()
        m.refreshPermissions("a1")
        m.clear()
        assertTrue(m.permissions.value.isEmpty())
        assertEquals(ListLoad.NOT_LOADED, m.load.value)
    }

    @Test
    fun aReadThatFailsLeavesWhatWasReadAndThrows() = runTest {
        val ops = Ops().apply { grants["a1"] = body }
        val m = manager(ops)
        m.refresh()
        m.refreshPermissions("a1")
        ops.refuse["grants"] = "internal"
        try {
            m.refreshPermissions("a1")
            fail("a failed read passed")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.OTHER, e.kind)
        }
        assertEquals(3, m.permissions.value.getValue("a1").others.size)
    }

    @Test
    fun aLockWhileAReadIsRunningDropsItsAnswer() = runTest {
        val gate = CompletableDeferred<Unit>()
        val ops = Ops().apply {
            grants["a1"] = body
            this.gate = gate
        }
        val m = manager(ops)
        val read = launch(UnconfinedTestDispatcher(testScheduler)) { m.refreshPermissions("a1") }
        m.clear()
        gate.complete(Unit)
        read.join()
        assertTrue("an answer read before the lock is not shown after it", m.permissions.value.isEmpty())
    }

    @Test
    fun removeSendsOnlyTheGrantIdAndReadsTheGrantsAgain() = runTest {
        val ops = Ops().apply { grants["a1"] = body }
        val m = manager(ops)
        m.refresh()
        assertEquals(PermissionRemoval.REMOVED, m.removePermission("a1", "g2"))
        assertEquals(listOf("revoke:g2", "grants:a1"), ops.calls.filter { it.startsWith("revoke") || it.startsWith("grants:a1") })
    }

    @Test
    fun removeNotFoundIsAlreadyGoneAndReadsAgain() = runTest {
        val ops = Ops().apply {
            grants["a1"] = body
            refuse["revoke"] = "not_found"
        }
        val m = manager(ops)
        m.refresh()
        assertEquals(PermissionRemoval.ALREADY_GONE, m.removePermission("a1", "g2"))
        assertTrue("grants:a1" in ops.calls)
    }

    @Test
    fun anyOtherRemoveFailureIsThrownAndNothingIsReadAgain() = runTest {
        for (code in listOf("internal", "forbidden", "rate_limited", "owner_check_required")) {
            val ops = Ops().apply { refuse["revoke"] = code }
            val m = manager(ops)
            try {
                m.removePermission("a1", "g2")
                fail("$code passed")
            } catch (e: VaultFailure) {
                assertTrue(e.kind != FailureKind.NOT_FOUND)
            }
            assertTrue("$code: the row is kept, nothing is read", ops.calls.none { it.startsWith("grants") })
        }
    }

    @Test
    fun aRemovalThatWentThroughIsNotAFailureBecauseTheReadAfterItFailed() = runTest {
        val ops = Ops().apply { refuse["grants"] = "internal" }
        val m = manager(ops)
        assertEquals(PermissionRemoval.REMOVED, m.removePermission("a1", "g2"))
    }

    @Test
    fun resumeSendsOnlyTheAgentIdAndReadsAgainWhichClearsThePause() = runTest {
        val ops = Ops().apply { grants["a1"] = body }
        val m = manager(ops)
        m.refresh()
        m.refreshPermissions("a1")
        assertTrue(m.devices.value.first { it.id == "a1" }.paused)
        ops.grants["a1"] = obj("""{"grants":[],"suspended":[]}""")
        assertEquals(AgentResumption.RESUMED, m.resume("a1"))
        assertEquals(1, ops.calls.count { it == "resume:a1" })
        assertFalse(m.devices.value.first { it.id == "a1" }.paused)
    }

    @Test
    fun resumeNotFoundIsNoLongerPausedAndReadsAgain() = runTest {
        val ops = Ops().apply { refuse["resume"] = "not_found" }
        val m = manager(ops)
        m.refresh()
        assertEquals(AgentResumption.NOT_PAUSED, m.resume("a1"))
        assertTrue("grants:a1" in ops.calls)
    }

    @Test
    fun anyOtherResumeFailureIsThrownAndNothingIsReadAgain() = runTest {
        val ops = Ops().apply { refuse["resume"] = "internal" }
        val m = manager(ops)
        try {
            m.resume("a1")
            fail("a failed resume passed")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.OTHER, e.kind)
        }
        assertTrue(ops.calls.none { it.startsWith("grants") })
    }

    @Test
    fun eventsReachTheOpenPageAndOnlyTheKindsThatConcernIt() = runTest {
        val m = manager(Ops())
        val got = mutableListOf<AgentChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { m.changes.collect { got += it } }
        m.onEvent(sync("""{"kind":"leash.grant.changed","agent_id":"a1","grant_id":"g1","version":2}"""))
        m.onEvent(sync("""{"kind":"leash.grant.revoked","agent_id":"a1","grant_id":"g1"}"""))
        m.onEvent(sync("""{"kind":"leash.agent.suspended","agent_id":"a1","suspended":true}"""))
        m.onEvent(sync("""{"kind":"share.rule.changed","rule_id":"r1","version":1}"""))
        m.onEvent(sync("""{"kind":"share.rule.deleted","rule_id":"r1"}"""))
        m.onEvent(sync("""{"kind":"item.changed","item_id":"i1"}"""))
        assertEquals(
            listOf(AgentChange.Grants("a1"), AgentChange.Grants("a1"), AgentChange.Grants("a1"), AgentChange.Rule("r1"), AgentChange.Rule("r1")),
            got,
        )
    }

    // --- the answers to leash.grant.issue ---

    private fun failure(code: String, limit: VaultLimit? = null, body: String? = null): VaultFailure {
        val cause = VaultOpException("leash.grant.issue", code, "", body?.let(::obj))
        val kind = com.vettid.core.data.vault.VaultManager.opFailure(code)
        return VaultFailure(kind, code, cause = cause, limit = limit)
    }

    @Test
    fun aGrantAnswerIsSaved() {
        val r = GrantIssueAnswers.saved(obj("""{"grant_id":"g9","agent_id":"a1","version":1,"scope":"message.list","approval":"ask","delegation":"AAAA","sig":"BBBB"}"""))
        assertEquals("g9", (r as GrantIssueResult.Saved).grant.grantId)
        assertEquals("message.list", r.grant.scope)
    }

    @Test
    fun aGrantThatDoesNotReadIsNeverASavedPermission() {
        assertTrue(GrantIssueAnswers.saved(obj("""{}""")) is GrantIssueResult.Failed)
        assertTrue(GrantIssueAnswers.saved(obj("""{"grant_id":"g9"}""")) is GrantIssueResult.Failed)
    }

    @Test
    fun credentialLockedPromptsOnceAndASecondOneIsLockedAgain() {
        assertEquals(GrantIssueResult.CredentialLocked, GrantIssueAnswers.refused(failure("credential_locked"), resent = false))
        assertEquals(GrantIssueResult.LockedAgain, GrantIssueAnswers.refused(failure("credential_locked"), resent = true))
    }

    @Test
    fun theOtherRefusalsHaveTheirOwnAnswers() {
        assertEquals(GrantIssueResult.Conflict, GrantIssueAnswers.refused(failure("conflict"), false))
        assertEquals(GrantIssueResult.Limit, GrantIssueAnswers.refused(failure("limit", VaultLimit("agent_grants", 32)), false))
        assertEquals(GrantIssueResult.Limit, GrantIssueAnswers.refused(failure("limit"), false))
        assertEquals(GrantIssueResult.NotPaired, GrantIssueAnswers.refused(failure("not_found"), false))
        assertEquals(GrantIssueResult.OwnerCheck, GrantIssueAnswers.refused(failure("owner_check_required"), false))
    }

    @Test
    fun anotherLimitIsNotThePermissionsLimit() {
        assertTrue(GrantIssueAnswers.refused(failure("limit", VaultLimit("share_rules_subject", 64)), false) is GrantIssueResult.Failed)
    }

    @Test
    fun badRequestNamesTheFieldOnlyWhenItReadsAsAMemberName() {
        assertEquals(GrantIssueResult.BadRequest("per_hour"), GrantIssueAnswers.refused(failure("bad_request", body = """{"field":"per_hour"}"""), false))
        assertEquals(GrantIssueResult.BadRequest(null), GrantIssueAnswers.refused(failure("bad_request"), false))
        assertEquals(GrantIssueResult.BadRequest(null), GrantIssueAnswers.refused(failure("bad_request", body = """{"field":"Sam Lee's phone"}"""), false))
        assertEquals(GrantIssueResult.BadRequest(null), GrantIssueAnswers.refused(failure("bad_request", body = """{"field":"${"x".repeat(60)}"}"""), false))
    }

    @Test
    fun anythingElseKeepsItsFailure() {
        val e = failure("internal")
        assertEquals(e, (GrantIssueAnswers.refused(e, false) as GrantIssueResult.Failed).failure)
    }

    @Test
    fun anOpenAlarmFromTheUnlockEndsTheAttemptAndAWrongPasswordKeepsThePrompt() {
        assertEquals(GrantIssueResult.AlarmOpen, GrantIssueAnswers.unlockRefused(failure("credential_frozen")))
        assertEquals(GrantIssueResult.AlarmOpen, GrantIssueAnswers.unlockRefused(failure("rotation_required")))
        assertEquals(GrantIssueResult.OwnerCheck, GrantIssueAnswers.unlockRefused(failure("owner_check_required")))
        assertNull(GrantIssueAnswers.unlockRefused(failure("bad_password")))
        assertNull(GrantIssueAnswers.unlockRefused(failure("backoff")))
    }
}
