package com.vettid.core.notify

import org.junit.Assert.assertEquals
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
}
