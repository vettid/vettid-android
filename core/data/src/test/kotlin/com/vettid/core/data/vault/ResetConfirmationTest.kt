package com.vettid.core.data.vault

import com.vettid.core.vault.ChangeTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the vault manager does when a `credential.reset` is confirmed (answer, resend or check), without the manager. */
@OptIn(ExperimentalCoroutinesApi::class)
class ResetConfirmationTest {
    private val reset = UnresolvedChange(ChangeTypes.RESET, "01JRESET", 1, 0, heldKey = "key-one")
    private val pin = UnresolvedChange(ChangeTypes.PIN_CHANGE, "01JPIN", 1, 0)

    private class Effects : ResetEffects {
        val events = mutableListOf<String>()

        override fun closeWindow() {
            events += "window"
        }

        override fun clearItems() {
            events += "items"
        }

        override suspend fun afterwards() {
            events += "afterwards"
        }
    }

    private fun TestScope2.build(hooks: List<() -> (suspend () -> Unit)>) = ResetConfirmation(scope, hooks, effects)

    private class TestScope2(val scope: CoroutineScope, val effects: Effects)

    /** Both paths confirm the same reset: the record is written each time, the effects run once. */
    @Test
    fun theEffectsRunOnceWhenBothPathsConfirm() = runTest {
        val t = TestScope2(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), Effects())
        var hookActions = 0
        var prepares = 0
        val c = t.build(listOf({ prepares++; { hookActions++ } }))
        var writes = 0
        assertTrue(c.confirm(reset) { writes++; true })
        assertTrue(c.confirm(reset) { writes++; true })
        testScheduler.runCurrent()
        assertEquals(2, writes)
        assertEquals(1, prepares)
        assertEquals(1, hookActions)
        assertEquals(listOf("window", "items", "afterwards"), t.effects.events)
        t.scope.cancel()
    }

    /** The window is closed and the items cleared at once; the hooks' actions run before anything that needs a session. */
    @Test
    fun theWindowAndItemsGoAtOnceAndTheHooksRunFirst() = runTest {
        val t = TestScope2(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), Effects())
        val c = t.build(listOf({ { t.effects.events += "hook" } }))
        c.confirm(reset) { true }
        assertEquals(listOf("window", "items"), t.effects.events)
        testScheduler.runCurrent()
        assertEquals(listOf("window", "items", "hook", "afterwards"), t.effects.events)
        t.scope.cancel()
    }

    /** A hook that throws still lets the record be written (the marker cleared) and the effects run once. */
    @Test
    fun aHookThatThrowsStillConfirmsTheReset() = runTest {
        val t = TestScope2(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), Effects())
        val c = t.build(listOf({ error("prepare failed") }, { { t.effects.events += "hook" } }))
        var written = false
        assertTrue(c.confirm(reset) { written = true; true })
        c.confirm(reset) { true }
        testScheduler.runCurrent()
        assertTrue(written)
        assertEquals(listOf("window", "items", "hook", "afterwards"), t.effects.events)
        t.scope.cancel()
    }

    /** The record's result is returned as it is (a failed write is still "shown as done" by the caller); only resets have effects. */
    @Test
    fun otherChangesHaveNoEffectsAndTheResultIsReturned() = runTest {
        val t = TestScope2(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), Effects())
        val c = t.build(listOf({ { t.effects.events += "hook" } }))
        assertEquals(false, c.confirm(pin) { false })
        testScheduler.runCurrent()
        assertTrue(t.effects.events.isEmpty())
        assertEquals(false, c.confirm(reset) { false })
        t.scope.cancel()
    }
}
