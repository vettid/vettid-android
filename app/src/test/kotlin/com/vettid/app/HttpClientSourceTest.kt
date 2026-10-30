package com.vettid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every HTTP client comes from the shared one in AppModule (which sends the fixed User-Agent, VAULT-MESSAGING §13.6),
 * or derives from it with `newBuilder()`. A new `OkHttpClient()` or `OkHttpClient.Builder()` in main code outside
 * AppModule and the dev-stack helper would send OkHttp's own User-Agent.
 */
class HttpClientSourceTest {
    @Test
    fun noOtherClientIsBuilt() {
        val root = File("..").canonicalFile
        val dirs = listOf(File(root, "app")) + File(root, "core").listFiles().orEmpty() + File(root, "feature").listFiles().orEmpty()
        val files = dirs.map { File(it, "src/main") }.filter { it.isDirectory }
            .flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue("the source was not found from ${File(".").canonicalPath}", files.size > MIN_FILES)
        val hits = files.filter { it.name != "AppModule.kt" && it.name != "DevStack.kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (CLIENT.containsMatchIn(line)) "${f.relativeTo(root)}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertEquals("clients built outside AppModule:\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }

    @Test
    fun theCheckSeesWhatItLooksFor() {
        assertTrue(CLIENT.containsMatchIn("val c = OkHttpClient()"))
        assertTrue(CLIENT.containsMatchIn("val c = OkHttpClient.Builder().build()"))
    }

    private companion object {
        const val MIN_FILES = 50
        val CLIENT = Regex("""OkHttpClient(\.Builder)?\(""")
    }
}
