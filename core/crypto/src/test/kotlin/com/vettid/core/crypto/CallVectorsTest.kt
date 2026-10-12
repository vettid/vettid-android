package com.vettid.core.crypto

import com.vettid.core.crypto.call.CallFrames
import com.vettid.core.crypto.call.CallMedia
import com.vettid.core.crypto.call.CallWire
import com.vettid.core.crypto.call.MediaSuite
import com.vettid.core.crypto.hpke.KemPrivateKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * vettid-vault's call vectors (`calls.json`, VAULT-MESSAGING §10.10): the key agreement from both sides, the shares
 * of both roles and the vault-signed ICE configuration, byte for byte. Then the media keys the app derives from
 * `k_call` (docs/calls/CALL-MEDIA.md), whose expected values were computed independently (Python `hmac` and
 * `cryptography`) from the same vector.
 */
class CallVectorsTest {
    private val d = VectorDoc.load("calls.json")
    private val callId = d.str("call_id")
    private val ek = d.b64("ek_b64")
    private val enc = d.b64("enc_b64")
    private val kCall = d.hex("k_call_hex")
    private val offerKey = KemPrivateKey.fromSeed(d.hex("offer_kem_seed_hex"))

    private class Party(d: VectorDoc) {
        val device = Ed25519PrivateKey.fromSeed(d.hex("device_ik_seed_hex"))
        val vault = Ed25519PrivateKey.fromSeed(d.hex("vault_ik_seed_hex"))
        val deviceIk: ByteArray = d.b64("device_ik_b64")
        val vaultIk: ByteArray = d.b64("vault_ik_b64")
    }

    private val caller = Party(d.sub("caller"))
    private val callee = Party(d.sub("callee"))

    @Test
    fun labels() {
        assertEquals(CallWire.LABEL_CALL, d.str("label_call"))
        assertEquals(CallWire.LABEL_CALL_KEY, d.str("label_call_key"))
        assertEquals(CallWire.MEDIA_SALT, d.str("media_salt"))
        assertEquals(CallWire.LABEL_SHARE, d.str("label_share"))
        assertEquals(CallWire.LABEL_VOUCH, d.str("label_vouch"))
        assertEquals(CallWire.LABEL_ICE, d.str("label_ice"))
    }

    @Test
    fun theCallerDerivesTheVectorsKey() {
        assertBytes("ek", ek, offerKey.publicKey.bytes())
        assertBytes("k_call", kCall, CallWire.accept(offerKey, enc, callId))
    }

    @Test
    fun theAnswererProducesTheVectorsEncAndKey() {
        val a = deterministic(d.hex("answer_randomness_hex")) { CallWire.answer(ek, callId) }
        assertBytes("enc", enc, a.enc)
        assertBytes("k_call", kCall, a.kCall)
    }

    @Test
    fun aFreshAnswerAgreesAndIsBoundToTheCallAndTheKey() {
        val a = CallWire.answer(ek, callId)
        assertEquals(Suite.ENC_SIZE, a.enc.size)
        assertFalse(a.enc.contentEquals(enc))
        assertBytes("k_call", a.kCall, CallWire.accept(offerKey, a.enc, callId))
        val otherCall = runCatching { CallWire.accept(offerKey, a.enc, callId.dropLast(1) + "1") }.getOrNull()
        assertFalse(otherCall != null && otherCall.contentEquals(a.kCall))
        val otherKey = runCatching { CallWire.accept(CallWire.newOfferKey(), a.enc, callId) }.getOrNull()
        assertFalse(otherKey != null && otherKey.contentEquals(a.kCall))
    }

    @Test
    fun badSizesAndAnEmptyCallIdAreRefused() {
        assertThrows(CryptoException::class.java) { CallWire.answer(ek.copyOf(ek.size - 1), callId) }
        assertThrows(CryptoException::class.java) { CallWire.answer(ek + 0, callId) }
        assertThrows(CryptoException::class.java) { CallWire.answer(ek, "") }
        assertThrows(CryptoException::class.java) { CallWire.accept(offerKey, enc.copyOf(enc.size - 1), callId) }
        assertThrows(CryptoException::class.java) { CallWire.accept(offerKey, enc, "") }
    }

    private fun share(name: String, role: String, media: String, value: ByteArray, by: Party, other: Party) {
        val s = d.sub(name)
        assertEquals(role, s.str("role"))
        assertEquals(media, s.str("media"))
        val m = CallWire.shareMessage(role, callId, media, value)
        assertBytes("$name message", s.b64("message_b64"), m)
        val deviceSig = CallWire.signShare(by.device, m)
        assertBytes("$name device_sig", s.b64("device_sig_b64"), deviceSig)
        assertBytes("$name vouch message", s.b64("vouch_message_b64"), CallWire.vouchMessage(by.deviceIk, m))
        val vaultSig = by.vault.sign(CallWire.LABEL_VOUCH, CallWire.vouchMessage(by.deviceIk, m))
        assertBytes("$name vault_sig", s.b64("vault_sig_b64"), vaultSig)
        CallWire.verifyShare(by.vaultIk, by.deviceIk, m, deviceSig, vaultSig)

        // Another vault's key, another device's key, a changed share, the other role: none verifies.
        assertThrows(CryptoException.Signature::class.java) { CallWire.verifyShare(other.vaultIk, by.deviceIk, m, deviceSig, vaultSig) }
        assertThrows(CryptoException.Signature::class.java) { CallWire.verifyShare(by.vaultIk, other.deviceIk, m, deviceSig, vaultSig) }
        val changed = m.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertThrows(CryptoException.Signature::class.java) { CallWire.verifyShare(by.vaultIk, by.deviceIk, changed, deviceSig, vaultSig) }
        val swapped = if (role == CallWire.ROLE_OFFER) CallWire.ROLE_ANSWER else CallWire.ROLE_OFFER
        val otherRole = CallWire.shareMessage(swapped, callId, media, value)
        assertThrows(CryptoException.Signature::class.java) {
            CallWire.verifyShare(by.vaultIk, by.deviceIk, otherRole, deviceSig, vaultSig)
        }
        assertThrows(CryptoException.Signature::class.java) { CallWire.verifyShare(by.vaultIk, by.deviceIk, m, deviceSig, deviceSig) }
    }

    @Test
    fun theOfferShare() = share("offer_share", CallWire.ROLE_OFFER, CallWire.MEDIA_AUDIO, ek, caller, callee)

    @Test
    fun theAnswerShare() = share("answer_share", CallWire.ROLE_ANSWER, "", enc, callee, caller)

    @Test
    fun theIceConfigurations() {
        val exp = d.num("ice_exp")
        val before = Instant.ofEpochSecond(exp - 1)
        for (name in listOf("ice", "ice_no_servers")) {
            val v = d.sub(name)
            val config = v.b64("ice_config_b64")
            val sig = v.b64("ice_sig_b64")
            assertEquals(v.str("config"), String(config, Charsets.UTF_8))
            assertBytes("$name sig", sig, caller.vault.sign(CallWire.LABEL_ICE, config))
            val c = CallWire.verifyIce(caller.vaultIk, config, sig, callId, before)
            assertEquals(callId, c.callId)
            assertEquals(exp, c.exp.epochSecond)
            assertBytes("$name canonical", config, CallWire.marshalIce(c))
            val atExp = Instant.ofEpochSecond(exp)
            assertThrows(CryptoException.Time::class.java) { CallWire.verifyIce(caller.vaultIk, config, sig, callId, atExp) }
            assertThrows(CryptoException.Signature::class.java) { CallWire.verifyIce(callee.vaultIk, config, sig, callId, before) }
            assertThrows(CryptoException.Protocol::class.java) {
                CallWire.verifyIce(caller.vaultIk, config, sig, callId.dropLast(1) + "1", before)
            }
        }
        val servers = CallWire.parseIce(d.sub("ice").b64("ice_config_b64")).servers
        assertEquals(2, servers.size)
        assertEquals(listOf("stun:turn.example.org:3478"), servers[0].urls)
        assertNull(servers[0].credential)
        assertEquals("1800000000:$callId", servers[1].username)
        assertFalse("a credential is never printed", servers[1].toString().contains(servers[1].credential!!))
        assertTrue(CallWire.parseIce(d.sub("ice_no_servers").b64("ice_config_b64")).servers.isEmpty())
    }

    @Test
    fun anIceConfigurationThatIsNotCanonicalOrNotValidIsRefused() {
        val ok = String(d.sub("ice").b64("ice_config_b64"), Charsets.UTF_8)
        val bad = listOf(
            ok.replace("""{"v":1,"call_id"""", """{ "v":1,"call_id""""),
            ok.replace(""""v":1,""", """"v":2,"""),
            ok.replace("stun:turn.example.org:3478", "http://turn.example.org"),
            ok.replace("stun:turn.example.org:3478", "stun:"),
            ok.replace("stun:turn.example.org:3478", "stun:a b"),
            ok.replace(""","credential":"c2VjcmV0"""", ""),
            ok.replace(""""credential":"c2VjcmV0"""", """"credential":"""""),
            ok.replace("""{"urls":["stun:turn.example.org:3478"]},""", """{"urls":[]},"""),
            ok.replace(""""ice_servers":[""", """"extra":1,"ice_servers":["""),
            ok.replace(""""exp":1800000000""", """"exp":0"""),
        )
        for (b in bad) {
            assertFalse(b, b == ok)
            assertThrows(b, CryptoException::class.java) { CallWire.parseIce(b.toByteArray(Charsets.UTF_8)) }
        }
        val servers = List(CallWire.MAX_ICE_SERVERS + 1) { CallWire.IceServer(listOf("stun:x")) }
        val many = CallWire.IceConfig(callId, Instant.ofEpochSecond(1), servers)
        assertThrows(CryptoException.Format::class.java) { CallWire.marshalIce(many) }
    }

    // --- the media keys (docs/calls/CALL-MEDIA.md §3, §4) ---

    private val s1 = MediaSuite.AES_128_GCM
    private val one = CallMedia.Negotiated(s1, listOf(1))

    @Test
    fun theDirectionKeysAndFrameKeysOfTheVector() {
        val caller = CallMedia.directionKey(kCall, one, CallMedia.DIRECTION_CALLER, callId)
        val callee = CallMedia.directionKey(kCall, one, CallMedia.DIRECTION_CALLEE, callId)
        assertBytes("caller", Bytes.unhex("df07dfb74e30ab8b952a864b0214911649859b9d371d50cc04daa84d5c952c08"), caller)
        assertBytes("callee", Bytes.unhex("8e8b93784e37abb2257c2d42e47a0839e5aee933610f511c218199ebb7da637e"), callee)
        assertBytes("caller frame key", Bytes.unhex("df487affce123283952f691628c1c2de"), CallFrames.frameKey(caller, s1))
        assertBytes("callee frame key", Bytes.unhex("81e8788d323103611d582bd089af9be4"), CallFrames.frameKey(callee, s1))
    }

    @Test
    fun theChoiceAndTheOfferedListAreInTheKey() {
        val k = { n: CallMedia.Negotiated -> Bytes.hex(CallMedia.directionKey(kCall, n, CallMedia.DIRECTION_CALLER, callId)) }
        val two = CallMedia.Negotiated(MediaSuite.AES_256_GCM, listOf(2, 1))
        assertEquals("129e7ea015351c448613b72d821141780fbc2681c9562f2b47894d999c609617", k(two))
        // A list cut down to the weaker suite on the way: the caller (who offered 2 and 1) and the answerer (who saw
        // only 1) derive different keys for the same choice.
        assertEquals("eb7c3d3b7c0d8d3a42b85e908c9f9dbf5b76a56a11e91d0e9138134c7328aec0", k(CallMedia.Negotiated(s1, listOf(2, 1))))
        assertFalse(k(CallMedia.Negotiated(s1, listOf(2, 1))) == k(one))
        assertFalse(k(one) == Bytes.hex(CallMedia.directionKey(kCall, one, CallMedia.DIRECTION_CALLER, callId.dropLast(1) + "1")))
    }

    @Test
    fun aSealedAudioFrameOfTheVector() {
        val key = CallFrames.frameKey(CallMedia.directionKey(kCall, one, CallMedia.DIRECTION_CALLER, callId), s1)
        val frame = Bytes.unhex("78797a7b7c7d7e7f808182838485868788898a8b")
        val iv = CallFrames.iv(0x11223344, 960, 0)
        assertBytes("iv", Bytes.unhex("11223344000003c0000003c0"), iv)
        val sealed = CallFrames.seal(key, frame, CallFrames.AUDIO_CLEAR_SIZE, iv)
        assertBytes(
            "sealed",
            Bytes.unhex("780a14a5d44d9ba28ccf9ad78a82e7855c69d9584297c8aef4c5bf19d8a503b6d6aae36a11223344000003c0000003c00c00"),
            sealed,
        )
        assertEquals(frame.size + CallFrames.OVERHEAD, sealed.size)
        assertArrayEquals(frame, CallFrames.open(key, sealed, CallFrames.AUDIO_CLEAR_SIZE))
        // The other direction's key does not open it.
        val other = CallFrames.frameKey(CallMedia.directionKey(kCall, one, CallMedia.DIRECTION_CALLEE, callId), s1)
        assertNull(CallFrames.open(other, sealed, CallFrames.AUDIO_CLEAR_SIZE))
    }
}
