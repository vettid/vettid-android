package com.vettid.core.crypto

import com.vettid.core.crypto.altchan.AltChannel
import com.vettid.core.crypto.altchan.DeviceAssertion
import com.vettid.core.crypto.altchan.Descriptor
import com.vettid.core.crypto.altchan.UnlockRequest
import com.vettid.core.crypto.envelope.Envelope
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Mode
import com.vettid.core.crypto.envelope.Padding
import com.vettid.core.crypto.envelope.Timestamps
import com.vettid.core.crypto.hpke.HpkeSender
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.crypto.invite.InviteBundle
import com.vettid.core.crypto.invite.InviteQr
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.kdf.Hkdf
import com.vettid.core.crypto.session.HsFin
import com.vettid.core.crypto.session.HsInit
import com.vettid.core.crypto.session.HsResp
import com.vettid.core.crypto.session.Initiator
import com.vettid.core.crypto.session.InitiatorConfig
import com.vettid.core.crypto.session.KeyLookup
import com.vettid.core.crypto.session.Mailbox
import com.vettid.core.crypto.session.PendingInit
import com.vettid.core.crypto.session.Policy
import com.vettid.core.crypto.session.Purpose
import com.vettid.core.crypto.session.RelayAddr
import com.vettid.core.crypto.session.ResponderConfig
import com.vettid.core.crypto.session.Schedule
import org.bouncycastle.crypto.digests.SHA3Digest
import org.bouncycastle.crypto.kems.MLKEMGenerator
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters
import org.bouncycastle.math.ec.rfc7748.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The VAULT-MESSAGING §16 vectors (vettid-vault testdata/vectors, copied to
 * src/test/resources/vectors): every value is checked from the receiving side,
 * as vettid-vault's own verify_test.go does, and every envelope is also
 * rebuilt from the sending side, byte for byte, with the vectors'
 * deterministic randomness.
 */
class VectorsTest {
    private class Keyset(val ik: Ed25519PrivateKey, val kem: KemPrivateKey, val relay: Ed25519PrivateKey?, val doc: VectorDoc)

    private val keys = VectorDoc.load("keys.json")
    private val vault = principal(keys.sub("vault"))
    private val ini = principal(keys.sub("initiator"))
    private val eph = kemFrom(keys.sub("initiator_ephemeral"))
    private val etk = kemFrom(keys.sub("etk"))
    private val t0: Instant = Instant.parse("2026-10-01T12:00:00Z")

    private fun principal(d: VectorDoc): Keyset {
        val ik = Ed25519PrivateKey.fromSeed(d.hex("ik_seed_hex"))
        assertBytes("ik_pk", d.b64("ik_pk_b64"), ik.publicKey)
        val kem = kemFrom(d)
        val relay = if (d.o.has("relay_seed_hex")) {
            Ed25519PrivateKey.fromSeed(d.hex("relay_seed_hex")).also {
                assertBytes("relay_pk", d.b64("relay_pk_b64"), it.publicKey)
                assertEquals("mailbox", d.str("mailbox"), Mailbox.id(it.publicKey))
            }
        } else {
            null
        }
        return Keyset(ik, kem, relay, d)
    }

    private fun kemFrom(d: VectorDoc): KemPrivateKey {
        val k = KemPrivateKey.fromSeed(d.hex("kem_seed_hex"))
        val ek = k.publicKey.bytes()
        assertEquals(Suite.EK_SIZE, ek.size)
        assertBytes("ek", d.b64("ek_b64"), ek)
        assertBytes("kid", d.hex("kid_hex"), k.publicKey.kid.bytes())
        assertBytes("kid (direct)", d.hex("kid_hex"), Bytes.sha256("vettid/vms/2/kid".toByteArray(), ek).copyOf(8))
        assertBytes("seed round trip", d.hex("kem_seed_hex"), k.seed())
        return k
    }

    private fun relayAddr(k: Keyset) = RelayAddr(k.doc.str("relay_url"), k.doc.str("mailbox"), k.relay!!.publicKey)

    @Test
    fun keys() {
        // The key derivations are checked in the constructors; the vault kid is in §16 too.
        assertEquals("40d6c2c8471844a9", vault.kem.publicKey.kid.toString())
        assertEquals("d6c9d114385e91df", etk.publicKey.kid.toString())
    }

    @Test
    fun hpke() {
        val d = VectorDoc.load("hpke.json")
        assertEquals("0x647a", d.str("kem_id"))
        assertBytes("recipient ek", d.b64("recipient_ek_b64"), vault.kem.publicKey.bytes())
        val rnd = d.hex("encapsulation_randomness_hex")
        val info = d.str("info").toByteArray()
        val s = deterministic(rnd) { HpkeSender.setup(vault.kem.publicKey, info) }
        assertEquals(d.num("enc_len").toInt(), Suite.ENC_SIZE)
        assertBytes("enc", d.b64("enc_b64"), s.enc())

        // Independent derivation of the X-Wing combiner and the RFC 9180 key
        // schedule (as vettid-vault internal/hpkederand), from BC's ML-KEM-768
        // and X25519 primitives: pins the intermediate values the BC HPKE
        // context does not expose.
        val ek = vault.kem.publicKey.bytes()
        val pq = MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, ek.copyOf(1184))
        val enc = MLKEMGenerator.internalGenerateEncapsulated(pq, rnd.copyOf(32))
        val pkX = ek.copyOfRange(1184, 1216)
        val ctX = ByteArray(32).also { X25519.scalarMultBase(rnd, 32, it, 0) }
        val ssX = ByteArray(32).also { X25519.scalarMult(rnd, 32, pkX, 0, it, 0) }
        val sha3 = SHA3Digest(256)
        for (p in listOf(enc.secret, ssX, ctX, pkX, byteArrayOf(0x5c, 0x2e, 0x2f, 0x2f, 0x5e, 0x5c))) sha3.update(p, 0, p.size)
        val ss = ByteArray(32).also { sha3.doFinal(it, 0) }
        assertBytes("enc (independent)", d.b64("enc_b64"), Bytes.concat(enc.encapsulation, ctX))
        assertBytes("shared_secret", d.hex("shared_secret_hex"), ss)

        val suiteId = Bytes.concat("HPKE".toByteArray(), byteArrayOf(0x64, 0x7a, 0x00, 0x01, 0x00, 0x03))
        fun labeledExtract(salt: ByteArray, label: String, ikm: ByteArray) =
            Hkdf.extract(salt, Bytes.concat("HPKE-v1".toByteArray(), suiteId, label.toByteArray(), ikm))
        fun labeledExpand(prk: ByteArray, label: String, ctx: ByteArray, l: Int) =
            Hkdf.expand(prk, Bytes.concat(Bytes.uintBE(l.toLong(), 2), "HPKE-v1".toByteArray(), suiteId, label.toByteArray(), ctx), l)
        val ksc = Bytes.concat(
            byteArrayOf(0),
            labeledExtract(ByteArray(0), "psk_id_hash", ByteArray(0)),
            labeledExtract(ByteArray(0), "info_hash", info),
        )
        val secret = labeledExtract(ss, "secret", ByteArray(0))
        assertBytes("key", d.hex("key_hex"), labeledExpand(secret, "key", ksc, 32))
        assertBytes("base_nonce", d.hex("base_nonce_hex"), labeledExpand(secret, "base_nonce", ksc, 12))
        val exporterSecret = labeledExpand(secret, "exp", ksc, 32)
        assertBytes("exporter_secret", d.hex("exporter_secret_hex"), exporterSecret)
        // BC's context exports from that exporter secret.
        assertBytes(
            "export",
            labeledExpand(exporterSecret, "sec", "vettid/vms/2/hs-ks".toByteArray(), 32),
            s.export("vettid/vms/2/hs-ks", 32),
        )
    }

    @Test
    fun envelopeSealed() {
        val d = VectorDoc.load("envelope_sealed.json")
        val h = VectorDoc.load("hpke.json")
        val raw = d.b64("envelope_b64")
        assertEquals(1668, raw.size)
        assertEquals(d.num("envelope_len").toInt(), raw.size)
        assertEquals(512L, d.num("padded_len"))
        val e = Envelope.parse(raw)
        assertBytes("sender_kid", d.hex("sender_kid_hex"), e.senderKid.bytes())
        assertBytes("recipient_kid", d.hex("recipient_kid_hex"), e.recipientKid.bytes())
        assertBytes("enc in header", h.b64("enc_b64"), raw.copyOfRange(20, 1140))
        val (padded, _) = Envelope.openSealed(e, vault.kem)
        assertBytes("padded", d.hex("padded_hex"), padded)
        assertEquals(d.str("inner"), String(Padding.unpad(padded)))
        val inner = Inner.decode(padded, Mode.SEALED)
        assertEquals(d.str("inner"), String(inner.marshal(Mode.SEALED)))

        // Sender side, byte for byte.
        val (built, _) = deterministic(h.hex("encapsulation_randomness_hex")) {
            Envelope.sealSealed(vault.kem.publicKey, Kid.ANONYMOUS, Inner.encode(inner, Mode.SEALED))
        }
        assertBytes("envelope", raw, built)
    }

    @Test
    fun envelopeSession() {
        val d = VectorDoc.load("envelope_session.json")
        val raw = d.b64("envelope_b64")
        assertEquals(572, raw.size)
        assertEquals(d.num("envelope_len").toInt(), raw.size)
        val inner = Inner.parse(d.str("inner").toByteArray(), Mode.SESSION)
        assertEquals(d.str("inner"), String(inner.marshal(Mode.SESSION)))
        val built = deterministic(d.hex("nonce_hex")) {
            Envelope.sealSession(
                d.hex("k_i2r_hex"),
                Kid(d.hex("sender_kid_hex")),
                Kid(d.hex("recipient_kid_hex")),
                Inner.encode(inner, Mode.SESSION),
            )
        }
        assertBytes("envelope", raw, built)
        val opened = Inner.decode(Envelope.openSession(Envelope.parse(raw), d.hex("k_i2r_hex")), Mode.SESSION)
        assertEquals(1L, opened.seq)
    }

    @Test
    fun handshakeReceivingSide() {
        val d = VectorDoc.load("handshake.json")
        val inp = d.sub("inputs")
        assertBytes("eph seed", inp.hex("initiator_eph_seed_hex"), eph.seed())
        val envInit = d.b64("hs_init_envelope_b64")
        val envResp = d.b64("hs_resp_envelope_b64")
        val envFin = d.b64("hs_fin_envelope_b64")

        val p = PendingInit.open(envInit, KeyLookup { if (it == vault.kem.publicKey.kid) vault.kem else null }, t0)
        assertEquals(d.str("hs_init_body"), String(p.body.marshal()))
        assertEquals(eph.publicKey, p.body.eph)
        val ie = Envelope.parse(envInit)
        assertEquals(ini.kem.publicKey.kid, ie.senderKid)
        val (_, iexp) = Envelope.openSealed(ie, vault.kem)
        val ks = iexp.export("vettid/vms/2/hs-ks", 32)
        assertBytes("K_s", d.hex("K_s_hex"), ks)

        val re = Envelope.parse(envResp)
        assertEquals(eph.publicKey.kid, re.recipientKid)
        assertTrue(re.senderKid.isAnonymous)
        val (rpad, rexp) = Envelope.openSealed(re, eph)
        val ke = rexp.export("vettid/vms/2/hs-ke", 32)
        assertBytes("K_e", d.hex("K_e_hex"), ke)
        val rin = Inner.decode(rpad, Mode.SEALED)
        assertEquals(d.str("hs_resp_inner"), String(rin.marshal(Mode.SEALED)))

        val th1 = Bytes.sha256("vettid/vms/2/th1".toByteArray(), envInit)
        assertBytes("th1", d.hex("th1_hex"), th1)
        assertBytes("th1 (lib)", th1, p.th1)
        val th = Bytes.sha256("vettid/vms/2/th".toByteArray(), envInit, envResp.copyOf(1140))
        assertBytes("th", d.hex("th_hex"), th)
        val sc = Schedule.derive(ks, ke, th1, th)
        assertBytes("prk", d.hex("prk_hex"), sc.prk)
        assertBytes("k_i2r", d.hex("k_i2r_hex"), sc.kI2R)
        assertBytes("k_r2i", d.hex("k_r2i_hex"), sc.kR2I)
        assertBytes("kid_i2r", d.hex("kid_i2r_hex"), sc.kidI2R.bytes())
        assertBytes("kid_r2i", d.hex("kid_r2i_hex"), sc.kidR2I.bytes())
        assertBytes("rk", d.hex("rk_hex"), sc.rk)
        assertBytes("epoch_id", d.hex("epoch_id_hex"), sc.epochId)
        // 0.10.3: the commitment in hs.init, n_R in hs.resp, n_I revealed in hs.fin; the SAS over th and both nonces.
        val nI = inp.hex("n_I_hex")
        val nR = inp.hex("n_R_hex")
        assertBytes("sas_commit", d.hex("sas_commit_hex"), Schedule.sasCommit(nI))
        assertBytes("sas_commit in hs.init", d.hex("sas_commit_hex"), p.body.sasCommit()!!)
        assertTrue(Schedule.checkSasCommit(p.body.sasCommit()!!, nI))
        assertEquals(d.str("sas"), Schedule.sas(sc.prk, th, nI, nR))
        assertBytes("n_R in hs.resp", nR, HsResp.parse(rin.body, Purpose.CONNECTION).sasNonce()!!)

        val sigR = d.b64("sig_R_b64")
        assertTrue(Schedule.verifyResp(vault.ik.publicKey, th, sigR))
        assertTrue(String(rpad).contains(d.str("sig_R_b64")))
        val sigI = d.b64("sig_I_b64")
        assertTrue(Schedule.verifyFin(ini.ik.publicKey, th, sigI))
        assertBytes("sig_R (Ed25519 is deterministic)", sigR, Schedule.signResp(vault.ik, th))
        assertBytes("sig_I (Ed25519 is deterministic)", sigI, Schedule.signFin(ini.ik, th))

        val fe = Envelope.parse(envFin)
        assertEquals(Mode.SESSION, fe.mode)
        assertEquals(sc.kidI2R, fe.recipientKid)
        assertEquals(sc.kidR2I, fe.senderKid)
        assertBytes("hs.fin nonce", inp.hex("fin_nonce_hex"), envFin.copyOfRange(20, 44))
        val fin = Inner.decode(Envelope.openSession(fe, sc.kI2R), Mode.SESSION)
        assertEquals("hs.fin", fin.type)
        assertEquals(1L, fin.seq)
        assertEquals(inp.str("fin_id"), fin.id)
        val hf = HsFin.parse(fin.body, Purpose.CONNECTION)
        assertBytes("hs.fin sig", sigI, hf.sig())
        assertBytes("n_I in hs.fin", nI, hf.sasNonce()!!)
    }

    /** The whole handshake run through Initiator / PendingInit / Responder, byte for byte. */
    @Test
    fun handshakeSendingSide() {
        val d = VectorDoc.load("handshake.json")
        val inp = d.sub("inputs")
        val body = HsInit.parse(d.str("hs_init_body").toByteArray())
        val respBody = StrictJson.parseObject(d.str("hs_resp_inner")).obj("body")
        val now = Timestamps.parseMillis(inp.str("ts"))

        // The scripted randomness (§16): the ephemeral key seed, n_I, then the hs.init encapsulation.
        val initiator = deterministic(inp.hex("initiator_eph_seed_hex"), inp.hex("n_I_hex"), inp.hex("init_encapsulation_randomness_hex")) {
            Initiator.create(
                InitiatorConfig(
                    purpose = Purpose.CONNECTION,
                    ctx = body.ctx,
                    identity = ini.ik,
                    staticKem = ini.kem.publicKey,
                    relay = relayAddr(ini),
                    token = body.token,
                    reconnectToken = body.reconnectToken,
                    responderIk = vault.ik.publicKey,
                    responderEk = vault.kem.publicKey,
                    responderRelayKey = vault.relay!!.publicKey,
                    policy = Policy.VAULT_TO_VAULT,
                    id = inp.str("init_id"),
                    finId = inp.str("fin_id"),
                    now = now,
                ),
            )
        }
        assertBytes("hs.init envelope", d.b64("hs_init_envelope_b64"), initiator.envelope())

        val pending = PendingInit.open(initiator.envelope(), KeyLookup { if (it == vault.kem.publicKey.kid) vault.kem else null }, now)
        // n_R, then the hs.resp encapsulation.
        val (responder, resp) = deterministic(inp.hex("n_R_hex"), inp.hex("resp_encapsulation_randomness_hex")) {
            pending.respond(
                ResponderConfig(
                    identity = vault.ik,
                    token = respBody.string("token"),
                    policy = Policy.VAULT_TO_VAULT,
                    collectSender = ini.relay!!.publicKey,
                    id = inp.str("resp_id"),
                    now = now,
                ),
            )
        }
        assertBytes("hs.resp envelope", d.b64("hs_resp_envelope_b64"), resp)

        val result = deterministic(inp.hex("fin_nonce_hex")) { initiator.handleResp(resp, vault.relay!!.publicKey, now) }
        assertBytes("hs.fin envelope", d.b64("hs_fin_envelope_b64"), result.fin)
        assertBytes("epoch_id", d.hex("epoch_id_hex"), result.epoch.id)
        assertEquals(d.str("sas"), result.sas)

        val fr = responder.handleFin(result.fin, ini.relay!!.publicKey, now)
        val epochR = fr.epoch
        assertEquals("hs.fin", fr.inner.type)
        assertEquals(d.str("sas"), fr.sas)
        assertBytes("epoch ids", result.epoch.id, epochR.id)

        // The new epoch carries traffic both ways.
        val msg = Inner(id = "01JB2Z6V9K3M4N5P6Q7R8S9T13", type = "test.ping", ts = now)
        val back = epochR.open(Envelope.parse(result.epoch.seal(msg)))
        assertEquals(2L, back.seq) // hs.fin was seq 1
        val forth = result.epoch.open(Envelope.parse(epochR.seal(msg)))
        assertEquals(1L, forth.seq)
    }

    @Test
    fun invite() {
        val d = VectorDoc.load("invite.json")
        val blob = d.b64("blob_b64")
        val (built, kb, h) = deterministic(d.hex("k_b_hex"), d.hex("nonce_hex")) { InviteBundle.seal(d.str("bundle_json").toByteArray()) }
        assertBytes("blob", blob, built)
        assertBytes("k_b", d.hex("k_b_hex"), kb)
        assertBytes("h", d.hex("h_hex"), h)
        val q = InviteQr.parseLink(d.str("link"))
        assertEquals(d.str("qr_json"), String(q.marshal()))
        assertEquals(d.str("link"), q.link())
        val b = InviteBundle.open(blob, q, Instant.parse(d.str("now")))
        assertEquals(vault.kem.publicKey, b.vault.kem)
        assertEquals(d.str("bundle_json"), String(b.marshal()))
        assertEquals("Test Vault", b.hintName)
    }

    @Test
    fun altchan() {
        val d = VectorDoc.load("altchan.json")
        val desc = d.str("descriptor").toByteArray()
        assertBytes("descriptor user_data", d.hex("descriptor_user_data_hex"), AltChannel.etkUserData(desc))
        val parsed = Descriptor.parse(desc)
        assertEquals(etk.publicKey, parsed.etk)
        assertEquals(d.str("descriptor"), String(Descriptor.marshal(parsed.instanceId, parsed.etk, parsed.release, parsed.notAfter)))
        for (k in listOf("devatt_enroll", "devatt_unlock")) {
            val c = d.sub(k)
            val want = Bytes.sha256(("vettid/vms/2/devatt" + c.str("request_id") + c.str("vault_id") + c.str("ts")).toByteArray())
            assertBytes(k, c.hex("challenge_hex"), want)
            assertBytes("$k (lib)", want, AltChannel.devattChallenge(c.str("request_id"), c.str("vault_id"), c.str("ts")))
        }

        val f = d.sub("unlock_fields")
        assertEquals(etk.publicKey.kid, Kid.parseHex(f.str("etk_kid_hex")))
        val rel = VectorDoc.load("release.json")
        assertBytes("unlock manifest hash", f.hex("manifest_sha256_hex"), Bytes.sha256(rel.str("manifest").toByteArray()))
        val fields = AltChannel.UnlockFields(
            userGuid = f.str("user_guid"),
            vaultId = f.str("vault_id"),
            requestId = f.str("request_id"),
            ts = f.str("ts"),
            etkKid = Kid.parseHex(f.str("etk_kid_hex")),
            minStateSeq = f.num("min_state_seq"),
            minHeaderSeq = f.num("min_header_seq"),
            pin = f.str("pin"),
            token = f.str("token"),
            manifestSha256 = f.str("manifest_sha256_hex"),
            toPcr0 = f.str("to_pcr0_hex"),
        )
        val signing = AltChannel.unlockSigningString(fields)
        assertEquals(d.str("unlock_signing_string"), signing)
        val sig = d.b64("unlock_sig_b64")
        assertTrue(Ed25519.verifyRaw(ini.ik.publicKey, signing.toByteArray(), sig))
        assertBytes("unlock sig (deterministic)", sig, ini.ik.signRaw(signing.toByteArray()))

        val raw = d.b64("unlock_envelope_b64")
        assertEquals(13444, raw.size)
        assertEquals(AltChannel.REQUEST_ENVELOPE_SIZE, raw.size)
        assertEquals(13444L, d.num("unlock_envelope_len"))
        val (pt, _) = Envelope.openSealed(Envelope.parse(raw), etk)
        assertEquals(d.str("unlock_inner"), String(Padding.unpadFixed(pt, 12_288)))
        val ui = Inner.parse(d.str("unlock_inner").toByteArray(), Mode.SEALED)
        val ub = StrictJson.parseObject(ui.body)
        DeviceAssertion.parse(ub.obj("device_assertion"))
        assertEquals(rel.str("manifest_sha256_hex"), ub.string("manifest_sha256"))
        assertEquals(7L, ub.uint("manifest_serial", 1, 100))
        assertFalse(ub.has("manifest"))

        // Sender side: the request body, its inner and the sealed envelope, byte for byte.
        val req = UnlockRequest(
            userGuid = fields.userGuid,
            vaultId = fields.vaultId,
            requestId = fields.requestId,
            deviceIk = ini.ik.publicKey,
            pin = fields.pin,
            minStateSeq = fields.minStateSeq,
            minHeaderSeq = fields.minHeaderSeq,
            token = fields.token,
            assertion = DeviceAssertion.android(rep(0x2a, 72)),
            manifestSha256 = fields.manifestSha256,
            manifestSerial = 7,
            sig = sig,
        )
        assertEquals(StrictJson.parseObject(d.str("unlock_inner")).obj("body").raw, String(req.marshal()))
        val built = deterministic(d.hex("unlock_encapsulation_randomness_hex")) {
            val ts = Timestamps.parseMillis(fields.ts)
            AltChannel.sealRequest(etk.publicKey, AltChannel.TYPE_UNLOCK, fields.requestId, ts, req.marshal())
        }
        assertBytes("unlock envelope", raw, built)
    }

    @Test
    fun releaseApprovalString() {
        val a = VectorDoc.load("release.json").sub("approval")
        val s = AltChannel.approvalSigningString(
            a.str("vault_id"),
            a.str("request_id"),
            a.str("from_pcr0_hex"),
            a.str("to_pcr0_hex"),
            a.num("to_release"),
            a.num("manifest_serial"),
        )
        assertEquals(a.str("signing_string"), s)
        assertBytes("approval sha256", a.hex("signing_string_sha256_hex"), Bytes.sha256(s.toByteArray()))
        val u = VectorDoc.load("release.json").sub("unlock_with_update")
        assertTrue(Ed25519.verifyRaw(ini.ik.publicKey, u.str("signing_string").toByteArray(), u.b64("sig_b64")))
        assertBytes("unlock-with-update sig", u.b64("sig_b64"), ini.ik.signRaw(u.str("signing_string").toByteArray()))
    }
}
