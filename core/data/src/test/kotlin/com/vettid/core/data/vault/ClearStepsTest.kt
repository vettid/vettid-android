package com.vettid.core.data.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The steps of a clear on a security path all run, whatever one throws; the first failure is rethrown after the last. */
class ClearStepsTest {
    @Test
    fun aThrowingStepDoesNotSkipTheLaterLockSteps() {
        val ran = mutableListOf<String>()
        val first = IllegalStateException("attachments")
        val later = NoSuchElementException("sharing")
        val steps = ClearSteps()
        // The lock collector's order (VaultManager): the second and fourth fail.
        steps.step { ran += "social" }
        steps.step {
            ran += "attachments"
            throw first
        }
        steps.step { ran += "items" }
        steps.step {
            ran += "sharing"
            throw later
        }
        steps.step { ran += "feed" }
        steps.step { ran += "profile" }
        val e = assertThrows(IllegalStateException::class.java) { steps.finish() }
        assertEquals(listOf("social", "attachments", "items", "sharing", "feed", "profile"), ran)
        assertSame(first, e)
        assertSame(later, e.suppressed.single())
    }

    @Test
    fun stepsThatAllSucceedThrowNothing() {
        var n = 0
        val steps = ClearSteps()
        repeat(3) { steps.step { n++ } }
        steps.finish()
        assertEquals(3, n)
    }

    @Test
    fun suspendingStepsAndACancellationDoNotSkipTheRestEither() = runTest {
        val ran = mutableListOf<String>()
        val steps = ClearSteps()
        steps.step {
            yield()
            ran += "forgetDevices"
        }
        steps.step { throw CancellationException("cancelled") }
        steps.step { ran += "erase" }
        val e = assertThrows(CancellationException::class.java) { steps.finish() }
        assertEquals("cancelled", e.message)
        assertEquals(listOf("forgetDevices", "erase"), ran)
    }

    @Test
    fun anErrorIsKeptAndRethrownToo() {
        var after = false
        val steps = ClearSteps()
        steps.step { throw AssertionError("bug") }
        steps.step { after = true }
        assertThrows(AssertionError::class.java) { steps.finish() }
        assertTrue(after)
        // Rethrown once: the steps can be reused.
        steps.finish()
    }
}
