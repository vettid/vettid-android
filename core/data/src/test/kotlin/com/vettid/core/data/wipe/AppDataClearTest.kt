package com.vettid.core.data.wipe

import com.vettid.core.data.vault.FailureKind
import com.vettid.core.keystore.AndroidKeys
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/** "Erase VettID's data on this phone": offered only after a wipe that did not finish; the keys go before the clear. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppDataClearTest {
    private fun clear(
        steps: MutableList<String>,
        listed: () -> List<String> = { listOf("a", "b") },
        known: List<String> = emptyList(),
        deleteFails: (String) -> Boolean = { false },
        cleared: () -> Boolean = { true },
        io: CoroutineDispatcher = StandardTestDispatcher(),
    ) = AppDataClear(
        keyAliases = listed,
        deleteKey = { if (deleteFails(it)) error("keystore") else steps += "delete $it" },
        clearAppData = {
            steps += "clear"
            cleared()
        },
        known = known,
        io = io,
    )

    @Test
    fun offeredOnlyAfterAWipeThatDidNotFinish() {
        for (k in FailureKind.entries) assertEquals(k.name, k == FailureKind.ERASE_FAILED, AppDataClear.offeredFor(k))
        assertFalse(AppDataClear.offeredFor(null))
    }

    @Test
    fun theKeysAreDeletedBeforeTheClear() = runTest {
        val steps = mutableListOf<String>()
        assertTrue(clear(steps, io = StandardTestDispatcher(testScheduler)).run())
        assertEquals(listOf("delete a", "delete b", "clear"), steps)
    }

    @Test
    fun theKnownAliasesGoTooAsInTheWipe() = runTest {
        val steps = mutableListOf<String>()
        clear(steps, listed = { listOf("b", "c") }, known = listOf("a", "b"), io = StandardTestDispatcher(testScheduler)).run()
        assertEquals(listOf("delete a", "delete b", "delete c", "clear"), steps)
        assertTrue(AndroidKeys.ALIAS_APP_API in LocalWipe.KNOWN_ALIASES)
    }

    @Test
    fun itRunsOnItsOwnDispatcherNotTheCallers() = runTest {
        var dispatched = 0
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatched++
                block.run()
            }
        }
        assertTrue(clear(mutableListOf(), io = io).run())
        assertTrue(dispatched > 0)
    }

    @Test
    fun aKeystoreThatFailsDoesNotStopTheClear() = runTest {
        val steps = mutableListOf<String>()
        assertTrue(clear(steps, deleteFails = { it == "a" }, io = StandardTestDispatcher(testScheduler)).run())
        assertEquals(listOf("delete b", "clear"), steps)
        assertTrue(clear(mutableListOf(), listed = { error("keystore") }, io = StandardTestDispatcher(testScheduler)).run())
    }

    @Test
    fun aRefusedClearIsReported() = runTest {
        val t = StandardTestDispatcher(testScheduler)
        assertFalse(clear(mutableListOf(), cleared = { false }, io = t).run())
        assertFalse(clear(mutableListOf(), cleared = { error("refused") }, io = t).run())
    }
}
