package com.vettid.core.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The speaker tap kept until the platform has it (#18637). */
class RouteRequestTest {
    private var now = 0L
    private val r = RouteRequest({ now }, holdMs = 5_000)
    private val all = listOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER)

    @Test
    fun nothingAskedShowsThePlatformsRoute() {
        assertEquals(AudioRoute.EARPIECE, r.shown(AudioRoute.EARPIECE))
        assertNull(r.again(AudioRoute.EARPIECE, all))
        assertFalse(r.pending)
    }

    /** Shown at once, before the platform has switched, and asked for again while the platform reports another. */
    @Test
    fun anEarlyTapIsShownAtOnceAndAskedForAgain() {
        r.ask(AudioRoute.SPEAKER)
        assertEquals("shown before the platform switched", AudioRoute.SPEAKER, r.shown(AudioRoute.EARPIECE))
        assertEquals("asked for again", AudioRoute.SPEAKER, r.again(AudioRoute.EARPIECE, all))
        assertNull("in place: nothing to ask", r.again(AudioRoute.SPEAKER, all))
        // The platform turns back to the earpiece when the call's audio starts: asked for again.
        now = 2_000
        assertEquals(AudioRoute.SPEAKER, r.again(AudioRoute.EARPIECE, all))
        assertTrue(r.pending)
    }

    /** After the hold the platform's own route is shown again: a refused request is seen to go. */
    @Test
    fun theHoldEnds() {
        r.ask(AudioRoute.SPEAKER)
        now = 4_999
        assertEquals(AudioRoute.SPEAKER, r.shown(AudioRoute.EARPIECE))
        now = 5_000
        assertEquals(AudioRoute.EARPIECE, r.shown(AudioRoute.EARPIECE))
        assertNull(r.again(AudioRoute.EARPIECE, all))
        assertFalse(r.pending)
    }

    /** The platform keeps turning the route back: asked for again a few times, then left to it until the hold ends. */
    @Test
    fun askedForAgainAFewTimesOnly() {
        r.ask(AudioRoute.SPEAKER)
        val asks = (1..10).count { r.again(AudioRoute.EARPIECE, all) != null }
        assertEquals(1 + RouteRequest.MAX_AGAIN, asks)
        assertEquals("still shown until the hold ends", AudioRoute.SPEAKER, r.shown(AudioRoute.EARPIECE))
        r.ask(AudioRoute.SPEAKER)
        assertEquals("a new tap asks again", AudioRoute.SPEAKER, r.again(AudioRoute.EARPIECE, all))
    }

    /** A route the phone no longer has (a headset gone) is dropped; a new tap replaces the old one, and its hold. */
    @Test
    fun aGoneRouteIsDroppedAndANewTapReplaces() {
        r.ask(AudioRoute.BLUETOOTH)
        assertNull(r.again(AudioRoute.EARPIECE, all))
        assertEquals(AudioRoute.EARPIECE, r.shown(AudioRoute.EARPIECE))
        r.ask(AudioRoute.SPEAKER)
        now = 4_000
        r.ask(AudioRoute.EARPIECE)
        now = 8_000
        assertEquals("the new tap's own hold", AudioRoute.EARPIECE, r.shown(AudioRoute.SPEAKER))
        r.clear()
        assertEquals(AudioRoute.SPEAKER, r.shown(AudioRoute.SPEAKER))
    }
}

/** A headset is new by its own id among the devices the callback reported (#18637). */
class HeadsetWatchTest {
    @Test
    fun aHeadsetIsNewByItsId() {
        val w = HeadsetWatch()
        w.seed(listOf(1, 2)) // the earpiece and the speaker, there at registration
        assertFalse("the callback's first call: what is there", w.added(listOf(1 to AudioRoute.EARPIECE, 2 to AudioRoute.SPEAKER)))
        assertTrue("a wired headset plugged in", w.added(listOf(3 to AudioRoute.WIRED)))
        assertFalse("reported again", w.added(listOf(3 to AudioRoute.WIRED)))
        assertTrue("a second headset of the same kind is new too", w.added(listOf(4 to AudioRoute.BLUETOOTH)))
        assertFalse("a new device that is not a headset", w.added(listOf(5 to AudioRoute.SPEAKER, 6 to null)))
        w.removed(listOf(3))
        assertTrue("unplugged, then plugged in again", w.added(listOf(3 to AudioRoute.WIRED)))
        val atStart = HeadsetWatch()
        atStart.seed(listOf(7))
        assertFalse("a headset there when the callback was registered", atStart.added(listOf(7 to AudioRoute.WIRED)))
    }
}
