package com.vettid.core.crypto

import com.vettid.core.crypto.json.JsonObject
import com.vettid.core.crypto.json.StrictJson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals

/** One vector file from src/test/resources/vectors (copied from vettid-vault, see SOURCE.md). */
class VectorDoc(val o: JsonObject) {
    fun sub(k: String) = VectorDoc(o.obj(k))

    fun str(k: String): String = o.string(k)

    fun num(k: String): Long = o.uint(k, 0, StrictJson.MAX_SAFE_INTEGER)

    fun hex(k: String): ByteArray = Bytes.unhex(str(k))

    fun b64(k: String): ByteArray = Base64s.decodeStd(str(k))

    companion object {
        fun load(name: String): VectorDoc {
            val stream = VectorDoc::class.java.getResourceAsStream("/vectors/$name") ?: error("missing vector $name")
            return VectorDoc(StrictJson.parseObject(stream.use { it.readBytes() }))
        }
    }
}

/** n copies of byte [b]. */
fun rep(b: Int, n: Int): ByteArray = ByteArray(n) { b.toByte() }

/**
 * A deterministic randomness source that hands out [chunks] in order; each
 * draw must request exactly the next chunk's size, so the order of draws is
 * pinned too.
 */
fun chunks(vararg chunks: ByteArray): (Int) -> ByteArray {
    val queue = ArrayDeque(chunks.toList())
    return { n ->
        val next = queue.removeFirstOrNull() ?: error("unexpected random draw of $n bytes")
        assertEquals("random draw size", next.size, n)
        next.copyOf()
    }
}

/** Runs [block] with deterministic randomness (the §16 test hook). */
fun <T> deterministic(vararg c: ByteArray, block: () -> T): T = Randomness.withDeterministic(chunks(*c), block)

fun assertBytes(what: String, want: ByteArray, got: ByteArray) {
    if (!want.contentEquals(got)) {
        assertArrayEquals("$what\n want ${Bytes.hex(want).take(200)}\n got  ${Bytes.hex(got).take(200)}", want, got)
    }
}
