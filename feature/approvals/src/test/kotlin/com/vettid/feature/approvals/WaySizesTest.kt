package com.vettid.feature.approvals

import org.junit.Assert.assertEquals
import org.junit.Test

/** AA8: a run's size in its summary, 1,024-based. */
class WaySizesTest {
    @Test
    fun bytesKbAndMb() {
        assertEquals(WaySizes.Size.Bytes(1_023), WaySizes.unit(1_023))
        assertEquals(WaySizes.Size.Kb("1.0"), WaySizes.unit(1_024))
        assertEquals(WaySizes.Size.Kb("32"), WaySizes.unit(32_768))
        assertEquals(WaySizes.Size.Mb("1.5"), WaySizes.unit(1_572_864))
    }

    @Test
    fun theBytesOfArgvAndEnvNamesAndValues() {
        val r = heldRun("i1").copy(argv = listOf("git", "é"), env = listOf("A" to "bc"))
        assertEquals(3L + 2 + 1 + 2, WaySizes.runBytes(r))
    }
}
