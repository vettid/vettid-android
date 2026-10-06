package com.vettid.core.data.vault

import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The process scope's last line of defence (W9 staging, 2026-10-06: a relay `token_revoked` that escaped the
 * mailbox collector killed the old phone at every launch). A relay or transport error ([IOException], which
 * includes `RelayException`) that escapes a coroutine of the scope is reported to [onRelayError] and goes no
 * further: no relay answer may ever end the process. Every other throwable goes to [fatal], by default the
 * thread's uncaught-exception handler, so that real bugs still crash as before.
 */
class RelaySafetyNet(
    private val onRelayError: (IOException) -> Unit,
    private val fatal: (Throwable) -> Unit = { e ->
        val t = Thread.currentThread()
        t.uncaughtExceptionHandler?.uncaughtException(t, e) ?: throw e
    },
) : AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {
    override fun handleException(context: CoroutineContext, exception: Throwable) {
        if (exception is IOException) onRelayError(exception) else fatal(exception)
    }
}
