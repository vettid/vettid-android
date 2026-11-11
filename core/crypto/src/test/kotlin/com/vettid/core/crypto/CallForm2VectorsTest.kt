package com.vettid.core.crypto

import com.vettid.core.crypto.call.CallFrames
import com.vettid.core.crypto.call.CallMedia
import com.vettid.core.crypto.call.CallWire
import com.vettid.core.crypto.call.MediaSuite
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.crypto.json.asUint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (C2) Form 2 of the call shares and key confirmation (VAULT-MESSAGING 0.88.16 §10.10 "Call forms", "Key
 * confirmation", §16): vettid-vault's `calls.json` byte for byte, both members' vault identity keys in `m2`, in
 * `k_call` and in `confirm`, and the §16 values of the form 2 direction and frame keys.
 */
class CallForm2VectorsTest {
    private val d = VectorDoc.load("calls.json")
    private val callId = d.str("call_id")
    private val ek = d.b64("ek_b64")
    private val enc = d.b64("enc_b64")
    private val offerKey = KemPrivateKey.fromSeed(d.hex("offer_kem_seed_hex"))

    private class Party(d: VectorDoc) {
        val device = Ed25519PrivateKey.fromSeed(d.hex("device_ik_seed_hex"))
        val vault = Ed25519PrivateKey.fromSeed(d.hex("vault_ik_seed_hex"))
        val deviceIk: ByteArray = d.b64("device_ik_b64")
        val vaultIk: ByteArray = d.b64("vault_ik_b64")
    }

    private val caller = Party(d.sub("caller"))
    private val callee = Party(d.sub("callee"))
    private val parties = CallWire.Parties(caller.vaultIk, callee.vaultIk)
    private val offered = d.sub("offer_share2").o.array("suites").map { it.asUint(1, 65535).toInt() }
    private val chosen = d.sub("answer_share2").num("suite").toInt()

    @Test
    fun labelsAndLists() {
        assertEquals(CallWire.LABEL_CONFIRM, d.str("label_confirm"))
        assertEquals(listOf(2, 1), offered)
        assertEquals(offered, d.sub("answer_share2").o.array("offered").map { it.asUint(1, 65535).toInt() })
        assertEquals("2 1", CallWire.suiteList(offered))
    }

    private fun share2(name: String, role: String, m: ByteArray, by: Party, other: Party) {
        val s = d.sub(name)
        assertEquals(role, s.str("role"))
        assertBytes("$name message", s.b64("message_b64"), m)
        val deviceSig = CallWire.signShare(by.device, m)
        assertBytes("$name device_sig", s.b64("device_sig_b64"), deviceSig)
        assertBytes("$name vouch message", s.b64("vouch_message_b64"), CallWire.vouchMessage(by.deviceIk, m))
        val vaultSig = by.vault.sign(CallWire.LABEL_VOUCH, CallWire.vouchMessage(by.deviceIk, m))
        assertBytes("$name vault_sig", s.b64("vault_sig_b64"), vaultSig)
        CallWire.verifyShare(by.vaultIk, by.deviceIk, m, deviceSig, vaultSig)
        assertThrows(CryptoException.Signature::class.java) { CallWire.verifyShare(other.vaultIk, by.deviceIk, m, deviceSig, vaultSig) }
    }

    @Test
    fun theOfferShare2() = share2(
        "offer_share2", CallWire.ROLE_OFFER2,
        CallWire.offerMessage2(callId, CallWire.MEDIA_AUDIO, offered, parties, ek), caller, callee,
    )

    @Test
    fun theAnswerShare2() = share2(
        "answer_share2", CallWire.ROLE_ANSWER2,
        CallWire.answerMessage2(callId, chosen, offered, parties, enc), callee, caller,
    )

    /** A signature over m2 never verifies for other parties: the parties swapped, or a third vault on either side. */
    @Test
    fun m2NamesBothMembers() {
        val m = CallWire.offerMessage2(callId, CallWire.MEDIA_AUDIO, offered, parties, ek)
        val sig = CallWire.signShare(caller.device, m)
        val third = Ed25519PrivateKey.fromSeed(rep(0x33, 32)).publicKey
        for (p in listOf(
            CallWire.Parties(callee.vaultIk, caller.vaultIk),
            CallWire.Parties(third, callee.vaultIk),
            CallWire.Parties(caller.vaultIk, third),
        )) {
            val other = CallWire.offerMessage2(callId, CallWire.MEDIA_AUDIO, offered, p, ek)
            assertFalse(Ed25519.verify(caller.deviceIk, CallWire.LABEL_SHARE, other, sig))
        }
        // A form 1 signature never verifies over form 2.
        val m1 = CallWire.shareMessage(CallWire.ROLE_OFFER, callId, CallWire.MEDIA_AUDIO, ek)
        assertFalse(Ed25519.verify(caller.deviceIk, CallWire.LABEL_SHARE, m, CallWire.signShare(caller.device, m1)))
    }

    @Test
    fun theFormTwoCallKeyNamesBothMembers() {
        val k2 = CallWire.accept2(offerKey, enc, callId, parties)
        assertBytes("k_call2", d.hex("k_call2_hex"), k2)
        // VAULT-MESSAGING 0.88.16 §16.
        assertBytes("k_call2 (spec §16)", Bytes.unhex("fc69525bf7681cf0aa0bf2109578a36ba249fef9ea06a7f10bf6f44668d5612e"), k2)
        assertFalse(k2.contentEquals(CallWire.accept(offerKey, enc, callId)))
        val a = deterministic(d.hex("answer_randomness_hex")) { CallWire.answer2(ek, callId, parties) }
        assertBytes("enc (answer2)", enc, a.enc)
        assertBytes("k_call2 (answer2)", k2, a.kCall)
    }

    @Test
    fun keyConfirmation() {
        val k2 = CallWire.accept2(offerKey, enc, callId, parties)
        val kc = CallWire.confirmKey(k2, callId)
        assertBytes("k_confirm", d.hex("k_confirm_hex"), kc)
        val confirm = CallWire.confirm(kc, chosen, offered, callId, parties)
        assertBytes("confirm", d.b64("confirm_b64"), confirm)
        assertTrue(CallWire.verifyConfirm(kc, chosen, offered, callId, parties, confirm))
        assertFalse(CallWire.verifyConfirm(kc, chosen + 1, offered, callId, parties, confirm))
        assertFalse(CallWire.verifyConfirm(kc, chosen, listOf(1, 2), callId, parties, confirm))
        assertFalse(CallWire.verifyConfirm(kc, chosen, offered, callId, CallWire.Parties(callee.vaultIk, caller.vaultIk), confirm))
        assertFalse(CallWire.verifyConfirm(kc, chosen, offered, callId, parties, null))
        assertFalse(CallWire.verifyConfirm(kc, chosen, offered, callId, parties, ByteArray(0)))
        assertFalse(CallWire.verifyConfirm(kc, chosen, offered, callId, parties, confirm.copyOf(31)))
        // §16: the other two cases.
        assertBytes("confirm 1/1", Bytes.unhex("79c34a326ba978264d68200745b47606b51e21e76a392d00211d277b4613eb78"),
            CallWire.confirm(kc, 1, listOf(1), callId, parties))
        assertBytes("confirm 2 1/2", Bytes.unhex("ae9cd7eb039aec21e28e12980d752412c0454713227ad338b2a9192235640976"),
            CallWire.confirm(kc, 2, listOf(2, 1), callId, parties))
    }

    /** A call passed on by a third vault C: B derives "C calls B", A "A calls C"; B's confirm never verifies at A. */
    @Test
    fun aRelayedCallNeverConfirms() {
        val relay = d.b64("relay_vault_ik_b64")
        assertBytes("relay ik (seed 0x56)", Ed25519PrivateKey.fromSeed(rep(0x56, 32)).publicKey, relay)
        val atB = CallWire.Parties(relay, callee.vaultIk)
        val atA = CallWire.Parties(caller.vaultIk, relay)
        val kB = CallWire.accept2(offerKey, enc, callId, atB)
        assertBytes("k_call2 relayed (callee)", d.hex("k_call2_relayed_callee_hex"), kB)
        val confirmB = CallWire.confirm(CallWire.confirmKey(kB, callId), chosen, offered, callId, atB)
        assertBytes("confirm relayed", d.b64("confirm_relayed_b64"), confirmB)
        val kA = CallWire.accept2(offerKey, enc, callId, atA)
        assertFalse(kA.contentEquals(kB))
        assertFalse(CallWire.verifyConfirm(CallWire.confirmKey(kA, callId), chosen, offered, callId, atA, confirmB))
    }

    /** §16 (0.88.16): the form 2 direction and frame keys come from the bound k_call. */
    @Test
    fun formTwoMediaKeys() {
        val k2 = CallWire.accept2(offerKey, enc, callId, parties)
        val s1 = MediaSuite.byId(1)!!
        val n11 = CallMedia.Negotiated(s1, listOf(1))
        assertBytes("k_dir caller 1/1", Bytes.unhex("f50365693517abe27d0128be02100c7fa28d4ef8b849a49a760edb0f9bf4c51f"),
            CallMedia.directionKey(k2, n11, CallMedia.DIRECTION_CALLER, callId))
        assertBytes("k_dir callee 1/1", Bytes.unhex("91912e5c6fbdda2784745c28b07102a55c58cddc50cd2aef7d3a211568ced27e"),
            CallMedia.directionKey(k2, n11, CallMedia.DIRECTION_CALLEE, callId))
        assertBytes("k_dir caller 2 1/1", Bytes.unhex("157c0c53b1c46a9f90b8497b119546119000c34847440c537ae28cc556d46659"),
            CallMedia.directionKey(k2, CallMedia.Negotiated(s1, listOf(2, 1)), CallMedia.DIRECTION_CALLER, callId))
        val s2 = MediaSuite.byId(2)!!
        val kDir2 = CallMedia.directionKey(k2, CallMedia.Negotiated(s2, listOf(2, 1)), CallMedia.DIRECTION_CALLER, callId)
        assertBytes("k_dir caller 2 1/2", Bytes.unhex("4ac64a47eeb6199469d98039e28131e86927ae28adf6c5a302cffc295d27b542"), kDir2)
        // The frame keys.
        assertBytes("frame key caller 1/1", Bytes.unhex("83366919df86b7fc3a43c5c92f8367bd"),
            CallFrames.frameKey(CallMedia.directionKey(k2, n11, CallMedia.DIRECTION_CALLER, callId), s1))
        assertBytes("frame key callee 1/1", Bytes.unhex("f48a7399a6eebe7f53324dc50de746f7"),
            CallFrames.frameKey(CallMedia.directionKey(k2, n11, CallMedia.DIRECTION_CALLEE, callId), s1))
        assertBytes("frame key caller 2 1/2", Bytes.unhex("576b179f1a55bbb44af932f3b613a258229eee91098b046dc35f8ea8fa6d9447"),
            CallFrames.frameKey(kDir2, s2))
    }

    /** §16 (0.88.16): the two confirms that MUST NOT verify, the two keys swapped ("2 1", "1"). */
    @Test
    fun swappedPartiesNeverConfirm() {
        val k2 = CallWire.accept2(offerKey, enc, callId, parties)
        val kc = CallWire.confirmKey(k2, callId)
        val swapped = CallWire.Parties(callee.vaultIk, caller.vaultIk)
        val inHmacOnly = CallWire.confirm(kc, 1, listOf(2, 1), callId, swapped)
        assertBytes("swapped in the HMAC input only",
            Bytes.unhex("987cb11aea0528421f48a28898b26e1e1b1d8bf98c9c8dde0b60046eb52f5dec"), inHmacOnly)
        val kSwapped = CallWire.accept2(offerKey, enc, callId, swapped)
        val inKCallToo = CallWire.confirm(CallWire.confirmKey(kSwapped, callId), 1, listOf(2, 1), callId, swapped)
        assertBytes("swapped in k_call too",
            Bytes.unhex("b5bd80febcdfa5939a6624ae625e502ebb33dfca7ee9902d207da637371ce5a1"), inKCallToo)
        for (c in listOf(inHmacOnly, inKCallToo)) assertFalse(CallWire.verifyConfirm(kc, 1, listOf(2, 1), callId, parties, c))
        assertBytes("confirm 2 1/1", Bytes.unhex("5ed63e824d485ef4e2af8ac7d58936fec1eec26a437b5506722692e5db83357c"),
            CallWire.confirm(kc, 1, listOf(2, 1), callId, parties))
    }

    /** §16 (0.88.16): the relayed call's k_call and confirm, as printed. */
    @Test
    fun theRelayedCallsSpecValues() {
        val relay = d.b64("relay_vault_ik_b64")
        assertBytes("relay ik", Bytes.unhex("88df556413538b002015e9619b01bdb5cc48c7215645792563f5460f7307a763"), relay)
        val kB = CallWire.accept2(offerKey, enc, callId, CallWire.Parties(relay, callee.vaultIk))
        assertBytes("k_call relayed", Bytes.unhex("658f2ce918bdb72fec26036eea2ad4a8b34e5e813713f301fd70c377639af947"), kB)
        assertBytes("confirm relayed", Bytes.unhex("50abad6efd920a5dc6eb226a5daf8cff00fcca0bbc261d454637f0faf9641f0e"),
            CallWire.confirm(CallWire.confirmKey(kB, callId), 1, listOf(2, 1), callId, CallWire.Parties(relay, callee.vaultIk)))
    }

    @Test
    fun partiesMustBeKeys() {
        assertThrows(CryptoException::class.java) { CallWire.Parties(ByteArray(31), callee.vaultIk) }
        assertThrows(CryptoException::class.java) { CallWire.Parties(caller.vaultIk, ByteArray(0)) }
    }
}
