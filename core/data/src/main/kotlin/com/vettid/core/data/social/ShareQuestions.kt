package com.vettid.core.data.social

/**
 * One question per item and subject (VAULT-MESSAGING 0.23.0 §10.12 Overlapping rules): an item pending in several
 * rules of the same connection (or agent) is asked once. Since 0.23.0 the vault applies a `share.decide` of the item
 * in one rule to every rule of that subject where it waits (and a decline also where it is included), so the app shows
 * it in the first of those rules (by `rule_id`, the order `share.pending.list` keeps) and notes the others in
 * [ShareItem.alsoIn]. A rule left with no item of its own is not shown. With a vault before 0.23.0 the item comes back
 * in the next rule after the first answer, as that vault still asks per rule.
 */
object ShareQuestions {
    fun dedupe(approvals: List<Approval>): List<Approval> {
        val shares = approvals.filterIsInstance<Approval.ShareDecision>()
        if (shares.size < 2) return approvals
        // subject → item → the rules (in rule_id order) where it waits.
        val where = mutableMapOf<String, MutableMap<String, MutableList<String>>>()
        shares.sortedBy { it.ruleId }.forEach { d ->
            val s = subjectOf(d) ?: return@forEach
            d.items.forEach { i -> where.getOrPut(s) { mutableMapOf() }.getOrPut(i.itemId) { mutableListOf() } += d.ruleId }
        }
        return approvals.mapNotNull { a ->
            if (a !is Approval.ShareDecision) return@mapNotNull a
            val s = subjectOf(a) ?: return@mapNotNull a
            val items = a.items.mapNotNull { i ->
                val rules = where[s]?.get(i.itemId).orEmpty()
                when {
                    rules.isEmpty() || rules.first() == a.ruleId -> i.copy(alsoIn = rules.drop(1))
                    else -> null
                }
            }
            if (items.isEmpty()) null else a.copy(items = items)
        }
    }

    private fun subjectOf(d: Approval.ShareDecision): String? =
        d.subjectConnectionId?.let { "c:$it" } ?: d.subjectAgentId?.let { "a:$it" }
}
