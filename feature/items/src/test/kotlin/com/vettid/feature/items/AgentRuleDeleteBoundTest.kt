package com.vettid.feature.items

import com.vettid.core.data.items.RuleDraft
import com.vettid.core.data.items.ShareRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The delete text names the stored rule's status lifetime at its worst (review Low C): an unreadable or out-of-range one
 * reads as the longest there, while the draft keeps the value as sent (the member chooses).
 */
class AgentRuleDeleteBoundTest {
    private fun rule(ttl: Int?, bound: Int?) =
        ShareRule("r1", 1, connectionId = null, agentId = "a1", tags = listOf("api"), statusTtl = ttl, statusTtlBound = bound)

    @Test
    fun theStoredRulesWorstCaseNotTheDraftsChoice() {
        val unreadable = rule(ttl = null, bound = 3_600)
        assertNull("the draft leaves the choice to the member", RuleDraft.of(unreadable).statusTtl)
        assertEquals(3_600, deleteStatusTtl(unreadable, RuleDraft.of(unreadable)))
        val huge = rule(ttl = 99_999, bound = 3_600)
        assertEquals(3_600, deleteStatusTtl(huge, RuleDraft.of(huge).copy(statusTtl = 60)))
        // A rule the vault does not hold yet: the draft's.
        assertEquals(300, deleteStatusTtl(null, RuleDraft.of(rule(300, 300))))
        // Not sent: the vault's default, as before.
        assertNull(deleteStatusTtl(rule(null, null), RuleDraft.of(rule(null, null))))
    }
}
