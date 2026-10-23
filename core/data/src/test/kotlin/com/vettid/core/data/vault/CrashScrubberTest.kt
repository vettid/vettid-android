package com.vettid.core.data.vault

import com.vettid.core.data.vault.RelaySafetyNet.Scrubbed
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The owner's rule (2026-10-23): a crash anywhere in the process reaches Android's handler with class names and stack
 * frames only, never a message.
 */
class CrashScrubberTest {
    private var before: Thread.UncaughtExceptionHandler? = null
    private val received = Collections.synchronizedList(mutableListOf<Pair<Thread, Throwable>>())
    private val android = Thread.UncaughtExceptionHandler { t, e -> received += t to e }

    @Before
    fun setUp() {
        before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(android)
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(before)
    }

    /** Every throwable of [t]'s chain (causes and suppressed). */
    private fun chain(t: Throwable): List<Throwable> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val out = mutableListOf<Throwable>()
        fun walk(x: Throwable) {
            if (!seen.add(x)) return
            out += x
            x.cause?.let(::walk)
            x.suppressed.forEach(::walk)
        }
        walk(t)
        return out
    }

    private fun secretCrash(): Throwable {
        val e = IllegalStateException("item value +1 555 0100 of Alice Example", IOException("relay: 403 token_revoked: secret answer"))
        e.addSuppressed(IllegalArgumentException("message text: meet at noon"))
        return e
    }

    @Test
    fun installedItHandsAScrubbedCopyToThePreviousHandlerOnce() {
        CrashScrubber.install()
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() is CrashScrubber)

        val e = secretCrash()
        val t = Thread { throw e }
        t.start()
        t.join()

        val (thread, s) = received.single()
        assertSame(t, thread)
        val original = chain(e)
        val scrubbed = chain(s)
        assertEquals(original.map { it.javaClass.name }, scrubbed.map { it.message })
        assertTrue(scrubbed.all { it is Scrubbed })
        for (x in scrubbed) {
            assertFalse(x.toString(), listOf("Alice", "555", "secret", "noon", "token_revoked").any { x.toString().contains(it) })
        }
        // The frames are kept, every one of them.
        original.zip(scrubbed).forEach { (o, x) -> assertArrayEquals(o.stackTrace, x.stackTrace) }
    }

    @Test
    fun aSecondInstallChangesNothing() {
        CrashScrubber.install()
        val first = Thread.getDefaultUncaughtExceptionHandler()
        CrashScrubber.install()
        assertSame(first, Thread.getDefaultUncaughtExceptionHandler())

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), secretCrash())
        // Not chained to itself: the previous handler once, with the scrubbed copy.
        assertEquals(1, received.size)
        assertEquals(IllegalStateException::class.java.name, received.single().second.message)
    }

    @Test
    fun relaySafetyNetsScrubbedCrashIsPassedOnAsItIs() {
        CrashScrubber.install()
        // RelaySafetyNet.crash: the thread's handler (here the default one, through the thread group) gets a Scrubbed.
        RelaySafetyNet.crash(keepFrames = true)(secretCrash())
        val s = received.single().second
        assertEquals(IllegalStateException::class.java.name, s.message)
        assertEquals(IOException::class.java.name, s.cause!!.message)
    }

    @Test
    fun scrubbingTwiceKeepsTheClassNames() {
        val once = Scrubbed.of(secretCrash(), keepFrames = true)
        val wrapped = RuntimeException("wraps it", once)
        val twice = Scrubbed.of(wrapped, keepFrames = true)
        assertEquals(
            listOf(RuntimeException::class.java.name) + chain(once).map { it.message },
            chain(twice).map { it.message },
        )
    }

    @Test
    fun aScrubbingThatFailsPassesTheClassNameOnlyAndDoesNotRecurse() {
        var scrubs = 0
        val handler = CrashScrubber(android, scrub = { scrubs++; throw StackOverflowError() })
        handler.uncaughtException(Thread.currentThread(), secretCrash())
        assertEquals(1, scrubs)
        val s = received.single().second
        assertTrue(s is CrashScrubber.ScrubFailed)
        assertEquals(IllegalStateException::class.java.name, s.message)
        assertNull(s.cause)
        assertEquals(0, s.stackTrace.size)
        assertEquals(0, s.suppressed.size)
    }

    /** A throwable whose frames cannot be read: the real scrubbing fails on it. */
    private class Unreadable : IllegalStateException("Alice Example") {
        override fun getStackTrace(): Array<StackTraceElement> = throw UnsupportedOperationException("Alice Example")
    }

    @Test
    fun aThrowableThatCannotBeScrubbedStillCrashesWithItsClassNameOnly() {
        CrashScrubber.install()
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), Unreadable())
        val s = received.single().second
        assertEquals(Unreadable::class.java.name, s.message)
        assertFalse(s.toString().contains("Alice"))
    }
}
