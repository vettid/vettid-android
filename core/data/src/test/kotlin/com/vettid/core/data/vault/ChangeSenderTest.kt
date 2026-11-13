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
                    val u = UnresolvedChange(m.type, m.innerId, m.sentVersion, m.exp.toEpochMilli(), fromEpoch, m.heldKey)
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
            failureOf = { e -> VaultFailure(if (e.code == "bad_pin") FailureKind.BAD_PIN else FailureKind.OTHER, e.code, cause = e) },
        )

        suspend fun send(kind: ChangeKind = ChangeKind.PIN, block: suspend (suspend (ChangeMarker) -> Unit) -> Unit): Result<Unit> =
            runCatching { sender.send(record.recordGeneration, kind, 3, block) }
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

    private val resetMarker = ChangeMarker(ChangeTypes.RESET, "01JRESET", 1, Instant.parse("2026-10-18T12:10:00Z"), heldKey = "key-one")

    /** (0.48.0 step 1) A reset's marker is stored before the send, with its held key; no secret is in it. */
    @Test
    fun aResetMarkerIsStoredFirstWithItsHeldKey() = runTest {
        val f = Fixture(testScheduler)
        var stored: UnresolvedChange? = null
        val r = f.send(ChangeKind.RESET) { store ->
            store(resetMarker)
            stored = f.record.value.unresolvedChange
            throw ChangeUnansweredException(kept(resetMarker))
        }
        assertEquals(FailureKind.CHANGE_CHECKING, (r.exceptionOrNull() as VaultFailure).kind)
        assertEquals("key-one", stored?.heldKey)
        assertEquals(ChangeKind.RESET, stored?.kind)
        assertEquals("key-one", f.record.value.unresolvedChange?.heldKey)
        assertTrue(f.resolver.check.value!!.open)
        assertEquals(ChangeKind.RESET, f.resolver.check.value!!.kind)
        f.scope.cancel()
    }

    /** An answer that applied confirms a reset; the phone's epoch does not move (only a PIN change moves it). */
    @Test
    fun anAnswerToAResetConfirmsItWithoutMovingTheEpoch() = runTest {
        val f = Fixture(testScheduler)
        assertTrue(f.send(ChangeKind.RESET) { store -> store(resetMarker) }.isSuccess)
        assertNull(f.record.value.unresolvedChange)
        assertEquals(3L, f.record.value.phoneEpoch)
        assertEquals(ChangeCheck.Applied(ChangeKind.RESET), f.resolver.check.value)
        f.scope.cancel()
    }

    /** `stale_credential` to a reset: not applied, not retried with the typed secrets; any other refusal is said as it is. */
    @Test
    fun aStaleOrRefusedResetClearsTheMarker() = runTest {
        val f = Fixture(testScheduler)
        val stale = f.send(ChangeKind.RESET) { store ->
            store(resetMarker)
            throw VaultOpException(ChangeTypes.RESET, "stale_credential")
        }
        assertEquals(FailureKind.CHANGE_CHECKING, (stale.exceptionOrNull() as VaultFailure).kind)
        assertNull(f.record.value.unresolvedChange)
        assertEquals(ChangeCheck.NotApplied(ChangeKind.RESET), f.resolver.check.value)
        val refused = f.send(ChangeKind.RESET) { store ->
            store(resetMarker.copy(innerId = "01JRESET2"))
            throw VaultOpException(ChangeTypes.RESET, "bad_pin")
        }
        assertTrue(refused.exceptionOrNull() is VaultOpException)
        assertNull(f.record.value.unresolvedChange)
        assertEquals(ChangeCheck.Refused(ChangeKind.RESET, FailureKind.BAD_PIN), f.resolver.check.value)
        f.scope.cancel()
    }

    /**
     * (ANDROID-PLAN 0.1.55, VAULT-MESSAGING 0.51.1) The vault's own `forbidden` to the request itself ends the change as moved,
     * for a reset and for the other changes alike: the marker goes and it is not a refusal.
     */
    @Test
    fun aForbiddenAnswerToTheRequestItselfEndsTheChangeAsMoved() = runTest {
        val password = marker.copy(type = ChangeTypes.PASSWORD_CHANGE)
        for ((kind, m) in listOf(ChangeKind.RESET to resetMarker, ChangeKind.PIN to marker, ChangeKind.PASSWORD to password)) {
            val f = Fixture(testScheduler)
            val r = f.send(kind) { store ->
                store(m)
                throw VaultOpException(m.type, "forbidden")
            }
            assertEquals(FailureKind.CHANGE_CHECKING, (r.exceptionOrNull() as VaultFailure).kind)
            assertNull(f.record.value.unresolvedChange)
            assertEquals(ChangeCheck.Moved(kind), f.resolver.check.value)
            f.scope.cancel()
        }
    }

    /**
     * (ANDROID-PLAN §4 "While pending") A change whose first send the lock gate held: nothing was sent, so the marker goes, nothing
     * is kept to resend and no check starts; the failure is `lock_pending`.
     */
    @Test
    fun aChangeHeldByTheLockGateClearsItsMarker() = runTest {
        val f = Fixture(testScheduler)
        val held =
            com.vettid.core.vault.VaultStateException("held", ChangeTypes.PIN_CHANGE, com.vettid.core.vault.VaultDevice.CODE_LOCK_PENDING)
        val r = f.send { store ->
            store(marker)
            throw ChangeUnansweredException(kept(marker), held)
        }
        // Cancelled first: a check started by mistake would otherwise run on and keep the test from ending.
        f.scope.cancel()
        val e = r.exceptionOrNull() as VaultFailure
        assertEquals(com.vettid.core.vault.VaultDevice.CODE_LOCK_PENDING, e.code)
        assertNull("no marker", f.record.value.unresolvedChange)
        assertNull("nothing kept", f.kept)
        assertNull("no check", f.resolver.check.value)
        assertEquals(3L, f.record.value.phoneEpoch)
    }
}
