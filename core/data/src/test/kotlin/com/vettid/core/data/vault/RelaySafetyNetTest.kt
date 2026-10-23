package com.vettid.core.data.vault

import com.vettid.core.relay.RelayException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // --- The owner's logging rule (2026-10-23): class names only, never a message; frames only in debug builds. ---

    private fun allMessages(t: Throwable, out: MutableList<String> = mutableListOf()): List<String> {
        out += t.toString()
        t.cause?.let { allMessages(it, out) }
        t.suppressed.forEach { allMessages(it, out) }
        return out
    }

    @Test
    fun theRelayLineCarriesNoMessage() {
        val line = RelaySafetyNet.relayLine(RelayException(403, RelayException.TOKEN_REVOKED, "vault data: Alice"))
        assertFalse(line.contains("Alice"))
        assertEquals("relay error not handled: RelayException", line)
    }

    @Test
    fun aScrubbedCrashKeepsClassNamesOnlyThroughTheWholeChain() {
        val io = IOException("secret one", IllegalArgumentException("secret two"))
        io.addSuppressed(IllegalStateException("secret three"))
        val crash = CollectorFailure("phase", io)
        val release = RelaySafetyNet.Scrubbed.of(crash, keepFrames = false)
        val text = allMessages(release)
        assertTrue(text.none { it.contains("secret") || it.contains("phase") })
        assertEquals(
            listOf(CollectorFailure::class.java.name, IOException::class.java.name, IllegalArgumentException::class.java.name,
                IllegalStateException::class.java.name),
            text.map { it.substringAfter(": ") },
        )
        assertEquals(0, release.stackTrace.size)
        assertEquals(0, release.cause!!.stackTrace.size)
        val debug = RelaySafetyNet.Scrubbed.of(crash, keepFrames = true)
        assertTrue(allMessages(debug).none { it.contains("secret") })
        assertTrue(debug.stackTrace.contentEquals(crash.stackTrace))
    }

    @Test
    fun aCycleInTheChainEnds() {
        val a = IllegalStateException("a")
        val b = IOException("b", a)
        a.addSuppressed(b)
        val s = RelaySafetyNet.Scrubbed.of(a, keepFrames = false)
        assertEquals(1, s.suppressed.size)
        assertNull(s.suppressed.single().cause)
    }

    @Test
    fun theFatalHandlerHandsOnlyTheScrubbedCrashToTheThread() {
        val got = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val t = Thread { RelaySafetyNet.crash(keepFrames = false)(IllegalStateException("vault data: Alice")) }
        t.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> got.set(e) }
        t.start()
        t.join(5_000)
        val e = got.get()
        assertTrue(e is RelaySafetyNet.Scrubbed)
        assertEquals(IllegalStateException::class.java.name, e.message)
        assertTrue(allMessages(e).none { it.contains("Alice") })
    }
}
