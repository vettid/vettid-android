package com.vettid.feature.devices

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.crypto.invite.InviteKind
import com.vettid.core.crypto.invite.InviteQr
import com.vettid.core.data.devices.PairRole
import com.vettid.core.data.devices.PairingManager
import com.vettid.core.data.devices.PairingOps
import com.vettid.core.data.devices.PairingStage
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.testing.FakeVault
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * (iOS #255) A refused approval that one pairing screen kept for another try (the daily check was due) is not cleared by a
 * later screen's start-time acknowledge; a new screen over it clears it once its start made it the owner, and only if the
 * daily check is known to have passed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairKeptRefusalTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val clock = Instant.parse("2026-10-26T12:00:00Z")

    private fun link() = InviteQr(
        InviteKind.AGENT, "https://relay.vettid.test", "abcdefghijklmnopqrstuvwxyz", ByteArray(32) { 1 }, ByteArray(32) { 2 },
        clock.plusSeconds(600).epochSecond,
    ).link()

    private inner class Ops : PairingOps {
        val calls: MutableList<String> = CopyOnWriteArrayList()

        override suspend fun create(role: PairRole): JsonObject {
            calls += "create"
            return Json.parseToJsonElement("""{"pairing_id":"p1","link":"${link()}","exp":"${clock.plusSeconds(600)}"}""") as JsonObject
        }

        override suspend fun approve(pairingId: String, sessionSeconds: Int?) {
            calls += "approve:$pairingId"
            throw VaultOpException("device.pair.approve", "owner_check_required")
        }

        override suspend fun reject(pairingId: String) {
            calls += "reject:$pairingId"
        }
    }

    private fun pending() = com.vettid.core.vault.VaultMessage(
        com.vettid.core.crypto.envelope.Inner(
            id = com.vettid.core.crypto.envelope.Ulid.new(), type = "device.pair.pending", ts = clock,
            body = """{"pairing_id":"p1","pending_id":"x","role":"agent","name":"Claude on laptop","sas":"123456"}""".toByteArray(),
        ),
    )

    private fun TestScope.waitFor(until: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!until()) {
            check(System.nanoTime() < end) { "timed out" }
            runCurrent()
            Thread.sleep(5)
        }
    }

    private fun view(state: OwnerCheckState) =
        OwnerCheckView(state, Instant.now().plusSeconds(3_600), 86_400, 0, hold = true, holdOffUntil = null)

    /** A first screen whose approval was refused for the daily check: the comparison is kept with the refusal. */
    private fun TestScope.keptRefusal(m: PairingManager, vault: FakeVault): PairViewModel {
        val a = PairViewModel(SavedStateHandle(mapOf(PairRoute.HELD to false)), m, vault)
        waitFor { m.state.value.stage is PairingStage.Code }
        m.onEvent(pending())
        a.codesMatch("p1")
        a.pair()
        waitFor { m.state.value.error == FailureKind.OWNER_CHECK_REQUIRED }
        assertTrue(m.state.value.stage is PairingStage.Compare)
        return a
    }

    @Test
    fun aNewScreenClearsAKeptRefusalAfterItsStartWhenTheCheckPassed() = runTest {
        val vault = FakeVault().also { it.ownerCheck.value = view(OwnerCheckState.HELD) }
        val m = PairingManager(backgroundScope, ops = { Ops() }, now = { clock })
        keptRefusal(m, vault)
        vault.ownerCheck.value = view(OwnerCheckState.OK)
        PairViewModel(SavedStateHandle(mapOf(PairRoute.HELD to false)), m, vault)
        waitFor { m.state.value.error == null }
        assertTrue("the comparison stays, for another try", m.state.value.stage is PairingStage.Compare)
    }

    @Test
    fun aNewScreenLeavesAKeptRefusalWhileTheCheckIsStillDue() = runTest {
        val vault = FakeVault().also { it.ownerCheck.value = view(OwnerCheckState.HELD) }
        val m = PairingManager(backgroundScope, ops = { Ops() }, now = { clock })
        keptRefusal(m, vault)
        PairViewModel(SavedStateHandle(mapOf(PairRoute.HELD to false)), m, vault)
        repeat(20) { runCurrent(); Thread.sleep(5) }
        assertEquals(FailureKind.OWNER_CHECK_REQUIRED, m.state.value.error)
    }

    @Test
    fun theCheckPassedRuleIsFailSafe() {
        val vault = FakeVault()
        vault.ownerCheck.value = null
        assertEquals("not known: not passed", false, checkPassed(vault))
        vault.ownerCheck.value = view(OwnerCheckState.HELD)
        assertEquals(false, checkPassed(vault))
        vault.ownerCheck.value = view(OwnerCheckState.OK)
        assertEquals(true, checkPassed(vault))
        assertNull(null)
    }
}
