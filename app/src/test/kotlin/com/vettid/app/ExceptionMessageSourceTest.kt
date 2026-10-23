package com.vettid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The owner's rule (2026-10-23): an exception's message never carries vault data, since logs and crash reports may carry
 * the message (crashes are scrubbed, but a message logged or wrapped elsewhere is not). A coarse check of the source:
 * no `throw …Exception("…$x…")` or `error("…$x…")` in the app's own code (app, core, feature; not tests, not
 * :core:testing) interpolates a value whose name says it is a secret or a member's data. Names, item values,
 * message text and the like are described instead ("item value is not a valid phone number").
 */
class ExceptionMessageSourceTest {
    @Test
    fun noExceptionMessageInterpolatesSensitiveValues() {
        val root = File("..").canonicalFile
        val dirs = listOf(File(root, "app")) +
            (File(root, "core").listFiles().orEmpty().filter { it.name != "testing" }) +
            File(root, "feature").listFiles().orEmpty()
        val files = dirs.flatMap { d ->
            File(d, "src").listFiles().orEmpty()
                .filter { it.name !in TEST_SOURCE_SETS }
                .flatMap { s -> s.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        }
        assertTrue("the source was not found from ${File(".").canonicalPath}", files.size > MIN_FILES)
        val hits = files.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (SENSITIVE.containsMatchIn(line)) "${f.relativeTo(root)}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertEquals("exception messages with sensitive values:\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }

    @Test
    fun theCheckSeesWhatItLooksFor() {
        val d = '$'
        assertTrue(SENSITIVE.containsMatchIn("throw IllegalStateException(\"not a phone number: ${d}{item.value}\")"))
        assertTrue(SENSITIVE.containsMatchIn("throw CryptoException.Format(\"bad ${d}password\")"))
        assertTrue(SENSITIVE.containsMatchIn("?: error(\"no ${d}{e.message}\")"))
        assertTrue(!SENSITIVE.containsMatchIn("throw CryptoException.Format(\"json: missing ${d}name\")"))
        assertTrue(!SENSITIVE.containsMatchIn("throw VaultStateException(\"${d}type: no response\")"))
    }

    private companion object {
        val TEST_SOURCE_SETS = setOf("test", "androidTest", "testFixtures")
        const val MIN_FILES = 200

        /** A `throw XxxException("…$v` or `error("…$v` whose value [v] (or `x.v`) has one of these names. */
        val SENSITIVE = Regex(
            """(throw\s+[\w.]*(Exception|Error|Failure)[\w.]*\(|\berror\()"[^"]*\$\{?([a-z]\w*\.)*""" +
                """(password|passphrase|pin|secret|seed|plaintext|privateKey|firstName|lastName|displayName|email|""" +
                """phone|value|itemValue|payload|body|text|answer|message|detail|token)\b""",
        )
    }
}
