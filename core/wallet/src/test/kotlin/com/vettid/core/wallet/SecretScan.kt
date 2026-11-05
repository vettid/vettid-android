// Test code: long literal lines are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.wallet

import java.io.File
import java.util.Base64

/**
 * The wallet's data directory scan (ANDROID-PLAN §4 "Wallet", "Tests"): looks in every file under a directory (and in every
 * file's path) for secrets in the forms they could take on disk: UTF-8, UTF-16 (either order), hex (either case), base64 and
 * URL-safe base64 (at the three alignments, so a secret in the middle of a larger value is found too), and raw bytes.
 */
internal object SecretScan {
    class Needle(val secret: String, val form: String, val bytes: ByteArray)

    private const val HEX_DIGITS = "0123456789abcdef"

    private fun hex(b: ByteArray) = buildString { b.forEach { append(HEX_DIGITS[(it.toInt() shr 4) and 15]).append(HEX_DIGITS[it.toInt() and 15]) } }

    /** The forms of a text secret. */
    fun forms(name: String, text: String): List<Needle> {
        val utf8 = text.encodeToByteArray()
        val out = mutableListOf(
            Needle(name, "utf-8", utf8),
            Needle(name, "utf-16le", text.toByteArray(Charsets.UTF_16LE)),
            Needle(name, "utf-16be", text.toByteArray(Charsets.UTF_16BE)),
            Needle(name, "hex", hex(utf8).encodeToByteArray()),
            Needle(name, "HEX", hex(utf8).uppercase().encodeToByteArray()),
        )
        out += base64Forms(name, utf8)
        return out
    }

    /** The forms of a binary secret (a raw transaction, a seed): the bytes, their hex and base64. */
    fun forms(name: String, bytes: ByteArray): List<Needle> =
        listOf(Needle(name, "raw", bytes), Needle(name, "hex", hex(bytes).encodeToByteArray()), Needle(name, "HEX", hex(bytes).uppercase().encodeToByteArray())) +
            base64Forms(name, bytes)

    private fun base64Forms(name: String, bytes: ByteArray): List<Needle> = listOf(
        "base64" to Base64.getEncoder(),
        "base64url" to Base64.getUrlEncoder(),
    ).flatMap { (form, enc) ->
        (0..2).mapNotNull { shift ->
            // `shift` bytes before the secret: the characters that hold only the secret are the same whatever came before.
            val e = enc.withoutPadding().encodeToString(ByteArray(shift) + bytes)
            val from = (shift * 8 + 5) / 6
            val to = e.length - 2
            if (to - from < 4) null else Needle(name, "$form+$shift", e.substring(from, to).encodeToByteArray())
        }
    }

    /** Each word (of four letters or more) and the phrase, in every form. */
    fun phrase(name: String, words: List<String>): List<Needle> =
        forms("$name (phrase)", words.joinToString(" ")) + words.filter { it.length >= 4 }.distinct().flatMap { forms("$name (word $it)", it) }

    /** What was found: file, secret and form. */
    fun find(root: File, needles: List<Needle>): List<String> = root.walkTopDown().filter { it.isFile }.flatMap { f ->
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return@flatMap emptySequence()
        val path = f.path.encodeToByteArray()
        needles.asSequence().filter { indexOf(bytes, it.bytes) >= 0 || indexOf(path, it.bytes) >= 0 }.map { "${f.path}: ${it.secret} as ${it.form}" }
    }.toList()

    /** Whether [bytes] hold any of [needles]. */
    fun contains(bytes: ByteArray, needles: List<Needle>): Boolean = needles.any { indexOf(bytes, it.bytes) >= 0 }

    fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
