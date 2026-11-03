package com.vettid.core.data.devices

import kotlinx.serialization.Serializable

/**
 * The editor of an agent's share rule (ANDROID-PLAN 0.1.116 §4 AA4): [ruleId] null for a new rule. The route is here, not
 * in a feature module, because it is opened from the agent's page (`:feature:devices`) and registered by the share-rule
 * editor it reuses (`:feature:items`); features depend only on `:core`.
 */
@Serializable
data class AgentRuleRoute(val agentId: String, val ruleId: String? = null) {
    companion object {
        const val ARG_AGENT = "agentId"
        const val ARG_RULE = "ruleId"
    }
}
