package com.vettid.feature.devices

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.vettid.core.crypto.invite.InviteKind
import com.vettid.core.crypto.invite.InviteQr
import com.vettid.core.data.devices.PairRole
import com.vettid.core.data.devices.PairingManager
import com.vettid.core.data.devices.PairingOps
import com.vettid.core.data.devices.PairingStage
import com.vettid.core.data.devices.PairingState
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * (GC #7370, as iOS) A pairing screen that is cleared late (Navigation may clear a popped screen after the next one started)
 * touches only the pairing it saw: the next screen's create, code or comparison is neither cancelled nor rejected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairHandoverTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val clock = Instant.parse("2026-10-26T12:00:00Z")

    private fun link(base: Instant = clock) = InviteQr(
        InviteKind.AGENT, "https://relay.vettid.test", "abcdefghijklmnopqrstuvwxyz", ByteArray(32) { 1 }, ByteArray(32) { 2 },
        base.plusSeconds(600).epochSecond,
    ).link()

    private inner class Ops : PairingOps {
        val calls: MutableList<String> = CopyOnWriteArrayList()
        var next = 1
        var gate: CompletableDeferred<Unit>? = null

        /** The phone's time the next code is made at (its end is 10 minutes on). */
        var base: Instant = clock

        override suspend fun create(role: PairRole): JsonObject {
            val id = "p${next++}"
            calls += "create:$id"
            gate?.await()
            return Json.parseToJsonElement("""{"pairing_id":"$id","link":"${link(base)}","exp":"${base.plusSeconds(600)}"}""") as JsonObject
        }

        override suspend fun approve(pairingId: String, sessionSeconds: Int?) {
            calls += "approve:$pairingId"
        }

        override suspend fun reject(pairingId: String) {
            calls += "reject:$pairingId"
        }
    }

    private fun TestScope.waitFor(calls: () -> Any? = { null }, until: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!until()) {
            check(System.nanoTime() < end) { "timed out waiting: ${calls()}" }
            runCurrent()
            Thread.sleep(5)
        }
    }

    private fun screen(m: PairingManager) = PairViewModel(SavedStateHandle(mapOf(PairRoute.HELD to false)), m, FakeVault())

    /** The way Navigation clears a popped screen. */
    private fun cleared(vm: PairViewModel) = ViewModelStore().apply { put("pair", vm) }.clear()

    private fun pending(id: String) = com.vettid.core.vault.VaultMessage(
        com.vettid.core.crypto.envelope.Inner(
            id = com.vettid.core.crypto.envelope.Ulid.new(), type = "device.pair.pending", ts = clock,
            body = """{"pairing_id":"$id","pending_id":"x","role":"agent","name":"Claude","sas":"123456"}""".toByteArray(),
        ),
    )

    @Test
    fun aScreenDoneThenClearedLeavesTheNextOnesCreateAlone() = runTest {
        val ops = Ops()
        val m = PairingManager(backgroundScope, ops = { ops }, now = { clock })
        val a = screen(m)
        waitFor({ "${m.state.value} ${ops.calls}" }) { m.state.value.stage is PairingStage.Code }
        a.cancel() // the member leaves A (rejects p1)
        waitFor({ "${m.state.value} ${ops.calls}" }) { "reject:p1" in ops.calls }
        ops.gate = CompletableDeferred()
        val b = screen(m)
        waitFor({ "${m.state.value} ${ops.calls}" }) { "create:p2" in ops.calls }
        assertEquals(PairingStage.Creating, m.state.value.stage)
        cleared(a)
        runCurrent()
        ops.gate!!.complete(Unit)
        waitFor({ "${m.state.value} ${ops.calls}" }) { m.state.value.stage is PairingStage.Code }
        assertEquals("p2", (m.state.value.stage as PairingStage.Code).code.pairingId)
        Thread.sleep(50)
        assertFalse("B's code is not rejected", "reject:p2" in ops.calls)
        cleared(b)
    }

    @Test
    fun aScreenClearedLateLeavesTheNextOnesCreateCodeAndComparisonAlone() = runTest {
        // B starting (Creating): A's code had ended, B's start replaced it.
        run {
            val ops = Ops()
            var now = clock
            val m = PairingManager(backgroundScope, ops = { ops }, now = { now })
            val a = screen(m)
            waitFor({ "${m.state.value} ${ops.calls}" }) { m.state.value.stage is PairingStage.Code }
            now = now.plusSeconds(700) // A's code past its end
            ops.base = now
            ops.gate = CompletableDeferred()
            screen(m)
            waitFor({ "${m.state.value} ${ops.calls}" }) { "create:p2" in ops.calls }
            cleared(a)
            runCurrent()
            Thread.sleep(50)
            assertEquals("B's create is not cancelled", PairingStage.Creating, m.state.value.stage)
            ops.gate!!.complete(Unit)
            waitFor({ "${m.state.value} ${ops.calls}" }) { m.state.value.stage is PairingStage.Code }
            assertEquals("p2", (m.state.value.stage as PairingStage.Code).code.pairingId)
            assertFalse("reject:p2" in ops.calls)
        }
        // B showing the code (A's live code, kept for B), then comparing it.
        for (compare in listOf(false, true)) {
            val ops = Ops()
            val m = PairingManager(backgroundScope, ops = { ops }, now = { clock })
            val a = screen(m)
            waitFor({ "${m.state.value} ${ops.calls}" }) { m.state.value.stage is PairingStage.Code }
            screen(m)
            runCurrent()
            if (compare) m.onEvent(pending("p1"))
            cleared(a)
            runCurrent()
            Thread.sleep(50)
            assertFalse("$compare: not rejected", "reject:p1" in ops.calls)
            assertTrue("$compare", if (compare) m.state.value.stage is PairingStage.Compare else m.state.value.stage is PairingStage.Code)
        }
        // B's pairing ended (rejected): A cleared late does not take B's final stage away before B read it.
        run {
            val ops = Ops()
            val m = PairingManager(backgroundScope, ops = { ops }, now = { clock })
            val a = screen(m)
            waitFor { m.state.value.stage is PairingStage.Code }
            val b = screen(m)
            runCurrent()
            m.onEvent(pending("p1"))
            b.reject()
            waitFor { m.state.value.stage is PairingStage.Rejected }
            cleared(a)
            runCurrent()
            assertTrue(m.state.value.stage is PairingStage.Rejected)
        }
    }

    /**
     * (Re-check High 1) Back on a comparison or while creating calls Done, then the screen is cleared: the comparison is
     * still rejected, and the create's answer is rejected when it arrives. Nothing is left approvable with no screen.
     */
    @Test
    fun backOnAComparisonOrWhileCreatingStillRejects() = runTest {
        // A comparison.
        run {
            val ops = Ops()
            val m = PairingManager(backgroundScope, ops = { ops }, now = { clock })
            val a = screen(m)
            waitFor { m.state.value.stage is PairingStage.Code }
            m.onEvent(pending("p1"))
            assertTrue(m.state.value.stage is PairingStage.Compare)
            a.done()
            cleared(a)
            waitFor { "reject:p1" in ops.calls }
            assertEquals(PairingState(), m.state.value)
        }
        // A create still out.
        run {
            val ops = Ops()
            ops.gate = CompletableDeferred()
            val m = PairingManager(backgroundScope, ops = { ops }, now = { clock })
            val a = screen(m)
            waitFor { "create:p1" in ops.calls }
            a.done()
            cleared(a)
            runCurrent()
            ops.gate!!.complete(Unit)
            waitFor { "reject:p1" in ops.calls }
            assertEquals(PairingState(), m.state.value)
        }
    }

    @Test
    fun aScreenClearedAloneStillRejectsItsOwnPairing() = runTest {
        val ops = Ops()
        val m = PairingManager(backgroundScope, ops = { ops }, now = { clock })
        val a = screen(m)
        waitFor({ "${m.state.value} ${ops.calls}" }) { m.state.value.stage is PairingStage.Code }
        cleared(a)
        waitFor({ "${m.state.value} ${ops.calls}" }) { "reject:p1" in ops.calls }
        assertEquals(PairingState(), m.state.value)
    }
}
