// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MapAttributionTest {
    @Test
    fun entitiesAreDecodedAndTagsRemoved() {
        val raw = """<a href="https://openfreemap.org" target="_blank">OpenFreeMap</a> <a href="https://www.openmaptiles.org/">&copy; OpenMapTiles</a> Data from <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>"""
        assertEquals("OpenFreeMap © OpenMapTiles Data from OpenStreetMap", MapAttribution.clean(listOf(raw, null, raw)))
    }

    @Test
    fun isCapped() {
        assertTrue(MapAttribution.clean(listOf("x".repeat(1000))).length == MapAttribution.MAX)
    }
}
