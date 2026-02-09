package com.vettid.app.features.archive

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.FeedClient
import com.vettid.app.core.nats.FeedEvent
import com.vettid.app.features.feed.FeedRepository
import com.vettid.app.features.feed.FeedStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

private const val TAG = "ArchiveViewModel"

@HiltViewModel
class ArchiveViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
    private val feedClient: FeedClient
) : ViewModel() {

    private val _state = MutableStateFlow<ArchiveState>(ArchiveState.Loading)
    val state: StateFlow<ArchiveState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ArchiveEffect>()
    val effects: SharedFlow<ArchiveEffect> = _effects.asSharedFlow()

    // In-memory archive store (would be persisted in production)
    private val archivedItems = mutableListOf<ArchivedItem>()

    init {
        loadArchive()
    }

    fun onEvent(event: ArchiveEvent) {
        when (event) {
            is ArchiveEvent.SearchQueryChanged -> updateSearchQuery(event.query)
            is ArchiveEvent.ItemClicked -> handleItemClick(event.itemId)
            is ArchiveEvent.ItemLongPressed -> handleItemLongPress(event.itemId)
            is ArchiveEvent.ToggleSelection -> toggleSelection(event.itemId)
            is ArchiveEvent.SelectAll -> selectAll()
            is ArchiveEvent.ClearSelection -> clearSelection()
            is ArchiveEvent.EnterSelectionMode -> enterSelectionMode()
            is ArchiveEvent.ExitSelectionMode -> exitSelectionMode()
            is ArchiveEvent.DeleteSelected -> deleteSelected()
            is ArchiveEvent.RestoreSelected -> restoreSelected()
            is ArchiveEvent.Refresh -> loadArchive()
        }
    }

    private fun loadArchive() {
        viewModelScope.launch {
            _state.value = ArchiveState.Loading
            try {
                // First try cached events
                val cached = feedRepository.getCachedEvents()
                    .filter { it.feedStatus == FeedStatus.ARCHIVED }

                if (cached.isNotEmpty()) {
                    archivedItems.clear()
                    archivedItems.addAll(cached.map { it.toArchivedItem() })
                } else {
                    // Try fetching archived events from vault
                    feedClient.listFeed(status = "archived")
                        .onSuccess { response ->
                            archivedItems.clear()
                            archivedItems.addAll(response.events.map { it.toArchivedItem() })
                        }
                        .onFailure {
                            Log.w(TAG, "Failed to fetch archived events from vault", it)
                        }
                }

                if (archivedItems.isEmpty()) {
                    _state.value = ArchiveState.Empty
                } else {
                    val grouped = groupByMonth(archivedItems)
                    _state.value = ArchiveState.Loaded(
                        items = archivedItems.toList(),
                        groupedByMonth = grouped
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load archive", e)
                _state.value = ArchiveState.Error(e.message ?: "Failed to load archive")
            }
        }
    }

    /**
     * Convert a FeedEvent to an ArchivedItem for display.
     */
    private fun FeedEvent.toArchivedItem(): ArchivedItem {
        val itemType = when {
            eventType.startsWith("message.") -> ArchivedItemType.MESSAGE
            eventType.startsWith("connection.") -> ArchivedItemType.CONNECTION
            eventType.startsWith("security.") || eventType.startsWith("credential.") -> ArchivedItemType.AUTH_REQUEST
            else -> ArchivedItemType.EVENT
        }
        val archivedInstant = if (archivedAt != null && archivedAt > 0) {
            com.vettid.app.util.toInstant(archivedAt)
        } else {
            com.vettid.app.util.toInstant(createdAt)
        }
        val expiresInstant = if (expiresAt != null && expiresAt > 0) {
            com.vettid.app.util.toInstant(expiresAt)
        } else {
            null
        }
        return ArchivedItem(
            id = eventId,
            type = itemType,
            title = title,
            subtitle = message,
            archivedAt = archivedInstant,
            expiresAt = expiresInstant,
            metadata = metadata ?: emptyMap()
        )
    }

    private fun updateSearchQuery(query: String) {
        val currentState = _state.value
        if (currentState is ArchiveState.Loaded) {
            val filtered = if (query.isBlank()) {
                archivedItems.toList()
            } else {
                archivedItems.filter {
                    it.title.contains(query, ignoreCase = true) ||
                    it.subtitle?.contains(query, ignoreCase = true) == true
                }
            }
            val grouped = groupByMonth(filtered)
            _state.value = currentState.copy(
                items = filtered,
                groupedByMonth = grouped,
                searchQuery = query
            )
        }
    }

    private fun handleItemClick(itemId: String) {
        val currentState = _state.value
        if (currentState is ArchiveState.Loaded && currentState.isSelectionMode) {
            toggleSelection(itemId)
        } else {
            // View item details (not implemented for archive)
        }
    }

    private fun handleItemLongPress(itemId: String) {
        val currentState = _state.value
        if (currentState is ArchiveState.Loaded && !currentState.isSelectionMode) {
            _state.value = currentState.copy(
                isSelectionMode = true,
                selectedIds = setOf(itemId)
            )
        }
    }

    private fun toggleSelection(itemId: String) {
        val currentState = _state.value
        if (currentState is ArchiveState.Loaded) {
            val newSelection = if (currentState.selectedIds.contains(itemId)) {
                currentState.selectedIds - itemId
            } else {
                currentState.selectedIds + itemId
            }
            _state.value = currentState.copy(
                selectedIds = newSelection,
                isSelectionMode = newSelection.isNotEmpty()
            )
        }
    }

    private fun selectAll() {
        val currentState = _state.value
        if (currentState is ArchiveState.Loaded) {
            _state.value = currentState.copy(
                selectedIds = currentState.items.map { it.id }.toSet()
            )
        }
    }

    private fun clearSelection() {
        val currentState = _state.value
        if (currentState is ArchiveState.Loaded) {
            _state.value = currentState.copy(selectedIds = emptySet())
        }
    }

    private fun enterSelectionMode() {
        val currentState = _state.value
        if (currentState is ArchiveState.Loaded) {
            _state.value = currentState.copy(isSelectionMode = true)
        }
    }

    private fun exitSelectionMode() {
        val currentState = _state.value
        if (currentState is ArchiveState.Loaded) {
            _state.value = currentState.copy(
                isSelectionMode = false,
                selectedIds = emptySet()
            )
        }
    }

    private fun deleteSelected() {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState is ArchiveState.Loaded && currentState.selectedIds.isNotEmpty()) {
                val count = currentState.selectedIds.size
                archivedItems.removeAll { it.id in currentState.selectedIds }
                loadArchive()
                _effects.emit(ArchiveEffect.ShowSuccess("$count item${if (count > 1) "s" else ""} deleted"))
            }
        }
    }

    private fun restoreSelected() {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState is ArchiveState.Loaded && currentState.selectedIds.isNotEmpty()) {
                val count = currentState.selectedIds.size
                var restored = 0
                for (id in currentState.selectedIds) {
                    feedClient.executeAction(id, "restore")
                        .onSuccess { restored++ }
                        .onFailure { Log.w(TAG, "Failed to restore event $id", it) }
                }
                archivedItems.removeAll { it.id in currentState.selectedIds }
                loadArchive()
                _effects.emit(ArchiveEffect.ShowSuccess("$restored item${if (restored > 1) "s" else ""} restored"))
            }
        }
    }

    private fun groupByMonth(items: List<ArchivedItem>): Map<YearMonth, List<ArchivedItem>> {
        return items
            .sortedByDescending { it.archivedAt }
            .groupBy { item ->
                YearMonth.from(item.archivedAt.atZone(ZoneId.systemDefault()))
            }
    }

}
