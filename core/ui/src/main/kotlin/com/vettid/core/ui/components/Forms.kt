package com.vettid.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.vettid.core.ui.R
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape
import com.vettid.core.ui.theme.VettIdTheme

/**
 * A secret text field (vault PIN, account PIN, credential password): masked,
 * with a show/hide toggle. [isPin] switches to the digit keyboard. Nothing is
 * saved across process death: callers keep secrets in ViewModel state only.
 * Excluded from autofill: password managers neither fill nor offer to save it
 * ([excludeFromAutofill]).
 */
@Composable
fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isPin: Boolean = false,
    error: String? = null,
    supporting: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    enabled: Boolean = true,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onValueChange(if (isPin) v.filter { it in '0'..'9' } else v) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = (error ?: supporting)?.let { t -> { Text(t) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPin) KeyboardType.NumberPassword else KeyboardType.Password,
            imeAction = imeAction,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.core_ui_cd_hide_secret else R.string.core_ui_cd_show_secret,
                        label,
                    ),
                )
            }
        },
        modifier = modifier.excludeFromAutofill().fillMaxWidth(),
    )
}

/**
 * A password strength meter: [level] of 4 segments filled (0: too short), with
 * [label] read out for accessibility.
 */
@Composable
fun StrengthMeter(level: Int, label: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val fill = when (level) {
        0, 1 -> colors.error
        2 -> VettIdTheme.colors.warning
        3 -> colors.primary
        else -> VettIdTheme.colors.success
    }
    val description = stringResource(R.string.core_ui_cd_strength, label)
    Column(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            repeat(SEGMENTS) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .background(if (i < level) fill else colors.surfaceContainerHigh, RoundedCornerShape(3.dp)),
                )
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    }
}

private const val SEGMENTS = 4

/**
 * A step of a form flow (onboarding, unlock, credential and settings forms):
 * optional back arrow, a heading and body, the [content], and the primary
 * (and optional secondary) action pinned to the bottom above the keyboard.
 */
@Composable
fun FormScaffold(
    title: String,
    primaryLabel: String?,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    onBack: (() -> Unit)? = null,
    primaryEnabled: Boolean = true,
    busy: Boolean = false,
    destructive: Boolean = false,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    header: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            if (onBack != null) {
                VettIdBackTopBar(onBackClick = onBack)
            } else {
                Spacer(Modifier.statusBarsPadding().height(Spacing.xl))
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.xl),
            ) {
                header()
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .padding(top = Spacing.s, bottom = Spacing.m)
                        .semantics { heading() },
                )
                if (body != null) {
                    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Spacing.xl))
                }
                content()
                Spacer(Modifier.height(Spacing.xl))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.xl, vertical = Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                if (primaryLabel != null) {
                    PrimaryButton(primaryLabel, onPrimary, enabled = primaryEnabled && !busy, busy = busy, destructive = destructive)
                }
                if (secondaryLabel != null) {
                    TextButton(
                        onClick = onSecondary,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget),
                    ) {
                        Text(secondaryLabel, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

/** The gold primary action (filled), full width; [destructive] uses the error colour. */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    destructive: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = VettIdShape.pill,
        colors = if (destructive) {
            ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError)
        } else {
            ButtonDefaults.buttonColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer)
        },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .testTag("primary_button"),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.onPrimaryContainer)
            Spacer(Modifier.width(Spacing.m))
        }
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

/** A secondary (outlined) action, full width. */
@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = VettIdShape.pill,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** How serious a [NoticeCard] is. */
enum class NoticeKind { INFO, SUCCESS, WARNING, URGENT }

/**
 * A notice in a form or detail screen: release updates, warnings before an
 * irreversible choice, the clone alarm. [actions] go under the text.
 */
@Composable
fun NoticeCard(
    kind: NoticeKind,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val (accent: Color, icon: ImageVector) = when (kind) {
        NoticeKind.INFO -> colors.primary to Icons.Outlined.Info
        NoticeKind.SUCCESS -> VettIdTheme.colors.success to Icons.Outlined.CheckCircle
        NoticeKind.WARNING -> VettIdTheme.colors.warning to Icons.Outlined.WarningAmber
        NoticeKind.URGENT -> colors.error to Icons.Outlined.ReportProblem
    }
    Surface(
        shape = VettIdShape.card,
        color = colors.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = actions == null) {},
    ) {
        Row(Modifier.padding(Spacing.l)) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(Spacing.xs))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                if (actions != null) {
                    Spacer(Modifier.height(Spacing.s))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), content = actions)
                }
            }
        }
    }
}

/**
 * A non-blocking information banner above every screen (the vault service paused for maintenance, MEMBER-API
 * 1.2.0; the owner check's early warning and "hold is off", VAULT-MESSAGING §3.6.5): one line and an optional
 * action, nothing behind it is disabled. Pads for the status bar itself unless [statusBarPadding] is false (a
 * banner under another one).
 */
@Composable
fun InfoBanner(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    statusBarPadding: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerHigh, contentColor = colors.onSurface, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .then(if (statusBarPadding) Modifier.statusBarsPadding() else Modifier)
                .heightIn(min = Spacing.touchTarget)
                .padding(start = Spacing.l, end = if (actionLabel != null) Spacing.s else Spacing.l, top = Spacing.s, bottom = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = VettIdTheme.colors.warning)
                Spacer(Modifier.width(Spacing.m))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            if (actionLabel != null) {
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
                    Text(actionLabel, color = colors.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * The urgent banner above every top-level screen (the clone alarm, §3.5.9):
 * error colour, one line and an action. Pads for the status bar itself.
 */
@Composable
fun UrgentBanner(text: String, actionLabel: String, onClick: () -> Unit, modifier: Modifier = Modifier, statusBarPadding: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.error, contentColor = colors.onError, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .then(if (statusBarPadding) Modifier.statusBarsPadding() else Modifier)
                .padding(start = Spacing.l, end = Spacing.s, top = Spacing.s, bottom = Spacing.s)
                .semantics { liveRegion = LiveRegionMode.Assertive },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
            Spacer(Modifier.width(Spacing.m))
            Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
                Text(actionLabel, color = colors.onError, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** The state of one [StepList] step. */
enum class StepState { PENDING, ACTIVE, DONE, FAILED }

/** A vertical list of steps with their state (the enrollment progress). */
@Composable
fun StepList(steps: List<Pair<String, StepState>>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        steps.forEach { (label, state) ->
            val stateLabel = stringResource(
                when (state) {
                    StepState.PENDING -> R.string.core_ui_step_pending
                    StepState.ACTIVE -> R.string.core_ui_step_active
                    StepState.DONE -> R.string.core_ui_step_done
                    StepState.FAILED -> R.string.core_ui_step_failed
                },
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .clearAndSetSemantics { contentDescription = "$label: $stateLabel" },
            ) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    when (state) {
                        StepState.ACTIVE -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.primary)
                        StepState.DONE -> Icon(Icons.Outlined.CheckCircle, null, tint = VettIdTheme.colors.success)
                        StepState.FAILED -> Icon(Icons.Outlined.ErrorOutline, null, tint = colors.error)
                        StepState.PENDING -> Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = colors.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(Spacing.m))
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state == StepState.PENDING) colors.onSurfaceVariant else colors.onSurface,
                )
            }
        }
    }
}

/** A centred busy indicator for a whole screen. */
@Composable
fun FullScreenProgress(label: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RookLogo(height = 72.dp)
            Spacer(Modifier.height(Spacing.xl))
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { contentDescription = label },
            )
        }
    }
}
