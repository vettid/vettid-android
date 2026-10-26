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

        fun newRing() = AttestationKeys(store, ks) { slotInUse }

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
        p.ring.remove(1, posts.notRegistered(RegisterKeys.End.REFUSED))
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
        p.ring.remove(1, posts.notRegistered(RegisterKeys.End.KEEP))
        assertEquals(listOf(a2, a1), p.ring.candidates(1).map { it.alias })
        // Newest first: `attestation`, then the first key's `ok`, which makes it the identity's key from now on.
        assertEquals("ok", p.open(1, vault))
        assertEquals(listOf<String?>(a2, a1), vault.asked)
        assertEquals(a1, p.ring.current(1)?.alias)
        // A restart keeps it.
        p.restart()
        assertEquals(a1, p.ring.current(1)?.alias)
        p.attester(1).assert(ByteArray(32))
        assertEquals(a1, p.signed.last())
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
        p.ring.remove(1, posts.notRegistered(RegisterKeys.endOf(503, "release_starting")))
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
        p.ring.remove(1, nPosts.notRegistered(RegisterKeys.End.KEEP))
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
        assertTrue(fresh.store.m.isEmpty())
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
        assertEquals(keys[0], none.asked.first())
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
        // POST 1 answered `409 instance_moved` (no 202: never forwarded), POST 2 `202` then `expired`, POST 3 the last.
        val k1 = p.buildRegister(0, "R", posts)
        val k2 = p.buildRegister(0, "R", posts)
        posts.accepted()
        val k3 = p.buildRegister(0, "R", posts)
        assertEquals(listOf(k1), posts.notRegistered(RegisterKeys.End.KEEP))
        assertEquals(listOf(k1, k3), posts.notRegistered(RegisterKeys.End.NOT_FORWARDED))
        posts.accepted()
        assertEquals(listOf(k1), posts.notRegistered(RegisterKeys.End.NOT_FORWARDED))
        assertEquals(listOf(k1, k3), posts.notRegistered(RegisterKeys.End.REFUSED))
        assertFalse(k2 in posts.notRegistered(RegisterKeys.End.REFUSED))
        // Only the answers that never forward a POST settle it.
        val notForwarded = listOf(429 to "rate_limited", 503 to "vault_unavailable", 401 to "unauthorized", 409 to "recovery_not_available")
        for ((status, code) in notForwarded) {
            assertEquals(code, RegisterKeys.End.NOT_FORWARDED, RegisterKeys.endOf(status, code))
        }
        assertEquals(RegisterKeys.End.NOT_FORWARDED, RegisterKeys.endOf(409, "instance_moved"))
        for ((status, code) in listOf(502 to null, 500 to "internal", 409 to "recovery_unavailable", null to null)) {
            assertEquals("$status $code", RegisterKeys.End.KEEP, RegisterKeys.endOf(status, code))
        }
        assertTrue(RegisterKeys().notRegistered(RegisterKeys.End.REFUSED).isEmpty())
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
        assertTrue(p.store.m.isEmpty())
    }

    @Test
    fun pairingKeepsTheKeyThatAnsweredTheOk() = runTest {
        val p = Phone(1)
        val vault = Vault()
        // N registered for R1 with A1 (the vault adopted it), then again for R2 with A2.
        val a1 = p.attester(1, "R1").also { it.attest(ByteArray(32)) }.made!!
        val a2 = p.attester(1, "R2").also { it.attest(ByteArray(32)) }.made!!
        vault.held = a1
        // The lost-ok unlock: A2 is refused `attestation`, A1 answers `ok`.
        assertEquals("ok", p.open(1, vault))
        // N pairs with the recovery_id of the registration whose key answered the ok.
        assertEquals("R1", p.ring.current(1)?.recoveryId)
        // Before the handshake completes both keys stay.
        assertTrue(a1 in p.ks.keys && a2 in p.ks.keys)
        // After it: that key is kept, N's others go.
        p.ring.keepOnly(1, p.ring.current(1)!!.alias)
        assertEquals(listOf(a1), p.ring.candidates(1).map { it.alias })
        assertFalse(a2 in p.ks.keys)
        p.attester(1).assert(ByteArray(32))
        assertEquals(a1, p.signed.last())
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
        assertEquals("vettid.device_attestation.v1.slot1.15", slotAlias(1, 15))
    }
}
