package com.vettid.feature.approvals

import com.vettid.core.data.social.HeldRequest
import java.util.Locale

/** A run's size in the summary line (AA8): the bytes of all of `argv` and of `env`'s names and values, 1,024-based. Pure. */
internal object WaySizes {
    private const val K = 1_024L

    sealed interface Size {
        data class Bytes(val n: Long) : Size

        data class Kb(val text: String) : Size

        data class Mb(val text: String) : Size
    }

    fun runBytes(r: HeldRequest.ItemRun): Long =
        r.argv.sumOf { utf8(it) } + r.env.sumOf { (k, v) -> utf8(k) + utf8(v) }

    /** Bytes below 1 KB; else KB below 1 MB; else MB. One decimal below 10 of the unit, else whole. */
    fun unit(bytes: Long): Size {
        if (bytes < K) return Size.Bytes(bytes)
        val kb = rounded(bytes.toDouble() / K)
        // Judged after rounding, so 1,048,000 bytes is "1.0 MB", not "1024 KB".
        return if (kb < K) Size.Kb(number(kb)) else Size.Mb(number(rounded(bytes.toDouble() / (K * K))))
    }

    /** One decimal below 10, whole from 10 (as shown). */
    private fun rounded(x: Double): Double = if (x < TEN - HALF_TENTH) Math.round(x * TEN) / TEN else Math.round(x).toDouble()

    private fun number(x: Double): String =
        if (x < TEN) String.format(Locale.getDefault(), "%.1f", x) else String.format(Locale.getDefault(), "%.0f", x)

    private const val HALF_TENTH = 0.05

    private fun utf8(s: String): Long = s.toByteArray(Charsets.UTF_8).size.toLong()

    private const val TEN = 10.0
}
