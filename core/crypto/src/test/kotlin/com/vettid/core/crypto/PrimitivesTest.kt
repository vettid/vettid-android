package com.vettid.core.crypto

import com.vettid.core.crypto.aead.XChaCha20Poly1305
import com.vettid.core.crypto.hpke.HpkeRecipient
import com.vettid.core.crypto.hpke.HpkeSender
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.crypto.hpke.KemPublicKey
import com.vettid.core.crypto.kdf.Argon2id
import com.vettid.core.crypto.kdf.Hkdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimitivesTest {
    @Test
    fun hkdfRfc5869Case1() {
        val ikm = rep(0x0b, 22)
        val salt = Bytes.unhex("000102030405060708090a0b0c")
        val info = Bytes.unhex("f0f1f2f3f4f5f6f7f8f9")
        val prk = Hkdf.extract(salt, ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", Bytes.hex(prk))
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Bytes.hex(Hkdf.expand(prk, info, 42)),
        )
    }

    @Test
    fun xchachaDraftVector() {
        // draft-irtf-cfrg-xchacha-03, A.3.1.
        val pt = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
        val aad = Bytes.unhex("50515253c0c1c2c3c4c5c6c7")
        val key = ByteArray(32) { (0x80 + it).toByte() }
        val nonce = ByteArray(24) { (0x40 + it).toByte() }
        val ct = XChaCha20Poly1305.seal(key, nonce, aad, pt.toByteArray())
        assertEquals(
            "bd6d179d3e83d43b9576579493c0e939572a1700252bfaccbed2902c21396cbb" +
                "731c7f1b0b4aa6440bf3a82f4eda7e39ae64c6708c54c216cb96b72e1213b4" +
                "522f8c9ba40db5d945b11b69b982c1bb9e3f3fac2bc369488f76b2383565d3fff921f9664c97637da9768812f615c68b13b52e" +
                "c0875924c1c7987947deafd8780acf49",
            Bytes.hex(ct),
        )
        assertEquals(pt, String(XChaCha20Poly1305.open(key, nonce, aad, ct)))
        ct[3] = (ct[3].toInt() xor 1).toByte()
        assertThrows(CryptoException.Decrypt::class.java) { XChaCha20Poly1305.open(key, nonce, aad, ct) }
        assertThrows(CryptoException.Decrypt::class.java) { XChaCha20Poly1305.open(key, nonce, aad, ByteArray(15)) }
        assertThrows(CryptoException.Key::class.java) { XChaCha20Poly1305.seal(ByteArray(31), nonce, aad, ByteArray(1)) }
    }

    @Test
    fun argon2idReferenceVector() {
        // phc-winner-argon2 test.c: argon2id v19, t=2, m=2^16 KiB, p=1, "password", "somesalt".
        val h = Argon2id.derive("password".toByteArray(), "somesalt".toByteArray(), t = 2, mKiB = 65_536, p = 1)
        assertEquals("09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7", Bytes.hex(h))
        assertThrows(CryptoException.Key::class.java) { Argon2id.checkParams(0, 65_536, 1) }
        assertThrows(CryptoException.Key::class.java) { Argon2id.checkParams(3, 8191, 1) }
    }

    @Test
    fun ed25519LabelsAndStrictness() {
        val k = Ed25519PrivateKey.fromSeed(rep(4, 32))
        val sig = k.sign("vettid/vms/2/test", "m".toByteArray())
        assertTrue(Ed25519.verify(k.publicKey, "vettid/vms/2/test", "m".toByteArray(), sig))
        assertFalse(Ed25519.verify(k.publicKey, "vettid/vms/2/other", "m".toByteArray(), sig))
        assertFalse(Ed25519.verify(k.publicKey.copyOf(31), "vettid/vms/2/test", "m".toByteArray(), sig))
        // Non-canonical S (S + L) is rejected.
        val l = Bytes.unhex("edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010")
        val s = sig.copyOfRange(32, 64)
        var carry = 0
        for (i in 0 until 32) {
            val v = (s[i].toInt() and 0xff) + (l[i].toInt() and 0xff) + carry
            s[i] = v.toByte()
            carry = v shr 8
        }
        assertFalse(Ed25519.verify(k.publicKey, "vettid/vms/2/test", "m".toByteArray(), Bytes.concat(sig.copyOf(32), s)))
        k.destroy()
        assertThrows(CryptoException.Key::class.java) { k.sign("x", ByteArray(0)) }
        assertEquals("Ed25519PrivateKey[redacted]", k.toString())
    }

    @Test
    fun hpkeRoundTripSingleUseAndExport() {
        val sk = KemPrivateKey.generate()
        val s = HpkeSender.setup(sk.publicKey, "info".toByteArray())
        val ct = s.seal("aad".toByteArray(), "hello".toByteArray())
        assertThrows(CryptoException.Used::class.java) { s.seal("aad".toByteArray(), "again".toByteArray()) }
        val r = HpkeRecipient.setup(s.enc(), sk, "info".toByteArray())
        assertEquals("hello", String(r.open("aad".toByteArray(), ct)))
        assertThrows(CryptoException.Used::class.java) { r.open("aad".toByteArray(), ct) }
        assertTrue(s.export("x", 32).contentEquals(r.export("x", 32)))
        // Wrong info or aad fails.
        assertThrows(CryptoException.Decrypt::class.java) {
            HpkeRecipient.setup(s.enc(), sk, "other".toByteArray()).open("aad".toByteArray(), ct)
        }
        assertThrows(CryptoException.Decrypt::class.java) {
            HpkeRecipient.setup(s.enc(), sk, "info".toByteArray()).open("aaX".toByteArray(), ct)
        }
        assertThrows(CryptoException.Key::class.java) { HpkeRecipient.setup(ByteArray(1119), sk, ByteArray(0)) }
        // Two senders to the same key never share an encapsulation.
        assertNotEquals(Bytes.hex(s.enc()), Bytes.hex(HpkeSender.setup(sk.publicKey, ByteArray(0)).enc()))
    }

    @Test
    fun kemKeysAreValidated() {
        val sk = KemPrivateKey.fromSeed(rep(5, 32))
        val ek = sk.publicKey.bytes()
        assertThrows(CryptoException.Key::class.java) { KemPublicKey.parse(ek.copyOf(1215)) }
        // A coefficient >= q (3329 = 0xd01): set the first 12-bit value to 0xfff.
        val bad = ek.copyOf()
        bad[0] = 0xff.toByte()
        bad[1] = (bad[1].toInt() or 0x0f).toByte()
        assertThrows(CryptoException.Key::class.java) { KemPublicKey.parse(bad) }
        assertEquals(sk.publicKey, KemPublicKey.parse(ek))
        assertThrows(CryptoException.Key::class.java) { KemPrivateKey.fromSeed(ByteArray(31)) }
        sk.destroy()
        assertThrows(CryptoException.Key::class.java) { sk.seed() }
        assertTrue(sk.toString().contains("redacted"))
    }

    @Test
    fun base64IsCanonical() {
        assertEquals("AQI=", Base64s.encodeStd(byteArrayOf(1, 2)))
        assertTrue(Base64s.decodeStd("AQI=").contentEquals(byteArrayOf(1, 2)))
        for (bad in listOf("AQI", "AQJ=", "AQI=\n", "AQ\r\nI=", "AQI==", "A-I=")) {
            assertThrows(bad, CryptoException.Format::class.java) { Base64s.decodeStd(bad) }
        }
        assertThrows(CryptoException.Format::class.java) { Base64s.decodeStd("AQI=", 3) }
        assertEquals("_-8", Base64s.encodeRawUrl(byteArrayOf(-1, -17)))
        assertThrows(CryptoException.Format::class.java) { Base64s.decodeRawUrl("_-8=") }
        assertThrows(CryptoException.Format::class.java) { Base64s.decodeRawUrl("_-9") }
    }

    @Test
    fun constantTimeHelpers() {
        assertTrue(Bytes.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(Bytes.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(Bytes.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
        assertFalse(Bytes.constantTimeEquals(null, byteArrayOf()))
        val b = byteArrayOf(1, 2, 3)
        Bytes.wipe(b)
        assertTrue(b.all { it.toInt() == 0 })
        assertThrows(CryptoException.Format::class.java) { Bytes.unhex("0G") }
        assertThrows(CryptoException.Format::class.java) { Bytes.unhex("0A") } // lowercase only
    }

    @Test
    fun suiteNegotiation() {
        assertEquals(2, Suite.negotiate(listOf(2), 0))
        assertEquals(2, Suite.negotiate(listOf(2, 3, 9), 0))
        assertThrows(CryptoException.Suite::class.java) { Suite.negotiate(listOf(1, 2), 0) }
        assertThrows(CryptoException.Suite::class.java) { Suite.negotiate(listOf(3, 2), 0) }
        assertThrows(CryptoException.Suite::class.java) { Suite.negotiate(emptyList(), 0) }
        assertThrows(CryptoException.Suite::class.java) { Suite.negotiate((2..10).toList(), 0) }
        assertThrows(CryptoException.Suite::class.java) { Suite.negotiate(listOf(2), 3) } // downgrade
        assertThrows(CryptoException.Suite::class.java) { Suite.checkChosen(2, listOf(3), 0) }
        assertThrows(CryptoException.Suite::class.java) { Suite.check(1, 0) }
    }
}
