package com.vettid.core.crypto.vote

import org.junit.Assert.assertEquals
import org.junit.Test

/** `pad` (VAULT-MESSAGING 0.83.6 §10.19.5): ASCII `0` x (32 − the UTF-8 byte length of the `choice_id`), empty from 32 bytes on. */
class VotePadTest {
    @Test
    fun padFillsAChoiceIdTo32Bytes() {
        // The §16 `vote.cast` body vector: "yes" has a pad of 29 zeros.
        assertEquals("0".repeat(29), VotePad.of("yes"))
        for (n in 1..32) assertEquals(32, n + VotePad.of("a".repeat(n)).length)
        assertEquals("", VotePad.of("a".repeat(32)))
        // A longer foreign one gets an empty pad.
        assertEquals("", VotePad.of("a".repeat(33)))
    }

    @Test
    fun padCountsUtf8BytesNotCharacters() {
        // "é" is 2 bytes, "€" 3: the decoded UTF-8 bytes, not the characters or the escaped JSON form.
        assertEquals("0".repeat(30), VotePad.of("é"))
        assertEquals("0".repeat(29), VotePad.of("€"))
        assertEquals(VotePad.of("é"), VotePad.of("é".toByteArray(Charsets.UTF_8)))
    }
}
