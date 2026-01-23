package com.vettid.app.features.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.features.services.models.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for activity history.
 *
 * Issue #42 [AND-050] - Activity History View.
 */
@HiltViewModel
class ActivityHistoryViewModel @Inject constructor(
    // TODO: Inject activity storage
) : ViewModel() {

    private val _activities = MutableStateFlow<List<ActivityEvent>>(emptyList())
    val activities: StateFlow<List<ActivityEvent>> = _activities.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedType = MutableStateFlow<String?>(null)
    val selectedType: StateFlow<String?> = _selectedType.asStateFlow()

    private val _selectedService = MutableStateFlow<String?>(null)
    val selectedService: StateFlow<String?> = _selectedService.asStateFlow()

    private val _services = MutableStateFlow<List<String>>(emptyList())
    val services: StateFlow<List<String>> = _services.asStateFlow()

    private var allActivities: List<ActivityEvent> = emptyList()

    init {
        loadActivities()
    }

    private fun loadActivities() {
        viewModelScope.launch {
            _isLoading.value = true

            try {
                // TODO: Load activities from storage
                allActivities = emptyList()
                _activities.value = emptyList()

                // Extract unique services
                _services.value = allActivities.map { it.serviceName }.distinct()
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        applyFilters()
    }

    fun setTypeFilter(type: String?) {
        _selectedType.value = type
        applyFilters()
    }

    fun setServiceFilter(service: String?) {
        _selectedService.value = service
        applyFilters()
    }

    private fun applyFilters() {
        val query = _searchQuery.value.lowercase()
        val type = _selectedType.value
        val service = _selectedService.value

        _activities.value = allActivities.filter { event ->
            val matchesQuery = query.isEmpty() ||
                    event.serviceName.lowercase().contains(query) ||
                    when (event) {
                        is ActivityEvent.Authentication -> event.method.lowercase().contains(query)
                        is ActivityEvent.DataRequest -> event.dataType.lowercase().contains(query)
                        is ActivityEvent.Payment -> event.description?.lowercase()?.contains(query) == true
                        is ActivityEvent.Notification -> event.title.lowercase().contains(query)
                        is ActivityEvent.ContractChange -> event.changeType.displayName.lowercase().contains(query)
                    }

            val matchesType = type == null || when (type) {
                "authentication" -> event is ActivityEvent.Authentication
                "data_request" -> event is ActivityEvent.DataRequest
                "payment" -> event is ActivityEvent.Payment
                "notification" -> event is ActivityEvent.Notification
                "contract_change" -> event is ActivityEvent.ContractChange
                else -> true
            }

            val matchesService = service == null || event.serviceName == service

            matchesQuery && matchesType && matchesService
        }
    }
}
