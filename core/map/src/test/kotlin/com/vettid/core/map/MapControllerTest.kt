package com.vettid.core.map

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every content change reaches the map; the camera frames a new share and follows only while the member has not moved it. */
class MapControllerTest {
    private class FakeDrawer : MapDrawer {
        val log = mutableListOf<String>()
        val drawn = mutableListOf<MapContent>()
        var controller: MapController? = null

        override fun loadStyle(url: String) {
            log += "style $url"
        }

        override fun draw(content: MapContent) {
            drawn += content
            log += "draw"
        }

        override fun frame(content: MapContent) {
            log += "frame"
        }
    }

    private val d = FakeDrawer()
    private val c = MapController(d)

    private fun content(lat: Double = 52.5, key: String = "s1", style: String = "https://a.example/s", trail: Int = 0) =
        MapContent(style, MapPoint(lat, 13.4), 100.0, (0 until trail).map { MapPoint(52.0 + it, 13.0) }, key)

    @Test
    fun nothingIsDrawnBeforeTheStyleHasLoadedThenTheLatestContentIs() {
        c.onContent(content(52.5))
        c.onContent(content(52.6))
        assertEquals(listOf("style https://a.example/s"), d.log)
        c.onStyleLoaded()
        assertEquals(1, d.drawn.size)
        assertEquals(52.6, d.drawn[0].position.lat, 0.0)
        assertEquals(listOf("style https://a.example/s", "draw", "frame"), d.log)
    }

    @Test
    fun everyNewPositionAndTrailChangeReachesTheMap() {
        c.onContent(content(52.5))
        c.onStyleLoaded()
        c.onContent(content(52.6))
        c.onContent(content(52.7, trail = 2))
        assertEquals(listOf(52.5, 52.6, 52.7), d.drawn.map { it.position.lat })
        // The same picture again draws nothing.
        c.onContent(content(52.7, trail = 2))
        assertEquals(3, d.drawn.size)
    }

    @Test
    fun theCameraFollowsOnlyUntilTheMemberMovesIt() {
        c.onContent(content(52.5))
        c.onStyleLoaded()
        c.onContent(content(52.6))
        assertEquals(2, d.log.count { it == "frame" })
        c.onUserMoved()
        c.onContent(content(52.7))
        assertEquals("their camera is kept", 2, d.log.count { it == "frame" })
        assertEquals("but the dot is still drawn", 3, d.drawn.size)
        // Another share frames again.
        c.onContent(content(10.0, key = "s2"))
        assertEquals(3, d.log.count { it == "frame" })
        c.onContent(content(10.1, key = "s2"))
        assertEquals(4, d.log.count { it == "frame" })
    }

    @Test
    fun aNewStyleIsLoadedAndDrawnAgain() {
        c.onContent(content())
        c.onStyleLoaded()
        c.onContent(content(style = "https://b.example/s"))
        assertEquals("style https://b.example/s", d.log.last())
        c.onStyleLoaded()
        assertEquals(2, d.drawn.size)
    }

    @Test
    fun whatWasAskedBeforeTheMapExistedIsLoadedWhenItDoes() {
        c.onContent(content())
        c.onMapReady()
        assertEquals(2, d.log.count { it.startsWith("style") })
    }
}
