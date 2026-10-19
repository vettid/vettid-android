package com.vettid.core.altchan

import com.vettid.core.attestation.VerifiedEnclave
import com.vettid.core.attestation.manifest.ReleaseManifest
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.Randomness
import com.vettid.core.crypto.altchan.AltChannel
import com.vettid.core.crypto.altchan.DeviceAssertion
import com.vettid.core.crypto.altchan.DeviceAttest
import com.vettid.core.crypto.altchan.EnrollRequest
import com.vettid.core.crypto.altchan.Passwords
import com.vettid.core.crypto.altchan.RecoveryOpen
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

        /**
         * (STAGING-TIME-SCALE §4 item 4) The vault's release has a time scale this build does not accept (a production
         * build: any but 1): nothing is sent to it.
         */
        TIME_SCALE,
    }
}

/** What the app shares about itself in an enrollment or recovery: identity and KEM keys, relay address, name. */
class AppIdentity(ik: ByteArray, val kem: KemPublicKey, val relay: RelayAddr, val name: String, apiKey: ByteArray? = null) {
    private val ik = ik.copyOf()
    private val apiKey = apiKey?.copyOf()

    fun ik(): ByteArray = ik.copyOf()

    /** The app key's SPKI DER (§11.12.2, 0.15.0): `app.api_key` in an enrollment or a recovery's register. */
    fun apiKey(): ByteArray? = apiKey?.copyOf()
}

/** An enrollment built and the state the app keeps until vault.enrolled (§11.3 "App state"). */
class BuiltEnroll(val request: SealedRequest, val state: AltState)

/** An unlock built, and what the app keeps until its result (release, number, serial, the approved target). */
class BuiltUnlock(val request: SealedRequest, val pending: PendingUnlock, val releaseChanged: Boolean)

/** Kept between building an unlock and opening its result. */
data class PendingUnlock(val requestId: String, val release: String, val number: Long, val serial: Long, val toNumber: Long)

/** A member-approved release update (§11.10.3). */
data class Approval(val toPcr0: String, val toRelease: Long)

/**
 * The less common unlock paths (§11.10.4, §11.11.4). [sendChain] (0.27.0, §11.7.1): the unlock's
 * `device_assertion` carries the device key's certificate chain, which the device asks for while the vault has
 * not shown it a signer for this app (`device.list`); the callers above the device never set it.
 */
data class UnlockOptions(
    val approve: Approval? = null,
    val abandon: Boolean = false,
    val cancelRecovery: Boolean = false,
    val sendChain: Boolean = false,
    /**
     * (VAULT-MESSAGING 0.33.0 §11.14.1, release R) The phone's unlock secret at its epoch: sent right after `pin`, and
     * the signing string ends with its hash. Null for a vault before release R. Never logged.
     */
    val unlockSecret: ByteArray? = null,
) {
    override fun toString(): String = "UnlockOptions(approve=$approve, abandon=$abandon, cancelRecovery=$cancelRecovery, " +
        "sendChain=$sendChain, unlockSecret=${if (unlockSecret != null) "set" else "none"})"
}

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
        /** (0.33.0, release R) The new phone key's secret of epoch 1; null for a release before R. */
        unlockSecret: ByteArray? = null,
    ): BuiltEnroll {
        val t = ts(now)
        val rid = Ulid.new(t)
        val challenge = AltChannel.devattChallenge(rid, "", Timestamps.formatMillis(t))
        val attest = attester.attest(challenge)
        val nonce = Randomness.bytes(NONCE_SIZE)
        val request = EnrollRequest(
            userGuid, rid, nonce, pin, app.ik(), app.kem, app.relay, openToken, app.name, attest, manifest.sha256Hex, manifest.serial,
            app.apiKey(), unlockSecret,
        )
        val body = request.marshal()
        request.wipe()
        val env = sealWiping(enclave, AltChannel.TYPE_ENROLL, rid, t, body, wipe = unlockSecret != null)
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
        // §11.7.1 (0.27.0): the bound key's chain, while the vault has not recorded this app's signer. Not part of
        // the signing string; a bound key ignores it. Never on the release approval below.
        val assertion = unlockAssertion(attester, challenge, options.sendChain)
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
                unlockSecret = options.unlockSecret,
            ),
        )
        val sig = ik.signRaw(signing.toByteArray())

        fun seal(a: DeviceAssertion): ByteArray {
            val request = UnlockRequest(
                userGuid, vaultId, rid, ik.publicKey, pin, state.stateSeq, minHeader, token, a, manifest.sha256Hex, manifest.serial,
                update, options.cancelRecovery, sig, options.unlockSecret,
            )
            val body = request.marshal()
            request.wipe()
            return sealWiping(enclave, AltChannel.TYPE_UNLOCK, rid, t, body, wipe = options.unlockSecret != null)
        }
        val env = sealFitting(assertion, ::seal)
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

    /** Seals [body] to the enclave; with [wipe] (a body that carries the release R unlock secret) zeroes it after. */
    private fun sealWiping(enclave: VerifiedEnclave, type: String, rid: String, t: Instant, body: ByteArray, wipe: Boolean): ByteArray =
        try {
            AltChannel.sealRequest(enclave.descriptor.etk, type, rid, t, body)
        } finally {
            if (wipe) body.fill(0)
        }

    /**
     * Applies an `ok` `vault.recovery.open.result` (VAULT-MESSAGING 0.33.0 §11.14.6, release R) to the app state, as an
     * unlock result is applied: floors only move up, and the result's `release` with its `header_seq` becomes the
     * release the next unlock names (with its `min_header_seq`). A result without a well-formed release moves only the
     * state floor.
     */
    fun applyRecoveryOpen(state: AltState, stateSeq: Long, headerSeq: Long, release: String, releaseNumber: Long): AltState {
        val s = state.copy(stateSeq = maxOf(state.stateSeq, stateSeq))
        if (release.length != PCR_HEX || releaseNumber <= 0) return s
        val hs = s.headerSeq.toMutableMap()
        if (headerSeq > (hs[release] ?: 0)) hs[release] = headerSeq
        return s.copy(headerSeq = hs, release = release, releaseNumber = releaseNumber)
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
            .string("name", app.name).raw("device_attest", attest.marshal())
            .also { b -> app.apiKey()?.let { b.base64("api_key", it) } }
            .build()
        val body = JsonBuilder().string("user_guid", userGuid).string("vault_id", code.vaultId).string("request_id", rid)
            .string("recovery_id", code.recoveryId).string("code", code.code).raw("app", appObj).bytes()
        val env = AltChannel.sealRequest(enclave.descriptor.etk, TYPE_RECOVERY_REGISTER, rid, t, body)
        return SealedRequest(rid, enclave.descriptor.kid.toString(), env, null)
    }

    /**
     * `vault.recovery.open` (VAULT-MESSAGING 0.33.0 §11.14.6, release R): the registered phone's PIN, password (NFC)
     * and unlock secret, an open token for its own mailbox, the device assertion over the challenge, the verified
     * manifest, and the signature by its identity key [ik].
     */
    @Suppress("LongParameterList")
    fun buildRecoveryOpen(
        userGuid: String,
        vaultId: String,
        recoveryId: String,
        pin: String,
        password: String,
        unlockSecret: ByteArray,
        enclave: VerifiedEnclave,
        manifest: ReleaseManifest,
        attester: Attester,
        ik: Ed25519PrivateKey,
        token: String,
        now: Instant,
    ): SealedRequest {
        val t = ts(now)
        val tss = Timestamps.formatMillis(t)
        val rid = Ulid.new(t)
        val assertion = attester.assert(AltChannel.devattChallenge(rid, vaultId, tss))
        val secrets = Passwords.recoverySecrets(pin, password)
        val signing = try {
            RecoveryOpen.signingString(
                RecoveryOpen.Fields(
                    userGuid, vaultId, rid, recoveryId, tss, enclave.descriptor.kid, secrets, token, manifest.sha256Hex, unlockSecret,
                ),
            )
        } finally {
            secrets.fill(0)
        }
        val sig = ik.signRaw(signing.toByteArray())
        val body = RecoveryOpen.marshal(
            userGuid, vaultId, rid, recoveryId, ik.publicKey, pin, password, unlockSecret, token, assertion, manifest.sha256Hex,
            manifest.serial, sig,
        )
        val env = try {
            AltChannel.sealRequest(enclave.descriptor.etk, RecoveryOpen.TYPE, rid, t, body)
        } finally {
            body.fill(0)
        }
        return SealedRequest(rid, enclave.descriptor.kid.toString(), env, manifest.sha256Hex)
    }

    /** The unlock's assertion over [challenge], with the device key's chain when [withChain] and there is a usable one. */
    private fun unlockAssertion(attester: Attester, challenge: ByteArray, withChain: Boolean): DeviceAssertion {
        val a = attester.assert(challenge)
        val chain = if (withChain) usableChain(attester) else null
        return if (chain != null) DeviceAssertion.android(a.sig(), chain) else a
    }

    /**
     * [seal] of [assertion]; when it carries a chain that does not fit the request's 12,288 bytes (none that an
     * enrollment carried does not, vettid-vault's chainsize_test), the unlock goes without the chain rather than
     * not at all.
     */
    private fun sealFitting(assertion: DeviceAssertion, seal: (DeviceAssertion) -> ByteArray): ByteArray = try {
        seal(assertion)
    } catch (e: CryptoException.Format) {
        if (assertion.chain() == null) throw e
        seal(assertion.withoutChain())
    }

    /** [attester]'s chain if it is one a `device_assertion` can carry (1–10 certificates), else null. */
    private fun usableChain(attester: Attester): List<ByteArray>? =
        attester.chain()?.takeIf { c -> c.size in 1..DeviceAttest.MAX_CHAIN && c.none { it.isEmpty() || it.size > MAX_CERT } }

    private fun relayJson(r: RelayAddr): String =
        JsonBuilder().string("url", r.url).string("mailbox", r.mailbox).base64("pk", r.pk()).build()

    private const val NONCE_SIZE = 32
    private const val MAX_CERT = 16 * 1024
    private const val PCR_HEX = 96
}
