package com.vettid.app.features.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.features.services.models.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the service directory browser.
 *
 * Handles:
 * - Loading services from directory
 * - Search and filtering
 * - Pagination
 *
 * Issue #33 [AND-010] - Service directory browser.
 */
@HiltViewModel
class ServiceDirectoryViewModel @Inject constructor(
    // TODO: Inject directory service API client
) : ViewModel() {

    private val _state = MutableStateFlow<ServiceDirectoryState>(ServiceDirectoryState.Loading)
    val state: StateFlow<ServiceDirectoryState> = _state.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow<ServiceCategory?>(null)
    val selectedCategory: StateFlow<ServiceCategory?> = _selectedCategory.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var currentPage = 0
    private var allServices: List<DirectoryService> = emptyList()

    companion object {
        private const val PAGE_SIZE = 20
    }

    init {
        loadServices()
    }

    /**
     * Load services from the directory.
     */
    private fun loadServices() {
        viewModelScope.launch {
            _state.value = ServiceDirectoryState.Loading

            try {
                // TODO: Implement actual API call to fetch services
                // For now, return empty state until backend is ready
                _state.value = ServiceDirectoryState.Empty
            } catch (e: Exception) {
                _state.value = ServiceDirectoryState.Error(e.message ?: "Failed to load services")
            }
        }
    }

    /**
     * Refresh the service list.
     */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            currentPage = 0
            loadServices()
            _isRefreshing.value = false
        }
    }

    /**
     * Load more services (pagination).
     */
    fun loadMore() {
        val currentState = _state.value
        if (currentState !is ServiceDirectoryState.Loaded || !currentState.hasMore) {
            return
        }

        viewModelScope.launch {
            currentPage++
            // TODO: Implement pagination
        }
    }

    /**
     * Set search query and filter services.
     */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        applyFilters()
    }

    /**
     * Perform search.
     */
    fun search(query: String) {
        setSearchQuery(query)
    }

    /**
     * Set category filter.
     */
    fun setCategory(category: ServiceCategory?) {
        _selectedCategory.value = category
        applyFilters()
    }

    /**
     * Apply current filters to the service list.
     */
    private fun applyFilters() {
        val query = _searchQuery.value.lowercase()
        val category = _selectedCategory.value

        val filtered = allServices.filter { service ->
            val matchesQuery = query.isEmpty() ||
                    service.name.lowercase().contains(query) ||
                    service.description.lowercase().contains(query) ||
                    service.organization.name.lowercase().contains(query)

            val matchesCategory = category == null || service.category == category

            matchesQuery && matchesCategory
        }

        if (filtered.isEmpty() && allServices.isEmpty()) {
            _state.value = ServiceDirectoryState.Empty
        } else if (filtered.isEmpty()) {
            _state.value = ServiceDirectoryState.Loaded(
                services = emptyList(),
                featuredServices = emptyList(),
                hasMore = false
            )
        } else {
            _state.value = ServiceDirectoryState.Loaded(
                services = filtered.filter { !it.featured },
                featuredServices = if (query.isEmpty() && category == null) {
                    filtered.filter { it.featured }
                } else emptyList(),
                hasMore = false // TODO: Implement proper pagination
            )
        }
    }
}

/**
 * State for the service directory screen.
 */
sealed class ServiceDirectoryState {
    object Loading : ServiceDirectoryState()
    object Empty : ServiceDirectoryState()

    data class Loaded(
        val services: List<DirectoryService>,
        val featuredServices: List<DirectoryService> = emptyList(),
        val hasMore: Boolean = false
    ) : ServiceDirectoryState()

    data class Error(val message: String) : ServiceDirectoryState()
}
