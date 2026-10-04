package com.vettid.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.social.ApprovalsRepository
import com.vettid.core.data.social.MessagesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The drawer's counts: approvals waiting, conversations with unread messages. */
data class ShellBadges(val approvals: Int = 0, val unread: Int = 0)

/** The app shell (drawer badges). */
@HiltViewModel
class ShellViewModel @Inject constructor(approvals: ApprovalsRepository, messages: MessagesRepository) : ViewModel() {
    val badges: StateFlow<ShellBadges> = combine(approvals.approvals, messages.conversations) { a, c ->
        ShellBadges(approvals = a.size, unread = c.count { it.unread > 0 })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ShellBadges())
}
