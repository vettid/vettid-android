package com.vettid.core.data.wipe

import com.vettid.core.data.vault.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Erase VettID's data on this phone": offered only after a wipe that did not finish; the keys go before the clear. */
class AppDataClearTest {
    @Test
    fun offeredOnlyAfterAWipeThatDidNotFinish() {
        for (k in FailureKind.entries) assertEquals(k.name, k == FailureKind.ERASE_FAILED, AppDataClear.offeredFor(k))
        assertFalse(AppDataClear.offeredFor(null))
    }

    @Test
    fun theKeysAreDeletedBeforeTheClear() {
        val steps = mutableListOf<String>()
        val clear = AppDataClear(
            keyAliases = { listOf("a", "b") },
            deleteKey = { steps += "delete $it" },
            clearAppData = {
                steps += "clear"
                true
            },
        )
        assertTrue(clear.run())
        assertEquals(listOf("delete a", "delete b", "clear"), steps)
    }

    @Test
    fun aKeystoreThatFailsDoesNotStopTheClear() {
        val steps = mutableListOf<String>()
        val clear = AppDataClear(
            keyAliases = { listOf("a", "b") },
            deleteKey = { if (it == "a") error("keystore") else steps += "delete $it" },
            clearAppData = {
                steps += "clear"
                true
            },
        )
        assertTrue(clear.run())
        assertEquals(listOf("delete b", "clear"), steps)
        val noList = AppDataClear(keyAliases = { error("keystore") }, deleteKey = {}, clearAppData = { true })
        assertTrue(noList.run())
    }

    @Test
    fun aRefusedClearIsReported() {
        assertFalse(AppDataClear(keyAliases = { emptyList() }, deleteKey = {}, clearAppData = { false }).run())
        assertFalse(AppDataClear(keyAliases = { emptyList() }, deleteKey = {}, clearAppData = { error("refused") }).run())
    }
}
