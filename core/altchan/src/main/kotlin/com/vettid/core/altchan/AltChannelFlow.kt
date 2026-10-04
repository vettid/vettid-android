package com.vettid.core.altchan

import com.vettid.core.attestation.EnclaveVerifier
import com.vettid.core.attestation.VerifiedEnclave
import com.vettid.core.attestation.manifest.ManifestKey
import com.vettid.core.attestation.manifest.ManifestKeys
import com.vettid.core.attestation.manifest.ManifestVerifier
import com.vettid.core.attestation.manifest.ReleaseManifest
import com.vettid.core.attestation.nitro.NitroRoot
import com.vettid.core.attestation.nitro.NitroVerifier
import com.vettid.core.crypto.envelope.Ulid
import java.io.IOException
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Instant

/**
 * What the app pins for the alternate channel (§11.2, §11.10.1): the AWS
 * Nitro root and the manifest keys. [production] is the release build's;
 * the local dev stack's TEST-ONLY anchors are passed by development code
 * and instrumented tests, never compiled into a release.
 */
class AltTrust(val nitroRoots: List<X509Certificate>, val manifestKeys: List<ManifestKey>) {
    companion object {
        fun production() = AltTrust(listOf(NitroRoot.certificate), ManifestKeys.PRODUCTION)
    }
}

/**
 * The device side of the alternate channel, implemented by the vault
 * client (`:core:vault`): it owns the keys and the [AltState] and records
 * what each request needs to open its result.
 */
interface AltParty {
    /** The highest manifest serial this device has seen for its vault. */
    fun manifestSerialSeen(): Long

    fun vaultId(): String?

    suspend fun prepareEnroll(
        userGuid: String,
        pin: String,
        enclave: VerifiedEnclave,
        manifest: ReleaseManifest,
        attester: Attester,
    ): SealedRequest

    suspend fun openEnrollResult(raw: ByteArray, requestId: String): EnrollResult

    suspend fun prepareUnlock(
        userGuid: String,
        pin: String,
        enclave: VerifiedEnclave,
        manifest: ReleaseManifest,
        attester: Attester,
        options: UnlockOptions,
    ): BuiltUnlock

    suspend fun openUnlockResult(raw: ByteArray): UnlockResult
}

/** The opened vault.enroll.result, the vault id the API assigned and where it ran. */
data class EnrollOutcome(val vaultId: String, val ok: Boolean, val code: String?, val instanceId: String)

/** The opened vault.unlock.result and where it ran. */
data class UnlockOutcome(val result: UnlockResult, val instanceId: String, val releaseChanged: Boolean) {
    val ok: Boolean get() = result.ok
}

/** The alternate channel gave up: no answer, or the routed enclave's descriptor names another instance. */
class AltChannelException(message: String) : IOException("alternate channel: $message")

/**
 * The alternate-channel flows (§11.3, §11.4, §11.9), as vettid-vault's
 * reference client runs them: verify the served manifest (signature and
 * serial floor) and the routed enclave (Nitro chain, user_data over the
 * descriptor, PCRs listed in the manifest, `active` to enroll), seal, post,
 * poll the slot and open the result. Re-seals with a fresh descriptor after
 * `instance_moved`, an `expired` slot or `etk_unknown` (at most 4
 * attempts), and refetches the manifest once after a `manifest` result.
 */
class AltChannelFlow(
    private val api: MemberApiClient,
    private val trust: AltTrust,
    private val clock: Clock = Clock.systemUTC(),
    private val verifyEnclave: (ByteArray, ByteArray, ReleaseManifest, Boolean, Instant) -> VerifiedEnclave =
        EnclaveVerifier(NitroVerifier(trust.nitroRoots))::verifyEnclave,
) {
    private val manifests = ManifestVerifier(trust.manifestKeys)

    /** Fetches and verifies the served manifest; refuses one older than [seen]. */
    suspend fun manifest(seen: Long): ReleaseManifest {
        val m = manifests.verify(api.manifest())
        if (m.serial < seen) throw AltRefusedException(AltRefusedException.Reason.MANIFEST_OLDER)
        return m
    }

    private suspend fun enclaveFor(release: String?, m: ReleaseManifest, enroll: Boolean): Pair<EnclaveInfo, VerifiedEnclave> {
        val info = api.enclaveWait(release)
        val e = verifyEnclave(info.descriptor(), info.attestation(), m, enroll, Instant.now(clock))
        if (e.descriptor.instanceId != info.instanceId) throw AltChannelException("descriptor names another instance")
        return info to e
    }

    private fun retryable(e: Exception?, s: Slot?): Boolean =
        when {
            e is MemberApiException -> e.code == MemberApiException.INSTANCE_MOVED
            s == null -> false
            else -> s.status == Slot.EXPIRED || s.code == Slot.ETK_UNKNOWN
        }

    /** Enrolls this app (§11.3). Success also arrives as vault.enrolled over the relay. */
    suspend fun enroll(party: AltParty, userGuid: String, pin: String, attester: Attester): EnrollOutcome {
        var m = manifest(party.manifestSerialSeen())
        var refetched = false
        var attempt = 1
        while (true) {
            val (info, e) = enclaveFor(null, m, enroll = true)
            val req = party.prepareEnroll(userGuid, pin, e, m, attester)
            var err: MemberApiException? = null
            var slot: Slot? = null
            var vid = ""
            try {
                vid = api.enroll(info.instanceId, req)
                slot = api.poll(req.requestId)
            } catch (x: MemberApiException) {
                err = x
            }
            if (retryable(err, slot) && attempt++ < MAX_ATTEMPTS) continue
            err?.let { throw it }
            val s = slot!!
            val env = s.envelope()
            if (s.status != Slot.DONE || env == null) throw AltChannelException("enrollment not answered (${s.status} ${s.code ?: ""})")
            val r = party.openEnrollResult(env, req.requestId)
            if (!r.ok && r.code == CODE_MANIFEST && !refetched) {
                refetched = true
                m = manifest(party.manifestSerialSeen())
                continue
            }
            return EnrollOutcome(vid, r.ok, r.code, info.instanceId)
        }
    }

    /** Unlocks the vault (§11.4); [release] (a PCR0) only to abandon an unconfirmed move. */
    @Suppress("CyclomaticComplexMethod") // one branch per §11.9 retry rule, as in the reference client
    suspend fun unlock(
        party: AltParty,
        userGuid: String,
        pin: String,
        attester: Attester,
        options: UnlockOptions = UnlockOptions(),
        release: String? = null,
    ): UnlockOutcome {
        val vaultId = party.vaultId() ?: throw AltRefusedException(AltRefusedException.Reason.NOT_PAIRED)
        var m = manifest(party.manifestSerialSeen())
        var refetched = false
        var attempt = 1
        while (true) {
            val (info, e) = enclaveFor(release, m, enroll = false)
            val built = party.prepareUnlock(userGuid, pin, e, m, attester, options)
            var err: MemberApiException? = null
            var slot: Slot? = null
            try {
                api.unlock(vaultId, info.instanceId, built.request)
                slot = api.poll(built.request.requestId)
            } catch (x: MemberApiException) {
                err = x
            }
            if (retryable(err, slot) && attempt++ < MAX_ATTEMPTS) continue
            err?.let { throw it }
            val s = slot!!
            val env = s.envelope()
            if (s.status != Slot.DONE || env == null) throw AltChannelException("unlock not answered (${s.status} ${s.code ?: ""})")
            val r = party.openUnlockResult(env)
            if (!r.ok && r.code == CODE_MANIFEST && !refetched) {
                refetched = true
                m = manifest(party.manifestSerialSeen())
                continue
            }
            return UnlockOutcome(r, info.instanceId, built.releaseChanged)
        }
    }

    /** Asks the leaseholder to lock the vault and waits for the slot. */
    suspend fun lock(vaultId: String): Slot {
        val rid = Ulid.new(Instant.now(clock))
        api.lock(vaultId, rid)
        return api.poll(rid)
    }

    /**
     * Registers this app for a recovery (§11.11.3) with the portal's code.
     * [build] seals the request to the verified enclave (the device records
     * its recovery state); [open] opens vault.recovery.result.
     */
    suspend fun recoveryRegister(
        vaultId: String,
        build: (VerifiedEnclave) -> SealedRequest,
        open: (ByteArray, String) -> RecoveryResult,
    ): RecoveryResult {
        val m = manifest(0)
        var attempt = 1
        while (true) {
            val (info, e) = enclaveFor(null, m, enroll = false)
            val req = build(e)
            var err: MemberApiException? = null
            var slot: Slot? = null
            try {
                api.recoveryRegister(vaultId, info.instanceId, req)
                slot = api.poll(req.requestId)
            } catch (x: MemberApiException) {
                err = x
            }
            if (retryable(err, slot) && attempt++ < MAX_ATTEMPTS) continue
            err?.let { throw it }
            val s = slot!!
            val env = s.envelope()
            if (s.status != Slot.DONE || env == null) throw AltChannelException("recovery not answered (${s.status} ${s.code ?: ""})")
            return open(env, req.requestId)
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 4
        const val CODE_MANIFEST = "manifest"
    }
}
