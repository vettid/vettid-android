package com.vettid.app.spike

import android.content.Context
import android.content.Intent
import android.util.Log
import com.vettid.core.calls.CallAudio
import com.vettid.core.calls.CallEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * C0 SPIKE: the one call of the spike screen, kept by the process so that it outlives the activity (screen off,
 * another app in front) while [CallsSpikeService] holds the microphone. Debug builds only.
 */
internal object SpikeSession {
    private const val TAG = "VettIDCallsSpike"

    private val current = MutableStateFlow<CallEngine?>(null)
    val engine: StateFlow<CallEngine?> = current

    /** What the foreground service and the audio session reported, for the screen. Never anything secret. */
    val notes = MutableStateFlow(listOf<String>())

    var audio: CallAudio? = null
        private set

    /** Whether this process has started a spike call before (the media stack is then loaded, as expected). */
    var everStarted = false
        private set

    /** Loads the media stack (first use in the process) and opens the audio session and the service. */
    fun start(context: Context, key: ByteArray) {
        if (current.value != null) return
        notes.value = emptyList()
        everStarted = true
        val app = context.applicationContext
        val session = CallAudio(app)
        audio = session
        note("audio focus " + if (session.start()) "granted" else "REFUSED")
        current.value = CallEngine.create(app, key)
        // startService, not startForegroundService: the screen is visible, and a refused foreground start then
        // leaves a note on the screen instead of the crash that an unkept startForegroundService promise brings.
        runCatching { app.startService(Intent(app, CallsSpikeService::class.java)) }
            .onFailure { note("foreground service not started: ${it.javaClass.simpleName}") }
    }

    fun stop(context: Context) {
        current.value?.close()
        current.value = null
        audio?.stop()
        audio = null
        context.applicationContext.stopService(Intent(context.applicationContext, CallsSpikeService::class.java))
    }

    /** States only: no key, no SDP, no address. */
    fun note(text: String) {
        Log.i(TAG, text)
        notes.value = notes.value + text
    }
}
