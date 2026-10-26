package com.vettid.core.data.vault

import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.altchan.UnlockSecret
import com.vettid.core.keystore.KeyLevel
import com.vettid.core.keystore.KeystoreException
import com.vettid.core.keystore.PhoneKeyHandle
import com.vettid.core.vault.ChangeTypes
import com.vettid.core.vault.VaultOpException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The phone key's secrets per epoch (VAULT-MESSAGING 0.33.0 §11.14.1, vettid-vault `unlock.json`'s phone vector). */
class PhoneSecretsTest {
    private class SoftKey(private val level: KeyLevel = KeyLevel.TEE) : PhoneKeyHandle {
        var key: ByteArray? = null

        override fun create(): KeyLevel {
            if (level == KeyLevel.SOFTWARE) throw KeystoreException("phone key in software")
            key = ByteArray(32) { 0x44 }
            return level
        }

        override fun exists() = key != null

        override fun delete() {
            key = null
        }

        override fun hmac(message: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key!!, "HmacSHA256")) }.doFinal(message)
    }

    private val keys = mutableMapOf<String, SoftKey>()
    private val secrets = PhoneSecrets { keys.getOrPut(it) { SoftKey() } }

    @Test
    fun epochOneAndTheNextEpochMatchTheVector() {
        val s = secrets.create("test-vault-0001")
        assertEquals(PhoneSecretState("test-vault-0001", 1), s)
        assertArrayEquals(Bytes.unhex("84ae0b66319d60524f6dcffa6fa3c540669caa0f13a2b1cc01c49305d4af7836"), secrets.secret(s))
        assertArrayEquals(Bytes.unhex("03bbaea3348d1fdb182a3474faf655f7f444c74be38ab84423ec21f3a2587f07"), secrets.secret(s, ahead = 1))
        assertArrayEquals(secrets.secret(s, ahead = 1), secrets.secret(s.copy(epoch = 2)))
    }

    @Test
    fun aMissingKeyIsThePhoneNoLongerHoldingTheKeys() {
        val s = secrets.create("v1")
        secrets.delete(s)
        assertEquals(FailureKind.DEVICE_SECRET, assertThrows(VaultFailure::class.java) { secrets.secret(s) }.kind)
        assertEquals(FailureKind.DEVICE_SECRET, assertThrows(VaultFailure::class.java) { secrets.secret(PhoneSecretState()) }.kind)
        assertFalse(PhoneSecretState().inUse)
        assertTrue(s.inUse)
    }

    @Test
    fun aKeyInSoftwareIsRefused() {
        val soft = PhoneSecrets { SoftKey(KeyLevel.SOFTWARE) }
        assertEquals(FailureKind.HARDWARE_KEYS, assertThrows(VaultFailure::class.java) { soft.create("v1") }.kind)
    }

    /** A retry of an enrollment or a transfer reuses the key the vault may already hold the secret of. */
    @Test
    fun aRetryReusesTheKeyOfEpochOne() {
        val first = secrets.create("v1")
        val before = secrets.secret(first)
        val again = secrets.reuseOrCreate(first, "v1")
        assertEquals(first, again)
        assertArrayEquals(before, secrets.secret(again))
        // Another vault, a later epoch, or a key that is gone: a new key.
        assertEquals(PhoneSecretState("v2", 1), secrets.reuseOrCreate(first, "v2"))
        val k = keys.getValue("v1")
        val later = secrets.reuseOrCreate(first.copy(epoch = 2), "v1")
        assertEquals(PhoneSecretState("v1", 1), later)
        k.delete()
        assertTrue(secrets.reuseOrCreate(later, "v1").inUse)
        assertTrue(k.exists())
        // Nothing yet: a new key.
        assertEquals(PhoneSecretState("v3", 1), secrets.reuseOrCreate(PhoneSecretState(), "v3"))
    }

    /** Only answers the vault gives after the secret matched move the phone to the next epoch. */
    @Test
    fun theNextEpochIsKeptOnlyWhenTheSecretWasAccepted() {
        assertTrue(UnlockEpoch.confirmsNext(ok = true, code = null))
        assertTrue(UnlockEpoch.confirmsNext(ok = false, code = "bad_pin"))
        assertTrue(UnlockEpoch.confirmsNext(ok = false, code = "transfer_incomplete"))
        for (c in listOf("device_secret", "backoff", "manifest", "retry", "attestation", "recovery_pending", null)) {
            assertFalse(c ?: "null", UnlockEpoch.confirmsNext(ok = false, code = c))
        }
    }

    /** The old phone's 7 days after a transfer it approved. */
    @Test
    fun aKeptTransferEndsAfterSevenDays() {
        val day = 24L * 60 * 60 * 1000
        val at = 1_000_000_000_000L
        assertEquals(KeptTransfer.Decision.Keep(7 * day), KeptTransfer.decide(at, at))
        assertEquals(KeptTransfer.Decision.Keep(day), KeptTransfer.decide(at, at + 6 * day))
        assertEquals(KeptTransfer.Decision.Erase, KeptTransfer.decide(at, at + 7 * day))
        assertEquals(KeptTransfer.Decision.Erase, KeptTransfer.decide(at, at + 30 * day))
        // A clock set back before the approval never gives more than 7 days from now.
        assertEquals(KeptTransfer.Decision.Keep(7 * day), KeptTransfer.decide(at, at - 3 * day))
    }

    /** §11.14.1: only the trusted execution environment and StrongBox hold a phone key. */
    @Test
    fun onlySecureHardwareHoldsThePhoneKey() {
        assertTrue(com.vettid.core.keystore.PhoneKey.holdsPhoneKey(KeyLevel.TEE))
        assertTrue(com.vettid.core.keystore.PhoneKey.holdsPhoneKey(KeyLevel.STRONG_BOX))
        assertFalse(com.vettid.core.keystore.PhoneKey.holdsPhoneKey(KeyLevel.SOFTWARE))
        assertFalse(com.vettid.core.keystore.PhoneKey.holdsPhoneKey(KeyLevel.UNKNOWN))
    }

    /** A clock behind the approval: the 7 days count from when that is noticed, and that time is kept. */
    @Test
    fun aClockBehindTheApprovalRestartsTheSevenDaysOnce() {
        val day = 24L * 60 * 60 * 1000
        val at = 1_000_000_000_000L
        assertEquals(at, KeptTransfer.start(at, at + day))
        val noticed = at - 3 * day
        assertEquals(noticed, KeptTransfer.start(at, noticed))
        // Later checks with the clock still behind the first approval count from the stored, noticed time.
        assertEquals(KeptTransfer.Decision.Keep(6 * day), KeptTransfer.decide(KeptTransfer.start(noticed, noticed + day), noticed + day))
        assertEquals(KeptTransfer.Decision.Erase, KeptTransfer.decide(KeptTransfer.start(noticed, noticed + 7 * day), noticed + 7 * day))
    }

    /** Only an approval that got no answer may have reached the vault; any other failure removes the stored time. */
    @Test
    fun onlyAnUnansweredApprovalKeepsItsStoredTime() {
        assertTrue(KeptTransfer.mayHaveReached(FailureKind.NO_RESPONSE))
        for (k in listOf(FailureKind.BAD_PIN, FailureKind.BAD_PASSWORD, FailureKind.BUSY, FailureKind.NETWORK, FailureKind.OTHER)) {
            assertFalse(k.name, KeptTransfer.mayHaveReached(k))
        }
    }

    /** "Finish the move" ends only with an unlock the vault answered ok and whose result this phone took. */
    @Test
    fun onlyAnUnlockThatOpenedTheVaultFinishesTheMove() {
        assertTrue(FinishMoveProof.provedBy(resultOk = true, overtaken = false))
        assertFalse(FinishMoveProof.provedBy(resultOk = true, overtaken = true))
        assertFalse(FinishMoveProof.provedBy(resultOk = false, overtaken = false))
    }

    /** Release R: `exists` on credential.create fetches the credential the vault holds; before release R it stays an error. */
    @Test
    fun anExistingCredentialIsFetchedOnlyForReleaseR() {
        assertTrue(CredentialCreate.fetchInstead(releaseR = true, code = "exists"))
        assertFalse(CredentialCreate.fetchInstead(releaseR = false, code = "exists"))
        assertFalse(CredentialCreate.fetchInstead(releaseR = true, code = "weak_password"))
    }

    /**
     * The check at the end of the 7 days (owner decision 2026-10-23): kept only when the vault's device.list over this
     * phone's session lists it as the app; a definite list without it erases; no answer, a failure or an unclear answer
     * decides nothing (the check runs again later).
     */
    @Test
    fun theEndOfKeepCheckErasesOnlyOnADefiniteAnswer() {
        fun list(vararg d: Pair<String, String>) = kotlinx.serialization.json.Json.parseToJsonElement(
            d.joinToString(",", "{\"devices\":[", "]}") { (id, kind) -> "{\"id\":\"$id\",\"kind\":\"$kind\",\"name\":\"x\"}" },
        ) as kotlinx.serialization.json.JsonObject
        // Confirmed: kept.
        assertEquals(KeptTransfer.Verdict.STILL_THE_APP, KeptTransfer.verdict(list("dev-old" to "app", "desk-1" to "desktop"), "dev-old"))
        // Definite: the vault's app is another phone.
        assertEquals(KeptTransfer.Verdict.NOT_THE_APP, KeptTransfer.verdict(list("dev-new" to "app", "desk-1" to "desktop"), "dev-old"))
        assertEquals(KeptTransfer.Verdict.NOT_THE_APP, KeptTransfer.verdict(list(), "dev-old"))
        // This phone's id listed, but not as the app: not definite.
        assertEquals(KeptTransfer.Verdict.UNKNOWN, KeptTransfer.verdict(list("dev-old" to "desktop"), "dev-old"))
        // No answer, a refusal, a failure, an answer without a list, or a phone without a device id: unknown.
        assertEquals(KeptTransfer.Verdict.UNKNOWN, KeptTransfer.verdict(null, "dev-old"))
        assertEquals(KeptTransfer.Verdict.UNKNOWN, KeptTransfer.verdict(list("dev-old" to "app"), null))
        assertEquals(KeptTransfer.Verdict.UNKNOWN, KeptTransfer.verdict(kotlinx.serialization.json.JsonObject(emptyMap()), "dev-old"))
    }

    /** §11.10.9: the path from the verified capabilities, and the mark that never lets a vault go back. */
    @Test
    fun theCapabilitiesChooseThePathAndAMarkedVaultNeverGoesBack() {
        // A vault whose release lists nothing: the earlier path, exactly as before.
        assertEquals(ReleasePath.Path.EARLIER, ReleasePath.choose(emptyList(), releaseRKnown = false))
        assertEquals(ReleasePath.Path.EARLIER, ReleasePath.choose(listOf("attachments"), releaseRKnown = false))
        // recovery_r: release R's requests.
        assertEquals(ReleasePath.Path.R, ReleasePath.choose(listOf("recovery_r"), releaseRKnown = false))
        assertEquals(ReleasePath.Path.R, ReleasePath.choose(listOf("recovery_offered", "recovery_r"), releaseRKnown = true))
        // Seen release R, then an instance without it: refused like a rollback (nothing is built or sent).
        val e = assertThrows(VaultFailure::class.java) { ReleasePath.choose(listOf("attachments"), releaseRKnown = true) }
        assertEquals(FailureKind.CAPABILITY_ROLLBACK, e.kind)
        assertEquals(ReleasePath.CODE_CAPABILITY_ROLLBACK, e.code)
        assertThrows(VaultFailure::class.java) { ReleasePath.choose(emptyList(), releaseRKnown = true) }
    }

    /** Recovery is shown for a release before R (its earlier recovery), and from release R only with recovery_offered. */
    @Test
    fun recoveryIsOfferedOnlyWhenTheReleaseSaysSo() {
        assertTrue(ReleasePath.recoveryOffered(emptyList()))
        assertTrue(ReleasePath.recoveryOffered(listOf("recovery_offered", "recovery_r")))
        assertFalse(ReleasePath.recoveryOffered(listOf("attachments", "recovery_r")))
    }

    /**
     * The mark: set by an R path that makes the phone key (enrollment, a transfer to this phone, a register) or on a phone
     * that holds a phone secret; an unlock of a phone without one that meets an R instance never sets it.
     */
    @Test
    fun anUnlockWithoutAPhoneSecretNeverMarksTheVault() {
        val r = ReleasePath.Path.R
        assertFalse(ReleasePath.marks(wasMarked = false, path = r, makesPhoneKey = false, holdsPhoneSecret = false))
        assertTrue(ReleasePath.marks(wasMarked = false, path = r, makesPhoneKey = true, holdsPhoneSecret = false))
        assertTrue(ReleasePath.marks(wasMarked = false, path = r, makesPhoneKey = false, holdsPhoneSecret = true))
        assertFalse(ReleasePath.marks(wasMarked = false, path = ReleasePath.Path.EARLIER, makesPhoneKey = true, holdsPhoneSecret = true))
        assertTrue(ReleasePath.marks(wasMarked = true, path = ReleasePath.Path.EARLIER, makesPhoneKey = false, holdsPhoneSecret = false))
        // So after such an unlock the phone still reaches its own earlier vault.
        assertEquals(ReleasePath.Path.EARLIER, ReleasePath.choose(emptyList(), releaseRKnown = false))
    }

    /**
     * VAULT-MESSAGING 0.44.0 §11.14.9 step 5, the two-step unlock: while a PIN change is unresolved the PIN typed first
     * (the previous one) goes with the current record only; its `device_secret` (decided before any PIN is tested) asks
     * for the new PIN instead of trying the candidate with the same PIN; the new PIN then goes with the candidate only.
     * Without an unresolved change the unlock is as before (current, then the next epoch with the same PIN).
     */
    @Test
    fun anUnresolvedPinChangeUnlocksInTwoSteps() {
        val secret = "device_secret"
        // First step: the current record.
        assertFalse(UnlockEpoch.nextOnly(pinChangeUnresolved = true, newPinAsked = false))
        assertEquals(UnlockEpoch.Next.ASK_NEW_PIN, UnlockEpoch.afterCurrent(true, ok = false, code = secret, pinChangeUnresolved = true))
        // `ok` with the previous PIN, or any other answer: it stands (the check after the open decides the change).
        assertEquals(UnlockEpoch.Next.DONE, UnlockEpoch.afterCurrent(true, ok = true, code = null, pinChangeUnresolved = true))
        for (c in listOf("bad_pin", "backoff", "retry", "transfer_incomplete")) {
            assertEquals(c, UnlockEpoch.Next.DONE, UnlockEpoch.afterCurrent(true, ok = false, code = c, pinChangeUnresolved = true))
        }
        // Second step: the new PIN, with the candidate only.
        assertTrue(UnlockEpoch.nextOnly(pinChangeUnresolved = true, newPinAsked = true))
        // Nothing unresolved: as before.
        assertFalse(UnlockEpoch.nextOnly(pinChangeUnresolved = false, newPinAsked = true))
        assertEquals(UnlockEpoch.Next.TRY_NEXT, UnlockEpoch.afterCurrent(true, ok = false, code = secret, pinChangeUnresolved = false))
        assertEquals(UnlockEpoch.Next.DONE, UnlockEpoch.afterCurrent(false, ok = false, code = secret, pinChangeUnresolved = true))
    }

    /** One sealed unlock as [UnlockEpoch.run] sends it: the epoch offset, and the answer it gets. */
    private class Sends(vararg answers: UnlockEpoch.Answer) {
        val sent = mutableListOf<Int>()
        val queue = ArrayDeque(answers.toList())
        var asked: Boolean? = null
        var confirmed = 0
        var fail: java.io.IOException? = null

        var currentOk = 0

        suspend fun run(unresolved: Boolean, newPinAsked: Boolean, renew: Boolean = false): UnlockEpoch.Answer = UnlockEpoch.run(
            pinChangeUnresolved = unresolved,
            newPinAsked = newPinAsked,
            send = { ahead ->
                fail?.let { throw it }
                sent += ahead
                val a = queue.removeFirst()
                a to a
            },
            setNewPinAsked = { asked = it },
            confirmNext = { confirmed++ },
            previousPinCandidates = if (renew) listOf(1) else emptyList(),
            currentOk = { currentOk++ },
        )
    }

    private fun answer(code: String?) = UnlockEpoch.Answer(ok = code == null, code = code, releaseR = true)

    /**
     * The real sequence of the unlock (VAULT-MESSAGING 0.44.0 §11.14.9 step 5; [UnlockEpoch.run], used by the vault
     * manager): the previous PIN goes with the current record only, its `device_secret` asks for the new PIN without
     * sending anything more; the new PIN goes with the candidate only, and an answer after the secret check confirms
     * it. Without an unresolved change: current, then the candidate with the same PIN. A failure that never reached the
     * vault keeps the step.
     */
    @Test
    fun theUnlockSendsEachPinWithItsOwnRecord() = kotlinx.coroutines.runBlocking {
        val first = Sends(answer("device_secret"))
        first.run(unresolved = true, newPinAsked = false)
        assertEquals(listOf(0), first.sent)
        assertEquals(true, first.asked)
        assertEquals(0, first.confirmed)
        val second = Sends(answer("bad_pin"))
        second.run(unresolved = true, newPinAsked = true)
        assertEquals("the new PIN, with the candidate only", listOf(1), second.sent)
        assertEquals(false, second.asked)
        assertEquals(1, second.confirmed)
        val ok = Sends(answer(null))
        ok.run(unresolved = true, newPinAsked = false)
        assertEquals(listOf(0), ok.sent)
        assertEquals(null, ok.asked)
        assertEquals("settles an uncertain PIN, not the change", 1, ok.currentOk)
        // (0.45.0) An unanswered renew: its candidate is tried with the previous PIN before the new PIN is asked for.
        val renewHeld = Sends(answer("device_secret"), answer(null))
        renewHeld.run(unresolved = true, newPinAsked = false, renew = true)
        assertEquals(listOf(0, 1), renewHeld.sent)
        assertEquals(1, renewHeld.confirmed)
        assertEquals("no new PIN asked", null, renewHeld.asked)
        val renewNot = Sends(answer("device_secret"), answer("device_secret"))
        renewNot.run(unresolved = true, newPinAsked = false, renew = true)
        assertEquals(listOf(0, 1), renewNot.sent)
        assertEquals(true, renewNot.asked)
        val renewBusy = Sends(answer("device_secret"), answer("retry"))
        renewBusy.run(unresolved = true, newPinAsked = false, renew = true)
        assertEquals("decided before the secret check: proves nothing", null, renewBusy.asked)
        assertEquals(0, renewBusy.confirmed)
        val net = Sends().apply { fail = java.io.IOException("network") }
        assertThrows(java.io.IOException::class.java) { kotlinx.coroutines.runBlocking { net.run(unresolved = true, newPinAsked = true) } }
        assertEquals("the step stays", null, net.asked)
        val before = Sends(answer("device_secret"), answer(null))
        before.run(unresolved = false, newPinAsked = false)
        assertEquals(listOf(0, 1), before.sent)
        assertEquals(1, before.confirmed)
        // (H1) Outside the unlock screen nothing is sent while a PIN change is unresolved.
        assertFalse(UnlockEpoch.allowed(pinChangeUnresolved = true, twoStepScreen = false))
        assertTrue(UnlockEpoch.allowed(pinChangeUnresolved = true, twoStepScreen = true))
        assertTrue(UnlockEpoch.allowed(pinChangeUnresolved = false, twoStepScreen = false))
    }

    /**
     * A refused or ended change never touches the phone key: the current record's secret still computes after the
     * vault's `forbidden` to the resend, a cleared or ended change, and a renew that did not apply. (A guard: a design
     * that gave a candidate its own key would have to keep the current one through all of these.)
     */
    @Test
    fun theUnlockSecretStillComputesAfterARefusedOrEndedChange() = kotlinx.coroutines.runBlocking {
        val pinChange = UnresolvedChange(ChangeTypes.PIN_CHANGE, "01JPIN", 7, 1_000, fromEpoch = 3)
        val renew = UnconfirmedRenew("01JRENEW", 6, 1_000, 3)
        val account = LocalAccount(
            userGuid = "u1",
            vaultId = "v1",
            setupComplete = true,
            phoneKeyVault = "kv",
            phoneEpoch = 3,
            unresolvedChange = pinChange,
            unconfirmedRenew = renew,
        )
        val ends: List<Pair<String, suspend (AccountRecord) -> Unit>> = listOf(
            "forbidden to the resend" to { record ->
                val resolver = ChangeResolver(
                    this,
                    object : ChangeResolver.Ops {
                        override fun marker() = record.value.unresolvedChange
                        override suspend fun resend(m: UnresolvedChange): ChangeResolver.Answer? =
                            throw VaultOpException(m.type, "forbidden", "not the holder")
                        override suspend fun version(): ChangeResolver.Version = error("not asked")
                        override suspend fun fetch() = error("not asked")
                        override fun confirm(m: UnresolvedChange): Boolean = error("not confirmed")
                        override fun clear(m: UnresolvedChange) {
                            record.clearChange(m)
                        }
                        override fun end(m: UnresolvedChange) {
                            record.endChange(m)
                        }
                    },
                )
                assertEquals(ChangeCheck.Moved(ChangeKind.PIN), resolver.decide())
            },
            "a cleared change" to { record -> assertTrue(record.clearChange(pinChange)) },
            "an ended change" to { record -> assertTrue(record.endChange(pinChange)) },
            "a renew that did not apply" to { record -> assertTrue(record.decideRenew(renew.innerId, applied = false)) },
        )
        for ((name, end) in ends) {
            val key = SoftKey().apply { create() }
            val phoneSecrets = PhoneSecrets { key }
            val record = AccountRecord(account, readable = true, persist = {})
            end(record)
            assertTrue(name, key.exists())
            assertEquals(name, PhoneSecretState("kv", 3), record.value.phone)
            assertArrayEquals(name, UnlockSecret.compute("kv", 3, key::hmac), phoneSecrets.secret(record.value.phone))
        }
    }
}
