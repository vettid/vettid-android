package com.vettid.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.feed.FeedBadge
import com.vettid.core.data.feed.FeedRepository
import com.vettid.core.data.prefs.NotificationMode
import com.vettid.core.data.prefs.PreferencesRepository
import com.vettid.core.data.presence.PresenceAvailability
import com.vettid.core.data.presence.PresenceRepository
import com.vettid.core.data.presence.PresenceState
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.notify.NotificationOpenInbox
import com.vettid.core.notify.NotifyVisibility
import com.vettid.core.notify.Visible
import com.vettid.core.vault.FeedItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.vettid.core.data.social.ApprovalsRepository
import com.vettid.core.data.social.InviteLinkInbox
import com.vettid.core.data.social.MessagesRepository
import com.vettid.core.data.social.needsDecision
import com.vettid.core.data.vault.ProfileRepository
import com.vettid.core.data.votes.VoteRules
import com.vettid.core.data.votes.VotesRepository
import com.vettid.core.data.wallet.WalletAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The drawer's counts: approvals waiting, conversations with unread messages. */
data class ShellBadges(
    val approvals: Int = 0,
    val unread: Int = 0,
    /** Open votes not yet voted on (ANDROID-PLAN 0.1.102); the Votes entry shows only with [votesShown]. */
    val votes: Int = 0,
    val votesShown: Boolean = false,
    /** The vault has wallets (ANDROID-PLAN §4 "Wallet"; never in a build without the wallet): the Wallet entry shows (W1c-2). */
    val walletShown: Boolean = false,
    /** The vault has location (ANDROID-PLAN §4 "Location", L1b): the Location entry shows. */
    val locationShown: Boolean = false,
)

/** A refused change of the member's status: the failure and the vault code it may name (shown with `failureText`). */
data class PresenceFailure(val kind: com.vettid.core.data.vault.FailureKind, val code: String?)

/** The app shell (drawer badges, the Notifications bell, invitation links the app was opened with). */
@HiltViewModel
@Suppress("LongParameterList") // injected: what the shell shows comes from each of these
class ShellViewModel @Inject constructor(
    private val approvals: ApprovalsRepository,
    messages: MessagesRepository,
    private val invites: InviteLinkInbox,
    profiles: ProfileRepository,
    private val feed: FeedRepository,
    private val prefs: PreferencesRepository,
    private val taps: NotificationOpenInbox,
    private val visibility: NotifyVisibility,
    votes: VotesRepository,
    wallet: WalletAccess,
    private val presence: PresenceRepository,
    location: com.vettid.core.data.location.LocationRepository = com.vettid.core.data.location.NoLocation,
) : ViewModel() {
    /** A notification's tap waiting for the shell (ANDROID-PLAN 0.1.23). */
    val tap: StateFlow<NotificationOpenInbox.Open?> = taps.pending

    fun tapTaken() = taps.consume()

    /** The asks waiting now: a batch's notification leads to the ones that are still open. */
    fun openAsks() = approvals.approvals.value

    /** The tapped item, read (not waiting for the answer); waits a little for the feed after a cold start. */
    suspend fun openItem(itemId: String): FeedItem? {
        val list = withTimeoutOrNull(ITEM_WAIT_MS) { feed.items.first { l -> l.any { it.itemId == itemId } } }
        val item = list?.firstOrNull { it.itemId == itemId }
        if (item != null) feed.markReadQuietly(itemId)
        return item
    }

    /** The screen in front, for the notifications ("nothing about what is on screen"); null in the background. */
    fun setVisible(v: Visible?) = visibility.set(v)

    /**
     * Notifications (ANDROID-PLAN 0.1.23, §9 question 13): [ask] the permission once when a mode other than Off is
     * in effect; [note] the one-time "VettID now notifies you in the background" while the mode was never chosen.
     */
    data class NotifyPrompt(val ask: Boolean = false, val note: Boolean = false)

    val notifyPrompt: StateFlow<NotifyPrompt> = prefs.preferences.map { p ->
        val on = p.effectiveNotificationMode != NotificationMode.OFF
        NotifyPrompt(ask = on && !p.notificationsAsked, note = on && p.notificationMode == null && !p.notificationsNoteShown)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NotifyPrompt())

    fun permissionAsked() {
        viewModelScope.launch { prefs.setNotificationsAsked() }
    }

    fun noteShown() {
        viewModelScope.launch { prefs.setNotificationsNoteShown() }
    }


    /** The bell's count (ANDROID-PLAN 0.1.23): unread feed items, red while one is urgent. */
    val feedBadge: StateFlow<FeedBadge> = feed.badge

    /** The member's own profile photo (§10.8, base64) for the avatar tile and the account sheet; null for none. */
    val photo: StateFlow<String?> = profiles.profile.map { it?.photo }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { runCatching { profiles.refreshProfile() } }
    }

    /** The member's own status for the avatar sheet's row (ANDROID-PLAN §4 "Presence"); null where the vault has no presence. */
    val presenceState: StateFlow<PresenceState?> = combine(presence.availability, presence.policy) { a, p ->
        p?.state?.takeIf { a == PresenceAvailability.AVAILABLE }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The sheet's picker: the same `presence.set` as Settings → Privacy → Presence. A refused change leaves the row as it was. */
    fun setPresenceState(state: PresenceState) {
        if (presenceBusyFlow.value) return
        presenceFailure.value = null
        presenceBusyFlow.value = true
        viewModelScope.launch {
            try {
                presence.update { it.copy(state = state) }
            } catch (e: VaultFailure) {
                // The row keeps the state the vault still has, and the sheet says why: a failed "Invisible" must not look done.
                presenceFailure.value = PresenceFailure(e.kind, e.shownCode)
            } finally {
                presenceBusyFlow.value = false
            }
        }
    }

    private val presenceBusyFlow = MutableStateFlow(false)

    /** A status pick is on its way to the vault: the sheet's row waits. */
    val presenceBusy: StateFlow<Boolean> = presenceBusyFlow.asStateFlow()

    /** The sheet opened: an earlier refusal is not shown again. */
    fun presenceErrorSeen() {
        presenceFailure.value = null
    }

    private val presenceFailure = MutableStateFlow<PresenceFailure?>(null)

    /** The last refused status pick of the avatar sheet, until the next pick. */
    val presenceError: StateFlow<PresenceFailure?> = presenceFailure.asStateFlow()

    /** An opened invitation link waiting for the connect flow. */
    val inviteLink: StateFlow<String?> = invites.link

    fun inviteLinkTaken() = invites.consume()

    val badges: StateFlow<ShellBadges> = combine(
        approvals.approvals,
        messages.conversations,
        combine(votes.votes, votes.reconnect, votes.availability, VoteRules::entry),
        combine(wallet.shown, location.availability) { w, l -> w to (l == com.vettid.core.data.location.LocationAvailability.AVAILABLE) },
    ) { a, c, v, (w, l) ->
        ShellBadges(
            approvals = a.count { it.needsDecision },
            unread = unreadConversations(c),
            votes = v.badge,
            votesShown = v.shown,
            walletShown = w,
            locationShown = l,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ShellBadges())

    private companion object {
        const val ITEM_WAIT_MS = 10_000L
    }
}

/** The Messages badge: conversations with unread messages. A service is never listed there, so it never counts (ANDROID-PLAN 0.1.90). */
internal fun unreadConversations(c: List<com.vettid.core.data.social.ConversationSummary>): Int =
    c.count { it.unread > 0 && !it.connection.service }
