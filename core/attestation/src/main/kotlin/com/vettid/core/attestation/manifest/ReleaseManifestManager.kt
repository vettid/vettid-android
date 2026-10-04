package com.vettid.core.attestation.manifest

import com.vettid.core.attestation.AttestationException
import java.time.Duration
import java.time.Instant

/** What the app keeps about releases (§11.10.6): public data, but its integrity matters. */
interface ReleaseStateStore {
    /** The highest manifest serial seen (0: none). */
    var highestSerial: Long

    /** The last verified served document (its exact bytes), or null. */
    var servedManifest: ByteArray?

    /** When the manifest was last fetched and verified. */
    var lastFetchAt: Instant?

    /** The release the vault last unlocked into: PCR0 and number, or null before the first unlock. */
    var lastRelease: Pair<String, Long>?
}

/** A process-local [ReleaseStateStore] (tests; a fresh install before anything is stored). */
class InMemoryReleaseStateStore : ReleaseStateStore {
    override var highestSerial: Long = 0
    override var servedManifest: ByteArray? = null
    override var lastFetchAt: Instant? = null
    override var lastRelease: Pair<String, Long>? = null
}

/** What the app does before sending a PIN to a routed release (§11.2 step 4, §11.10.6). */
sealed class UnlockDecision {
    /** The release the vault last unlocked into (or the first one): send the PIN. */
    data class Proceed(val release: Release) : UnlockDecision()

    /** A newer listed release: the vault was moved from another device; tell the user first. */
    data class Updated(val from: Pair<String, Long>, val release: Release) : UnlockDecision()

    /** Deprecated or retired: show the status and `ends_at`, offer the newest active release. */
    data class EndOfLife(val release: Release, val newest: Release?) : UnlockDecision()

    /** Removed: the release has ended; the vault can no longer be opened there. */
    data class Removed(val release: Release) : UnlockDecision()
}

/**
 * Keeps the verified release manifest and the release rules of
 * VAULT-MESSAGING §11.2 and §11.10.6. The successor of the v1 app's
 * `PcrConfigManager` (signed PCR sets with a bundled fallback and a
 * user-trusted PCR0 set): in 0.10.0 the signed manifest is the only source of
 * trusted PCRs, there is no bundled fallback, the serial never goes back, and
 * the release the vault last unlocked into replaces the trusted set.
 *
 * Fetching is the caller's (A2 `:core:altchan`): it passes the served bytes
 * from `https://vettid.org/.well-known/vettid/pcr-manifest.json` to [accept]
 * before each enroll and unlock.
 */
class ReleaseManifestManager(
    private val verifier: ManifestVerifier,
    private val store: ReleaseStateStore,
) {
    /**
     * Verifies a freshly fetched served document (signature under a pinned
     * key, strict format) and applies the serial rule against the highest
     * serial seen; on success stores it and raises the serial.
     */
    @Synchronized
    fun accept(served: ByteArray, now: Instant): ReleaseManifest {
        val m = verifier.verify(served)
        m.checkSerial(store.highestSerial)
        store.highestSerial = m.serial
        store.servedManifest = served.copyOf()
        store.lastFetchAt = now
        return m
    }

    /** The cached manifest, verified again (null if none, or if it no longer verifies). */
    @Synchronized
    fun cached(): ReleaseManifest? {
        val b = store.servedManifest ?: return null
        return try {
            verifier.verify(b).takeIf { it.serial >= store.highestSerial }
        } catch (_: AttestationException) {
            null
        }
    }

    /** Whether a background refresh is due (enroll and unlock always fetch first). */
    fun refreshDue(now: Instant, interval: Duration = REFRESH_INTERVAL): Boolean {
        val last = store.lastFetchAt ?: return true
        return Duration.between(last, now) >= interval
    }

    /** Enrollment goes only to an `active` release (§11.2 step 2, §11.10.1). */
    fun checkEnroll(m: ReleaseManifest, pcr0: String): Release {
        val r = m.byPcr0(pcr0) ?: throw AttestationException.Release("not listed")
        if (r.status != ReleaseStatus.ACTIVE) throw AttestationException.Release("not active")
        return r
    }

    /**
     * Decides what to do before sending a PIN to [pcr0], the release the API
     * routed to. A release absent from the manifest, or one with a lower
     * number than the release last unlocked into (a rollback), is refused.
     */
    @Synchronized
    fun decideUnlock(m: ReleaseManifest, pcr0: String): UnlockDecision {
        val r = m.byPcr0(pcr0) ?: throw AttestationException.Release("not listed")
        val last = store.lastRelease
        if (last != null && r.number < last.second) throw AttestationException.Release("rollback")
        return when {
            r.status == ReleaseStatus.REMOVED -> UnlockDecision.Removed(r)
            last != null && r.number > last.second -> UnlockDecision.Updated(last, r)
            r.status == ReleaseStatus.DEPRECATED || r.status == ReleaseStatus.RETIRED -> UnlockDecision.EndOfLife(r, m.newest())
            else -> UnlockDecision.Proceed(r)
        }
    }

    /** Records the release a successful unlock reached (or a confirmed move / abandon). */
    @Synchronized
    fun recordUnlocked(r: Release) {
        store.lastRelease = r.pcr0 to r.number
    }

    companion object {
        val REFRESH_INTERVAL: Duration = Duration.ofHours(24)
    }
}
