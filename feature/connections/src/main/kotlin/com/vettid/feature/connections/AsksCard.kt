package com.vettid.feature.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.vettid.core.data.social.AskState
import com.vettid.core.ui.components.DetailCard
import com.vettid.core.ui.components.SecondaryButton
import com.vettid.core.ui.format.Times
import com.vettid.core.ui.theme.Spacing

/** What the connection page offers for a connection's asks (VAULT-MESSAGING 0.23.0 §10.4.1). */
enum class AsksAction {
    MUTE,
    UNMUTE,

    /** `connection.asks.resume` while paused. */
    RESUME,

    /** `connection.asks.resume` without a pause: clears the cooldowns of declined asks. */
    ALLOW_AGAIN,
}

/**
 * A connection's asks as the page shows them: muted, paused and the declined asks in cooldown, with the actions
 * that end each state. Unmuting does not resume a pause, and resuming does not unmute (§10.4.1), so both can be
 * offered at once. Null for a vault before 0.23.0 (no `asks` on the connection): nothing is shown or offered.
 */
data class AsksView(
    val muted: Boolean,
    val paused: Boolean,
    val pausedAt: java.time.Instant?,
    val cooldowns: Int,
    val actions: List<AsksAction>,
) {
    /** A state the member should see before the rest of the page. */
    val prominent: Boolean get() = muted || paused

    companion object {
        fun of(a: AskState?): AsksView? = a?.let {
            AsksView(
                muted = it.muted,
                paused = it.paused,
                pausedAt = it.pausedAt,
                cooldowns = it.cooldowns,
                actions = listOfNotNull(
                    when {
                        it.paused -> AsksAction.RESUME
                        it.cooldowns > 0 -> AsksAction.ALLOW_AGAIN
                        else -> null
                    },
                    if (it.muted) AsksAction.UNMUTE else AsksAction.MUTE,
                ),
            )
        }
    }
}

/**
 * "Requests from <First>" (§10.4.1): paused after several declines ("<First>'s requests are paused after you
 * declined several", with Resume; removing is in the pill), muted (with Unmute), or the usual state (with Mute), and
 * how many declined asks are refused for 7 days. The connection never learns which: it sees "not accepted".
 */
@Composable
internal fun AsksCard(view: AsksView, name: String?, busy: Boolean, onMute: (Boolean) -> Unit, onResume: () -> Unit) {
    val who = name ?: stringResource(R.string.connections_share_theirs_unnamed)
    DetailCard(Modifier.testTag("asks_card")) {
        Text(
            name?.let { stringResource(R.string.connections_asks_title, it) } ?: stringResource(R.string.connections_asks_title_unnamed),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.s))
        AsksState(view, who)
        view.actions.forEach { a ->
            Spacer(Modifier.height(Spacing.s))
            SecondaryButton(
                stringResource(actionLabel(a)),
                {
                    when (a) {
                        AsksAction.MUTE -> onMute(true)
                        AsksAction.UNMUTE -> onMute(false)
                        AsksAction.RESUME, AsksAction.ALLOW_AGAIN -> onResume()
                    }
                },
                enabled = !busy,
                modifier = Modifier.testTag("asks_${a.name.lowercase()}"),
            )
        }
    }
}

private fun actionLabel(a: AsksAction): Int = when (a) {
    AsksAction.MUTE -> R.string.connections_asks_mute
    AsksAction.UNMUTE -> R.string.connections_asks_unmute
    AsksAction.RESUME -> R.string.connections_asks_resume
    AsksAction.ALLOW_AGAIN -> R.string.connections_asks_allow_again
}

/** Paused (since when), muted, or the usual state; the cooldowns; and that the connection is never told which. */
@Composable
private fun AsksState(view: AsksView, who: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (view.paused) {
            StateTitle(R.string.connections_asks_paused_title, MaterialTheme.colorScheme.error, "asks_paused")
            Text(stringResource(R.string.connections_asks_paused, who))
            view.pausedAt?.let {
                Text(
                    stringResource(R.string.connections_asks_paused_since, Times.full(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (view.muted) {
            StateTitle(R.string.connections_asks_muted_title, MaterialTheme.colorScheme.primary, "asks_muted")
            Text(stringResource(R.string.connections_asks_muted, who))
        }
        if (!view.paused && !view.muted) {
            Text(stringResource(R.string.connections_asks_normal, who), modifier = Modifier.testTag("asks_normal"))
        }
        if (view.cooldowns > 0 && !view.paused) {
            Text(
                pluralStringResource(R.plurals.connections_asks_cooldowns, view.cooldowns, view.cooldowns),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("asks_cooldowns"),
            )
        }
        Text(
            stringResource(R.string.connections_asks_neutral, who),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StateTitle(text: Int, color: androidx.compose.ui.graphics.Color, tag: String) {
    Text(stringResource(text), color = color, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag(tag))
}
