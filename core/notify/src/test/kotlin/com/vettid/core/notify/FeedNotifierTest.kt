// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.notify

import com.vettid.core.data.feed.FeedNames
import com.vettid.core.data.feed.FeedTarget
import com.vettid.core.data.prefs.NotificationMode
import com.vettid.core.data.prefs.NotificationPreviews
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.vault.CredentialAlarm
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.WaitingCounts
import com.vettid.core.vault.FeedItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Feed items and the vault's state as notifications (ANDROID-PLAN 0.1.23, Notification modes 7, 8 and 11). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FeedNotifierTest {
    private val res = RuntimeEnvironment.getApplication().resources

    private class Posted : Poster {
        val shown = linkedMapOf<String, LocalNotification>()
        val log = mutableListOf<String>()

        override fun post(n: LocalNotification) {
            shown[n.tag] = n
            log += "post:${n.tag}"
        }

        override fun cancel(tag: String) {
            if (shown.remove(tag) != null) log += "cancel:$tag"
        }
    }

    private class Source : NotifySource {
        override val arrivals = MutableSharedFlow<FeedItem>(extraBufferCapacity = 16)
        override val items = MutableStateFlow<List<FeedItem>>(emptyList())
        override val open = MutableStateFlow(true)
        override val lockedElsewhere = MutableStateFlow(false)
        override val ownerCheck = MutableStateFlow<OwnerCheckView?>(null)
        override val alarm = MutableStateFlow<CredentialAlarm?>(null)
        var names = FeedNames(mapOf("c1" to ConnectionInfo("c1", "", ConnectionState.ACTIVE, firstName = "Sam", lastName = "Lee")), mapOf("i1" to "Passport"))

        override fun names() = names

        override suspend fun messageText(connectionId: String, messageId: String): String? = "See you at 6"
    }

    private val source = Source()
    private val settings = MutableStateFlow(NotifySettings())
    private val visible = MutableStateFlow<Visible?>(null)
    private val poster = Posted()

    private fun TestScope.notifier() = FeedNotifier(backgroundScope, source, settings, visible, { res }, poster).also {
        it.start()
        runCurrent()
    }

    private fun item(id: String, kind: String = "message.received", status: String = "active", count: Int? = null, ref: String? = "m1") =
        FeedItem(id, 1, kind, "2026-10-09T12:00:00Z", status, connectionId = "c1", ref = ref, count = count)

    private suspend fun TestScope.arrive(i: FeedItem) {
        source.items.value = source.items.value.filterNot { it.itemId == i.itemId } + i
        source.arrivals.emit(i)
        runCurrent()
    }

    @Test
    fun anItemNotifiesOnItsChannelAndGoesWhenReadAnywhere() = runTest {
        notifier()
        arrive(item("f1"))
        val n = poster.shown.getValue("f1")
        assertEquals(Channels.MESSAGES, n.channel)
        assertEquals("New message from Sam Lee", n.title)
        assertNull("Names: never the message text", n.text)
        assertEquals("New activity in VettID", n.publicTitle)
        // Read on another device (a catch-up changed its status).
        source.items.value = listOf(item("f1", status = "read"))
        runCurrent()
        assertTrue(poster.shown.isEmpty())
    }

    @Test
    fun aBatchIsOneNotificationUpdatedInPlace() = runTest {
        notifier()
        arrive(item("b", kind = "grant.request", count = 2, ref = "g1"))
        arrive(item("b", kind = "grant.request", count = 3, ref = "g1"))
        assertEquals(listOf("post:b", "post:b"), poster.log)
        assertEquals("Sam asks for 3 things", poster.shown.getValue("b").title)
        assertTrue(poster.shown.getValue("b").alertOnce)
        assertEquals(Channels.REQUESTS, poster.shown.getValue("b").channel)
    }

    @Test
    fun whatNotificationsShow() = runTest {
        notifier()
        settings.value = NotifySettings(previews = NotificationPreviews.NAMES_AND_TEXT)
        arrive(item("f1"))
        assertEquals("See you at 6", poster.shown.getValue("f1").text)
        arrive(item("f2", kind = "item.revealed", ref = "i1"))
        assertEquals("Passport was revealed", poster.shown.getValue("f2").title)
        settings.value = NotifySettings(previews = NotificationPreviews.NAMES)
        arrive(item("f3", kind = "item.revealed", ref = "i1"))
        assertFalse("Names: no item names", poster.shown.getValue("f3").title.contains("Passport"))
        settings.value = NotifySettings(previews = NotificationPreviews.NOTHING)
        arrive(item("f4"))
        assertEquals("New activity in VettID", poster.shown.getValue("f4").title)
        assertFalse(poster.shown.getValue("f4").names)
        // The security channel's lock-screen text.
        arrive(item("f5", kind = "credential.alarm", ref = "a1").copy(priority = "urgent"))
        assertEquals("VettID needs your attention", poster.shown.getValue("f5").publicTitle)
    }

    @Test
    fun lockHoldAndOffCancelEveryItemNotification() = runTest {
        notifier()
        arrive(item("f1"))
        arrive(item("f2", kind = "connection.added"))
        source.open.value = false // locked or held
        runCurrent()
        assertTrue(poster.shown.isEmpty())
        source.open.value = true
        runCurrent()
        arrive(item("f3"))
        settings.value = NotifySettings(mode = NotificationMode.OFF)
        runCurrent()
        assertTrue(poster.shown.isEmpty())
        arrive(item("f4"))
        assertTrue("Off posts nothing", poster.shown.isEmpty())
    }

    @Test
    fun nothingWhileItsScreenIsInFront() = runTest {
        notifier()
        visible.value = Visible.Notifications
        arrive(item("f1"))
        visible.value = Visible.Target(FeedTarget.Conversation("c1"))
        arrive(item("f2"))
        arrive(item("f3", kind = "connection.added"))
        assertEquals(setOf("f3"), poster.shown.keys)
    }

    @Test
    fun theVaultsStateLockedHeldAndTheAlarm() = runTest {
        notifier()
        source.lockedElsewhere.value = true
        runCurrent()
        assertEquals(Channels.SECURITY, poster.shown.getValue(FeedNotifier.TAG_LOCKED).channel)
        source.lockedElsewhere.value = false
        runCurrent()
        assertNull(poster.shown[FeedNotifier.TAG_LOCKED])
        val held = OwnerCheckView(OwnerCheckState.HELD, null, 86_400, 0, hold = true, holdOffUntil = null, waiting = WaitingCounts(3, 1, 0, 0))
        source.ownerCheck.value = held
        runCurrent()
        val due = poster.shown.getValue(FeedNotifier.TAG_CHECK)
        assertEquals(Channels.OWNER_CHECK, due.channel)
        assertEquals("3 messages and 1 request waiting", due.text)
        source.ownerCheck.value = held.copy(waiting = null)
        runCurrent()
        assertEquals("Something may be waiting", poster.shown.getValue(FeedNotifier.TAG_CHECK).text)
        source.alarm.value = CredentialAlarm("a1", CredentialAlarm.STATE_FROZEN, null)
        runCurrent()
        assertEquals(Channels.SECURITY, poster.shown.getValue(FeedNotifier.TAG_ALARM).channel)
        source.ownerCheck.value = held.copy(state = OwnerCheckState.OK)
        runCurrent()
        assertNull(poster.shown[FeedNotifier.TAG_CHECK])
    }
}
