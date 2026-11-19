package com.vettid.feature.wallet

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The view models' tests build their wallet flags only with [testFlags] (the calling thread, never `Dispatchers.IO`): IO work
 * that finished after its test came back through the view model's Main dispatcher while the next test's
 * [MainDispatcherRule] set it, and failed that test now and then on CI.
 */
class WalletTestsOffMainTest {
    @Test
    fun everyTestBuildsItsFlagsWithTestFlags() {
        val dir = File("src/test/kotlin/com/vettid/feature/wallet")
        val direct = dir.walk()
            .filter { it.isFile && it.extension == "kt" && it.name != "WalletTestSupport.kt" && it.name != "WalletTestsOffMainTest.kt" }
            .filter { f -> f.readLines().any { "WalletFlags(" in it } }
            .map { it.name }
            .sorted()
            .toList()
        assertEquals("build the flags with testFlags(...)", emptyList<String>(), direct)
    }
}
