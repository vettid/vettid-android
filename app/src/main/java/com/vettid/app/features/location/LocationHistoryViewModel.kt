package com.vettid.app.features.location

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
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

private const val TAG = "LocationHistoryVM"

data class LocationHistoryState(
    val entries: List<LocationHistoryEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedFilter: TimeFilter = TimeFilter.LAST_7_DAYS
)

enum class TimeFilter(val displayName: String, val days: Int) {
    TODAY("Today", 1),
    LAST_7_DAYS("Last 7 days", 7),
    LAST_30_DAYS("Last 30 days", 30),
    ALL("All", 0)
}

sealed class LocationHistoryEffect {
    data class ShowSuccess(val message: String) : LocationHistoryEffect()
    data class ShowError(val message: String) : LocationHistoryEffect()
}

@HiltViewModel
class LocationHistoryViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient
) : ViewModel() {

    private val _state = MutableStateFlow(LocationHistoryState())
    val state: StateFlow<LocationHistoryState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<LocationHistoryEffect>()
    val effects: SharedFlow<LocationHistoryEffect> = _effects.asSharedFlow()

    init {
        loadHistory()
    }

    fun setFilter(filter: TimeFilter) {
        _state.update { it.copy(selectedFilter = filter) }
        loadHistory()
    }

    fun loadHistory() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            try {
                val filter = _state.value.selectedFilter
                val now = System.currentTimeMillis() / 1000
                val payload = JsonObject().apply {
                    if (filter.days > 0) {
                        addProperty("start_time", now - (filter.days * 86400L))
                    }
                    addProperty("end_time", now)
                    addProperty("limit", 500)
                }

                val response = ownerSpaceClient.sendAndAwaitResponse(
                    "location.list", payload, 15000L
                )

                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success && response.result != null) {
                            val entries = parseEntries(response.result)
                            _state.update {
                                it.copy(entries = entries, isLoading = false, error = null)
                            }
                        } else {
                            _state.update {
                                it.copy(
                                    entries = emptyList(),
                                    isLoading = false,
                                    error = response.error ?: "Failed to load history"
                                )
                            }
                        }
                    }
                    is VaultResponse.Error -> {
                        _state.update {
                            it.copy(
                                entries = emptyList(),
                                isLoading = false,
                                error = response.message
                            )
                        }
                    }
                    else -> {
                        _state.update {
                            it.copy(entries = emptyList(), isLoading = false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load location history", e)
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to load: ${e.message}"
                    )
                }
            }
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            try {
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    "location.delete-all", JsonObject(), 15000L
                )

                when (response) {
                    is VaultResponse.HandlerResult -> {
                        if (response.success) {
                            _state.update { it.copy(entries = emptyList()) }
                            _effects.emit(LocationHistoryEffect.ShowSuccess("All location data deleted"))
                        } else {
                            _effects.emit(LocationHistoryEffect.ShowError(
                                response.error ?: "Failed to delete"
                            ))
                        }
                    }
                    is VaultResponse.Error -> {
                        _effects.emit(LocationHistoryEffect.ShowError(response.message))
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete all location data", e)
                _effects.emit(LocationHistoryEffect.ShowError("Delete failed: ${e.message}"))
            }
        }
    }

    private fun parseEntries(result: com.google.gson.JsonObject): List<LocationHistoryEntry> {
        val entries = mutableListOf<LocationHistoryEntry>()
        val pointsArray = result.getAsJsonArray("points") ?: return entries

        for (element in pointsArray) {
            try {
                val obj = element.asJsonObject
                entries.add(
                    LocationHistoryEntry(
                        id = obj.get("id")?.asString ?: "",
                        latitude = obj.get("latitude")?.asDouble ?: 0.0,
                        longitude = obj.get("longitude")?.asDouble ?: 0.0,
                        accuracy = obj.get("accuracy")?.asFloat,
                        altitude = obj.get("altitude")?.asDouble,
                        speed = obj.get("speed")?.asFloat,
                        timestamp = obj.get("timestamp")?.asLong ?: 0L,
                        source = obj.get("source")?.asString ?: "unknown",
                        isSummary = obj.get("is_summary")?.asBoolean ?: false
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse location entry", e)
            }
        }
        return entries
    }
}
