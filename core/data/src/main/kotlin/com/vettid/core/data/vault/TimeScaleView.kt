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

/** The rule of STAGING-TIME-SCALE §3, for the app's own waits (S2, S9, S10, S12, S16). */
object TimeScales {
    /** [seconds] divided by [scale], rounded up to a whole second; a scale below 1 counts as 1. */
    fun divide(seconds: Long, scale: Long): Long {
        val s = scale.coerceAtLeast(TimeScale.REAL)
        return (seconds + s - 1) / s
    }

    /** [ms] divided by [scale] in whole seconds (rounded up), as milliseconds. */
    fun divideMs(ms: Long, scale: Long): Long = divide(ms / MS_PER_S, scale) * MS_PER_S

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
