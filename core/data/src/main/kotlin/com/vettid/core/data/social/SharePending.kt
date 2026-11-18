package com.vettid.core.data.social

import com.vettid.core.data.items.ShareRule
import com.vettid.core.data.items.SharingManager
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.VaultJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * The member's pending share decisions (VAULT-MESSAGING §10.12), over the vault's pages so that it
 * can be tested without a vault. Since 0.21.0 the vault lists them (`share.pending.list`) and decides a mixed choice
 * in one change (`share.decide{include, decline}`, sent by [SocialManager.decideShare]).
 */
internal object SharePending {
    /** `share.pending.list`'s largest page (§10.12: 1–500), and at most the vault's 4,096 pending items. */
    const val PAGE = 500
    private const val MAX_PENDING = 4_096

    private fun JsonObject.arr(k: String): List<JsonObject> = (this[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    /**
     * The decisions waiting, one per rule: from [pendingPage] (`share.pending.list` from `after`, 0.21.0), with each
     * rule's tags from [rulePage] (`share.rule.list`; none if it fails). A failed `share.pending.list`, `unsupported_type`
     * and `bad_request` included, is thrown: the app requires the vault's current release, which lists them.
     */
    suspend fun decisions(
        pendingPage: suspend (after: String?) -> JsonObject,
        rulePage: suspend (after: String?) -> JsonObject,
        t: Instant,
    ): List<Approval.ShareDecision> {
        val pending = pages(pendingPage, "pending", MAX_PENDING)
        val rules = try {
            pages(rulePage, "rules", Int.MAX_VALUE).mapNotNull(SharingManager::rule)
        } catch (_: VaultFailure) {
            emptyList()
        }
        val tags = rules.associate { it.ruleId to it.tags }
        return pending.groupBy { VaultJson.str(it, "rule_id") ?: "" }.filterKeys { it.isNotEmpty() }.map { (rule, entries) ->
            val subject = entries.first()["subject"] as? JsonObject
            Approval.ShareDecision(
                ruleId = rule,
                subjectConnectionId = subject?.let { VaultJson.str(it, "connection_id") },
                subjectAgentId = subject?.let { VaultJson.str(it, "agent_id") },
                items = entries.mapNotNull(ApprovalParser::shareItem).distinctBy { it.itemId },
                reason = null,
                receivedAt = entries.mapNotNull { ApprovalParser.instant(VaultJson.str(it, "at")) }.maxOrNull() ?: t,
                tags = tags[rule].orEmpty(),
                rules = sameSubject(rules, subject),
            )
        }
    }

    /** The rules of [subject] (a connection or an agent), to name the one that asks (§10.12, 0.23.0). */
    private fun sameSubject(rules: List<ShareRule>, subject: JsonObject?): List<ShareRule> {
        val c = subject?.let { VaultJson.str(it, "connection_id") }
        val a = subject?.let { VaultJson.str(it, "agent_id") }
        if (c == null && a == null) return emptyList()
        return rules.filter { (c != null && it.connectionId == c) || (a != null && it.agentId == a) }
    }

    /** Every page's [key] entries (`next` passed back as `after`), at most [max]. */
    private suspend fun pages(page: suspend (String?) -> JsonObject, key: String, max: Int): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var after: String? = null
        do {
            val p = page(after)
            val entries = p.arr(key)
            out += entries
            after = VaultJson.str(p, "next")?.takeIf { it.isNotEmpty() && entries.isNotEmpty() }
        } while (after != null && out.size < max)
        return out
    }
}
