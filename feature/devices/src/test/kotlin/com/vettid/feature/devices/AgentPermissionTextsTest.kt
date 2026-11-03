// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.devices

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The plan's drafts for an agent's permissions, word for word (ANDROID-PLAN 0.1.116 §4 AA3); not approved yet: the owner approves them. */
@RunWith(RobolectricTestRunner::class)
class AgentPermissionTextsTest {
    private val r = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun theDraftsAreThePlansWords() {
        val drafts = listOf(
            R.string.devices_perms_title to "Other permissions",
            R.string.devices_scope_connection_list to "See your list of connections",
            R.string.devices_scope_connection_get to "See a connection's details",
            R.string.devices_scope_message_send to "Send messages for you",
            R.string.devices_scope_message_list to "See your conversations",
            R.string.devices_scope_message_get to "Read your messages",
            R.string.devices_scope_message_read to "Mark messages as read",
            R.string.devices_scope_profile_get to "See your shared profile",
            R.string.devices_scope_action_list to "See shared actions",
            R.string.devices_scope_action_invoke to "Use shared actions",
            R.string.devices_perm_ask to "Asks you each time",
            R.string.devices_perm_removed_connection to "Removed connection",
            R.string.devices_perm_remove_title to "Remove this permission?",
            R.string.devices_perm_removed to "Permission removed",
            R.string.devices_perm_already_removed to "It was already removed.",
            R.string.devices_paused_title to "Paused by your vault",
            R.string.devices_resume to "Resume",
            R.string.devices_resumed to "Resumed",
            R.string.devices_not_paused to "It is no longer paused",
        )
        for ((id, text) in drafts) assertEquals(text, r.getString(id))
        assertEquals("Allowed automatically, up to 60 an hour and 1,000 a day without asking", r.getString(R.string.devices_perm_auto, 60L, 1_000L))
        assertEquals(", until 3 Nov 2026", r.getString(R.string.devices_perm_until, "3 Nov 2026"))
        assertEquals(", only with Sam Lee, Ana Ruiz", r.getString(R.string.devices_perm_only_with, "Sam Lee, Ana Ruiz"))
        assertEquals(
            "Claude made too many refused requests in an hour, so your vault paused all of its permissions. Resume it only if you know why this happened.",
            r.getString(R.string.devices_paused_body, "Claude"),
        )
        assertEquals(
            "Claude can no longer send messages for you from now on. Services it showed this permission to may accept it for up to 16 minutes.",
            r.getString(R.string.devices_perm_remove_body, "Claude", "send messages for you", "16 minutes"),
        )
    }

    @Test
    fun theTextsThePlanDoesNotGiveAreProposedHere() {
        assertEquals("Another permission", r.getString(R.string.devices_scope_unknown))
        assertEquals("Other permissions could not be loaded.", r.getString(R.string.devices_perms_failed))
        assertEquals("That permission could not be removed. Try again.", r.getString(R.string.devices_perm_remove_failed))
        assertEquals("Not resumed", r.getString(R.string.devices_resume_failed_title))
        assertEquals("Claude could not be resumed. Try again.", r.getString(R.string.devices_resume_failed, "Claude"))
        assertEquals("Name not shared yet", r.getString(R.string.devices_perm_unnamed_connection))
        assertEquals("More actions for Send messages for you", r.getString(R.string.devices_perm_more_cd, "Send messages for you"))
        assertEquals(
            "Claude loses this permission from now on. Services it showed this permission to may accept it for up to 1 hour.",
            r.getString(R.string.devices_perm_remove_body_unknown, "Claude", "1 hour"),
        )
    }

    @Test
    fun theNineScopesHaveALabelAndAnythingElseHasNone() {
        for (scope in listOf("connection.list", "connection.get", "message.send", "message.list", "message.get", "message.read", "profile.get", "action.list", "action.invoke")) {
            assertEquals(scope, true, PermissionText.labelRes(scope) != null)
        }
        assertNull(PermissionText.labelRes("items.read"))
        assertNull(PermissionText.labelRes("wallet.send"))
        assertNull(PermissionText.labelRes(""))
    }
}
