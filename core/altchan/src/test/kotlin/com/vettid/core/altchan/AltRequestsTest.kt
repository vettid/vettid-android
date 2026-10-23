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
    private val apiKey = ByteArray(91) { it.toByte() } // an app key's SPKI DER (§11.12.2); its bytes do not matter here
    private val app = AppIdentity(
        ik.publicKey, kem.publicKey, RelayAddr("https://relay.vettid.test", Mailbox.id(relayKey.publicKey), relayKey.publicKey), "phone", apiKey,
    )

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
        // §11.3 (0.15.0): `app.api_key`, standard base64 of the SPKI DER, after `device_attest`.
        assertArrayEquals(apiKey, o.obj("app").base64("api_key"))
        val appJson = String(o.obj("app").rawBytes())
        assertTrue(appJson.indexOf("\"device_attest\"") < appJson.indexOf("\"api_key\""))
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
        assertNull(after.pendingEnroll)
    }

    /**
     * (A6 audit N3) The enrollment request is outstanding from its build: its id, its time and its nonce are in the stored
     * state (and survive a restart), until `vault.enrolled` is applied or it is dropped.
     */
    @Test
    fun anEnrollmentRequestIsKeptOutstandingAcrossARestart() {
        val built = AltRequests.buildEnroll("guid-1", "246802", enclave.verified(manifest), manifest, TestSupport.SoftAttester(), app, "v4.public.open", AltState(), now)
        val p = built.state.pendingEnroll!!
        assertEquals(built.request.requestId, p.requestId)
        assertEquals(now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toEpochMilli(), p.postedMs)
        assertTrue(!p.answered)
        val restored = AltState.parse(StrictJson.parseObject(built.state.copy(enrollAnswered = true).marshal().toByteArray()))
        assertEquals(p.copy(answered = true), restored.pendingEnroll)
        assertArrayEquals(built.state.enrollNonce, restored.enrollNonce)
        assertNull(restored.withoutEnroll().pendingEnroll)
        assertNull(restored.withoutEnroll().enrollNonce)
        // A state of an earlier version (a nonce, no request id) has nothing to resume.
        assertNull(AltState.parse(StrictJson.parseObject(built.state.copy(enrollRequestId = "").marshal().toByteArray())).pendingEnroll)
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

    private val vid = "0123456789abcdef0123456789abcdef"

    private fun unlockBody(att: Attester, options: UnlockOptions, state: AltState = AltState(release = TestSupport.pcr0, releaseNumber = 3)): Pair<BuiltUnlock, com.vettid.core.crypto.json.JsonObject> {
        val b = AltRequests.buildUnlock("guid-1", vid, "246802", enclave.verified(manifest), manifest, att, ik, "v4.public.tok", state, options, now)
        assertEquals(AltChannel.REQUEST_ENVELOPE_SIZE, b.request.envelope().size)
        return b to StrictJson.parseObject(enclave.open(b.request.envelope()).body)
    }

    /** VAULT-MESSAGING 0.27.0 §11.7.1: the bound key's chain rides in `device_assertion`, outside every signing string. */
    @Test
    fun unlockCarriesTheChainOnlyWhenAsked() {
        val leaf = ByteArray(700) { 0x31 }
        val inter = ByteArray(650) { 0x32 }
        val root = ByteArray(560) { 0x33 }
        val att = TestSupport.SoftAttester().apply { keystoreChain = listOf(leaf, inter, root) }
        // Not asked for (the vault has shown this app's signer): no member at all.
        val (_, plain) = unlockBody(att, UnlockOptions())
        assertFalse(plain.obj("device_assertion").members.containsKey("chain"))
        // Asked for: exactly the Keystore's chain, leaf first.
        val (b, o) = unlockBody(att, UnlockOptions(sendChain = true))
        val chain = o.obj("device_assertion").array("chain").map { Base64s.decodeStd((it as com.vettid.core.crypto.json.JsonString).value) }
        assertEquals(3, chain.size)
        assertArrayEquals(leaf, chain[0])
        assertArrayEquals(inter, chain[1])
        assertArrayEquals(root, chain[2])
        // The assertion and the identity signature cover what they covered before: the chain is in neither.
        val tss = Timestamps.formatMillis(enclave.open(b.request.envelope()).ts)
        assertTrue(att.verify(AltChannel.devattChallenge(b.request.requestId, vid, tss), Base64s.decodeStd(o.obj("device_assertion").string("sig"))))
        val signing = AltChannel.unlockSigningString(
            AltChannel.UnlockFields("guid-1", vid, b.request.requestId, tss, enclave.etk.publicKey.kid, 0, 0, "246802", "v4.public.tok", manifest.sha256Hex),
        )
        assertTrue(Ed25519.verifyRaw(ik.publicKey, signing.toByteArray(), o.base64("sig")))
    }

    /** §11.7.1: never on the release approval, which is the other `device_assertion` object of an unlock. */
    @Test
    fun aReleaseApprovalNeverCarriesTheChain() {
        val att = TestSupport.SoftAttester().apply { keystoreChain = listOf(ByteArray(700) { 1 }, ByteArray(500) { 2 }) }
        val (_, o) = unlockBody(att, UnlockOptions(approve = Approval("b4".repeat(48), 4), sendChain = true))
        assertTrue(o.obj("device_assertion").members.containsKey("chain"))
        val approval = o.obj("release_update").obj("approval")
        assertFalse(approval.members.containsKey("chain"))
        assertEquals(setOf("platform", "sig"), approval.members.keys)
    }

    /** A chain the request cannot carry never stops the unlock: it goes without (a vault that needs one answers so). */
    @Test
    fun anUnusableChainIsLeftOut() {
        val none = TestSupport.SoftAttester()
        assertFalse(unlockBody(none, UnlockOptions(sendChain = true)).second.obj("device_assertion").members.containsKey("chain"))
        val eleven = TestSupport.SoftAttester().apply { keystoreChain = List(11) { ByteArray(100) { 7 } } }
        assertFalse(unlockBody(eleven, UnlockOptions(sendChain = true)).second.obj("device_assertion").members.containsKey("chain"))
        val empty = TestSupport.SoftAttester().apply { keystoreChain = emptyList() }
        assertFalse(unlockBody(empty, UnlockOptions(sendChain = true)).second.obj("device_assertion").members.containsKey("chain"))
        // 12,000 bytes of DER do not fit the request's 12,288 padded bytes (vettid-vault's chainsize_test).
        val huge = TestSupport.SoftAttester().apply { keystoreChain = List(10) { ByteArray(1200) { 9 } } }
        assertFalse(unlockBody(huge, UnlockOptions(sendChain = true)).second.obj("device_assertion").members.containsKey("chain"))
        // A chain as phones produce it (five certificates, about 4.5 kB) fits, with a release approval beside it.
        val real = TestSupport.SoftAttester().apply { keystoreChain = listOf(1100, 700, 750, 900, 1380).map { ByteArray(it) { 5 } } }
        val (_, o) = unlockBody(real, UnlockOptions(approve = Approval("b4".repeat(48), 4), cancelRecovery = true, sendChain = true))
        assertEquals(5, o.obj("device_assertion").array("chain").size)
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

    /**
     * ANDROID-PLAN 0.1.54: with the marker of an unconfirmed move stored, an unlock routed to the release the move left
     * is still refused by the rollback check (the PIN goes there only through the way back, `abandon`).
     */
    @Test
    fun theMarkerDoesNotOpenTheRollbackCheck() {
        val att = TestSupport.SoftAttester()
        val moved = AltState(release = "b1".repeat(48), releaseNumber = 4, previousRelease = TestSupport.pcr0, previousReleaseNumber = 3, moveFailed = true)
        val e = assertThrows(AltRefusedException::class.java) {
            AltRequests.buildUnlock("g", "v", "246802", enclave.verified(manifest), manifest, att, ik, "t", moved, UnlockOptions(), now)
        }
        assertEquals(AltRefusedException.Reason.ROLLBACK_RELEASE, e.reason)
        assertThrows(AltRefusedException::class.java) {
            AltRequests.buildUnlock(
                "g", "v", "246802", enclave.verified(manifest), manifest, att, ik, "t", moved, UnlockOptions(approve = Approval(TestSupport.pcr0, 3)), now,
            )
        }
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

    /**
     * Release R (VAULT-MESSAGING 0.33.0 §11.14.6): an `ok` recovery-open result moves the floors like an unlock
     * result, and its release with its header_seq becomes what the next unlock names (`min_header_seq`).
     */
    @Test
    fun applyRecoveryOpenRecordsTheReleaseAndItsHeaderSeq() {
        val rel = "d5".repeat(48)
        val s0 = AltState(release = TestSupport.pcr0, releaseNumber = 3, stateSeq = 9, headerSeq = mapOf(TestSupport.pcr0 to 4L))
        val s1 = AltRequests.applyRecoveryOpen(s0, stateSeq = 12, headerSeq = 21, release = rel, releaseNumber = 13)
        assertEquals(12, s1.stateSeq)
        assertEquals(rel, s1.release)
        assertEquals(13, s1.releaseNumber)
        assertEquals(21L, s1.headerSeq[rel])
        assertEquals(4L, s1.headerSeq[TestSupport.pcr0])
        // Floors never move down.
        val s2 = AltRequests.applyRecoveryOpen(s1, stateSeq = 5, headerSeq = 2, release = rel, releaseNumber = 13)
        assertEquals(12, s2.stateSeq)
        assertEquals(21L, s2.headerSeq[rel])
        // Without a well-formed release, only the state floor moves.
        val s3 = AltRequests.applyRecoveryOpen(s0, stateSeq = 15, headerSeq = 30, release = "", releaseNumber = 0)
        assertEquals(15, s3.stateSeq)
        assertEquals(TestSupport.pcr0, s3.release)
        assertEquals(s0.headerSeq, s3.headerSeq)
    }

    /** Release R: the unlock secret goes into the sealed unlock, and the caller's array is left for the caller to zero. */
    @Test
    fun unlockCarriesTheSecretAndLeavesTheCallersCopy() {
        val secret = ByteArray(32) { 0x42 }
        val (_, o) = unlockBody(TestSupport.SoftAttester(), UnlockOptions(unlockSecret = secret))
        assertArrayEquals(ByteArray(32) { 0x42 }, o.base64("unlock_secret"))
        assertArrayEquals(ByteArray(32) { 0x42 }, secret)
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
        // The inner's ts (the enclave's clock) is available: a vault.locking not newer than an unlock result is stale.
        val ts = java.time.Instant.parse("2026-10-10T14:09:35.120Z")
        val sealedAt = TestSupport.sealResult(kem.publicKey, AltResults.TYPE_ENROLL_RESULT, rid, "{\"ok\":true}", ts)
        assertEquals(ts, AltResults.openInner(sealedAt, kem, AltResults.TYPE_ENROLL_RESULT, rid).ts)
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
        val qr = RecoveryCode.parseQr(
            """{"v":1,"t":"r","api":"https://account.vettid.org","vault_id":"0123456789abcdef0123456789abcdef","recovery_id":"$rid","code":"${"K".repeat(32)}"}""".toByteArray(),
        )
        assertEquals(rid, qr.recoveryId)
        assertEquals("https://account.vettid.org", qr.api)
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
        assertArrayEquals(apiKey, o.obj("app").base64("api_key")) // §11.11.3 (0.15.0)
        assertArrayEquals(AltChannel.devattChallenge(req.requestId, qr.vaultId, Timestamps.formatMillis(inner.ts)), att.lastChallenge)
    }

    @Test
    fun altStateRoundTrips() {
        val s = AltState("a", 3, "b", 2, 7, 9, mapOf("a" to 4L, "b" to 1L), byteArrayOf(1, 2), "pcrs", 3)
        assertEquals(s, AltState.parse(StrictJson.parseObject(s.marshal())))
    }
}
