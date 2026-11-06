package com.vettid.core.crypto

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Test-only edwards25519 arithmetic (RFC 8032 §5.1, extended coordinates over BigInteger), independent of Bouncy Castle and
 * of [Ed25519Exact]: it makes the signatures the strict rules are about (an `R` of small order, a key with a small-order
 * part) from a seed, so that no such value is written into a test as a literal.
 */
object Edwards {
    val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val SQRT_M1 = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    /** (X, Y, Z, T) with x = X/Z, y = Y/Z, xy = T/Z. */
    class Pt(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    val IDENTITY = Pt(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    val BASE: Pt = decode(encodeY(BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P), 0))!!

    fun add(p: Pt, q: Pt): Pt {
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P)
        val c = p.t.multiply(BigInteger.TWO).multiply(D).multiply(q.t).mod(P)
        val d = p.z.multiply(BigInteger.TWO).multiply(q.z).mod(P)
        val e = b.subtract(a)
        val f = d.subtract(c)
        val g = d.add(c)
        val h = b.add(a)
        return Pt(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    fun mul(s: BigInteger, p: Pt): Pt {
        var q = IDENTITY
        var r = p
        var k = s
        while (k.signum() > 0) {
            if (k.testBit(0)) q = add(q, r)
            r = add(r, r)
            k = k.shiftRight(1)
        }
        return q
    }

    fun encode(p: Pt): ByteArray {
        val zi = p.z.modInverse(P)
        val x = p.x.multiply(zi).mod(P)
        val y = p.y.multiply(zi).mod(P)
        return encodeY(y, if (x.testBit(0)) 1 else 0)
    }

    private fun encodeY(y: BigInteger, sign: Int): ByteArray {
        val v = if (sign == 1) y.setBit(255) else y
        return le(v)
    }

    fun decode(b: ByteArray): Pt? {
        val sign = (b[31].toInt() shr 7) and 1
        val y = BigInteger(1, b.reversedArray()).clearBit(255)
        if (y >= P) return null
        val y2 = y.multiply(y).mod(P)
        val x2 = y2.subtract(BigInteger.ONE).multiply(D.multiply(y2).add(BigInteger.ONE).modInverse(P)).mod(P)
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null
        if (x.signum() == 0 && sign == 1) return null
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x)
        return Pt(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    fun le(v: BigInteger): ByteArray {
        val out = ByteArray(32)
        val be = v.toByteArray()
        for (i in be.indices) {
            val j = be.size - 1 - i
            if (i < 32) out[i] = be[j]
        }
        return out
    }

    private fun leInt(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    /** The secret scalar of an RFC 8032 seed (clamped). */
    fun scalar(seed: ByteArray): BigInteger {
        val h = MessageDigest.getInstance("SHA-512").digest(seed).copyOf(32)
        h[0] = (h[0].toInt() and 248).toByte()
        h[31] = ((h[31].toInt() and 127) or 64).toByte()
        return leInt(h)
    }

    /** k = SHA-512(R || A || m) mod L. */
    fun challenge(r: ByteArray, a: ByteArray, m: ByteArray): BigInteger {
        val md = MessageDigest.getInstance("SHA-512")
        md.update(r)
        md.update(a)
        md.update(m)
        return leInt(md.digest()).mod(L)
    }

    /**
     * A signature `R || S` under the public key [pub] (whose discrete log is [a] plus, for a mixed key, a small-order part)
     * with the nonce [r] (`R = [r]B`), or with [rPoint] as `R` and no nonce: `S = r + k·a mod L`.
     */
    fun sign(a: BigInteger, pub: ByteArray, m: ByteArray, r: BigInteger, rPoint: Pt = mul(r, BASE)): ByteArray {
        val rEnc = encode(rPoint)
        val k = challenge(rEnc, pub, m)
        val s = r.add(k.multiply(a)).mod(L)
        return rEnc + le(s)
    }
}
