package com.vettid.app.di

import com.vettid.core.data.wipe.WipeHook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The wipe's in-memory steps: the modules' hooks (the wallet's generation) before the app's own, so before anything is erased. */
class WipeHooksOrderTest {
    @Test
    fun theModulesHooksRunFirst() = runBlocking {
        val ran = mutableListOf<String>()
        val hooks = AppModule.wipeHooks(setOf(WipeHook { ran += "wallet" }), listOf({ ran += "app lock" }, { ran += "prefs" }))
        for (h in hooks) h()
        assertEquals(listOf("wallet", "app lock", "prefs"), ran)
    }

    @Test
    fun noModuleHookIsTheAppsOwnList() {
        assertEquals(2, AppModule.wipeHooks(emptySet(), listOf({}, {})).size)
    }
}
