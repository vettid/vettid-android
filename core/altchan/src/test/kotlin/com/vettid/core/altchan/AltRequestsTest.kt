// Fixtures (spec vectors, JSON bodies of the fake relay, member API and vault) stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.altchan

import com.vettid.core.attestation.manifest.ManifestVerifier
import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Ed25519
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.altchan.AltChannel
import com.vettid.core.crypto.envelope.Timestamps
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.crypto.hpke.KemPrivateKey
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.session.Mailbox
import com.vettid.core.crypto.session.RelayAddr
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AltRequestsTest {
    private val now = Instant.parse("2026-10-04T12:00:00.123456Z")
    private val manifest = ManifestVerifier(listOf(TestSupport.manifestKey)).verify(TestSupport.served(TestSupport.manifestBytes(7)))
    private val enclave = TestSupport.Enclave()
    private val ik = Ed25519PrivateKey.generate()
    private val kem = KemPrivateKey.generate()
    private val relayKey = Ed25519PrivateKey.generate()
    private val app = AppIdentity(ik.publicKey, kem.publicKey, RelayAddr("https://relay.vettid.test", Mailbox.id(relayKey.publicKey), relayKey.publicKey), "phone")

    @Test
    fun enrollIsSealedPaddedAndBound() {
        val att = TestSupport.SoftAttester()
        val built = AltRequests.buildEnroll("guid-1", "246802", enclave.verified(manifest), manifest, att, app, "v4.public.open", AltState(), now)
        val req = built.request
        assertEquals(AltChannel.REQUEST_ENVELOPE_SIZE, req.envelope().size) // 13,444
        assertEquals(enclave.etk.publicKey.kid.toString(), req.etkKid)
        assertEquals(manifest.sha256Hex, req.manifestSha256)
        val inner = enclave.open(req.envelope())
        assertEquals(AltChannel.TYPE_ENROLL, inner.type)
        assertEquals(req.requestId, inner.id)
        val o = StrictJson.parseObject(inner.body)
        assertEquals("guid-1", o.string("user_guid"))
        assertEquals(req.requestId, o.string("request_id"))
        assertEquals("246802", o.string("pin"))
        assertEquals(manifest.serial, o.uint("manifest_serial", 1, 1 shl 40))
        assertArrayEquals(ik.publicKey, o.obj("app").base64("ik"))
        assertEquals("v4.public.open", o.obj("app").string("open_token"))
        // The attested challenge binds request_id, "" and ts (§11.7).
        assertArrayEquals(AltChannel.devattChallenge(req.requestId, "", Timestamps.formatMillis(inner.ts)), att.lastChallenge)
        // The state to keep until vault.enrolled.
        assertArrayEquals(o.base64("nonce"), built.state.enrollNonce)
        assertEquals(TestSupport.pcr0 + TestSupport.pcr1 + TestSupport.pcr2, built.state.enrollPcrs)
        val after = AltRequests.applyEnrolled(built.state, 4)
        assertEquals(TestSupport.pcr0, after.release)
        assertEquals(3, after.releaseNumber)
        assertEquals(4, after.stateSeq)
        assertNull(after.enrollNonce)
    }

    @Test
    fun unlockCarriesSignatureAssertionAndFloors() {
        val att = TestSupport.SoftAttester()
        val state = AltState(release = TestSupport.pcr0, releaseNumber = 3, stateSeq = 9, headerSeq = mapOf(TestSupport.pcr0 to 5L), manifestSerial = 7)
        val b = AltRequests.buildUnlock("guid-1", "0123456789abcdef0123456789abcdef", "246802", enclave.verified(manifest), manifest, att, ik, "v4.public.tok", state, UnlockOptions(), now)
        assertEquals(AltChannel.REQUEST_ENVELOPE_SIZE, b.request.envelope().size)
        assertFalse(b.releaseChanged)
        val inner = enclave.open(b.request.envelope())
        val o = StrictJson.parseObject(inner.body)
        assertEquals(9, o.uint("min_state_seq", 0, 100))
        assertEquals(5, o.uint("min_header_seq", 0, 100))
        val tss = Timestamps.formatMillis(inner.ts)
        val signing = AltChannel.unlockSigningString(
            AltChannel.UnlockFields(
                "guid-1", "0123456789abcdef0123456789abcdef", b.request.requestId, tss, enclave.etk.publicKey.kid, 9, 5, "246802", "v4.public.tok",
                manifest.sha256Hex,
            ),
        )
        assertTrue(Ed25519.verifyRaw(ik.publicKey, signing.toByteArray(), o.base64("sig")))
        val challenge = AltChannel.devattChallenge(b.request.requestId, "0123456789abcdef0123456789abcdef", tss)
        assertTrue(att.verify(challenge, Base64s.decodeStd(o.obj("device_assertion").string("sig"))))
        assertEquals(PendingUnlock(b.request.requestId, TestSupport.pcr0, 3, 7, 0), b.pending)
    }

    @Test
    fun unlockRefusesOlderReleasesAndManifests() {
        val att = TestSupport.SoftAttester()
        val newer = AltState(release = "b1".repeat(48), releaseNumber = 4)
        val e = assertThrows(AltRefusedException::class.java) {
            AltRequests.buildUnlock("g", "v", "246802", enclave.verified(manifest), manifest, att, ik, "t", newer, UnlockOptions(), now)
        }
        assertEquals(AltRefusedException.Reason.ROLLBACK_RELEASE, e.reason)
        val seen = AltState(manifestSerial = 8)
        assertEquals(
            AltRefusedException.Reason.MANIFEST_OLDER,
            assertThrows(AltRefusedException::class.java) {
                AltRequests.buildUnlock("g", "v", "246802", enclave.verified(manifest), manifest, att, ik, "t", seen, UnlockOptions(), now)
            }.reason,
        )
        // Abandoning an unconfirmed move back to the previous release is allowed (§11.10.4).
        val moved = AltState(release = "b1".repeat(48), releaseNumber = 4, previousRelease = TestSupport.pcr0, previousReleaseNumber = 3)
        val b = AltRequests.buildUnlock("g", "v", "246802", enclave.verified(manifest), manifest, att, ik, "t", moved, UnlockOptions(abandon = true), now)
        val o = StrictJson.parseObject(enclave.open(b.request.envelope()).body)
        assertEquals(TestSupport.pcr0, o.obj("release_update").string("to"))
        assertTrue(b.releaseChanged)
    }

    @Test
    fun applyUnlockMovesFloorsAndRecordsMoves() {
        val p = PendingUnlock(Ulid.new(), TestSupport.pcr0, 3, 7, 4)
        val s0 = AltState(release = TestSupport.pcr0, releaseNumber = 3, stateSeq = 2, manifestSerial = 7)
        val fail = AltRequests.applyUnlock(s0, p, UnlockResult(ok = false, code = "bad_pin", headerSeq = 6))
        assertEquals(6L, fail.headerSeq[TestSupport.pcr0])
        val to = "c4".repeat(48)
        val moved = AltRequests.applyUnlock(
            s0, p,
            UnlockResult(true, stateSeq = 10, headerSeq = 1, release = TestSupport.pcr0, releaseNumber = 3, releaseStatus = "active", manifestSerial = 8, update = UnlockResult.Update(to, "moved", null)),
        )
        assertEquals(to, moved.release)
        assertEquals(4, moved.releaseNumber)
        assertEquals(TestSupport.pcr0, moved.previousRelease)
        assertEquals(10, moved.stateSeq)
        assertEquals(8, moved.manifestSerial)
        assertEquals(1L, moved.headerSeq[to])
        assertThrows(AltResultException::class.java) {
            AltRequests.applyUnlock(s0, p, UnlockResult(true, release = to, releaseNumber = 4, releaseStatus = "active"))
        }
    }

    @Test
    fun resultsOpenOnlyForTheirRequest() {
        val rid = Ulid.new()
        val raw = TestSupport.sealResult(kem.publicKey, AltResults.TYPE_ENROLL_RESULT, rid, """{"ok":true,"vault_id":"abc"}""")
        assertEquals(EnrollResult(true, null, "abc"), EnrollResult.parse(AltResults.open(raw, kem, AltResults.TYPE_ENROLL_RESULT, rid)))
        assertThrows(AltResultException::class.java) { AltResults.open(raw, kem, AltResults.TYPE_ENROLL_RESULT, Ulid.new()) }
        assertThrows(AltResultException::class.java) { AltResults.open(raw, kem, AltResults.TYPE_UNLOCK_RESULT, rid) }
        assertThrows(AltResultException::class.java) { AltResults.open(raw.copyOf(100), kem, AltResults.TYPE_ENROLL_RESULT, rid) }
        assertThrows(AltResultException::class.java) { AltResults.open(raw, KemPrivateKey.generate(), AltResults.TYPE_ENROLL_RESULT, rid) }
        val u = UnlockResult.parse(
            """{"ok":true,"state_seq":3,"header_seq":2,"token":"t","release":"${TestSupport.pcr0}","release_number":3,"release_status":"active","manifest_serial":7}""".toByteArray(),
        )
        assertEquals(3, u.stateSeq)
        assertEquals("t", u.token)
        assertNull(u.credentialBackup) // absent: an older vault (before 0.10.6)
        val ok = """{"ok":true,"state_seq":3,"header_seq":2,"release":"${TestSupport.pcr0}","release_number":3,"release_status":"active",""" +
            """"manifest_serial":7,"vault_bundle":"AQ==","credential_backup":"""
        assertEquals(true, UnlockResult.parse((ok + "true}").toByteArray()).credentialBackup)
        assertEquals(false, UnlockResult.parse((ok + "false}").toByteArray()).credentialBackup)
        assertThrows(AltResultException::class.java) { UnlockResult.parse((ok + "1}").toByteArray()) }
        val f = UnlockResult.parse("""{"ok":false,"code":"backoff","header_seq":4,"retry_after":30}""".toByteArray())
        assertEquals("backoff", f.code)
        assertEquals(30, f.retryAfterSeconds)
        assertEquals(RecoveryResult(false, "bad_code"), RecoveryResult.parse("""{"ok":false,"code":"bad_code"}""".toByteArray()))
    }

    @Test
    fun recoveryQrAndRegister() {
        val rid = Ulid.new()
        val qr = RecoveryCode.parseQr("""{"v":1,"t":"r","vault_id":"0123456789abcdef0123456789abcdef","recovery_id":"$rid","code":"${"K".repeat(32)}"}""".toByteArray())
        assertEquals(rid, qr.recoveryId)
        assertThrows(AltResultException::class.java) { RecoveryCode.parseQr("""{"v":1,"t":"x","vault_id":"a","recovery_id":"$rid","code":"c"}""".toByteArray()) }
        val att = TestSupport.SoftAttester()
        val req = AltRequests.buildRecoveryRegister("guid-1", qr, enclave.verified(manifest), att, app, now)
        assertNull(req.manifestSha256)
        assertEquals(AltChannel.REQUEST_ENVELOPE_SIZE, req.envelope().size)
        val inner = enclave.open(req.envelope())
        assertEquals(AltRequests.TYPE_RECOVERY_REGISTER, inner.type)
        val o = StrictJson.parseObject(inner.body)
        assertEquals(qr.code, o.string("code"))
        assertEquals("phone", o.obj("app").string("name"))
        assertArrayEquals(AltChannel.devattChallenge(req.requestId, qr.vaultId, Timestamps.formatMillis(inner.ts)), att.lastChallenge)
    }

    @Test
    fun altStateRoundTrips() {
        val s = AltState("a", 3, "b", 2, 7, 9, mapOf("a" to 4L, "b" to 1L), byteArrayOf(1, 2), "pcrs", 3)
        assertEquals(s, AltState.parse(StrictJson.parseObject(s.marshal())))
    }
}
