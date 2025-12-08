package com.vettid.app.features.handlers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.network.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for handler discovery and browsing.
 *
 * Features:
 * - Load handlers by category
 * - Search handlers
 * - Install/uninstall handlers
 * - Pagination support
 */
@HiltViewModel
class HandlerDiscoveryViewModel @Inject constructor(
    private val registryClient: HandlerRegistryClient,
    private val vaultHandlerClient: VaultHandlerClient
) : ViewModel() {

    private val _state = MutableStateFlow<HandlerDiscoveryState>(HandlerDiscoveryState.Loading)
    val state: StateFlow<HandlerDiscoveryState> = _state.asStateFlow()

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    private val _categories = MutableStateFlow<List<HandlerCategory>>(emptyList())
    val categories: StateFlow<List<HandlerCategory>> = _categories.asStateFlow()

    private val _effects = MutableSharedFlow<HandlerDiscoveryEffect>()
    val effects: SharedFlow<HandlerDiscoveryEffect> = _effects.asSharedFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Action token for authenticated operations
    private var currentActionToken: String? = null

    // Current page for pagination
    private var currentPage = 1

    init {
        loadCategories()
        loadHandlers()
    }

    /**
     * Set action token for authenticated operations.
     */
    fun setActionToken(token: String) {
        currentActionToken = token
    }

    /**
     * Load available categories.
     */
    private fun loadCategories() {
        viewModelScope.launch {
            registryClient.getCategories().fold(
                onSuccess = { response ->
                    _categories.value = response.categories
                },
                onFailure = {
                    // Use default categories on failure
                    _categories.value = listOf(
                        HandlerCategory("messaging", "Messaging", "Communication handlers", "chat", 0),
                        HandlerCategory("social", "Social", "Social network handlers", "people", 0),
                        HandlerCategory("productivity", "Productivity", "Work tools", "work", 0),
                        HandlerCategory("utilities", "Utilities", "Utility handlers", "build", 0)
                    )
                }
            )
        }
    }

    /**
     * Load handlers with optional category filter.
     */
    fun loadHandlers(category: String? = _selectedCategory.value, refresh: Boolean = false) {
        if (refresh) {
            currentPage = 1
        }

        viewModelScope.launch {
            _state.value = if (refresh || _state.value is HandlerDiscoveryState.Error) {
                HandlerDiscoveryState.Loading
            } else {
                _state.value
            }

            registryClient.listHandlers(
                category = category,
                page = currentPage,
                limit = 20
            ).fold(
                onSuccess = { response ->
                    val currentHandlers = if (currentPage == 1) {
                        emptyList()
                    } else {
                        (_state.value as? HandlerDiscoveryState.Loaded)?.handlers ?: emptyList()
                    }

                    _state.value = HandlerDiscoveryState.Loaded(
                        handlers = currentHandlers + response.handlers,
                        hasMore = response.hasMore,
                        total = response.total
                    )
                },
                onFailure = { error ->
                    _state.value = HandlerDiscoveryState.Error(
                        message = error.message ?: "Failed to load handlers"
                    )
                }
            )
        }
    }

    /**
     * Load more handlers (pagination).
     */
    fun loadMore() {
        val currentState = _state.value
        if (currentState is HandlerDiscoveryState.Loaded && currentState.hasMore) {
            currentPage++
            loadHandlers()
        }
    }

    /**
     * Refresh handler list.
     */
    fun refresh() {
        loadHandlers(refresh = true)
    }

    /**
     * Select a category.
     */
    fun selectCategory(category: String?) {
        _selectedCategory.value = category
        currentPage = 1
        loadHandlers(category, refresh = true)
    }

    /**
     * Search handlers by query.
     */
    fun search(query: String) {
        _searchQuery.value = query

        if (query.isBlank()) {
            loadHandlers(refresh = true)
            return
        }

        viewModelScope.launch {
            _state.value = HandlerDiscoveryState.Loading

            registryClient.searchHandlers(query).fold(
                onSuccess = { response ->
                    _state.value = HandlerDiscoveryState.Loaded(
                        handlers = response.handlers,
                        hasMore = response.hasMore,
                        total = response.total,
                        isSearchResult = true
                    )
                },
                onFailure = { error ->
                    _state.value = HandlerDiscoveryState.Error(
                        message = error.message ?: "Search failed"
                    )
                }
            )
        }
    }

    /**
     * Install a handler.
     */
    fun installHandler(handler: HandlerSummary) {
        val token = currentActionToken
        if (token == null) {
            viewModelScope.launch {
                _effects.emit(HandlerDiscoveryEffect.RequireAuth)
            }
            return
        }

        viewModelScope.launch {
            _effects.emit(HandlerDiscoveryEffect.ShowLoading("Installing ${handler.name}..."))

            vaultHandlerClient.installHandler(token, handler.id, handler.version).fold(
                onSuccess = { response ->
                    if (response.status == "installed") {
                        _effects.emit(HandlerDiscoveryEffect.ShowSuccess("${handler.name} installed"))
                        // Update handler in list
                        updateHandlerInstallState(handler.id, installed = true, version = handler.version)
                    } else {
                        _effects.emit(HandlerDiscoveryEffect.ShowError(
                            response.error ?: "Installation failed"
                        ))
                    }
                },
                onFailure = { error ->
                    _effects.emit(HandlerDiscoveryEffect.ShowError(
                        error.message ?: "Installation failed"
                    ))
                }
            )
        }
    }

    /**
     * Uninstall a handler.
     */
    fun uninstallHandler(handler: HandlerSummary) {
        val token = currentActionToken
        if (token == null) {
            viewModelScope.launch {
                _effects.emit(HandlerDiscoveryEffect.RequireAuth)
            }
            return
        }

        viewModelScope.launch {
            _effects.emit(HandlerDiscoveryEffect.ShowLoading("Uninstalling ${handler.name}..."))

            vaultHandlerClient.uninstallHandler(token, handler.id).fold(
                onSuccess = { response ->
                    if (response.status == "uninstalled") {
                        _effects.emit(HandlerDiscoveryEffect.ShowSuccess("${handler.name} uninstalled"))
                        // Update handler in list
                        updateHandlerInstallState(handler.id, installed = false, version = null)
                    } else {
                        _effects.emit(HandlerDiscoveryEffect.ShowError(
                            response.error ?: "Uninstall failed"
                        ))
                    }
                },
                onFailure = { error ->
                    _effects.emit(HandlerDiscoveryEffect.ShowError(
                        error.message ?: "Uninstall failed"
                    ))
                }
            )
        }
    }

    /**
     * Update a handler's install state in the current list.
     */
    private fun updateHandlerInstallState(handlerId: String, installed: Boolean, version: String?) {
        val currentState = _state.value
        if (currentState is HandlerDiscoveryState.Loaded) {
            val updatedHandlers = currentState.handlers.map { handler ->
                if (handler.id == handlerId) {
                    handler.copy(
                        installed = installed,
                        installedVersion = version
                    )
                } else {
                    handler
                }
            }
            _state.value = currentState.copy(handlers = updatedHandlers)
        }
    }

    /**
     * Navigate to handler details.
     */
    fun viewHandlerDetails(handlerId: String) {
        viewModelScope.launch {
            _effects.emit(HandlerDiscoveryEffect.NavigateToDetails(handlerId))
        }
    }
}

// MARK: - State Types

/**
 * Handler discovery state.
 */
sealed class HandlerDiscoveryState {
    object Loading : HandlerDiscoveryState()

    data class Loaded(
        val handlers: List<HandlerSummary>,
        val hasMore: Boolean,
        val total: Int = 0,
        val isSearchResult: Boolean = false
    ) : HandlerDiscoveryState()

    data class Error(val message: String) : HandlerDiscoveryState()
}

// MARK: - Effects

/**
 * One-time effects from the ViewModel.
 */
sealed class HandlerDiscoveryEffect {
    object RequireAuth : HandlerDiscoveryEffect()
    data class ShowLoading(val message: String) : HandlerDiscoveryEffect()
    data class ShowSuccess(val message: String) : HandlerDiscoveryEffect()
    data class ShowError(val message: String) : HandlerDiscoveryEffect()
    data class NavigateToDetails(val handlerId: String) : HandlerDiscoveryEffect()
}
