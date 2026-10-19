package com.vettid.core.ui.format

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.vettid.core.ui.R

/**
 * Durations in words, computed from seconds (STAGING-TIME-SCALE §5.2, ANDROID-PLAN 0.1.44): no text hard-codes "24
 * hours" or "7 days" for a wait the vault's time scale divides. Two or more whole days are days, whole hours hours (so
 * a day is "24 hours", as the texts always said); anything else is in minutes, rounded up (2.5 minutes is "3
 * minutes"), and under a minute in seconds.
 */
object Durations {
    enum class Unit { DAYS, HOURS, MINUTES, SECONDS }

    /** The unit and the count [seconds] is said in. */
    fun parts(seconds: Long): Pair<Unit, Long> {
        val s = seconds.coerceAtLeast(0)
        return when {
            s >= 2 * DAY && s % DAY == 0L -> Unit.DAYS to s / DAY
            s >= HOUR && s % HOUR == 0L -> Unit.HOURS to s / HOUR
            s >= MINUTE -> Unit.MINUTES to (s + MINUTE - 1) / MINUTE
            else -> Unit.SECONDS to s
        }
    }

    const val MINUTE = 60L
    const val HOUR = 3_600L
    const val DAY = 86_400L
}

/**
 * The vault's time scale as the screens need it (STAGING-TIME-SCALE §5.2), provided at the app's root ([LocalTimeScale]):
 * [scale] divides the waits of 1 hour or more; [stagingNote]: a staging build shows "Staging: 1 day = 1 hour" next to
 * them.
 */
data class UiTimeScale(val scale: Long = 1, val stagingNote: Boolean = false) {
    /** [realSeconds] of a scaled wait as it runs (divided, rounded up to a whole second). */
    fun runs(realSeconds: Long): Long {
        val s = scale.coerceAtLeast(1)
        return (realSeconds + s - 1) / s
    }
}

/** Real time unless the app's root provides the vault's scale. */
val LocalTimeScale = compositionLocalOf { UiTimeScale() }

/** A wait the vault's time scale divides, from its real [realSeconds], in words ("24 hours", or "1 hour" at 24). */
@Composable
fun scaledDurationText(realSeconds: Long): String = durationText(LocalTimeScale.current.runs(realSeconds))

/** [StagingTimeNote] when the app's root says a staging build shows it ([UiTimeScale.stagingNote]). */
@Composable
fun StagingTimeNoteIfShown(modifier: Modifier = Modifier) {
    if (LocalTimeScale.current.stagingNote) StagingTimeNote(modifier)
}

/** [seconds] in words: "7 days", "1 hour", "3 minutes". */
@Composable
fun durationText(seconds: Long): String {
    val (unit, n) = Durations.parts(seconds)
    val count = n.toInt()
    val res = when (unit) {
        Durations.Unit.DAYS -> R.plurals.core_ui_duration_days
        Durations.Unit.HOURS -> R.plurals.core_ui_duration_hours
        Durations.Unit.MINUTES -> R.plurals.core_ui_duration_minutes
        Durations.Unit.SECONDS -> R.plurals.core_ui_duration_seconds
    }
    return pluralStringResource(res, count, count)
}

/**
 * A setting in its real units and, on a vault with a time scale, as it runs there ("24 hours (staging: 1 hour)",
 * STAGING-TIME-SCALE §3 rule 3): [real] is the setting's own text, [runsSeconds] the scaled wait.
 */
@Composable
fun scaledSettingText(real: String, scaled: Boolean, runsSeconds: Long): String =
    if (scaled) stringResource(R.string.core_ui_time_scale_setting, real, durationText(runsSeconds)) else real

/** The small note a staging build shows where a scaled wait is shown (ANDROID-PLAN 0.1.44). */
@Composable
fun StagingTimeNote(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.core_ui_time_scale_staging),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.testTag("staging_time_note"),
    )
}
