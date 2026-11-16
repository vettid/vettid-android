package com.vettid.core.data.vault

import com.vettid.core.crypto.altchan.TimeScale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Duration

/**
 * The time scale of this phone's vault as the app knows it (STAGING-TIME-SCALE §5.2; VAULT-MESSAGING 0.43.0 §11.10.9):
 * read from the descriptor of its routed instance and from a transfer bundle, stored per vault in the account record.
 * 1 in production. At 24 (staging) the vault and this app divide their waits of 1 hour or more by 24, each when it
 * starts; settings keep their real units.
 *
 * [known]: the app has read its vault's scale (false on a phone without a vault, or before the first read).
 * [stagingBuild]: a staging build, which adds the note "Staging: 1 day = 1 hour" where a scaled wait is shown.
 */
data class TimeScaleView(
    val scale: Long = TimeScale.REAL,
    val known: Boolean = false,
    val stagingBuild: Boolean = false,
) {
    /** The vault's waits are shorter than real time. */
    val scaled: Boolean get() = scale != TimeScale.REAL

    /** [seconds] of a wait that scales, as it runs now (divided, rounded up to a whole second, STAGING-TIME-SCALE §3). */
    fun scaledSeconds(seconds: Long): Long = TimeScales.divide(seconds, scale)

    /** As [scaledSeconds], for a [Duration]. */
    fun scaled(d: Duration): Duration = Duration.ofSeconds(scaledSeconds(d.seconds))

    /**
     * Whether a scaled wait shown on screen carries the note "Staging: 1 day = 1 hour" (ANDROID-PLAN 0.1.44): a staging
     * build whose vault runs scaled, or one that has not read its vault's scale yet (a new phone recovering a vault
     * from the staging service, which runs at 24).
     */
    val stagingNote: Boolean get() = stagingBuild && (scaled || !known)
}

/** The rule of STAGING-TIME-SCALE §3, for the app's own waits (S2, S8, S9, S10, S12, S16). */
object TimeScales {
    /** [seconds] divided by [scale], rounded up to a whole second; a scale below 1 counts as 1. */
    fun divide(seconds: Long, scale: Long): Long {
        val s = scale.coerceAtLeast(TimeScale.REAL)
        return (seconds + s - 1) / s
    }

    /** [ms] divided by [scale] in whole seconds (rounded up), as milliseconds. */
    fun divideMs(ms: Long, scale: Long): Long = divide(ms / MS_PER_S, scale) * MS_PER_S

    /**
     * (VAULT-MESSAGING 0.43.0 §11.10.8, STAGING-TIME-SCALE §5.2) The scale to store when a `vault.status` [reported] one,
     * or null to store nothing. The status comes from the vault's running enclave over the open session, so it is as
     * current as the descriptor read before the last unlock or more: it fills a scale this phone does not know (a phone
     * updated while its vault was open) and replaces one that is no longer the vault's. A value this build does not
     * accept ([accepts], the rule the descriptor is checked against: a production build 1 only) or out of range is never
     * taken, nor is an absent one: the stored scale stays until the next descriptor says otherwise.
     */
    fun fromStatus(stored: Long?, reported: Long?, accepts: (Long) -> Boolean): Long? = when {
        reported == null || reported == stored -> null
        reported !in TimeScale.REAL..TimeScale.MAX || !accepts(reported) -> null
        else -> reported
    }

    /**
     * The scale a `vault.status` [reported] runs at, for the times this phone works out from it (when a check passed, the
     * presence notice's check time, the idle lock's time, the relay re-check): the reported one only when it is in range
     * and this build accepts it ([accepts], the rule [fromStatus] stores by); otherwise real time. Which way is safe
     * depends on the use: for a pass's time a scale larger than the vault's own is the fail-open way (it moves the pass
     * later) and real time the fail-closed one; for the presence notice's check time it is the other way round (an
     * earlier check time lets an older presence end the notice), so that use also takes the stored scale
     * ([OwnerCheckManager]).
     */
    fun inForce(reported: Long?, accepts: (Long) -> Boolean): Long = when {
        reported == null || reported !in TimeScale.REAL..TimeScale.MAX || !accepts(reported) -> TimeScale.REAL
        else -> reported
    }

    /**
     * Whether this build accepts [scale] by [rule] (the channel's descriptor rule, `AltChannelFlow.acceptsTimeScale`);
     * with no rule to ask (no channel yet), nothing is accepted and [inForce] gives real time.
     */
    fun accepted(scale: Long, rule: ((Long) -> Boolean)?): Boolean = rule?.invoke(scale) == true

    private const val MS_PER_S = 1_000L
}

/** Where the screens read the time scale ([VaultManager] in the app). */
interface TimeScaleSource {
    val timeScale: StateFlow<TimeScaleView>

    /** Real time (tests, previews, and a build without a vault). */
    object RealTime : TimeScaleSource {
        override val timeScale: StateFlow<TimeScaleView> = MutableStateFlow(TimeScaleView())
    }
}
