package com.vettid.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.feed.FeedBadge
import com.vettid.core.data.feed.FeedRepository
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
    feed: FeedRepository,
) : ViewModel() {
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
}
