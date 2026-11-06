package com.vettid.core.crypto

import com.vettid.core.crypto.json.asObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * [Ed25519.verifyStrict]: the four Ed25519 rules of BALLOT-PROTOCOL §2, against the shared `ed25519` vectors of `vote.json`
 * (BALLOT-PROTOCOL §11 item 4, verified with the ballot label as vettid-vault's `vms/votewire` does), against the two
 * cases Bouncy Castle's own (cofactored) check accepts and §2 refuses (an `R` of small order, and a key with a small-order
 * part whose equation holds only times 8), and against the keys Tink's (cofactorless) check accepts and §2 refuses (one of
 * small order, one whose `y` is not below p).
 */
class Ed25519StrictTest {
    private val v = VectorDoc.load("vote.json")
    private val label = "vettid/vote/1/ballot\u0000"
    private val seed = rep(0x31, 32)
    private val a = Edwards.scalar(seed)
    private val pub = Ed25519PrivateKey.fromSeed(seed).publicKey
    private val m = "a ballot message".toByteArray()
    private val lm = label.toByteArray(Charsets.US_ASCII) + m

    @Test
    fun everySharedVectorHasItsVerdict() {
        val cases = v.o.array("ed25519").map { it.asObject() }
        assertEquals(24, cases.size)
        for (c in cases) {
            val got = Ed25519.verifyStrict(Bytes.unhex(c.string("key")), label, Bytes.unhex(c.string("msg")), Bytes.unhex(c.string("sig")))
            assertEquals(c.string("case"), c.bool("valid"), got)
        }
    }

    @Test
    fun theTestArithmeticAgreesWithTheLibrary() {
        assertTrue(Edwards.encode(Edwards.mul(a, Edwards.BASE)).contentEquals(pub))
        val sig = Edwards.sign(a, pub, lm, BigInteger.valueOf(12345))
        assertTrue(Ed25519.verifyRaw(pub, lm, sig))
        assertTrue(Ed25519.verifyStrict(pub, label, m, sig))
        // Tink's cofactorless check alone agrees on an ordinary signature, and refuses it changed.
        assertTrue(Ed25519.cofactorless(pub, lm, sig))
        assertFalse(Ed25519.cofactorless(pub, lm + 1, sig))
        assertFalse(Ed25519.cofactorless(pub, lm, sig.copyOf().also { it[40] = (it[40] + 1).toByte() }))
    }

    @Test
    fun theIdentityAsRIsRefused() {
        // R = the identity, S = k·a: [S]B = R + [k]A holds without the factor 8, so Bouncy Castle and Tink both accept it;
        // only the table refuses it.
        val sig = Edwards.sign(a, pub, lm, BigInteger.ZERO, Edwards.IDENTITY)
        assertTrue("the library alone accepts it", Ed25519.verifyRaw(pub, lm, sig))
        assertTrue("Tink alone accepts it", Ed25519.cofactorless(pub, lm, sig))
        assertFalse(Ed25519.verifyStrict(pub, label, m, sig))
        assertFalse(Ed25519.strictPoint(sig.copyOf(32)))
    }

    @Test
    fun aMixedOrderKeyPassesOnlyByTheCofactorlessEquation() {
        val t8 = Edwards.decode(Bytes.unhex(order8Key()))!!
        val mixed = Edwards.encode(Edwards.add(Edwards.mul(a, Edwards.BASE), t8))
        assertTrue("not of small order itself", Ed25519.strictPoint(mixed))
        for (holds in listOf(true, false)) {
            var r = BigInteger.ONE
            var sig: ByteArray
            while (true) {
                sig = Edwards.sign(a, mixed, lm, r)
                val k = Edwards.challenge(sig.copyOf(32), mixed, lm)
                if ((k.mod(BigInteger.valueOf(8)).signum() == 0) == holds) break
                r = r.add(BigInteger.ONE)
            }
            assertTrue("the library (cofactored) accepts it", Ed25519.verifyRaw(mixed, lm, sig))
            assertEquals("cofactorless holds: $holds", holds, Ed25519.verifyStrict(mixed, label, m, sig))
        }
    }

    @Test
    fun aSmallOrderRWithAMixedKeyIsRefusedByTheTableAlone() {
        // A' = A + T (T of order 8), R = -[k]T of order 4 or 8, S = k·a: [S]B = R + [k]A' holds exactly (cofactorless too),
        // so only rule 2 (the table) refuses it.
        val t8 = Edwards.decode(Bytes.unhex(order8Key()))!!
        val mixed = Edwards.encode(Edwards.add(Edwards.mul(a, Edwards.BASE), t8))
        val smalls = (2..7).filter { it != 4 }.map { Edwards.mul(BigInteger.valueOf(it.toLong()), t8) }
        var found: ByteArray? = null
        var msg = m
        var i = 0
        while (found == null) {
            msg = "message $i".toByteArray()
            val lmi = label.toByteArray(Charsets.US_ASCII) + msg
            for (q in smalls) {
                val k = Edwards.challenge(Edwards.encode(q), mixed, lmi)
                val sum = Edwards.add(q, Edwards.mul(k, t8))
                if (Edwards.encode(sum).contentEquals(Edwards.encode(Edwards.IDENTITY))) {
                    found = Edwards.encode(q) + Edwards.le(k.multiply(a).mod(Edwards.L))
                    break
                }
            }
            i++
        }
        val sig = found!!
        val lmf = label.toByteArray(Charsets.US_ASCII) + msg
        assertTrue("the library alone accepts it", Ed25519.verifyRaw(mixed, lmf, sig))
        assertTrue("the equation holds without the factor 8", Ed25519.cofactorless(mixed, lmf, sig))
        assertFalse(Ed25519.verifyStrict(mixed, label, msg, sig))
    }

    @Test
    fun aNonCanonicalSAndWrongSizesAreRefused() {
        val sig = Edwards.sign(a, pub, lm, BigInteger.valueOf(777))
        val s = BigInteger(1, sig.copyOfRange(32, 64).reversedArray()).add(Edwards.L)
        assertFalse(Ed25519.verifyStrict(pub, label, m, sig.copyOf(32) + Edwards.le(s)))
        assertFalse(Ed25519.verifyStrict(pub.copyOf(31), label, m, sig))
        assertFalse(Ed25519.verifyStrict(pub, label, m, sig.copyOf(63)))
        assertFalse(Ed25519.verifyStrict(pub, "vettid/vote/1/other\u0000", m, sig))
    }

    /** A point of order 8, taken from the shared vectors' small-order keys (no literal here). */
    private fun order8Key(): String = v.o.array("ed25519").map { it.asObject() }
        .first { it.string("case") == "small-order key and R 3" }.string("key")

    @Test
    fun anRWithYNotBelowPIsRefused() {
        // y = p + k for a few small k, with and without the sign bit: never a canonical encoding.
        val p = Edwards.P
        for (k in listOf(0L, 1L, 5L, 18L)) {
            for (sign in listOf(0, 1)) {
                val enc = Edwards.le(if (sign == 1) p.add(BigInteger.valueOf(k)).setBit(255) else p.add(BigInteger.valueOf(k)))
                assertFalse("y = p + $k, sign $sign", Ed25519.strictPoint(enc))
                val sig = enc + Edwards.le(BigInteger.valueOf(7))
                assertFalse(Ed25519.verifyStrict(pub, label, m, sig))
            }
        }
        // 2^255 - 1 with the sign bit cleared (the largest y an encoding can carry).
        assertFalse(Ed25519.strictPoint(ByteArray(32) { 0xff.toByte() }.also { it[31] = 0x7f }))
    }

    @Test
    fun aMixedOrderRWithAPrimeOrderKeyIsRefused() {
        // R' = [r]B + T (T of order 8), S = r + k·a with k over R': cofactored the equation holds (Bouncy Castle accepts),
        // cofactorless it is off by T, so only Tink's cofactorless check refuses it.
        val t8 = Edwards.decode(Bytes.unhex(order8Key()))!!
        val r = BigInteger.valueOf(4242)
        val rPoint = Edwards.add(Edwards.mul(r, Edwards.BASE), t8)
        val sig = Edwards.sign(a, pub, lm, r, rPoint)
        assertTrue("R itself is not of small order", Ed25519.strictPoint(sig.copyOf(32)))
        assertTrue("the library (cofactored) accepts it", Ed25519.verifyRaw(pub, lm, sig))
        assertFalse(Ed25519.cofactorless(pub, lm, sig))
        assertFalse(Ed25519.verifyStrict(pub, label, m, sig))
    }

    @Test
    fun aSmallOrderKeyIsRefusedByTheTableAlone() {
        // A = the identity, R = [r]B, S = r: [S]B = R + [k]A holds for any message without the factor 8, so Tink accepts it.
        val identity = Edwards.encode(Edwards.IDENTITY)
        val r = BigInteger.valueOf(9001)
        val sig = Edwards.encode(Edwards.mul(r, Edwards.BASE)) + Edwards.le(r)
        assertTrue("Tink alone accepts it", Ed25519.cofactorless(identity, lm, sig))
        assertFalse(Ed25519.strictPoint(identity))
        assertFalse(Ed25519.verifyStrict(identity, label, m, sig))
    }

    @Test
    fun aKeyWithYNotBelowPIsRefusedThoughTinkReadsItModP() {
        // y = p + 1 is the identity read mod p (Tink does not refuse y >= p), and it is in no table: only y < p refuses it.
        val nonCanonical = Edwards.le(Edwards.P.add(BigInteger.ONE))
        val r = BigInteger.valueOf(9002)
        val sig = Edwards.encode(Edwards.mul(r, Edwards.BASE)) + Edwards.le(r)
        assertTrue("Tink alone accepts it", Ed25519.cofactorless(nonCanonical, lm, sig))
        assertFalse(Ed25519.strictPoint(nonCanonical))
        assertFalse(Ed25519.verifyStrict(nonCanonical, label, m, sig))
    }
}
