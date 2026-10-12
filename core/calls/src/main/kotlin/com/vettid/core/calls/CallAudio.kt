package com.vettid.core.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager

/** A communication device the call's audio can go to, by Android's `AudioDeviceInfo` type. */
data class AudioRoute(val id: Int, val type: Int) {
    val label: String
        get() = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (SCO)"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth (LE)"
            AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
            else -> "Type $type"
        }
}

/**
 * The audio session of a call WITHOUT Telecom (C0 spike): communication mode, audio focus for voice, and the
 * route through `AudioManager.setCommunicationDevice` (the app's minimum SDK is 31, so no deprecated speakerphone or
 * SCO call). With `core-telecom` (C1) the route belongs to Telecom's endpoints instead, and none of this is called.
 */
class CallAudio(context: Context) {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private var previousMode = AudioManager.MODE_NORMAL
    private var active = false

    private val focus: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener { }
        .build()

    /** Enters the call's audio session, on the earpiece. Returns whether audio focus was granted. */
    fun start(): Boolean {
        if (!active) {
            previousMode = manager.mode
            active = true
        }
        val granted = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        routes().firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }?.let(::select)
        return granted
    }

    fun routes(): List<AudioRoute> = manager.availableCommunicationDevices.map { AudioRoute(it.id, it.type) }

    fun current(): AudioRoute? = manager.communicationDevice?.let { AudioRoute(it.id, it.type) }

    /** Whether Android accepted the route (it may still take a moment, for Bluetooth). */
    fun select(route: AudioRoute): Boolean {
        val device = manager.availableCommunicationDevices.firstOrNull { it.id == route.id } ?: return false
        return manager.setCommunicationDevice(device)
    }

    fun stop() {
        if (!active) return
        active = false
        manager.clearCommunicationDevice()
        manager.mode = previousMode
        manager.abandonAudioFocusRequest(focus)
    }
}
