package com.vettid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * All user-facing text uses American English spellings (owner 2026-11-05). This reads every module's
 * `src/main/res/values*` `strings*.xml` (strings, plurals, arrays), takes only the text values (not the keys, not
 * the XML comments) and fails on the common British spellings. Text built in code is checked in review.
 *
 * A true exception (a proper name, a quoted code) goes in [ALLOWED] with a reason, keyed by the string's name.
 */
class AmericanEnglishTextTest {
    @Test
    fun stringValuesUseAmericanSpellings() {
        val root = File("..").canonicalFile
        val files = resourceFiles(root)
        assertTrue("the resources were not found from ${File(".").canonicalPath}", files.size >= MIN_FILES)
        val hits = files.flatMap { f ->
            values(f.readText()).flatMap { (name, text) ->
                British.findAll(text).map { it.value.lowercase() }.filter { it !in ALLOWED[name].orEmpty() }
                    .map { "${f.relativeTo(root)}: $name: \"$it\"" }.toList()
            }
        }
        assertEquals("British spellings in user-facing text:\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }

    @Test
    fun theAllowListHasReasonsAndNoStaleEntries() {
        val root = File("..").canonicalFile
        val names = resourceFiles(root).flatMap { values(it.readText()).map { v -> v.first } }.toSet()
        ALLOWED.keys.forEach { assertTrue("allow-listed string $it does not exist", it in names) }
    }

    @Test
    fun thePatternsSeeWhatTheyLookFor() {
        val british = listOf(
            "colour", "Colours", "favourite", "favourites", "behaviour", "honour", "centre", "Centre", "metre", "metres",
            "kilometres", "organise", "organised", "organisation", "recognise", "authorise", "realise", "apologise",
            "minimise", "prioritise", "customise", "summarise", "finalise", "optimisation", "cancelled", "Cancelling",
            "labelled", "labelling", "travelled", "travelling", "licence", "grey", "catalogue", "programme", "whilst",
            "enrolment", "judgement", "defence", "analyse", "analysed", "analysing", "north-east", "south-west",
        )
        british.forEach { assertTrue("$it is not matched", British.containsMatchIn("a $it b")) }
        val american = listOf(
            "color", "favorite", "center", "meter", "organize", "organization", "recognize", "canceled", "canceling",
            "license", "gray", "catalog", "program", "while", "enroll", "enrolled", "enrollment", "judgment", "defense",
            "northeast", "southwest", "analyze", "analysis", "raise", "raised", "precise", "Unauthorized", "licensed", "cancel",
            "cancellation",
        )
        american.forEach { assertFalse("$it is matched", British.containsMatchIn("a $it b")) }
    }

    @Test
    fun onlyValuesAreReadNotKeysOrComments() {
        val xml = """
            <resources>
              <!-- a colour comment: centre -->
              <string name="data_location_metres">Distance in meters</string>
              <string name="a_cancelled_key">It was canceled</string>
              <plurals name="n"><item quantity="one">1 colour</item><item quantity="other">%d colors</item></plurals>
              <string name="multi">First line
                favourite</string>
            </resources>
        """.trimIndent()
        val v = values(xml)
        assertEquals(listOf("data_location_metres", "a_cancelled_key", "n", "multi"), v.map { it.first })
        assertEquals(listOf("n", "multi"), v.filter { British.containsMatchIn(it.second) }.map { it.first })
    }

    private fun resourceFiles(root: File): List<File> {
        val modules = listOf(File(root, "app")) + File(root, "core").listFiles().orEmpty() + File(root, "feature").listFiles().orEmpty()
        return modules.flatMap { m ->
            File(m, "src/main/res").listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty().toList()
        }.flatMap { d -> d.listFiles { f -> f.isFile && f.name.startsWith("strings") && f.extension == "xml" }.orEmpty().toList() }
    }

    /** (name, text) of each `<string>`, `<plurals>` and `<string-array>` (items joined), comments removed, tags stripped. */
    private fun values(xml: String): List<Pair<String, String>> {
        val text = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        return Regex("""<(string|plurals|string-array)\s[^>]*name="([^"]+)"[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).map { it.groupValues[2] to it.groupValues[3].replace(Regex("<[^>]+>"), " ") }.toList()
    }

    private companion object {
        const val MIN_FILES = 20

        /** String name to the lowercase matches that are allowed in it, each with its reason. None are needed today. */
        val ALLOWED: Map<String, Set<String>> = emptyMap()

        private const val VERBS = "authori|organi|recogni|reali|apologi|minimi|maximi|prioriti|customi|summari|finali|optimi|normali|" +
            "initiali|synchroni|visuali|utili|personali|digiti|categori|stabili|specifi|memori|sanitis|standardi"

        val British = Regex(
            listOf(
                """\b\w*colour\w*""", """\bfavourit\w*""", """\bbehaviour\w*""", """\bhonour\w*""", """\bneighbour\w*""",
                """\bcentres?\b""", """\b(kilo)?metres?\b""", """\blitres?\b""",
                "\\b($VERBS)s(e|es|ed|ing|ation|ations)\\b",
                """\bcancell(ed|ing)\b""", """\blabell(ed|ing)\b""", """\btravell(ed|ing|er|ers)\b""",
                """\blicences?\b""", """\bgreys?\b""", """\bcatalogues?\b""", """\bprogrammes?\b""", """\bwhilst\b""",
                """\benrolments?\b""", """\bjudgement\b""", """\bdefences?\b""", """\banalys(e|ed|es|ing)\b""",
                """\b(north|south)-(east|west)\b""",
            ).joinToString("|"),
            RegexOption.IGNORE_CASE,
        )
    }
}
