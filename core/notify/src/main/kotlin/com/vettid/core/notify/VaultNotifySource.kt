package com.vettid.core.notify

import com.vettid.core.data.account.AccountNames
import com.vettid.core.data.feed.FeedNames
import com.vettid.core.data.feed.VoteTitles
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
class VaultNotifySource(
    private val vault: VaultManager,
    scope: CoroutineScope,
    /** The resources for a photo's text ("Photo", "Photo: <caption>"). */
    private val res: () -> android.content.res.Resources? = { null },
) : NotifySource {
    override val arrivals: Flow<FeedItem> = vault.feed.arrivals
    override val items: StateFlow<List<FeedItem>> = vault.feed.items
    override val open: StateFlow<Boolean> = combine(vault.phase, vault.ownerCheck.ownerCheck) { p, v ->
        p == AppPhase.Unlocked && (v == null || v.state == OwnerCheckState.OK)
    }.stateIn(scope, SharingStarted.Eagerly, false)
    override val lockedElsewhere: StateFlow<Boolean> = vault.lockedElsewhere
    override val idleLocked: StateFlow<com.vettid.core.data.vault.IdleLocked?> = vault.ownerCheck.idleLocked
    override val ownerCheck: StateFlow<OwnerCheckView?> = vault.ownerCheck.ownerCheck
    override val alarm: StateFlow<CredentialAlarm?> = vault.alarm

    override fun names(): FeedNames = FeedNames(
        connections = vault.social.connections.value.associateBy { it.id },
        items = vault.items.items.value.associate { it.itemId to it.name },
        approvals = vault.social.approvals.value.associateBy { it.key },
        // Vote titles from the list in memory (ANDROID-PLAN 0.1.102): the second line of a vote item, never a choice.
        votes = vault.votes.votes.value.mapNotNull { v ->
            AccountNames.isolateOrNull(v.title)?.let { VoteTitles.key(v.connectionId, v.proposalId) to it }
        }.toMap(),
    )

    /** A photo's notification says "Photo" and, with previews on, its caption; never the image or the thumbnail. */
    override suspend fun messageText(connectionId: String, messageId: String): String? {
        val m = vault.social.messages(connectionId).first().firstOrNull { it.messageId == messageId && !it.outgoing }
        return m?.let { notifyMessageText(it.text, it.attachment != null, res()) }
    }
}

/**
 * A received message's text for a notification: the first line that shows something, cleaned and isolated
 * (VAULT-MESSAGING 0.76.0 §10.8 step 6); "Photo" for a photo with nothing to show; null for a text with nothing to show.
 */
internal fun notifyMessageText(text: String, photo: Boolean, r: android.content.res.Resources?): String? {
    val line = AccountNames.firstLineIsolated(text)
    return when {
        !photo || r == null -> line
        line == null -> r.getString(R.string.notify_photo)
        else -> r.getString(R.string.notify_photo_caption, line)
    }
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
