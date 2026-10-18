package com.vettid.core.data.vault

import com.vettid.core.vault.ChangeMarker
import com.vettid.core.vault.ChangeTypes
import com.vettid.core.vault.ChangeUnansweredException
import com.vettid.core.vault.CredentialUnsettledException
import com.vettid.core.vault.KeptChange
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * VAULT-MESSAGING 0.44.0 §11.14.9 as the vault manager sends a change ([ChangeSender]), over the real account record
 * ([AccountRecord]): the marker first, then an answer decides, no answer keeps the request and starts the check, and the
 * send outlives a screen that goes away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangeSenderTest {
    private val marker = ChangeMarker(ChangeTypes.PIN_CHANGE, "01JINNER", 7, Instant.parse("2026-10-18T12:10:00Z"))

    private class Fixture(scheduler: TestCoroutineScheduler) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val record = AccountRecord(LocalAccount(userGuid = "u", phoneEpoch = 3), readable = true, persist = {})
        var kept: KeptChange? = null
        val resolver = ChangeResolver(
            scope,
            object : ChangeResolver.Ops {
                override fun marker() = record.value.unresolvedChange
                override suspend fun resend(m: UnresolvedChange): ChangeResolver.Answer? = null
                override suspend fun version(): ChangeResolver.Version = throw java.io.IOException("unreachable in this test")
                override suspend fun fetch() = Unit
                override fun confirm(m: UnresolvedChange) = record.confirmChange(m)
                override fun clear(m: UnresolvedChange) {
                    record.clearChange(m)
                }
            },
        )
        val sender = ChangeSender(
            scope,
            resolver,
            object : ChangeSender.Ops {
                override fun store(since: Int, m: ChangeMarker, fromEpoch: Long): UnresolvedChange {
                    val u = UnresolvedChange(m.type, m.innerId, m.sentVersion, m.exp.toEpochMilli(), fromEpoch)
                    check(record.storeChange(since, u) == AccountRecord.ChangeStored.STORED) { "not stored" }
                    return u
                }
                override fun confirm(m: UnresolvedChange) = record.confirmChange(m)
                override fun clear(m: UnresolvedChange) {
                    record.clearChange(m)
                }
                override fun keep(k: KeptChange) {
                    kept = k
                }
            },
            failureOf = { if (it == "bad_pin") FailureKind.BAD_PIN else FailureKind.OTHER },
        )

        suspend fun send(block: suspend (suspend (ChangeMarker) -> Unit) -> Unit): Result<Unit> =
            runCatching { sender.send(record.recordGeneration, ChangeKind.PIN, 3, block) }
    }

    private fun kept(m: ChangeMarker) = KeptChange(m.type, m.innerId, JsonObject(emptyMap()), m.exp, m.sentVersion)

    /** An answer that applied: the candidate becomes current, the marker goes, and "applied" is said once. */
    @Test
    fun anAnswerThatAppliedConfirmsTheChange() = runTest {
        val f = Fixture(testScheduler)
        var storedBeforeSend = false
        val r = f.send { store ->
            store(marker)
            storedBeforeSend = f.record.value.unresolvedChange != null
        }
        assertTrue(r.isSuccess)
        assertTrue(storedBeforeSend)
        assertNull(f.record.value.unresolvedChange)
        assertEquals(4L, f.record.value.phoneEpoch)
        assertEquals(ChangeCheck.Applied(ChangeKind.PIN), f.resolver.check.value)
        f.scope.cancel()
    }

    /** No answer: not a failure; the request is kept for resends, the marker stays and the check says "checking". */
    @Test
    fun noAnswerKeepsTheRequestAndStartsTheCheck() = runTest {
        val f = Fixture(testScheduler)
        val r = f.send { store ->
            store(marker)
            throw ChangeUnansweredException(kept(marker))
        }
        assertEquals(FailureKind.CHANGE_CHECKING, (r.exceptionOrNull() as VaultFailure).kind)
        assertEquals(marker.innerId, f.kept?.innerId)
        assertEquals(marker.innerId, f.record.value.unresolvedChange?.innerId)
        assertEquals(3L, f.record.value.phoneEpoch)
        assertTrue(f.resolver.check.value!!.open)
        f.scope.cancel()
    }

    /** `stale_credential`: this request did not apply (not retried with the typed secrets); a refusal is said as it is. */
    @Test
    fun aStaleOrRefusedAnswerClearsTheMarker() = runTest {
        val f = Fixture(testScheduler)
        val stale = f.send { store ->
            store(marker)
            throw VaultOpException(ChangeTypes.PIN_CHANGE, "stale_credential")
        }
        assertEquals(FailureKind.CHANGE_CHECKING, (stale.exceptionOrNull() as VaultFailure).kind)
        assertNull(f.record.value.unresolvedChange)
        assertEquals(ChangeCheck.NotApplied(ChangeKind.PIN), f.resolver.check.value)
        val bad = f.send { store ->
            store(marker.copy(innerId = "01JSECOND"))
            throw VaultOpException(ChangeTypes.PIN_CHANGE, "bad_pin")
        }
        assertEquals("bad_pin", (bad.exceptionOrNull() as VaultOpException).code)
        assertNull(f.record.value.unresolvedChange)
        assertEquals(ChangeCheck.Refused(ChangeKind.PIN, FailureKind.BAD_PIN), f.resolver.check.value)
        assertEquals(3L, f.record.value.phoneEpoch)
        f.scope.cancel()
    }

    /** (M1) An earlier credential operation not settled: refused before anything is stored or sent. */
    @Test
    fun anUnsettledCredentialOperationRefusesTheChange() = runTest {
        val f = Fixture(testScheduler)
        val r = f.send { throw CredentialUnsettledException() }
        assertEquals(FailureKind.CREDENTIAL_UNSETTLED, (r.exceptionOrNull() as VaultFailure).kind)
        assertNull(f.record.value.unresolvedChange)
        f.scope.cancel()
    }

    /** (L3) A screen that goes away while the change is on its way does not stop it: the request is kept, the check runs. */
    @Test
    fun theSendOutlivesTheCallersScope() = runTest {
        val f = Fixture(testScheduler)
        val gate = CompletableDeferred<Unit>()
        val caller = launch {
            f.send { store ->
                store(marker)
                gate.await()
                throw ChangeUnansweredException(kept(marker))
            }
        }
        runCurrent()
        assertEquals(marker.innerId, f.record.value.unresolvedChange?.innerId)
        caller.cancel()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals("kept although the screen went away", marker.innerId, f.kept?.innerId)
        assertTrue(f.resolver.check.value!!.open)
        f.scope.cancel()
    }
}
