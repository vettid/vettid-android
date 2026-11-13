package com.vettid.core.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.VettIdShape

@Immutable
data class PillAction(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    /** Shown but not usable (the bar's [hint] may say why). */
    val enabled: Boolean = true,
    /** Read by TalkBack with the action (why it is disabled, for one). */
    val stateDescription: String? = null,
)

/** Floating pill of icon actions at the bottom of a detail screen (Proton); [hint], a short line above it. */
@Composable
fun BoxScope.FloatingPillActionBar(
    actions: List<PillAction>,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    Column(
        modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = Spacing.l, start = Spacing.gutter, end = Spacing.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (hint != null) {
            Surface(
                shape = VettIdShape.pill,
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 3.dp,
                modifier = Modifier.padding(bottom = Spacing.s).testTag("pill_hint"),
            ) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
                )
            }
        }
        Surface(
            shape = VettIdShape.pill,
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 3.dp,
        ) {
            Row(Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs)) {
                actions.forEach { action ->
                    IconButton(
                        onClick = action.onClick,
                        enabled = action.enabled,
                        modifier = Modifier
                            .padding(horizontal = Spacing.xxs)
                            .semantics { action.stateDescription?.let { stateDescription = it } },
                    ) {
                        val tint = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        Icon(
                            imageVector = action.icon,
                            contentDescription = action.contentDescription,
                            tint = if (action.enabled) tint else tint.copy(alpha = DISABLED_ALPHA),
                        )
                    }
                }
            }
        }
    }
}

/** Material's alpha for disabled content. */
private const val DISABLED_ALPHA = 0.38f
