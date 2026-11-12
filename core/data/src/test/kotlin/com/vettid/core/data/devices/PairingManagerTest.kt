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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

private fun obj(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject

/** The comparison held, as a sheet that shows it would name it. */
private fun PairingManager.shown(): String = (state.value.stage as? PairingStage.Compare)?.pending?.pairingId ?: ""

/** The approval and the reject of what is shown (the screens bind both to the comparison they show, GC #7370, #7440). */
private suspend fun PairingManager.approve(sessionSeconds: Int?) = approve(sessionSeconds, shown())

private suspend fun PairingManager.reject() = reject(shown())

/** Pairing an agent or a desktop (ANDROID-PLAN 0.1.53 AA2; "Pair a desktop", owner decision 2026-11-05; VAULT-MESSAGING §6.7, §10.3). */
@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
@Suppress("LargeClass") // one scenario per rule of the pairing machine
class PairingManagerTest {
    private var clock = Instant.parse("2026-10-26T12:00:00Z")

    private fun link(kind: InviteKind = InviteKind.AGENT, exp: Instant = clock.plusSeconds(600)) =
        InviteQr(kind, "https://relay.vettid.test", "abcdefghijklmnopqrstuvwxyz", ByteArray(32) { 1 }, ByteArray(32) { 2 }, exp.epochSecond).link()

    private inner class Ops : PairingOps {
        var created = obj("""{"pairing_id":"p1","link":"${link()}","exp":"${clock.plusSeconds(600)}"}""")
        /** Written from the request threads (a reject and a create can be out at once): a list safe to add to and read concurrently. */
        val calls: MutableList<String> = CopyOnWriteArrayList()
        val refuse = mutableMapOf<String, String>()
        var onApprove: () -> Unit = {}

        val roles: MutableList<String> = CopyOnWriteArrayList()

        /** An answer per role (else [created]), and a gate that holds the next create (once) until it is completed. */
        val byRole = mutableMapOf<String, JsonObject>()
        var holdNext: CompletableDeferred<Unit>? = null

        /** Completed when an agent create reached the gate (the request runs on another thread, so a test must wait for it). */
        val held = CompletableDeferred<Unit>()

        override suspend fun create(role: PairRole): JsonObject {
            roles += role.wire
            calls += "create"
            refuse["create"]?.let { throw VaultOpException("device.pair.create", it) }
            val gate = holdNext
            if (gate != null && role == PairRole.AGENT) {
                holdNext = null
                held.complete(Unit)
                gate.await()
            }
            return byRole[role.wire] ?: created
        }

        /** Holds the next approval (once) until completed; [approveEntered] says it got there. */
        var holdApprove: CompletableDeferred<Unit>? = null
        val approveEntered = CompletableDeferred<Unit>()

        override suspend fun approve(pairingId: String, sessionSeconds: Int?) {
            calls += "approve:$pairingId:$sessionSeconds"
            holdApprove?.let {
                holdApprove = null
                approveEntered.complete(Unit)
                it.await()
            }
            onApprove()
            refuse["approve"]?.let { throw VaultOpException("device.pair.approve", it) }
        }

        /** Holds the next rejection (once) until completed; [rejectEntered] says it got there. */
        var holdReject: CompletableDeferred<Unit>? = null
        val rejectEntered = CompletableDeferred<Unit>()

        override suspend fun reject(pairingId: String) {
            calls += "reject:$pairingId"
            holdReject?.let {
                holdReject = null
                rejectEntered.complete(Unit)
                it.await()
            }
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
        assertEquals("Red  Team", p.name)
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

    private fun TestScope.watched(ops: Ops, left: MutableList<String>) =
        PairingManager(this, ops = { ops }, now = { clock }, knownDeviceIds = { setOf("me") }, readDevices = { emptyList() }, onCompareLeft = { left += it })

    @Test
    fun theComparisonLeftIsToldByItsPendingIdWhenApprovedRejectedEndedOrCleared() = runTest {
        // Approved.
        val a = mutableListOf<String>()
        val m = watched(Ops(), a)
        m.start()
        m.onEvent(pending())
        assertTrue("held: nothing yet", a.isEmpty())
        m.approve(3600)
        assertEquals(listOf("x"), a)
        // Rejected.
        val r = mutableListOf<String>()
        val n = watched(Ops(), r)
        n.start()
        n.onEvent(pending())
        n.reject()
        assertEquals(listOf("x"), r)
        // Ended by its time.
        val e = mutableListOf<String>()
        val o = watched(Ops(), e)
        o.start()
        o.onEvent(pending())
        advanceTimeBy(601_000)
        assertEquals(PairingStage.Ended, o.state.value.stage)
        assertEquals(listOf("x"), e)
        // Cleared (a lock).
        val c = mutableListOf<String>()
        val p = watched(Ops(), c)
        p.start()
        p.onEvent(pending())
        p.clear()
        assertEquals(listOf("x"), c)
    }

    @Test
    fun aRefusedApprovalThatKeepsTheComparisonDoesNotCloseIt() = runTest {
        val left = mutableListOf<String>()
        val ops = Ops().apply { refuse["approve"] = "owner_check_required" }
        val m = watched(ops, left)
        m.start()
        m.onEvent(pending())
        m.approve(3600)
        assertTrue(m.state.value.stage is PairingStage.Compare)
        assertTrue(left.isEmpty())
    }

    /** (GC #7440) A reject is bound to the comparison shown: one for another pairing does nothing, nothing sent. */
    @Test
    fun aRejectForAnotherPairingDoesNothing() = runTest {
        val ops = Ops()
        val m = manager(ops)
        m.start()
        m.onEvent(pending())
        m.reject("p0")
        assertEquals(listOf("create"), ops.calls)
        assertTrue(m.state.value.stage is PairingStage.Compare)
        m.reject("p1")
        assertEquals(listOf("create", "reject:p1"), ops.calls)
        assertTrue(m.state.value.stage is PairingStage.Rejected)
    }

    /** (GC #7370) An approval is bound to the comparison the sheet showed: one for another pairing is refused, nothing sent. */
    @Test
    fun anApprovalForAnotherPairingIsRefusedAndNothingSent() = runTest {
        val ops = Ops()
        val m = manager(ops)
        m.start()
        m.onEvent(pending())
        m.approve(3600, "p0")
        assertEquals(listOf("create"), ops.calls)
        val s = m.state.value
        assertTrue(s.stage is PairingStage.Compare && !s.busy)
        m.approve(3600, "p1")
        assertEquals(listOf("create", "approve:p1:3600"), ops.calls)
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

    /**
     * (Review Low 1, item 4) An earlier approval's search for its device, still waiting on the list, must not overwrite the
     * next pairing's Paired state when it ends: every start is a new generation, the same role's too.
     */
    @Test
    fun anEarlierApprovalsLateSearchLeavesTheNextPairingAlone() = runTest {
        val gate = CompletableDeferred<Unit>()
        val ops = Ops()
        val m = PairingManager(
            this, ops = { ops }, now = { clock }, knownDeviceIds = { emptySet() },
            readDevices = { gate.await(); listOf(agent("a1", "Claude on laptop")) },
        )
        m.start()
        m.onEvent(pending())
        val first = launch { m.approve(3600) }
        try {
            pump { m.state.value.stage is PairingStage.Paired } // the request runs on the vault guard's thread
            advanceTimeBy(10_500) // past its wait for the feed item: now on the list read, held
            m.acknowledge()
            ops.created = obj("""{"pairing_id":"p2","link":"${link()}","exp":"${clock.plusSeconds(600)}"}""")
            m.start()
            m.onEvent(pending(id = "p2", name = "Desk agent"))
            ops.onApprove = { m.onEvent(paidFeed("a2")) }
            m.approve(3600)
            assertEquals(PairingStage.Paired("Desk agent", "a2"), m.state.value.stage)
        } finally {
            gate.complete(Unit) // the earlier search runs NonCancellable: never left waiting
        }
        first.join()
        assertEquals("the next pairing's, untouched", PairingStage.Paired("Desk agent", "a2"), m.state.value.stage)
    }

    /** (iOS #242 `aStartAfterAPairedPairingCutsTheEarlierSearch`) Every start cuts the earlier pairing's id search: it reads nothing. */
    @Test
    fun aStartAfterAPairedPairingCutsTheEarlierSearch() = runTest {
        var reads = 0
        val ops = Ops()
        val m = PairingManager(
            this, ops = { ops }, now = { clock }, knownDeviceIds = { setOf("me") },
            readDevices = { reads++; emptyList() },
        )
        m.start()
        m.onEvent(pending())
        val task = launch { m.approve(3600) }
        pump { m.state.value.stage is PairingStage.Paired }
        advanceTimeBy(2_000)
        // The member reads the end and starts again while the search still waits.
        m.acknowledge()
        ops.created = obj("""{"pairing_id":"p2","link":"${link()}","exp":"${clock.plusSeconds(600)}"}""")
        m.start()
        advanceTimeBy(15_000)
        task.join()
        assertEquals(0, reads)
        assertEquals("p2", (m.state.value.stage as PairingStage.Code).code.pairingId)
    }

    /** (iOS #242 `anEarlierApprovalsEndLeavesTheNewerWatchAlone`, review nit 1) An earlier approval's end leaves a newer one's watch. */
    @Test
    fun anEarlierApprovalsEndLeavesTheNewerWatchAlone() = runTest {
        // The earlier search is held on the list read, so that it ends only once the newer approval watches.
        val reading = CompletableDeferred<Unit>()
        val ops = Ops()
        val m = PairingManager(
            this, ops = { ops }, now = { clock }, knownDeviceIds = { emptySet() },
            readDevices = { reading.await(); listOf(agent("a1", "Claude on laptop")) },
        )
        m.start()
        m.onEvent(pending())
        val first = launch { m.approve(3600) }
        val gate = CompletableDeferred<Unit>()
        try {
            pump { m.state.value.stage is PairingStage.Paired }
            advanceTimeBy(10_500) // on the list read
            m.clear()
            // A new pairing, approved while the earlier search is still ending.
            m.start()
            m.onEvent(pending())
            ops.holdApprove = gate
            ops.onApprove = { m.onEvent(paidFeed("agent-B")) }
            val second = launch { m.approve(3600) }
            pump { ops.approveEntered.isCompleted }
            // The earlier search ends now.
            reading.complete(Unit)
            first.join()
            gate.complete(Unit)
            advanceTimeBy(11_000)
            second.join()
        } finally {
            reading.complete(Unit)
            gate.complete(Unit)
        }
        assertEquals("agent-B", (m.state.value.stage as PairingStage.Paired).deviceId)
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

    /** VAULT-MESSAGING 0.83.11 section 11.14.10: `transfer_incomplete` to an approval that carries grants. */
    @Test
    fun transferIncompleteIsTheMoveNotFinishedTextAndNotTheUnlocks() = runTest {
        val ops = Ops().apply { refuse["approve"] = "transfer_incomplete" }
        val m = manager(ops)
        m.start()
        m.onEvent(pending())
        m.approve(3600)
        assertEquals(PairingStage.Ended, m.state.value.stage)
        com.vettid.core.data.vault.assertMoveNotFinished(m.state.value.error!!)
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

    /**
     * (Re-check Low 1) The screen left while its approval was out, and the vault then keeps the comparison (the daily check
     * due): it is rejected, not kept approvable with no screen.
     */
    @Test
    fun aComparisonWhoseScreenLeftDuringTheApprovalIsRejectedNotKept() = runTest {
        val ops = Ops().apply { refuse["approve"] = "owner_check_required" }
        val gate = CompletableDeferred<Unit>()
        ops.holdApprove = gate
        val m = manager(ops)
        val screen = Any()
        m.start(PairRole.AGENT, screen)
        m.onEvent(pending())
        val approval = launch { m.approve(3600) }
        try {
            pump { ops.approveEntered.isCompleted }
            m.leave(screen)
            runCurrent() // the leave's cancel runs now
            assertTrue("the approval is out: nothing changes yet", m.state.value.busy)
        } finally {
            gate.complete(Unit)
        }
        approval.join()
        pump { "reject:p1" in ops.calls }
        assertEquals(PairingState(), m.state.value)
        // The next pairing starts afresh (also one started with no screen named): its refused approval keeps its comparison.
        m.start()
        m.onEvent(pending())
        m.approve(3600)
        assertTrue("a new start forgets the earlier screen's leaving", m.state.value.stage is PairingStage.Compare)
        // With the screen still there, the same refusal keeps the comparison for another try (as before).
        val kept = manager(Ops().apply { refuse["approve"] = "owner_check_required" })
        kept.start(PairRole.AGENT, Any())
        kept.onEvent(pending())
        kept.approve(3600)
        assertTrue(kept.state.value.stage is PairingStage.Compare)
    }

    /**
     * (Re-check nit 1) A screen that takes the busy comparison over (a start that keeps it, or a claim) is there: a refusal of
     * the approval then keeps the comparison for another try, whatever the earlier screen did.
     */
    @Test
    fun aScreenThatTakesTheComparisonOverKeepsItAfterTheEarlierOneLeft() = runTest {
        for (byClaim in listOf(false, true)) {
            val ops = Ops().apply { refuse["approve"] = "owner_check_required" }
            val gate = CompletableDeferred<Unit>()
            ops.holdApprove = gate
            val m = manager(ops)
            val a = Any()
            m.start(PairRole.AGENT, a)
            m.onEvent(pending())
            val approval = launch { m.approve(3600) }
            try {
                pump { ops.approveEntered.isCompleted }
                m.leave(a)
                runCurrent() // the leave's cancel runs now
                assertTrue("$byClaim: still busy", m.state.value.busy)
                val b = Any()
                if (byClaim) m.claim(b) else m.start(PairRole.AGENT, b) // kept: the comparison is busy
            } finally {
                gate.complete(Unit)
            }
            approval.join()
            runCurrent()
            assertTrue("$byClaim: kept for another try", m.state.value.stage is PairingStage.Compare)
            assertFalse("$byClaim", "reject:p1" in ops.calls)
        }
    }

    /**
     * (Re-check nit 2) The screen left during the approval and the answer was lost: the vault may have taken it, so it is not
     * rejected; it ends with the outcome unknown, as before.
     */
    @Test
    fun aLostAnswerAfterTheScreenLeftIsNotRejected() = runTest {
        val ops = Ops()
        val gate = CompletableDeferred<Unit>()
        ops.holdApprove = gate
        val m = PairingManager(this, ops = { ops }, now = { clock }, knownDeviceIds = { setOf("me") }, readDevices = { emptyList() })
        ops.onApprove = { throw java.io.IOException("lost") }
        val a = Any()
        m.start(PairRole.AGENT, a)
        m.onEvent(pending())
        val approval = launch { m.approve(3600) }
        try {
            pump { ops.approveEntered.isCompleted }
            m.leave(a)
            runCurrent() // the leave's cancel runs now
        } finally {
            gate.complete(Unit)
        }
        pump {
            advanceTimeBy(500) // through the search for the device (none found)
            !m.state.value.busy
        }
        approval.join()
        assertFalse("not rejected", "reject:p1" in ops.calls)
        assertEquals(PairingStage.Ended, m.state.value.stage)
        assertTrue(m.state.value.unknownOutcome)
    }

    @Test
    fun aDueDailyCheckKeepsTheComparisonForAnotherTry() = runTest {
        val ops = Ops().apply { refuse["approve"] = "owner_check_required" }
        val m = manager(ops)
        val owner = Any()
        m.start(PairRole.AGENT, owner)
        m.onEvent(pending())
        m.approve(3600)
        assertTrue(m.state.value.stage is PairingStage.Compare)
        assertEquals(FailureKind.OWNER_CHECK_REQUIRED, m.state.value.error)
        ops.refuse.clear()
        m.acknowledge()
        assertEquals("(iOS #255) a start-time acknowledge with no owner leaves it", FailureKind.OWNER_CHECK_REQUIRED, m.state.value.error)
        m.acknowledge(Any())
        assertEquals("nor another screen's", FailureKind.OWNER_CHECK_REQUIRED, m.state.value.error)
        m.acknowledge(owner) // its owner, once the check passed
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

    private fun desktopLink() = link(InviteKind.DESKTOP)

    private fun TestScope.desktop(ops: Ops, known: Set<String>? = setOf("me"), listed: List<DeviceInfo> = emptyList()) =
        manager(ops, known, listed).also {
            ops.created = obj("""{"pairing_id":"p1","link":"${desktopLink()}","exp":"${clock.plusSeconds(600)}"}""")
        }

    private fun desktopDevice(id: String, name: String) = DeviceInfo(id, "desktop", name, null, null, null, null)

    @Test
    fun aDesktopCodeAsksForTheDesktopRoleAndTheStateSaysSo() = runTest {
        val ops = Ops()
        val m = desktop(ops)
        m.start(PairRole.DESKTOP)
        assertEquals(listOf("desktop"), ops.roles)
        assertTrue(m.state.value.stage is PairingStage.Code)
        assertEquals(PairRole.DESKTOP, m.state.value.role)
        // The agent's button still asks for an agent.
        val a = Ops()
        manager(a).also { it.start() }
        assertEquals(listOf("agent"), a.roles)
    }

    @Test
    fun anAgentLinkIsRefusedForADesktopPairingAndTheReverse() = runTest {
        val ops = Ops()
        val m = manager(ops)
        // Default (an agent link) for a desktop pairing: never shown.
        m.start(PairRole.DESKTOP)
        assertEquals(PairingStage.Idle, m.state.value.stage)
        assertEquals(FailureKind.OTHER, m.state.value.error)
        // A desktop link for an agent pairing: never shown.
        ops.created = obj("""{"pairing_id":"p1","link":"${desktopLink()}","exp":"${clock.plusSeconds(600)}"}""")
        m.acknowledge()
        m.start(PairRole.AGENT)
        assertEquals(PairingStage.Idle, m.state.value.stage)
        assertEquals(FailureKind.OTHER, m.state.value.error)
        // The right kind for each is shown.
        m.acknowledge()
        m.start(PairRole.DESKTOP)
        assertTrue(m.state.value.stage is PairingStage.Code)
    }

    @Test
    fun aDesktopPairingIsComparedAndApprovedWithNoGrants() = runTest {
        val ops = Ops()
        val m = desktop(ops)
        m.start(PairRole.DESKTOP)
        m.onEvent(pending(name = "Work laptop", role = "desktop"))
        val c = m.state.value.stage as PairingStage.Compare
        assertTrue(c.pending.comparable)
        assertEquals("123456", c.pending.sas)
        assertEquals(PairRole.DESKTOP, m.state.value.role)
        m.approve(3600)
        // The approval op has no place for grants: pairing id and the first session only.
        assertEquals(listOf("create", "approve:p1:3600"), ops.calls)
        assertTrue(m.state.value.stage is PairingStage.Paired)
        assertEquals(PairRole.DESKTOP, m.state.value.role)
    }

    @Test
    fun aDesktopPairingWhosePendingIsForAnAgentOrHasNoCodeCanOnlyBeRejected() = runTest {
        for ((sas, role) in listOf("123456" to "agent", "12345" to "desktop", "abcdef" to "desktop")) {
            val ops = Ops()
            val m = desktop(ops)
            m.start(PairRole.DESKTOP)
            m.onEvent(pending(sas = sas, role = role))
            assertFalse("$sas $role", (m.state.value.stage as PairingStage.Compare).pending.comparable)
            m.approve(3600)
            assertEquals(listOf("create"), ops.calls)
            m.reject()
            assertEquals(listOf("create", "reject:p1"), ops.calls)
        }
        // And the agent's pairing refuses a desktop's pending.
        val ops = Ops()
        val m = manager(ops)
        m.start()
        m.onEvent(pending(role = "desktop"))
        assertFalse((m.state.value.stage as PairingStage.Compare).pending.comparable)
    }

    @Test
    fun aMismatchRejectsADesktopPairing() = runTest {
        val ops = Ops()
        val m = desktop(ops)
        m.start(PairRole.DESKTOP)
        m.onEvent(pending(role = "desktop"))
        m.reject()
        assertEquals(listOf("create", "reject:p1"), ops.calls)
        assertEquals(PairingStage.Rejected(told = true), m.state.value.stage)
    }

    @Test
    fun aDesktopCodeExpiresAndALockClearsIt() = runTest {
        val ops = Ops()
        val m = desktop(ops)
        m.start(PairRole.DESKTOP)
        clock = clock.plusSeconds(601)
        advanceTimeBy(601_000)
        assertTrue(m.state.value.stage is PairingStage.CodeExpired)
        assertEquals(PairRole.DESKTOP, m.state.value.role)
        val n = desktop(Ops())
        n.start(PairRole.DESKTOP)
        n.onEvent(pending(role = "desktop"))
        n.clear()
        assertEquals(PairingState(), n.state.value)
        n.onEvent(pending(role = "desktop"))
        assertEquals(PairingStage.Idle, n.state.value.stage)
    }

    @Test
    fun theNewDesktopsIdComesFromTheListAsADesktopNotAnAgent() = runTest {
        val listed = listOf(agent("a1", "Work laptop"), desktopDevice("d1", "Work laptop"))
        val m = desktop(Ops(), listed = listed)
        m.start(PairRole.DESKTOP)
        m.onEvent(pending(name = "Work laptop", role = "desktop"))
        m.approve(3600)
        assertEquals("d1", (m.state.value.stage as PairingStage.Paired).deviceId)
    }

    private fun both(ops: Ops) {
        ops.byRole["agent"] = obj("""{"pairing_id":"pa","link":"${link()}","exp":"${clock.plusSeconds(600)}"}""")
        ops.byRole["desktop"] = obj("""{"pairing_id":"pd","link":"${desktopLink()}","exp":"${clock.plusSeconds(600)}"}""")
    }

    @Test
    fun theOtherButtonRejectsTheHeldCodeAndReplacesItInOneStep() = runTest {
        val ops = Ops().also { both(it) }
        val m = manager(ops)
        m.start(PairRole.AGENT)
        assertEquals("pa", (m.state.value.stage as PairingStage.Code).code.pairingId)
        m.start(PairRole.DESKTOP)
        // Already the desktop's code: nothing of the agent's is left to read, and the old id is told it is not wanted.
        val s = m.state.value
        assertEquals(PairRole.DESKTOP, s.role)
        assertEquals("pd", (s.stage as PairingStage.Code).code.pairingId)
        // (Idle runs the virtual time; the reject goes out in the manager's own scope, on a real thread: wait for it.)
        advanceUntilIdle()
        pump { ops.calls.contains("reject:pa") }
        // The reject is launched before the desktop's create and both run on the IO dispatcher at once, so either can reach
        // the vault first: only the agent's create comes first for sure, then the other two, once each, in either order.
        assertEquals("create", ops.calls.first())
        assertEquals(listOf("create", "reject:pa"), ops.calls.drop(1).sorted())
    }

    @Test
    fun theOtherButtonRejectsAHeldComparisonToo() = runTest {
        val ops = Ops().also { both(it) }
        val m = manager(ops)
        m.start(PairRole.AGENT)
        m.onEvent(pending(id = "pa"))
        m.start(PairRole.DESKTOP)
        assertEquals("pd", (m.state.value.stage as PairingStage.Code).code.pairingId)
        advanceUntilIdle()
        pump { ops.calls.contains("reject:pa") }
        assertTrue(ops.calls.contains("reject:pa"))
    }

    /**
     * Runs the test scheduler's current tasks until [until] holds, waiting for the request threads in real time but never
     * advancing the virtual clock (a code's own end must not fire). Gives up after [timeoutMs] so no test can hang.
     */
    private fun TestScope.pump(timeoutMs: Long = 10_000, until: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!until()) {
            check(System.nanoTime() < end) { "timed out waiting" }
            runCurrent()
            Thread.sleep(5)
        }
    }

    private fun held(m: PairingManager): String? = when (val st = m.state.value.stage) {
        is PairingStage.Code -> st.code.pairingId
        is PairingStage.CodeExpired -> st.pairingId
        else -> null
    }

    @Test
    fun aCreateOfTheOtherRoleStillInFlightIsSupersededDroppedAndRejected() = runTest {
        val ops = Ops().also { both(it) }
        val gate = CompletableDeferred<Unit>()
        ops.holdNext = gate
        val m = manager(ops)
        val first = launch { m.start(PairRole.AGENT) }
        pump { ops.held.isCompleted }
        assertTrue(m.state.value.stage is PairingStage.Creating)
        // The desktop button while the agent's create is out: the desktop code is made, not skipped.
        m.start(PairRole.DESKTOP)
        assertEquals("pd", (m.state.value.stage as PairingStage.Code).code.pairingId)
        gate.complete(Unit)
        pump { first.isCompleted && ops.calls.contains("reject:pa") }
        // The agent's late answer is dropped (the desktop code is still the one shown, exactly) and that pairing is rejected.
        assertEquals("pd", (m.state.value.stage as PairingStage.Code).code.pairingId)
        assertEquals(PairRole.DESKTOP, m.state.value.role)
        assertFalse(ops.calls.contains("reject:pd"))
    }

    @Test
    fun aSupersededCreateIsRejectedEvenWhenItsCallerWasCancelled() = runTest {
        val ops = Ops().also { both(it) }
        val gate = CompletableDeferred<Unit>()
        ops.holdNext = gate
        val m = manager(ops)
        val first = launch { m.start(PairRole.AGENT) }
        pump { ops.held.isCompleted }
        first.cancel()
        m.start(PairRole.DESKTOP)
        gate.complete(Unit)
        pump { ops.calls.contains("reject:pa") }
        assertEquals("pd", held(m))
        assertEquals(PairRole.DESKTOP, m.state.value.role)
    }

    @Test
    fun aLockDuringCreatingShowsNothingAndRejectsTheAnswerWhenItArrives() = runTest {
        val ops = Ops().also { both(it) }
        val gate = CompletableDeferred<Unit>()
        ops.holdNext = gate
        val m = manager(ops)
        val first = launch { m.start(PairRole.AGENT) }
        pump { ops.held.isCompleted }
        m.clear()
        assertEquals(PairingState(), m.state.value)
        gate.complete(Unit)
        pump { first.isCompleted && ops.calls.contains("reject:pa") }
        // Nothing of it was shown.
        assertEquals(PairingState(), m.state.value)
        assertEquals(listOf("create", "reject:pa"), ops.calls)
    }

    /**
     * (iOS #242 review, item 1) Leaving while the create is out: nothing is held, and its answer, when it arrives, is not shown
     * but rejected (no live code without a screen).
     */
    @Test
    fun leavingWhileTheCreateIsOutRejectsItsAnswer() = runTest {
        val ops = Ops().also { both(it) }
        val gate = CompletableDeferred<Unit>()
        ops.holdNext = gate
        val m = manager(ops)
        val first = launch { m.start(PairRole.AGENT) }
        pump { ops.held.isCompleted }
        m.leave()
        try {
            pump(timeoutMs = 2_000) { m.state.value == PairingState() }
        } finally {
            gate.complete(Unit) // the create runs NonCancellable: never left waiting
        }
        pump { first.isCompleted && ops.calls.contains("reject:pa") }
        assertEquals(PairingState(), m.state.value)
        assertEquals(listOf("create", "reject:pa"), ops.calls)
    }

    /** (iOS #242 review, item 2) A late pending that arrives before the code's timer has run is taken up to a minute after its end. */
    @Test
    fun aLatePendingBeforeTheTimerRanIsTakenForAMinute() = runTest {
        val m = manager(Ops())
        m.start()
        clock = clock.plusSeconds(630) // past the code's end, the timer not run yet (no time advanced)
        assertTrue(m.state.value.stage is PairingStage.Code)
        m.onEvent(pending())
        assertEquals("123456", (m.state.value.stage as PairingStage.Compare).pending.sas)

        val late = manager(Ops())
        late.start()
        clock = clock.plusSeconds(661)
        late.onEvent(pending())
        assertTrue("past the minute: dropped", late.state.value.stage is PairingStage.Code)
    }

    /** (iOS #242 review, item 3) "New code" after the code expired rejects the old pairing (a late handshake may still be live). */
    @Test
    fun aNewCodeAfterAnExpiredOneRejectsTheOld() = runTest {
        // As the screen does it: the expired stage read, then a start.
        val ops = Ops()
        val m = manager(ops)
        m.start()
        clock = clock.plusSeconds(601)
        advanceTimeBy(601_000)
        ops.created = obj("""{"pairing_id":"p2","link":"${link(exp = clock.plusSeconds(600))}"}""")
        m.acknowledge()
        m.start()
        pump { "reject:p1" in ops.calls }
        assertEquals("p2", (m.state.value.stage as PairingStage.Code).code.pairingId)
        assertEquals(listOf("create", "create", "reject:p1"), ops.calls.sorted()) // the reject goes in the manager's scope
        // A start straight from the expired stage, or from a code past its end whose timer has not run: the same.
        for (advance in listOf(true, false)) {
            val o = Ops()
            val n = manager(o)
            n.start()
            clock = clock.plusSeconds(601)
            if (advance) advanceTimeBy(601_000)
            o.created = obj("""{"pairing_id":"p3","link":"${link(exp = clock.plusSeconds(600))}"}""")
            n.start()
            pump { "reject:p1" in o.calls } // the reject goes in the manager's scope
            runCurrent()
            assertEquals("$advance", "p3", (n.state.value.stage as PairingStage.Code).code.pairingId)
            assertEquals("$advance: once", 1, o.calls.count { it == "reject:p1" })
        }
    }

    @Test
    fun anApprovalInFlightIsNotSwitchedByTheOtherButton() = runTest {
        val ops = Ops().also { both(it) }
        val m = manager(ops)
        m.start(PairRole.AGENT)
        m.onEvent(pending(id = "pa"))
        val gate = CompletableDeferred<Unit>()
        ops.holdApprove = gate
        val approving = launch { m.approve(3600) }
        pump { ops.approveEntered.isCompleted }
        val before = m.state.value
        assertTrue(before.busy)
        m.start(PairRole.DESKTOP)
        // Nothing changed: the same state and role, and no create (so no new generation either: the approval still settles).
        assertEquals(before, m.state.value)
        assertEquals(PairRole.AGENT, m.state.value.role)
        assertEquals(listOf("agent"), ops.roles)
        gate.complete(Unit)
        // The approval settles as Paired (not dropped by a new generation); its search for the id then needs virtual time.
        pump { m.state.value.stage is PairingStage.Paired }
        advanceUntilIdle()
        assertTrue(approving.isCompleted)
        assertEquals(PairRole.AGENT, m.state.value.role)
    }

    @Test
    fun theSameRoleKeepsAHeldCodeButTheOtherRoleReplacesAComparison() = runTest {
        val ops = Ops().also { both(it) }
        val m = manager(ops)
        m.start(PairRole.AGENT)
        m.start(PairRole.AGENT)
        assertEquals(listOf("create"), ops.calls)
        m.onEvent(pending(id = "pa"))
        m.start(PairRole.DESKTOP)
        advanceUntilIdle()
        assertEquals(PairRole.DESKTOP, m.state.value.role)
        // The replaced comparison's reject goes out in the manager's own scope, on a real thread: wait for it.
        pump { ops.calls.contains("reject:pa") }
        assertTrue(ops.calls.contains("reject:pa"))
    }

    @Test
    fun aReadablePairingWithTheWrongKindOrAnUnreadableLinkIsRejectedAtOnceAndNeverShown() = runTest {
        val cases = listOf(
            Triple(PairRole.AGENT, "p1", desktopLink()),
            Triple(PairRole.DESKTOP, "p2", link()),
            Triple(PairRole.AGENT, "p3", "not a link"),
            Triple(PairRole.DESKTOP, "p4", "not a link"),
        )
        for ((role, id, bad) in cases) {
            val ops = Ops()
            ops.created = obj("""{"pairing_id":"$id","link":"$bad","exp":"${clock.plusSeconds(600)}"}""")
            val m = manager(ops)
            m.start(role)
            // Nothing is shown, and the vault is told this pairing is not wanted (in the manager's scope).
            assertEquals(PairingStage.Idle, m.state.value.stage)
            assertEquals(FailureKind.OTHER, m.state.value.error)
            pump { ops.calls.contains("reject:$id") }
        }
        // No readable id: nothing to reject.
        val ops = Ops()
        ops.created = obj("""{"link":"${link()}"}""")
        val m = manager(ops)
        m.start(PairRole.AGENT)
        assertEquals(PairingStage.Idle, m.state.value.stage)
        assertEquals(listOf("create"), ops.calls)
    }

    @Test
    fun pressingTheOtherButtonJustAfterPairedStartsTheOtherPairingAndTheLateSearchTouchesNothing() = runTest {
        val ops = Ops().also { both(it) }
        val m = manager(ops, listed = listOf(desktopDevice("d1", "Work laptop")))
        m.start(PairRole.DESKTOP)
        m.onEvent(pending(id = "pd", name = "Work laptop", role = "desktop"))
        val approving = launch { m.approve(3600) }
        pump { m.state.value.stage is PairingStage.Paired }
        // The id search is still waiting (virtual time); the member pairs an agent instead.
        m.start(PairRole.AGENT)
        assertEquals("pa", held(m))
        assertEquals(PairRole.AGENT, m.state.value.role)
        advanceUntilIdle()
        assertTrue(approving.isCompleted)
        assertEquals(PairRole.AGENT, m.state.value.role)
        // The late search wrote nothing: the agent's code is still the one held.
        assertEquals("pa", held(m))
    }

    /** (iOS #255) A leave while the member's rejection is out leaves no mark once the rejection has settled. */
    @Test
    fun aSettledRejectionClearsTheLeaveMark() = runTest {
        val ops = Ops()
        val m = manager(ops)
        val owner = Any()
        m.start(PairRole.AGENT, owner)
        m.onEvent(pending())
        val gate = CompletableDeferred<Unit>()
        ops.holdReject = gate
        val rejecting = launch { m.reject("p1") }
        pump { ops.rejectEntered.isCompleted }
        m.leave(owner)
        pump { m.abandoned }
        gate.complete(Unit)
        rejecting.join()
        assertTrue(m.state.value.stage is PairingStage.Rejected)
        assertFalse(m.abandoned)
    }

    /**
     * (iOS #255) The same screen starting again does not undo its own leave: its approval, still out and then refused for the
     * daily check, is rejected rather than kept with no screen for it.
     */
    @Test
    fun theSameOwnersStartDoesNotResetItsLeave() = runTest {
        val ops = Ops().apply { refuse["approve"] = "owner_check_required" }
        val m = manager(ops)
        val owner = Any()
        m.start(PairRole.AGENT, owner)
        m.onEvent(pending())
        val gate = CompletableDeferred<Unit>()
        ops.holdApprove = gate
        val approving = launch { m.approve(3600) }
        pump { ops.approveEntered.isCompleted }
        m.leave(owner)
        pump { m.abandoned }
        try {
            m.start(PairRole.AGENT, owner) // returns early (the comparison is busy); the same owner: the mark stays
            assertTrue(m.abandoned)
            gate.complete(Unit)
            approving.join()
            runCurrent()
            assertEquals("rejected, not kept with no screen", PairingState(), m.state.value)
            pump { "reject:p1" in ops.calls } // the reject goes on a request thread
        } finally {
            gate.complete(Unit)
            m.clear() // nothing of a kept comparison (its timer) outlives a failed run
        }
    }

    /**
     * (iOS #255) A rejection that settles late, of an earlier pairing, leaves a later comparison's leave mark alone: that
     * comparison's approval, refused for the daily check after its screen left, is still rejected, not kept with no screen.
     */
    @Test
    fun aLateRejectionOfAnEarlierPairingLeavesALaterOnesMarkAlone() = runTest {
        val ops = Ops()
        val m = manager(ops)
        m.start(PairRole.AGENT, Any())
        m.onEvent(pending("p1"))
        val rejectGate = CompletableDeferred<Unit>()
        ops.holdReject = rejectGate
        val rejecting = launch { m.reject("p1") }
        pump { ops.rejectEntered.isCompleted }
        m.clear() // the vault locked; it opens again, and a new screen pairs p2
        ops.created = obj("""{"pairing_id":"p2","link":"${link()}","exp":"${clock.plusSeconds(600)}"}""")
        val owner = Any()
        m.start(PairRole.AGENT, owner)
        m.onEvent(pending("p2"))
        ops.refuse["approve"] = "owner_check_required"
        val approveGate = CompletableDeferred<Unit>()
        ops.holdApprove = approveGate
        val approving = launch { m.approve(3600) }
        try {
            pump { ops.approveEntered.isCompleted }
            m.leave(owner)
            pump { m.abandoned }
            rejectGate.complete(Unit)
            rejecting.join()
            assertTrue("p1's late rejection leaves p2's mark", m.abandoned)
            approveGate.complete(Unit)
            approving.join()
            runCurrent()
            assertEquals("p2 rejected, not kept with no screen", PairingState(), m.state.value)
            pump { "reject:p2" in ops.calls }
        } finally {
            rejectGate.complete(Unit)
            approveGate.complete(Unit)
            m.clear()
        }
    }
}
