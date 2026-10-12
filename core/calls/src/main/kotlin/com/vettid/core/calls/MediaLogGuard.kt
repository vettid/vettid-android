package com.vettid.core.calls

/** The media stack's logging is on, or cannot be shown to be off: no key may be given to the stack. */
class MediaLoggingException(what: String, cause: Throwable? = null) : IllegalStateException("media stack logging: $what", cause)

/**
 * Keeps key material out of logs. The media stack's key derivation logs its input key, its salt and the derived key
 * at severity INFO (docs/calls/CALL-MEDIA.md §7.1, read from the library's source; the texts are in the shipped
 * binary). They are printed only if something switches the stack's logging on, so nothing in this app ever does:
 *
 * - no code calls `Logging.enableLogToDebugOutput`, `enableLogThreads` or `enableLogTimeStamps`,
 *   `InitializationOptions.Builder.setInjectableLogger` or `setEnableInternalTracer` (`MediaLoggingSourceTest`
 *   reads every source file of the repository for them);
 * - [check] looks at the stack's own switches before the stack is started and again before every key is set, and
 *   refuses with [MediaLoggingException] if either is on, or if the switches cannot be read (a changed library is
 *   inspected again before it is trusted). A call without keys sends and plays nothing.
 */
object MediaLogGuard {
    /** Names no source file outside this one and its tests may contain. */
    val FORBIDDEN: List<String> = listOf(
        "enableLogToDebugOutput", "enableLogThreads", "enableLogTimeStamps", "setInjectableLogger", "injectLoggable",
        "setEnableInternalTracer",
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
