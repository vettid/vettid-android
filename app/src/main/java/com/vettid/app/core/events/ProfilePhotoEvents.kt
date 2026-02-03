package com.vettid.app.core.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simple event bus for profile photo updates.
 * Allows PersonalDataViewModel to notify AppViewModel when the photo changes.
 */
@Singleton
class ProfilePhotoEvents @Inject constructor() {
    private val _photoUpdated = MutableSharedFlow<String?>(extraBufferCapacity = 1)
    val photoUpdated: SharedFlow<String?> = _photoUpdated.asSharedFlow()

    fun notifyPhotoUpdated(base64Photo: String?) {
        _photoUpdated.tryEmit(base64Photo)
    }
}
