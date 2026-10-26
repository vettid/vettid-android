package com.vettid.core.data.vault

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ANDROID-PLAN 0.1.78 "A phone that could not tell" (VAULT-MESSAGING 0.59.0 §11.14.6, option A): the rules and each outcome. */
class RecoveryReuseTest {
    private val min = 60_000L
    private val day = 24 * 60 * min

    // --- when: the trigger and the two reads ---

    @Test
    fun theRuleAppliesOnlyAfterAnOpenWithNoReadableAnswerOrWhileARecordStands() {
        assertFalse(Rules.applies(openUnanswered = false, reuse = null))
        assertTrue(Rules.applies(openUnanswered = true, reuse = null))
        assertTrue(Rules.applies(openUnanswered = false, reuse = ReuseRecord(0, "r")))
        assertFalse(Rules.undecided(false, null))
        assertTrue(Rules.undecided(true, null))
        assertTrue(Rules.undecided(false, ReuseRecord(0, "r")))
    }

    private fun at(wallMin: Long, monoMin: Long = wallMin) = Rules.Clock(wallMin * min, monoMin * min)

    /** One read after another on both clocks; the decisions in order. */
    private fun reads(vararg steps: Pair<Rules.Read, Rules.Clock>, second: Boolean = false): List<Rules.Decision> {
        var seen = Rules.Seen()
        return steps.map { (r, c) ->
            val (d, s) = Rules.afterRead(r, seen, c, second)
            seen = s
            d
        }
    }

    @Test
    fun aListedStatusIsTheLostOkUnlockOnlyAfterTheSamePairAndNeverAdoption() {
        val l = Rules.Read.Listed(registered = false)
        val d = reads(l to at(0), l to at(1), l to at(6))
        assertEquals(Rules.Decision.Wait(5 * min), d[0])
        assertEquals(Rules.Decision.Wait(5 * min), d[1])
        assertEquals(Rules.Decision.LostOk, d[2])
        // Still registered: the phone opens again, with the same pair.
        val r = Rules.Read.Listed(registered = true)
        assertEquals(Rules.Decision.OpenAgain, reads(r to at(0), r to at(5))[1])
        // An ended read between restarts the pair of 200s.
        val mixed = reads(l to at(0), Rules.Read.Ended to at(6), l to at(7))
        assertEquals(Rules.Decision.Wait(12 * min), mixed[2])
    }

    @Test
    fun aNewRecoveryNeedsTwoEndedReadsAtLeastFiveMinutesApartOnBothClocks() {
        val e = Rules.Read.Ended
        val d = reads(e to at(0), e to at(4), e to at(5), e to at(60))
        assertEquals(Rules.Decision.Wait(5 * min), d[0])
        assertEquals(Rules.Decision.Wait(5 * min), d[1]) // too soon: the first read is kept
        assertEquals(Rules.Decision.StartNew, d[2])
        assertEquals(Rules.Decision.StartNew, d[3])
    }

    @Test
    fun bothClocksMustAgreeAndARebootOrASetClockRestartsThePair() {
        val e = Rules.Read.Ended
        // The wall clock says an hour, the monotonic clock 1 minute (the wall clock was set forward): restarts.
        assertEquals(Rules.Decision.Wait(60 * min + 5 * min), reads(e to at(0), e to at(60, monoMin = 1))[1])
        // The monotonic clock says an hour, the wall clock 6 minutes (set back): restarts.
        assertEquals(Rules.Decision.Wait(6 * min + 5 * min), reads(e to at(0), e to at(6, monoMin = 60))[1])
        // A reboot: the monotonic clock went back below the first read; restarts.
        assertEquals(Rules.Decision.Wait(30 * min + 5 * min), reads(e to at(0, monoMin = 100), e to at(30, monoMin = 2))[1])
        // A reboot just after the first read, the two measures still within 2 minutes: restarts, not "too soon".
        assertEquals(Rules.Decision.Wait(1 * min + 5 * min), reads(e to at(0, monoMin = 100), e to at(1, monoMin = 99))[1])
        // The wall clock went back: restarts.
        assertEquals(Rules.Decision.Wait(3 * min + 5 * min), reads(e to at(10), e to at(3, monoMin = 13))[1])
        // Within 2 minutes of each other: counts.
        assertEquals(Rules.Decision.StartNew, reads(e to at(0), e to at(6, monoMin = 7))[1])
        assertEquals(Rules.Decision.Wait(30 * min + 5 * min), reads(e to at(0), e to at(30, monoMin = 33))[1])
        // Only one clock at five minutes: not enough.
        assertEquals(Rules.Decision.Wait(5 * min), reads(e to at(0), e to at(6, monoMin = 4))[1])
    }

    @Test
    fun withBothIdentitiesTwoEndedReadsKeepBothAndStillAllowANewCodeThatReusesN() {
        val e = Rules.Read.Ended
        assertEquals(Rules.Decision.KeepBoth, reads(e to at(0), e to at(5), second = true)[1])
        assertEquals(Rules.Decision.StartNew, reads(e to at(0), e to at(5), second = false)[1])
        // A new code reuses N (never a third identity); undecided and allowed is the only way in.
        assertEquals(Rules.Gate.REUSE, Rules.gateNewCode(undecided = true, startNew = true))
        assertEquals(Rules.Gate.REFUSE, Rules.gateNewCode(undecided = true, startNew = false))
        assertEquals(Rules.Gate.FRESH, Rules.gateNewCode(undecided = false, startNew = false))
    }

    @Test
    fun aFirstRegisterThatFailedBeforeTheVaultMayBeTriedAgainWithTheSameN() {
        assertTrue(Rules.registerFailedBeforeVault(false, false, null, null)) // no network
        assertTrue(Rules.registerFailedBeforeVault(false, false, 503, "release_starting"))
        assertTrue(Rules.registerFailedBeforeVault(false, false, 503, "vault_unavailable"))
        assertTrue(Rules.registerFailedBeforeVault(false, false, 429, "rate_limited"))
        assertTrue(Rules.registerFailedBeforeVault(false, false, 409, "recovery_not_available"))
        // Anything else may have reached the vault: N stays unknown.
        assertFalse(Rules.registerFailedBeforeVault(false, false, 500, "internal"))
        assertFalse(Rules.registerFailedBeforeVault(false, false, 504, "http_504"))
        assertFalse(Rules.registerFailedBeforeVault(false, false, 400, "bad_request"))
    }

    @Test
    fun aFailureAfterThe202IsNeverBeforeTheVault() {
        // A poll that failed (no network, a 503, a 429) or a slot that never answered, after the register was accepted.
        for ((status, code) in listOf<Pair<Int?, String?>>(
            null to null, 503 to "release_starting", 503 to "vault_unavailable", 429 to "rate_limited", 409 to "recovery_not_available",
        )) {
            assertFalse("$status $code", Rules.registerFailedBeforeVault(false, true, status, code))
        }
        // Before the POST (no 202) the same failures are still before the vault.
        assertTrue(Rules.registerFailedBeforeVault(false, false, null, null))
    }

    @Test
    fun aRepostInTheSameFlowAfterAnExpiredSlotIsARepeat() {
        // The first POST of a first flow is not a repeat; the re-post after its 202 and an expired slot is.
        assertFalse(Rules.isRepeatPost(triedBefore = false, postedEarlierInFlow = false))
        val repeat = Rules.isRepeatPost(triedBefore = false, postedEarlierInFlow = true)
        assertTrue(repeat)
        // Answered 409 recovery_not_available or used: maybe registered (status signed with K), never before the vault.
        assertTrue(Rules.maybeRegistered(repeat, "recovery_not_available"))
        assertTrue(Rules.maybeRegistered(repeat, "used"))
        assertFalse(Rules.registerFailedBeforeVault(repeat, false, 409, "recovery_not_available"))
        // An earlier flow's POST for the same code makes the first POST of this flow a repeat too.
        assertTrue(Rules.isRepeatPost(triedBefore = true, postedEarlierInFlow = false))
    }

    private fun failure(
        tried: Boolean = false,
        postedEarlier: Boolean = false,
        accepted: Boolean = false,
        earlierAccepted: Boolean = false,
        beforeNew: Boolean = false,
        status: Int? = null,
        code: String? = null,
    ) = Rules.registerFailure(tried, postedEarlier, accepted, earlierAccepted, beforeNew, status, code)

    @Test
    fun theOutcomeOfAFailedRegisterMatrix() {
        // (a) 401 on a re-post after an earlier 202 (the 202 stays remembered): maybe registered, nothing dropped.
        assertEquals(
            Rules.RegisterOutcome.MAYBE_REGISTERED,
            failure(postedEarlier = true, earlierAccepted = true, status = 401, code = "unauthorized"),
        )
        // (b) 401 with N registered before this flow: nothing dropped.
        assertEquals(Rules.RegisterOutcome.MAYBE_REGISTERED, failure(beforeNew = true, status = 401, code = "unauthorized"))
        // ... and only the first POST of an N never registered drops it.
        assertEquals(Rules.RegisterOutcome.DROP_UNAUTHORIZED, failure(status = 401, code = "unauthorized"))
        assertEquals(Rules.RegisterOutcome.MAYBE_REGISTERED, failure(postedEarlier = true, status = 401, code = "unauthorized"))
        // (c) a sealed bad_code to POST 2 after POST 1's 202; (d) to the only POST after its 202: settled.
        assertEquals(Rules.RegisterOutcome.MAYBE_REGISTERED, Rules.registerRefusal(true, true, "bad_code"))
        assertEquals(Rules.RegisterOutcome.DROP_REFUSED, Rules.registerRefusal(false, false, "bad_code"))
        assertEquals(Rules.RegisterOutcome.DROP_REFUSED, Rules.registerRefusal(false, false, null))
        assertEquals(Rules.RegisterOutcome.MAYBE_REGISTERED, Rules.registerRefusal(true, false, "used"))
        // (e) instance_moved (a POST built, no 202) then a 503: the repeat keeps N unknown.
        assertEquals(Rules.RegisterOutcome.KEEP_UNKNOWN, failure(postedEarlier = true, status = 503, code = "release_starting"))
        // (f) a first POST answered 503: before the vault.
        assertEquals(Rules.RegisterOutcome.DROP_AND_RETRY, failure(status = 503, code = "release_starting"))
        assertEquals(Rules.RegisterOutcome.DROP_AND_RETRY, failure()) // no network before the POST
        // (g) recovery_unavailable on a repeat (or after a 202): N is kept; on a first POST it is refused.
        assertEquals(Rules.RegisterOutcome.KEEP_UNKNOWN, failure(tried = true, status = 409, code = "recovery_unavailable"))
        assertEquals(Rules.RegisterOutcome.MAYBE_REGISTERED, failure(accepted = true, status = 409, code = "recovery_unavailable"))
        assertEquals(Rules.RegisterOutcome.DROP_REFUSED, failure(status = 409, code = "recovery_unavailable"))
        // A 409 recovery_not_available: before the vault on a first POST, maybe registered on a repeat.
        assertEquals(Rules.RegisterOutcome.DROP_REFUSED, failure(status = 409, code = "recovery_not_available"))
        assertEquals(Rules.RegisterOutcome.MAYBE_REGISTERED, failure(postedEarlier = true, status = 409, code = "recovery_not_available"))
        // After this POST's own 202 every failure is maybe registered, a poll failure included.
        assertEquals(Rules.RegisterOutcome.MAYBE_REGISTERED, failure(accepted = true))
        assertEquals(Rules.RegisterOutcome.MAYBE_REGISTERED, failure(accepted = true, status = 503, code = "vault_unavailable"))
    }

    @Test
    fun aRepeatForTheSameCodeIsMaybeRegisteredNeverBeforeTheVault() {
        for ((status, code) in listOf<Pair<Int?, String?>>(
            null to null, 503 to "release_starting", 429 to "rate_limited", 409 to "recovery_not_available",
        )) {
            assertFalse("$status $code", Rules.registerFailedBeforeVault(true, false, status, code))
        }
        assertTrue(Rules.maybeRegistered(true, "recovery_not_available"))
        assertTrue(Rules.maybeRegistered(true, "used"))
        assertFalse(Rules.maybeRegistered(false, "recovery_not_available")) // a first attempt: before the vault
        assertFalse(Rules.maybeRegistered(false, "used"))
        assertFalse(Rules.maybeRegistered(true, "expired"))
    }

    @Test
    fun nKeysAreNeverReplacedOnceNWasOrMayHaveBeenRegistered() {
        assertTrue(Rules.mayMakeNewKeysForN(newRegistered = false, newRecoveryId = null))
        assertFalse(Rules.mayMakeNewKeysForN(newRegistered = true, newRecoveryId = null))
        assertFalse(Rules.mayMakeNewKeysForN(newRegistered = false, newRecoveryId = "R2"))
    }

    @Test
    fun noFailureOfAPostOrAfterItRemovesAnOpenEntry() {
        // The status-derived codes of a refused post or poll (401 unauthorized, 409 duplicate_request, 429, 404) are not
        // sealed refusals: none of them settles an entry. An HTTP client may have sent a copy that was queued.
        val l = sent("rid1")
        val codes = listOf("unauthorized", "duplicate_request", "rate_limited", "not_found", "http_401", "http_409", "http_429", "http_404")
        for (c in codes) {
            assertEquals(c, listOf("rid1"), answer(l, "rid1", c))
        }
    }

    @Test
    fun theCouldNotRecoverTextComesOnlyFromAFreshOpenThatRuledRecoveryOut() {
        for (c in listOf("exhausted", "closed", "not_armed")) {
            assertTrue(c, Rules.showCouldNotRecover(keyInUse = false, secondIdentity = true, openCode = c))
        }
        // Not after ordinary refusals, a wrong PIN, a retry, or without both identities or with key_in_use.
        for (c in listOf("wrong", "retry", "bad_pin", "device_secret", "wait", null)) {
            assertFalse(c.toString(), Rules.showCouldNotRecover(false, true, c))
        }
        assertFalse(Rules.showCouldNotRecover(keyInUse = true, secondIdentity = true, openCode = "exhausted"))
        assertFalse(Rules.showCouldNotRecover(keyInUse = false, secondIdentity = false, openCode = "exhausted"))
    }

    @Test
    fun anUnknownReadConcludesNothingAndKeepsTheFirstRead() {
        val (d, s) = Rules.afterRead(Rules.Read.Unknown, Rules.Seen(ended = at(1)), at(30))
        assertEquals(Rules.Decision.Unknown, d)
        assertEquals(Rules.Seen(ended = at(1)), s)
    }

    @Test
    fun onlyTheVaultRefusingThePhoneCountsTowardTheDay() {
        for (c in listOf("bad_pin", "backoff", "retry", "busy", "counter_unavailable", "no_answer", "timeout", "network", "service")) {
            assertFalse(c, Rules.countsAsRefusal(c))
        }
        for (c in listOf("device_secret", "no_bundle", "no_recovery", "unknown_device")) assertTrue(c, Rules.countsAsRefusal(c))
    }

    @Test
    fun theTookPhoneTextIsStagingScaledAndNeedsKeyInUse() {
        assertTrue(Rules.showContact(keyInUse = true, refusal = true, sinceMs = 0, nowMs = day))
        assertFalse(Rules.showContact(keyInUse = false, refusal = true, sinceMs = 0, nowMs = day))
        assertTrue(Rules.showContact(true, true, 0, day / 24, scale = 24))
        assertFalse(Rules.showContact(true, true, 0, day / 24 - 1, scale = 24))
    }

    @Test
    fun aCodeForAnotherVaultIsRefused() {
        assertTrue(Rules.sameVault("v1", "v1"))
        assertTrue(Rules.sameVault("v1", null)) // an older API names none
        assertFalse(Rules.sameVault("v1", "v2"))
    }

    @Test
    fun exhaustedAndNotArmedSettleOnlyTheirOwnOpen() {
        val l = sent("rid1", "rid2")
        for (c in listOf("exhausted", "not_armed")) {
            assertEquals(c, listOf("rid1"), answer(l, "rid2", c))
            assertEquals(c, emptyList<String>(), answer(sent("rid1"), "rid1", c))
        }
    }

    // --- what it may do: the three sequences ---

    private class NewPorts(
        val claim: ClaimAnswer = ClaimAnswer.Claimed,
        val earlier: EarlierOpen = EarlierOpen.Undecided,
        val register: RegisterAnswer = RegisterAnswer.Registered,
    ) : NewRecovery.Ports {
        val log = mutableListOf<String>()

        override suspend fun claim(): ClaimAnswer = claim.also { log += "claim" }

        override suspend fun pollEarlierOpen(): EarlierOpen = earlier.also { log += "poll" }

        override suspend fun registerNew(): RegisterAnswer = register.also { log += "register" }

        override suspend fun lostOkDue() {
            log += "lostOkDue"
        }
    }

    @Test
    fun theNewRecoveryClaimsFirstLooksOnceAtTheEarlierOpenThenRegistersTheNewIdentity() = runTest {
        val p = NewPorts()
        assertEquals(NewRecovery.Result.Registered, NewRecovery(p).run())
        assertEquals(listOf("claim", "poll", "register"), p.log)
    }

    @Test
    fun keyInUseAtTheClaimIsTheLostOkUnlockAndRegistersNothing() = runTest {
        val p = NewPorts(claim = ClaimAnswer.KeyInUse)
        assertEquals(NewRecovery.Result.KeyInUse, NewRecovery(p).run())
        assertEquals(listOf("claim", "lostOkDue"), p.log)
    }

    @Test
    fun aReadableOkInTheEarlierSlotDecidesAndNothingIsRegistered() = runTest {
        val p = NewPorts(earlier = EarlierOpen.Ok)
        assertEquals(NewRecovery.Result.EarlierOk, NewRecovery(p).run())
        assertEquals(listOf("claim", "poll"), p.log)
    }

    @Test
    fun a401AtTheRegisterAndARefusedClaimAreReportedAndKeepEverything() = runTest {
        assertEquals(NewRecovery.Result.RegisterUnauthorized, NewRecovery(NewPorts(register = RegisterAnswer.Unauthorized)).run())
        assertEquals(NewRecovery.Result.Refused("expired"), NewRecovery(NewPorts(claim = ClaimAnswer.Refused("expired"))).run())
        val p = NewPorts(claim = ClaimAnswer.Refused("no_recovery"))
        NewRecovery(p).run()
        assertEquals(listOf("claim"), p.log) // a refused claim reaches nothing else
        val notAvailable = NewPorts(register = RegisterAnswer.Refused("not_available"))
        assertEquals(NewRecovery.Result.Refused("not_available"), NewRecovery(notAvailable).run())
    }

    private class LostPorts(private val answers: Map<Identity, LostOkAnswer>) : LostOk.Ports {
        val order = mutableListOf<Identity>()

        override suspend fun unlock(identity: Identity): LostOkAnswer {
            order += identity
            return answers.getValue(identity)
        }
    }

    @Test
    fun theLostOkUnlockTriesTheNewestIdentityFirstThenTheEarlierOne() = runTest {
        val p = LostPorts(mapOf(Identity.NEW to LostOkAnswer.Refused("device_secret"), Identity.EARLIER to LostOkAnswer.Ok))
        assertEquals(LostOk.Result.Ok(Identity.EARLIER), LostOk(p).run(newRegistered = true))
        assertEquals(listOf(Identity.NEW, Identity.EARLIER), p.order)
        // The first `ok` pairs: the earlier identity is not tried.
        val q = LostPorts(mapOf(Identity.NEW to LostOkAnswer.Ok, Identity.EARLIER to LostOkAnswer.Ok))
        assertEquals(LostOk.Result.Ok(Identity.NEW), LostOk(q).run(newRegistered = true))
        assertEquals(listOf(Identity.NEW), q.order)
    }

    @Test
    fun anIdentityThatNeverRegisteredIsNotTried() = runTest {
        val p = LostPorts(mapOf(Identity.EARLIER to LostOkAnswer.Ok))
        assertEquals(LostOk.Result.Ok(Identity.EARLIER), LostOk(p).run(newRegistered = false))
        assertEquals(listOf(Identity.EARLIER), p.order)
    }

    @Test
    fun transferIncompleteIsReportedForAnOpenAndRefusalsKeepEverything() = runTest {
        val p = LostPorts(mapOf(Identity.EARLIER to LostOkAnswer.TransferIncomplete))
        assertEquals(LostOk.Result.TransferIncomplete(Identity.EARLIER), LostOk(p).run(false))
        val q = LostPorts(mapOf(Identity.NEW to LostOkAnswer.Refused("retry"), Identity.EARLIER to LostOkAnswer.Refused("device_secret")))
        assertEquals(LostOk.Result.NoneOk("device_secret"), LostOk(q).run(true))
        assertEquals(listOf(Identity.NEW, Identity.EARLIER), q.order)
    }

    @Test
    fun theContactTextComesAfterADayOfRefusalsWithTheClockChecked() {
        assertFalse(Rules.contactVettid(null, 5 * day))
        assertFalse(Rules.contactVettid(1_000L, 1_000L + day - 1))
        assertTrue(Rules.contactVettid(1_000L, 1_000L + day))
        // A clock set back before the first refusal never shows it.
        assertFalse(Rules.contactVettid(10 * day, 2 * day))
    }

    private class PairPorts(
        private val pairs: Map<Identity, PairAnswer>,
        private val unlock: LostOkAnswer = LostOkAnswer.Ok,
    ) : Pairing.Ports {
        val log = mutableListOf<String>()

        override suspend fun pair(identity: Identity): PairAnswer {
            log += "pair:$identity"
            return pairs.getValue(identity)
        }

        override suspend fun unlockEarlier(): LostOkAnswer {
            log += "unlockEarlier"
            return unlock
        }
    }

    @Test
    fun theNewIdentityPairsWithItsOwnRecoveryIdAndTheEarlierOneIsNotTouched() = runTest {
        val p = PairPorts(mapOf(Identity.NEW to PairAnswer.PAIRED))
        assertEquals(Pairing.Result.Paired(Identity.NEW), Pairing(p).run())
        assertEquals(listOf("pair:NEW"), p.log)
    }

    @Test
    fun withNoHsRespTheEarlierIdentityUnlocksAndPairsWithItsOwnRecoveryId() = runTest {
        val p = PairPorts(mapOf(Identity.NEW to PairAnswer.NO_RESPONSE, Identity.EARLIER to PairAnswer.PAIRED))
        assertEquals(Pairing.Result.Paired(Identity.EARLIER), Pairing(p).run())
        assertEquals(listOf("pair:NEW", "unlockEarlier", "pair:EARLIER"), p.log)
    }

    @Test
    fun aFailedFallBackKeepsEverything() = runTest {
        val refused = PairPorts(mapOf(Identity.NEW to PairAnswer.NO_RESPONSE), unlock = LostOkAnswer.Refused("device_secret"))
        assertEquals(Pairing.Result.Failed("device_secret"), Pairing(refused).run())
        assertEquals(listOf("pair:NEW", "unlockEarlier"), refused.log)
        val silent = PairPorts(mapOf(Identity.NEW to PairAnswer.NO_RESPONSE, Identity.EARLIER to PairAnswer.NO_RESPONSE))
        assertEquals(Pairing.Result.Failed("no_hs_resp"), Pairing(silent).run())
        val incomplete = PairPorts(mapOf(Identity.NEW to PairAnswer.NO_RESPONSE), unlock = LostOkAnswer.TransferIncomplete)
        assertEquals(Pairing.Result.Failed("transfer_incomplete"), Pairing(incomplete).run())
    }

    // --- what it keeps ---

    @Test
    fun nothingIsDeletedBeforeTheVaultAnsweredOkAndThePairingCompleted() {
        assertFalse(Rules.mayDeleteEarlier(openOk = false, pairingComplete = true))
        assertFalse(Rules.mayDeleteEarlier(openOk = true, pairingComplete = false))
        assertTrue(Rules.mayDeleteEarlier(openOk = true, pairingComplete = true))
    }

    @Test
    fun theRecordKeepsBothIdentitiesAndRoundTrips() {
        val r = ReuseRecord(
            earlierSlot = 0, earlierRecoveryId = "01EARLIER", newSlot = 1, keyInUse = true, newRecoveryId = "01NEW",
            newRegistered = true, endedSeen = Rules.Clock(5L, 6L), listedSeen = Rules.Clock(7L, 8L), startNew = true,
            lostOkDue = false, refusedSinceMs = 9L,
        )
        val json = Json
        assertEquals(r, json.decodeFromString<ReuseRecord>(json.encodeToString(ReuseRecord.serializer(), r)))
        // An older record without the later members still reads (defaults).
        assertEquals(
            ReuseRecord(0, "01EARLIER"),
            json.decodeFromString<ReuseRecord>("""{"earlierSlot":0,"earlierRecoveryId":"01EARLIER"}"""),
        )
    }

    @Test
    fun theLostOkOrderIsNewestFirst() {
        assertEquals(listOf(Identity.NEW, Identity.EARLIER), Rules.lostOkOrder(true))
        assertEquals(listOf(Identity.EARLIER), Rules.lostOkOrder(false))
    }

    // --- the guards, as pure decisions (the review of #195) ---

    private val o1 = SentOpen("rid1", "rec1", 0)
    private val o2 = SentOpen("rid2", "rec1", 0)

    private fun sent(vararg rids: String): List<SentOpen> =
        rids.fold(emptyList()) { l, r -> Ledger.afterSent(false, l, r, "rec", 0) }

    private fun answer(l: List<SentOpen>, rid: String, code: String, ok: Boolean = false) =
        Ledger.afterAnswer(flag = l.isNotEmpty(), ledger = l, rid = rid, code = code, ok = ok).map { it.rid }

    @Test
    fun unansweredIsStickyAndALaterAnswerOnlyAnswersItself() {
        // Open 1 gets no answer; open 2 is answered `wrong`: both stay (wrong proves nothing with an earlier open standing).
        val l = sent("rid1", "rid2")
        assertEquals(listOf("rid1", "rid2"), answer(l, "rid2", "wrong"))
        // A wait verdict for open 2 removes open 2 only; open 1 stays unanswered.
        assertEquals(listOf("rid1"), answer(l, "rid2", "wait"))
        assertTrue(Rules.undecided(true, null))
        // A lone open: `wrong` and `expired` leave it, a refusal before the spend too.
        for (c in listOf("wrong", "expired", "busy", "wrong_again", "attempt_lost", "closed", "counter_unavailable", "wait", "quiet")) {
            assertEquals(c, emptyList<String>(), answer(sent("rid1"), "rid1", c))
        }
        // The same open is not listed twice.
        assertEquals(listOf("rid1"), sent("rid1", "rid1").map { it.rid })
    }

    @Test
    fun aSealedRetryOrAnyOtherCodeLeavesTheOpenUnanswered() {
        // The vault can store step 1 and still answer `retry`: Start over would lose K and the secret.
        for (c in listOf("retry", "stored_mismatch", "missing", "vault_missing", "no_recovery", "something_new")) {
            assertEquals(c, listOf("rid1"), answer(sent("rid1"), "rid1", c))
        }
    }

    @Test
    fun wrongAndExpiredProveNothingWhenAnEarlierOpenStands() {
        for (c in listOf("wrong", "expired")) assertEquals(c, listOf("rid1", "rid2"), answer(sent("rid1", "rid2"), "rid2", c))
        // ... but the earlier one answered with them leaves when it is the first.
        assertEquals(listOf("rid2"), answer(sent("rid1", "rid2"), "rid1", "wrong"))
    }

    @Test
    fun anOkDoesNotRemoveAnythingUntilEverythingCompleted() {
        assertEquals(listOf("rid1", "rid2"), answer(sent("rid1", "rid2"), "rid2", "ok", ok = true))
        assertFalse(Ledger.completes(ok = true, paired = false, credential = true))
        assertFalse(Ledger.completes(ok = true, paired = true, credential = false))
        assertFalse(Ledger.completes(ok = false, paired = true, credential = true))
        assertTrue(Ledger.completes(ok = true, paired = true, credential = true))
    }

    @Test
    fun aRecordOfAnEarlierBuildThatSaysUnansweredStaysSticky() {
        // Flag true, no list: a synthetic entry that no answer can remove.
        val seeded = Ledger.seed(true, emptyList())
        assertEquals(1, seeded.size)
        assertEquals(emptyList<SentOpen>(), Ledger.seed(false, emptyList()))
        // A new open sent and answered `wait` leaves the legacy one standing.
        val l = Ledger.afterSent(true, emptyList(), "rid9", "rec", 0)
        assertEquals(2, l.size)
        val after = Ledger.afterAnswer(true, l, "rid9", "wait", ok = false)
        assertEquals(1, after.size)
        assertTrue(Rules.undecided(after.isNotEmpty(), null))
        // An answer with the flag and no list at all changes nothing.
        assertEquals(1, Ledger.afterAnswer(true, emptyList(), "rid9", "wait", ok = false).size)
    }

    @Test
    fun anOpenWhoseRequestIdIsUnknownIsKeptUnanswered() {
        val l = Ledger.afterSent(false, emptyList(), null, "rec", 0)
        assertEquals(1, l.size)
        // No answer can name it, so none removes it.
        assertEquals(1, Ledger.afterAnswer(true, l, null, "wait", ok = false).size)
        assertEquals(1, Ledger.afterAnswer(true, l, "ridX", "wait", ok = false).size)
        // Two such opens are two entries.
        assertEquals(2, Ledger.afterSent(false, l, null, "rec", 0).size)
        // A synthetic entry is not looked at.
        assertTrue(Ledger.toPoll(l).isEmpty())
    }

    @Test
    fun everyUnansweredOpenIsLookedAtOnce() {
        val l = listOf(o1, o2)
        assertEquals(listOf(o1, o2), Ledger.toPoll(l))
        val p = Ledger.polled(l, "rid1")
        assertEquals(listOf(o2), Ledger.toPoll(p))
        assertEquals(emptyList<SentOpen>(), Ledger.toPoll(Ledger.polled(p, "rid2")))
    }

    @Test
    fun theUndecidedGuardHoldsWhateverTheDeviceSays() {
        // A record stands (even after a refused register): undecided, nothing may be forgotten or generated.
        assertTrue(Rules.undecided(openUnanswered = false, reuse = ReuseRecord(0, "r")))
        for (u in listOf(true)) {
            assertFalse(Rules.mayForget(u))
            assertFalse(Rules.mayGenerateKeys(u))
        }
        assertTrue(Rules.mayForget(false))
        assertTrue(Rules.mayGenerateKeys(false))
    }

    @Test
    fun aNewCodeIsRefusedWhileUndecidedUnlessTwoReadsAllowedIt() {
        assertEquals(Rules.Gate.FRESH, Rules.gateNewCode(undecided = false, startNew = false))
        assertEquals(Rules.Gate.REFUSE, Rules.gateNewCode(undecided = true, startNew = false))
        assertEquals(Rules.Gate.REUSE, Rules.gateNewCode(undecided = true, startNew = true))
    }

    @Test
    fun openAgainUsesTheIdentityRegisteredForTheCurrentRecovery() {
        assertEquals(1, Rules.slotForOpenAgain(newRegistered = true, newSlot = 1, earlierSlot = 0))
        assertEquals(0, Rules.slotForOpenAgain(newRegistered = false, newSlot = 1, earlierSlot = 0))
        assertEquals(0, Rules.slotForOpenAgain(newRegistered = true, newSlot = null, earlierSlot = 0))
        assertEquals(0, Rules.slotAfterRefusedRegister(0))
    }

    @Test
    fun theNewIdentityPairsWithTheFallbackOnlyWhenItIsInUse() {
        assertTrue(Rules.pairsWithFallback(newSlot = 1, activeSlot = 1))
        assertFalse(Rules.pairsWithFallback(newSlot = 1, activeSlot = 0))
        assertFalse(Rules.pairsWithFallback(newSlot = null, activeSlot = 0))
    }

    @Test
    fun theContactTextNeedsKeyInUseARealRefusalAndADay() {
        assertTrue(Rules.showContact(keyInUse = true, refusal = true, sinceMs = 0L, nowMs = day))
        assertFalse(Rules.showContact(keyInUse = false, refusal = true, sinceMs = 0L, nowMs = day)) // a 200 alone
        assertFalse(Rules.showContact(keyInUse = true, refusal = false, sinceMs = 0L, nowMs = day)) // a wrong PIN
        assertFalse(Rules.showContact(keyInUse = true, refusal = true, sinceMs = 0L, nowMs = day - 1))
    }
}
