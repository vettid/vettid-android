package com.vettid.core.data.vault

import com.vettid.core.attestation.manifest.Release

/**
 * (Rule 3: the app requires the vault's current release) Who takes a vault event while the release gate pauses the
 * vault's background work ([ReleaseUpdateManager.paused]).
 */
internal enum class EventTaker { ITEMS, SHARING, DEVICES, VOTES, PRESENCE, LOCATION, PAIRING, SOCIAL, ATTACHMENTS }

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
 * (Rule 3) What the vault manager does when the release gate's pause changes ([ReleaseUpdateManager.onPausedChange]):
 * paused, the votes' hourly work stops and location sharing pauses; lifted with the vault open and not being deleted
 * ([open]), the location sharing goes on and the opened vault's reads run once ([reads]), as after an unlock.
 */
internal class ReleasePauseSteps(
    private val open: () -> Boolean,
    private val stopVotes: () -> Unit,
    private val gateChanged: (lifted: Boolean) -> Unit,
    private val reads: () -> Unit,
) {
    fun onPaused(paused: Boolean) {
        val steps = ClearSteps()
        steps.step { gateChanged(!paused) }
        if (paused) {
            steps.step { stopVotes() }
        } else if (open()) {
            steps.step { reads() }
        }
        steps.finish()
    }
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
