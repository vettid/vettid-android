package com.vettid.core.data.vault

import com.vettid.core.vault.ChangeTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * VAULT-MESSAGING 0.44.0 §11.14.9 "A lost answer" (ANDROID-PLAN 0.1.45): a PIN or password change whose answer was lost
 * is decided, not repeated: by an answer to the same request resent while its `exp` has not passed, or by
 * `credential.version` (one above the version sent: applied; the same in an answer stamped more than a minute after
 * `exp`: not applied; otherwise undecided). While undecided no other credential operation goes out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangeResolverTest {
    private val exp: Instant = Instant.parse("2026-10-18T12:10:00Z")
    private val pinChange = UnresolvedChange(ChangeTypes.PIN_CHANGE, "01JINNER", sentVersion = 7, expMs = exp.toEpochMilli(), fromEpoch = 3)
    private val passwordChange = pinChange.copy(type = ChangeTypes.PASSWORD_CHANGE, fromEpoch = 3)

    /** A fake of the vault manager's side: the marker on the phone, the kept request, `credential.version`. */
    private class FakeOps(var marker: UnresolvedChange?) : ChangeResolver.Ops {
        /** What a resend of the kept request answers; null: not held (a restart), exp passed, or no answer. */
        var resendAnswer: ChangeResolver.Answer? = null
        var resends = 0

        /** (0.1.55) What a resend throws instead of answering (a vault error answer, or a failure to reach it). */
        var resendThrows: Exception? = null

        /** (0.1.55) What the fetch after "applied" throws. */
        var fetchThrows: Exception? = null
        val ended = mutableListOf<UnresolvedChange>()

        /** `credential.version`'s answers in order (the last repeats); an [IOException] for no answer. */
        val versions = ArrayDeque<Any>()
        var versionCalls = 0
        var fetches = 0
        var fetchFails = false
        val confirmed = mutableListOf<UnresolvedChange>()
        val cleared = mutableListOf<UnresolvedChange>()

        /** Every credential operation the app would send meanwhile, as the gate lets it through. */
        val sent = mutableListOf<String>()

        override fun marker(): UnresolvedChange? = marker

        override suspend fun resend(m: UnresolvedChange): ChangeResolver.Answer? {
            resends++
            resendThrows?.let { throw it }
            return resendAnswer
        }

        override suspend fun version(): ChangeResolver.Version {
            versionCalls++
            val v = if (versions.size > 1) versions.removeFirst() else versions.first()
            if (v is Exception) throw v
            return v as ChangeResolver.Version
        }

        override suspend fun fetch() {
            fetches++
            fetchThrows?.let { throw it }
            if (fetchFails) throw IOException("no answer")
        }

        override fun confirm(m: UnresolvedChange): Boolean {
            confirmed += m
            marker = null
            return true
        }

        override fun clear(m: UnresolvedChange) {
            cleared += m
            marker = null
        }

        override fun end(m: UnresolvedChange) {
            ended += m
            clear(m)
        }
    }

    private fun version(v: Long?, ts: Instant, exists: Boolean = true, alarm: Boolean = false) =
        ChangeResolver.Version(exists, v, alarm, ts)

    private fun resolver(scheduler: TestCoroutineScheduler, ops: FakeOps): Pair<ChangeResolver, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        return ChangeResolver(scope, ops) to scope
    }

    @Test
    fun theVersionDecides() {
        val m = pinChange
        val late = exp.plusSeconds(61)
        assertEquals(ChangeDecision.Verdict.APPLIED, ChangeDecision.of(m, true, 8, false, exp.minusSeconds(300)))
        assertEquals(ChangeDecision.Verdict.APPLIED, ChangeDecision.of(m, true, 8, false, late))
        assertEquals(ChangeDecision.Verdict.NOT_APPLIED, ChangeDecision.of(m, true, 7, false, late))
        // Not more than a minute after exp: a copy may still be on its way.
        assertEquals(ChangeDecision.Verdict.UNDECIDED, ChangeDecision.of(m, true, 7, false, exp.plusSeconds(60)))
        assertEquals(ChangeDecision.Verdict.UNDECIDED, ChangeDecision.of(m, true, 7, false, exp.minusSeconds(1)))
        // Anything else ends the check without a decision.
        assertEquals(ChangeDecision.Verdict.ENDED, ChangeDecision.of(m, true, 9, false, late))
        assertEquals(ChangeDecision.Verdict.ENDED, ChangeDecision.of(m, true, 6, false, late))
        assertEquals(ChangeDecision.Verdict.ENDED, ChangeDecision.of(m, false, null, false, late))
        assertEquals(ChangeDecision.Verdict.ENDED, ChangeDecision.of(m, true, 8, true, late))
    }

    /** A lost answer, then the check finds the version one higher: applied; the blob fetched, the candidate confirmed. */
    @Test
    fun aLostAnswerThenAppliedIsConfirmed() = runTest {
        val ops = FakeOps(pinChange).apply { versions += version(8, exp.minusSeconds(500)) }
        val (r, scope) = resolver(testScheduler, ops)
        assertEquals(ChangeCheck.Checking(ChangeKind.PIN), r.check.value)
        val c = r.decide()
        assertEquals(ChangeCheck.Applied(ChangeKind.PIN), c)
        assertEquals(1, ops.fetches)
        assertEquals(listOf(pinChange), ops.confirmed)
        assertTrue(ops.cleared.isEmpty())
        assertEquals(c, r.check.value)
        r.acknowledge()
        assertNull(r.check.value)
        scope.cancel()
    }

    /** A lost answer, then the same version in an answer stamped more than a minute after exp: not applied, ever. */
    @Test
    fun aLostAnswerThenNotAppliedAfterExp() = runTest {
        val ops = FakeOps(passwordChange).apply { versions += version(7, exp.plusSeconds(61)) }
        val (r, scope) = resolver(testScheduler, ops)
        assertEquals(ChangeCheck.NotApplied(ChangeKind.PASSWORD), r.decide())
        assertEquals(listOf(passwordChange), ops.cleared)
        assertTrue(ops.confirmed.isEmpty())
        assertEquals(0, ops.fetches)
        scope.cancel()
    }

    /**
     * Before exp the same version decides nothing: the check runs again after 30 s, doubling to 5 minutes, until an
     * answer stamped late enough decides it.
     */
    @Test
    fun undecidedBeforeExpIsCheckedAgainWithAPauseThatDoubles() = runTest {
        val ops = FakeOps(pinChange)
        repeat(6) { ops.versions += version(7, exp.minusSeconds(10)) }
        ops.versions += version(7, exp.plusSeconds(120))
        val (r, scope) = resolver(testScheduler, ops)
        r.kick()
        testScheduler.runCurrent()
        assertEquals(1, ops.versionCalls)
        assertEquals(ChangeCheck.Checking(ChangeKind.PIN), r.check.value)
        assertTrue(ops.cleared.isEmpty() && ops.confirmed.isEmpty())
        for ((i, pause) in listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L).withIndex()) {
            testScheduler.advanceTimeBy(pause - 1)
            testScheduler.runCurrent()
            assertEquals("not before the pause of $pause ms", i + 1, ops.versionCalls)
            testScheduler.advanceTimeBy(1)
            testScheduler.runCurrent()
            assertEquals(i + 2, ops.versionCalls)
        }
        assertEquals(ChangeCheck.NotApplied(ChangeKind.PIN), r.check.value)
        scope.cancel()
    }

    /** While exp has not passed, the same request is resent; the vault's cached answer decides it, no version asked. */
    @Test
    fun aResendWithinExpGetsTheCachedAnswer() = runTest {
        val ops = FakeOps(pinChange).apply { resendAnswer = ChangeResolver.Answer.Ok }
        val (r, scope) = resolver(testScheduler, ops)
        assertEquals(ChangeCheck.Applied(ChangeKind.PIN), r.decide())
        assertEquals(1, ops.resends)
        assertEquals(0, ops.versionCalls)
        assertEquals(listOf(pinChange), ops.confirmed)
        scope.cancel()
    }

    /** A refusal that answers the resend is shown as it is; `stale_credential` means this request did not apply. */
    @Test
    fun aRefusalOrStaleAnswerToTheResendDecidesIt() = runTest {
        val bad = FakeOps(pinChange).apply { resendAnswer = ChangeResolver.Answer.Refused(FailureKind.BAD_PIN) }
        val (r1, s1) = resolver(testScheduler, bad)
        assertEquals(ChangeCheck.Refused(ChangeKind.PIN, FailureKind.BAD_PIN), r1.decide())
        assertEquals(listOf(pinChange), bad.cleared)
        s1.cancel()
        val stale = FakeOps(passwordChange).apply { resendAnswer = ChangeResolver.Answer.Refused(FailureKind.NO_RESPONSE, stale = true) }
        val (r2, s2) = resolver(testScheduler, stale)
        assertEquals(ChangeCheck.NotApplied(ChangeKind.PASSWORD), r2.decide())
        assertEquals(listOf(passwordChange), stale.cleared)
        assertEquals(0, stale.versionCalls)
        s2.cancel()
    }

    /**
     * After a restart the request is no longer in memory (nothing to resend) but the marker is on the phone: the check
     * starts as "checking" and decides by `credential.version` alone.
     */
    @Test
    fun aRestartWithTheMarkerDecidesByTheVersion() = runTest {
        val ops = FakeOps(pinChange).apply { versions += version(8, exp.plusSeconds(3600)) }
        val (r, scope) = resolver(testScheduler, ops)
        assertEquals(ChangeCheck.Checking(ChangeKind.PIN), r.check.value)
        assertEquals(ChangeCheck.Applied(ChangeKind.PIN), r.decide())
        assertEquals(1, ops.resends) // asked, and nothing was held
        assertEquals(listOf(pinChange), ops.confirmed)
        scope.cancel()
    }

    /** No answer at all: unresolved, "will check when it can"; a fetch that fails after "applied" writes nothing yet. */
    @Test
    fun anUnreachableVaultLeavesItUnresolved() = runTest {
        val ops = FakeOps(pinChange).apply { versions += IOException("no answer") }
        val (r, scope) = resolver(testScheduler, ops)
        assertEquals(ChangeCheck.Unreachable(ChangeKind.PIN), r.decide())
        assertTrue(r.check.value!!.open)
        assertTrue(ops.cleared.isEmpty() && ops.confirmed.isEmpty())
        ops.versions.clear()
        ops.versions += version(8, exp)
        ops.fetchFails = true
        assertEquals(ChangeCheck.Unreachable(ChangeKind.PIN), r.decide())
        assertTrue("not confirmed before the blob is here", ops.confirmed.isEmpty())
        ops.fetchFails = false
        assertEquals(ChangeCheck.Applied(ChangeKind.PIN), r.decide())
        scope.cancel()
    }

    /** No credential, another version or a clone alarm: the check ends without a decision and the marker goes. */
    @Test
    fun anotherAnswerEndsTheCheck() = runTest {
        val ops = FakeOps(pinChange).apply { versions += version(8, exp, alarm = true) }
        val (r, scope) = resolver(testScheduler, ops)
        assertEquals(ChangeCheck.Ended(ChangeKind.PIN), r.decide())
        assertEquals(listOf(pinChange), ops.cleared)
        assertTrue(ops.confirmed.isEmpty())
        scope.cancel()
    }

    /**
     * §11.14.9 step 2: while unresolved, no owner check or other credential operation is sent: the gate decides first and
     * refuses ("still checking") while it cannot; once decided, they go.
     */
    @Test
    fun noCredentialOperationIsSentWhileUnresolved() = runTest {
        val ops = FakeOps(passwordChange).apply { versions += IOException("no answer") }
        val (r, scope) = resolver(testScheduler, ops)
        suspend fun ownerCheck() {
            r.gate(carriesPin = true)
            ops.sent += "vault.owner_check"
        }
        // Refused at once (0.45.0: no waiting for the decision), and the check is started.
        val e = runCatching { ownerCheck() }.exceptionOrNull() as VaultFailure
        assertEquals(FailureKind.CHANGE_UNDECIDED_PASSWORD, e.kind)
        assertTrue(ops.sent.isEmpty())
        assertEquals(0, ops.versionCalls)
        testScheduler.runCurrent()
        assertEquals("the check started", 1, ops.versionCalls)
        // The vault cannot be reached: "can't reach your vault".
        assertEquals(FailureKind.CHANGE_UNRESOLVED, (runCatching { ownerCheck() }.exceptionOrNull() as VaultFailure).kind)
        assertTrue(ops.sent.isEmpty())
        // Decided: it goes.
        ops.versions.clear()
        ops.versions += version(7, exp.plusSeconds(90))
        testScheduler.runCurrent()
        assertEquals(ChangeCheck.NotApplied(ChangeKind.PASSWORD), r.check.value)
        ownerCheck()
        assertEquals(listOf("vault.owner_check"), ops.sent)
        // Nothing unresolved: no check at all.
        val calls = ops.versionCalls
        ownerCheck()
        assertEquals(calls, ops.versionCalls)
        scope.cancel()
    }

    /** A PIN change undecided while the vault answers: refused with the PIN's "still checking" text. */
    @Test
    fun theGateNamesThePinChange() = runTest {
        val ops = FakeOps(pinChange).apply { versions += version(7, exp.minusSeconds(30)) }
        val (r, scope) = resolver(testScheduler, ops)
        r.decide()
        assertEquals(FailureKind.CHANGE_UNDECIDED, (runCatching { r.gate(carriesPin = false) }.exceptionOrNull() as VaultFailure).kind)
        scope.cancel()
    }

    /**
     * (0.45.0 step 3) A PIN change's check that ends without a decision leaves the PIN uncertain: operations that carry
     * the PIN are refused until a two-step unlock settles it; the others go.
     */
    @Test
    fun anEndedPinChangeLeavesThePinUncertain() = runTest {
        var uncertain = false
        val ops = object : ChangeResolver.Ops {
            var marker: UnresolvedChange? = pinChange
            override fun marker() = marker
            override suspend fun resend(m: UnresolvedChange): ChangeResolver.Answer? = null
            override suspend fun version() = version(9, exp)
            override suspend fun fetch() = Unit
            override fun confirm(m: UnresolvedChange) = true
            override fun clear(m: UnresolvedChange) {
                marker = null
            }
            override fun end(m: UnresolvedChange) {
                marker = null
                uncertain = true
            }
            override fun pinUncertain() = uncertain
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val r = ChangeResolver(scope, ops)
        assertEquals(ChangeCheck.Ended(ChangeKind.PIN), r.decide())
        assertTrue(uncertain)
        assertEquals(FailureKind.PIN_UNCERTAIN, (runCatching { r.gate(carriesPin = true) }.exceptionOrNull() as VaultFailure).kind)
        r.gate(carriesPin = false) // a password-only operation goes
        uncertain = false // settled by the unlock
        r.gate(carriesPin = true)
        scope.cancel()
    }

    /** Nothing stored: nothing to decide, and nothing shown. */
    @Test
    fun nothingUnresolvedIsNothingShown() = runTest {
        val ops = FakeOps(null)
        val (r, scope) = resolver(testScheduler, ops)
        assertNull(r.check.value)
        assertNull(r.decide())
        r.kick()
        testScheduler.runCurrent()
        assertEquals(0, ops.versionCalls)
        assertFalse(r.check.value?.open ?: false)
        scope.cancel()
    }

    /** (N5) The decision survives a process death until it is seen: kept by the ops, restored at the next start. */
    @Test
    fun theDecisionIsKeptUntilSeen() = runTest {
        var saved: ChangeCheck? = null
        val ops = object : ChangeResolver.Ops {
            var marker: UnresolvedChange? = pinChange
            override fun marker() = marker
            override suspend fun resend(m: UnresolvedChange): ChangeResolver.Answer? = null
            override suspend fun version() = version(8, exp)
            override suspend fun fetch() = Unit
            override fun confirm(m: UnresolvedChange): Boolean {
                marker = null
                return true
            }
            override fun clear(m: UnresolvedChange) {
                marker = null
            }
            override fun savedDecision() = saved
            override fun saveDecision(c: ChangeCheck?) {
                saved = c
            }
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        assertEquals(ChangeCheck.Applied(ChangeKind.PIN), ChangeResolver(scope, ops).decide())
        assertEquals(ChangeCheck.Applied(ChangeKind.PIN), saved)
        // The process dies before the member sees it: the next start says it again.
        val again = ChangeResolver(scope, ops)
        assertEquals(ChangeCheck.Applied(ChangeKind.PIN), again.check.value)
        again.acknowledge()
        assertNull(saved)
        assertNull(ChangeResolver(scope, ops).check.value)
        scope.cancel()
    }

    private fun forbidden(type: String) = com.vettid.core.vault.VaultOpException(type, "forbidden", "not the holder")

    /**
     * ANDROID-PLAN 0.1.55 "Another phone became the holder": the vault's own `forbidden` to the resend, to
     * `credential.version`, or to `credential.get` after "applied" ends the change: the marker and the candidate
     * cleared (never ended, so the PIN is not uncertain), the gate open, the one-time notice "moved". Nothing is erased.
     */
    @Test
    fun aForbiddenFromTheVaultEndsTheChange() = runTest {
        val cases = listOf<Pair<UnresolvedChange, FakeOps.() -> Unit>>(
            pinChange to { resendThrows = forbidden(ChangeTypes.PIN_CHANGE) },
            passwordChange to { versions += forbidden("credential.version") },
            pinChange to {
                versions += version(8, exp.minusSeconds(500))
                fetchThrows = forbidden("credential.get")
            },
        )
        for ((m, setup) in cases) {
            val ops = FakeOps(m).apply(setup)
            val (r, scope) = resolver(testScheduler, ops)
            assertEquals(ChangeCheck.Moved(m.kind), r.decide())
            assertEquals(listOf(m), ops.cleared)
            assertTrue(ops.ended.isEmpty())
            assertTrue(ops.confirmed.isEmpty())
            assertNull(ops.marker)
            assertEquals(ChangeCheck.Moved(m.kind), r.check.value)
            // The gate is open again: nothing is refused for this change.
            r.gate(carriesPin = true)
            // Said once: acknowledged, it goes.
            r.acknowledge()
            assertNull(r.check.value)
            scope.cancel()
        }
    }

    /**
     * Only the vault's own answer counts (0.1.55): an HTTP 403 of the member API, a `forbidden` from the relay or the
     * host, and a timeout are no answer. The marker stays and the check is "unreachable".
     */
    @Test
    fun aForbiddenThatIsNotTheVaultsAnswerIsUnreachable() = runTest {
        val failures = listOf<Exception>(
            com.vettid.core.altchan.MemberApiException(403, "forbidden"),
            com.vettid.core.relay.RelayException(403, "forbidden", ""),
            com.vettid.core.vault.VaultStateException("credential.version: no response"),
            IOException("timeout"),
        )
        for (f in failures) {
            val ops = FakeOps(pinChange).apply {
                resendThrows = f
                versions += f
            }
            val (r, scope) = resolver(testScheduler, ops)
            assertEquals(f.toString(), ChangeCheck.Unreachable(ChangeKind.PIN), r.decide())
            assertEquals(pinChange, ops.marker)
            assertTrue(ops.cleared.isEmpty() && ops.ended.isEmpty())
            scope.cancel()
        }
    }

    /** A decided "moved" is kept on the phone until it is seen, as any decision (N5). */
    @Test
    fun aMovedDecisionIsKept() {
        val saved = SavedDecision.of(ChangeCheck.Moved(ChangeKind.PASSWORD))
        assertEquals(ChangeCheck.Moved(ChangeKind.PASSWORD), saved?.toCheck())
    }

    /** A refused change's code is kept with the decision, so the notice names it after a restart (0.1.57 (2)). */
    @Test
    fun aRefusedDecisionKeepsItsCode() {
        val c = ChangeCheck.Refused(ChangeKind.PIN, FailureKind.OTHER, "internal")
        val json = kotlinx.serialization.json.Json.encodeToString(SavedDecision.serializer(), SavedDecision.of(c)!!)
        val back = kotlinx.serialization.json.Json.decodeFromString(SavedDecision.serializer(), json)
        assertEquals(c, back.toCheck())
        // A record written before the code was kept reads without one.
        val earlier = """{"kind":"PIN","verdict":"refused","failure":"OTHER"}"""
        val old = kotlinx.serialization.json.Json.decodeFromString(SavedDecision.serializer(), earlier)
        assertEquals(ChangeCheck.Refused(ChangeKind.PIN, FailureKind.OTHER), old.toCheck())
    }
}
