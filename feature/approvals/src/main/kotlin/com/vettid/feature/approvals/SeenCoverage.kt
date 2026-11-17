package com.vettid.feature.approvals

import kotlin.math.abs

/**
 * How much of one row of a document has been on screen, from its top down without a gap (VAULT-MESSAGING 0.89.0 §10.4:
 * "Approve is enabled only once the whole of it has been on screen"). As vettid-ios #300's `SeenCoverage`: every part
 * that comes on screen is kept, in any order, and the coverage is what they join into from the top, so a jump past the
 * middle leaves a gap until the middle has been on screen too. A change of the row's height (another text size, another
 * width) starts it again. Immutable: [saw] returns the new coverage.
 */
data class SeenCoverage(
    /** The row's height the coverage is for, in pixels. */
    val height: Float = 0f,
    /** The parts seen, merged, in order. */
    val seen: List<ClosedFloatingPointRange<Float>> = emptyList(),
) {
    /** Seen from the top down to here, without a gap. */
    val seenTo: Float
        get() {
            val first = seen.firstOrNull() ?: return 0f
            return if (first.start <= SLACK) first.endInclusive else 0f
        }

    /** Whether the whole row has been on screen. */
    val whole: Boolean get() = height > 0f && seenTo >= height - SLACK

    /** The part of the row on screen is [visible] (its own coordinates; null when none) of a row [h] tall. */
    fun saw(visible: ClosedFloatingPointRange<Float>?, h: Float): SeenCoverage {
        val base = if (abs(h - height) > SLACK) SeenCoverage(h) else this
        if (visible == null) return base
        val top = maxOf(visible.start, 0f)
        val part = top..maxOf(minOf(visible.endInclusive, base.height), top)
        val merged = mutableListOf<ClosedFloatingPointRange<Float>>()
        for (r in (base.seen + listOf(part)).sortedBy { it.start }) {
            val last = merged.lastOrNull()
            if (last != null && r.start <= last.endInclusive + SLACK) {
                merged[merged.lastIndex] = last.start..maxOf(last.endInclusive, r.endInclusive)
            } else {
                merged += r
            }
        }
        return base.copy(seen = merged)
    }

    companion object {
        /** How far apart two parts may be and still join (rounding, one pixel). */
        const val SLACK = 1f
    }
}
