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

    @Test
    fun theDotIsDrawnOnlyForAnExactShare() {
        val exact = MapContent("https://x.example/s", MapPoint(48.5, 2.25), 10.0, showPoint = true)
        val area = MapContent("https://x.example/s", MapPoint(48.5, 2.25), 1000.0, showPoint = false)
        assertTrue(MapShapes.pointGeoJson(exact).contains("Point"))
        assertEquals(MapShapes.EMPTY, MapShapes.pointGeoJson(area))
        assertTrue("the circle stays", MapShapes.circleGeoJson(area).contains("Polygon"))
        val noDot = MapContent("https://x.example/s", MapPoint(48.5, 2.25), 10.0, showPoint = false)
        assertTrue("a different dot is a different picture", !exact.sameDrawing(noDot))
    }

    @Test
    fun noTrailIsAnEmptyCollection() {
        val c = MapContent("https://x.example/s", MapPoint(48.5, 2.25), 10.0, listOf(MapPoint(1.0, 2.0)))
        assertEquals(MapShapes.EMPTY, MapShapes.trailGeoJson(c))
        val two = MapContent("https://x.example/s", MapPoint(48.5, 2.25), 10.0, listOf(MapPoint(1.0, 2.0), MapPoint(1.5, 2.5)))
        assertTrue(MapShapes.trailGeoJson(two).contains("LineString"))
    }
}
