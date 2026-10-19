package com.vettid.core.data.vault

import com.vettid.core.data.R
import com.vettid.core.vault.SentRequest
import com.vettid.core.vault.VaultOpException
import com.vettid.core.vault.VaultStateException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * VAULT-MESSAGING 0.45.0 §11.14.9 step 5 (N1): a new phone secret is stored before it is sent, its answer decides it, and
 * one whose answer was lost is decided by `credential.version` before any PIN change, over the real account record.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RenewRunnerTest {
    private val exp: Instant = Instant.parse("2026-10-18T12:10:00Z")
    private val req = SentRequest(RenewRunner.TYPE_RENEW, "01JRENEW", 6, exp)

    private class Fixture(scheduler: TestCoroutineScheduler) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        var storeWorks = true
        val record = AccountRecord(LocalAccount(userGuid = "u", phoneEpoch = 3), readable = true, persist = {
            if (!storeWorks) throw java.io.IOException("disk full")
        })
        var settled = true
        var version = ChangeResolver.Version(true, 6, false, Instant.EPOCH)
        var fetches = 0
        val runner = RenewRunner(
            scope,
            object : RenewRunner.Ops {
                override fun store(r: SentRequest) =
                    record.storeRenew(record.recordGeneration, UnconfirmedRenew(r.innerId, r.sentVersion ?: 0, r.exp.toEpochMilli(), 3))
                override fun decide(innerId: String, applied: Boolean) {
                    record.decideRenew(innerId, applied)
                }
                override fun unconfirmed() = record.value.unconfirmedRenew
                override suspend fun awaitSettled() = settled
                override suspend fun version() = version
                override suspend fun fetch() {
                    fetches++
                }
            },
        )
    }

    @Test
    fun anAnsweredRenewIsDecidedByItsAnswer() = runTest {
        val f = Fixture(testScheduler)
        var storedBeforeSend = false
        f.runner.send { onSending, onAnswered, _ ->
            onSending(req)
            storedBeforeSend = f.record.value.unconfirmedRenew?.innerId == req.innerId
            onAnswered(req.innerId)
        }
        assertTrue(storedBeforeSend)
        assertNull(f.record.value.unconfirmedRenew)
        assertEquals(4L, f.record.value.phoneEpoch)
        f.scope.cancel()
    }

    /**
     * A refusal of the renew itself: not applied. A refused acknowledgement after its `ok` answer (P1): applied. A
     * `stale_credential` (R1) is a refusal under the renew's id too: not applied at once, so a failed fetch after it leaves
     * nothing stored and the epoch where it was, and a later version check cannot read the earlier operation as the renew.
     */
    @Test
    fun onlyTheRenewsOwnAnswerDecidesIt() = runTest {
        val f = Fixture(testScheduler)
        val r = runCatching {
            f.runner.send { onSending, _, onRefused ->
                onSending(req)
                onRefused(req.innerId, "bad_pin")
                throw VaultOpException(RenewRunner.TYPE_RENEW, "bad_pin")
            }
        }
        assertEquals("bad_pin", (r.exceptionOrNull() as VaultOpException).code)
        assertNull(f.record.value.unconfirmedRenew)
        assertEquals(3L, f.record.value.phoneEpoch)
        // Renew ok, then credential.ack refused: applied.
        val ack = runCatching {
            f.runner.send { onSending, onAnswered, _ ->
                onSending(req.copy(innerId = "01JRENEW2"))
                onAnswered("01JRENEW2")
                throw VaultOpException("credential.ack", "bad_request")
            }
        }
        assertTrue("the renew succeeded", ack.isSuccess)
        assertNull(f.record.value.unconfirmedRenew)
        assertEquals(4L, f.record.value.phoneEpoch)
        // Stale, then credential.get refused: not applied, nothing stored, the epoch where it was.
        val g = Fixture(testScheduler)
        val stale = runCatching {
            g.runner.send { onSending, _, onRefused ->
                onSending(req)
                onRefused(req.innerId, "stale_credential")
                throw VaultOpException("credential.get", "busy")
            }
        }
        assertEquals("busy", (stale.exceptionOrNull() as VaultOpException).code)
        assertNull(g.record.value.unconfirmedRenew)
        assertEquals(3L, g.record.value.phoneEpoch)
        // The version moved for the earlier operation that made the blob stale: nothing to read as the renew.
        g.version = ChangeResolver.Version(true, 7, false, exp.plusSeconds(600))
        assertEquals(RenewRunner.State.NONE, g.runner.settle())
        assertEquals("never moved to the renew's candidate", 3L, g.record.value.phoneEpoch)
        assertEquals(0, g.fetches)
        // A retry after the fetch is a new request, stored and decided on its own.
        val h = Fixture(testScheduler)
        h.runner.send { onSending, onAnswered, onRefused ->
            onSending(req)
            onRefused(req.innerId, "stale_credential")
            onSending(req.copy(innerId = "01JRETRY"))
            onAnswered("01JRETRY")
        }
        assertNull(h.record.value.unconfirmedRenew)
        assertEquals(4L, h.record.value.phoneEpoch)
        f.scope.cancel()
        g.scope.cancel()
        h.scope.cancel()
    }

    /** No answer: it stays stored, to be decided; a store that fails sends nothing. */
    @Test
    fun aLostAnswerStaysAndAFailedStoreSendsNothing() = runTest {
        val f = Fixture(testScheduler)
        val lost = runCatching {
            f.runner.send { onSending, _, _ ->
                onSending(req)
                throw VaultStateException("vault.secret.renew: no response")
            }
        }
        assertTrue(lost.isFailure)
        assertEquals(req.innerId, f.record.value.unconfirmedRenew?.innerId)
        val g = Fixture(testScheduler)
        g.storeWorks = false
        var sent = false
        val r = runCatching {
            g.runner.send { onSending, _, _ ->
                onSending(req)
                sent = true
            }
        }
        assertEquals(FailureKind.LOCAL_STATE, (r.exceptionOrNull() as VaultFailure).kind)
        assertTrue("nothing sent", !sent)
        f.scope.cancel()
        g.scope.cancel()
    }

    /** (N1) A lost renew decided by `credential.version` once settled; otherwise it holds the PIN change. */
    @Test
    fun aLostRenewIsDecidedByTheVersion() = runTest {
        val f = Fixture(testScheduler)
        assertEquals(RenewRunner.State.NONE, f.runner.settle())
        val r = UnconfirmedRenew(req.innerId, 6, exp.toEpochMilli(), 3)
        f.record.storeRenew(f.record.recordGeneration, r)
        f.settled = false
        assertEquals(RenewRunner.State.UNSETTLED, f.runner.settle())
        f.settled = true
        f.version = ChangeResolver.Version(true, 6, false, exp.minusSeconds(5))
        assertEquals("may still apply", RenewRunner.State.UNSETTLED, f.runner.settle())
        f.version = ChangeResolver.Version(true, 9, false, exp.plusSeconds(120))
        assertEquals(RenewRunner.State.UNDECIDABLE, f.runner.settle())
        assertEquals(r, f.record.value.unconfirmedRenew)
        f.version = ChangeResolver.Version(true, 6, false, exp.plusSeconds(61))
        assertEquals(RenewRunner.State.NONE, f.runner.settle())
        assertNull(f.record.value.unconfirmedRenew)
        assertEquals("not applied", 3L, f.record.value.phoneEpoch)
        f.record.storeRenew(f.record.recordGeneration, r)
        f.version = ChangeResolver.Version(true, 7, false, exp)
        assertEquals(RenewRunner.State.NONE, f.runner.settle())
        assertEquals("applied: the candidate", 4L, f.record.value.phoneEpoch)
        assertEquals(1, f.fetches)
        f.scope.cancel()
    }

    /**
     * (VAULT-MESSAGING 0.47.0 §11.14.9 step 1) A renew refused before anything was stored or sent, because an earlier
     * request may still apply or the vault's blob could not be made current: the "earlier request" text, nothing stored.
     */
    @Test
    fun aRenewRefusedBeforeItIsStoredIsTheEarlierRequestText() = runTest {
        val f = Fixture(testScheduler)
        val r = runCatching {
            f.runner.send { _, _, _ ->
                throw com.vettid.core.vault.CredentialUnsettledException(VaultOpException("credential.get", "busy"))
            }
        }
        val e = r.exceptionOrNull() as VaultFailure
        assertEquals(FailureKind.CREDENTIAL_UNSETTLED, e.kind)
        assertEquals(ChangeSender.CODE_UNSETTLED, e.code)
        assertNull("nothing stored", f.record.value.unconfirmedRenew)
        assertEquals(3L, f.record.value.phoneEpoch)
        f.scope.cancel()
    }

    /**
     * (0.47.0) A renew's own refusal decides it as not applied, whatever the code, `stale_credential` included: the record
     * goes, the epoch stays, and a later `credential.version` has nothing to decide.
     */
    @Test
    fun aRenewRefusedInAnyWayIsNotApplied() = runTest {
        for (code in listOf("stale_credential", "bad_pin", "bad_password", "backoff", "device_secret", "owner_check_required")) {
            val f = Fixture(testScheduler)
            val r = runCatching {
                f.runner.send { onSending, _, onRefused ->
                    onSending(req)
                    onRefused(req.innerId, code)
                    throw VaultOpException(RenewRunner.TYPE_RENEW, code)
                }
            }
            when (val e = r.exceptionOrNull()) {
                // Its own stale_credential: the "earlier request" text, not "did not answer".
                is VaultFailure -> {
                    assertEquals("stale_credential", code)
                    assertEquals(FailureKind.CREDENTIAL_UNSETTLED, e.kind)
                    assertEquals(R.string.data_failure_credential_unsettled, e.kind.messageRes())
                }
                else -> assertEquals(code, (e as VaultOpException).code)
            }
            assertNull(code, f.record.value.unconfirmedRenew)
            assertEquals(code, 3L, f.record.value.phoneEpoch)
            // Whatever the version says afterwards, the refused renew is not read from it.
            f.version = ChangeResolver.Version(true, 7, false, exp.plusSeconds(600))
            assertEquals(code, RenewRunner.State.NONE, f.runner.settle())
            assertEquals(code, 3L, f.record.value.phoneEpoch)
            assertEquals(code, 0, f.fetches)
            f.scope.cancel()
        }
    }

    /**
     * Why the request carries the vault's current version (0.47.0): an earlier operation that applied without its answer
     * moved the version from 6 to 7. A request sent with the stale blob (6) and then lost would read 7 as "applied"; sent
     * with the refreshed blob (7), an unchanged 7 after its `exp` is "not applied", and only 8 is "applied".
     */
    @Test
    fun theRefreshedVersionKeepsAnEarlierOperationFromReadingAsTheRequest() {
        val expMs = exp.toEpochMilli()
        val after = exp.plusSeconds(120)
        assertEquals(ChangeDecision.Verdict.APPLIED, ChangeDecision.of(6, expMs, true, 7, false, after))
        assertEquals(ChangeDecision.Verdict.NOT_APPLIED, ChangeDecision.of(7, expMs, true, 7, false, after))
        assertEquals(ChangeDecision.Verdict.APPLIED, ChangeDecision.of(7, expMs, true, 8, false, after))
    }
}
