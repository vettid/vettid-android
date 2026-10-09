package com.vettid.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.R
import com.vettid.core.data.prefs.PreferencesRepository
import com.vettid.core.data.prefs.ReleaseNoticeDismissal
import com.vettid.core.data.vault.ReleaseDismissal
import com.vettid.core.data.vault.ReleaseNotices
import com.vettid.core.data.vault.ReleaseUpdateInbox
import com.vettid.core.data.vault.ReleaseUpdateOffer
import com.vettid.core.data.vault.ReleaseUpdateRepository
import com.vettid.core.data.vault.UpdateNoticeKind
import com.vettid.core.ui.components.InfoBanner
import com.vettid.core.ui.components.UrgentBanner
import com.vettid.feature.onboarding.releaseDate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** What the shell shows of the release notice at one moment. */
data class ReleaseBannerState(
    val offer: ReleaseUpdateOffer? = null,
    val visible: Boolean = false,
    /** The notification permission was asked for once already (never again). */
    val notificationsAsked: Boolean = true,
)

/** A stored dismissal as [ReleaseNotices] reads it; null for none or an unknown kind. */
fun ReleaseNoticeDismissal.toDismissal(): ReleaseDismissal? =
    runCatching { ReleaseDismissal(release, UpdateNoticeKind.valueOf(kind), Instant.ofEpochMilli(atMs)) }.getOrNull()

/**
 * The shell's release notice (owner decision 2026-10-09): shown while the vault has an update to approve, dismissed
 * for 24 h at most ([ReleaseNotices.DISMISS_FOR]), re-evaluated as time passes; a notification tap opens the update
 * screen ([ReleaseUpdateInbox]).
 */
@HiltViewModel
class ReleaseBannerViewModel @Inject constructor(
    private val updates: ReleaseUpdateRepository,
    private val prefs: PreferencesRepository,
    private val inbox: ReleaseUpdateInbox,
) : ViewModel() {
    private val clock = flow {
        while (true) {
            emit(Instant.now())
            delay(TICK_MS)
        }
    }

    val banner: StateFlow<ReleaseBannerState> = combine(updates.offer, prefs.preferences, clock) { o, p, now ->
        ReleaseBannerState(o, ReleaseNotices.bannerVisible(o, p.releaseNoticeDismissed?.toDismissal(), now), p.notificationsAsked)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), ReleaseBannerState())

    /** A tap on the "Vault updates" notification waits here until the shell opens the update screen. */
    val openRequested: StateFlow<Boolean> = inbox.pending

    fun openTaken() = inbox.consume()

    fun dismiss() {
        val o = banner.value.offer ?: return
        viewModelScope.launch {
            prefs.setReleaseNoticeDismissed(ReleaseNoticeDismissal(o.target.number, o.kind.name, Instant.now().toEpochMilli()))
        }
    }

    /**
     * The member answered the one notification-permission request. Granted: the release on the screen counts as
     * notified (they are looking at its banner), so the next release is the first one notified.
     */
    fun permissionAnswered(granted: Boolean) {
        val o = banner.value.offer
        viewModelScope.launch {
            prefs.setNotificationsAsked()
            if (granted && o != null && o.target.number > prefs.preferences.first().releaseNotified) {
                prefs.setReleaseNotified(o.target.number)
            }
        }
    }

    fun markAsked() {
        viewModelScope.launch { prefs.setNotificationsAsked() }
    }

    private companion object {
        const val TICK_MS = 60_000L
        const val STOP_MS = 5_000L
    }
}

/**
 * The release notice above the shell's screens: "A new vault release is available (release N)", or the end-date
 * warning for a `retired` release or a `deprecated` one with an `ends_at`; "Update now" and a close button
 * (24 h). [first] says whether it is the topmost banner (which pads for the status bar).
 */
@Composable
fun ReleaseUpdateBanner(state: ReleaseBannerState, first: Boolean, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    val o = state.offer
    if (!state.visible || o == null) return
    val action = stringResource(R.string.release_banner_update)
    when (o.kind) {
        UpdateNoticeKind.AVAILABLE -> InfoBanner(
            stringResource(R.string.release_banner_available, o.target.number.toInt()),
            Modifier.testTag("release_banner"),
            action,
            onUpdate,
            statusBarPadding = first,
            onDismiss = onDismiss,
        )
        UpdateNoticeKind.ENDING, UpdateNoticeKind.ENDED -> UrgentBanner(
            when {
                o.kind == UpdateNoticeKind.ENDED -> stringResource(R.string.release_banner_ended)
                else -> o.endsAt?.let { stringResource(R.string.release_banner_ending, releaseDate(it)) }
                    ?: stringResource(R.string.release_banner_ending_soon)
            },
            action,
            onUpdate,
            Modifier.testTag("release_banner_ending"),
            statusBarPadding = first,
            onDismiss = onDismiss,
        )
    }
}
