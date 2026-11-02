package com.vettid.core.data.vault

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** A hook that fails to prepare never stops the others, nor (in the vault manager) the confirmation of the reset. */
class ResetHooksTest {
    @Test
    fun aPrepareThatThrowsIsDroppedAndTheOthersKept() = runBlocking {
        val ran = mutableListOf<String>()
        val hooks = listOf<() -> (suspend () -> Unit)>(
            { { ran += "first" } },
            { throw IllegalStateException("prepare failed") },
            { { ran += "third" } },
        )
        val actions = prepareResetHooks(hooks)
        assertEquals(2, actions.size)
        actions.forEach { it() }
        assertEquals(listOf("first", "third"), ran)
    }

    @Test
    fun noHooksIsNoActions() {
        assertEquals(emptyList<Any>(), prepareResetHooks(emptyList()))
    }
}
