package com.vettid.app.calls

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rule 3 (the release gate): the calls and the wallets follow the vault manager's own `releaseUpdate.required`. Module
 * wiring has no unit test harness, so it is read from the source; the rules themselves are tested in
 * `CallsReleaseGateTest` and `WalletWiringTest`.
 */
class ReleaseGateAppWiringTest {
    private fun code(path: String) = File(path).readLines().map { it.substringBefore("//").trim() }

    @Test
    fun theCallsFollowTheGate() {
        val c = code("src/calls/kotlin/com/vettid/app/calls/CallsModule.kt")
        assertTrue(c.contains("override val unlocked: Flow<Boolean> = callsUnlocked(m.phase, m.opens, m.releaseUpdate.required)"))
        assertTrue(c.contains("override val unlockedNow: Boolean get() = " + "callsOpen(m.phase.value, m.releaseUpdate.required.value)"))
    }

    /** No ring while the daily owner check is due or held (owner decision a, 2026-11-17): the same rule as presence and location. */
    @Test
    fun theCallsFollowTheOwnerCheck() {
        val c = code("src/calls/kotlin/com/vettid/app/calls/CallsModule.kt")
        assertTrue(c.contains("override val ownerCheckGated: Boolean get() = " + "m.ownerCheck.ownerCheck.value?.gated(java.time.Instant.now()) == true"))
    }

    @Test
    fun theWalletsFollowTheGate() {
        val c = code("src/wallet/kotlin/com/vettid/app/wallet/WalletModule.kt")
        val open = "val open = combine(manager.phase, manager.ownerCheck.ownerCheck, manager.opens, " +
            "manager.releaseUpdate.required, ::sessionOf)"
        assertTrue(c.contains(open))
    }
}
