package com.vettid.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapShapesTest {
    @Test
    fun circleIsTheLargerOfAccuracyAndNominalArea() {
        assertEquals(30.0, MapCircle.radiusM(30.0, 0.0), 0.0)
        assertEquals(1000.0, MapCircle.radiusM(30.0, 1000.0), 0.0)
        assertEquals(1000.0, MapCircle.radiusM(null, 1000.0), 0.0)
        assertEquals(5000.0, MapCircle.radiusM(5000.0, 1000.0), 0.0)
        assertEquals(0.0, MapCircle.radiusM(null, 0.0), 0.0)
        assertEquals(11000.0, MapCircle.radiusM(Double.NaN, 11000.0), 0.0)
    }

    @Test
    fun ringIsClosedAndAboutTheRadius() {
        val ring = MapShapes.ring(48.0, 2.0, 1000.0)
        assertEquals(65, ring.size)
        assertEquals(ring.first().lat, ring.last().lat, 0.0)
        val north = ring.maxOf { it.lat } - 48.0
        assertEquals(1000.0, north * 111_320.0, 5.0)
    }

    @Test
    fun trailNeedsTwoPoints() {
        assertTrue(MapCircle.trailOf(emptyList()).isEmpty())
        assertTrue(MapCircle.trailOf(listOf(MapPoint(1.0, 2.0))).isEmpty())
        assertEquals(2, MapCircle.trailOf(listOf(MapPoint(1.0, 2.0), MapPoint(1.1, 2.1))).size)
    }

    @Test
    fun geoJsonUsesLonLatInAFixedLocale() {
        val c = MapContent("https://x.example/s", MapPoint(48.5, 2.25), 10.0)
        assertTrue(MapShapes.pointGeoJson(c).contains("[2.2500000,48.5000000]"))
    }

    @Test
    fun contentsPrintTheirTypeNameOnly() {
        val c = MapContent("https://x.example/s", MapPoint(48.5, 2.25), 10.0)
        assertEquals("MapContent", c.toString())
        assertEquals("MapPoint", c.position.toString())
    }
}
