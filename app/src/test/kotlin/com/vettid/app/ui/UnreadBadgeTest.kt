package com.vettid.app.ui

import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.social.ConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Messages badge never counts a service (ANDROID-PLAN 0.1.90). */
class UnreadBadgeTest {
    private fun conv(id: String, unread: Int, service: Boolean) =
        ConversationSummary(ConnectionInfo(id, id, ConnectionState.ACTIVE, service = service), null, unread)

    @Test
    fun aServiceWithUnreadDoesNotCount() {
        assertEquals(1, unreadConversations(listOf(conv("p", 2, false), conv("s", 5, true), conv("q", 0, false))))
        assertEquals(0, unreadConversations(listOf(conv("s", 5, true))))
    }
}
