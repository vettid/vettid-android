package com.vettid.feature.notifications

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.feed.FeedKinds
import com.vettid.core.data.feed.FeedManager
import com.vettid.core.data.feed.FeedNames
import com.vettid.core.data.feed.FeedRepository
import com.vettid.core.data.feed.FeedTarget
import com.vettid.core.data.items.ItemsRepository
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.social.ApprovalsRepository
import com.vettid.core.data.social.ConnectionsRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.FeedItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** A row of the list's day groups: the day, or null for Needs attention. */
data class FeedSection(val day: LocalDate?, val items: List<FeedItem>)

/**
 * The list's grouping (ANDROID-PLAN 0.1.23, 3), kept pure for tests: newest first by `at` (a batch keeps its first
 * ask's `at`); unread `urgent` items in Needs attention at the top; the others in day groups in local time. The
 * Archived view lists the archived items only, the main list everything else; [unreadOnly] keeps the `active` ones.
 */
object FeedSections {
    fun of(items: List<FeedItem>, archived: Boolean, unreadOnly: Boolean, zone: ZoneId): List<FeedSection> {
        val shown = items.filter { i ->
            if (archived) i.status == FeedManager.STATUS_ARCHIVED else i.status != FeedManager.STATUS_ARCHIVED &&
                (!unreadOnly || i.status == FeedManager.STATUS_ACTIVE)
        }.sortedWith(compareByDescending<FeedItem> { FeedManager.at(it) ?: Instant.EPOCH }.thenByDescending { it.itemId })
        val urgent = if (archived) emptyList() else shown.filter { it.status == FeedManager.STATUS_ACTIVE && FeedKinds.urgent(it) }
        val rest = shown - urgent.toSet()
        val days = rest.groupBy { FeedManager.at(it)?.atZone(zone)?.toLocalDate() ?: LocalDate.MIN }
            .map { (d, l) -> FeedSection(d, l) }
        return listOfNotNull(urgent.takeIf { it.isNotEmpty() }?.let { FeedSection(null, it) }) + days
    }

    /** An older day group's title: "Mon 5 Oct", with the year when it is not [today]'s. */
    fun dayTitle(day: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern(if (day.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", locale).format(day)
}

/** An archive the member can undo: back to [previous] (`active` or `read`). */
data class ArchiveUndo(val itemId: String, val previous: String)

/** Immutable UI state of the Notifications screen and its Archived view. */
data class NotificationsUiState(
    val items: List<FeedItem> = emptyList(),
    val names: FeedNames = FeedNames(),
    val load: ListLoad = ListLoad.NOT_LOADED,
    val archived: Boolean = false,
    val unreadOnly: Boolean = false,
    /** The detail sheet's item, with [sheetGone] when its target is gone (or it has none). */
    val sheet: FeedItem? = null,
    val sheetGone: Boolean = false,
    val confirmDelete: FeedItem? = null,
    val undo: ArchiveUndo? = null,
    val markingAll: Boolean = false,
    val error: FailureKind? = null,
    /** A screen to open (consumed by the screen through [NotificationsViewModel.targetTaken]). */
    val open: FeedTarget? = null,
) {
    fun sections(zone: ZoneId = ZoneId.systemDefault()): List<FeedSection> = FeedSections.of(items, archived, unreadOnly, zone)

    val unread: Int get() = items.count { it.status == FeedManager.STATUS_ACTIVE }
}

/**
 * Notifications (ANDROID-PLAN 0.1.23): the vault's feed from [FeedRepository], with names from the app's caches.
 * Tapping an item marks it read and opens its target, or the detail sheet when the target is gone; swiping archives
 * (with Undo) or marks read and unread; the Archived view moves items back or deletes them (confirmed).
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val feed: FeedRepository,
    private val connections: ConnectionsRepository,
    private val approvals: ApprovalsRepository,
    private val items: ItemsRepository,
) : ViewModel() {
    private val state = MutableStateFlow(NotificationsUiState(archived = saved.get<Boolean>(ARG_ARCHIVED) ?: false))
    val uiState: StateFlow<NotificationsUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(feed.items, feed.load) { i, l -> i to l }.collect { (i, l) -> state.update { it.copy(items = i, load = l) } }
        }
        viewModelScope.launch {
            combine(connections.connections, approvals.approvals, items.items) { c, a, i ->
                FeedNames(c.associateBy { x -> x.id }, i.associate { x -> x.itemId to x.name }, a.associateBy { x -> x.key })
            }.collect { n -> state.update { it.copy(names = n) } }
        }
        if (feed.load.value == ListLoad.NOT_LOADED) refresh()
        if (items.load.value == ListLoad.NOT_LOADED) viewModelScope.launch { runCatching { items.refresh() } }
    }

    fun refresh() {
        viewModelScope.launch { guard { feed.refresh() } }
    }

    fun setUnreadOnly(on: Boolean) = state.update { it.copy(unreadOnly = on) }

    /** A tap: marks the item read (not waiting for the answer) and opens its target, or the sheet. */
    fun open(item: FeedItem) {
        feed.markReadQuietly(item.itemId)
        val target = FeedKinds.target(item)
        if (target == FeedTarget.Sheet || !exists(target)) {
            state.update { it.copy(sheet = item, sheetGone = target != FeedTarget.Sheet || FeedKinds.isAsk(item.kind)) }
        } else {
            state.update { it.copy(open = target) }
        }
    }

    /** Whether the target is still there (a removed connection, a deleted item, an ask decided or expired: the sheet). */
    private fun exists(t: FeedTarget): Boolean {
        val n = state.value.names
        fun connection(id: String) = n.connections.containsKey(id)
        return when (t) {
            is FeedTarget.ApprovalEntry -> n.approvals.containsKey(t.key)
            is FeedTarget.Connection -> connection(t.connectionId)
            is FeedTarget.Conversation -> connection(t.connectionId)
            is FeedTarget.SharedWithYou -> connection(t.connectionId)
            is FeedTarget.ShareRule -> connection(t.connectionId)
            is FeedTarget.Item -> n.items.containsKey(t.itemId)
            else -> true
        }
    }

    fun targetTaken() = state.update { it.copy(open = null) }

    fun closeSheet() = state.update { it.copy(sheet = null, sheetGone = false) }

    /** End-to-start swipe: archived, with an Undo that restores the previous status. */
    fun archive(item: FeedItem) {
        val previous = item.status.takeIf { it == FeedManager.STATUS_ACTIVE || it == FeedManager.STATUS_READ } ?: FeedManager.STATUS_READ
        state.update { it.copy(undo = ArchiveUndo(item.itemId, previous)) }
        setStatus(item.itemId, FeedManager.STATUS_ARCHIVED)
    }

    fun undoArchive() {
        val u = state.value.undo ?: return
        state.update { it.copy(undo = null) }
        setStatus(u.itemId, u.previous)
    }

    fun undoShown() = state.update { it.copy(undo = null) }

    /** Start-to-end swipe: read ↔ unread. */
    fun toggleRead(item: FeedItem) =
        setStatus(item.itemId, if (item.status == FeedManager.STATUS_ACTIVE) FeedManager.STATUS_READ else FeedManager.STATUS_ACTIVE)

    /** Archived view: back to the main list, read. */
    fun moveToNotifications(item: FeedItem) = setStatus(item.itemId, FeedManager.STATUS_READ)

    fun askDelete(item: FeedItem?) = state.update { it.copy(confirmDelete = item) }

    fun delete() {
        val item = state.value.confirmDelete ?: return
        state.update { it.copy(confirmDelete = null) }
        viewModelScope.launch { guard { feed.delete(item.itemId) } }
    }

    fun markAllRead() {
        if (state.value.markingAll) return
        state.update { it.copy(markingAll = true) }
        viewModelScope.launch {
            guard { feed.markAllRead() }
            state.update { it.copy(markingAll = false) }
        }
    }

    fun errorShown() = state.update { it.copy(error = null) }

    private fun setStatus(itemId: String, status: String) {
        viewModelScope.launch { guard { feed.setStatus(itemId, status) } }
    }

    private suspend fun guard(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: VaultFailure) {
            state.update { it.copy(error = e.kind) }
        }
    }

    companion object {
        const val ARG_ARCHIVED = "archived"
    }
}
