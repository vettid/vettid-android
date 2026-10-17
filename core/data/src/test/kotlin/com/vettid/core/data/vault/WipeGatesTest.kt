package com.vettid.core.data.vault

import com.vettid.core.altchan.RecoveryCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** No session opens and no vault is enrolled while a wipe runs or stays unfinished. */
class WipeGatesTest {
    private fun record(eraseFailed: Boolean = false) =
        AccountRecord(LocalAccount(userGuid = "u"), readable = true, persist = {}, eraseFailed = eraseFailed)

    private suspend fun refusal(r: AccountRecord): VaultFailure? = try {
        SessionGate.open(r, null) { "session" }
        null
    } catch (e: VaultFailure) {
        e
    }

    @Test
    fun aSessionOpensWhenNoWipeRuns() = runTest {
        val r = record()
        assertEquals("session", SessionGate.open(r, null) { "session" })
        assertEquals("current", SessionGate.open(r, "current") { "new" })
    }

    @Test
    fun noSessionOpensWhileAWipeRuns() = runTest {
        val r = record()
        r.beginWipe()
        assertEquals(FailureKind.BUSY, refusal(r)?.kind)
        // Not even the one that is current.
        try {
            SessionGate.open(r, "current") { "new" }
            fail("the current session was handed out during a wipe")
        } catch (e: VaultFailure) {
            assertEquals(SessionGate.CODE_WIPING, e.code)
        }
    }

    @Test
    fun noSessionOpensAfterAWipeThatDidNotFinish() = runTest {
        val r = record()
        r.beginWipe()
        r.endWipe(complete = false)
        assertEquals(FailureKind.ERASE_FAILED, refusal(r)?.kind)
    }

    @Test
    fun noSessionOpensWithAMarkerLeftOverAtTheStart() = runTest {
        assertEquals(FailureKind.ERASE_FAILED, refusal(record(eraseFailed = true))?.kind)
    }

    /** The four ways a vault comes to this phone; [calls] the ones that ran. */
    private class Ops : Enrolments.Ops<String> {
        val calls = mutableListOf<String>()

        override suspend fun redeemSetupCode(code: SetupCodeInput): String = "redeem".also { calls += it }

        override suspend fun transferIn(code: String): String = "transfer".also { calls += it }

        override suspend fun registerRecovery(code: RecoveryCode): RecoveryRegistration =
            RecoveryRegistration.Registered("qr").also { calls += "recovery" }

        override suspend fun registerTypedRecovery(typed: String): RecoveryRegistration =
            RecoveryRegistration.Registered("typed").also { calls += "typed" }
    }

    private val paths: List<Pair<String, suspend (Enrolments<String>) -> Unit>> = listOf(
        "redeem" to { e -> e.redeemSetupCode(SetupCodeInput.Secret("s")) },
        "transfer" to { e -> e.transferIn("t") },
        "recovery" to { e -> e.registerRecovery(RecoveryCode("v", "r", "c")) },
        "typed" to { e -> e.registerTypedRecovery("typed code") },
    )

    @Test
    fun everyEnrolmentIsRefusedWhileAWipeStaysUnfinished() = runTest {
        for ((name, path) in paths) {
            val ops = Ops()
            var finishes = 0
            val e = Enrolments(pending = { true }, finish = { finishes++ }, ops = ops)
            try {
                path(e)
                fail("$name went on with a wipe unfinished")
            } catch (f: VaultFailure) {
                assertEquals(name, FailureKind.ERASE_FAILED, f.kind)
            }
            assertEquals(name, 1, finishes)
            assertTrue(name, ops.calls.isEmpty())
        }
    }

    @Test
    fun everyEnrolmentFinishesAPendingWipeFirst() = runTest {
        for ((name, path) in paths) {
            val ops = Ops()
            var pending = true
            val e = Enrolments(pending = { pending }, finish = { pending = false }, ops = ops)
            path(e)
            assertEquals(name, listOf(name), ops.calls)
        }
    }
}
