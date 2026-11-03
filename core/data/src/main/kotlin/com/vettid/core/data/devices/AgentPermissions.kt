package com.vettid.core.data.devices

import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.VaultOpException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.format.DateTimeParseException

/** Whether a grant asks the member each time or is allowed within its limits (VAULT-MESSAGING §10.11 `approval`). */
enum class GrantApproval {
    ASK,
    AUTO,

    /** A word this app does not know (a newer vault): nothing is said about how it is allowed, and nothing is written back. */
    UNKNOWN,
}

/**
 * One of an agent's grants as `leash.grant.list` shows it (VAULT-MESSAGING §10.11; ANDROID-PLAN 0.1.116 §4 AA3), without
 * its `delegation` and `sig`, which the app never reads. [scope] is the vault's own text: a scope this app has no label
 * for is still listed, and can be removed ([PermissionScopes.known]). [connections] is empty for "all connections" (and
 * for every scope that takes none). A grant that does not read is still listed: an empty [scope] is shown as "Another
 * permission", and a null [grantId] (the vault sent none) cannot be removed ([removable]).
 */
data class AgentGrant(
    val grantId: String?,
    val scope: String,
    val version: Long,
    val approval: GrantApproval,
    val perHour: Long,
    val perDay: Long,
    val expiresAt: Instant?,
    val connections: List<String>,
    /** Seconds a service shown this grant may still accept it after it is revoked, before the 60 s skew (§10.11). */
    val statusTtl: Long,
) {
    /** Only a grant with an id can be revoked. */
    val removable: Boolean get() = grantId != null

    /** A grant names connections, which are the member's business: nothing is printed but the type. */
    override fun toString(): String = "AgentGrant"
}

/** The agent's grants that are not share rules, and whether the vault paused it (`leash.grant.list{agent_id}`). */
data class AgentPermissions(val others: List<AgentGrant>, val suspended: Boolean) {
    override fun toString(): String = "AgentPermissions"
}

/** The scopes of §10.11 and the one that is a share rule. */
object PermissionScopes {
    /** An agent's share rule (AA4): listed under **What it may use**, never under **Other permissions**. */
    const val ITEMS_READ = "items.read"

    /** The nine delegable owner types, which have labels (ANDROID-PLAN 0.1.116 §4 AA3). */
    val known: Set<String> = linkedSetOf(
        "connection.list", "connection.get", "message.send", "message.list", "message.get", "message.read", "profile.get",
        "action.list", "action.invoke",
    )
}

/** `leash.grant.list{agent_id}` bodies as models. A grant that does not read (no id, no scope) is not shown. */
object PermissionWire {
    /** §10.11: `auto` grants default to 60 an hour and 1,000 a day. */
    const val DEFAULT_PER_HOUR = 60L
    const val DEFAULT_PER_DAY = 1_000L

    /** At most this many connection ids of one grant are read (§10.11 allows 64). */
    private const val MAX_CONNECTIONS = 64

    private fun JsonObject.s(k: String) = VaultJson.str(this, k)

    private fun instant(s: String?): Instant? = try {
        s?.let { Instant.parse(it) }
    } catch (_: DateTimeParseException) {
        null
    }

    private fun JsonObject.positive(k: String): Long? =
        (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 }

    /**
     * The grants of [body] for [agentId] that are not share rules, in the vault's order, and whether [agentId] is in
     * `suspended`. A grant of another agent (when it names one) is dropped. **A grant that does not read is not dropped**
     * (it could be a permission nobody sees): it is listed with an empty scope, and without an id when it has none. A body
     * without a `grants` array is a failure: an unreadable answer is never "no permissions".
     */
    fun permissions(body: JsonObject, agentId: String): AgentPermissions {
        val list = body["grants"] as? JsonArray ?: throw VaultFailure(FailureKind.OTHER, "unreadable_grants")
        val others = list.mapNotNull { e ->
            val o = e as? JsonObject
            val agent = o?.s("agent_id")
            val scope = o?.s("scope")?.takeIf { it.isNotEmpty() }
            when {
                agent != null && agent != agentId -> null
                scope == PermissionScopes.ITEMS_READ -> null
                o == null -> unreadable()
                else -> grantOf(o, o.s("grant_id")?.takeIf { it.isNotEmpty() }, scope.orEmpty())
            }
        }
        return AgentPermissions(others, agentId in DeviceWire.suspended(body))
    }

    /** An entry that is not even an object: shown as an unknown permission that cannot be removed. */
    private fun unreadable() = AgentGrant(
        null, "", 0, GrantApproval.UNKNOWN, DEFAULT_PER_HOUR, DEFAULT_PER_DAY, null, emptyList(), DeviceWire.MAX_STATUS_TTL,
    )

    /** A `<grant>` as `leash.grant.issue` answers it; null when it has no `grant_id` or no `scope`. */
    fun issued(o: JsonObject): AgentGrant? {
        val id = o.s("grant_id")?.takeIf { it.isNotEmpty() }
        val scope = o.s("scope")?.takeIf { it.isNotEmpty() }
        return if (id == null || scope == null) null else grantOf(o, id, scope)
    }

    private fun grantOf(o: JsonObject, id: String?, scope: String): AgentGrant {
        // Absent is `ask` (§10.11 default); a word this app does not know is [GrantApproval.UNKNOWN], never claimed as ask.
        val approval = when (o.s("approval")) {
            null, "ask" -> GrantApproval.ASK
            "auto" -> GrantApproval.AUTO
            else -> GrantApproval.UNKNOWN
        }
        val ids = (o["connections"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .filter { it.isNotEmpty() }.take(MAX_CONNECTIONS)
        return AgentGrant(
            grantId = id,
            scope = scope,
            version = (o["version"] as? JsonPrimitive)?.longOrNull ?: 0,
            approval = approval,
            perHour = o.positive("per_hour") ?: DEFAULT_PER_HOUR,
            perDay = o.positive("per_day") ?: DEFAULT_PER_DAY,
            expiresAt = instant(o.s("expires_at")),
            connections = ids,
            statusTtl = DeviceWire.statusTtl(o),
        )
    }
}

/** What changed that an agent's page re-reads for (VAULT-MESSAGING §10.1 `sync.event`). */
sealed interface AgentChange {
    /** `leash.grant.changed`, `leash.grant.revoked` or `leash.agent.suspended` for [agentId]. */
    data class Grants(val agentId: String) : AgentChange

    /** `share.rule.changed` or `share.rule.deleted`: they carry only `rule_id` ([ruleId], null when the event has none). */
    data class Rule(val ruleId: String?) : AgentChange

    companion object {
        private val GRANT_KINDS = setOf("leash.grant.changed", "leash.grant.revoked", "leash.agent.suspended")
        private val RULE_KINDS = setOf("share.rule.changed", "share.rule.deleted")

        /** The change a `sync.event` announces, or null for one an agent's page does not read for. */
        fun of(m: VaultMessage): AgentChange? {
            if (m.type != "sync.event") return null
            val kind = VaultJson.str(m.body, "kind")
            return when (kind) {
                in GRANT_KINDS -> VaultJson.str(m.body, "agent_id")?.takeIf { it.isNotEmpty() }?.let { Grants(it) }
                in RULE_KINDS -> Rule(VaultJson.str(m.body, "rule_id")?.takeIf { it.isNotEmpty() })
                else -> null
            }
        }
    }

    /**
     * Whether the page of [agentId] re-reads. A grant event: when it names this agent. A rule event carries no agent, only
     * `rule_id`: the page re-reads when the rule is one of this agent's or one it does not know (a new rule's id is not
     * known yet). The app knows no rule it can tell is another subject's, so every rule event re-reads (one read each).
     */
    fun concerns(agentId: String): Boolean = when (this) {
        is Grants -> this.agentId == agentId
        is Rule -> true
    }
}

/** What removing a permission came to: the vault removed it, or it was already gone (`not_found`). */
enum class PermissionRemoval { REMOVED, ALREADY_GONE }

/** What resuming an agent came to: the vault resumed it, or it was no longer paused (`not_found`). */
enum class AgentResumption { RESUMED, NOT_PAUSED }

/**
 * The answers to `leash.grant.issue` (ANDROID-PLAN 0.1.116 §4 AA3), for the editor that decision 3a keeps hidden. The
 * caller prompts for the password once on [CredentialLocked] and sends the same request once; a second refusal is
 * [LockedAgain] (`resent` below).
 */
sealed interface GrantIssueResult {
    /** "Permission saved". */
    data class Saved(val grant: AgentGrant) : GrantIssueResult

    /** `credential_locked`: the unlock window is closed. */
    data object CredentialLocked : GrantIssueResult

    /** `credential_locked` again after the prompt and the resend: "Your credential locked again before the permission was saved." */
    data object LockedAgain : GrantIssueResult

    /** `conflict`: "Another device changed this permission. Here is the current one." */
    data object Conflict : GrantIssueResult

    /** `limit` (`agent_grants`): "An agent can have at most 32 permissions, its share rules included. Remove one first." */
    data object Limit : GrantIssueResult

    /** `not_found`: "This agent is no longer paired." */
    data object NotPaired : GrantIssueResult

    /** `bad_request`: "This permission could not be saved: <the field> is not valid." ([field] when the vault named one). */
    data class BadRequest(val field: String?) : GrantIssueResult

    /** `owner_check_required`: the owner-check screen. */
    data object OwnerCheck : GrantIssueResult

    /** `credential_frozen` / `rotation_required` from the unlock: "Permissions can't be changed while a credential alarm is open". */
    data object AlarmOpen : GrantIssueResult

    /** Any other refusal: its own error text. */
    data class Failed(val failure: VaultFailure) : GrantIssueResult
}

/** Maps what the vault answered to `leash.grant.issue` (and to the prompt's `credential.unlock`) to a [GrantIssueResult]. */
object GrantIssueAnswers {
    private const val MAX_FIELD = 32
    private val FIELD = Regex("[a-z][a-z0-9_.]*")

    /** The `<grant>` answer. A grant that does not read is [GrantIssueResult.Failed], never a saved permission. */
    fun saved(body: JsonObject): GrantIssueResult =
        PermissionWire.issued(body)?.let { GrantIssueResult.Saved(it) }
            ?: GrantIssueResult.Failed(VaultFailure(FailureKind.OTHER, "unreadable_grant"))

    /** A refusal of the request itself; [resent] is true when this request already followed one prompt for the password. */
    fun refused(e: VaultFailure, resent: Boolean): GrantIssueResult = when {
        e.kind == FailureKind.CREDENTIAL_LOCKED -> if (resent) GrantIssueResult.LockedAgain else GrantIssueResult.CredentialLocked
        e.kind == FailureKind.CONFLICT -> GrantIssueResult.Conflict
        // The only limit this request meets is the agent's 32 (§10.11); an older vault names none.
        e.kind == FailureKind.LIMIT && (e.limit == null || e.limit.name == "agent_grants") -> GrantIssueResult.Limit
        e.kind == FailureKind.NOT_FOUND -> GrantIssueResult.NotPaired
        e.kind == FailureKind.OWNER_CHECK_REQUIRED -> GrantIssueResult.OwnerCheck
        e.kind == FailureKind.OTHER && e.code == "bad_request" -> GrantIssueResult.BadRequest(field(e))
        else -> GrantIssueResult.Failed(e)
    }

    /**
     * A refusal of the prompt's `credential.unlock`: an open credential alarm or the owner check end the editor's attempt;
     * null for the refusals that keep the prompt (a wrong password, a backoff), which the caller shows on the prompt.
     */
    fun unlockRefused(e: VaultFailure): GrantIssueResult? = when (e.kind) {
        FailureKind.CREDENTIAL_FROZEN, FailureKind.ROTATION_REQUIRED -> GrantIssueResult.AlarmOpen
        FailureKind.OWNER_CHECK_REQUIRED -> GrantIssueResult.OwnerCheck
        else -> null
    }

    /** The `field` a `bad_request` names, only when it reads as a plain member name (it is shown in a sentence). */
    private fun field(e: VaultFailure): String? =
        (e.cause as? VaultOpException)?.body?.let { VaultJson.str(it, "field") }
            ?.takeIf { it.length <= MAX_FIELD && FIELD.matches(it) }
}
