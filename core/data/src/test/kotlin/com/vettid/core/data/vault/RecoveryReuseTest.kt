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

    @Test
    fun aListedStatusIsTheLostOkUnlockAndNeverAdoption() {
        val (d, seen) = Rules.afterRead(Rules.Read.Listed(registered = false), seenAtMs = 5L, nowMs = 10L)
        assertEquals(Rules.Decision.LostOk, d)
        assertEquals(null, seen) // the count of ended reads starts again
        // Still registered: the phone opens again.
        assertEquals(Rules.Decision.OpenAgain, Rules.afterRead(Rules.Read.Listed(registered = true), null, 10L).first)
    }

    @Test
    fun aNewRecoveryNeedsTwoEndedReadsAtLeastFiveMinutesApart() {
        val (d1, s1) = Rules.afterRead(Rules.Read.Ended, null, 1_000L)
        assertEquals(Rules.Decision.Wait(1_000L + 5 * min), d1)
        assertEquals(1_000L, s1)
        // Too soon: still waiting, the first read is kept.
        val (d2, s2) = Rules.afterRead(Rules.Read.Ended, s1, 1_000L + 4 * min + 59_000L)
        assertEquals(Rules.Decision.Wait(1_000L + 5 * min), d2)
        assertEquals(1_000L, s2)
        // Exactly 5 minutes: the second read.
        assertEquals(Rules.Decision.StartNew, Rules.afterRead(Rules.Read.Ended, s1, 1_000L + 5 * min).first)
        assertEquals(Rules.Decision.StartNew, Rules.afterRead(Rules.Read.Ended, s1, 1_000L + 6 * 60 * min).first)
    }

    @Test
    fun theClockIsChecked() {
        // A phone clock set back past the first read starts the count again; it never counts as five minutes.
        val (d, s) = Rules.afterRead(Rules.Read.Ended, seenAtMs = 10 * day, nowMs = 5 * day)
        assertEquals(Rules.Decision.Wait(5 * day + 5 * min), d)
        assertEquals(5 * day, s)
        // An unknown read (a network fault, a 401 for the clock) concludes nothing and keeps the first read.
        assertEquals(Rules.Decision.Unknown to 7L, Rules.afterRead(Rules.Read.Unknown, 7L, 10 * day))
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
            earlierSlot = 0, earlierRecoveryId = "01EARLIER", earlierOpenRid = "01RID", newSlot = 1, newRecoveryId = "01NEW",
            newRegistered = true, endedSeenAtMs = 5L, startNew = true, lostOkDue = false, earlierPolled = true, refusedSinceMs = 9L,
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
}
