// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.map

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** The map reaches the noted server only (https, host and port), with a generic User-Agent and no redirect. */
class MapRequestPolicyTest {
    private val ofm = "tiles.openfreemap.org"

    @Test
    fun onlyHttpsToTheNotedHostAndPortIsAllowed() {
        assertTrue(MapRequestPolicy.allows("https://tiles.openfreemap.org/styles/liberty", ofm))
        assertTrue(MapRequestPolicy.allows("HTTPS://Tiles.OpenFreeMap.org:443/planet/1/2/3.pbf", ofm))
        assertFalse(MapRequestPolicy.allows("http://tiles.openfreemap.org/styles/liberty", ofm))
        assertFalse(MapRequestPolicy.allows("https://tiles.openfreemap.org:8443/x", ofm))
        assertFalse(MapRequestPolicy.allows("https://evil.example/x", ofm))
        assertFalse(MapRequestPolicy.allows("https://tiles.openfreemap.org.evil.example/x", ofm))
        assertFalse(MapRequestPolicy.allows("https://api.mapbox.com/v4/x", ofm))
        for (u in listOf("mapbox://styles/a/b", "maptiler://maps/streets", "file:///data/x", "asset://style.json", "ftp://tiles.openfreemap.org/x", "", "https:///x")) {
            assertFalse(u, MapRequestPolicy.allows(u, ofm))
        }
        assertFalse("nothing is allowed before a server is set", MapRequestPolicy.allows("https://tiles.openfreemap.org/x", null))
    }

    @Test
    fun anExplicitPortIsAnotherServer() {
        assertTrue(MapRequestPolicy.allows("https://maps.example.org:8443/s.json", "maps.example.org:8443"))
        assertFalse(MapRequestPolicy.allows("https://maps.example.org/s.json", "maps.example.org:8443"))
        assertEquals("maps.example.org:8443", MapRequestPolicy.authorityOf("https://Maps.Example.org:8443/s"))
        assertEquals("maps.example.org", MapRequestPolicy.authorityOf("https://maps.example.org:443/s"))
        assertNull(MapRequestPolicy.authorityOf("http://maps.example.org/s"))
    }

    @Test
    fun theTransformLetsTheNotedServerThroughAndRefusesTheRest() {
        assertEquals("https://tiles.openfreemap.org/a", MapRequestPolicy.transform("https://tiles.openfreemap.org/a", ofm))
        for (u in listOf("file:///x", "asset://x", "mapbox://x", "maptiler://x", "https://evil.example/x")) {
            assertEquals(MapRequestPolicy.REFUSED, MapRequestPolicy.transform(u, ofm))
        }
        assertFalse(MapRequestPolicy.allows(MapRequestPolicy.REFUSED, ofm))
    }

    private class Captured(var request: Request? = null)

    /** The app's shared client as far as this test needs it: an interceptor that sets the VettID User-Agent, like `fixedUserAgent`. */
    private val shared = okhttp3.OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", "VettID-Android").build()) })
        .build()

    private fun call(url: String, only: String?, c: Captured, headers: Map<String, String> = emptyMap()): Response {
        val client = MapNetwork.derive(shared) { only }.newBuilder()
            .addInterceptor(
                Interceptor { chain ->
                    c.request = chain.request()
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok").build()
                },
            ).build()
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        return client.newCall(b.build()).execute()
    }

    @Test
    fun theUserAgentIsGenericWhateverTheSdkSet() {
        val c = Captured()
        call("https://tiles.openfreemap.org/a", ofm, c, mapOf("User-Agent" to "com.vettid.app/1.0 MapLibre Android/13.7.1"))
        assertEquals("Mozilla/5.0", c.request!!.header("User-Agent"))
        assertFalse(c.request!!.header("User-Agent")!!.contains("VettID"))
        assertFalse(c.request!!.headers.toString().contains("vettid", ignoreCase = true))
    }

    @Test
    fun aRequestToAnotherHostOrWithoutHttpsNeverGoesOut() {
        for (u in listOf("https://evil.example/a", "http://tiles.openfreemap.org/a")) {
            val c = Captured()
            try {
                call(u, ofm, c)
                fail("$u went out")
            } catch (_: IOException) {
                assertNull(c.request)
            }
        }
    }

    @Test
    fun noRedirectIsFollowedAndThereIsNoCache() {
        val client = MapNetwork.derive(shared) { ofm }
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertNull(client.cache)
    }

    @Test
    fun theDerivedClientKeepsTheSharedOnesInterceptorsButOursRunLastAndWin() {
        val client = MapNetwork.derive(shared) { ofm }
        assertEquals(shared.interceptors.size + 1, client.interceptors.size)
        val c = Captured()
        call("https://tiles.openfreemap.org/a", ofm, c, mapOf("User-Agent" to "x"))
        assertEquals("Mozilla/5.0", c.request!!.header("User-Agent"))
    }

    @Test
    fun queriesWithAwkwardCharactersDoNotChangeTheServer() {
        assertTrue(MapRequestPolicy.allows("https://tiles.openfreemap.org/s?key=a|b&x={z}", ofm))
        assertTrue(MapRequestPolicy.allows("https://tiles.openfreemap.org/planet/{z}/{x}/{y}.pbf?a=|", ofm))
        assertFalse(MapRequestPolicy.allows("https://evil.example/s?host=tiles.openfreemap.org|{x}", ofm))
        assertFalse(MapRequestPolicy.allows("https://evil.example/@tiles.openfreemap.org/s", ofm))
        assertFalse(MapRequestPolicy.allows("https://tiles.openfreemap.org@evil.example/s", ofm))
        val c = Captured()
        call("https://tiles.openfreemap.org/s?key=a|b&x={z}", ofm, c)
        assertEquals("tiles.openfreemap.org", c.request!!.url.host)
    }

    @Test
    fun withNoSharedClientInstalledThereIsNoClient() {
        try {
            MapNetwork.client()
            fail("a client was made without the shared one")
        } catch (_: IllegalStateException) {
            // the set-up turns this into FAILED
        }
    }
}
