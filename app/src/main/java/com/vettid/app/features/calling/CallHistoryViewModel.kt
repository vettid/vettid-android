package com.vettid.app.features.calling

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.CallSignalingClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "CallHistoryViewModel"

/**
 * ViewModel for the call history screen.
 *
 * Fetches call history from the vault via CallSignalingClient and
 * exposes it as a list of CallHistoryEntry items for the UI.
 */
@HiltViewModel
class CallHistoryViewModel @Inject constructor(
    private val callSignalingClient: CallSignalingClient
) : ViewModel() {

    private val _calls = MutableStateFlow<List<CallHistoryEntry>>(emptyList())
    val calls: StateFlow<List<CallHistoryEntry>> = _calls.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _effects = MutableSharedFlow<CallHistoryEffect>()
    val effects: SharedFlow<CallHistoryEffect> = _effects.asSharedFlow()

    init {
        loadHistory()
    }

    /**
     * Load call history from the vault.
     */
    fun loadHistory() {
        viewModelScope.launch {
            try {
                _isLoading.value = _calls.value.isEmpty()
                _isRefreshing.value = _calls.value.isNotEmpty()

                val result = callSignalingClient.getCallHistory(limit = 50)

                result.onSuccess { entries ->
                    _calls.value = entries.sortedByDescending { it.call.initiatedAt }
                    Log.i(TAG, "Loaded ${entries.size} call history entries")
                }.onFailure { e ->
                    Log.e(TAG, "Failed to load call history: ${e.message}")
                    _effects.emit(CallHistoryEffect.ShowError("Failed to load call history"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading call history", e)
                _effects.emit(CallHistoryEffect.ShowError("Error loading call history: ${e.message}"))
            } finally {
                _isLoading.value = false
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Refresh call history (pull-to-refresh).
     */
    fun refresh() {
        loadHistory()
    }
}

/**
 * Side effects emitted by CallHistoryViewModel.
 */
sealed interface CallHistoryEffect {
    data class ShowError(val message: String) : CallHistoryEffect
}
