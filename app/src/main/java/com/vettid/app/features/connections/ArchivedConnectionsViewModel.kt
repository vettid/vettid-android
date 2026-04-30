package com.vettid.app.features.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.ConnectionRecord
import com.vettid.app.features.feed.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs ArchivedConnectionsScreen — lists every connection in a
 * terminal state (rejected / revoked / expired / declined_by_*) so
 * the user has a record of past invitations and connections that no
 * longer surface in the live feed.
 *
 * The vault retains the records; we just filter and present them.
 */
@HiltViewModel
class ArchivedConnectionsViewModel @Inject constructor(
    private val feedRepository: FeedRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ArchivedConnectionsState>(ArchivedConnectionsState.Loading)
    val state: StateFlow<ArchivedConnectionsState> = _state.asStateFlow()

    init { load() }

    fun refresh() = load()

    private fun load() {
        viewModelScope.launch {
            _state.value = ArchivedConnectionsState.Loading
            feedRepository.getConnections()
                .onSuccess { all ->
                    val archived = all
                        .filter { it.status in TERMINAL_STATUSES }
                        .sortedByDescending { it.createdAt }
                    _state.value = if (archived.isEmpty()) {
                        ArchivedConnectionsState.Empty
                    } else {
                        ArchivedConnectionsState.Loaded(archived)
                    }
                }
                .onFailure { err ->
                    _state.value = ArchivedConnectionsState.Error(err.message ?: "Failed to load history")
                }
        }
    }

    companion object {
        val TERMINAL_STATUSES = setOf(
            "rejected",
            "revoked",
            "expired",
            "declined_by_us",
            "declined_by_peer",
        )
    }
}

sealed class ArchivedConnectionsState {
    object Loading : ArchivedConnectionsState()
    object Empty : ArchivedConnectionsState()
    data class Loaded(val items: List<ConnectionRecord>) : ArchivedConnectionsState()
    data class Error(val message: String) : ArchivedConnectionsState()
}
