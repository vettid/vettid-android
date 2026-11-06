package com.vettid.core.data.feed

import com.vettid.core.vault.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `identity.rotation_failed` (VAULT-MESSAGING 0.84.0 §3.4; owner decision a, 2026-11-05; approved by the owner 2026-11-06,
 * GC thread 01M6RZBVG54W7FXPVHKHF2GVGE, with vettid-ios #221): the title is unchanged, the supporting text no longer says the
 * connections still know the old keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeedRotationFailedTextTest {
    private val res = RuntimeEnvironment.getApplication().resources
    private val item = FeedItem("i", 1, "identity.rotation_failed", "2026-11-05T12:00:00Z", "active", "high", ref = "recovery")

    @Test
    fun theTitleIsUnchanged() {
        assertEquals("Your vault could not update its keys", FeedKinds.title(item, FeedNames(), true).resolve(res))
    }

    @Test
    fun theSupportingTextIsTheCorrectedOne() {
        assertEquals(
            "Your vault couldn't finish updating its keys. It will try again the next time you unlock it.",
            FeedKinds.supporting(item, FeedNames())?.resolve(res),
        )
    }
}
