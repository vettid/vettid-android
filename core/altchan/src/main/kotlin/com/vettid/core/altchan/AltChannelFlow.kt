package com.vettid.core.altchan

import com.vettid.core.attestation.AttestationException
import com.vettid.core.attestation.EnclaveVerifier
import com.vettid.core.attestation.VerifiedEnclave
import com.vettid.core.attestation.manifest.ManifestKey
import com.vettid.core.attestation.manifest.ManifestKeys
import com.vettid.core.attestation.manifest.ManifestVerifier
import com.vettid.core.attestation.manifest.Release
import com.vettid.core.attestation.manifest.ReleaseManifest
import com.vettid.core.attestation.nitro.NitroRoot
import com.vettid.core.attestation.nitro.NitroVerifier
import com.vettid.core.crypto.altchan.ReleaseTraits
import com.vettid.core.crypto.altchan.TimeScale
import com.vettid.core.crypto.envelope.Ulid
import java.io.IOException
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * What the app pins for the alternate channel (§11.2, §11.10.1): the AWS
 * Nitro root and the manifest keys. [production] is the release build's,
 * [staging] the `staging` build type's;
 * the local dev stack's TEST-ONLY anchors are passed by development code
 * and instrumented tests, never compiled into a release.
 *
 * [timeScaleOk] says which release time scales this build accepts (STAGING-TIME-SCALE §4 item 4, §5.2): a production
 * build only 1, a staging build 1 and 24. An instance or a transfer bundle with any other value gets nothing.
 */
class AltTrust(
    val nitroRoots: List<X509Certificate>,
    val manifestKeys: List<ManifestKey>,
    private val timeScaleOk: (Long) -> Boolean = PRODUCTION_SCALES,
) {
    /** Whether this build may talk to a release with [scale]. */
    fun acceptsTimeScale(scale: Long): Boolean = timeScaleOk(scale)

    /** Refuses a release this build may not talk to ([acceptsTimeScale]): nothing is sent to it. */
    fun checkTimeScale(scale: Long) {
        if (!acceptsTimeScale(scale)) throw AltRefusedException(AltRefusedException.Reason.TIME_SCALE)
    }

    companion object {
        /** A production build: real time only. */
        val PRODUCTION_SCALES: (Long) -> Boolean = { it == TimeScale.REAL }

        /** A staging build: real time, or 1 day = 1 hour. */
        val STAGING_SCALES: (Long) -> Boolean = { it == TimeScale.REAL || it == TimeScale.STAGING }

        /** The dev stack (TEST ONLY): its `-time-scale` flag, 1 to 86,400. */
        val DEV_STACK_SCALES: (Long) -> Boolean = { it in TimeScale.REAL..TimeScale.MAX }

        fun production() = AltTrust(listOf(NitroRoot.certificate), ManifestKeys.PRODUCTION)

        /** The `staging` build type's: the real AWS Nitro root and the staging manifest key; scales 1 and 24. */
        fun staging() = AltTrust(listOf(NitroRoot.certificate), ManifestKeys.STAGING, STAGING_SCALES)
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
        /** (0.33.0, release R) The new phone key's secret of epoch 1; null for a release before R. */
        unlockSecret: ByteArray? = null,
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

    /** (ANDROID-PLAN 0.1.54) The release an unconfirmed move left, from the stored marker ([AltState.movedFrom]); null for none. */
    fun movedFrom(): MovedFrom? = null

    /**
     * [built] was not posted (VAULT-MESSAGING §11.14.9 step 2: its PIN may no longer be the one the vault expects): what
     * [prepareUnlock] recorded for it goes, as if it had never been built (the pending unlock, the standing token's
     * issue). Nothing of it reached the vault.
     */
    suspend fun discardUnlock(built: BuiltUnlock) {}
}

/**
 * (A6 audit N3) What the slot of an enrollment request posted earlier says ([AltChannelFlow.enrollAnswer]).
 */
sealed interface EnrollAnswer {
    /** The enclave answered: `vault.enroll.result` with [ok] (the vault exists) or the refusal's [code]. */
    data class Answered(val ok: Boolean, val code: String?) : EnrollAnswer

    /**
     * The request left the queue unprocessed (`expired`), the enclave did not know the key (`etk_unknown`), or it
     * answered random bytes: no vault was made.
     */
    data object NotProcessed : EnrollAnswer

    /** The slot does not exist (`404`): never posted, or more than 15 minutes old and gone. */
    data object Gone : EnrollAnswer
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
    /** A canary manifest installed out of band (VAULT-RELEASES §10.1 step 9), or null. */
    private val canary: CanaryManifestSource? = null,
    private val verifyEnclave: (ByteArray, ByteArray, ReleaseManifest, Boolean, Instant) -> VerifiedEnclave =
        EnclaveVerifier(NitroVerifier(trust.nitroRoots))::verifyEnclave,
) {
    private val manifests = ManifestVerifier(trust.manifestKeys)

    /**
     * Fetches and verifies the served manifest; refuses one older than [seen]. With a canary manifest
     * installed ([CanaryManifestSource]) the one with the higher serial is used ([CanaryManifests.choose]):
     * the canary document while it is newer than the published one, also while nothing is published yet
     * (404); once the published manifest reaches its serial, the canary document is retired. Only a 404 falls
     * back to the canary (VAULT-MESSAGING §11.10.1): any other failure to fetch the published manifest (another
     * status, a network error) stays an error.
     */
    suspend fun manifest(seen: Long): ReleaseManifest {
        val canaryDoc = canary?.served()
        val c = canaryDoc?.let { canaryManifest(it) }
        val published = try {
            manifests.verify(api.manifest())
        } catch (e: MemberApiException) {
            if (c == null || e.code != MemberApiException.MANIFEST_UNAVAILABLE || e.status != HTTP_NOT_FOUND) throw e
            null
        }
        if (canaryDoc != null && c != null && published != null && published.serial >= c.serial) canary?.retire(canaryDoc)
        val m = CanaryManifests.choose(published, c) ?: throw MemberApiException(0, MemberApiException.MANIFEST_UNAVAILABLE)
        if (m.serial < seen) throw AltRefusedException(AltRefusedException.Reason.MANIFEST_OLDER)
        return m
    }

    /** The installed canary manifest, verified again; one that no longer verifies is retired and ignored. */
    private fun canaryManifest(served: ByteArray): ReleaseManifest? = try {
        manifests.verify(served)
    } catch (_: AttestationException) {
        canary?.retire(served)
        null
    }

    /**
     * Whether this build may take [scale] as its vault's time scale (STAGING-TIME-SCALE §4 item 4): the rule the
     * descriptor is checked against, for a scale that reaches the app another way (`vault.status`, §11.10.8).
     */
    fun acceptsTimeScale(scale: Long): Boolean = trust.acceptsTimeScale(scale)

    private suspend fun enclaveFor(
        release: String?,
        m: ReleaseManifest,
        enroll: Boolean,
        onStarting: ((Long) -> Unit)? = null,
    ): Pair<EnclaveInfo, VerifiedEnclave> {
        val info = api.enclaveWait(release, onStarting)
        val e = verifyEnclave(info.descriptor(), info.attestation(), m, enroll, Instant.now(clock))
        if (e.descriptor.instanceId != info.instanceId) throw AltChannelException("descriptor names another instance")
        // STAGING-TIME-SCALE §4 item 4: a release with a time scale this build does not accept gets nothing.
        trust.checkTimeScale(e.descriptor.timeScale)
        return info to e
    }

    private fun retryable(e: Exception?, s: Slot?): Boolean =
        when {
            e is MemberApiException -> e.code == MemberApiException.INSTANCE_MOVED
            s == null -> false
            else -> s.status == Slot.EXPIRED || s.code == Slot.ETK_UNKNOWN
        }

    /**
     * Enrolls this app (§11.3) into [vaultId], the vault the setup code's redeem named (§11.12.1). Success also
     * arrives as vault.enrolled over the relay. (0.34.0, release R) [unlockSecretFor] is given the verified instance's
     * capabilities and time scale (§11.10.9) before each request is built: it returns the phone's unlock secret for release R's shape,
     * null for the earlier one, or throws, and then nothing is sent. The secret is zeroed once the request is sealed.
     */
    suspend fun enroll(
        party: AltParty,
        vaultId: String,
        userGuid: String,
        pin: String,
        attester: Attester,
        unlockSecretFor: suspend (traits: ReleaseTraits) -> ByteArray? = { null },
    ): EnrollOutcome {
        var m = manifest(party.manifestSerialSeen())
        var refetched = false
        var attempt = 1
        while (true) {
            val (info, e) = enclaveFor(null, m, enroll = true)
            val secret = unlockSecretFor(e.descriptor.traits)
            val req = try {
                party.prepareEnroll(userGuid, pin, e, m, attester, secret)
            } finally {
                secret?.fill(0)
            }
            var err: MemberApiException? = null
            var slot: Slot? = null
            var vid = ""
            try {
                vid = api.enroll(vaultId, info.instanceId, req)
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

    /**
     * (A6 audit N3) Looks at the slot of the enrollment request [requestId] posted earlier (a lost answer, a crash, a
     * restart) without posting anything: polls while it is queued, and opens its result. A transport failure is thrown
     * (the request stays outstanding).
     */
    suspend fun enrollAnswer(party: AltParty, requestId: String): EnrollAnswer {
        val s = try {
            api.poll(requestId)
        } catch (e: MemberApiException) {
            if (e.status == HTTP_NOT_FOUND) return EnrollAnswer.Gone
            throw e
        }
        if (s.status == Slot.EXPIRED || s.code == Slot.ETK_UNKNOWN) return EnrollAnswer.NotProcessed
        val env = s.envelope()
        if (s.status != Slot.DONE || env == null) throw AltChannelException("enrollment not answered (${s.status} ${s.code ?: ""})")
        val r = try {
            party.openEnrollResult(env, requestId)
        } catch (_: AltResultException) {
            // Random bytes of the result's size: the enclave could not read the request (§11.3), so it made nothing.
            return EnrollAnswer.NotProcessed
        }
        return EnrollAnswer.Answered(r.ok, r.code)
    }

    /**
     * Unlocks the vault (§11.4); [release] (a PCR0) only to abandon an unconfirmed move. [onStarting] hears each
     * `503 release_starting` while the routed release starts (§11.10.5).
     */
    @Suppress("CyclomaticComplexMethod", "LongParameterList") // one branch per §11.9 retry rule, as in the reference client
    suspend fun unlock(
        party: AltParty,
        userGuid: String,
        pin: String,
        attester: Attester,
        options: UnlockOptions = UnlockOptions(),
        release: String? = null,
        onStarting: ((Long) -> Unit)? = null,
        /**
         * Runs before each request is built and again right before it is posted (retries included); a throw stops it
         * with nothing posted, and a request already built is discarded ([AltParty.discardUnlock]). For a PIN typed on a
         * flow's own screen that may no longer be the one the vault expects (VAULT-MESSAGING §11.14.9 step 2).
         */
        beforePost: () -> Unit = {},
        /** (0.34.0, release R) As for [enroll]: the request's shape from the routed instance's capabilities. */
        unlockSecretFor: suspend (traits: ReleaseTraits) -> ByteArray? = { options.unlockSecret?.copyOf() },
    ): UnlockOutcome {
        val vaultId = party.vaultId() ?: throw AltRefusedException(AltRefusedException.Reason.NOT_PAIRED)
        var m = manifest(party.manifestSerialSeen())
        var refetched = false
        var attempt = 1
        while (true) {
            // Before anything is built: building mints the standing token and records the pending unlock.
            beforePost()
            // ANDROID-PLAN 0.1.54 point 6: a route still naming the release the vault moved from gets no PIN; it is fetched
            // again for about a minute. Not for the way back, which asks for that release by its PCR0.
            val routed = enclaveFor(release, m, enroll = false, onStarting)
            val (info, e) = if (release == null && !options.abandon) {
                RouteWait.awayFrom(party.movedFrom(), routed, { it.second.release }) { enclaveFor(null, m, enroll = false, onStarting) }
            } else {
                routed
            }
            val secret = unlockSecretFor(e.descriptor.traits)
            val built = try {
                party.prepareUnlock(userGuid, pin, e, m, attester, options.copy(unlockSecret = secret))
            } finally {
                secret?.fill(0)
            }
            var err: MemberApiException? = null
            var slot: Slot? = null
            // Right before the post as well: what moved while the request was built leaves nothing of it behind.
            runCatching { beforePost() }.onFailure { x ->
                party.discardUnlock(built)
                throw x
            }
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

    /**
     * What the app must know before it asks for the PIN (§11.2 step 4,
     * §11.10.6): the verified manifest and the release the vault is routed
     * to, compared with the release this device last unlocked into
     * ([state]). Throws [MemberApiException] `release_unavailable` (410) when
     * the vault's release has ended, and the verification errors of the
     * manifest and the enclave. A route naming the release an unconfirmed
     * move left is fetched again as for the unlock ([RouteWait]).
     */
    suspend fun preflight(state: AltState, release: String? = null): UnlockPreflight {
        val m = manifest(state.manifestSerial)
        val routed = enclaveFor(release, m, enroll = false)
        val (_, e) = if (release == null) {
            RouteWait.awayFrom(state.movedFrom, routed, { it.second.release }) { enclaveFor(null, m, enroll = false) }
        } else {
            routed
        }
        return UnlockPreflight.of(state, e.release, m)
    }

    /**
     * Asks the leaseholder to lock the vault and waits for the slot: until it is no longer queued, or for [atMost]
     * (then the slot is returned as it is, still [Slot.QUEUED]: the request may be carried out later, or never).
     * An answered slot from a release before L says that the request was handled, **not** that the vault locked: the
     * enclave answers "done" whatever became of its lock. From release L a `done` carries a code ([Slot.CODE_LOCKED],
     * [Slot.CODE_NOT_RUNNING], [Slot.CODE_STALE]), the host's word. [unlockId]: a bound lock (MEMBER-API 2.5.0).
     */
    suspend fun lock(vaultId: String, atMost: Duration? = null, unlockId: String? = null): Slot {
        val rid = Ulid.new(Instant.now(clock))
        api.lock(vaultId, rid, unlockId)
        return api.poll(rid, atMost)
    }

    /**
     * Registers this app for a recovery (§11.11.3) with the portal's code.
     * [build] seals the request to the verified enclave (the device records
     * its recovery state); [open] opens vault.recovery.result.
     */
    suspend fun recoveryRegister(
        vaultId: String,
        build: suspend (VerifiedEnclave) -> SealedRequest,
        open: suspend (ByteArray, String) -> RecoveryResult,
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

    /**
     * Opens a registered recovery (VAULT-MESSAGING 0.33.0 §11.14.6, release R) through `POST /api/vault/recovery/open`,
     * routed like an unlock. [build] seals `vault.recovery.open` to the verified enclave; [open] opens
     * `vault.recovery.open.result`. Never retried here: every open may use an attempt, so the member sends again.
     * [onSent] runs once the member API has taken the request: from then on it may reach the vault and use an attempt
     * even if no answer comes back.
     */
    suspend fun recoveryOpen(
        vaultId: String,
        build: suspend (VerifiedEnclave, ReleaseManifest) -> SealedRequest,
        open: suspend (ByteArray, String) -> com.vettid.core.crypto.altchan.RecoveryOpen.Result,
        onSent: suspend () -> Unit = {},
    ): com.vettid.core.crypto.altchan.RecoveryOpen.Result {
        val m = manifest(0)
        val (info, e) = enclaveFor(null, m, enroll = false)
        val req = build(e, m)
        api.recoveryOpen(vaultId, info.instanceId, req)
        onSent()
        val s = api.poll(req.requestId)
        val env = s.envelope()
        if (s.status != Slot.DONE || env == null) throw AltChannelException("recovery open not answered (${s.status} ${s.code ?: ""})")
        return open(env, req.requestId)
    }

    companion object {
        const val MAX_ATTEMPTS = 4
        const val CODE_MANIFEST = "manifest"

        /** Nothing is published yet (release 1): the only status that falls back to the canary manifest. */
        private const val HTTP_NOT_FOUND = 404
    }
}

/**
 * The release check before an unlock (§11.10.6). [routed] is the manifest
 * entry of the attested instance the vault is routed to.
 *
 * - [rollback]: older than the release this device last unlocked into; the
 *   app MUST NOT send the PIN.
 * - [softwareUpdated]: newer than the last one (another device moved the
 *   vault); the app tells the member before sending the PIN.
 * - [offer]: the newest `active` release when it is newer than the routed
 *   one; the member may approve the move in this unlock (§11.10.3).
 * - [between]: the manifest's releases between the routed one and [offer]
 *   (What's new lists their summaries, ANDROID-PLAN 0.1.31).
 * - [movedFromListed]: (ANDROID-PLAN 0.1.54) false when an unconfirmed move's
 *   previous release is no longer in this manifest (the way back is gone).
 */
data class UnlockPreflight(
    val routed: Release,
    val lastNumber: Long,
    val manifestSerial: Long,
    val offer: Release?,
    val between: List<Release> = emptyList(),
    val movedFromListed: Boolean = true,
) {
    val rollback: Boolean get() = lastNumber != 0L && routed.number < lastNumber
    val softwareUpdated: Boolean get() = lastNumber != 0L && routed.number > lastNumber

    companion object {
        fun of(state: AltState, routed: Release, m: ReleaseManifest): UnlockPreflight {
            val newest = m.newest()
            val offer = newest?.takeIf { it.number > routed.number }
            val between = offer?.let { o -> m.releases.filter { it.number > routed.number && it.number < o.number } }.orEmpty()
            val listed = state.movedFrom?.let { m.byPcr0(it.pcr0) != null } ?: true
            return UnlockPreflight(routed, state.releaseNumber, m.serial, offer, between, listed)
        }
    }
}
