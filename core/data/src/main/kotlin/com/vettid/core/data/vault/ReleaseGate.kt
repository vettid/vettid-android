package com.vettid.core.data.vault

import com.vettid.core.attestation.manifest.Release

/**
 * (Rule 3: the app requires the vault's current release) Who takes a vault event while the release gate pauses the
 * vault's background work ([ReleaseUpdateManager.paused]).
 */
internal enum class EventTaker { ITEMS, SHARING, DEVICES, VOTES, PRESENCE, LOCATION, PAIRING, SOCIAL, ATTACHMENTS }

/** (Rule 3) Nothing of the vault's features is read or taken in: the owner check is due or held (§3.6.3), or the gate pauses them. */
internal fun featuresGated(ownerCheckGated: Boolean, paused: Boolean): Boolean = ownerCheckGated || paused

/** (Rule 3) The events the vault's features take while the release gate pauses them. */
internal object ReleaseGateEvents {
    /**
     * The connections' events that no list read gives back: an authentication's verdict (with `key_changed`, whose warning
     * must not be lost behind the update) and its key.
     */
    val SOCIAL_ALWAYS: Set<String> = setOf("connection.authenticate.result", "connection.authenticate.key")

    /**
     * Whether [taker] takes an event of [type]. Not [paused]: every one. Paused: the devices' and a pairing's (in no list),
     * presence and location (each sends nothing while [VaultManager.featuresGated]; a stop is always taken), and
     * [SOCIAL_ALWAYS]. Those that only change a list (messages, asks, items, sharing, votes, attachments) wait: the lists
     * are read again when the pause lifts ([ReleasePauseSteps]). The feed follows its own predicate.
     */
    fun takes(taker: EventTaker, type: String, paused: Boolean): Boolean = !paused || when (taker) {
        EventTaker.DEVICES, EventTaker.PAIRING, EventTaker.PRESENCE, EventTaker.LOCATION -> true
        EventTaker.SOCIAL -> type in SOCIAL_ALWAYS
        EventTaker.ITEMS, EventTaker.SHARING, EventTaker.VOTES, EventTaker.ATTACHMENTS -> false
    }
}

/**
 * (Rule 3) A change of the release gate's state, told by [ReleaseUpdateManager.onGateChange] after a read or the update:
 * [paused] and [required] as they are now, which of them moved ([pausedChanged], [requiredChanged]), and [owedReads]: an
 * opening lifted a failed check's pause without telling (a lock and an unlock conflated into one open phase, which runs no
 * opened steps), so the lift's reads are owed once this opening's check is over.
 */
data class GateChange(
    val paused: Boolean,
    val required: Boolean,
    val pausedChanged: Boolean,
    val requiredChanged: Boolean,
    val owedReads: Boolean = false,
)

/**
 * (Rule 3) What the vault manager does when the release gate changes ([ReleaseUpdateManager.onGateChange]). Paused: the
 * votes' hourly work stops. With the vault open and not being deleted ([unlocked]): a lifted pause (or reads an opening
 * owed) runs the opened vault's reads once ([reads]), as after an unlock; the end of [required] (the check over, the vault
 * not behind) starts what sends ([sends]: the votes' work, presence, location, the notifications held during the check).
 * Not while the owner check is due or held ([checkGated], §3.6.3): those wait for the check to lift ([ownerCheckLifted]).
 * The location sharing is told of every change ([gateChanged], lifted when nothing is [required]).
 */
@Suppress("LongParameterList") // each step named
internal class ReleasePauseSteps(
    private val unlocked: () -> Boolean,
    private val checkGated: () -> Boolean,
    private val paused: () -> Boolean,
    private val required: () -> Boolean,
    private val stopVotes: () -> Unit,
    private val gateChanged: (lifted: Boolean) -> Unit,
    private val reads: () -> Unit,
    private val sends: () -> Unit,
    /** What an opening that continued owes, its own reads having run ([owedReads]). */
    private val owed: () -> Unit = {},
) {
    /** A lift's reads, and the end of the check's sends, that came while the owner check was due or held. */
    private var readsWaiting = false
    private var sendsWaiting = false

    fun onGate(c: GateChange) {
        val steps = ClearSteps()
        steps.step { gateChanged(!c.required) }
        if (c.paused) {
            synchronized(this) {
                readsWaiting = false
                sendsWaiting = false
            }
            steps.step { stopVotes() }
        } else if (unlocked()) {
            val readsDue = c.pausedChanged || c.owedReads
            val sendsDue = c.requiredChanged && !c.required
            if (checkGated()) {
                synchronized(this) {
                    readsWaiting = readsWaiting || readsDue
                    sendsWaiting = sendsWaiting || sendsDue
                }
            } else {
                if (readsDue) steps.step { reads() }
                if (sendsDue) steps.step { sends() }
            }
        }
        steps.finish()
    }

    /**
     * The owner check passed or its hold ended with the vault open: the reads and the sends that waited for it run now,
     * once (unless the gate paused the work, or required the update, again meanwhile).
     */
    fun ownerCheckLifted() {
        val (r, s) = synchronized(this) {
            if (!unlocked() || checkGated()) return
            val r = readsWaiting && !paused()
            val s = sendsWaiting && !required()
            if (r) readsWaiting = false
            if (s) sendsWaiting = false
            r to s
        }
        val steps = ClearSteps()
        if (r) steps.step { reads() }
        if (s) steps.step { sends() }
        steps.finish()
    }

    /**
     * (ANDROID-PLAN 0.1.134) An opening that continued owes what follows a lift's reads (a waiting-asks replay its pause
     * kept): [owed] now, its own reads having run; when the owner check is due or held, the lift's reads when it lifts;
     * while the gate pauses, when the pause lifts.
     */
    fun owedReads() {
        if (paused() || !unlocked()) return
        val run = synchronized(this) {
            if (checkGated()) {
                readsWaiting = true
                false
            } else {
                true
            }
        }
        if (run) owed()
    }

    /** The vault locked: what waited for the owner check goes with it. */
    fun reset() = synchronized(this) {
        readsWaiting = false
        sendsWaiting = false
    }
}

/**
 * (Rule 3) What the vault's outward work (presence, location, the votes) waits for: the owner check, the gate's pause
 * ([featuresGated]), and also the release check itself ([checking]): nothing is sent for a vault whose release is not
 * known to be current. The reads of an opening run meanwhile.
 */
internal fun sendsGated(featuresGated: Boolean, checking: Boolean): Boolean = featuresGated || checking

/**
 * (Rule 3, parity with iOS) How a failed read of the manifest counts once this opening's check has passed. Only a failure
 * that says the manifest could not be reached ([unavailable]: the network or the transport, no answer, the server answering
 * without the document, busy or paused) keeps the last verified answer, retried quietly. Every other failure (the
 * signature, `MANIFEST_OLDER`, a release that does not match or cannot be placed, anything unexpected) counts as a
 * verification failure and holds the gate as the first check does: fail closed.
 */
internal object ReleaseCheckFailure {
    val UNAVAILABLE: Set<FailureKind> = setOf(
        FailureKind.NETWORK,
        FailureKind.NO_RESPONSE,
        FailureKind.MANIFEST_UNAVAILABLE,
        FailureKind.VAULT_UNAVAILABLE,
        FailureKind.SERVICE_PAUSED,
        FailureKind.RATE_LIMITED,
    )

    fun unavailable(kind: FailureKind): Boolean = kind in UNAVAILABLE
}

/**
 * (Rule 3) A waiting-asks replay (ANDROID-PLAN 0.1.132) that came due while the release gate paused the work: kept with
 * its window open ([hold]) and run when the pause lifts ([take]), after the lift's reads. A lock drops it ([drop]); its
 * window goes with the feed's clear anyway.
 */
internal class HeldAskReplay {
    data class Due(val gen: Int, val listMark: Long, val token: Int)

    private val due = java.util.concurrent.atomic.AtomicReference<Due?>(null)

    fun hold(d: Due) = due.set(d)

    fun take(): Due? = due.getAndSet(null)

    fun held(): Boolean = due.get() != null

    fun drop() = due.set(null)
}

/**
 * The opened vault's reads that the release gate holds back ([paused]): run now when it does not, else when it lifts
 * ([ReleasePauseSteps]). The rest of an opening (the relay, the connections' reopening, the manifest's read) runs whatever.
 */
internal fun openedReads(paused: Boolean, reads: () -> Unit) {
    if (!paused) reads()
}

/**
 * (0.48.0) After a new credential: the clone alarm is read and the owner check's state with it whatever the release gate
 * says; the items only when it does not pause them ([paused]: read when it lifts).
 */
internal suspend fun afterCredentialReset(paused: Boolean, readAlarm: suspend () -> Unit, onOpened: () -> Unit, readItems: () -> Unit) {
    runCatching { readAlarm() }
    onOpened()
    if (!paused) readItems()
}

/**
 * (Rule 3) The offer the release gate decides from, from the trusted manifest's [releases], failing closed: what cannot
 * be placed in it never counts as current. Release 0 (none recorded), and a fingerprint listed under another number,
 * throw a [VaultFailure] of [FailureKind.MANIFEST]; a release the manifest does not list is behind a newer `active` one
 * (offered as `removed`: [UpdateNoticeKind.ENDED]), and throws when none is newer.
 */
internal object ReleaseGateOffer {
    /** This phone recorded no release number for the vault. */
    const val CODE_UNVERIFIABLE = "release_unverifiable"

    /** The vault's fingerprint is listed under another release number. */
    const val CODE_MISMATCH = "release_mismatch"

    /** The vault's release is not listed, and no newer `active` one is. */
    const val CODE_UNLISTED = "release_unlisted"

    fun checkNumber(number: Long) {
        if (number == 0L) throw VaultFailure(FailureKind.MANIFEST, CODE_UNVERIFIABLE)
    }

    /**
     * The offer for a vault on [number], sealed to [release] (its PCR0; empty when this phone recorded none, then found by
     * number). [byPcr0]: the manifest's own lookup. Null when nothing newer is listed.
     */
    fun of(
        release: String,
        number: Long,
        releases: List<Release>,
        byPcr0: (String) -> Release? = { p -> releases.firstOrNull { it.pcr0 == p } },
    ): ReleaseUpdateOffer? {
        checkNumber(number)
        val listed = if (release.isNotEmpty()) byPcr0(release) else releases.firstOrNull { it.number == number }
        if (listed != null && listed.number != number) throw VaultFailure(FailureKind.MANIFEST, CODE_MISMATCH)
        val newest = releases.lastOrNull { it.status == com.vettid.core.attestation.manifest.ReleaseStatus.ACTIVE }
        val sealed = listed?.let(::view) ?: unlisted(release, number, newest)
        val between = newest?.let { n -> releases.filter { it.number > sealed.number && it.number < n.number }.map(::view) }.orEmpty()
        return ReleaseUpdateOffer.of(sealed, newest?.let(::view), between)
    }

    /** The vault's release as this phone recorded it, behind [newest] as one that has ended; unverifiable with none newer. */
    private fun unlisted(release: String, number: Long, newest: Release?): ReleaseView {
        if (newest == null || newest.number <= number) throw VaultFailure(FailureKind.MANIFEST, CODE_UNLISTED)
        return ReleaseView(number, release, ReleaseUpdateOffer.STATUS_REMOVED, null, "")
    }

    private fun view(r: Release) = ReleaseView(r.number, r.pcr0, r.status.wire, r.endsAt, r.notes)
}
