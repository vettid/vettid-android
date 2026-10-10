// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.notifications

import androidx.lifecycle.SavedStateHandle
import org.robolectric.RuntimeEnvironment
import com.vettid.core.data.feed.FeedKinds
import com.vettid.core.data.feed.FeedNames
import com.vettid.core.data.feed.FeedTarget
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.social.GrantEntry
import com.vettid.core.data.vault.AuditCategory
import com.vettid.core.testing.FakeFeed
import com.vettid.core.testing.FakeItems
import com.vettid.core.testing.FakeSocial
import com.vettid.core.vault.FeedItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The Notifications screen's texts, targets, grouping and ViewModel (ANDROID-PLAN 0.1.23 Notifications, 3, 4, 11). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NotificationsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private val now = Instant.parse("2026-10-09T12:00:00Z")

    private val alice = ConnectionInfo("c1", "", ConnectionState.ACTIVE, firstName = "Alice", lastName = "Moreau")
    private val grant = Approval.GrantRequest("g1", "c1", listOf(GrantEntry("item", "i1", "Passport", true, name = "Passport")), 1, null, null, now, null, "Alice Moreau")
    private val names = FeedNames(mapOf("c1" to alice), mapOf("i1" to "Passport"), mapOf(grant.key to grant))

    private fun item(kind: String, id: String = kind, status: String = "active", priority: String = "normal", c: String? = "c1", ref: String? = "r1", at: Instant = now, count: Int? = null) =
        FeedItem(id, 1, kind, at.toString(), status, priority, connectionId = c, ref = ref, count = count)

    @Test
    fun everyKindHasATextAnIconAColourAndATarget() {
        for (kind in FeedKinds.KNOWN) {
            val i = item(kind)
            val text = FeedKinds.title(i, names).resolve(res)
            assertTrue("$kind: $text", text.isNotBlank() && !text.startsWith("Activity:"))
            if (kind != "guide") assertNotEquals("$kind has a category", AuditCategory.OTHER, FeedIcons.category(kind))
            FeedIcons.icon(kind)
            FeedIcons.hue(kind)
            val t = FeedKinds.target(i)
            if (FeedKinds.notAvailable(kind)) assertEquals(kind, FeedTarget.Sheet, t)
        }
        assertEquals("New message from Alice Moreau", FeedKinds.title(item("message.received"), names).resolve(res))
        assertEquals("Alice asks for Passport", FeedKinds.title(item("grant.request", ref = "g1"), names).resolve(res))
        assertEquals("Alice asks for 3 things", FeedKinds.title(item("grant.request", count = 3), names).resolve(res))
        assertEquals("3 things", FeedKinds.supporting(item("grant.request", count = 3), names)?.resolve(res))
        assertEquals("A daily check failed: wrong password", FeedKinds.title(item("owner_check.failed", ref = "password"), names).resolve(res))
        assertEquals(FeedTarget.ApprovalEntry("grant:g1"), FeedKinds.target(item("grant.request", ref = "g1")))
        assertEquals(FeedTarget.Conversation("c1"), FeedKinds.target(item("message.received")))
        assertEquals(FeedTarget.ShareRule("c1", "r1"), FeedKinds.target(item("share.rate_limited")))
        assertEquals(FeedTarget.Alarm, FeedKinds.target(item("credential.alarm", c = null)))
    }

    @Test
    fun unknownKindsAndMissingNamesFallBack() {
        val unknown = item("vault.something_new")
        assertEquals("Activity: vault.something_new", FeedKinds.title(unknown, names).resolve(res))
        assertEquals(FeedTarget.Sheet, FeedKinds.target(unknown))
        assertFalse(FeedKinds.known(unknown.kind))
        assertEquals("Removed connection was removed", FeedKinds.title(item("connection.removed", c = "gone"), names).resolve(res))
        assertEquals("Deleted item was revealed", FeedKinds.title(item("item.revealed", c = null, ref = "i9"), names).resolve(res))
        assertEquals("Passport was revealed", FeedKinds.title(item("item.revealed", c = null, ref = "i1"), names).resolve(res))
        // Never a value, never an email: names only.
        assertEquals("Alice asks for an item", FeedKinds.title(item("grant.request", ref = "g9"), names).resolve(res))
    }

    @Test
    fun urgentUnreadItemsNeedAttentionAndTheRestGoByDay() {
        val zone = ZoneOffset.UTC
        val items = listOf(
            item("credential.alarm", "a", priority = "urgent", at = now.minusSeconds(60)),
            item("credential.alarm", "a2", status = "read", priority = "urgent", at = now.minusSeconds(120)),
            item("message.received", "m1", at = now.minusSeconds(30)),
            item("message.received", "m2", status = "read", at = now.minusSeconds(86_400)),
            item("message.received", "m3", status = "archived", at = now.minusSeconds(10)),
        )
        val s = FeedSections.of(items, archived = false, unreadOnly = false, zone = zone)
        assertNull(s[0].day)
        assertEquals(listOf("a"), s[0].items.map { it.itemId })
        assertEquals(LocalDate.of(2026, 10, 9), s[1].day)
        assertEquals(listOf("m1", "a2"), s[1].items.map { it.itemId })
        assertEquals(listOf("m2"), s[2].items.map { it.itemId })
        assertEquals(listOf("a", "m1"), FeedSections.of(items, false, unreadOnly = true, zone = zone).flatMap { it.items }.map { it.itemId })
        assertEquals(listOf("m3"), FeedSections.of(items, archived = true, unreadOnly = false, zone = zone).flatMap { it.items }.map { it.itemId })
    }

    private val feed = FakeFeed()
    private val social = FakeSocial().apply {
        seed(listOf(alice))
        approvals.value = listOf(grant)
    }
    private val items = FakeItems().apply { add(FakeItems.item("i1", "Passport")) }

    private fun vm(archived: Boolean = false) =
        NotificationsViewModel(SavedStateHandle(mapOf(NotificationsViewModel.ARG_ARCHIVED to archived)), feed, social, social, items)

    @Test
    fun aTapMarksReadAndOpensTheTargetOrTheSheetWhenItIsGone() = runTest {
        val ask = item("grant.request", "f1", ref = "g1")
        val decided = item("grant.request", "f2", ref = "g0")
        val removed = item("message.received", "f3", c = "c9")
        feed.seed(listOf(ask, decided, removed))
        val m = vm()
        advanceUntilIdle()
        m.open(ask)
        assertEquals(FeedTarget.ApprovalEntry("grant:g1"), m.uiState.value.open)
        assertTrue("markRead:f1" in feed.calls)
        m.targetTaken()
        m.open(decided)
        assertNull(m.uiState.value.open)
        assertEquals(decided, m.uiState.value.sheet)
        assertTrue(m.uiState.value.sheetGone)
        m.closeSheet()
        m.open(removed)
        assertEquals(removed, m.uiState.value.sheet)
    }

    @Test
    fun archiveUndoesToThePreviousStatusAndDeleteIsConfirmed() = runTest {
        val read = item("message.received", "f1", status = "read")
        feed.seed(listOf(read, item("message.received", "f2")))
        val m = vm()
        advanceUntilIdle()
        m.archive(read)
        advanceUntilIdle()
        assertEquals("archived", feed.items.value.first { it.itemId == "f1" }.status)
        m.undoArchive()
        advanceUntilIdle()
        assertEquals("read", feed.items.value.first { it.itemId == "f1" }.status)
        m.toggleRead(feed.items.value.first { it.itemId == "f2" })
        advanceUntilIdle()
        assertTrue("setStatus:f2:read" in feed.calls)
        m.askDelete(read)
        assertEquals(read, m.uiState.value.confirmDelete)
        m.delete()
        advanceUntilIdle()
        assertTrue("delete:f1" in feed.calls)
        m.markAllRead()
        advanceUntilIdle()
        assertEquals(0, m.uiState.value.unread)
        assertFalse(m.uiState.value.markingAll)
    }

    private fun status(id: String) = feed.items.value.first { it.itemId == id }.status

    private fun current(id: String) = feed.items.value.first { it.itemId == id }

    @Test
    fun viewingMarksTheUnreadNonUrgentItemsReadAndKeepsTheirDotsUntilLeaving() = runTest {
        feed.seed(
            listOf(
                item("message.received", "m1"),
                item("credential.alarm", "a", priority = "urgent", c = null),
                item("owner_check.locked", "l", priority = "urgent", c = null),
                item("message.received", "m2", status = "read"),
                item("message.received", "m3", status = "archived"),
            ),
        )
        val m = vm()
        advanceUntilIdle()
        assertTrue(feed.calls.none { it.startsWith("markViewed") }) // not before the screen is in view
        m.shown()
        advanceUntilIdle()
        assertEquals(listOf("markViewed:m1"), feed.calls.filter { it.startsWith("markViewed") })
        assertEquals("read", status("m1"))
        assertEquals("active", status("a"))
        assertEquals("active", status("l"))
        assertEquals("archived", status("m3"))
        // The bell: only the urgent items are left, so it stays red.
        assertEquals(2, feed.badge.value.unread)
        assertTrue(feed.badge.value.urgent)
        // The dot and the Unread chip keep m1 while the screen is open; not m2 (read before).
        val ui = m.uiState.value
        assertEquals(setOf("m1"), ui.fresh)
        assertTrue(ui.showsUnread(current("m1")))
        assertFalse(ui.showsUnread(current("m2")))
        assertEquals(setOf("a", "l", "m1"), FeedSections.of(ui.items, false, unreadOnly = true, zone = ZoneOffset.UTC, fresh = ui.fresh).flatMap { it.items }.map { it.itemId }.toSet())
        // Leaving: m1 shows as read on return; nothing is marked again.
        m.paused()
        m.left()
        assertTrue(m.uiState.value.fresh.isEmpty())
        assertFalse(m.uiState.value.showsUnread(current("m1")))
        m.shown()
        advanceUntilIdle()
        assertEquals(1, feed.calls.count { it.startsWith("markViewed") })
        assertEquals("active", status("a"))
    }

    @Test
    fun itemsArrivingWhileInViewAreMarkedReadButNotWhilePaused() = runTest {
        feed.seed(listOf(item("message.received", "m1", status = "read")))
        val m = vm()
        advanceUntilIdle()
        m.shown()
        advanceUntilIdle()
        feed.seed(feed.items.value + item("message.received", "n1") + item("credential.alarm", "a", priority = "urgent", c = null))
        advanceUntilIdle()
        assertTrue("markViewed:n1" in feed.calls)
        assertEquals("read", status("n1"))
        assertEquals("active", status("a"))
        assertTrue("n1" in m.uiState.value.fresh)
        m.paused()
        feed.seed(feed.items.value + item("message.received", "n2"))
        advanceUntilIdle()
        assertEquals("active", status("n2"))
        m.shown()
        advanceUntilIdle()
        assertEquals("read", status("n2"))
        assertEquals(setOf("n1", "n2"), m.uiState.value.fresh)
    }

    @Test
    fun anItemMarkedUnreadIsNotMarkedReadAgainByViewing() = runTest {
        feed.seed(listOf(item("message.received", "m1"), item("message.received", "m2", status = "read")))
        val m = vm()
        advanceUntilIdle()
        m.shown()
        advanceUntilIdle()
        // m1 was read by viewing and still has its dot: the swipe reads "Mark as read" and only takes the dot away.
        m.toggleRead(current("m1"))
        advanceUntilIdle()
        assertFalse(m.uiState.value.showsUnread(current("m1")))
        assertTrue(feed.calls.none { it.startsWith("setStatus:m1") })
        // Then Mark as unread, for m1 and for m2 (read before the screen opened): they stay unread.
        m.toggleRead(current("m1"))
        m.toggleRead(current("m2"))
        advanceUntilIdle()
        feed.seed(feed.items.value + item("message.received", "n1")) // an arrival runs the viewing again
        advanceUntilIdle()
        assertEquals("active", status("m1"))
        assertEquals("active", status("m2"))
        assertEquals("read", status("n1"))
        // Coming back to the screen does not mark them either while it lives.
        m.paused()
        m.left()
        m.shown()
        advanceUntilIdle()
        assertEquals("active", status("m1"))
        assertEquals("active", status("m2"))
        assertEquals(listOf("markViewed:m1", "markViewed:n1"), feed.calls.filter { it.startsWith("markViewed") })
    }

    @Test
    fun anItemAnotherDeviceMarksUnreadWhileInViewStaysUnread() = runTest {
        feed.seed(listOf(item("message.received", "m1", status = "read")))
        val m = vm()
        advanceUntilIdle()
        m.shown()
        advanceUntilIdle()
        feed.seed(listOf(item("message.received", "m1"))) // feed.updated from another device
        advanceUntilIdle()
        assertEquals("active", status("m1"))
        assertTrue(feed.calls.none { it.startsWith("markViewed") })
    }

    @Test
    fun theArchivedViewMarksNothingAndMarkAllReadClearsTheDots() = runTest {
        feed.seed(listOf(item("message.received", "m1"), item("message.received", "m2", status = "archived")))
        val archived = vm(archived = true)
        advanceUntilIdle()
        archived.shown()
        advanceUntilIdle()
        assertTrue(feed.calls.none { it.startsWith("markViewed") })
        val m = vm()
        advanceUntilIdle()
        m.shown()
        advanceUntilIdle()
        assertEquals(setOf("m1"), m.uiState.value.fresh)
        m.markAllRead()
        advanceUntilIdle()
        assertTrue(m.uiState.value.fresh.isEmpty())
    }
}
