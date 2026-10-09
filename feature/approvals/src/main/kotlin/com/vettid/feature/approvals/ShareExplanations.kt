package com.vettid.feature.approvals

import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.ShareItem

/**
 * Why a share question's item is asked and what an answer does (VAULT-MESSAGING 0.23.0 §10.12 Overlapping rules):
 * apps MUST explain an item an `ask` rule holds ("Also covered by your “medical” rule, which asks you first"), say
 * that an item already shared by another rule of the subject stops being shared if declined, and the answer counts
 * for every rule of the subject where the item waits (one question per item and subject).
 */
object ShareExplanations {
    sealed interface Line {
        /** `ask_rule_id`: the `ask` rule of the subject that holds the item. */
        data class AsksFirst(val ruleId: String) : Line

        /** Another rule of the subject where the item waits too (the answer counts there as well). */
        data class AlsoCovered(val ruleId: String) : Line

        /** `shared`: another rule of the subject already shares it; declining stops that. */
        data object AlreadyShared : Line
    }

    fun of(i: ShareItem, d: Approval.ShareDecision): List<Line> = buildList {
        val asker = i.askRuleId?.takeIf { it != d.ruleId }
        asker?.let { add(Line.AsksFirst(it)) }
        i.alsoIn.filter { it != asker && it != d.ruleId }.forEach { add(Line.AlsoCovered(it)) }
        if (i.shared) add(Line.AlreadyShared)
    }
}
