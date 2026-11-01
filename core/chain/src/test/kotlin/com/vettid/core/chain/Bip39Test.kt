package com.vettid.core.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BIP39 word and checksum checks (ANDROID-PLAN §4 "Tests"), on the published BIP39 test phrases (public test data). */
class Bip39Test {
    private fun words(s: String) = s.split(" ")

    private val twelve = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val twentyFour =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"

    @Test
    fun thePublishedTestPhrasesAreValid() {
        assertEquals(Bip39.Result.Valid, Bip39.check(words(twelve)))
        assertEquals(Bip39.Result.Valid, Bip39.check(words(twentyFour)))
        // The vault takes any case.
        assertEquals(Bip39.Result.Valid, Bip39.check(words(twelve.uppercase())))
        // Another published vector (the BIP39 test vectors, 128-bit entropy of 0x7f repeated).
        assertEquals(
            Bip39.Result.Valid,
            Bip39.check(words("legal winner thank year wave sausage worth useful legal winner thank yellow")),
        )
    }

    @Test
    fun onlyTheFiveLengthsAreAccepted() {
        for (n in listOf(0, 1, 11, 13, 14, 16, 23, 25)) {
            assertEquals(Bip39.Result.WrongCount(n), Bip39.check(List(n) { "abandon" }))
        }
        for (n in Bip39.WORD_COUNTS) assertTrue(Bip39.check(List(n) { "abandon" }) !is Bip39.Result.WrongCount)
    }

    @Test
    fun anUnknownWordIsNamedByPosition() {
        val bad = words(twelve).toMutableList().also { it[4] = "abandin" }
        assertEquals(Bip39.Result.UnknownWord(4), Bip39.check(bad))
        // The first unknown word is the one named.
        bad[2] = "zzzz"
        assertEquals(Bip39.Result.UnknownWord(2), Bip39.check(bad))
    }

    @Test
    fun aWrongChecksumOrOrderIsCaughtAfterTheWords() {
        // "about" is the checksum word: another real word in its place breaks the checksum.
        val badSum = words(twelve).toMutableList().also { it[11] = "abandon" }
        assertEquals(Bip39.Result.BadChecksum, Bip39.check(badSum))
        val swapped = words("legal winner thank year wave sausage worth useful legal winner yellow thank")
        assertEquals(Bip39.Result.BadChecksum, Bip39.check(swapped))
    }

    @Test
    fun wordsAndPrefixesAreCheckedAsTyped() {
        assertTrue(Bip39.isWord("abandon"))
        assertTrue(Bip39.isWord("ZOO"))
        assertFalse(Bip39.isWord("aband"))
        assertFalse(Bip39.isWord(""))
        assertTrue(Bip39.isPrefix(""))
        assertTrue(Bip39.isPrefix("aban"))
        assertTrue(Bip39.isPrefix("ZO"))
        assertTrue(Bip39.isPrefix("zoo"))
        assertFalse(Bip39.isPrefix("abandonx"))
        assertFalse(Bip39.isPrefix("xq"))
    }
}
