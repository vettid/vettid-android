package com.vettid.feature.history

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vettid.core.data.R as DataR
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AA6: the feed's detail sheet for `leash.item.run` and `leash.item.proxy` shows History's approved detail lines. The feed
 * keeps its own copies in `:core:data` (it cannot see this module); both copies are held word for word here, and "Open the item".
 */
@RunWith(RobolectricTestRunner::class)
class LeashItemDetailTextTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun feed(res: Int) = context.getString(res)

    @Test
    fun theFeedCopiesAreHistorysTextsWordForWord() {
        assertEquals(
            "The secret went to your VettID connector for one command on your computer. The agent was not given it.",
            context.getString(R.string.history_detail_leash_item_run),
        )
        assertEquals(
            "The secret went to your VettID connector, which added it to the agent's requests to a site you allowed. " +
                "The agent was not given it.",
            context.getString(R.string.history_detail_leash_item_proxy),
        )
        assertEquals(context.getString(R.string.history_detail_leash_item_run), feed(DataR.string.data_feed_leash_item_run_detail))
        assertEquals(context.getString(R.string.history_detail_leash_item_proxy), feed(DataR.string.data_feed_leash_item_proxy_detail))
        assertEquals(context.getString(R.string.history_detail_open_item), "Open the item")
        // Part 2: the read line, in both places.
        assertEquals(
            "The agent was given the item's values. Its app may keep them in its history.",
            context.getString(R.string.history_detail_leash_item_read),
        )
        assertEquals(
            context.getString(R.string.history_detail_leash_item_read),
            feed(DataR.string.data_feed_leash_item_read_detail),
        )
    }
}
