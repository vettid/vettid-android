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
        override val idleLocked = MutableStateFlow<com.vettid.core.data.vault.IdleLocked?>(null)
        override val ownerCheck = MutableStateFlow<OwnerCheckView?>(null)
        override val alarm = MutableStateFlow<CredentialAlarm?>(null)
        var names = FeedNames(mapOf("c1" to ConnectionInfo("c1", "", ConnectionState.ACTIVE, firstName = "Sam", lastName = "Lee")), mapOf("i1" to "Passport"))

        override fun names() = names

        /** When set, [messageText] waits for it: an item notification still being prepared. */
        var textGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun messageText(connectionId: String, messageId: String): String? {
            textGate?.await()
            return "See you at 6"
        }
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
        assertEquals("New message from \u2068Sam Lee\u2069", n.title)
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
        assertEquals("\u2068Sam\u2069 asks for 3 things", poster.shown.getValue("b").title)
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
        assertEquals("\u2068Passport\u2069 was revealed", poster.shown.getValue("f2").title)
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

    /**
     * Google push (ANDROID-PLAN 0.1.30, Notification modes 4): a wake only makes the app collect; what is then posted
     * comes from this one notifier, so it is what the on-phone service mode posts for the same events: the same
     * channels, texts and lock-screen texts, nothing of a locked or held vault, "Your vault is locked" and "Daily
     * check due" with counts only.
     */
    @Test
    fun googlePushPostsWhatTheOnPhoneServicePosts() {
        fun scenario(mode: NotificationMode): Pair<List<String>, List<LocalNotification>> {
            val src = Source()
            val out = Posted()
            val seen = mutableListOf<LocalNotification>()
            runTest {
                val settings = MutableStateFlow(NotifySettings(mode, NotificationPreviews.NAMES_AND_TEXT))
                FeedNotifier(backgroundScope, src, settings, MutableStateFlow(null), { res }, out).start()
                runCurrent()
                suspend fun arrive(i: FeedItem) {
                    src.items.value = src.items.value.filterNot { it.itemId == i.itemId } + i
                    src.arrivals.emit(i)
                    runCurrent()
                    out.shown[i.itemId]?.let { seen += it }
                }
                arrive(item("f1"))
                arrive(item("b", kind = "grant.request", count = 2, ref = "g1"))
                arrive(item("a", kind = "credential.alarm", ref = "a1").copy(priority = "urgent"))
                // Held: the item notifications go, the counts are shown, nothing new is named.
                src.open.value = false
                src.ownerCheck.value = OwnerCheckView(OwnerCheckState.HELD, null, 86_400, 0, hold = true, holdOffUntil = null, waiting = WaitingCounts(2, 1, 0, 0))
                runCurrent()
                arrive(item("f2"))
                out.shown[FeedNotifier.TAG_CHECK]?.let { seen += it }
                // Locked by another device: "Your vault is locked".
                src.ownerCheck.value = null
                src.lockedElsewhere.value = true
                runCurrent()
                out.shown[FeedNotifier.TAG_LOCKED]?.let { seen += it }
            }
            return out.log to seen
        }
        val service = scenario(NotificationMode.SERVICE)
        val push = scenario(NotificationMode.PUSH)
        assertEquals(service, push)
        assertEquals(
            listOf("post:f1", "post:b", "post:a", "cancel:f1", "cancel:b", "cancel:a", "post:owner_check.due", "cancel:owner_check.due", "post:vault.locked"),
            push.first,
        )
        assertTrue("held: nothing names a connection", push.second.last { it.tag == FeedNotifier.TAG_CHECK }.let { !it.names && it.text == "2 messages and 1 request waiting" })
    }

    @Test
    fun anItemPreparedWhileThePhoneWasWipedIsNotPostedAfterwards() = runTest {
        settings.value = NotifySettings(previews = NotificationPreviews.NAMES_AND_TEXT)
        notifier()
        arrive(item("early"))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        source.textGate = gate
        arrive(item("late")) // waits for the message text
        assertEquals(setOf("early"), poster.shown.keys)
        // The wipe (or a lock): the feed is cleared and the vault is no longer open.
        source.items.value = emptyList()
        source.open.value = false
        runCurrent()
        assertTrue(poster.shown.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertTrue("nothing is posted after the cancel: ${poster.log}", poster.shown.isEmpty())
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

    /** Release L (VAULT-MESSAGING 0.29.0 §3.6.8): the idle lock's notification on Security, with the time computed. */
    @Test
    fun anIdleLockSaysTheVaultLockedItself() = runTest {
        notifier()
        source.lockedElsewhere.value = true
        runCurrent()
        assertEquals("Your vault is locked", poster.shown.getValue(FeedNotifier.TAG_LOCKED).title)
        source.idleLocked.value = com.vettid.core.data.vault.IdleLocked(604_800)
        runCurrent()
        val n = poster.shown.getValue(FeedNotifier.TAG_LOCKED)
        assertEquals(Channels.SECURITY, n.channel)
        assertEquals("Your vault locked itself", n.title)
        assertEquals(
            "You hadn’t checked in for 7 days, so your vault locked. Unlock it with your PIN and your credential password to carry on. New messages wait for up to 14 days.",
            n.text,
        )
        assertEquals("VettID needs your attention", n.publicTitle)
        // Staging (24): 7 days run as 7 hours; and a time not known.
        source.idleLocked.value = com.vettid.core.data.vault.IdleLocked(25_200)
        runCurrent()
        assertTrue(poster.shown.getValue(FeedNotifier.TAG_LOCKED).text!!.startsWith("You hadn’t checked in for 7 hours,"))
        source.idleLocked.value = com.vettid.core.data.vault.IdleLocked(null)
        runCurrent()
        assertTrue(poster.shown.getValue(FeedNotifier.TAG_LOCKED).text!!.startsWith("You hadn’t checked in, so"))
        source.lockedElsewhere.value = false
        runCurrent()
        assertNull(poster.shown[FeedNotifier.TAG_LOCKED])
    }

    @Test
    fun durationsAreSaidAsOnTheScreens() {
        assertEquals("1 hour", FeedNotifier.durationText(3_600, res))
        assertEquals("as the setting names it", "1 day", FeedNotifier.durationText(86_400, res))
        assertEquals("30 days", FeedNotifier.durationText(2_592_000, res))
        assertEquals("150 seconds", FeedNotifier.durationText(150, res))
        assertEquals("15 minutes", FeedNotifier.durationText(900, res))
    }
}
