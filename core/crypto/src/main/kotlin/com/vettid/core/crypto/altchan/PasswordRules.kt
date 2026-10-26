package com.vettid.core.crypto.altchan

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The password rules of VAULT-MESSAGING 0.33.0 §11.14.7 (release R) on the phone, so that the member learns while
 * typing what the vault would answer (`weak_password{reason}`). A port of vettid-vault's `vms/recovery/password.go`
 * (the five rules in order, and its zxcvbn-like estimator with the same word lists, copied into
 * `src/main/resources/passwords/`), run with a threshold a little above the vault's, so that a password the app
 * accepts is rarely refused. The vault's verdict is final. No network.
 */
object PasswordRules {
    const val MIN_CHARS = 12

    /** The vault's floor (`GuessabilityFloor`, 10^10 guesses). */
    const val VAULT_FLOOR = 1e10

    /** The app's: a little above the vault's (§11.14.7). */
    const val APP_FLOOR = 2e10

    /** `weak_password` reasons, as the vault names them. */
    const val SHORT = "short"
    const val REPEATED = "repeated"
    const val COMMON = "common"
    const val SAME_AS_PIN = "same_as_pin"
    const val GUESSABLE = "guessable"

    private val common: Map<String, Int> by lazy { load("common-passwords.txt") }
    private val words: Map<String, Int> by lazy { load("estimator-words.txt") }

    private fun load(name: String): Map<String, Int> {
        val text = PasswordRules::class.java.getResourceAsStream("/passwords/$name")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("missing password list $name")
        val m = LinkedHashMap<String, Int>()
        var rank = 0
        for (line in text.lineSequence()) {
            val w = line.trim()
            if (w.isEmpty() || w.startsWith("#")) continue
            rank++
            m.putIfAbsent(w, rank)
        }
        return m
    }

    /**
     * The first rule [password] breaks, in the vault's order, or null: at least 12 characters (code points), not one
     * character repeated, not on the common list (ASCII letters lowered), not the [pin], at least [floor] guesses.
     * [password] is normalized to NFC first, as the vault does.
     */
    fun check(password: String, pin: String?, floor: Double = APP_FLOOR): String? {
        val pw = Passwords.nfc(password)
        val cps = pw.codePoints().toArray()
        return when {
            cps.size < MIN_CHARS -> SHORT
            cps.all { it == cps[0] } -> REPEATED
            lowerAscii(pw) in common -> COMMON
            !pin.isNullOrEmpty() && pw == pin -> SAME_AS_PIN
            guesses(pw) < floor -> GUESSABLE
            else -> null
        }
    }

    private fun lowerAscii(s: String): String = buildString(s.length) { s.forEach { append(if (it in 'A'..'Z') it + 32 else it) } }

    // --- the estimator (vettid-vault's, rule for rule) ---

    private const val MIN_GUESSES_SINGLE = 10.0
    private const val MIN_GUESSES_MULTI = 50.0
    private const val BRUTEFORCE_CARD = 10.0
    private const val MAX_ESTIMATE_CHARS = 128
    private const val MAX_PARTS = 12

    private val leet = mapOf(
        '4' to 'a', '@' to 'a', '8' to 'b', '(' to 'c', '3' to 'e', '6' to 'g', '1' to 'i', '!' to 'i', '|' to 'i',
        '0' to 'o', '$' to 's', '5' to 's', '7' to 't', '+' to 't', '2' to 'z',
    ).entries.associate { it.key.code to it.value.code }

    private val keyboardRows = listOf(
        "`1234567890-=", "qwertyuiop[]\\", "asdfghjkl;'", "zxcvbnm,./", "1qaz2wsx3edc4rfv5tgb6yhn7ujm8ik,9ol.0p;/",
    )

    private fun str(cps: IntArray): String = String(cps, 0, cps.size)

    private fun lower(s: String) = s.lowercase(Locale.ROOT)

    private fun reverse(s: String): String = str(s.codePoints().toArray().reversedArray())

    private fun dictGuesses(sub: IntArray): Double {
        var best = Double.POSITIVE_INFINITY
        fun tryWord(s: String, factor: Double) {
            common[s]?.let { best = min(best, it * factor) }
            words[s]?.let { best = min(best, it * factor) }
        }
        val orig = str(sub)
        val lowered = lower(orig)
        var upper = 1.0
        if (lowered != orig) {
            upper = 2.0
            val rest = str(sub.copyOfRange(1, sub.size))
            val capitalized = Character.isUpperCase(sub[0]) && lower(rest) == rest
            if (!capitalized && orig.uppercase(Locale.ROOT) != orig) upper = 2.0.pow(sub.count { Character.isUpperCase(it) })
        }
        tryWord(lowered, upper)
        tryWord(reverse(lowered), 2 * upper)
        val lc = lowered.codePoints().toArray()
        var subs = 0
        val ul = IntArray(lc.size) { i -> leet[lc[i]]?.also { subs++ } ?: lc[i] }
        if (subs > 0) {
            val f = upper * 2.0.pow(subs)
            tryWord(str(ul), f)
            tryWord(reverse(str(ul)), 2 * f)
        }
        return best
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // as vettid-vault's sequenceGuesses
    private fun sequenceGuesses(cps: IntArray): Double {
        if (cps.size < 3) return Double.POSITIVE_INFINITY
        val d = cps[1] - cps[0]
        if (d == 0 || d > 2 || d < -2) return Double.POSITIVE_INFINITY
        for (i in 2 until cps.size) if (cps[i] - cps[i - 1] != d) return Double.POSITIVE_INFINITY
        val first = Character.toLowerCase(cps[0])
        var base = when {
            first == 'a'.code || first == 'z'.code || first == '0'.code || first == '1'.code || first == '9'.code -> 4.0
            Character.isDigit(first) -> 10.0
            else -> 26.0
        }
        if (d < 0) base *= 2
        return base * cps.size
    }

    private fun keyboardGuesses(cps: IntArray): Double {
        if (cps.size < 4) return Double.POSITIVE_INFINITY
        val s = lower(str(cps))
        for (row in keyboardRows) {
            if (row.contains(s) || reverse(row).contains(s)) return 94 * 4.6 * cps.size
        }
        return Double.POSITIVE_INFINITY
    }

    private fun repeatGuesses(cps: IntArray): Double {
        val n = cps.size
        for (u in 1..n / 2) {
            if (n % u != 0) continue
            var ok = true
            var i = u
            while (i < n && ok) {
                ok = cps[i] == cps[i - u]
                i++
            }
            if (ok) return guesses(str(cps.copyOfRange(0, u))) * (n / u)
        }
        return Double.POSITIVE_INFINITY
    }

    private fun dateGuesses(cps: IntArray): Double {
        if (cps.size < 4 || cps.size > 10) return Double.POSITIVE_INFINITY
        var digits = 0
        for (c in cps) {
            if (Character.isDigit(c)) {
                digits++
            } else if (c != '/'.code && c != '-'.code && c != '.'.code) {
                return Double.POSITIVE_INFINITY
            }
        }
        return when {
            digits < 4 || digits > 8 -> Double.POSITIVE_INFINITY
            digits == 4 -> 119.0
            else -> 365.0 * 119
        }
    }

    private fun bruteforce(cps: IntArray): Double {
        var lo = false
        var up = false
        var dg = false
        var sym = false
        var other = false
        for (c in cps) {
            when {
                c in 'a'.code..'z'.code -> lo = true
                c in 'A'.code..'Z'.code -> up = true
                c in '0'.code..'9'.code -> dg = true
                c < 128 -> sym = true
                else -> other = true
            }
        }
        var card = 0.0
        if (lo) card += 26
        if (up) card += 26
        if (dg) card += 10
        if (sym) card += 33
        if (other) card += 100
        return max(card, BRUTEFORCE_CARD).pow(cps.size)
    }

    private fun matchGuesses(sub: IntArray): Double {
        var g = bruteforce(sub)
        for (m in listOf(dictGuesses(sub), sequenceGuesses(sub), keyboardGuesses(sub), dateGuesses(sub))) g = min(g, m)
        if (sub.size >= 2) g = min(g, repeatGuesses(sub))
        return max(g, if (sub.size == 1) MIN_GUESSES_SINGLE else MIN_GUESSES_MULTI)
    }

    /** How many guesses a well-informed attacker needs for [password] (the vault's model). */
    @Suppress("NestedBlockDepth") // the vault's dynamic programme, line for line
    fun guesses(password: String): Double {
        val cps = password.codePoints().toArray()
        val n = cps.size
        if (n == 0) return 0.0
        if (n > MAX_ESTIMATE_CHARS) return Double.POSITIVE_INFINITY
        val best = Array(n + 1) { DoubleArray(MAX_PARTS + 1) { Double.POSITIVE_INFINITY } }
        best[0][0] = 1.0
        for (j in 1..n) {
            for (i in 0 until j) {
                val g = matchGuesses(cps.copyOfRange(i, j))
                for (k in 1..MAX_PARTS) {
                    val p = best[i][k - 1]
                    if (!p.isInfinite()) best[j][k] = min(best[j][k], p * g)
                }
            }
        }
        var out = Double.POSITIVE_INFINITY
        var fact = 1.0
        for (k in 1..MAX_PARTS) {
            fact *= k
            val g = best[n][k]
            if (!g.isInfinite()) out = min(out, fact * g + 10000.0.pow(k - 1))
        }
        return out
    }
}
