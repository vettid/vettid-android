package com.vettid.core.ui.components

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.vettid.core.ui.R
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.Locale

/**
 * The approvals indicator (ANDROID-PLAN §4 "The approvals indicator", D9): [count] open approvals (above 0: the shell
 * provides none at 0), and while one is time-critical ([critical]) the countdown to [deadline] (null: the clock alone),
 * in the urgency colours when [urgent], else neutral. [label] names the soonest time-critical entry for the description.
 */
@Immutable
data class ApprovalsIndicator(
    val count: Int,
    val critical: Boolean = false,
    val urgent: Boolean = false,
    val deadline: Instant? = null,
    val label: String? = null,
    val onClick: () -> Unit,
)

/**
 * The indicator the top bars show (left of the bell; on the detail screens before their actions). The shell provides it
 * inside the unlocked shell only, and null on Approvals, on a request's screen, while the vault is held or the daily
 * check is due, and at 0.
 */
val LocalApprovalsIndicator = compositionLocalOf<ApprovalsIndicator?> { null }

/** [content] without the approvals indicator: a camera or a photo inside a screen of the shell (2). */
@Composable
fun WithoutApprovalsIndicator(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalApprovalsIndicator provides null, content = content)

private const val MAX_COUNT = 99
private const val SECOND_MS = 1_000L
private const val SECONDS_PER_MINUTE = 60L

/** Font scale and width from which the urgent state is the icon with a clock badge, never the pill (4). */
private const val PILL_MAX_FONT_SCALE = 1.5f
private const val PILL_MIN_WIDTH_DP = 360

/**
 * The indicator: the Approvals glyph with its count badge, or (time-critical, room allowing) the pill with a clock and
 * `M:SS`. [compact] (a detail screen with its own actions) keeps it to the icon. One control, 48 dp, one description.
 */
@Composable
fun ApprovalsIndicatorButton(indicator: ApprovalsIndicator, modifier: Modifier = Modifier, compact: Boolean = false) {
    val config = LocalConfiguration.current
    val narrow = compact || config.fontScale >= PILL_MAX_FONT_SCALE || config.screenWidthDp < PILL_MIN_WIDTH_DP
    val pill = indicator.critical && !narrow
    // The one-second tick runs only while the screen is in front (3): for the pill, or while TalkBack explores by touch
    // for the icon's description to name the time left (it needs the entry's name); it announces nothing.
    val started = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    val ticks = started && indicator.critical && indicator.deadline != null &&
        (pill || (indicator.label != null && touchExploration()))
    val left = if (ticks) secondsLeft(indicator.deadline!!) else null
    val description = description(indicator, left)
    if (pill) {
        Pill(indicator, left, description, modifier)
    } else {
        Box(modifier) {
            IconButton(
                onClick = indicator.onClick,
                // A 48 dp target (11), not the icon button's 40 dp.
                modifier = Modifier
                    .sizeIn(minWidth = Spacing.touchTarget, minHeight = Spacing.touchTarget)
                    .testTag("top_bar_approvals")
                    .semantics { contentDescription = description },
            ) {
                Icon(Icons.Outlined.TaskAlt, contentDescription = null)
            }
            val badge = Modifier.align(Alignment.TopEnd).offset(y = 4.dp)
            if (indicator.critical) ClockBadge(indicator.urgent, badge) else CountBadgeSmall(indicator.count, badge)
        }
    }
}

@Composable
private fun Pill(indicator: ApprovalsIndicator, left: Long?, description: String, modifier: Modifier) {
    val (background, content) = timeColors(indicator.urgent, MaterialTheme.colorScheme)
    Box(
        modifier = modifier
            .heightIn(min = Spacing.touchTarget)
            .defaultMinSize(minWidth = Spacing.touchTarget)
            .clip(VettIdShape.pill)
            .clickable(role = Role.Button, onClick = indicator.onClick)
            .semantics(mergeDescendants = false) {
                contentDescription = description
                role = Role.Button
            }
            .testTag(if (indicator.urgent) "approvals_pill_urgent" else "approvals_pill"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(VettIdShape.pill)
                .background(background)
                .padding(horizontal = Spacing.s, vertical = Spacing.xs)
                .clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Schedule, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            if (left != null) {
                Text(
                    text = stringResource(R.string.approvals_indicator_time, left / SECONDS_PER_MINUTE, left % SECONDS_PER_MINUTE),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = content,
                    modifier = Modifier.testTag("approvals_pill_time"),
                )
            }
        }
    }
}

@Composable
private fun CountBadgeSmall(count: Int, modifier: Modifier) {
    val (background, content) = countColors(MaterialTheme.colorScheme)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .clip(VettIdShape.pill)
            .background(background)
            .padding(horizontal = Spacing.xs)
            .testTag("approvals_badge")
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > MAX_COUNT) "$MAX_COUNT+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = content,
        )
    }
}

@Composable
private fun ClockBadge(urgent: Boolean, modifier: Modifier) {
    val (background, content) = timeColors(urgent, MaterialTheme.colorScheme)
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(VettIdShape.pill)
            .background(background)
            .testTag(if (urgent) "approvals_clock_badge_urgent" else "approvals_clock_badge")
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Schedule,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * The pill's and the clock badge's colours (4): the urgency colours (the error container and its on-colour) while an
 * entry is urgent, else the neutral surface; background first.
 */
internal fun timeColors(urgent: Boolean, scheme: ColorScheme): Pair<Color, Color> =
    if (urgent) scheme.errorContainer to scheme.onErrorContainer else scheme.surfaceVariant to scheme.onSurfaceVariant

/** The count badge's colours (4): the drawer's gold `CountBadge`, never red (red is the bell's alarm). */
internal fun countColors(scheme: ColorScheme): Pair<Color, Color> = scheme.primaryContainer to scheme.onPrimaryContainer

/** Whether TalkBack (or another service) explores by touch, followed as it changes. */
@Composable
private fun touchExploration(): Boolean {
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java) ?: return false
    var on by remember(manager) { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/** Whole seconds left to [deadline], 0 at and after it, read again each second while composed. */
@Composable
private fun secondsLeft(deadline: Instant): Long {
    val left by produceState(initialValue = secondsUntil(deadline), deadline) {
        while (true) {
            value = secondsUntil(deadline)
            if (value <= 0) break
            // Wake at the next whole second of the time left.
            val ms = deadline.toEpochMilli() - System.currentTimeMillis()
            delay((ms % SECOND_MS).takeIf { it > 0 } ?: SECOND_MS)
        }
    }
    return left
}

/** Seconds left, rounded up (a deadline 80.4 s away reads 1:21 until 80 s remain), never below 0. */
internal fun secondsUntil(deadline: Instant, nowMs: Long = System.currentTimeMillis()): Long =
    ((deadline.toEpochMilli() - nowMs + SECOND_MS - 1) / SECOND_MS).coerceAtLeast(0)

/** "Approvals, 3 waiting", and with a known soonest deadline and its label "… Alex expires in 1 minute 20 seconds". */
@Composable
private fun description(indicator: ApprovalsIndicator, left: Long?): String {
    val count = indicator.count
    // A time left is only known (left) while the indicator is time-critical and ticks.
    if (left != null && left > 0 && indicator.label != null) {
        // The configuration's first locale (observed, so a locale change speaks the new one).
        val locales = LocalConfiguration.current.locales
        val locale = if (locales.isEmpty) Locale.ROOT else locales[0]
        val time = spokenDuration(left, locale)
        return pluralStringResource(R.plurals.approvals_indicator_description_urgent, count, count, indicator.label, time)
    }
    return pluralStringResource(R.plurals.approvals_indicator_description, count, count)
}

/** "1 minute 20 seconds", "45 seconds" (`MeasureFormat`, never built from pieces). */
internal fun spokenDuration(seconds: Long, locale: Locale): String {
    val format = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE)
    val m = seconds / SECONDS_PER_MINUTE
    val s = seconds % SECONDS_PER_MINUTE
    return when {
        m == 0L -> format.formatMeasures(Measure(s, MeasureUnit.SECOND))
        s == 0L -> format.formatMeasures(Measure(m, MeasureUnit.MINUTE))
        else -> format.formatMeasures(Measure(m, MeasureUnit.MINUTE), Measure(s, MeasureUnit.SECOND))
    }
}
