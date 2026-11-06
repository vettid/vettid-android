package com.vettid.core.crypto

import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.math.ec.custom.djb.Curve25519
import java.math.BigInteger
import java.security.MessageDigest

/**
 * The cofactorless Ed25519 equation `[S]B = R + [k]A` (BALLOT-PROTOCOL §2 step 4, RFC 8032 §5.1.7 without the factor 8),
 * for [Ed25519.verifyStrict] when `A` or `R` is not of order L: Bouncy Castle's own check is cofactored, and gives the same
 * answer only for points of order L. The edwards25519 points are mapped to Bouncy Castle's short Weierstrass form of
 * curve25519 (`u = (1 + y) / (1 - y)`, `v = c·u / x` with `c² = -486664`, then `X = u + 486662 / 3`, `Y = v`), a group
 * isomorphism, and the equation is checked there. Only the rare mixed-order point comes here: the cost is a few scalar
 * multiplications.
 */
internal object Ed25519Exact {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665).multiply(inv(BigInteger.valueOf(121666))).mod(P)
    private val SQRT_M1: BigInteger = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
    private val curve = Curve25519()
    private val A_THIRD: BigInteger = BigInteger.valueOf(486662).multiply(inv(BigInteger.valueOf(3))).mod(P)
    private val C: BigInteger = requireNotNull(sqrt(P.subtract(BigInteger.valueOf(486664))))

    /** The base point B: y = 4/5, x even. */
    private val BASE: ECPoint by lazy {
        val y = BigInteger.valueOf(4).multiply(inv(BigInteger.valueOf(5))).mod(P)
        requireNotNull(toCurve(requireNotNull(xOf(y, 0)), y))
    }

    /** Whether `[S]B = R + [k]A` with `k = SHA-512(R || A || m) mod L`. The encodings were checked by the caller (steps 1-3). */
    fun cofactorless(publicKey: ByteArray, m: ByteArray, sig: ByteArray): Boolean {
        val a = point(publicKey) ?: return false
        val r = point(sig.copyOf(32)) ?: return false
        val s = le(sig.copyOfRange(32, 64))
        if (s >= L) return false
        val md = MessageDigest.getInstance("SHA-512")
        md.update(sig, 0, 32)
        md.update(publicKey)
        md.update(m)
        val k = le(md.digest()).mod(L)
        val left = BASE.multiply(s).normalize()
        val right = r.add(a.multiply(k)).normalize()
        return left == right
    }

    /** The Weierstrass image of an edwards25519 encoding (RFC 8032 §5.1.3 decoding); null when it does not decode or is x = 0. */
    internal fun point(enc: ByteArray): ECPoint? {
        if (enc.size != 32) return null
        val sign = (enc[31].toInt() shr 7) and 1
        val y = le(enc.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() })
        if (y >= P) return null
        val x = xOf(y, sign) ?: return null
        return toCurve(x, y)
    }

    private fun xOf(y: BigInteger, sign: Int): BigInteger? {
        val y2 = y.multiply(y).mod(P)
        val x2 = y2.subtract(BigInteger.ONE).multiply(inv(D.multiply(y2).add(BigInteger.ONE))).mod(P)
        if (x2.signum() == 0) return if (sign == 0) BigInteger.ZERO else null
        val x = sqrt(x2) ?: return null
        return if (x.testBit(0) == (sign == 1)) x else P.subtract(x)
    }

    /** Null for x = 0 (the identity and the point of order 2): small-order points the caller has refused already. */
    private fun toCurve(x: BigInteger, y: BigInteger): ECPoint? {
        if (x.signum() == 0) return null
        val one = BigInteger.ONE
        val u = one.add(y).multiply(inv(one.subtract(y).mod(P))).mod(P)
        val v = C.multiply(u).multiply(inv(x)).mod(P)
        val wx = u.add(A_THIRD).mod(P)
        // On the curve (Y² = X³ + aX + b). Not ECPoint.isValid(): that also asks for order n, which a mixed point lacks.
        val rhs = wx.multiply(wx).add(curve.a.toBigInteger()).multiply(wx).add(curve.b.toBigInteger()).mod(P)
        if (v.multiply(v).mod(P) != rhs) return null
        return curve.createPoint(wx, v)
    }

    /** A square root mod p (p = 5 mod 8), or null. */
    private fun sqrt(a: BigInteger): BigInteger? {
        var x = a.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).subtract(a).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
        return if (x.multiply(x).subtract(a).mod(P).signum() == 0) x else null
    }

    private fun inv(a: BigInteger): BigInteger = a.modPow(P.subtract(BigInteger.TWO), P)

    private fun le(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())
}
