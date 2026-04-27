package com.vettid.app.features.settings.handlers

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "HandlerAuthVM"

data class HandlerAuthorizationState(
    val isLoading: Boolean = true,
    val handlers: List<VaultHandler> = emptyList(),
    val error: String? = null,
    /** ID of the handler whose toggle is currently in flight, for spinner UI. */
    val pendingHandlerId: String? = null,
)

sealed class HandlerAuthorizationEffect {
    data class ShowMessage(val message: String) : HandlerAuthorizationEffect()
}

@HiltViewModel
class HandlerAuthorizationViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
) : ViewModel() {

    private val _state = MutableStateFlow(HandlerAuthorizationState())
    val state: StateFlow<HandlerAuthorizationState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<HandlerAuthorizationEffect>()
    val effects: SharedFlow<HandlerAuthorizationEffect> = _effects.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val handlers = ownerSpaceClient.listHandlers()
                _state.update {
                    it.copy(
                        isLoading = false,
                        handlers = handlers.sortedWith(handlerSort()),
                        error = if (handlers.isEmpty()) "No handlers reported by vault" else null,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load handlers", e)
                _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to load handlers") }
            }
        }
    }

    fun setEnabled(handlerId: String, enabled: Boolean) {
        viewModelScope.launch {
            val before = _state.value.handlers.firstOrNull { it.id == handlerId } ?: return@launch
            // Optimistic update.
            _state.update { s ->
                s.copy(
                    pendingHandlerId = handlerId,
                    handlers = s.handlers.map {
                        if (it.id == handlerId) it.copy(
                            enabled = enabled,
                            // Disabling clears share — mirror vault behaviour.
                            shareGlobally = if (enabled) it.shareGlobally else false,
                        ) else it
                    },
                )
            }
            ownerSpaceClient.setHandlerEnabled(handlerId, enabled).fold(
                onSuccess = { _state.update { it.copy(pendingHandlerId = null) } },
                onFailure = { err ->
                    _state.update { s ->
                        s.copy(
                            pendingHandlerId = null,
                            handlers = s.handlers.map { if (it.id == handlerId) before else it },
                        )
                    }
                    _effects.emit(HandlerAuthorizationEffect.ShowMessage(prettyError(err)))
                },
            )
        }
    }

    fun setShareGlobally(handlerId: String, share: Boolean) {
        viewModelScope.launch {
            val before = _state.value.handlers.firstOrNull { it.id == handlerId } ?: return@launch
            _state.update { s ->
                s.copy(
                    pendingHandlerId = handlerId,
                    handlers = s.handlers.map {
                        if (it.id == handlerId) it.copy(shareGlobally = share) else it
                    },
                )
            }
            ownerSpaceClient.setHandlerShareGlobally(handlerId, share).fold(
                onSuccess = { _state.update { it.copy(pendingHandlerId = null) } },
                onFailure = { err ->
                    _state.update { s ->
                        s.copy(
                            pendingHandlerId = null,
                            handlers = s.handlers.map { if (it.id == handlerId) before else it },
                        )
                    }
                    _effects.emit(HandlerAuthorizationEffect.ShowMessage(prettyError(err)))
                },
            )
        }
    }

    private fun prettyError(t: Throwable): String {
        val msg = t.message.orEmpty()
        return when {
            msg.contains("handler_required") -> "This capability is required and cannot be disabled."
            msg.contains("handler_not_shareable") -> "This capability is not shareable."
            msg.contains("handler_disabled") -> "Enable the capability before sharing."
            msg.contains("handler_unknown") -> "Unknown capability."
            else -> msg.ifEmpty { "Update failed" }
        }
    }

    /**
     * Sort by category (system → default → optional) then by name. Required
     * handlers float to the top of their category for a clean visual.
     */
    private fun handlerSort(): Comparator<VaultHandler> = compareBy<VaultHandler>(
        { categoryOrder(it.category) },
        { if (it.required) 0 else 1 },
        { it.name },
    )

    private fun categoryOrder(category: String): Int = when (category) {
        "system" -> 0
        "default" -> 1
        "optional" -> 2
        else -> 3
    }
}
