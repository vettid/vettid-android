package com.vettid.app.calls

import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The name a call shows (CallsModule): account names, else the display name, each cleaned (VAULT-MESSAGING §10.8). */
@RunWith(RobolectricTestRunner::class)
class CallNameTest {
    private fun c(name: String, first: String? = null, last: String? = null) =
        ConnectionInfo("c1", name, ConnectionState.ACTIVE, firstName = first, lastName = last)

    @Test
    fun theAccountNameComesFirstThenTheDisplayName() {
        assertEquals("Ada Lovelace", callName(c("Countess", "Ada", "Lovelace")))
        assertEquals("Countess", callName(c("Countess")))
        assertNull(callName(c("")))
    }

    @Test
    fun anAccountNameThatShowsNothingFallsBackToTheDisplayName() {
        for (blank in listOf("\u2800", "\u3164")) {
            assertEquals(blank, "Countess", callName(c("Countess", blank, blank)))
        }
    }

    @Test
    fun aDisplayNameThatShowsNothingIsNoName() {
        for (blank in listOf("\u2800", "\u3164", " \u2800 ")) assertNull(blank, callName(c(blank)))
    }
}
