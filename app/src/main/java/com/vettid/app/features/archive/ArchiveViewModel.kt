package com.vettid.app.features.archive

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

private const val TAG = "ArchiveViewModel"

@HiltViewModel
class ArchiveViewModel @Inject constructor() : ViewModel() {

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
                // Initialize with mock data if empty
                if (archivedItems.isEmpty()) {
                    archivedItems.addAll(generateMockArchive())
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
                // In production, this would move items back to their original location
                archivedItems.removeAll { it.id in currentState.selectedIds }
                loadArchive()
                _effects.emit(ArchiveEffect.ShowSuccess("$count item${if (count > 1) "s" else ""} restored"))
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

    private fun generateMockArchive(): List<ArchivedItem> {
        val now = Instant.now()
        return listOf(
            ArchivedItem(
                id = "archive-1",
                type = ArchivedItemType.MESSAGE,
                title = "Message from Carol",
                subtitle = "Thanks for the update!",
                archivedAt = now.minusSeconds(86400 * 5),
                expiresAt = now.plusSeconds(86400 * 25)
            ),
            ArchivedItem(
                id = "archive-2",
                type = ArchivedItemType.CONNECTION,
                title = "Connection: Frank",
                subtitle = "Disconnected on Dec 3",
                archivedAt = now.minusSeconds(86400 * 11),
                expiresAt = now.plusSeconds(86400 * 19)
            ),
            ArchivedItem(
                id = "archive-3",
                type = ArchivedItemType.MESSAGE,
                title = "Thread with Alice",
                subtitle = "12 messages",
                archivedAt = now.minusSeconds(86400 * 16),
                expiresAt = now.plusSeconds(86400 * 14)
            ),
            ArchivedItem(
                id = "archive-4",
                type = ArchivedItemType.FILE,
                title = "report.pdf",
                subtitle = "2.4 MB",
                archivedAt = now.minusSeconds(86400 * 29),
                expiresAt = now.plusSeconds(86400 * 1)
            ),
            ArchivedItem(
                id = "archive-5",
                type = ArchivedItemType.AUTH_REQUEST,
                title = "Auth: Service X",
                subtitle = "Denied on Nov 10",
                archivedAt = now.minusSeconds(86400 * 34),
                expiresAt = null
            )
        )
    }
}
