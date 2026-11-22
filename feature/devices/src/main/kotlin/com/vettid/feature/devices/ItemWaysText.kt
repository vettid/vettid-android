package com.vettid.feature.devices

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import com.vettid.core.data.devices.ItemWay
import com.vettid.core.data.devices.ItemWaysRules

/**
 * A piece of a string as ANDROID-PLAN 0.1.137 AA8 "How a string is shown" draws it: plain printable ASCII ([Text] with
 * `plain`), other characters highlighted ([Text] without), an escaped code point ([Escape]) or a marked space ([Space]).
 */
sealed interface ShownPiece {
    data class Text(val text: String, val plain: Boolean) : ShownPiece

    data class Escape(val codePoint: Int) : ShownPiece {
        /** `U+202E`: at least four hex digits, upper case. */
        val label: String get() = "U+%04X".format(codePoint)
    }

    data object Space : ShownPiece
}

/** AA8 "How a string is shown": what is escaped, what is marked and what is highlighted. Pure. */
object ShownString {
    private const val SPACE = 0x20
    private const val TILDE = 0x7E
    private const val BRAILLE_BLANK = 0x2800

    private val ESCAPED_TYPES = setOf(
        Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(), Character.PRIVATE_USE.toInt(), Character.SURROGATE.toInt(),
        Character.UNASSIGNED.toInt(),
    )

    /**
     * Escaped on its own: Cc, Cf, Zl, Zp, Co, Cs, Cn; every White_Space code point but U+0020; every
     * Default_Ignorable_Code_Point; U+2800 BRAILLE PATTERN BLANK.
     */
    fun escaped(cp: Int): Boolean = Character.getType(cp) in ESCAPED_TYPES ||
        cp != SPACE && UCharacter.hasBinaryProperty(cp, UProperty.WHITE_SPACE) ||
        UCharacter.hasBinaryProperty(cp, UProperty.DEFAULT_IGNORABLE_CODE_POINT) ||
        cp == BRAILLE_BLANK

    private fun combining(cp: Int): Boolean {
        val t = Character.getType(cp)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt()
    }

    /**
     * [s] as pieces, in order. A combining mark at the start, right after a `/` or `\`, an escape or a marked space is
     * escaped too, so it cannot join the box's edge, a separator, an escape or a space mark.
     */
    fun pieces(s: String): List<ShownPiece> {
        val out = mutableListOf<ShownPiece>()
        val run = StringBuilder()
        var runPlain = true
        fun flush() {
            if (run.isNotEmpty()) out += ShownPiece.Text(run.toString(), runPlain)
            run.clear()
        }
        var prev: Int? = null
        var prevBreaks = true // the start counts as an edge
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val edge = prevBreaks || prev == '/'.code || prev == '\\'.code
            when {
                escaped(cp) || combining(cp) && edge -> {
                    flush()
                    out += ShownPiece.Escape(cp)
                    prevBreaks = true
                }
                cp == SPACE -> {
                    flush()
                    out += ShownPiece.Space
                    prevBreaks = true
                }
                else -> {
                    val plain = cp in SPACE..TILDE
                    if (run.isNotEmpty() && plain != runPlain) flush()
                    runPlain = plain
                    run.appendCodePoint(cp)
                    prevBreaks = false
                }
            }
            prev = cp
        }
        flush()
        return out
    }

    /** "Some characters here may look like others.": a character outside printable ASCII is shown, not escaped. */
    fun lookalike(pieces: List<ShownPiece>): Boolean = pieces.any { it is ShownPiece.Text && !it.plain }
}

/** A recorded fingerprint, as AA7 and AA8 show it. */
object Fingerprints {
    private const val SHORT = 16
    private const val GROUP = 4

    /** The first 16 hex digits in groups of four: "1a2b 3c4d 5e6f 7a8b". */
    fun short(sha256: String): String = grouped(sha256.take(SHORT))

    /** All 64 digits in groups of four. */
    fun full(sha256: String): String = grouped(sha256)

    private fun grouped(s: String): String = s.chunked(GROUP).joinToString(" ")
}

/** Advice on a listed program (AA7 "Run settings"; AA8 "A batch file's fingerprint"). Pure. */
object ProgramAdvice {
    private val ENDINGS = listOf(".exe", ".com", ".cmd", ".bat", ".ps1")

    /** vettid/vettid.org#636: programs that can run other commands given in their arguments. */
    val RUNS_COMMANDS = setOf(
        "git", "rsync", "tar", "sed", "npm", "yarn", "pnpm", "uv", "dotnet", "make", "find", "vim", "nvim", "vi", "ex", "cargo",
        "go", "gradle", "mvn", "pip", "pip3", "docker", "podman", "kubectl", "terraform", "gh", "scp", "sftp", "less", "ed", "zip",
    )
    private val VERSIONED_PIP = Regex("^pip[0-9][0-9.]*$")

    /**
     * The last path element as VAULT-MESSAGING §10.11's `shell` flag compares it (0.91.1): any case, without any number
     * of final `.exe`, `.com`, `.cmd`, `.bat` or `.ps1` endings, with trailing dots and spaces trimmed after each.
     */
    fun nameKey(program: String): String {
        var k = ItemWaysRules.simpleLower(ItemWaysRules.lastElement(program)).trimEnd(' ', '.')
        while (true) {
            val e = ENDINGS.firstOrNull { k.endsWith(it) } ?: break
            k = k.removeSuffix(e).trimEnd(' ', '.')
        }
        return k
    }

    /** The #636 advice applies: a listed name, or `pip` with digits and dots appended. */
    fun runsCommands(program: String): Boolean {
        val k = nameKey(program)
        return k.isNotEmpty() && (k in RUNS_COMMANDS || VERSIONED_PIP.matches(k))
    }

    /** A Windows batch file: the last part ends in `.cmd` or `.bat`, any case, trailing dots and spaces dropped. */
    fun batchFile(program: String): Boolean {
        val last = ItemWaysRules.simpleLower(ItemWaysRules.lastElement(program)).trimEnd(' ', '.')
        return last.endsWith(".cmd") || last.endsWith(".bat")
    }
}

/** The ways an item allows, in the order the agent's page lists them (`use` always first). */
fun shownWays(ways: Collection<String>): List<ItemWay> =
    ItemWay.entries.filter { it == ItemWay.USE || it.wire in ways }

/** The short labels of the agent's page rows. */
object WaysText {
    fun shortRes(w: ItemWay): Int = when (w) {
        ItemWay.USE -> R.string.ways_short_use
        ItemWay.RUN -> R.string.ways_short_run
        ItemWay.PROXY -> R.string.ways_short_proxy
        ItemWay.REVEAL -> R.string.ways_short_reveal
    }
}

/**
 * A `leash.item.set` `bad_request`'s `error.message` as ANDROID-PLAN 0.1.138 AA7 "Saving" shows it (VAULT-MESSAGING
 * 0.93.2 §10.11 "Why a setting is refused"): the message itself when it is 1 to 200 bytes of printable ASCII (0x20-0x7E),
 * never parsed; else null, and the page shows the fallback text. Pure.
 */
object VaultReason {
    private const val MAX_BYTES = 200
    private const val FIRST = 0x20
    private const val LAST = 0x7E

    fun shown(message: String?): String? =
        message?.takeIf { m -> m.isNotEmpty() && m.length <= MAX_BYTES && m.all { it.code in FIRST..LAST } }
}
