package com.vettid.core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tapped item notification is handed to the notifier (it goes by itself), a Settings tap is not. */
class NotificationOpenInboxTest {
    @Test
    fun aTappedItemIsReportedAndSettingsIsNot() {
        val tapped = mutableListOf<String>()
        val inbox = NotificationOpenInbox { tapped += it }
        inbox.offer(NotificationOpenInbox.Open.Settings)
        assertEquals(emptyList<String>(), tapped)
        inbox.offer(NotificationOpenInbox.Open.Item("f1"))
        assertEquals(listOf("f1"), tapped)
        assertEquals(NotificationOpenInbox.Open.Item("f1"), inbox.pending.value)
    }

    @Test
    fun aTappedConnectionIsHandedToTheShellAndIsNotAnItem() {
        val tapped = mutableListOf<String>()
        val inbox = NotificationOpenInbox { tapped += it }
        inbox.offer(NotificationOpenInbox.Open.Connection("c1"))
        assertEquals(emptyList<String>(), tapped)
        assertEquals(NotificationOpenInbox.Open.Connection("c1"), inbox.pending.value)
    }

    @Test
    fun aTapNotYetTakenGoesWhenTheVaultCloses() {
        val inbox = NotificationOpenInbox()
        inbox.offer(NotificationOpenInbox.Open.Connection("c1"))
        inbox.vaultClosed()
        assertNull(inbox.pending.value)
    }

    @Test
    fun onlyAnIdWithTheShapeOfAVaultIdIsPlausible() {
        listOf("c1", "01JD3X5Q8Z7K2M4N6P8R0T2V4W", "conn_a-b.c:1", "a".repeat(128)).forEach {
            assertTrue(it, NotificationOpenInbox.plausibleId(it))
        }
        listOf(null, "", "a".repeat(129), "../c1", "c 1", "c/1", "c1\n", "c\u00e91", "c1?x=1").forEach {
            assertFalse(it.toString(), NotificationOpenInbox.plausibleId(it))
        }
    }
}
