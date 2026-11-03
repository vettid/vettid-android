// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.items

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The plan's drafts for the agent rule editor, word for word (ANDROID-PLAN 0.1.116 §4 AA4); not approved yet: the owner approves them. */
@RunWith(RobolectricTestRunner::class)
class AgentRuleTextsTest {
    private val r = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun theDraftsAreThePlansWords() {
        val drafts = listOf(
            R.string.items_agent_rule_critical to "Critical items are never shared with agents, whatever their tags.",
            R.string.items_agent_rule_mode_ask to "Ask me about each item",
            R.string.items_agent_rule_mode_ask_note to "When an item gets one of these tags, you're asked before the agent can use it.",
            R.string.items_agent_rule_mode_auto to "Include tagged items automatically",
            R.string.items_agent_rule_mode_auto_note to "Items with these tags can be used without asking you first. Using an item gives the agent a result worked out from it, not the item itself.",
            R.string.items_agent_rule_reveal to "A use returns a code computed from the value and data the agent chooses. For a short or guessable value, such as a PIN or a common password, the agent could find the value from one such code. Share only items whose values are long and random, such as API keys.",
            R.string.items_agent_rule_status_ttl to "Status lifetime",
            R.string.items_agent_rule_status_ttl_note to "If you remove this permission, services the agent showed it to may still accept it for up to this long, plus 1 minute. Shorter is safer; the agent then checks with your vault more often.",
            R.string.items_agent_rule_ttl_1m to "1 minute",
            R.string.items_agent_rule_ttl_5m to "5 minutes",
            R.string.items_agent_rule_ttl_15m to "15 minutes",
            R.string.items_agent_rule_ttl_1h to "1 hour",
            R.string.items_agent_rule_delete to "Stop sharing",
            R.string.items_agent_rule_delete_body to "It can no longer use them from now on. Services it showed this rule to may accept it for up to %1\$s.",
            R.string.items_agent_password_title to "Confirm with your credential password",
            R.string.items_agent_password_body to "Giving %1\$s a permission is signed with your credential, so only you can do it. Enter your credential password.",
            R.string.items_agent_password_confirm to "Confirm",
            R.string.items_agent_rule_cancel to "Cancel",
            R.string.items_agent_alarm to "Permissions can't be changed while a credential alarm is open",
            R.string.items_agent_limit_grants to "An agent can have at most 32 permissions, its share rules included. Remove one first.",
            R.string.items_agent_limit_subject to "An agent can have at most 64 rules.",
            R.string.items_agent_limit_rules to "Your vault holds at most 512 share rules.",
            R.string.items_agent_not_paired to "This agent is no longer paired.",
            R.string.items_agent_saved to "Rule saved",
            R.string.items_agent_rule_title to "Share with %1\$s",
            R.string.items_agent_rule_per_note to "Up to %1\$s uses an hour and %2\$s a day. Past that, each use asks you.",
            R.string.items_agent_rule_uses_note to "Stop after %1\$s uses of each item.",
        )
        for ((id, text) in drafts) assertEquals(text, r.getString(id))
        assertEquals("2 items: 1 will be asked about, 1 included now", r.getQuantityString(R.plurals.items_agent_rule_preview, 2, 2, 1, 1))
    }
}
