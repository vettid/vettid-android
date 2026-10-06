package com.vettid.core.data.vault

import com.vettid.core.relay.RelayException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** W9 staging (2026-10-06): no relay error that escapes a coroutine of the process scope may end the process. */
class RelaySafetyNetTest {
    private val relayErrors = mutableListOf<IOException>()
    private val fatal = mutableListOf<Throwable>()
    private val net = RelaySafetyNet(
        onRelayError = { synchronized(relayErrors) { relayErrors.add(it) } },
        fatal = { synchronized(fatal) { fatal.add(it) } },
    )

    private fun escape(e: Throwable) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + net)
        scope.launch { throw e }.join()
        // The scope survives: a sibling still runs.
        assertTrue(scope.launch { }.let { it.join(); it.isCompleted })
    }

    @Test
    fun aTokenRevokedThatEscapesIsNotFatal() {
        val e = RelayException(403, RelayException.TOKEN_REVOKED, "deposit token has been revoked")
        escape(e)
        assertEquals(listOf<IOException>(e), relayErrors)
        assertTrue(fatal.isEmpty())
    }

    @Test
    fun otherRelayAndTransportErrorsAreNotFatal() {
        val errors = listOf(
            RelayException(403, RelayException.TOKEN_EXPIRED, ""),
            RelayException(404, RelayException.MAILBOX_UNKNOWN, ""),
            RelayException(503, "http_503", "", retryAfterSeconds = 5),
            IOException("connection reset"),
        )
        errors.forEach(::escape)
        assertEquals(errors, relayErrors)
        assertTrue(fatal.isEmpty())
    }

    @Test
    fun anythingElseStillGoesToTheFatalHandler() {
        val e = IllegalStateException("a bug")
        escape(e)
        assertSame(e, fatal.single())
        assertTrue(relayErrors.isEmpty())
    }
}
