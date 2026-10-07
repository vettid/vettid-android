package com.vettid.core.data.items

import java.time.Instant

/** A tag of the registry or in use (§10.8 `tag.list`): how many items carry it and which share rules name it. */
data class TagView(
    val tag: String,
    val items: Int = 0,
    val rules: List<String> = emptyList(),
    val color: String? = null,
    val description: String? = null,
) {
    val reserved: Boolean get() = tag.startsWith("@")
}

/** The tag registry as last listed; [version] is what `tag.set`, `.delete` and `.merge` name (§10.1). */
data class TagRegistry(val version: Long, val tags: List<TagView>)

/** An item a tag change would share, and how (§10.8 `tag.merge`'s `shares`). */
data class SharePreview(val ruleId: String, val itemId: String, val mode: ShareMode)

/**
 * What a tag delete or merge does, or would do with `dry_run` (§10.8): [items] changed, [rules] changed, and the
 * items it shares ([shares], at most what fits; [sharesTotal] counts them all).
 */
data class TagChange(
    val version: Long,
    val items: Int,
    val rules: Int = 0,
    val shares: List<SharePreview> = emptyList(),
    val sharesTotal: Int = 0,
)

/** A share rule's mode (§10.12): `ask` (the default: the member decides each new item) or `auto`. */
enum class ShareMode(val wire: String) {
    ASK("ask"),
    AUTO("auto"),
    ;

    companion object {
        fun of(w: String?): ShareMode = if (w == AUTO.wire) AUTO else ASK
    }
}

/** Whether a rule needs one of its tags on an item (`any`, the default) or all of them. */
enum class TagMatch(val wire: String) {
    ANY("any"),
    ALL("all"),
    ;

    companion object {
        fun of(w: String?): TagMatch = if (w == ALL.wire) ALL else ANY
    }
}

/** A share rule (§10.12): a connection (or agent) may read the items whose tags match. */
data class ShareRule(
    val ruleId: String,
    val version: Long,
    val connectionId: String?,
    val agentId: String? = null,
    val tags: List<String>,
    val match: TagMatch = TagMatch.ANY,
    val mode: ShareMode = ShareMode.ASK,
    val uses: Int? = null,
    val expiresAt: Instant? = null,
    val includeExisting: Boolean = true,
    val included: List<String> = emptyList(),
    val pending: List<String> = emptyList(),
    val declined: List<String> = emptyList(),
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    /** Whether an item with [itemTags] matches the rule's tags (§10.12; whether it is in force is the vault's). */
    fun matches(itemTags: List<String>): Boolean = when (match) {
        TagMatch.ANY -> tags.any { it in itemTags }
        TagMatch.ALL -> tags.all { it in itemTags }
    }
}

/** A share rule as the member edits it; [ruleId] null for a new one. */
data class RuleDraft(
    val connectionId: String,
    val tags: List<String> = emptyList(),
    val match: TagMatch = TagMatch.ANY,
    val mode: ShareMode = ShareMode.ASK,
    val uses: Int? = null,
    val expiresAt: Instant? = null,
    val includeExisting: Boolean = true,
    val ruleId: String? = null,
    val version: Long? = null,
) {
    companion object {
        /** A rule names 1–16 tags, none reserved (§10.12). */
        const val MAX_TAGS = 16
        const val MAX_USES = 10_000

        /** At most 3,650 days ahead (§10.12). */
        const val MAX_EXPIRY_DAYS = 3_650L
        private const val DAY_SECONDS = 86_400L

        fun of(r: ShareRule): RuleDraft = RuleDraft(
            r.connectionId ?: "", r.tags, r.match, r.mode, r.uses, r.expiresAt, r.includeExisting, r.ruleId, r.version,
        )
    }

    /** The rule's checks before it is sent (§10.12). */
    fun valid(now: Instant): Boolean = tags.size in 1..MAX_TAGS && tags.none { it.startsWith("@") } &&
        (uses == null || uses in 1..MAX_USES) &&
        (expiresAt == null || (expiresAt.isAfter(now) && expiresAt.isBefore(now.plusSeconds(MAX_EXPIRY_DAYS * DAY_SECONDS))))
}

/** An item a rule matches, from `share.rule.set{dry_run}` (§10.12); [state] for a replaced rule. */
data class RuleMatch(val itemId: String, val name: String, val category: String, val sensitivity: Sensitivity, val state: String? = null)

data class RulePreview(val matches: List<RuleMatch>, val total: Int)

/** Which way a grant goes (§10.12). */
enum class GrantDirection { GIVEN, RECEIVED }

/** A grant (§10.12): one connection may read one item (or some of its fields). */
data class GrantView(
    val grantId: String,
    val connectionId: String,
    val direction: GrantDirection,
    val itemRef: String,
    val name: String,
    val category: String,
    val fields: List<String>? = null,
    val label: String? = null,
    val ruleId: String? = null,
    val uses: Int? = null,
    val used: Int = 0,
    val expiresAt: Instant? = null,
    /** `active`, `used`, `expired` or `revoked`. */
    val state: String = STATE_ACTIVE,
    val createdAt: Instant? = null,
) {
    val active: Boolean get() = state == STATE_ACTIVE

    /** Uses left of a counted grant; null when not counted. */
    val usesLeft: Int? get() = uses?.let { maxOf(0, it - used) }

    companion object {
        const val STATE_ACTIVE = "active"
    }
}

data class GrantLists(val given: List<GrantView>, val received: List<GrantView>, val requested: List<GrantAsk> = emptyList())

/** A request this vault made of a connection (`grant.list`'s `requested`): [state] as the vault reports it. */
data class GrantAsk(val requestId: String, val connectionId: String, val labels: List<String>, val state: String)

/** What a connection shared with the member, as fetched (§10.12): read-only, the connection's own data. */
data class SharedContent(
    val itemId: String,
    val name: String,
    val category: String,
    val fields: List<ItemFieldView>,
    val notes: String?,
    val usesLeft: Long?,
) {
    override fun toString(): String = "SharedContent($itemId)"
}

/** A fetch's outcome: the content, or the connection's vault's refusal (`revoked`, `expired`, `exhausted`, `unavailable`, `not_found`). */
sealed interface FetchOutcome {
    data class Shared(val content: SharedContent) : FetchOutcome

    data class Refused(val reason: String) : FetchOutcome
}
