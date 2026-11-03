// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.devices

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The plan's drafts for AA4's page and pairing step, word for word (ANDROID-PLAN 0.1.116); not approved yet: the owner approves them. */
@RunWith(RobolectricTestRunner::class)
class AgentRuleTextsTest {
    private val r = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun theDraftsAreThePlansWords() {
        val drafts = listOf(
            R.string.devices_rules_title to "What it may use",
            R.string.devices_rules_add to "Add a rule",
            R.string.devices_rules_limit to "An agent can have at most 64 rules.",
            R.string.devices_rule_ask to "Asks you about each item",
            R.string.devices_rule_auto to "Includes tagged items automatically",
            R.string.devices_rule_saved to "Rule saved",
            R.string.pair_paired_body to "It can't use any of your items yet. Choose the items it may use: you decide per tag, and it never gets critical items.",
            R.string.pair_choose to "Choose what it may use",
            R.string.pair_later to "Later",
            com.vettid.core.ui.R.string.core_ui_agent_ways_warning to "On this vault, the agent may receive the values of the items it uses, and its app may keep them in its history.",
        )
        for ((id, text) in drafts) assertEquals(text, r.getString(id))
        assertEquals("Up to 60 uses an hour", r.getQuantityString(R.plurals.devices_rule_per_hour, 60, 60))
        assertEquals("3 waiting for you", r.getString(R.string.devices_rule_waiting, 3))
    }
}
