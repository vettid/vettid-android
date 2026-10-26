package com.vettid.core.data.vault

import com.vettid.core.altchan.Attester
import com.vettid.core.crypto.altchan.DeviceAssertion
import com.vettid.core.crypto.altchan.DeviceAttest
import com.vettid.core.keystore.AndroidKeys
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException

/**
 * (ANDROID-PLAN §4 step 5, review 2026-10-26) The device attestation keys per identity slot: a new key per register POST,
 * kept until that POST is settled not registered; opens and unlocks sign with the identity's own keys, newest first, and
 * fall back on `attestation`; the key of the builds before slots is taken over, never deleted.
 */
class AttestationKeysTest {
    /** The Android Keystore, by alias; [failing] cannot be deleted. */
    private class Keystore : AttestationKeys.Keys {
        val keys = linkedSetOf<String>()
        val failing = mutableSetOf<String>()

        /** Keys that are listed and present but cannot sign (a Keystore fault). */
        val broken = mutableSetOf<String>()

        override fun exists(alias: String) = alias in keys

        override fun delete(alias: String) {
            if (alias in failing) throw KeyStoreException("busy")
            keys.remove(alias)
        }
    }

    private class Store : AttestationKeys.Store {
        val m = mutableMapOf<Int, String>()

        override fun read(slot: Int) = m[slot]

        override fun write(slot: Int, value: String?) {
            if (value == null) m.remove(slot) else m[slot] = value
        }
    }

    /** A Keystore attester under one alias: an attestation generates the key; a signature names the key that made it. */
    private class KeyAttester(val alias: String, val ks: Keystore, val signed: MutableList<String>) : Attester {
        override fun attest(challenge: ByteArray): DeviceAttest {
            ks.keys += alias
            return DeviceAttest.android(listOf(alias.toByteArray()))
        }

        override fun assert(message: ByteArray): DeviceAssertion {
            check(alias in ks.keys) { "no attestation key" }
            check(alias !in ks.broken) { "keystore fault" }
            signed += alias
            return DeviceAssertion.android(alias.toByteArray())
        }
    }

    /** The vault's side: the attested key of the registration it recorded, and how it answers an open or unlock. */
    private class Vault {
        var held: String? = null
        val asked = mutableListOf<String?>()

        fun answer(alias: String?): String {
            asked += alias
            return if (alias != null && alias == held) "ok" else "attestation"
        }
    }

    private class Phone(private var slotInUse: Int = 0) {
        val ks = Keystore()
        val store = Store()
        val signed = mutableListOf<String>()
        var ring = newRing()

        /** As [IdentitySlots.active]: the key of a build before slots is taken over before the slot in use changes. */
        var active: Int
            get() = slotInUse
            set(v) {
                ring.migrate()
                slotInUse = v
            }

        /** The slots that hold an identity, and the recovery_id each device state names while recovering. */
        val existing = mutableSetOf<Int>()
        val recovering = mutableMapOf<Int, String>()
        var now = 1_000_000L

        fun newRing() = AttestationKeys(store, ks, { slotInUse }, { it in existing }, { recovering[it] }, { now })

        /** A process restart: the lists and keys stay, the ring is made anew. */
        fun restart() {
            ring = newRing()
        }

        fun attester(slot: Int, recoveryId: String? = null, signWith: String? = null) =
            SlotAttester(ring, slot, recoveryId, signWith) { KeyAttester(it, ks, signed) }

        /** One register POST of [slot] for [recoveryId]: a new key, which [posts] records. */
        fun buildRegister(slot: Int, recoveryId: String, posts: RegisterKeys): String {
            val a = attester(slot, recoveryId)
            a.attest(ByteArray(32))
            posts.built(a.made)
            return a.made!!
        }

        /**
         * An unlock as the app sends it (VaultManager.unlockWithSecret): newest first while `attestation`; outside a
         * recovery the key answered `ok` stays and the identity's other keys that served no registration go.
         */
        suspend fun unlock(slot: Int, vault: Vault, recovering: Boolean): String = ring.tryKeys(
            slot,
            attempt = { alias ->
                attester(slot, signWith = alias).assert(ByteArray(32))
                vault.answer(signed.last())
            },
            code = { it },
            ok = { it == "ok" },
            onOk = { alias -> if (!recovering) ring.keepAfterUnlock(slot, alias) },
        )

        /**
         * The handshake as the app sends it (VaultManager.pairWithEach): each `recovery_id` newest first until one
         * completes ([completes]), then that registration's keys are kept. The ctx that completed, or null.
         */
        fun pair(slot: Int, last: String?, completes: (String?) -> Boolean, tried: MutableList<String?>): String? {
            for (ctx in ring.pairingContexts(slot, last)) {
                tried += ctx
                if (completes(ctx)) {
                    ring.keepRegistration(slot, ctx)
                    return ctx
                }
            }
            return null
        }

        /** An open (or a lost-ok unlock) of [slot] as the app sends it: its keys, newest first, while `attestation`. */
        suspend fun open(slot: Int, vault: Vault): String = ring.tryKeys(
            slot,
            attempt = { alias ->
                attester(slot, signWith = alias).assert(ByteArray(32))
                vault.answer(signed.last())
            },
            code = { it },
            ok = { it == "ok" },
        )
    }

    private fun slotAlias(slot: Int, i: Int) = AndroidKeys.deviceAttestationAlias(slot, i)

    @Test
    fun caseAAnExpiredRegisterAndARepostAnsweredUsedOpenWithTheFirstKey() = runTest {
        val p = Phone(1)
        val vault = Vault()
        val posts = RegisterKeys()
        // POST 1: 202, the vault records its key; the slot shows `expired`, so the flow posts again.
        val a1 = p.buildRegister(1, "R1", posts)
        posts.accepted()
        vault.held = a1
        // The re-post: a new key (a Keystore key's challenge is fixed at generation), 202, answered `used`.
        val a2 = p.buildRegister(1, "R1", posts)
        posts.accepted()
        assertTrue("the first key is never replaced", a1 in p.ks.keys)
        // `used` settles the re-post only: its key goes, the first stays (maybe registered, confirmed by status with K).
        p.ring.remove(1, posts.notRegistered(lastRefused = true))
        assertEquals(listOf(a1), p.ring.candidates(1).map { it.alias })
        assertFalse(a2 in p.ks.keys)
        // The open signs with A1 and is not refused.
        assertEquals("ok", p.open(1, vault))
        assertEquals(listOf<String?>(a1), vault.asked)
    }

    @Test
    fun caseAWhenTheRepostsAnswerWasLostTheOpenFallsBackToTheFirstKey() = runTest {
        val p = Phone(1)
        val vault = Vault()
        val posts = RegisterKeys()
        val a1 = p.buildRegister(1, "R1", posts)
        posts.accepted()
        vault.held = a1
        val a2 = p.buildRegister(1, "R1", posts)
        posts.accepted()
        // No answer to the re-post: both may be held, both stay.
        p.ring.remove(1, posts.notRegistered(lastRefused = false))
        assertEquals(listOf(a2, a1), p.ring.candidates(1).map { it.alias })
        // Newest first: `attestation`, then the first key's `ok`. Both stay until the handshake (a recovery).
        assertEquals("ok", p.open(1, vault))
        assertEquals(listOf<String?>(a2, a1), vault.asked)
        assertTrue(a1 in p.ks.keys && a2 in p.ks.keys)
        // After a restart the same order finds it again, each key once.
        p.restart()
        assertEquals("ok", p.open(1, vault))
        assertEquals(listOf<String?>(a2, a1, a2, a1), vault.asked)
    }

    @Test
    fun caseBNsRegisterThatGotNo202LeavesEsKeyInPlace() = runTest {
        val p = Phone(0)
        // E: enrolled by a build before slots (its one key, which the vault holds).
        p.ks.keys += AndroidKeys.ALIAS_DEVICE_ATTESTATION
        val vault = Vault().apply { held = AndroidKeys.ALIAS_DEVICE_ATTESTATION }
        // N is made in slot 1 and becomes the identity in use for its register; the POST gets no 202 (`503`).
        p.active = 1
        val posts = RegisterKeys()
        val n1 = p.buildRegister(1, "R2", posts)
        posts.answered(503, "release_starting")
        p.ring.remove(1, posts.notRegistered(lastRefused = false))
        assertFalse(n1 in p.ks.keys)
        // Back to E: its open and its lost-ok unlock sign with E's key.
        p.active = 0
        assertEquals(AndroidKeys.ALIAS_DEVICE_ATTESTATION, p.ring.current(0)?.alias)
        assertEquals("ok", p.open(0, vault))
        assertEquals(listOf<String?>(AndroidKeys.ALIAS_DEVICE_ATTESTATION), vault.asked)
        assertTrue(AndroidKeys.ALIAS_DEVICE_ATTESTATION in p.ks.keys)
    }

    @Test
    fun caseBEvenAnNKeyThatStaysNeverTouchesE() = runTest {
        val p = Phone(0)
        val e = Vault()
        val ePosts = RegisterKeys()
        val e1 = p.buildRegister(0, "R1", ePosts)
        ePosts.accepted()
        e.held = e1
        val nPosts = RegisterKeys()
        val n1 = p.buildRegister(1, "R2", nPosts)
        // N's POST got no answer at all: its key stays (it may have been queued), in N's slot only.
        p.ring.remove(1, nPosts.notRegistered(lastRefused = false))
        assertTrue(n1 in p.ks.keys)
        assertEquals(listOf(e1), p.ring.candidates(0).map { it.alias })
        assertEquals("ok", p.open(0, e))
        p.attester(0).assert(ByteArray(32))
        assertEquals(e1, p.signed.last())
    }

    @Test
    fun theKeyOfTheBuildsBeforeSlotsIsTakenOverNotDeleted() {
        val p = Phone(0)
        p.ks.keys += AndroidKeys.ALIAS_DEVICE_ATTESTATION
        // An enrolled phone keeps unlocking with its current key.
        p.attester(0).assert(ByteArray(32))
        assertEquals(AndroidKeys.ALIAS_DEVICE_ATTESTATION, p.signed.last())
        assertEquals(listOf(AttestationKeys.Entry(AndroidKeys.ALIAS_DEVICE_ATTESTATION, null)), p.ring.candidates(0))
        assertTrue(p.ring.candidates(1).isEmpty())
        // A new attestation is a new alias: the earlier key stays until it is settled.
        val a = p.attester(0, "R1")
        a.attest(ByteArray(32))
        assertEquals(slotAlias(0, 0), a.made)
        assertEquals(listOf(slotAlias(0, 0), AndroidKeys.ALIAS_DEVICE_ATTESTATION), p.ring.candidates(0).map { it.alias })
        // Idempotent: a restart lists it once, in the same slot, even with another slot in use by then.
        p.active = 1
        p.restart()
        assertEquals(listOf(slotAlias(0, 0), AndroidKeys.ALIAS_DEVICE_ATTESTATION), p.ring.candidates(0).map { it.alias })
        assertTrue(p.ring.candidates(1).isEmpty())
    }

    @Test
    fun theKeyOfTheBuildsBeforeSlotsGoesToTheSlotInUse() {
        val p = Phone(1)
        p.ks.keys += AndroidKeys.ALIAS_DEVICE_ATTESTATION
        assertEquals(AndroidKeys.ALIAS_DEVICE_ATTESTATION, p.ring.current(1)?.alias)
        assertNull(p.ring.current(0))
        // No key of a build before slots: nothing to take over.
        val fresh = Phone()
        assertNull(fresh.ring.current(0))
        assertNull(fresh.store.m[0])
        assertNull(fresh.store.m[1])
    }

    @Test
    fun theFallbackTriesEachKeyOnceNewestFirst() = runTest {
        val p = Phone()
        val keys = (0 until 3).map { p.attester(0, "R$it").also { a -> a.attest(ByteArray(32)) }.made!! }
        val vault = Vault().apply { held = keys[0] }
        assertEquals("ok", p.open(0, vault))
        assertEquals(keys.reversed(), vault.asked)
        // No key the vault holds: each is tried once, and the answer is the last `attestation`.
        val none = Vault()
        assertEquals("attestation", p.open(0, none))
        assertEquals(3, none.asked.size)
        assertEquals(keys.reversed(), none.asked)
        // Any other answer stops at once (a wrong PIN, a wait: the key is the vault's).
        var n = 0
        val r = p.ring.tryKeys(0, attempt = { n++; "bad_pin" }, code = { it }, ok = { false })
        assertEquals("bad_pin", r)
        assertEquals(1, n)
        // No key at all: one attempt, with none.
        val empty = Phone()
        val asked = mutableListOf<String?>()
        empty.ring.tryKeys(1, attempt = { asked += it; "attestation" }, code = { it }, ok = { false })
        assertEquals(listOf<String?>(null), asked)
    }

    @Test
    fun registerKeysGoOnlyWhenTheirPostIsSettledNotRegistered() {
        val p = Phone()
        val posts = RegisterKeys()
        // POST 1 posted again without its 202 (`instance_moved`, not known to be unforwarded), POST 2 `202` then `expired`,
        // POST 3 the last.
        val k1 = p.buildRegister(0, "R", posts)
        val k2 = p.buildRegister(0, "R", posts)
        posts.accepted()
        val k3 = p.buildRegister(0, "R", posts)
        assertTrue(posts.notRegistered(lastRefused = false).isEmpty())
        // The last POST answered `429`: never forwarded, its key goes; the others stay.
        posts.answered(429, "rate_limited")
        assertEquals(listOf(k3), posts.notRegistered(lastRefused = false))
        // After a 202 no answer settles a POST, but its own readable refusal does.
        val q = RegisterKeys()
        val j1 = p.buildRegister(0, "R", q)
        q.accepted()
        q.answered(503, "release_starting")
        assertTrue(q.notRegistered(lastRefused = false).isEmpty())
        assertEquals(listOf(j1), q.notRegistered(lastRefused = true))
        assertFalse(k1 in posts.notRegistered(lastRefused = true) || k2 in posts.notRegistered(lastRefused = true))
        assertTrue(RegisterKeys().notRegistered(lastRefused = true).isEmpty())
    }

    @Test
    fun onlyTheAnswersThatNeverForwardAPostSettleIt() {
        val notForwarded = listOf(
            429 to "rate_limited", 429 to null, 503 to "release_starting", 503 to "vault_unavailable", 401 to "unauthorized",
            409 to "recovery_not_available",
        )
        for ((status, code) in notForwarded) assertEquals("$status $code", RegisterKeys.End.NOT_FORWARDED, RegisterKeys.endOf(status, code))
        val kept = listOf(
            409 to "instance_moved", 401 to "clock_skew", 503 to "something_new", 503 to null, 502 to null, 500 to "internal",
            409 to "recovery_unavailable", null to null,
        )
        for ((status, code) in kept) assertEquals("$status $code", RegisterKeys.End.KEEP, RegisterKeys.endOf(status, code))
    }

    @Test
    fun upgradedMidRecoveryTheOldKeyIsTaggedAndSurvivesTheHandshake() = runTest {
        // An install of a build before slots, recovering for R1 with its one key (the vault's binding).
        val p = Phone(0)
        p.ks.keys += AndroidKeys.ALIAS_DEVICE_ATTESTATION
        p.existing += 0
        p.recovering[0] = "R1"
        assertEquals(listOf(AttestationKeys.Entry(AndroidKeys.ALIAS_DEVICE_ATTESTATION, "R1")), p.ring.candidates(0))
        // After the upgrade it registers again (R2) with a new key the vault never recorded.
        val k2 = p.attester(0, "R2").also { it.attest(ByteArray(32)) }.made!!
        val vault = Vault().apply { held = AndroidKeys.ALIAS_DEVICE_ATTESTATION }
        // The open falls back to the old key, answered ok, which marks it.
        assertEquals("ok", p.ring.tryKeys(
            0,
            attempt = { alias -> p.attester(0, signWith = alias).assert(ByteArray(32)); vault.answer(p.signed.last()) },
            code = { it },
            ok = { it == "ok" },
            onOk = { p.ring.markOpened(0, it) },
        ))
        // hs.init with R2 completes: R2's key stays, and the old key too (the ok open's).
        p.ring.keepRegistration(0, "R2")
        assertEquals(setOf(k2, AndroidKeys.ALIAS_DEVICE_ATTESTATION), p.ring.candidates(0).map { it.alias }.toSet())
        // Untagged (its registration not known): it is not deleted at the handshake either.
        val q = Phone(0)
        q.ks.keys += AndroidKeys.ALIAS_DEVICE_ATTESTATION
        q.existing += 0
        val j2 = q.attester(0, "R2").also { it.attest(ByteArray(32)) }.made!!
        q.ring.keepRegistration(0, "R2")
        assertEquals(setOf(j2, AndroidKeys.ALIAS_DEVICE_ATTESTATION), q.ring.candidates(0).map { it.alias }.toSet())
    }

    @Test
    fun upgradedDuringAReuseTheOldKeyIsListedByBothIdentities() {
        // A reuse in progress: E in slot 0 (R1), N in slot 1 (R2); the one key may be either's.
        val p = Phone(1)
        p.ks.keys += AndroidKeys.ALIAS_DEVICE_ATTESTATION
        p.existing += setOf(0, 1)
        p.recovering[0] = "R1"
        p.recovering[1] = "R2"
        assertEquals(AttestationKeys.Entry(AndroidKeys.ALIAS_DEVICE_ATTESTATION, "R1"), p.ring.current(0))
        assertEquals(AttestationKeys.Entry(AndroidKeys.ALIAS_DEVICE_ATTESTATION, "R2"), p.ring.current(1))
        // Once only: a restart (the marker) lists nothing again, even after a slot dropped it.
        p.ring.clear(0)
        p.restart()
        assertNull(p.ring.current(0))
        // E's slot cleared after N paired: the key stays in the Keystore, N still signs with it.
        assertTrue(AndroidKeys.ALIAS_DEVICE_ATTESTATION in p.ks.keys)
        assertEquals(AndroidKeys.ALIAS_DEVICE_ATTESTATION, p.ring.current(1)?.alias)
        // Removed from the last slot that lists it: deleted.
        p.ring.remove(1, listOf(AndroidKeys.ALIAS_DEVICE_ATTESTATION))
        assertFalse(AndroidKeys.ALIAS_DEVICE_ATTESTATION in p.ks.keys)
    }

    @Test
    fun theKeyOfAnOkOpenSurvivesKeepRegistration() {
        val p = Phone(1)
        val a1 = p.attester(1, "R1").also { it.attest(ByteArray(32)) }.made!!
        val a3 = p.attester(1, "R3").also { it.attest(ByteArray(32)) }.made!!
        val a2 = p.attester(1, "R2").also { it.attest(ByteArray(32)) }.made!!
        p.ring.markOpened(1, a1)
        p.restart()
        p.ring.keepRegistration(1, "R2")
        assertEquals(listOf(a2, a1), p.ring.candidates(1).map { it.alias })
        assertFalse(a3 in p.ks.keys)
    }

    @Test
    fun statusWithKSettlesOnlyOldUnopenedKeysOfThatRecovery() {
        val p = Phone(1)
        val old = p.attester(1, "R").also { it.attest(ByteArray(32)) }.made!!
        val opened = p.attester(1, "R").also { it.attest(ByteArray(32)) }.made!!
        p.ring.markOpened(1, opened)
        val other = p.attester(1, "Q").also { it.attest(ByteArray(32)) }.made!!
        p.now += 31 * 60 * 1000L
        val recent = p.attester(1, "R").also { it.attest(ByteArray(32)) }.made!!
        p.ring.settleNotRegistered(1, "R", 30 * 60 * 1000L)
        assertEquals(setOf(opened, other, recent), p.ring.candidates(1).map { it.alias }.toSet())
        assertFalse(old in p.ks.keys)
    }

    @Test
    fun anUnlockOkOutsideARecoveryDropsItsRegistrationsOtherKeys() = runTest {
        val p = Phone(0)
        val r1 = p.attester(0, "R1").also { it.attest(ByteArray(32)) }.made!!
        val r1b = p.attester(0, "R1").also { it.attest(ByteArray(32)) }.made!!
        val q = p.attester(0, "Q").also { it.attest(ByteArray(32)) }.made!!
        val vault = Vault().apply { held = r1 }
        assertEquals("ok", p.unlock(0, vault, recovering = false))
        assertEquals(setOf(r1, q), p.ring.candidates(0).map { it.alias }.toSet())
        assertFalse(r1b in p.ks.keys)
    }

    @Test
    fun aKeyThatCannotSignIsSkipped() = runTest {
        val p = Phone(0)
        val good = p.attester(0, "R").also { it.attest(ByteArray(32)) }.made!!
        val bad = p.attester(0, "R").also { it.attest(ByteArray(32)) }.made!!
        p.ks.broken += bad
        val vault = Vault().apply { held = good }
        assertEquals("ok", p.open(0, vault))
        assertEquals(listOf<String?>(good), vault.asked)
        // Every key broken: the last failure is thrown, nothing answered.
        p.ks.broken += good
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { p.open(0, Vault()) } }
    }

    @Test
    fun aSlotIsClearedAloneAndAKeyTheKeystoreKeepsStaysListed() {
        val p = Phone()
        p.ks.keys += AndroidKeys.ALIAS_DEVICE_ATTESTATION
        val e1 = p.attester(0, "R1").also { it.attest(ByteArray(32)) }.made!!
        val n1 = p.attester(1, "R2").also { it.attest(ByteArray(32)) }.made!!
        val n2 = p.attester(1, "R3").also { it.attest(ByteArray(32)) }.made!!
        // An unlisted key of slot 1 (a crash between deleting and listing) goes too.
        p.ks.keys += slotAlias(1, 9)
        p.ring.clear(1)
        assertFalse(n1 in p.ks.keys || n2 in p.ks.keys || slotAlias(1, 9) in p.ks.keys)
        assertTrue(p.ring.candidates(1).isEmpty())
        assertEquals(listOf(e1, AndroidKeys.ALIAS_DEVICE_ATTESTATION), p.ring.candidates(0).map { it.alias })
        // A key the Keystore could not delete stays listed, to be deleted another time.
        p.ks.failing += e1
        p.ring.clear(0)
        assertEquals(listOf(e1), p.ring.candidates(0).map { it.alias })
        assertFalse(AndroidKeys.ALIAS_DEVICE_ATTESTATION in p.ks.keys)
        p.ks.failing.clear()
        p.ring.clear(0)
        assertTrue(p.ks.keys.isEmpty())
        assertNull(p.store.m[0])
    }

    @Test
    fun pairingKeepsTheKeyOfTheRegistrationWhoseRecoveryIdCompletedTheHandshake() = runTest {
        // The counter-case (review 2026-10-26): R1's open stored step 2; N registered R2 with A2; R2's open stored the state
        // but its header write failed. The header still binds A1, so A1's lost-ok unlock answers `ok`; the invitation is
        // R2's, so hs.init with ctx R2 completes, and the binding is then A2.
        val p = Phone(1)
        val vault = Vault()
        val a1 = p.attester(1, "R1").also { it.attest(ByteArray(32)) }.made!!
        val a2 = p.attester(1, "R2").also { it.attest(ByteArray(32)) }.made!!
        vault.held = a1
        assertEquals("ok", p.unlock(1, vault, recovering = true))
        assertEquals(listOf<String?>(a2, a1), vault.asked)
        // Before the handshake completes both keys stay (a recovery: nothing is dropped at the unlock).
        assertTrue(a1 in p.ks.keys && a2 in p.ks.keys)
        // hs.init with each recovery_id N registered for, newest first: R2 completes.
        val tried = mutableListOf<String?>()
        assertEquals("R2", p.pair(1, last = "R2", completes = { it == "R2" }, tried = tried))
        assertEquals(listOf<String?>("R2"), tried)
        // The binding is R2's key: A2 stays, A1 goes.
        assertEquals(listOf(a2), p.ring.candidates(1).map { it.alias })
        assertFalse(a1 in p.ks.keys)
        // The vault now binds A2, and the next unlock signs with it.
        vault.held = a2
        assertEquals("ok", p.unlock(1, vault, recovering = false))
        assertEquals(a2, p.signed.last())
    }

    @Test
    fun pairingTriesEachRecoveryIdNewestFirstAndKeepsAllWhenNoKeyNamesIt() {
        val p = Phone(1)
        val a1 = p.attester(1, "R1").also { it.attest(ByteArray(32)) }.made!!
        val a1b = p.attester(1, "R1").also { it.attest(ByteArray(32)) }.made!!
        val a2 = p.attester(1, "R2").also { it.attest(ByteArray(32)) }.made!!
        // No hs.resp for R2: R1 completes; both of R1's keys stay (either may be the one recorded), R2's goes.
        val tried = mutableListOf<String?>()
        assertEquals("R1", p.pair(1, last = "R2", completes = { it == "R1" }, tried = tried))
        assertEquals(listOf<String?>("R2", "R1"), tried)
        assertEquals(listOf(a1b, a1), p.ring.candidates(1).map { it.alias })
        assertFalse(a2 in p.ks.keys)
        // The key of a build before slots names no registration: the device's own recovery_id, and nothing is deleted.
        val old = Phone(0)
        old.ks.keys += AndroidKeys.ALIAS_DEVICE_ATTESTATION
        val t2 = mutableListOf<String?>()
        assertEquals("R9", old.pair(0, last = "R9", completes = { true }, tried = t2))
        assertEquals(listOf<String?>("R9"), t2)
        assertEquals(AndroidKeys.ALIAS_DEVICE_ATTESTATION, old.ring.current(0)?.alias)
        // No recovery_id at all: one hs.init with the device's own.
        assertEquals(listOf<String?>(null), Phone().ring.pairingContexts(0, null))
    }

    @Test
    fun anOrdinaryUnlockFallsBackToTheBoundEnrollmentKeyWhichIsKept() = runTest {
        // An enrollment re-posted after an `expired` slot: the vault recorded the first POST's key, the phone made a second.
        val p = Phone(0)
        val e1 = p.attester(0).also { it.attest(ByteArray(32)) }.made!!
        val e2 = p.attester(0).also { it.attest(ByteArray(32)) }.made!!
        val vault = Vault().apply { held = e1 }
        assertEquals("ok", p.unlock(0, vault, recovering = false))
        assertEquals(listOf<String?>(e2, e1), vault.asked)
        // Outside a recovery the bound key stays and the identity's other keys that served no registration go.
        assertEquals(listOf(e1), p.ring.candidates(0).map { it.alias })
        assertFalse(e2 in p.ks.keys)
        assertEquals("ok", p.unlock(0, vault, recovering = false))
        assertEquals(listOf<String?>(e2, e1, e1), vault.asked)
        // Fail secure: a registration's key is never dropped by an unlock, nor anything while recovering.
        val q = Phone(0)
        val r1 = q.attester(0, "R1").also { it.attest(ByteArray(32)) }.made!!
        val k = q.attester(0).also { it.attest(ByteArray(32)) }.made!!
        val k2 = q.attester(0).also { it.attest(ByteArray(32)) }.made!!
        val v = Vault().apply { held = k }
        assertEquals("ok", q.unlock(0, v, recovering = true))
        assertEquals(setOf(r1, k, k2), q.ks.keys.toSet())
        assertEquals("ok", q.unlock(0, v, recovering = false))
        assertEquals(setOf(r1, k), q.ks.keys.toSet())
    }

    @Test
    fun aFullSlotMakesNoKeyAndAFreedAliasIsReused() {
        val p = Phone()
        val made = (0 until AndroidKeys.DEVICE_ATTESTATION_KEYS_PER_SLOT).map {
            p.attester(0, "R").also { a -> a.attest(ByteArray(32)) }.made!!
        }
        assertEquals(made.toSet().size, made.size)
        val e = assertThrows(VaultFailure::class.java) { p.attester(0, "R").attest(ByteArray(32)) }
        assertEquals(AttestationKeys.CODE_KEYS_FULL, e.code)
        assertEquals(made.toSet(), p.ks.keys.toSet())
        // A key settled not registered frees its alias.
        p.ring.remove(0, listOf(made[3]))
        val again = p.attester(0, "R").also { it.attest(ByteArray(32)) }.made
        assertEquals(made[3], again)
        assertEquals(again, p.ring.current(0)?.alias)
    }

    @Test
    fun everySlotAliasIsKnownToTheWipe() {
        for (s in 0 until AndroidKeys.DEVICE_ATTESTATION_SLOTS) {
            for (i in 0 until AndroidKeys.DEVICE_ATTESTATION_KEYS_PER_SLOT) {
                assertTrue(slotAlias(s, i) in com.vettid.core.data.wipe.LocalWipe.KNOWN_ALIASES)
            }
        }
        assertTrue(AndroidKeys.ALIAS_DEVICE_ATTESTATION in com.vettid.core.data.wipe.LocalWipe.KNOWN_ALIASES)
        assertEquals("vettid.device_attestation.v1.slot1.63", slotAlias(1, 63))
    }
}
