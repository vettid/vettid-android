package com.vettid.core.altchan

import com.vettid.core.attestation.VerifiedEnclave
import com.vettid.core.attestation.manifest.ReleaseManifest
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.Randomness
import com.vettid.core.crypto.altchan.AltChannel
import com.vettid.core.crypto.altchan.EnrollRequest
import com.vettid.core.crypto.altchan.UnlockRequest
import com.vettid.core.crypto.envelope.Timestamps
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.crypto.hpke.KemPublicKey
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.session.RelayAddr
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit

/** The app refuses to build a request (§11.10.6, §13.2). */
class AltRefusedException(val reason: Reason) : IOException("alternate channel: $reason") {
    enum class Reason {
        /** Never send a PIN to an older release than the last one unlocked into. */
        ROLLBACK_RELEASE,

        /** The manifest is older than one already seen. */
        MANIFEST_OLDER,

        /** The device is not paired with a vault. */
        NOT_PAIRED,
    }
}

/** What the app shares about itself in an enrollment or recovery: identity and KEM keys, relay address, name. */
class AppIdentity(ik: ByteArray, val kem: KemPublicKey, val relay: RelayAddr, val name: String) {
    private val ik = ik.copyOf()

    fun ik(): ByteArray = ik.copyOf()
}

/** An enrollment built and the state the app keeps until vault.enrolled (§11.3 "App state"). */
class BuiltEnroll(val request: SealedRequest, val state: AltState)

/** An unlock built, and what the app keeps until its result (release, number, serial, the approved target). */
class BuiltUnlock(val request: SealedRequest, val pending: PendingUnlock, val releaseChanged: Boolean)

/** Kept between building an unlock and opening its result. */
data class PendingUnlock(val requestId: String, val release: String, val number: Long, val serial: Long, val toNumber: Long)

/** A member-approved release update (§11.10.3). */
data class Approval(val toPcr0: String, val toRelease: Long)

/** The less common unlock paths (§11.10.4, §11.11.4). */
data class UnlockOptions(val approve: Approval? = null, val abandon: Boolean = false, val cancelRecovery: Boolean = false)

/**
 * Builds the sealed requests of the alternate channel, as vettid-vault's
 * reference client does (`BuildEnroll`, `BuildUnlock`,
 * `BuildRecoveryRegister`). Pure: keys, tokens and state come in, sealed
 * bytes and the state to keep come out. Every request is padded to exactly
 * 12,288 bytes and sealed to the enclave's ETK (13,444 bytes).
 */
object AltRequests {
    const val TYPE_RECOVERY_REGISTER = "vault.recovery.register"

    private fun ts(now: Instant): Instant = now.truncatedTo(ChronoUnit.MILLIS)

    /**
     * vault.enroll (§11.3): a fresh device key attested over the request's
     * challenge (vault_id empty), a fresh 32-byte nonce, the app's open token
     * for the vault's first deposit, and the verified manifest by hash and serial.
     */
    fun buildEnroll(
        userGuid: String,
        pin: String,
        enclave: VerifiedEnclave,
        manifest: ReleaseManifest,
        attester: Attester,
        app: AppIdentity,
        openToken: String,
        state: AltState,
        now: Instant,
    ): BuiltEnroll {
        val t = ts(now)
        val rid = Ulid.new(t)
        val challenge = AltChannel.devattChallenge(rid, "", Timestamps.formatMillis(t))
        val attest = attester.attest(challenge)
        val nonce = Randomness.bytes(NONCE_SIZE)
        val body = EnrollRequest(
            userGuid, rid, nonce, pin, app.ik(), app.kem, app.relay, openToken, app.name, attest, manifest.sha256Hex, manifest.serial,
        ).marshal()
        val env = AltChannel.sealRequest(enclave.descriptor.etk, AltChannel.TYPE_ENROLL, rid, t, body)
        val next = state.copy(enrollNonce = nonce, enrollPcrs = enclave.measurements.joined(), enrollNumber = enclave.release.number)
        return BuiltEnroll(SealedRequest(rid, enclave.descriptor.kid.toString(), env, manifest.sha256Hex), next)
    }

    /**
     * vault.unlock (§11.4): rollback minimums from [state], a fresh standing
     * [token] for this device's mailbox (an open token while recovering), the
     * device assertion over the challenge, an optional release approval, and
     * the Ed25519 signature by the app's identity key [ik].
     */
    @Suppress("LongParameterList")
    fun buildUnlock(
        userGuid: String,
        vaultId: String,
        pin: String,
        enclave: VerifiedEnclave,
        manifest: ReleaseManifest,
        attester: Attester,
        ik: Ed25519PrivateKey,
        token: String,
        state: AltState,
        options: UnlockOptions,
        now: Instant,
    ): BuiltUnlock {
        if (manifest.serial < state.manifestSerial) throw AltRefusedException(AltRefusedException.Reason.MANIFEST_OLDER)
        val rel = enclave.release
        val changed = state.release.isNotEmpty() && state.release != rel.pcr0
        if (state.releaseNumber != 0L && rel.number < state.releaseNumber) {
            val abandoning = options.abandon && rel.pcr0 == state.previousRelease
            if (!abandoning) throw AltRefusedException(AltRefusedException.Reason.ROLLBACK_RELEASE)
        }
        val t = ts(now)
        val tss = Timestamps.formatMillis(t)
        val rid = Ulid.new(t)
        val challenge = AltChannel.devattChallenge(rid, vaultId, tss)
        val assertion = attester.assert(challenge)
        val upd = when {
            options.abandon -> Approval(rel.pcr0, rel.number)
            else -> options.approve
        }
        val update = upd?.let {
            val s = AltChannel.approvalSigningString(vaultId, rid, rel.pcr0, it.toPcr0, it.toRelease, manifest.serial)
            UnlockRequest.ReleaseUpdate(it.toPcr0, it.toRelease, attester.assert(s.toByteArray()))
        }
        val minHeader = state.headerSeq[rel.pcr0] ?: 0
        val signing = AltChannel.unlockSigningString(
            AltChannel.UnlockFields(
                userGuid = userGuid, vaultId = vaultId, requestId = rid, ts = tss, etkKid = enclave.descriptor.kid,
                minStateSeq = state.stateSeq, minHeaderSeq = minHeader, pin = pin, token = token,
                manifestSha256 = manifest.sha256Hex, toPcr0 = upd?.toPcr0 ?: "", cancelRecovery = options.cancelRecovery,
            ),
        )
        val body = UnlockRequest(
            userGuid, vaultId, rid, ik.publicKey, pin, state.stateSeq, minHeader, token, assertion, manifest.sha256Hex, manifest.serial,
            update, options.cancelRecovery, ik.signRaw(signing.toByteArray()),
        ).marshal()
        val env = AltChannel.sealRequest(enclave.descriptor.etk, AltChannel.TYPE_UNLOCK, rid, t, body)
        return BuiltUnlock(
            SealedRequest(rid, enclave.descriptor.kid.toString(), env, manifest.sha256Hex),
            PendingUnlock(rid, rel.pcr0, rel.number, manifest.serial, upd?.toRelease ?: 0),
            changed,
        )
    }

    /**
     * Applies an opened unlock result to the app state (§11.4, §11.10.4):
     * floors only move up; a result from another release than the attested
     * one is refused; a move records the previous release for abandonment.
     */
    fun applyUnlock(state: AltState, p: PendingUnlock, r: UnlockResult): AltState {
        val hs = state.headerSeq.toMutableMap()
        if (!r.ok) {
            if (r.headerSeq > (hs[p.release] ?: 0)) hs[p.release] = r.headerSeq
            return state.copy(headerSeq = hs)
        }
        if (r.release != p.release || r.releaseNumber != p.number) throw AltResultException("result from another release")
        var s = state.copy(stateSeq = maxOf(state.stateSeq, r.stateSeq), manifestSerial = maxOf(state.manifestSerial, r.manifestSerial))
        var release = p.release
        var number = p.number
        when (r.update?.result) {
            "moved" -> {
                s = s.copy(previousRelease = p.release, previousReleaseNumber = p.number)
                release = r.update.to
                number = p.toNumber
            }
            "abandoned" -> s = s.copy(previousRelease = "", previousReleaseNumber = 0)
        }
        if (r.headerSeq > (hs[release] ?: 0)) hs[release] = r.headerSeq
        return s.copy(headerSeq = hs, release = release, releaseNumber = if (number != 0L) number else s.releaseNumber)
    }

    /**
     * Applies vault.enrolled to the app state (§11.3 "App state"): the
     * release the enrollment was sealed to, and its state_seq.
     */
    fun applyEnrolled(state: AltState, stateSeq: Long?): AltState {
        var s = state
        if (s.enrollPcrs.length >= PCR_HEX) s = s.copy(release = s.enrollPcrs.substring(0, PCR_HEX), releaseNumber = s.enrollNumber)
        if (stateSeq != null && stateSeq > s.stateSeq) s = s.copy(stateSeq = stateSeq)
        return s.copy(enrollNonce = null, enrollPcrs = "", enrollNumber = 0)
    }

    /**
     * vault.recovery.register (§11.11.3): the code from the portal's QR, a
     * fresh device key attested over the challenge (with the vault id).
     */
    fun buildRecoveryRegister(
        userGuid: String,
        code: RecoveryCode,
        enclave: VerifiedEnclave,
        attester: Attester,
        app: AppIdentity,
        now: Instant,
    ): SealedRequest {
        val t = ts(now)
        val rid = Ulid.new(t)
        val attest = attester.attest(AltChannel.devattChallenge(rid, code.vaultId, Timestamps.formatMillis(t)))
        val appObj = JsonBuilder().base64("ik", app.ik()).base64("kem", app.kem.bytes())
            .raw("relay", relayJson(app.relay))
            .string("name", app.name).raw("device_attest", attest.marshal()).build()
        val body = JsonBuilder().string("user_guid", userGuid).string("vault_id", code.vaultId).string("request_id", rid)
            .string("recovery_id", code.recoveryId).string("code", code.code).raw("app", appObj).bytes()
        val env = AltChannel.sealRequest(enclave.descriptor.etk, TYPE_RECOVERY_REGISTER, rid, t, body)
        return SealedRequest(rid, enclave.descriptor.kid.toString(), env, null)
    }

    private fun relayJson(r: RelayAddr): String =
        JsonBuilder().string("url", r.url).string("mailbox", r.mailbox).base64("pk", r.pk()).build()

    private const val NONCE_SIZE = 32
    private const val PCR_HEX = 96
}
