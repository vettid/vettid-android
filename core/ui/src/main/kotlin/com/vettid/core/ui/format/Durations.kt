package com.vettid.core.ui.format

import androidx.annotation.StringRes
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
 * a day is "24 hours", as the texts always said); from 10 minutes anything else is in whole minutes, rounded up; under
 * 10 minutes in seconds (2.5 minutes is "150 seconds").
 */
object Durations {
    enum class Unit { DAYS, HOURS, MINUTES, SECONDS }

    /** The unit and the count [seconds] is said in. */
    fun parts(seconds: Long): Pair<Unit, Long> {
        val s = seconds.coerceAtLeast(0)
        return when {
            s >= 2 * DAY && s % DAY == 0L -> Unit.DAYS to s / DAY
            s >= HOUR && s % HOUR == 0L -> Unit.HOURS to s / HOUR
            s >= SECONDS_UP_TO -> Unit.MINUTES to (s + MINUTE - 1) / MINUTE
            else -> Unit.SECONDS to s
        }
    }

    /** Under 10 minutes a duration is said in seconds ("150 seconds"), as on iOS. */
    const val SECONDS_UP_TO = 600L
    const val MINUTE = 60L
    const val HOUR = 3_600L
    const val DAY = 86_400L
}

/**
 * The vault's time scale as the screens need it (STAGING-TIME-SCALE §5.2), provided at the app's root ([LocalTimeScale]):
 * [scale] divides the waits of 1 hour or more; [stagingNote]: a staging build shows "Staging: 1 day = 1 hour" next to
 * them. [scaledTexts]: a failure text that says an approved duration in words in real time ("one to three days") and
 * its `_scaled` variant, with the durations computed, used on a vault with a time scale ([failureText]; owner decision
 * 2026-10-19).
 */
data class UiTimeScale(val scale: Long = 1, val stagingNote: Boolean = false, val scaledTexts: Map<Int, Int> = emptyMap()) {
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

/**
 * The staging note as a paragraph to add to a text that cannot hold the composable (a dialog's, a banner's): "" unless a
 * staging build shows it.
 */
@Composable
fun stagingNoteSuffix(): String =
    if (LocalTimeScale.current.stagingNote) "\n\n" + stringResource(R.string.core_ui_time_scale_staging) else ""

/** [StagingTimeNote] when the app's root says a staging build shows it ([UiTimeScale.stagingNote]). */
@Composable
fun StagingTimeNoteIfShown(modifier: Modifier = Modifier) {
    if (LocalTimeScale.current.stagingNote) StagingTimeNote(modifier)
}

/**
 * A failure's text ([res]: `FailureKind.messageRes()`), with the recovery's waits a text may name: the code delay
 * (`%1$s`, 24 hours) and the end of the quiet period (`%2$s`, 3 days), both divided by the vault's time scale. A text
 * without them ignores them. In real time a text with approved words for a duration is shown as approved; on a vault
 * with a time scale its `_scaled` variant ([UiTimeScale.scaledTexts]) is shown instead, with the durations computed.
 */
@Composable
fun failureText(@StringRes res: Int): String {
    val t = LocalTimeScale.current
    val shown = if (t.scale > 1) t.scaledTexts[res] ?: res else res
    return stringResource(shown, scaledDurationText(Durations.DAY), scaledDurationText(RECOVERY_QUIET_S))
}

/** The recovery quiet period in real time (VAULT-MESSAGING §11.14.5: 72 h), a wait the time scale divides (S7). */
const val RECOVERY_QUIET_S = 259_200L

/** [seconds] in words: "7 days", "24 hours", "150 seconds". */
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
