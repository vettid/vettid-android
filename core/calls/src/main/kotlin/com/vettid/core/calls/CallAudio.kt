package com.vettid.core.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.vettid.core.notify.Channels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/** Where a call's sound goes. */
enum class AudioRoute { EARPIECE, SPEAKER, WIRED, BLUETOOTH }

data class AudioState(
    /** The route in use; null when no call has the audio. */
    val route: AudioRoute? = null,
    /** The routes this phone has right now. */
    val available: List<AudioRoute> = emptyList(),
)

/** A call's audio on the phone: the communication mode, the focus and the route. No Telecom in C1 (it comes with C3). */
interface CallAudio {
    val state: StateFlow<AudioState>

    /** Takes the audio for a call: a headset if one is there, otherwise the earpiece. */
    fun start()

    fun setRoute(route: AudioRoute)

    /** Gives the audio back. Safe to call without [start]. */
    fun stop()
}

/**
 * The route the member last asked for, kept for [holdMs] (owner phone test 2026-11-25: an early tap on the speaker seemed
 * to do nothing). It is shown at once, whatever the platform reports, and asked for again at each of the platform's
 * route and device changes meanwhile: the platform switches devices asynchronously and may make its own choice when
 * the call's audio starts. After [holdMs] the platform's route is shown again, so a request it refused is seen to go.
 * A request for a route the phone no longer has is dropped, and so is one when a device is added (a headset plugged in
 * takes the call, as before). It is asked for again at most [MAX_AGAIN] times, so a platform (or another app) that keeps
 * turning it back is not answered with a call per change.
 */
internal class RouteRequest(private val now: () -> Long, private val holdMs: Long = HOLD_MS) {
    private var wanted: AudioRoute? = null
    private var until = 0L
    private var asks = 0

    fun ask(route: AudioRoute) {
        wanted = route
        until = now() + holdMs
        asks = 0
    }

    /** Whether a request is still being kept. */
    val pending: Boolean get() = live() != null

    /** The route to ask the platform for again now ([current] is not it), or null. */
    fun again(current: AudioRoute?, available: List<AudioRoute>): AudioRoute? {
        val w = live()
        if (w != null && w !in available) wanted = null // the route is gone (a headset unplugged): dropped
        return w?.takeIf { it in available && it != current && asks <= MAX_AGAIN }?.also { asks++ }
    }

    /** The route to show: the requested one while it is kept, else the platform's. */
    fun shown(current: AudioRoute?): AudioRoute? = live() ?: current

    fun clear() {
        wanted = null
    }

    private fun live(): AudioRoute? {
        if (wanted != null && now() >= until) wanted = null
        return wanted
    }

    companion object {
        const val HOLD_MS = 5_000L

        /** The first ask, made by the tap's own refresh, and this many more. */
        const val MAX_AGAIN = 3
    }
}

/**
 * The devices the platform has reported to the device callback, by id, so that a headset is told new by its own id
 * (#18637): not by the routes last shown, which a refresh in between may already have taken from the platform's list.
 * Seeded with the devices there when the callback is registered (its first call lists those).
 */
internal class HeadsetWatch {
    private val known = mutableSetOf<Int>()

    fun seed(ids: Collection<Int>) {
        known.clear()
        known += ids
    }

    /** Takes the devices just added (id, route); whether one is a headset, wired or Bluetooth, not known before. */
    fun added(devices: List<Pair<Int, AudioRoute?>>): Boolean {
        val fresh = devices.any { (id, route) -> (route == AudioRoute.WIRED || route == AudioRoute.BLUETOOTH) && id !in known }
        known += devices.map { it.first }
        return fresh
    }

    fun removed(ids: Collection<Int>) {
        known -= ids.toSet()
    }
}

/** The sounds around a call. */
interface CallTones {
    /** The caller's ring-back tone while the other phone rings. */
    fun ringBack(on: Boolean)

    /** The phone's ringtone for an incoming call (it follows the phone's ringer mode). */
    fun ringtone(on: Boolean)
}

/**
 * [CallAudio] on `AudioManager`: `MODE_IN_COMMUNICATION`, transient audio focus for voice communication, and the
 * route by `setCommunicationDevice` (the minimum SDK is 31, so only that API). Routing to a Bluetooth headset needs
 * no permission this way.
 */
class AndroidCallAudio(context: Context) : CallAudio {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val flow = MutableStateFlow(AudioState())
    override val state: StateFlow<AudioState> = flow.asStateFlow()
    private val direct = Executor { it.run() }
    private var active = false
    private val request = RouteRequest(SystemClock::elapsedRealtime)
    private val main = Handler(Looper.getMainLooper())

    private val headsets = HeadsetWatch()

    /** A device came or went while a request was kept: its reroute is made when the hold ends. */
    private var rerouteOwed = false

    /** When a request's hold ends, the platform's route is looked at and shown again (and a reroute owed is made). */
    private val holdEnded = Runnable {
        val owed = synchronized(this) { rerouteOwed.also { rerouteOwed = false } }
        refresh(reroute = owed)
    }

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        .setOnAudioFocusChangeListener { }
        .build()

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            // A headset just added takes the call, as before: the member's pending tap does not keep it away. The
            // callback's first call, at registration, lists the devices already there: those clear nothing.
            synchronized(this@AndroidCallAudio) {
                if (headsets.added(added.map { it.id to routeOf(it) })) {
                    request.clear()
                } else if (request.pending) {
                    rerouteOwed = true
                }
            }
            refresh(reroute = true)
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            synchronized(this@AndroidCallAudio) {
                headsets.removed(removed.map { it.id })
                if (request.pending) rerouteOwed = true
            }
            refresh(reroute = true)
        }
    }
    private val changed = AudioManager.OnCommunicationDeviceChangedListener { refresh(reroute = false) }

    @Synchronized
    override fun start() {
        if (active || manager == null) return
        active = true
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        manager.requestAudioFocus(focus)
        headsets.seed(manager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS).map { it.id })
        manager.registerAudioDeviceCallback(devices, null)
        manager.addOnCommunicationDeviceChangedListener(direct, changed)
        refresh(reroute = true)
    }

    @Synchronized
    override fun setRoute(route: AudioRoute) {
        if (!active || manager == null) return
        request.ask(route)
        refresh(reroute = false) // asks the platform for it (once: the switch is asynchronous)
        main.removeCallbacks(holdEnded)
        main.postDelayed(holdEnded, RouteRequest.HOLD_MS + HOLD_SLACK_MS)
    }

    private fun apply(route: AudioRoute) {
        manager?.let { m -> m.availableCommunicationDevices.firstOrNull { routeOf(it) == route }?.let { m.setCommunicationDevice(it) } }
    }

    @Synchronized
    override fun stop() {
        if (!active || manager == null) return
        active = false
        request.clear()
        rerouteOwed = false
        main.removeCallbacks(holdEnded)
        runCatching { manager.unregisterAudioDeviceCallback(devices) }
        runCatching { manager.removeOnCommunicationDeviceChangedListener(changed) }
        runCatching { manager.clearCommunicationDevice() }
        runCatching { manager.abandonAudioFocusRequest(focus) }
        manager.mode = AudioManager.MODE_NORMAL
        flow.value = AudioState()
    }

    /** A headset that was plugged in or went away changes the route: to it, or back to the earpiece. */
    @Synchronized
    private fun refresh(reroute: Boolean) {
        if (!active || manager == null) return
        val available = manager.availableCommunicationDevices
        val routes = available.mapNotNull(::routeOf).distinct().sortedBy { it.ordinal }
        // The member's own request wins while it is kept: asked for again, and no reroute of ours over it.
        request.again(manager.communicationDevice?.let(::routeOf), routes)?.let(::apply)
        if (reroute && !request.pending) {
            val preferred = PREFERRED.firstOrNull { it in routes }
            val current = manager.communicationDevice?.let(::routeOf)
            val keep = current == AudioRoute.SPEAKER && preferred == AudioRoute.EARPIECE
            if (preferred != null && preferred != current && !keep) {
                available.firstOrNull { routeOf(it) == preferred }?.let { manager.setCommunicationDevice(it) }
            }
        }
        flow.value = AudioState(request.shown(manager.communicationDevice?.let(::routeOf)), routes)
    }

    companion object {
        /** The request's end is looked at a little after it. */
        private const val HOLD_SLACK_MS = 100L

        /** A headset before the earpiece; the speaker only when the member asks. */
        private val PREFERRED = listOf(AudioRoute.BLUETOOTH, AudioRoute.WIRED, AudioRoute.EARPIECE)

        internal fun routeOf(d: AudioDeviceInfo): AudioRoute? = routeOfType(d.type)

        internal fun routeOfType(type: Int): AudioRoute? = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoute.WIRED
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID -> AudioRoute.BLUETOOTH
            else -> null
        }
    }
}

/**
 * One sound that is either playing or not. [CallsController] reports the tones' wanted state at every change of the
 * call's state, so most calls say "on" while it already is (or "off" while it is not): those do nothing. Restarting a
 * tone that plays would cut and restart it; releasing one that was just started raced the platform's tone thread
 * (`ToneGenerator: --- Stop timed out`, owner phone test 2026-10-27). [begin] returns what plays (null: it could not
 * start, and the next "on" tries again); [end] stops it.
 */
internal class ToneSlot<T : Any>(private val begin: () -> T?, private val end: (T) -> Unit) {
    private var playing: T? = null

    @Synchronized
    fun set(on: Boolean) {
        if (on == (playing != null)) return
        if (on) {
            playing = begin()
        } else {
            playing?.let(end)
            playing = null
        }
    }
}

/** [CallTones] with the platform's tone generator and VettID's own ringtone (the Calls channel's sound, owner decision 2026-10-26). */
class AndroidCallTones(context: Context) : CallTones {
    private val app = context.applicationContext

    // Stopped first, then released, and the release off the caller's (the main) thread: it waits for the generator's
    // own thread, which logged "don't call waitForExit() from this Thread object's thread" when it ran there.
    private val ringBackSlot = ToneSlot<ToneGenerator>(
        begin = {
            runCatching {
                val generator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, RING_BACK_VOLUME)
                // A tone that did not start is not "playing": released at once, and the next "on" tries again.
                val started = runCatching { generator.startTone(ToneGenerator.TONE_SUP_RINGTONE) }.getOrDefault(false)
                if (started) {
                    generator
                } else {
                    runCatching { generator.release() }
                    null
                }
            }.getOrNull()
        },
        end = { generator ->
            releaser.execute {
                runCatching { generator.stopTone() }
                runCatching { generator.release() }
            }
        },
    )

    private val ringtoneSlot = ToneSlot<Ringtone>(
        begin = {
            runCatching {
                RingtoneManager.getRingtone(app, Channels.soundUri(app, com.vettid.core.notify.R.raw.vettid_ringtone))?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
                    isLooping = true
                    play()
                }
            }.getOrNull()
        },
        end = { runCatching { it.stop() } },
    )

    override fun ringBack(on: Boolean) = ringBackSlot.set(on)

    override fun ringtone(on: Boolean) = ringtoneSlot.set(on)

    private companion object {
        const val RING_BACK_VOLUME = 60

        /** One thread, so that a stop and the release of the next tone keep their order. */
        val releaser: Executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "call-tone-release").apply { isDaemon = true }
        }
    }
}
