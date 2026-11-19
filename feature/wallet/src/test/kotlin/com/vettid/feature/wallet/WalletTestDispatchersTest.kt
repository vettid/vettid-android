package com.vettid.feature.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No work of the view models' tests runs on another thread where it could outlive its test: work that finished there came back
 * through the view model's Main dispatcher while [MainDispatcherRule] set or reset it, and failed a test now and then on CI
 * ("Dispatchers.Main is used concurrently with setting it"). So the wallet flags are built only by [testFlags] (on the calling
 * thread), the manager only by [manager] (its compute on the calling thread), and the one `Dispatchers.Default` is [eventually]'s
 * wait, which its test always waits for. Comment lines are not read; a spelling other than these (a space before the
 * parenthesis, a function reference) is not caught, and a trailing comment or a string on a code line that has one fails.
 */
class WalletTestDispatchersTest {
    private val dir = File("src/test/kotlin/com/vettid/feature/wallet")

    private val watched = listOf(
        "WalletFlags(", "WalletsManager(", "Dispatchers.IO", "Dispatchers.Default",
        // Other threads, and the chain classes that use IO or Default inside.
        "newSingleThreadContext", "Executors.", "thread {", "thread(", "GlobalScope", "limitedParallelism",
        "ChainServers(", "EsploraClient(", "ServersChainSettings(",
    )

    private fun codeLines(f: File) = f.readLines().map { it.trim() }
        .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }

    /** Each watched spelling in the test sources' code lines, by file. */
    private fun uses(): Map<String, List<String>> = dir.walk()
        .filter { it.isFile && it.extension == "kt" && it.name != "WalletTestDispatchersTest.kt" }
        .associate { f ->
            val code = codeLines(f)
            f.name to watched.flatMap { w -> code.filter { w in it }.map { w } }
        }

    @Test
    fun onlyTheSupportFileStartsWorkOnAnotherThread() {
        val all = uses()
        assertTrue("run from the module directory: $dir not found", dir.isDirectory)
        assertTrue("the scan must see the tests", "SendViewModelTest.kt" in all.keys && "WalletTestSupport.kt" in all.keys)
        assertEquals(emptyMap<String, List<String>>(), all.filterKeys { it != "WalletTestSupport.kt" }.filterValues { it.isNotEmpty() })
        // testFlags, manager and eventually: once each.
        assertEquals(listOf("WalletFlags(", "WalletsManager(", "Dispatchers.Default"), all.getValue("WalletTestSupport.kt"))
    }

    /** The view models start no work on a dispatcher of their own: all of it is on `viewModelScope`, which the tests drive. */
    @Test
    fun theViewModelsNameNoDispatcher() {
        val main = File("src/main/kotlin/com/vettid/feature/wallet")
        assertTrue("run from the module directory: $main not found", main.isDirectory)
        val named = main.walk().filter { it.isFile && it.extension == "kt" }
            .filter { f -> codeLines(f).any { "Dispatchers." in it || "newSingleThreadContext" in it || "GlobalScope" in it } }
            .map { it.name }.sorted().toList()
        assertEquals(emptyList<String>(), named)
    }
}
