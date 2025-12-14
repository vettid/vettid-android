package com.vettid.app.features.archive

import java.time.Instant
import java.time.YearMonth

/**
 * Represents an archived item in the vault.
 */
data class ArchivedItem(
    val id: String,
    val type: ArchivedItemType,
    val title: String,
    val subtitle: String?,
    val archivedAt: Instant,
    val expiresAt: Instant?,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Types of items that can be archived.
 */
enum class ArchivedItemType(val displayName: String, val iconName: String) {
    MESSAGE("Message", "chat"),
    CONNECTION("Connection", "person"),
    FILE("File", "description"),
    AUTH_REQUEST("Auth Request", "verified_user"),
    EVENT("Event", "event")
}

/**
 * State for the archive list screen.
 */
sealed class ArchiveState {
    object Loading : ArchiveState()
    data class Loaded(
        val items: List<ArchivedItem>,
        val groupedByMonth: Map<YearMonth, List<ArchivedItem>>,
        val searchQuery: String = "",
        val selectedIds: Set<String> = emptySet(),
        val isSelectionMode: Boolean = false
    ) : ArchiveState()
    data class Error(val message: String) : ArchiveState()
    object Empty : ArchiveState()
}

/**
 * Effects emitted by the archive view model.
 */
sealed class ArchiveEffect {
    data class ShowSuccess(val message: String) : ArchiveEffect()
    data class ShowError(val message: String) : ArchiveEffect()
    data class ConfirmDelete(val count: Int) : ArchiveEffect()
}

/**
 * Events for the archive screen.
 */
sealed class ArchiveEvent {
    data class SearchQueryChanged(val query: String) : ArchiveEvent()
    data class ItemClicked(val itemId: String) : ArchiveEvent()
    data class ItemLongPressed(val itemId: String) : ArchiveEvent()
    data class ToggleSelection(val itemId: String) : ArchiveEvent()
    object SelectAll : ArchiveEvent()
    object ClearSelection : ArchiveEvent()
    object EnterSelectionMode : ArchiveEvent()
    object ExitSelectionMode : ArchiveEvent()
    object DeleteSelected : ArchiveEvent()
    object RestoreSelected : ArchiveEvent()
    object Refresh : ArchiveEvent()
}
