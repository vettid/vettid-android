package com.vettid.app.di

import com.vettid.core.data.wipe.LocalWipe
import com.vettid.core.data.wipe.WipeHook
import com.vettid.core.data.wipe.WipeTargets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    @Test
    fun aModuleHookThatThrowsDoesNotStopTheAppsOwnSteps() = runBlocking {
        val ran = mutableListOf<String>()
        val dir = java.nio.file.Files.createTempDirectory("wipe").toFile()
        val targets = object : WipeTargets {
            override val marker = java.io.File(dir, "wipe.marker")
            override val dirs = listOf(dir)

            override fun keyAliases(): List<String> = emptyList()

            override fun deleteKey(alias: String) = Unit

            override fun clearSharedPreferences() = Unit

            override fun cancelNotifications() = Unit
        }
        val failing = WipeHook { error("the wallet's hook fails") }
        val hooks = AppModule.wipeHooks(setOf(failing), listOf({ ran += "app lock" }, { ran += "prefs" }))
        val wipe = LocalWipe(targets, hooks)
        wipe.begin()
        wipe.resetMemory()
        assertEquals(listOf("app lock", "prefs"), ran)
        assertTrue(wipe.erase())
        wipe.finish()
        assertTrue(dir.deleteRecursively())
    }
}
