package com.vettid.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.feed.FeedBadge
import com.vettid.core.data.feed.FeedRepository
import com.vettid.core.data.prefs.NotificationMode
import com.vettid.core.data.prefs.PreferencesRepository
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The drawer's counts: approvals waiting, conversations with unread messages. */
data class ShellBadges(val approvals: Int = 0, val unread: Int = 0)

/** The app shell (drawer badges, the Notifications bell, invitation links the app was opened with). */
@HiltViewModel
class ShellViewModel @Inject constructor(
    approvals: ApprovalsRepository,
    messages: MessagesRepository,
    private val invites: InviteLinkInbox,
    profiles: ProfileRepository,
    private val feed: FeedRepository,
    private val prefs: PreferencesRepository,
    private val taps: NotificationOpenInbox,
    private val visibility: NotifyVisibility,
) : ViewModel() {
    /** A notification's tap waiting for the shell (ANDROID-PLAN 0.1.23). */
    val tap: StateFlow<NotificationOpenInbox.Open?> = taps.pending

    fun tapTaken() = taps.consume()

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

    /** An opened invitation link waiting for the connect flow. */
    val inviteLink: StateFlow<String?> = invites.link

    fun inviteLinkTaken() = invites.consume()

    val badges: StateFlow<ShellBadges> = combine(approvals.approvals, messages.conversations) { a, c ->
        ShellBadges(approvals = a.count { it.needsDecision }, unread = c.count { it.unread > 0 })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ShellBadges())

    private companion object {
        const val ITEM_WAIT_MS = 10_000L
    }
}
