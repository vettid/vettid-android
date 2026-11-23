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
    fun unit(bytes: Long): Size = when {
        bytes < K -> Size.Bytes(bytes)
        bytes < K * K -> Size.Kb(number(bytes.toDouble() / K))
        else -> Size.Mb(number(bytes.toDouble() / (K * K)))
    }

    private fun number(x: Double): String =
        if (x < TEN) String.format(Locale.getDefault(), "%.1f", x) else String.format(Locale.getDefault(), "%.0f", x)

    private fun utf8(s: String): Long = s.toByteArray(Charsets.UTF_8).size.toLong()

    private const val TEN = 10.0
}
