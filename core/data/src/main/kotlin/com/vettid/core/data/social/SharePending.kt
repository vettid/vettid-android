package com.vettid.core.data.social

import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.VaultJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * The member's pending share decisions and deciding them (VAULT-MESSAGING §10.12), over the vault's pages so that it
 * can be tested without a vault. Since 0.21.0 the vault lists them (`share.pending.list`) and decides a mixed choice
 * in one change (`share.decide{include, decline}`); for an older vault the app reads every rule's `pending` ids and
 * sends the two lists as two calls.
 */
internal object SharePending {
    /** `share.pending.list`'s largest page (§10.12: 1–500), and at most the vault's 4,096 pending items. */
    const val PAGE = 500
    private const val MAX_PENDING = 4_096

    private fun JsonObject.arr(k: String): List<JsonObject> = (this[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.strings(k: String): List<String> =
        (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

    private fun unknownType(e: VaultFailure) = e.kind == FailureKind.NOT_SUPPORTED || e.kind == FailureKind.OTHER && e.code == "bad_request"

    /**
     * The decisions waiting, one per rule: from [pendingPage] (`share.pending.list` from `after`, 0.21.0), with each
     * rule's tags from [rulePage] (`share.rule.list`; none if it fails). A vault that does not know the type (it
     * answers `unsupported_type` or `bad_request`): every rule's `pending` ids, named from [names] (the item list).
     */
    suspend fun decisions(
        pendingPage: suspend (after: String?) -> JsonObject,
        rulePage: suspend (after: String?) -> JsonObject,
        names: Map<String, ShareItem>,
        t: Instant,
    ): List<Approval.ShareDecision> {
        val pending = try {
            pages(pendingPage, "pending", MAX_PENDING)
        } catch (e: VaultFailure) {
            if (!unknownType(e)) throw e
            return fromRules(pages(rulePage, "rules", Int.MAX_VALUE), names, t)
        }
        val tags = try {
            pages(rulePage, "rules", Int.MAX_VALUE).mapNotNull { r -> VaultJson.str(r, "rule_id")?.let { it to r.strings("tags") } }.toMap()
        } catch (_: VaultFailure) {
            emptyMap()
        }
        return pending.groupBy { VaultJson.str(it, "rule_id") ?: "" }.filterKeys { it.isNotEmpty() }.map { (rule, entries) ->
            val subject = entries.first()["subject"] as? JsonObject
            Approval.ShareDecision(
                ruleId = rule,
                subjectConnectionId = subject?.let { VaultJson.str(it, "connection_id") },
                subjectAgentId = subject?.let { VaultJson.str(it, "agent_id") },
                items = entries.mapNotNull { e ->
                    val id = VaultJson.str(e, "item_id") ?: return@mapNotNull null
                    ShareItem(
                        id,
                        VaultJson.str(e, "name") ?: "",
                        VaultJson.str(e, "category") ?: "other",
                        VaultJson.str(e, "sensitivity") ?: "data",
                    )
                }.distinctBy { it.itemId },
                reason = null,
                receivedAt = entries.mapNotNull { ApprovalParser.instant(VaultJson.str(it, "at")) }.maxOrNull() ?: t,
                tags = tags[rule].orEmpty(),
            )
        }
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

    /** Before 0.21.0: every rule's `pending` ids, named from the item list where it has them. */
    private fun fromRules(rules: List<JsonObject>, names: Map<String, ShareItem>, t: Instant): List<Approval.ShareDecision> =
        rules.mapNotNull { r ->
            val id = VaultJson.str(r, "rule_id") ?: return@mapNotNull null
            val pending = r.strings("pending")
            if (pending.isEmpty()) return@mapNotNull null
            val subject = r["subject"] as? JsonObject
            Approval.ShareDecision(
                ruleId = id,
                subjectConnectionId = subject?.let { VaultJson.str(it, "connection_id") },
                subjectAgentId = subject?.let { VaultJson.str(it, "agent_id") },
                items = pending.map { i -> names[i] ?: ShareItem(i, "", "other", "data") },
                reason = null,
                receivedAt = ApprovalParser.instant(VaultJson.str(r, "updated_at")) ?: t,
                tags = r.strings("tags"),
            )
        }

    /**
     * Decides [include] and [decline] of one rule: [both] sends `share.decide{rule_id, include, decline}` (0.21.0, one
     * change). A vault before 0.21.0 refuses that form with `bad_request` and changes nothing: [one] then sends each
     * non-empty list as `share.decide{items, approve}`.
     */
    suspend fun decide(
        include: List<String>,
        decline: List<String>,
        both: suspend () -> Unit,
        one: suspend (items: List<String>, approve: Boolean) -> Unit,
    ) {
        try {
            both()
        } catch (e: VaultFailure) {
            if (e.kind != FailureKind.OTHER || e.code != "bad_request") throw e
            if (include.isNotEmpty()) one(include, true)
            if (decline.isNotEmpty()) one(decline, false)
        }
    }
}
