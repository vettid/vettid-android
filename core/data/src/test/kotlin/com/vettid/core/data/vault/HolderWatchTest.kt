package com.vettid.core.data.vault

import com.vettid.core.altchan.AltChannelException
import com.vettid.core.altchan.AltRefusedException
import com.vettid.core.altchan.AltResultException
import com.vettid.core.altchan.MemberApiException
import com.vettid.core.altchan.UnlockResult
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.relay.RelayException
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.VaultOpException
import com.vettid.core.vault.VaultStateException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * The owner decision of 2026-10-05: a replaced phone erases itself, but only on
 * an authenticated signal. Every proof wipes once; nothing else ever does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HolderWatchTest {
    private var wipes = 0

    private fun TestScope.watch() = HolderWatch(this) { wipes++ }

    private fun event(type: String, body: String = "{}") =
        VaultMessage(Inner(id = Ulid.new(), type = type, ts = Instant.now(), body = body.toByteArray()))

    // --- the authenticated signals wipe ---

    @Test
    fun deviceUnlinkedTransferredWipes() = runTest {
        val w = watch()
        assertTrue(w.onVaultEvent(event("device.unlinked", """{"reason":"transferred"}""")))
        advanceUntilIdle()
        assertEquals(1, wipes)
    }

    @Test
    fun deviceUnlinkedReplacedWipes() = runTest {
        val w = watch()
        assertTrue(w.onVaultEvent(event("device.unlinked", """{"reason":"replaced"}""")))
        advanceUntilIdle()
        assertEquals(1, wipes)
    }

    @Test
    fun aSealedUnknownDeviceAtUnlockWipes() = runTest {
        val w = watch()
        assertTrue(w.onSealedUnlockResult(UnlockResult(ok = false, code = "unknown_device")))
        assertEquals(1, wipes)
    }

    @Test
    fun aTransferApprovalWipesOnTheVaultsUnlinkedNoticeNotOnItsResponse() = runTest {
        val w = watch()
        // §6.7.1: vettid-vault answers {} and completes the transfer after the response; device.unlinked follows.
        var answered = false
        val result = async { w.approveTransfer(30_000) { answered = true } }
        advanceTimeBy(1_000)
        assertTrue(answered)
        assertEquals("the {} alone does not wipe", 0, wipes)
        w.onVaultEvent(event("device.unlinked", """{"reason":"transferred"}"""))
        advanceUntilIdle()
        assertTrue(result.await())
        assertEquals(1, wipes)
    }

    @Test
    fun aTransferApprovalWithoutTheNoticeDoesNotWipeAndTheLateNoticeDoes() = runTest {
        val w = watch()
        assertFalse(w.approveTransfer(30_000) {})
        assertEquals(0, wipes)
        // The phone was offline: the notice waits in its mailbox and arrives later.
        w.onVaultEvent(event("device.unlinked", """{"reason":"transferred"}"""))
        advanceUntilIdle()
        assertEquals(1, wipes)
    }

    @Test
    fun aFailedApprovalDoesNotWipe() = runTest {
        val w = watch()
        try {
            w.approveTransfer(30_000) { throw VaultFailure(FailureKind.BAD_PIN, "bad_pin") }
            fail()
        } catch (_: VaultFailure) {
            // expected
        }
        advanceUntilIdle()
        assertEquals(0, wipes)
    }

    @Test
    fun twoProofsWipeOnce() = runTest {
        val w = watch()
        w.onVaultEvent(event("device.unlinked", """{"reason":"transferred"}"""))
        w.onVaultEvent(event("device.unlinked", """{"reason":"transferred"}"""))
        advanceUntilIdle()
        assertEquals(1, wipes)
    }

    // --- the member's "Erase VettID from this phone" (owner decision, 2026-10-05) ---

    @Test
    fun theMembersEraseWipesOnce() = runTest {
        val w = watch()
        w.wipeNow()
        assertEquals(1, wipes)
        assertEquals(1, w.count)
    }

    @Test
    fun anEraseWhileAProofsWipeRunsWipesOnce() = runTest {
        var running = 0
        val w = HolderWatch(this) { running++; delay(1_000); wipes++ }
        w.onVaultEvent(event("device.unlinked", """{"reason":"replaced"}"""))
        advanceTimeBy(10) // the proof's wipe runs
        val erase = async { w.wipeNow() }
        advanceUntilIdle()
        erase.await()
        assertEquals(1, running)
        assertEquals(1, wipes)
    }

    // --- nothing else does ---

    @Test
    fun otherVaultEventsDoNotWipe() = runTest {
        val w = watch()
        val notProof = listOf(
            event("device.unlinked"), // no reason
            event("device.unlinked", """{"reason":"vault_deleted"}"""),
            event("device.unlinked", """{"reason":"something_else"}"""),
            event("device.unlinked", """{"reason":7}"""),
            event("vault.locking", """{"reason":"recovery"}"""),
            event("sync.event", """{"kind":"device.unlinked","device_id":"d1"}"""),
            event("sync.event", """{"kind":"device.transferred","device_id":"d2","old_device_id":"d1"}"""),
            event("sync.event", """{"kind":"device.transfer","state":"aborted","reason":"replaced"}"""),
            event("device.pair.rejected"),
        )
        for (m in notProof) assertFalse(m.type, w.onVaultEvent(m))
        advanceUntilIdle()
        assertEquals(0, wipes)
    }

    @Test
    fun otherSealedUnlockResultsDoNotWipe() = runTest {
        val w = watch()
        val codes = listOf("bad_pin", "backoff", "attestation", "state_rollback", "vault_missing", "manifest", "wrong_release",
            "release_key", "retry", "recovery_pending", null)
        for (c in codes) assertFalse(c, w.onSealedUnlockResult(UnlockResult(ok = false, code = c)))
        assertFalse(w.onSealedUnlockResult(UnlockResult(ok = true, code = "unknown_device")))
        assertEquals(0, wipes)
    }

    @Test
    fun failuresNeverWipeEvenWhenTheyNameAnUnknownDevice() = runTest {
        val w = watch()
        val thrown = listOf<suspend () -> Unit>(
            { throw IOException("network down") },
            { withTimeout(1) { delay(1_000) } },
            { throw MemberApiException(404, "unknown_device", "not found") }, // an HTTP status naming it: not proof
            { throw MemberApiException(401, MemberApiException.UNAUTHORIZED) },
            { throw MemberApiException(404, MemberApiException.NOT_FOUND) },
            { throw MemberApiException(409, MemberApiException.INSTANCE_MOVED) },
            { throw MemberApiException(503, "vault_unavailable") },
            { throw RelayException(403, RelayException.TOKEN_REVOKED, "revoked") }, // the relay refusing this phone's key
            { throw RelayException(401, RelayException.TOKEN_EXPIRED, "") },
            { throw RelayException(404, "not_found", "") },
            { throw AltResultException("result unreadable") }, // §11.4: an unknown device gets random bytes
            { throw AltChannelException("unlock not answered (expired)") },
            { throw AltRefusedException(AltRefusedException.Reason.NOT_PAIRED) },
            { throw VaultStateException("device.transfer.approve: no response") },
            { throw VaultOpException("vault.status", "unknown_device") }, // a vault error code, not device.unlinked
            { throw VaultOpException("credential.get", "forbidden") },
        )
        for (t in thrown) {
            val f = try {
                vaultGuard { t() }
                null
            } catch (e: VaultFailure) {
                e
            }
            requireNotNull(f)
            assertFalse(f.toString(), w.onFailure(f))
        }
        advanceUntilIdle()
        assertEquals(0, wipes)
    }

    @Test
    fun thePolicyNeverAcceptsAFailure() {
        for (k in FailureKind.entries) {
            for (c in listOf(null, "unknown_device", "transferred", "replaced", "unreadable_result")) {
                assertFalse(HolderPolicy.proves(HolderSignal.Failure(k, c)))
            }
        }
    }
}
