package com.vettid.core.notify

import com.vettid.core.data.feed.FeedNames
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.CredentialAlarm
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.VaultManager
import com.vettid.core.vault.FeedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [NotifySource] over the app's [VaultManager]: the feed, the phase and the owner check, names from the caches. */
class VaultNotifySource(private val vault: VaultManager, scope: CoroutineScope) : NotifySource {
    override val arrivals: Flow<FeedItem> = vault.feed.arrivals
    override val items: StateFlow<List<FeedItem>> = vault.feed.items
    override val open: StateFlow<Boolean> = combine(vault.phase, vault.ownerCheck.ownerCheck) { p, v ->
        p == AppPhase.Unlocked && (v == null || v.state == OwnerCheckState.OK)
    }.stateIn(scope, SharingStarted.Eagerly, false)
    override val lockedElsewhere: StateFlow<Boolean> = vault.lockedElsewhere
    override val ownerCheck: StateFlow<OwnerCheckView?> = vault.ownerCheck.ownerCheck
    override val alarm: StateFlow<CredentialAlarm?> = vault.alarm

    override fun names(): FeedNames = FeedNames(
        connections = vault.social.connections.value.associateBy { it.id },
        items = vault.items.items.value.associate { it.itemId to it.name },
        approvals = vault.social.approvals.value.associateBy { it.key },
    )

    override suspend fun messageText(connectionId: String, messageId: String): String? =
        vault.social.messages(connectionId).first().firstOrNull { it.messageId == messageId && !it.outgoing }?.text
}

/** A notification's tap, handed from the launcher activity to the shell: an item to open, or Settings → Notifications. */
class NotificationOpenInbox {
    sealed interface Open {
        data class Item(val itemId: String) : Open

        data object Settings : Open
    }

    private val flow = MutableStateFlow<Open?>(null)
    val pending: StateFlow<Open?> = flow.asStateFlow()

    fun offer(o: Open) {
        flow.value = o
    }

    fun consume() {
        flow.value = null
    }
}
