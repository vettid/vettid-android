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

        /** How many times the key shown on Vault status was said not known any more. */
        var keyUnknown = 0

        override fun keyUnknown() {
            keyUnknown++
        }

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

    private fun version(v: Long?, ts: Instant, exists: Boolean = true, alarm: Boolean = false, key: String? = null) =
        ChangeResolver.Version(exists, v, alarm, ts, key)

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

    // --- a lost answer to credential.reset (VAULT-MESSAGING 0.48.0, ANDROID-PLAN 0.1.51) ---

    private val reset = UnresolvedChange(
        ChangeTypes.RESET, "01JRESET", sentVersion = 7, expMs = exp.toEpochMilli(), heldKey = "key-one",
    )

    /** The version answer decides a reset by its key first, then by its version and the answer's `ts`. */
    @Test
    fun theKeyAndVersionDecideAReset() {
        val late = exp.plusSeconds(61)
        fun v(version: Long?, key: String?, ts: Instant = late, exists: Boolean = true, alarm: Boolean = false) =
            ChangeDecision.of(reset, exists, version, alarm, ts, key)
        // A key other than the held key: applied, whatever the version (a new credential restarts at 1).
        assertEquals(ChangeDecision.Verdict.APPLIED, v(1, "key-two", ts = exp.minusSeconds(300)))
        assertEquals(ChangeDecision.Verdict.APPLIED, v(9, "key-two"))
        // The same key and a lower version: applied too.
        assertEquals(ChangeDecision.Verdict.APPLIED, v(6, "key-one"))
        // The same key and version: not applied only in an answer stamped more than a minute after exp.
        assertEquals(ChangeDecision.Verdict.NOT_APPLIED, v(7, "key-one"))
        assertEquals(ChangeDecision.Verdict.UNDECIDED, v(7, "key-one", ts = exp.plusSeconds(60)))
        assertEquals(ChangeDecision.Verdict.UNDECIDED, v(7, "key-one", ts = exp.minusSeconds(1)))
        // Anything else ends without a decision: the same key with another version, no credential, no key, an alarm.
        assertEquals(ChangeDecision.Verdict.ENDED, v(8, "key-one"))
        assertEquals(ChangeDecision.Verdict.ENDED, v(null, null, exists = false))
        assertEquals(ChangeDecision.Verdict.ENDED, v(7, null))
        assertEquals(ChangeDecision.Verdict.ENDED, v(1, "key-two", alarm = true))
        // A record without a held key (written by an older build) decides nothing from the version.
        assertEquals(ChangeDecision.Verdict.ENDED, ChangeDecision.of(reset.copy(heldKey = null), true, 1, false, late, "key-two"))
    }

    /**
     * (GC #6816, as vettid-ios #236) A lost reset decided as applied by the version says the key is not known (Vault status
     * asks again); a lost PIN or password change decided the same way keeps the key and says nothing, nor does a reset whose
     * own answer arrived (it named the key) or one decided as not applied.
     */
    @Test
    fun onlyALostResetDecidedAppliedLeavesTheKeyUnknown() = runTest {
        val one = reset.copy(sentVersion = 1)
        val applied = FakeOps(one).apply { versions += version(1, exp.minusSeconds(500), key = "key-two") }
        val (r, scope) = resolver(testScheduler, applied)
        assertEquals(ChangeCheck.Applied(ChangeKind.RESET), r.decide())
        assertEquals(1, applied.keyUnknown)
        scope.cancel()
        for (m in listOf(pinChange, passwordChange)) {
            val ops = FakeOps(m).apply { versions += version(8, exp.minusSeconds(500)) }
            val (rr, s2) = resolver(testScheduler, ops)
            assertEquals(ChangeCheck.Applied(m.kind), rr.decide())
            assertEquals("${m.kind}", 0, ops.keyUnknown)
            s2.cancel()
        }
        val answered = FakeOps(one).apply { resendAnswer = ChangeResolver.Answer.Ok }
        val (ra, s3) = resolver(testScheduler, answered)
        assertEquals(ChangeCheck.Applied(ChangeKind.RESET), ra.decide())
        assertEquals("its answer named the key", 0, answered.keyUnknown)
        s3.cancel()
        val notApplied = FakeOps(reset).apply { versions += version(7, exp.plusSeconds(61), key = "key-one") }
        val (rn, s4) = resolver(testScheduler, notApplied)
        assertEquals(ChangeCheck.NotApplied(ChangeKind.RESET), rn.decide())
        assertEquals(0, notApplied.keyUnknown)
        s4.cancel()
    }

    /** A reset from a held blob of version 1 is decided by the key alone. */
    @Test
    fun aResetFromVersionOneIsDecidedByTheKeyAlone() = runTest {
        val one = reset.copy(sentVersion = 1)
        val ops = FakeOps(one).apply { versions += version(1, exp.minusSeconds(500), key = "key-two") }
        val (r, scope) = resolver(testScheduler, ops)
        assertEquals(ChangeCheck.Applied(ChangeKind.RESET), r.decide())
        assertEquals(1, ops.fetches)
        assertEquals(listOf(one), ops.confirmed)
        scope.cancel()
    }

    /** A dropped reset that applied: the new key decides it, with no alarm; the blob is fetched and the marker confirmed. */
    @Test
    fun aDroppedResetThatAppliedIsDecidedByTheNewKey() = runTest {
        val ops = FakeOps(reset).apply { versions += version(1, exp.minusSeconds(500), key = "key-two") }
        val (r, scope) = resolver(testScheduler, ops)
        assertEquals(ChangeCheck.Applied(ChangeKind.RESET), r.decide())
        assertEquals(1, ops.fetches)
        assertEquals(listOf(reset), ops.confirmed)
        assertTrue(ops.cleared.isEmpty() && ops.ended.isEmpty())
        scope.cancel()
    }

    /** A dropped reset that never reached the vault: undecided until an answer stamped more than a minute after exp. */
    @Test
    fun aDroppedResetThatNeverArrivedIsDecidedAfterExpPlusAMinute() = runTest {
        val ops = FakeOps(reset).apply { versions += version(7, exp.plusSeconds(30), key = "key-one") }
        val (r, scope) = resolver(testScheduler, ops)
        assertEquals(ChangeCheck.Checking(ChangeKind.RESET), r.decide())
        assertEquals(reset, ops.marker)
        ops.versions.clear()
        ops.versions += version(7, exp.plusSeconds(61), key = "key-one")
        assertEquals(ChangeCheck.NotApplied(ChangeKind.RESET), r.decide())
        assertEquals(listOf(reset), ops.cleared)
        assertEquals(0, ops.fetches)
        scope.cancel()
    }

    /** A resend answered from the vault's cache decides by itself: the new blob is success, every refusal is not applied. */
    @Test
    fun aResentResetIsDecidedByItsAnswer() = runTest {
        val ok = FakeOps(reset).apply { resendAnswer = ChangeResolver.Answer.Ok }
        val (r, scope) = resolver(testScheduler, ok)
        assertEquals(ChangeCheck.Applied(ChangeKind.RESET), r.decide())
        assertEquals(0, ok.versionCalls)
        scope.cancel()
        for (stale in listOf(false, true)) {
            val refused = FakeOps(reset).apply { resendAnswer = ChangeResolver.Answer.Refused(FailureKind.BAD_PASSWORD, stale = stale) }
            val (r2, scope2) = resolver(testScheduler, refused)
            val c = r2.decide()
            val expected =
                if (stale) ChangeCheck.NotApplied(ChangeKind.RESET) else ChangeCheck.Refused(ChangeKind.RESET, FailureKind.BAD_PASSWORD)
            assertEquals(expected, c)
            assertEquals(listOf(reset), refused.cleared)
            scope2.cancel()
        }
    }

    /** The check ends without a decision when the version cannot tell; nothing is retried with the typed secrets. */
    @Test
    fun aResetThatCannotBeDecidedEnds() = runTest {
        val ops = FakeOps(reset).apply { versions += version(8, exp, key = "key-one") }
        val (r, scope) = resolver(testScheduler, ops)
        assertEquals(ChangeCheck.Ended(ChangeKind.RESET), r.decide())
        assertEquals(listOf(reset), ops.ended)
        scope.cancel()
    }

    /** `forbidden` from the vault to the resend, `credential.version` or the fetch after "applied" ends the reset as moved. */
    @Test
    fun aResetThatGetsForbiddenIsMoved() = runTest {
        val cases = listOf<FakeOps.() -> Unit>(
            { resendThrows = forbidden(ChangeTypes.RESET) },
            { versions += forbidden("credential.version") },
            {
                versions += version(1, exp.minusSeconds(500), key = "key-two")
                fetchThrows = forbidden("credential.get")
            },
        )
        for (setup in cases) {
            val ops = FakeOps(reset).apply(setup)
            val (r, scope) = resolver(testScheduler, ops)
            assertEquals(ChangeCheck.Moved(ChangeKind.RESET), r.decide())
            assertEquals(listOf(reset), ops.cleared)
            assertNull(ops.marker)
            scope.cancel()
        }
    }

    /**
     * §11.14.9 step 2: while a reset is unresolved an owner check and a `wallet.sign` are refused at once, with the reset
     * wording while the vault can be reached and "can't reach your vault" while it cannot; nothing is sent.
     */
    @Test
    fun anOwnerCheckAndAWalletSignAreRefusedWhileAResetIsUnresolved() = runTest {
        val ops = FakeOps(reset).apply { versions += IOException("no answer") }
        val (r, scope) = resolver(testScheduler, ops)
        suspend fun send(type: String, carriesPin: Boolean) {
            r.gate(carriesPin)
            ops.sent += type
        }
        for (type in listOf("vault.owner_check", "wallet.sign")) {
            val e = runCatching { send(type, type == "vault.owner_check") }.exceptionOrNull() as VaultFailure
            assertEquals(FailureKind.RESET_UNDECIDED, e.kind)
        }
        assertTrue(ops.sent.isEmpty())
        testScheduler.runCurrent()
        assertEquals(ChangeCheck.Unreachable(ChangeKind.RESET), r.check.value)
        for (type in listOf("vault.owner_check", "wallet.sign")) {
            assertEquals(FailureKind.CHANGE_UNRESOLVED, (runCatching { send(type, false) }.exceptionOrNull() as VaultFailure).kind)
        }
        assertTrue(ops.sent.isEmpty())
        scope.cancel()
    }

    /** A marker record written without `heldKey` still reads; the key is public to the vault but never printed. */
    @Test
    fun aMarkerWithoutAHeldKeyStillReads() {
        val json = """{"type":"credential.reset","innerId":"01JOLD","sentVersion":4,"expMs":9}"""
        val m = kotlinx.serialization.json.Json.decodeFromString(UnresolvedChange.serializer(), json)
        assertNull(m.heldKey)
        assertEquals(ChangeKind.RESET, m.kind)
        val back = kotlinx.serialization.json.Json.decodeFromString(
            UnresolvedChange.serializer(),
            kotlinx.serialization.json.Json.encodeToString(UnresolvedChange.serializer(), reset),
        )
        assertEquals(reset, back)
        assertFalse(reset.toString().contains("key-one"))
    }

    /**
     * (ANDROID-PLAN 0.1.55 "Only the vault's own answer counts") An HTTP 403, a `forbidden` of the relay, the member API or the
     * host (none is the vault's error answer) and a timeout, to the resend, to `credential.version` or to the fetch after
     * "applied", leave the reset's marker and say "can't reach"; no wipe, no gate opening.
     */
    @Test
    fun anErrorThatIsNotTheVaultsOwnForbiddenLeavesTheResetUnresolved() = runTest {
        val errors = listOf<Exception>(
            IOException("HTTP 403"),
            IllegalStateException("relay: forbidden"),
            java.net.SocketTimeoutException("timeout"),
        )
        for (e in errors) {
            val cases = listOf<FakeOps.() -> Unit>(
                { resendThrows = e; versions += IOException("no answer") },
                { versions += e },
                {
                    versions += version(1, exp.minusSeconds(500), key = "key-two")
                    fetchThrows = e
                },
            )
            for (setup in cases) {
                val ops = FakeOps(reset).apply(setup)
                val (r, scope) = resolver(testScheduler, ops)
                assertEquals(ChangeCheck.Unreachable(ChangeKind.RESET), r.decide())
                assertEquals(reset, ops.marker)
                assertTrue(ops.cleared.isEmpty() && ops.confirmed.isEmpty())
                scope.cancel()
            }
        }
    }
}
