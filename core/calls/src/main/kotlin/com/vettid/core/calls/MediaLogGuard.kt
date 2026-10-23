package com.vettid.core.calls

/** The media stack's logging is on, or cannot be shown to be off: no key may be given to the stack. */
class MediaLoggingException(what: String, cause: Throwable? = null) : IllegalStateException("media stack logging: $what", cause)

/**
 * Keeps key material out of logs. Up to 150.7871.01 the media stack's key derivation logged its input key, its salt
 * and the derived key at severity INFO (docs/calls/CALL-MEDIA.md §7.1, read from the library's source; the texts
 * were in the shipped binary); 150.7871.04 logs sizes only, but a stack's logging is still nothing a call wants, so
 * nothing in this app ever switches it on:
 *
 * - no code names any of the stack's ways to log, trace or record ([FORBIDDEN]; `MediaGuardsTest` reads every
 *   source file of the repository for them). That list is every such entry point found in the pinned artifact's
 *   classes (`javap` over the AAR, 2026-10-12; 150.7871.04 changes no logging class or entry point, 2026-10-23),
 *   including the ones [check] cannot see: a
 *   `CallSessionFileRotatingLogSink` adds a native file sink at any severity without touching the two switches,
 *   and the event log, the echo-canceller dump and the sample callbacks write a call's metadata or its raw audio;
 * - [check] looks at the stack's own switches before the stack is started and again before every key is set, and
 *   refuses with [MediaLoggingException] if either is on, or if the switches cannot be read (a changed library is
 *   inspected again before it is trusted). A call without keys sends and plays nothing.
 */
object MediaLogGuard {
    /** Names no source file outside this one and its tests may contain. */
    val FORBIDDEN: List<String> = listOf(
        // Logging: debug output, an injected logger, a file sink.
        "enableLogToDebugOutput", "enableLogThreads", "enableLogTimeStamps", "setInjectableLogger", "injectLoggable",
        "CallSessionFileRotatingLogSink", "JNILogging", "Loggable",
        // Tracing and stack dumps.
        "setEnableInternalTracer", "initializeInternalTracer", "printStackTraces", "printInternalStackTraces",
        // Records of a call: its packet metadata, its raw microphone and playback audio.
        "startRtcEventLog", "startAecDump", "setSamplesReadyCallback", "setPlaybackSamplesReadyCallback",
        "setEnableVolumeLogger",
    )
    internal const val LOGGING_CLASS = "org.webrtc.Logging"
    internal const val ENABLED_FIELD = "loggingEnabled"
    internal const val LOGGABLE_FIELD = "loggable"

    /** Throws unless the stack's logging is off: no debug output enabled, no injected logger. */
    fun check() {
        val cls = try {
            Class.forName(LOGGING_CLASS)
        } catch (e: ReflectiveOperationException) {
            throw MediaLoggingException("the stack's logging class is not there", e)
        } catch (e: LinkageError) {
            throw MediaLoggingException("the stack's logging class does not load", e)
        }
        check(cls)
    }

    internal fun check(cls: Class<*>) {
        val enabled = read(cls, ENABLED_FIELD)
        if (enabled != false) throw MediaLoggingException("debug output is enabled")
        if (read(cls, LOGGABLE_FIELD) != null) throw MediaLoggingException("a logger is injected")
    }

    private fun read(cls: Class<*>, name: String): Any? = try {
        cls.getDeclaredField(name).apply { isAccessible = true }.get(null)
    } catch (e: ReflectiveOperationException) {
        throw MediaLoggingException("cannot read $name", e)
    } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
        // Not static, not accessible, or anything else a changed class may throw: all the same answer.
        throw MediaLoggingException("cannot read $name", e)
    }
}
