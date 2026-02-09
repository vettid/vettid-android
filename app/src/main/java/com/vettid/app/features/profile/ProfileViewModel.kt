package com.vettid.app.features.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.app.core.network.Profile
import com.vettid.app.core.network.ProfileApiClient
import com.vettid.app.core.storage.MinorSecretsStore
import com.vettid.app.core.storage.PersonalDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    private val personalDataStore: PersonalDataStore
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

    private val _publicPersonalData = MutableStateFlow<List<PublicMetadataItem>>(emptyList())
    val publicPersonalData: StateFlow<List<PublicMetadataItem>> = _publicPersonalData.asStateFlow()

    init {
        loadProfile()
        loadPublicMetadata()
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

    /**
     * Load metadata visible to agents, services, and connections.
     */
    private fun loadPublicMetadata() {
        viewModelScope.launch {
            // Public secrets (keys in public profile)
            val secrets = minorSecretsStore.getPublicProfileSecrets()
            _publicSecrets.value = secrets.map { secret ->
                PublicMetadataItem(
                    name = secret.name,
                    type = secret.type.name,
                    category = secret.category.displayName
                )
            }

            // Public personal data fields
            val publicFieldIds = personalDataStore.getPublicProfileFields()
            val customFields = personalDataStore.getCustomFields()
            val publicItems = mutableListOf<PublicMetadataItem>()

            // System fields (always potentially visible)
            personalDataStore.getSystemFields()?.let { sys ->
                if (sys.firstName.isNotBlank()) publicItems.add(PublicMetadataItem("First Name", "System", "Identity"))
                if (sys.lastName.isNotBlank()) publicItems.add(PublicMetadataItem("Last Name", "System", "Identity"))
                if (sys.email.isNotBlank()) publicItems.add(PublicMetadataItem("Email", "System", "Contact"))
            }

            // Custom fields marked as public profile
            customFields.filter { it.id in publicFieldIds }.forEach { field ->
                publicItems.add(PublicMetadataItem(
                    name = field.name,
                    type = field.fieldType.displayName,
                    category = field.category.name.lowercase().replaceFirstChar { it.uppercase() }
                ))
            }

            _publicPersonalData.value = publicItems
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
 * A metadata item visible to connections/agents/services.
 * Shows name and type only - never the actual value.
 */
data class PublicMetadataItem(
    val name: String,
    val type: String,
    val category: String
)
