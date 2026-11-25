package com.vettid.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Every PIN field keeps ASCII digits only (`PinPolicy.digits`): no source file with a PIN setter filters with `isDigit`, which
 * also takes the Arabic-Indic, fullwidth and other Unicode digits a keyboard may type (VAULT-MESSAGING §11.3).
 */
class PinFieldsAsciiScanTest {
    @Test
    fun noPinFieldFiltersWithIsDigit() {
        val root = File("..").canonicalFile
        val sources = listOf("app", "feature", "core").flatMap { top ->
            File(root, top).walkTopDown().onEnter { it.name != "build" }
                .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path }.toList()
        }
        val offending = sources.filter { f ->
            val text = f.readText()
            Regex("""fun set(Pin|Current)\b""").containsMatchIn(text) && "isDigit" in text
        }.map { it.relativeTo(root).path }
        assertEquals(emptyList<String>(), offending)
        // The scan read the modules: not an empty walk from a wrong directory.
        assert(sources.size > 100) { "only ${sources.size} files scanned" }
    }
}
