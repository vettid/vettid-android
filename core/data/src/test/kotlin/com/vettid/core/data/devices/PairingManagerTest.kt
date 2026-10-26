// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.devices

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.crypto.invite.InviteKind
import com.vettid.core.crypto.invite.InviteQr
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private fun obj(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject

/** Pairing an agent (ANDROID-PLAN 0.1.53 AA2; VAULT-MESSAGING §6.7, §10.3). */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingManagerTest {
    private var clock = Instant.parse("2026-10-26T12:00:00Z")

    private fun link(kind: InviteKind = InviteKind.AGENT, exp: Instant = clock.plusSeconds(600)) =
        InviteQr(kind, "https://relay.vettid.test", "abcdefghijklmnopqrstuvwxyz", ByteArray(32) { 1 }, ByteArray(32) { 2 }, exp.epochSecond).link()

    private inner class Ops : PairingOps {
        var created = obj("""{"pairing_id":"p1","link":"${link()}","exp":"${clock.plusSeconds(600)}"}""")
        val calls = mutableListOf<String>()
        val refuse = mutableMapOf<String, String>()
        var onApprove: () -> Unit = {}

        override suspend fun create(): JsonObject {
            calls += "create"
            refuse["create"]?.let { throw VaultOpException("device.pair.create", it) }
            return created
        }

        override suspend fun approve(pairingId: String, sessionSeconds: Int?) {
            calls += "approve:$pairingId:$sessionSeconds"
            onApprove()
            refuse["approve"]?.let { throw VaultOpException("device.pair.approve", it) }
        }

        override suspend fun reject(pairingId: String) {
            calls += "reject:$pairingId"
            refuse["reject"]?.let { throw VaultOpException("device.pair.reject", it) }
        }
    }

    private fun TestScope.manager(
        ops: Ops,
        known: Set<String>? = setOf("me"),
        listed: List<DeviceInfo> = emptyList(),
    ) = PairingManager(this, ops = { ops }, now = { clock }, knownDeviceIds = { known }, readDevices = { listed })

    private fun event(type: String, body: String) = VaultMessage(Inner(id = Ulid.new(), type = type, ts = clock, body = body.toByteArray()))

    private fun pending(id: String = "p1", name: String = "Claude on laptop", sas: String = "123456", role: String = "agent") =
        event("device.pair.pending", """{"pairing_id":"$id","pending_id":"x","role":"$role","name":"$name","sas":"$sas"}""")

    private fun paidFeed(deviceId: String) =
        event("feed.event", """{"item_id":"i","seq":1,"kind":"device.paired","at":"2026-10-26T12:00:00Z","status":"unread","device_id":"$deviceId"}""")

    private fun agent(id: String, name: String) = DeviceInfo(id, "agent", name, null, null, null, null)

    @Test
    fun aCodeIsMadeForAnAgentOnly() = runTest {
        val ops = Ops()
        val m = manager(ops)
        m.start()
        val s = m.state.value.stage as PairingStage.Code
        assertEquals("p1", s.code.pairingId)
        assertEquals(listOf("create"), ops.calls)
        // The code never reaches a text form (a log, a crash report).
        assertFalse(s.code.toString().contains(s.code.link))
    }

    @Test
    fun aLinkThatIsNotAnAgentPairingOrDoesNotReadIsNeverShown() = runTest {
        val ops = Ops()
        val m = manager(ops)
        ops.created = obj("""{"pairing_id":"p1","link":"${link(InviteKind.DESKTOP)}","exp":"${clock.plusSeconds(600)}"}""")
        m.start()
        assertEquals(PairingStage.Idle, m.state.value.stage)
        assertEquals(FailureKind.OTHER, m.state.value.error)
        ops.created = obj("""{"pairing_id":"p1","link":"not a link"}""")
        m.start()
        assertEquals(PairingStage.Idle, m.state.value.stage)
        ops.created = obj("""{"link":"${link()}"}""")
        m.start()
        assertEquals(PairingStage.Idle, m.state.value.stage)
    }

    @Test
    fun theCodeNeverOutlivesTenMinutes() = runTest {
        val ops = Ops()
        ops.created = obj("""{"pairing_id":"p1","link":"${link(exp = clock.plusSeconds(86_400))}","exp":"${clock.plusSeconds(86_400)}"}""")
        val m = manager(ops)
        m.start()
        val code = (m.state.value.stage as PairingStage.Code).code
        assertTrue(code.exp <= clock.plusSeconds(11 * 60))
    }

    @Test
    fun aFailedCodeSaysSoAndKeepsNothing() = runTest {
        val ops = Ops().apply { refuse["create"] = "owner_check_required" }
        val m = manager(ops)
        m.start()
        assertEquals(PairingStage.Idle, m.state.value.stage)
        assertEquals(FailureKind.OWNER_CHECK_REQUIRED, m.state.value.error)
        assertFalse(m.state.value.busy)
        // A second ask is made when the first failed.
        ops.refuse.clear()
        m.acknowledge()
        m.start()
        assertTrue(m.state.value.stage is PairingStage.Code)
    }

    @Test
    fun theCodeExpiresUnusedAndNothingIsPaired() = runTest {
        val ops = Ops()
        val m = manager(ops)
        m.start()
        clock = clock.plusSeconds(601)
        advanceTimeBy(601_000)
        assertEquals(PairingStage.CodeExpired("p1", clock.minusSeconds(1)), m.state.value.stage)
        // "New code" makes a new pairing.
        ops.created = obj("""{"pairing_id":"p2","link":"${link(exp = clock.plusSeconds(600))}"}""")
        m.acknowledge()
        m.start()
        assertEquals("p2", (m.state.value.stage as PairingStage.Code).code.pairingId)
    }

    @Test
    fun aLateAnswerIsTakenForAMinuteAfterTheCodeEndedAndStillNeedsTheComparison() = runTest {
        val m = manager(Ops())
        m.start()
        clock = clock.plusSeconds(601)
        advanceTimeBy(601_000)
        m.onEvent(pending(id = "other"))
        assertTrue(m.state.value.stage is PairingStage.CodeExpired)
        m.onEvent(pending())
        assertEquals("123456", (m.state.value.stage as PairingStage.Compare).pending.sas)

        val late = manager(Ops())
        late.start()
        clock = clock.plusSeconds(601)
        advanceTimeBy(601_000)
        clock = clock.plusSeconds(61)
        late.onEvent(pending())
        assertTrue(late.state.value.stage is PairingStage.CodeExpired)
    }

    @Test
    fun theAgentsAnswerShowsTheDigitsAndASanitisedName() = runTest {
        val m = manager(Ops())
        m.start()
        m.onEvent(pending(name = "  Red\\u202e  Team  ", sas = "123 456"))
        val p = (m.state.value.stage as PairingStage.Compare).pending
        assertEquals("123456", p.sas)
        assertEquals("Red Team", p.name)
        assertTrue(p.comparable)
        assertFalse(p.toString().contains("123456"))
    }

    @Test
    fun anEventForAnotherPairingOrWithNoCodeHeldChangesNothing() = runTest {
        val m = manager(Ops())
        // Nothing held (the process started again): nothing to compare.
        m.onEvent(pending())
        assertEquals(PairingStage.Idle, m.state.value.stage)
        m.start()
        m.onEvent(pending(id = "other"))
        assertTrue(m.state.value.stage is PairingStage.Code)
        // The second event for the same pairing does not replace the first.
        m.onEvent(pending(sas = "111111"))
        m.onEvent(pending(sas = "222222"))
        assertEquals("111111", (m.state.value.stage as PairingStage.Compare).pending.sas)
    }

    @Test
    fun aCodeThatIsNotSixDigitsOrNotAnAgentCanOnlyBeRejected() = runTest {
        for ((sas, role) in listOf("12345" to "agent", "abcdef" to "agent", "1234567" to "agent", "123456" to "desktop")) {
            val ops = Ops()
            val m = manager(ops)
            m.start()
            m.onEvent(pending(sas = sas, role = role))
            assertFalse("$sas $role", (m.state.value.stage as PairingStage.Compare).pending.comparable)
            m.approve(3600)
            assertEquals(listOf("create"), ops.calls)
            m.reject()
            assertEquals(listOf("create", "reject:p1"), ops.calls)
            assertEquals(PairingStage.Rejected(told = true), m.state.value.stage)
        }
    }

    @Test
    fun approvingSendsTheSessionAndNeverGrants() = runTest {
        val ops = Ops()
        val m = manager(ops)
        m.start()
        m.onEvent(pending())
        // A device.paired item from before the approval is not this pairing's.
        m.onEvent(paidFeed("agent-9"))
        m.approve(3600)
        assertEquals(listOf("create", "approve:p1:3600"), ops.calls)
        val s = m.state.value.stage as PairingStage.Paired
        assertEquals("Claude on laptop", s.name)
    }

    @Test
    fun noAccessYetSendsNoSession() = runTest {
        val ops = Ops()
        val m = manager(ops)
        m.start()
        m.onEvent(pending())
        m.approve(null)
        assertTrue(ops.calls.contains("approve:p1:null"))
    }

    @Test
    fun aSessionOutsideTheVaultsBoundsIsNotSent() = runTest {
        val ops = Ops()
        val m = manager(ops)
        m.start()
        m.onEvent(pending())
        m.approve(59)
        m.approve(86_401)
        assertEquals(listOf("create"), ops.calls)
        assertTrue(m.state.value.stage is PairingStage.Compare)
    }

    @Test
    fun theNewAgentsIdComesFromTheFeedItem() = runTest {
        val ops = Ops()
        val m = manager(ops)
        // The vault's feed item for the new agent arrives while the approval is being answered.
        ops.onApprove = { m.onEvent(paidFeed("agent-9")) }
        m.start()
        m.onEvent(pending())
        m.approve(3600)
        assertEquals("agent-9", (m.state.value.stage as PairingStage.Paired).deviceId)
    }

    @Test
    fun withoutTheFeedItemTheListNamesTheOneNewAgentOrNone() = runTest {
        val one = manager(Ops(), listed = listOf(agent("me2", "Other"), agent("new", "Claude on laptop")), known = setOf("me", "me2"))
        one.start()
        one.onEvent(pending())
        one.approve(3600)
        assertEquals("new", (one.state.value.stage as PairingStage.Paired).deviceId)

        // Two that fit: the list opens instead (no id).
        val two = manager(Ops(), listed = listOf(agent("n1", "Claude on laptop"), agent("n2", "Claude on laptop")))
        two.start()
        two.onEvent(pending())
        two.approve(3600)
        assertNull((two.state.value.stage as PairingStage.Paired).deviceId)

        // The list was never read before the approval: its baseline is read then, so the agent is not "new" to it (no guess).
        val unknown = manager(Ops(), known = null, listed = listOf(agent("n1", "Claude on laptop")))
        unknown.start()
        unknown.onEvent(pending())
        unknown.approve(3600)
        assertNull((unknown.state.value.stage as PairingStage.Paired).deviceId)
    }

    @Test
    fun aRefusedApprovalIsAnEndedPairingAndGrantsNothing() = runTest {
        val ops = Ops().apply { refuse["approve"] = "not_found" }
        val m = manager(ops)
        m.start()
        m.onEvent(pending())
        m.approve(3600)
        assertEquals(PairingStage.Ended, m.state.value.stage)
        assertFalse(m.state.value.unknownOutcome)
        // A second approval of an ended pairing is not sent.
        m.approve(3600)
        assertEquals(listOf("create", "approve:p1:3600"), ops.calls)
    }

    @Test
    fun anApprovalThatGotNoAnswerSaysTheOutcomeIsUnknown() = runTest {
        val ops = Ops().apply { onApprove = { throw java.io.IOException() } }
        val m = manager(ops)
        m.start()
        m.onEvent(pending())
        m.approve(3600)
        // Not asserted either way: nothing was confirmed, and the screen says so.
        assertEquals(PairingStage.Ended, m.state.value.stage)
        assertTrue(m.state.value.unknownOutcome)
        assertFalse(m.state.value.busy)
    }

    @Test
    fun aScreenThatLeavesWhileTheApprovalIsInFlightDoesNotStrandThePairing() = runTest {
        val ops = Ops().apply { onApprove = { throw java.io.IOException() } }
        val m = manager(ops)
        m.start()
        m.onEvent(pending())
        val screen = launch { m.approve(3600) }
        while ("approve:p1:3600" !in ops.calls) {
            delay(1)
            Thread.sleep(1)
        }
        screen.cancel()
        screen.join()
        assertFalse(m.state.value.busy)
        assertEquals(PairingStage.Ended, m.state.value.stage)
        // Nothing is left watching or busy: a new code can be made.
        ops.created = obj("""{"pairing_id":"p2","link":"${link()}"}""")
        m.acknowledge()
        m.start()
        assertEquals("p2", (m.state.value.stage as PairingStage.Code).code.pairingId)
    }

    @Test
    fun cancellingAnExpiredCodeStillTellsTheVault() = runTest {
        val ops = Ops()
        val m = manager(ops)
        m.start()
        clock = clock.plusSeconds(601)
        advanceTimeBy(601_000)
        m.cancel()
        assertEquals(PairingStage.Idle, m.state.value.stage)
        assertEquals(listOf("create", "reject:p1"), ops.calls)
    }

    @Test
    fun anUnansweredApprovalThatTheFeedItemConfirmsIsPaired() = runTest {
        val ops = Ops().apply { onApprove = { throw java.io.IOException() } }
        val m = manager(ops)
        m.start()
        m.onEvent(pending())
        // The device.paired item arrives after the request gave up, within the wait.
        val late = launch {
            delay(3_000)
            m.onEvent(paidFeed("agent-9"))
        }
        m.approve(3600)
        late.join()
        assertEquals(PairingStage.Paired("Claude on laptop", "agent-9"), m.state.value.stage)
    }

    @Test
    fun anUnansweredApprovalThatTheListConfirmsIsPaired() = runTest {
        val ops = Ops().apply { onApprove = { throw java.io.IOException() } }
        val m = manager(ops, listed = listOf(agent("new", "Claude on laptop")))
        m.start()
        m.onEvent(pending())
        m.approve(3600)
        assertEquals(PairingStage.Paired("Claude on laptop", "new"), m.state.value.stage)
    }

    @Test
    fun aFeedItemOfADeviceListedBeforeOrFromBeforeTheApprovalIsNotTheNewAgent() = runTest {
        val ops = Ops()
        val m = manager(ops, known = setOf("me", "old"))
        m.start()
        m.onEvent(pending())
        ops.onApprove = {
            m.onEvent(paidFeed("old"))
            m.onEvent(event("feed.event", """{"item_id":"i","seq":1,"kind":"device.paired","at":"2026-10-25T12:00:00Z","status":"unread","device_id":"stale"}"""))
        }
        m.approve(3600)
        assertNull((m.state.value.stage as PairingStage.Paired).deviceId)
    }

    @Test
    fun aDueDailyCheckKeepsTheComparisonForAnotherTry() = runTest {
        val ops = Ops().apply { refuse["approve"] = "owner_check_required" }
        val m = manager(ops)
        m.start()
        m.onEvent(pending())
        m.approve(3600)
        assertTrue(m.state.value.stage is PairingStage.Compare)
        assertEquals(FailureKind.OWNER_CHECK_REQUIRED, m.state.value.error)
        ops.refuse.clear()
        m.acknowledge()
        assertNull(m.state.value.error)
        m.approve(3600)
        assertTrue(m.state.value.stage is PairingStage.Paired)
    }

    @Test
    fun theComparisonEndsAtTenMinutesAndCannotBeApprovedAfter() = runTest {
        val ops = Ops()
        val m = manager(ops)
        m.start()
        m.onEvent(pending())
        clock = clock.plusSeconds(601)
        // Even before the timer ran, an approval is refused locally.
        m.approve(3600)
        assertEquals(listOf("create"), ops.calls)
        advanceTimeBy(601_000)
        assertEquals(PairingStage.Ended, m.state.value.stage)
    }

    @Test
    fun rejectingTellsTheVaultAndAFailureOfThatIsSaid() = runTest {
        val ops = Ops()
        val m = manager(ops)
        m.start()
        m.onEvent(pending())
        m.reject()
        assertEquals(PairingStage.Rejected(told = true), m.state.value.stage)
        m.acknowledge()
        assertEquals(PairingStage.Idle, m.state.value.stage)

        val bad = Ops().apply { refuse["reject"] = "internal" }
        val n = manager(bad)
        n.start()
        n.onEvent(pending())
        n.reject()
        // Nothing is paired either way; the screen says the vault could not be told.
        assertEquals(PairingStage.Rejected(told = false), n.state.value.stage)
    }

    @Test
    fun cancellingRejectsAndHoldsNothing() = runTest {
        val ops = Ops()
        val m = manager(ops)
        m.start()
        m.cancel()
        assertEquals(PairingStage.Idle, m.state.value.stage)
        assertEquals(listOf("create", "reject:p1"), ops.calls)
        // A comparison left is rejected too.
        m.start()
        m.onEvent(pending())
        m.cancel()
        assertEquals(PairingStage.Idle, m.state.value.stage)
        assertTrue(ops.calls.last() == "reject:p1")
    }

    @Test
    fun aLockDropsEverythingAndALateAnswerIsNotApplied() = runTest {
        val ops = Ops()
        val m = manager(ops)
        m.start()
        m.onEvent(pending())
        m.clear()
        assertEquals(PairingState(), m.state.value)
        // The agent's late event for the dropped pairing is not taken.
        m.onEvent(pending())
        assertEquals(PairingStage.Idle, m.state.value.stage)
        m.approve(3600)
        assertEquals(listOf("create"), ops.calls)
    }

    @Test
    fun aSecondStartKeepsALiveCodeAndAComparison() = runTest {
        val ops = Ops()
        val m = manager(ops)
        m.start()
        m.start()
        assertEquals(listOf("create"), ops.calls)
        m.onEvent(pending())
        m.start()
        assertEquals(listOf("create"), ops.calls)
        assertTrue(m.state.value.stage is PairingStage.Compare)
    }
}
