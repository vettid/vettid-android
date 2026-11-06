package com.vettid.core.crypto

import com.vettid.core.crypto.json.asObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * [Ed25519.verifyStrict]: the four Ed25519 rules of BALLOT-PROTOCOL §2, against the shared `ed25519` vectors of `vote.json`
 * (BALLOT-PROTOCOL §11 item 4, verified with the ballot label as vettid-vault's `vms/votewire` does) and against the two
 * cases Bouncy Castle's own (cofactored) check accepts and §2 refuses: an `R` of small order, and a key with a small-order
 * part whose equation holds only times 8.
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
        assertEquals(12, cases.size)
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
        // The exact path alone agrees on an ordinary signature, and refuses it changed.
        assertTrue(Ed25519Exact.cofactorless(pub, lm, sig))
        assertFalse(Ed25519Exact.cofactorless(pub, lm + 1, sig))
        assertFalse(Ed25519Exact.cofactorless(pub, lm, sig.copyOf().also { it[40] = (it[40] + 1).toByte() }))
    }

    @Test
    fun anROfSmallOrderIsRefused() {
        // R = the identity, S = k·a: [S]B = R + [k]A holds without the factor 8, and Bouncy Castle accepts it.
        val sig = Edwards.sign(a, pub, lm, BigInteger.ZERO, Edwards.IDENTITY)
        assertTrue("the library alone accepts it", Ed25519.verifyRaw(pub, lm, sig))
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
        assertTrue("the equation holds without the factor 8", Ed25519Exact.cofactorless(mixed, lmf, sig))
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
}
