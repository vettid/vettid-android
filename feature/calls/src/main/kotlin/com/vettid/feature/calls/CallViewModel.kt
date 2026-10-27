package com.vettid.feature.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.calls.AudioRoute
import com.vettid.core.calls.CallPhase
import com.vettid.core.calls.CallState
import com.vettid.core.calls.CallsController
import com.vettid.core.calls.MicrophoneRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Immutable UI state of the call screen. */
data class CallUiState(
    val call: CallState = CallState.Idle,
    /** The connection's name as the app shows it; null when it has none to show. */
    val name: String? = null,
    val speaker: Boolean = false,
    /** Whether this phone can switch to its speaker right now. */
    val canSpeaker: Boolean = false,
    /** The microphone permission is needed for this: the prompt is shown. */
    val microphone: MicrophoneRequest? = null,
)

/**
 * The call screen (CALLS-PLAN 0.1.1 §6.4): one screen for a call from its first moment to its last line, over the
 * process's [CallsController]. It holds nothing of the call but what it shows.
 */
@HiltViewModel
class CallViewModel @Inject constructor(private val calls: CallsController) : ViewModel() {
    private val prompt = MutableStateFlow<MicrophoneRequest?>(null)

    val uiState: StateFlow<CallUiState> = combine(calls.state, calls.audioState, prompt) { call, audio, mic ->
        val connectionId = (call as? CallState.Live)?.connectionId ?: (call as? CallState.Ended)?.connectionId
        CallUiState(
            call = call,
            // The name as it is known for the screen: never hidden here by the notification setting.
            name = connectionId?.let(calls::screenName),
            speaker = audio.route == AudioRoute.SPEAKER,
            canSpeaker = AudioRoute.SPEAKER in audio.available,
            microphone = mic,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CallUiState())

    init {
        viewModelScope.launch { calls.microphoneNeeded.collect { r -> prompt.update { r } } }
        // The prompt for answering a call goes when that call does (the caller gave up, or it ended another way).
        viewModelScope.launch { calls.state.collect { call -> prompt.update { p -> p?.takeIf { stillWanted(it, call) } } } }
    }

    fun answer() = calls.answer()

    companion object {
        /** Whether a microphone prompt still has something to do: a call to place always, an answer only while it rings. */
        fun stillWanted(request: MicrophoneRequest, call: CallState): Boolean =
            request.call != null || (call as? CallState.Live)?.phase == CallPhase.INCOMING
    }

    fun decline() = calls.decline()

    fun hangUp() = calls.hangUp()

    fun dismiss() = calls.dismiss()

    fun toggleMute() {
        (uiState.value.call as? CallState.Live)?.let { calls.setMuted(!it.muted) }
    }

    fun toggleSpeaker() {
        val s = uiState.value
        if (s.canSpeaker) calls.setRoute(if (s.speaker) AudioRoute.EARPIECE else AudioRoute.SPEAKER)
    }

    /** The member answered the microphone prompt: with the permission, what was held back goes on. */
    fun microphoneAnswered(granted: Boolean) {
        val request = prompt.value ?: return
        prompt.value = null
        if (!granted) return
        val connectionId = request.call
        if (connectionId != null) calls.call(connectionId) else calls.answer()
    }
}
