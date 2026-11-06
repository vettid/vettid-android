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
import kotlinx.coroutines.test.advanceTimeBy
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

        /** Snoozed: not active (so [isPosted] says no) but still known to the system, and cancelled by [cancel]. */
        val snoozed = linkedMapOf<String, LocalNotification>()

        fun snooze(tag: String) {
            snoozed[tag] = shown.remove(tag)!!
        }
        val log = mutableListOf<String>()

        override fun post(n: LocalNotification) {
            // Posting to a snoozed tag updates it without showing it (as the system does).
            if (n.tag in snoozed) snoozed[n.tag] = n else shown[n.tag] = n
            log += "post:${n.tag}"
        }

        override fun isPosted(tag: String): Boolean = tag in shown

        override fun cancel(tag: String) {
            val had = shown.remove(tag) != null
            if (snoozed.remove(tag) != null || had) log += "cancel:$tag"
        }

        var leftoverSweeps = mutableListOf<Set<String>>()

        override fun cancelLeftovers(keep: Set<String>) {
            leftoverSweeps += keep
        }
    }

    private class Source : NotifySource {
        override val arrivals = MutableSharedFlow<FeedItem>(extraBufferCapacity = 16)
        override val items = MutableStateFlow<List<FeedItem>>(emptyList())
        override val open = MutableStateFlow(true)
        override val covered = MutableStateFlow(false)
        /** When set, what [coveredNow] says whatever the [covered] flow still holds (the flow lags its source). */
        var nowOverride: Boolean? = null

        override fun coveredNow() = nowOverride ?: covered.value
        var namesCalls = 0
        var textCalls = 0
        override val lockedElsewhere = MutableStateFlow(false)
        override val idleLocked = MutableStateFlow<com.vettid.core.data.vault.IdleLocked?>(null)
        override val ownerCheck = MutableStateFlow<OwnerCheckView?>(null)
        override val alarm = MutableStateFlow<CredentialAlarm?>(null)
        var names = FeedNames(mapOf("c1" to ConnectionInfo("c1", "", ConnectionState.ACTIVE, firstName = "Sam", lastName = "Lee")), mapOf("i1" to "Passport"))

        override fun names(): FeedNames {
            namesCalls++
            return names
        }

        /** What [refreshVotes] does: by default the list reads and [names] stays as it is. */
        var refresh: suspend () -> Boolean = { true }

        override suspend fun refreshVotes(): Boolean = refresh()

        /** When set, [messageText] waits for it: an item notification still being prepared. */
        var textGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun messageText(connectionId: String, messageId: String): String? {
            textCalls++
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

    // --- the app lock's cover (owner request 2026-11-02): nothing past the lock names anything ---

    @Test
    fun underTheCoverAnItemNotifiesInItsPublicFormOnly() = runTest {
        notifier()
        source.covered.value = true
        settings.value = NotifySettings(previews = NotificationPreviews.NAMES_AND_TEXT)
        arrive(item("f1"))
        arrive(item("f2", kind = "item.revealed", ref = "i1"))
        arrive(item("f3", kind = "grant.request", ref = "g1"))
        for (tag in listOf("f1", "f2", "f3")) {
            val n = poster.shown.getValue(tag)
            assertEquals(n.publicTitle, n.title)
            assertNull(n.text)
            assertFalse(n.names)
            val all = "${n.title} ${n.text}"
            assertFalse(all, "Sam" in all || "Lee" in all || "Passport" in all || "See you" in all)
        }
        assertEquals("New activity in VettID", poster.shown.getValue("f1").title)
        assertEquals(Channels.MESSAGES, poster.shown.getValue("f1").channel)
        assertEquals("f1", poster.shown.getValue("f1").itemId)
    }

    /**
     * The wallet's feed items (ANDROID-PLAN §4 "Wallet", "Notifications"; `wallet.created` is History only): all on Vault
     * activity; a wallet's name only with names on and no cover; under the cover nothing is read and no txid or name is said.
     */
    @Test
    fun theWalletsItemsNotifyOnVaultActivityAndNameTheWalletOnlyUncovered() = runTest {
        notifier()
        source.names = FeedNames(emptyMap(), mapOf("w1" to "Savings"))
        val kinds = listOf("wallet.signed" to "ab12cd34", "wallet.cap_refused" to "w1", "wallet.caps_raised" to "w1")
        settings.value = NotifySettings(previews = NotificationPreviews.NAMES_AND_TEXT)
        kinds.forEachIndexed { i, (k, ref) -> arrive(item("u$i", kind = k, ref = ref).copy(connectionId = null, priority = "high")) }
        for (i in kinds.indices) assertEquals(Channels.ACTIVITY, poster.shown.getValue("u$i").channel)
        assertEquals("Bitcoin payment signed", poster.shown.getValue("u0").title)
        assertEquals("A payment was refused by your spending limit", poster.shown.getValue("u1").title)
        assertEquals("A spending limit was raised or removed on \u2068Savings\u2069", poster.shown.getValue("u2").title)
        // Names only: the kind, never the wallet's name.
        settings.value = NotifySettings(previews = NotificationPreviews.NAMES)
        arrive(item("n2", kind = "wallet.caps_raised", ref = "w1").copy(connectionId = null, priority = "high"))
        assertEquals("A spending limit was raised or removed", poster.shown.getValue("n2").title)
        // Under the cover: the public form only, no name read, no txid anywhere.
        val before = source.namesCalls
        source.covered.value = true
        settings.value = NotifySettings(previews = NotificationPreviews.NAMES_AND_TEXT)
        kinds.forEachIndexed { i, (k, ref) -> arrive(item("c$i", kind = k, ref = ref).copy(connectionId = null, priority = "high")) }
        assertEquals("no names read under the cover", before, source.namesCalls)
        for (i in kinds.indices) {
            val n = poster.shown.getValue("c$i")
            assertEquals(Channels.ACTIVITY, n.channel)
            assertEquals("New activity in VettID", n.title)
            assertNull(n.text)
            assertFalse("${n.title} ${n.text}".let { "Savings" in it || "ab12cd34" in it })
        }
    }

    @Test
    fun underTheCoverNothingIsReadAndAnArrivalKeepsItsSound() = runTest {
        notifier()
        source.covered.value = true
        settings.value = NotifySettings(previews = NotificationPreviews.NAMES_AND_TEXT)
        arrive(item("f1"))
        arrive(item("f2", kind = "grant.request", ref = "g1"))
        assertEquals("no names read", 0, source.namesCalls)
        assertEquals("no message text read", 0, source.textCalls)
        for (tag in listOf("f1", "f2")) {
            val n = poster.shown.getValue(tag)
            assertEquals(n.publicTitle, n.title)
            assertNull(n.text)
            assertFalse("a new arrival still sounds, vibrates and shows heads-up", n.silent)
        }
        // A re-post after a withdrawal stays as quiet as it was.
        arrive(item("f2", kind = "grant.request", ref = "g1").copy(quietRepost = true))
        assertTrue(poster.shown.getValue("f2").silent)
    }

    @Test
    fun aSnoozedNotificationStaysKnownAndIsCancelledWhenTheVaultLocks() = runTest {
        notifier()
        arrive(item("f1"))
        poster.snooze("f1")
        source.covered.value = true
        runCurrent()
        assertTrue("not shown (it is snoozed)", poster.shown.isEmpty())
        assertEquals("but updated in place: the named original cannot come back", "New activity in VettID", poster.snoozed.getValue("f1").title)
        assertNull(poster.snoozed.getValue("f1").text)
        source.open.value = false
        runCurrent()
        assertTrue(poster.snoozed.isEmpty())
        assertTrue("cancel:f1" in poster.log)
    }

    @Test
    fun theCoverIsReadFromItsSourceNotFromTheLaggingFlow() = runTest {
        notifier()
        // The flow still says "open", the source already says "covered": public.
        source.nowOverride = true
        arrive(item("f1"))
        assertEquals("New activity in VettID", poster.shown.getValue("f1").title)
        assertEquals(0, source.namesCalls)
        // A re-post for a cover that is gone by the time it runs does not replace a named notification.
        source.nowOverride = null
        arrive(item("f2"))
        assertEquals("New message from \u2068Sam Lee\u2069", poster.shown.getValue("f2").title)
        source.nowOverride = false
        source.covered.value = true
        runCurrent()
        assertEquals("New message from \u2068Sam Lee\u2069", poster.shown.getValue("f2").title)
    }

    @Test
    fun aCoverThatTheTimerMissedIsActedOnByAnArrivalOrTheScreenComingOn() = runTest {
        val n = notifier()
        settings.value = NotifySettings(previews = NotificationPreviews.NAMES_AND_TEXT)
        arrive(item("f1"))
        arrive(item("f2", kind = "item.revealed", ref = "i1"))
        // The wall clock is past the deadline, the timer (the flow) has not fired.
        source.nowOverride = true
        n.recheckCover()
        assertEquals("New activity in VettID", poster.shown.getValue("f1").title)
        assertEquals("New activity in VettID", poster.shown.getValue("f2").title)
        assertTrue(poster.shown.getValue("f1").silent)
        // Once per cover: asked again, nothing is posted again.
        poster.log.clear()
        n.recheckCover()
        assertTrue(poster.log.isEmpty())
        // The cover goes (unlocked); a named one posted; the next cover is acted on again.
        source.nowOverride = false
        arrive(item("f3"))
        assertEquals("New message from \u2068Sam Lee\u2069", poster.shown.getValue("f3").title)
        source.nowOverride = true
        arrive(item("f4"))
        assertEquals("New activity in VettID", poster.shown.getValue("f3").title)
        assertEquals("New activity in VettID", poster.shown.getValue("f4").title)
    }

    @Test
    fun aTappedNotificationIsNotBroughtBackByACoverUp() = runTest {
        val n = notifier()
        arrive(item("f1"))
        poster.shown.remove("f1") // tapped: auto-cancelled
        n.onDismissed("f1") // the tap handler
        poster.log.clear()
        source.covered.value = true
        runCurrent()
        assertTrue(poster.log.isEmpty())
        assertTrue(poster.shown.isEmpty())
    }

    @Test
    fun withTheCoverDownItIsAsBefore() = runTest {
        notifier()
        source.covered.value = true
        source.covered.value = false
        runCurrent()
        arrive(item("f1"))
        assertEquals("New message from \u2068Sam Lee\u2069", poster.shown.getValue("f1").title)
        assertFalse(poster.shown.getValue("f1").silent)
    }

    @Test
    fun theCoverComingUpReplacesWhatIsShowingAndLeavesADismissedOneAlone() = runTest {
        val notifier = notifier()
        settings.value = NotifySettings(previews = NotificationPreviews.NAMES_AND_TEXT)
        arrive(item("f1"))
        arrive(item("f2", kind = "item.revealed", ref = "i1"))
        assertEquals("See you at 6", poster.shown.getValue("f1").text)
        poster.shown.remove("f2") // the member swiped it away
        notifier.onDismissed("f2") // its delete intent
        poster.log.clear()
        source.covered.value = true
        runCurrent()
        val n = poster.shown.getValue("f1")
        assertEquals("New activity in VettID", n.title)
        assertNull(n.text)
        assertTrue("quiet", n.silent)
        assertEquals(listOf("post:f1"), poster.log)
        assertFalse("f2" in poster.shown)
        // Read on another device while covered: it still goes.
        source.items.value = emptyList()
        runCurrent()
        assertTrue(poster.shown.isEmpty())
    }

    @Test
    fun anItemBeingPreparedWhenTheCoverComesUpIsPostedPublic() = runTest {
        notifier()
        settings.value = NotifySettings(previews = NotificationPreviews.NAMES_AND_TEXT)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        source.textGate = gate
        arrive(item("f1"))
        assertTrue(poster.shown.isEmpty())
        source.covered.value = true
        gate.complete(Unit)
        runCurrent()
        val n = poster.shown.getValue("f1")
        assertEquals("New activity in VettID", n.title)
        assertNull(n.text)
    }

    @Test
    fun aBatchUpdateUnderTheCoverStaysPublic() = runTest {
        notifier()
        arrive(item("b", kind = "grant.request", count = 2, ref = "g1"))
        source.covered.value = true
        runCurrent()
        arrive(item("b", kind = "grant.request", count = 3, ref = "g1"))
        assertEquals("New activity in VettID", poster.shown.getValue("b").title)
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

    /** ANDROID-PLAN §4 Notification modes 8: a notification posted before a process death does not outlive the open vault. */
    @Test
    fun atStartWithTheVaultNotOpenWhatAnEarlierProcessPostedIsCancelled() = runTest {
        source.open.value = false
        notifier()
        assertEquals(1, poster.leftoverSweeps.size)
        // The vault's own state notices are not item notifications: they stay.
        assertEquals(setOf(FeedNotifier.TAG_LOCKED, FeedNotifier.TAG_CHECK, FeedNotifier.TAG_ALARM), poster.leftoverSweeps.single())
    }

    @Test
    fun anOpenVaultAtStartSweepsNothingAndALockSweepsAgain() = runTest {
        notifier()
        assertTrue(poster.leftoverSweeps.isEmpty())
        source.open.value = false
        runCurrent()
        assertEquals(1, poster.leftoverSweeps.size)
    }

    @Test
    fun receivedMessageTextIsCleanedIsolatedAndFirstLineOnly() {
        val fsi = "\u2068"
        val pdi = "\u2069"
        assertEquals("${fsi}hi$pdi", notifyMessageText("\u202E\n hi \nsecond", false, res))
        assertNull(notifyMessageText("\u200B\u3164\n\u200D", false, res))
        assertEquals("Photo", notifyMessageText("\u3164", true, res))
        assertEquals("Photo: ${fsi}beach$pdi", notifyMessageText("\u202Ebeach\nmore", true, res))
    }

    // --- one ask of a batch withdrawn (VAULT-MESSAGING 0.80.0 §10.4) ---

    private fun askItem() = item("b1", kind = "connection.authenticate.requested", count = 3, ref = "a1")

    @Test
    fun aWithdrawRepostIsSilentTimesOutAtTheLatestDeadlineAndShowsTheEarliest() = runTest {
        notifier()
        arrive(askItem())
        val early = java.time.Instant.parse("2026-10-30T12:05:00Z")
        val late = java.time.Instant.parse("2026-10-30T12:30:00Z")
        arrive(askItem().copy(count = 2, openUntil = early, closeAt = late, quietRepost = true))
        val n = poster.shown.getValue("b1")
        assertTrue(n.silent)
        assertEquals(early, n.openUntil)
        assertEquals(late, n.closeAt)
        assertTrue("alerts once", n.alertOnce)
    }

    @Test
    fun aDismissedNotificationIsNotBroughtBackByAWithdrawRepost() = runTest {
        notifier()
        arrive(askItem())
        poster.shown.remove("b1") // swiped away
        poster.log.clear()
        arrive(askItem().copy(count = 2, quietRepost = true))
        assertTrue(poster.log.isEmpty())
    }

    @Test
    fun aPlainRepostWhenAnAskJoinsCarriesNoOldDeadline() = runTest {
        notifier()
        arrive(askItem().copy(count = 2, closeAt = java.time.Instant.parse("2026-10-30T12:30:00Z"), quietRepost = true))
        arrive(askItem().copy(count = 3))
        val n = poster.shown.getValue("b1")
        assertNull(n.closeAt)
        assertNull(n.openUntil)
        assertEquals(false, n.silent)
    }

    @Test
    fun theNotificationNeverCarriesAMatchCode() = runTest {
        notifier()
        // Whatever a title or body of the item says, no preview setting shows it as the match code: the text is the app's own.
        val signin = item("s1", kind = "connection.authenticate.requested", ref = "a1").copy(title = "Match code 4271", body = "match code 4271")
        listOf(com.vettid.core.data.prefs.NotificationPreviews.NAMES, com.vettid.core.data.prefs.NotificationPreviews.NAMES_AND_TEXT).forEach {
            settings.value = NotifySettings(previews = it)
            arrive(signin)
            val n = poster.shown.getValue("s1")
            assertTrue(n.title, !n.title.contains("4271"))
            assertTrue(n.text.orEmpty(), !n.text.orEmpty().contains("4271"))
        }
    }

    // --- ANDROID-PLAN 0.1.116 §4 AA5: device and agent items ---

    private fun deviceItem(id: String, kind: String, device: String? = "d1", ref: String? = "r1") =
        FeedItem(id, 1, kind, "2026-10-09T12:00:00Z", "active", "high", deviceId = device, ref = ref)

    private val agentNames get() = FeedNames(devices = mapOf("d1" to "Build bot"), pairingName = "New bot", pairingPendingId = "r1")

    @Test
    fun deviceAndAgentItemsNameTheDeviceOnTheirChannels() = runTest {
        notifier()
        source.names = agentNames
        settings.value = NotifySettings(previews = NotificationPreviews.NAMES)
        arrive(deviceItem("a1", "device.session.pending"))
        arrive(deviceItem("a2", "approval.pending"))
        arrive(deviceItem("a3", "leash.agent.suspended"))
        arrive(deviceItem("a4", "leash.referrals_limited"))
        arrive(deviceItem("a5", "device.pair.pending", device = null))
        assertEquals("\u2068Build bot\u2069 asks for access", poster.shown.getValue("a1").title)
        assertEquals("\u2068Build bot\u2069 asks you to approve a request", poster.shown.getValue("a2").title)
        assertEquals("\u2068Build bot\u2069 was paused", poster.shown.getValue("a3").title)
        assertEquals("\u2068Build bot\u2069 asked too often", poster.shown.getValue("a4").title)
        assertEquals("\u2068New bot\u2069 asks to pair", poster.shown.getValue("a5").title)
        assertEquals(Channels.REQUESTS, poster.shown.getValue("a1").channel)
        assertEquals(Channels.REQUESTS, poster.shown.getValue("a2").channel)
        assertEquals(Channels.REQUESTS, poster.shown.getValue("a5").channel)
        // Decision 16: a paused agent, or one asking too often, is a Security alarm.
        assertEquals(Channels.SECURITY, poster.shown.getValue("a3").channel)
        assertEquals(Channels.SECURITY, poster.shown.getValue("a4").channel)
        assertEquals("VettID needs your attention", poster.shown.getValue("a3").publicTitle)
        assertEquals("VettID needs your attention", poster.shown.getValue("a4").publicTitle)
    }

    @Test
    fun underTheCoverADeviceOrAgentItemNamesNothing() = runTest {
        notifier()
        source.names = agentNames
        source.covered.value = true
        for (previews in listOf(NotificationPreviews.NAMES, NotificationPreviews.NAMES_AND_TEXT)) {
            settings.value = NotifySettings(previews = previews)
            arrive(deviceItem("c1", "device.session.pending"))
            arrive(deviceItem("c2", "approval.pending"))
            arrive(deviceItem("c3", "leash.agent.suspended"))
            arrive(deviceItem("c4", "device.pair.pending", device = null))
            for (tag in listOf("c1", "c2", "c3", "c4")) {
                val n = poster.shown.getValue(tag)
                assertEquals(n.publicTitle, n.title)
                assertNull(n.text)
                assertFalse(n.names)
                assertFalse("${n.title}", "Build bot" in n.title || "New bot" in n.title)
            }
            assertEquals(Channels.SECURITY, poster.shown.getValue("c3").channel)
            assertEquals("VettID needs your attention", poster.shown.getValue("c3").title)
            assertEquals("New activity in VettID", poster.shown.getValue("c1").title)
        }
        assertEquals("no names read under the cover", 0, source.namesCalls)
    }

    @Test
    fun anItemNamingNoKnownDeviceSaysAnAgentOrADevice() = runTest {
        notifier()
        settings.value = NotifySettings(previews = NotificationPreviews.NAMES)
        arrive(deviceItem("n1", "device.session.pending", device = "gone"))
        arrive(deviceItem("n2", "leash.agent.suspended", device = null))
        assertEquals("A device asks for access", poster.shown.getValue("n1").title)
        assertEquals("An agent was paused", poster.shown.getValue("n2").title)
    }

    // --- review 1, M3: the give-up vote.result is titled from the list as read after it arrived ---

    private val voteKey = com.vettid.core.data.feed.VoteTitles.key("c1", "P1")

    private suspend fun TestScope.voteResult(id: String) = arrive(item(id, kind = "vote.result", ref = "P1"))

    @Test
    fun aGiveUpThatTheFreshListShowsVerifiedIsTitledAsAResult() = runTest {
        notifier()
        source.names = source.names.copy(votesGaveUp = setOf(voteKey))
        source.refresh = {
            source.names = source.names.copy(votesGaveUp = emptySet())
            true
        }
        voteResult("v1")
        assertEquals("Vote results are out", poster.shown.getValue("v1").title)
    }

    @Test
    fun aFreshGiveUpIsTitledAsOne() = runTest {
        notifier()
        source.refresh = {
            source.names = source.names.copy(votesGaveUp = setOf(voteKey))
            true
        }
        voteResult("v2")
        assertEquals("Vote results not checked yet", poster.shown.getValue("v2").title)
    }

    @Test
    fun aListThatCannotBeReadInTimeOrAtAllGivesTheGenericTitle() = runTest {
        notifier()
        source.names = source.names.copy(votesGaveUp = setOf(voteKey))
        source.refresh = {
            kotlinx.coroutines.awaitCancellation()
        }
        voteResult("v3")
        assertFalse(poster.shown.containsKey("v3"))
        advanceTimeBy(FeedNotifier.VOTE_LIST_WAIT_MS + 1)
        runCurrent()
        assertEquals("Vote results are out", poster.shown.getValue("v3").title)
        source.refresh = { throw com.vettid.core.data.vault.VaultFailure(com.vettid.core.data.vault.FailureKind.OTHER, "x") }
        voteResult("v4")
        assertEquals("Vote results are out", poster.shown.getValue("v4").title)
        // Another kind is not held up.
        source.refresh = { kotlinx.coroutines.awaitCancellation() }
        arrive(item("v5"))
        assertTrue(poster.shown.containsKey("v5"))
    }
}
