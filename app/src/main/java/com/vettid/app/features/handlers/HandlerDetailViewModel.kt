package com.vettid.app.features.handlers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.vettid.app.core.network.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for handler detail screen.
 *
 * Features:
 * - Load handler details
 * - Install/uninstall handler
 * - View permissions
 * - Navigate to execution
 */
@HiltViewModel
class HandlerDetailViewModel @Inject constructor(
    private val registryClient: HandlerRegistryClient,
    private val vaultHandlerClient: VaultHandlerClient,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val handlerId: String = savedStateHandle.get<String>("handlerId") ?: ""

    private val _state = MutableStateFlow<HandlerDetailState>(HandlerDetailState.Loading)
    val state: StateFlow<HandlerDetailState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<HandlerDetailEffect>()
    val effects: SharedFlow<HandlerDetailEffect> = _effects.asSharedFlow()

    private val _isInstalling = MutableStateFlow(false)
    val isInstalling: StateFlow<Boolean> = _isInstalling.asStateFlow()

    // Action token for authenticated operations
    private var currentActionToken: String? = null

    init {
        if (handlerId.isNotBlank()) {
            loadHandler(handlerId)
        }
    }

    /**
     * Set action token for authenticated operations.
     */
    fun setActionToken(token: String) {
        currentActionToken = token
    }

    /**
     * Load handler details.
     */
    fun loadHandler(id: String = handlerId) {
        viewModelScope.launch {
            _state.value = HandlerDetailState.Loading

            registryClient.getHandler(id).fold(
                onSuccess = { handler ->
                    _state.value = HandlerDetailState.Loaded(handler = handler)
                },
                onFailure = { error ->
                    _state.value = HandlerDetailState.Error(
                        message = error.message ?: "Failed to load handler"
                    )
                }
            )
        }
    }

    /**
     * Refresh handler details.
     */
    fun refresh() {
        loadHandler()
    }

    /**
     * Install the handler.
     */
    fun installHandler() {
        val currentState = _state.value
        if (currentState !is HandlerDetailState.Loaded) return

        val token = currentActionToken
        if (token == null) {
            viewModelScope.launch {
                _effects.emit(HandlerDetailEffect.RequireAuth)
            }
            return
        }

        val handler = currentState.handler

        viewModelScope.launch {
            _isInstalling.value = true

            vaultHandlerClient.installHandler(token, handler.id, handler.version).fold(
                onSuccess = { response ->
                    _isInstalling.value = false
                    if (response.status == "installed") {
                        // Update handler state
                        _state.value = currentState.copy(
                            handler = handler.copy(
                                installed = true,
                                installedVersion = handler.version
                            )
                        )
                        _effects.emit(HandlerDetailEffect.ShowSuccess("${handler.name} installed"))
                    } else {
                        _effects.emit(HandlerDetailEffect.ShowError(
                            response.error ?: "Installation failed"
                        ))
                    }
                },
                onFailure = { error ->
                    _isInstalling.value = false
                    _effects.emit(HandlerDetailEffect.ShowError(
                        error.message ?: "Installation failed"
                    ))
                }
            )
        }
    }

    /**
     * Uninstall the handler.
     */
    fun uninstallHandler() {
        val currentState = _state.value
        if (currentState !is HandlerDetailState.Loaded) return

        val token = currentActionToken
        if (token == null) {
            viewModelScope.launch {
                _effects.emit(HandlerDetailEffect.RequireAuth)
            }
            return
        }

        val handler = currentState.handler

        viewModelScope.launch {
            _isInstalling.value = true

            vaultHandlerClient.uninstallHandler(token, handler.id).fold(
                onSuccess = { response ->
                    _isInstalling.value = false
                    if (response.status == "uninstalled") {
                        // Update handler state
                        _state.value = currentState.copy(
                            handler = handler.copy(
                                installed = false,
                                installedVersion = null
                            )
                        )
                        _effects.emit(HandlerDetailEffect.ShowSuccess("${handler.name} uninstalled"))
                    } else {
                        _effects.emit(HandlerDetailEffect.ShowError(
                            response.error ?: "Uninstall failed"
                        ))
                    }
                },
                onFailure = { error ->
                    _isInstalling.value = false
                    _effects.emit(HandlerDetailEffect.ShowError(
                        error.message ?: "Uninstall failed"
                    ))
                }
            )
        }
    }

    /**
     * Execute the handler with default/empty input.
     */
    fun executeHandler(input: JsonObject = JsonObject()) {
        val currentState = _state.value
        if (currentState !is HandlerDetailState.Loaded) return

        if (!currentState.handler.installed) {
            viewModelScope.launch {
                _effects.emit(HandlerDetailEffect.ShowError("Handler not installed"))
            }
            return
        }

        viewModelScope.launch {
            _effects.emit(HandlerDetailEffect.NavigateToExecution(
                handlerId = currentState.handler.id,
                inputSchema = currentState.handler.inputSchema
            ))
        }
    }

    /**
     * Navigate back.
     */
    fun navigateBack() {
        viewModelScope.launch {
            _effects.emit(HandlerDetailEffect.NavigateBack)
        }
    }
}

// MARK: - State Types

/**
 * Handler detail state.
 */
sealed class HandlerDetailState {
    object Loading : HandlerDetailState()

    data class Loaded(
        val handler: HandlerDetailResponse
    ) : HandlerDetailState()

    data class Error(val message: String) : HandlerDetailState()
}

// MARK: - Effects

/**
 * One-time effects from the ViewModel.
 */
sealed class HandlerDetailEffect {
    object RequireAuth : HandlerDetailEffect()
    object NavigateBack : HandlerDetailEffect()
    data class ShowSuccess(val message: String) : HandlerDetailEffect()
    data class ShowError(val message: String) : HandlerDetailEffect()
    data class NavigateToExecution(
        val handlerId: String,
        val inputSchema: JsonObject
    ) : HandlerDetailEffect()
}
