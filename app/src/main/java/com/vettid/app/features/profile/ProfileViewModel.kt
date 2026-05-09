package com.vettid.app.features.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import com.vettid.app.core.network.Profile
import com.vettid.app.core.network.ProfileApiClient
import com.vettid.app.core.storage.MinorSecretsStore
import com.vettid.app.features.personaldata.PublicMetadataItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ProfileViewModel"

/**
 * ViewModel for profile viewing and editing.
 *
 * Features:
 * - View own profile
 * - Edit profile fields
 * - Publish profile to connections
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileApiClient: ProfileApiClient,
    private val minorSecretsStore: MinorSecretsStore,
    private val ownerSpaceClient: OwnerSpaceClient
) : ViewModel() {

    private val _state = MutableStateFlow<ProfileState>(ProfileState.Loading)
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ProfileEffect>()
    val effects: SharedFlow<ProfileEffect> = _effects.asSharedFlow()

    // Edit mode state
    private val _isEditing = MutableStateFlow(false)
    val isEditing: StateFlow<Boolean> = _isEditing.asStateFlow()

    // Edit form fields
    private val _editDisplayName = MutableStateFlow("")
    val editDisplayName: StateFlow<String> = _editDisplayName.asStateFlow()

    private val _editBio = MutableStateFlow("")
    val editBio: StateFlow<String> = _editBio.asStateFlow()

    private val _editLocation = MutableStateFlow("")
    val editLocation: StateFlow<String> = _editLocation.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _publicSecrets = MutableStateFlow<List<PublicMetadataItem>>(emptyList())
    val publicSecrets: StateFlow<List<PublicMetadataItem>> = _publicSecrets.asStateFlow()

    private val _publishedProfileItems = MutableStateFlow<List<PublishedProfileItem>>(emptyList())
    val publishedProfileItems: StateFlow<List<PublishedProfileItem>> = _publishedProfileItems.asStateFlow()

    init {
        loadProfile()
        loadPublicSecrets()
        loadPublishedProfile()
    }

    /**
     * Load own profile.
     */
    fun loadProfile() {
        viewModelScope.launch {
            _state.value = ProfileState.Loading

            profileApiClient.getProfile().fold(
                onSuccess = { profile ->
                    _state.value = ProfileState.Loaded(profile)
                    // Initialize edit fields
                    _editDisplayName.value = profile.displayName
                    _editBio.value = profile.bio ?: ""
                    _editLocation.value = profile.location ?: ""
                },
                onFailure = { error ->
                    _state.value = ProfileState.Error(
                        message = error.message ?: "Failed to load profile"
                    )
                }
            )
        }
    }

    /**
     * Enter edit mode.
     */
    fun startEditing() {
        val currentState = _state.value
        if (currentState is ProfileState.Loaded) {
            _editDisplayName.value = currentState.profile.displayName
            _editBio.value = currentState.profile.bio ?: ""
            _editLocation.value = currentState.profile.location ?: ""
            _isEditing.value = true
        }
    }

    /**
     * Cancel editing.
     */
    fun cancelEditing() {
        val currentState = _state.value
        if (currentState is ProfileState.Loaded) {
            // Reset to original values
            _editDisplayName.value = currentState.profile.displayName
            _editBio.value = currentState.profile.bio ?: ""
            _editLocation.value = currentState.profile.location ?: ""
        }
        _isEditing.value = false
    }

    /**
     * Update display name.
     */
    fun onDisplayNameChanged(name: String) {
        _editDisplayName.value = name
    }

    /**
     * Update bio.
     */
    fun onBioChanged(bio: String) {
        _editBio.value = bio
    }

    /**
     * Update location.
     */
    fun onLocationChanged(location: String) {
        _editLocation.value = location
    }

    /**
     * Save profile changes.
     */
    fun saveProfile() {
        val displayName = _editDisplayName.value.trim()
        if (displayName.isBlank()) {
            viewModelScope.launch {
                _effects.emit(ProfileEffect.ShowError("Display name is required"))
            }
            return
        }

        viewModelScope.launch {
            _isSaving.value = true

            profileApiClient.updateProfile(
                displayName = displayName,
                bio = _editBio.value.trim().takeIf { it.isNotBlank() },
                location = _editLocation.value.trim().takeIf { it.isNotBlank() }
            ).fold(
                onSuccess = { profile ->
                    _state.value = ProfileState.Loaded(profile)
                    _isEditing.value = false
                    _effects.emit(ProfileEffect.ShowSuccess("Profile updated"))
                },
                onFailure = { error ->
                    _effects.emit(ProfileEffect.ShowError(
                        error.message ?: "Failed to save profile"
                    ))
                }
            )

            _isSaving.value = false
        }
    }

    /**
     * Publish profile to all connections.
     */
    fun publishProfile() {
        viewModelScope.launch {
            val currentState = _state.value
            if (currentState is ProfileState.Loaded) {
                _state.value = currentState.copy(isPublishing = true)
            }

            profileApiClient.publishProfile().fold(
                onSuccess = {
                    if (currentState is ProfileState.Loaded) {
                        _state.value = currentState.copy(isPublishing = false)
                    }
                    _effects.emit(ProfileEffect.ShowSuccess("Profile published to connections"))
                },
                onFailure = { error ->
                    if (currentState is ProfileState.Loaded) {
                        _state.value = currentState.copy(isPublishing = false)
                    }
                    _effects.emit(ProfileEffect.ShowError(
                        error.message ?: "Failed to publish profile"
                    ))
                }
            )
        }
    }

    /**
     * Check if form is valid.
     */
    fun isFormValid(): Boolean {
        return _editDisplayName.value.trim().isNotBlank()
    }

    fun loadPublicSecrets() {
        viewModelScope.launch {
            val secrets = minorSecretsStore.getPublicProfileSecrets()
            _publicSecrets.value = secrets.map { secret ->
                PublicMetadataItem(
                    name = secret.name,
                    type = secret.type.name,
                    category = secret.category.displayName
                )
            }
        }
    }

    /**
     * Load the full published profile from the vault via profile.get-published.
     * Returns all public items (fields, wallets, etc.) in one call.
     */
    fun loadPublishedProfile() {
        viewModelScope.launch {
            try {
                val response = ownerSpaceClient.sendAndAwaitResponse(
                    "profile.get-published", JsonObject(), 15000L
                )
                if (response is VaultResponse.HandlerResult && response.success && response.result != null) {
                    val result = response.result!!
                    val items = mutableListOf<PublishedProfileItem>()

                    // Parse published fields. M3 / 2026-05-09: vault
                    // emits field_order so the user's drag-to-reorder
                    // propagates here. Iterate field_order if present;
                    // otherwise fall back to JSON-map insertion order.
                    val fieldsObj = result.getAsJsonObject("fields")
                    val fieldOrder = result.getAsJsonArray("field_order")
                        ?.mapNotNull { it?.asString }
                        ?.takeIf { it.isNotEmpty() }
                    val orderedKeys = if (fieldOrder != null && fieldsObj != null) {
                        fieldOrder.filter { fieldsObj.has(it) }
                    } else {
                        fieldsObj?.entrySet()?.map { it.key } ?: emptyList()
                    }
                    orderedKeys.forEach { key ->
                        val fieldObj = fieldsObj?.get(key)?.asJsonObject ?: return@forEach
                        val displayName = fieldObj.get("display_name")?.asString ?: key
                        val fieldValue = fieldObj.get("value")?.asString ?: ""
                        if (fieldValue.isNotBlank()) {
                            items.add(PublishedProfileItem(
                                category = "Personal Data",
                                label = displayName,
                                value = fieldValue
                            ))
                        }
                    }

                    // Parse published wallets
                    result.getAsJsonArray("wallets")?.forEach { element ->
                        val walletObj = element?.asJsonObject ?: return@forEach
                        val label = walletObj.get("label")?.asString ?: "Wallet"
                        val address = walletObj.get("address")?.asString ?: ""
                        val network = walletObj.get("network")?.asString ?: "mainnet"
                        if (address.isNotBlank()) {
                            items.add(PublishedProfileItem(
                                category = "Bitcoin Wallet",
                                label = "$label ($network)",
                                value = address
                            ))
                        }
                    }

                    _publishedProfileItems.value = items
                } else {
                    _publishedProfileItems.value = emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load published profile", e)
                _publishedProfileItems.value = emptyList()
            }
        }
    }

    /**
     * Check if form has changes.
     */
    fun hasChanges(): Boolean {
        val currentState = _state.value
        if (currentState !is ProfileState.Loaded) return false

        val profile = currentState.profile
        return _editDisplayName.value.trim() != profile.displayName ||
               _editBio.value.trim() != (profile.bio ?: "") ||
               _editLocation.value.trim() != (profile.location ?: "")
    }
}

// MARK: - State Types

/**
 * Profile state.
 */
sealed class ProfileState {
    object Loading : ProfileState()

    data class Loaded(
        val profile: Profile,
        val isPublishing: Boolean = false
    ) : ProfileState()

    data class Error(val message: String) : ProfileState()
}

// MARK: - Effects

/**
 * One-time effects from the ViewModel.
 */
sealed class ProfileEffect {
    data class ShowSuccess(val message: String) : ProfileEffect()
    data class ShowError(val message: String) : ProfileEffect()
}

/**
 * A single item from the published profile (field, wallet, etc.).
 */
data class PublishedProfileItem(
    val category: String,
    val label: String,
    val value: String
)

