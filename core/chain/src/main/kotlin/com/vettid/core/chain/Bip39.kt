package com.vettid.core.chain

import fr.acinq.bitcoin.MnemonicCode
import java.util.Locale

/**
 * BIP39's English word list and checksum for the Import flow (ANDROID-PLAN §4 "Wallet", screens table: "each word is checked against
 * the BIP39 English list as typed, and the checksum before anything is sent"), on bitcoin-kmp's [MnemonicCode] behind this module's
 * boundary. No network, no file of the app's own: the list is the library's. Words are compared in lower case (the vault accepts any
 * case). The word list lookups make `String`s of what is typed; they are short-lived and hold no more than the typing did.
 */
object Bip39 {
    /** The phrase lengths BIP39 has and the vault accepts. */
    val WORD_COUNTS: Set<Int> = setOf(12, 15, 18, 21, 24)

    private val words: List<String> = MnemonicCode.englishWordlist
    private val wordSet: Set<String> = words.toHashSet()

    /** What [check] found in a phrase. */
    sealed interface Result {
        data object Valid : Result

        /** Not 12, 15, 18, 21 or 24 words ([count] were given). */
        data class WrongCount(val count: Int) : Result

        /** The word at [index] (0-based) is not in the list. */
        data class UnknownWord(val index: Int) : Result

        /** Every word is in the list but the checksum does not fit: a word is wrong or the order is. */
        data object BadChecksum : Result
    }

    /** Whether [word] is a word of the list, in any case. */
    fun isWord(word: CharSequence): Boolean = word.toString().lowercase(Locale.ROOT) in wordSet

    /** Whether [prefix] begins at least one word of the list (a word still being typed), in any case. */
    fun isPrefix(prefix: CharSequence): Boolean {
        val p = prefix.toString().lowercase(Locale.ROOT)
        if (p.isEmpty()) return true
        var lo = 0
        var hi = words.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid] < p) lo = mid + 1 else hi = mid
        }
        return lo < words.size && words[lo].startsWith(p)
    }

    /** The phrase [phrase] (words in order): its length, each word, then the checksum, in that order. */
    fun check(phrase: List<CharSequence>): Result {
        if (phrase.size !in WORD_COUNTS) return Result.WrongCount(phrase.size)
        val lower = phrase.map { it.toString().lowercase(Locale.ROOT) }
        val unknown = lower.indexOfFirst { it !in wordSet }
        if (unknown >= 0) return Result.UnknownWord(unknown)
        return try {
            MnemonicCode.validate(lower)
            Result.Valid
        } catch (_: IllegalArgumentException) {
            Result.BadChecksum
        }
    }
}
