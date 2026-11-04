// Test code: long literal lines are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.location

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Looks for the coordinates of a test route in every file under a directory, in the forms a position takes on disk: as text
 * (the number as `Double.toString` writes it and cut at 5 and 6 decimals), as binary doubles and floats in either byte order, and
 * as fixed-point integers of 1e6 and 1e7 in either order. Used by the data directory scan (ANDROID-PLAN §4 "Tests").
 */
object RouteScan {
    private fun forms(v: Double): List<ByteArray> {
        val text = listOf(v.toString(), "%.6f".format(java.util.Locale.ROOT, v), "%.5f".format(java.util.Locale.ROOT, v))
            .map { it.encodeToByteArray() }
        val doubles = listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN).map { ByteBuffer.allocate(8).order(it).putDouble(v).array() }
        val floats = listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN).map { ByteBuffer.allocate(4).order(it).putFloat(v.toFloat()).array() }
        val fixed = listOf(1e6, 1e7).flatMap { scale ->
            listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN).map { ByteBuffer.allocate(4).order(it).putInt((v * scale).toInt()).array() }
        }
        return text + doubles + floats + fixed
    }

    /** The files under [root] (recursively) that contain any form of any coordinate of [route], with the form's name. */
    fun find(root: File, route: List<Pair<Double, Double>>): List<String> {
        val needles = route.flatMap { listOf(it.first, it.second) }.flatMap { forms(it) }
        return root.walkTopDown().filter { it.isFile }.mapNotNull { f ->
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: return@mapNotNull null
            f.path.takeIf { needles.any { n -> indexOf(bytes, n) >= 0 } }
        }.toList()
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
