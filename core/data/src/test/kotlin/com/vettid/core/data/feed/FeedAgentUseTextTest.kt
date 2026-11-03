package com.vettid.core.data.feed

import com.vettid.core.vault.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** AA6 (texts approved by the owner 2026-11-03): the feed's agent-use titles and detail lines as rendered text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeedAgentUseTextTest {
    private val res = RuntimeEnvironment.getApplication().resources
    private val names = FeedNames(items = mapOf("01ITEM" to "GitHub token"))

    private fun item(kind: String, ref: String?) = FeedItem("i", 1, kind, "2026-11-03T12:00:00Z", "active", "normal", ref = ref)

    private fun title(kind: String, ref: String?, details: Boolean = true) =
        FeedKinds.title(item(kind, ref), names, details).resolve(res)

    @Test
    fun titlesReadAsApproved() {
        assertEquals("An agent read ⁨GitHub token⁩", title("leash.item.read", "01ITEM"))
        assertEquals("An agent read an item", title("leash.item.read", "01GONE"))
        assertEquals("An agent read an item", title("leash.item.read", null))
        assertEquals("An agent read an item", title("leash.item.read", "01ITEM", details = false))
        for (k in listOf("leash.item.run", "leash.item.proxy")) {
            assertEquals("An agent used ⁨GitHub token⁩", title(k, "01ITEM"))
            assertEquals("An agent used an item", title(k, "01GONE"))
            assertEquals("An agent used an item", title(k, "01ITEM", details = false))
        }
    }

    @Test
    fun detailLinesReadAsApproved() {
        fun detail(kind: String) = FeedKinds.sheetDetail(item(kind, "01ITEM"))?.resolve(res)
        assertEquals(
            "The secret went to your VettID connector for one command on your computer. The agent was not given it.",
            detail("leash.item.run"),
        )
        assertEquals(
            "The secret went to your VettID connector, which added it to the agent's requests to a site you allowed. " +
                "The agent was not given it.",
            detail("leash.item.proxy"),
        )
        assertEquals("The agent was given the item's values. Its app may keep them in its history.", detail("leash.item.read"))
    }
}
