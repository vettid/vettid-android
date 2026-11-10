// Test data: the approved sentences are clearer on one line.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.feed

import com.vettid.core.data.social.ConnectionState
import com.vettid.core.testing.FakeSocial
import com.vettid.core.vault.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * (GC #7478; VAULT-MESSAGING 0.88.7 §10.9) `connection.active_again`: "Your connection with <name> works again." (owner-approved
 * 2026-11-10, GC #7229), the name as `connection.stale`'s, the connection's page as target.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeedActiveAgainTextTest {
    private val res = RuntimeEnvironment.getApplication().resources

    private fun item(conn: String?) = FeedItem("i", 1, "connection.active_again", "2026-11-10T12:00:00Z", "active", connectionId = conn, ref = conn)

    private fun title(conn: String?, names: FeedNames) = FeedKinds.title(item(conn), names).resolve(res)

    @Test
    fun theOwnersWordsWordForWord() {
        assertEquals("Your connection with %1\$s works again.", res.getString(com.vettid.core.data.R.string.data_feed_connection_active_again))
    }

    @Test
    fun theNameAsForAStaleConnection() {
        val names = FeedNames(connections = mapOf("c1" to FakeSocial.connection("c1", "Sam", last = "Lee")))
        assertEquals("Your connection with ⁨Sam Lee⁩ works again.", title("c1", names))
        assertEquals("a removed connection", "Your connection with Removed connection works again.", title("c9", names))
        assertEquals("no connection", "Your connection with Someone works again.", title(null, names))
        // The same fallbacks as connection.stale.
        for (c in listOf("c1", "c9", null)) {
            val stale = FeedKinds.title(item(c).copy(kind = "connection.stale"), names)
            val again = FeedKinds.title(item(c), names)
            assertEquals("$c", stale.args, again.args)
        }
    }

    @Test
    fun aKnownKindThatOpensTheConnection() {
        assertTrue("connection.active_again" in FeedKinds.KNOWN)
        assertEquals(FeedTarget.Connection("c1"), FeedKinds.target(item("c1")))
        assertEquals(FeedTarget.Sheet, FeedKinds.target(item(null)))
        assertEquals(ConnectionState.ACTIVE, FakeSocial.connection("c1", "Sam").state)
    }
}
