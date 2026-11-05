// Test code: long literal lines are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.location

import android.app.Application
import org.robolectric.RuntimeEnvironment
import com.vettid.core.data.location.ServiceState
import com.vettid.core.data.location.ShareTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

/** The notification's words (ANDROID-PLAN §4 "Location", Texts (drafts)), word for word. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class SharingTextTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-11-03T12:00:00Z")
    private val until = Instant.parse("2026-11-03T15:30:00Z")

    private fun state(vararg ids: String, paused: Boolean = false, resumed: Boolean = false) =
        ServiceState(ids.map { ServiceState.Target(it, until) }, paused, resumed)

    private fun title(s: ServiceState, names: Map<String, String> = mapOf("c1" to "Sam", "c2" to "Riley")) = SharingText.title(app, s, names, now)

    @Test
    fun oneShareNamesThePersonAndTheTime() {
        val t = title(state("c1"))
        assertTrue(t, t.startsWith("Sharing your location with "))
        assertTrue(t, t.contains("Sam") && t.contains(" until "))
        assertFalse(t.endsWith("."))
    }

    @Test
    fun severalSharesSayHowManyPeople() {
        assertEquals("Sharing your location with 2 people", title(state("c1", "c2")))
        assertEquals("Sharing your location with 3 people", title(state("c1", "c2", "c3")))
    }

    @Test
    fun aResumedShareSaysAgainWithAFullStop() {
        val t = title(state("c1", resumed = true))
        assertTrue(t, t.startsWith("Sharing your location with ") && t.contains(" again, until ") && t.endsWith("."))
        assertTrue(t.contains("Sam"))
    }

    @Test
    fun pausedSaysSo() {
        assertEquals("Paused until your daily check.", title(state("c1", paused = true)))
        assertEquals("Paused until your daily check.", title(state("c1", "c2", paused = true)))
    }

    @Test
    fun whyNothingIsSentIsSaidInPlainWords() {
        fun issue(i: com.vettid.core.data.location.SendIssue) = title(state("c1").copy(issue = i))
        assertEquals("Not sending until your vault has stopped a share.", issue(com.vettid.core.data.location.SendIssue.STOP_PENDING))
        assertEquals("Not sending: location is off for VettID.", issue(com.vettid.core.data.location.SendIssue.NO_PERMISSION))
        assertEquals("Not sending: location is off on this phone.", issue(com.vettid.core.data.location.SendIssue.LOCATION_OFF))
        assertEquals("Not sending: open VettID to carry on.", issue(com.vettid.core.data.location.SendIssue.SERVICE))
        // Paused by the daily check says its own words first.
        assertEquals("Paused until your daily check.", title(state("c1", paused = true).copy(issue = com.vettid.core.data.location.SendIssue.NO_PERMISSION)))
    }

    @Test
    fun withoutAnameNoNameIsSaid() {
        // The app lock's cover is up, or the name is not known yet.
        assertEquals("Sharing your location", title(state("c1"), emptyMap()))
        assertEquals("Sharing your location", title(state("c9")))
        // Several people: a count, never a name.
        assertEquals("Sharing your location with 2 people", title(state("c1", "c2"), emptyMap()))
    }

    @Test
    fun theTimeIsTheTimeOfDayToday() {
        val t = ShareTime.format(app, until, now, ZoneOffset.UTC)
        assertTrue(t, t == "3:30 PM" || t == "15:30" || t.contains("3:30") || t.contains("15:30"))
        assertFalse(t, t.contains(","))
    }

    @Test
    fun anotherDayComesFirst() {
        val t = ShareTime.format(app, until.plusSeconds(3 * 86_400), now, ZoneOffset.UTC)
        assertTrue(t, t.contains(","))
        assertTrue(t, t.contains("Nov"))
    }

    @Test
    fun anotherDayFollowsTheRegionsOrder() {
        // One locale skeleton for date and time (owner decision 2026-11-05): month first in the US, day first in the UK.
        fun inLocale(tag: String): android.content.Context {
            val c = android.content.res.Configuration(app.resources.configuration)
            c.setLocale(java.util.Locale.forLanguageTag(tag))
            return app.createConfigurationContext(c)
        }
        val later = until.plusSeconds(3 * 86_400)
        val us = ShareTime.format(inLocale("en-US"), later, now, ZoneOffset.UTC)
        assertTrue(us, us.startsWith("Nov"))
        val uk = ShareTime.format(inLocale("en-GB"), later, now, ZoneOffset.UTC)
        assertTrue(uk, uk.indexOf("Nov") > 0 && uk.first().isDigit())
    }
}
