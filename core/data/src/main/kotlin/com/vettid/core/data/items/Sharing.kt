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

        /** A vault holds at most 64 share rules per subject (limit `share_rules_subject`) and 512 in all (`share_rules`), §10.12. */
        const val MAX_RULES_PER_SUBJECT = 64
        const val MAX_RULES = 512

        fun of(r: ShareRule): RuleDraft = RuleDraft(
            r.connectionId ?: "", r.tags, r.match, r.mode, r.uses, r.expiresAt, r.includeExisting, r.ruleId, r.version,
        )
    }

    /** The rule's checks before it is sent (§10.12). */
    fun valid(now: Instant): Boolean = tags.size in 1..MAX_TAGS && tags.none { it.startsWith("@") } &&
        (uses == null || uses in 1..MAX_USES) &&
        (expiresAt == null || (expiresAt.isAfter(now) && expiresAt.isBefore(now.plusSeconds(MAX_EXPIRY_DAYS * DAY_SECONDS))))
}

/**
 * Where share rules for one subject cover the same tags or items (§10.12). The vault keeps no precedence between them:
 * each rule matches, asks about and includes items on its own (one inclusion state and one grant per rule and item), so
 * an item covered by several rules is readable while any of them includes it — an `auto` rule shares it even when an
 * `ask` rule for the same connection would ask, and the most permissive uses and end apply in effect.
 */
object RuleOverlaps {
    /** Each rule's id → the other rules that name one of its tags or hold one of its items (included or pending). */
    fun of(rules: List<ShareRule>): Map<String, List<ShareRule>> = rules.associate { r ->
        r.ruleId to rules.filter { o -> o.ruleId != r.ruleId && overlap(r.tags, r.included + r.pending, o) }
    }

    /**
     * The saved rules (other than the draft's own) that cover the draft's [tags] or one of [itemIds] (the dry run's
     * matches), in the order given.
     */
    fun forDraft(ruleId: String?, tags: List<String>, itemIds: Collection<String>, rules: List<ShareRule>): List<ShareRule> =
        rules.filter { o -> o.ruleId != ruleId && overlap(tags, itemIds, o) }

    /** The tags [a] shares with [b]. */
    fun sharedTags(a: List<String>, b: ShareRule): List<String> = a.filter { it in b.tags }

    /** The items of [itemIds] that [rule] already includes or asks about. */
    fun sharedItems(itemIds: Collection<String>, rule: ShareRule): Set<String> =
        itemIds.filter { it in rule.included || it in rule.pending }.toSet()

    private fun overlap(tags: List<String>, items: Collection<String>, o: ShareRule): Boolean =
        tags.any { it in o.tags } || items.any { it in o.included || it in o.pending }
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
    /**
     * A received grant's field labels, as the connection's vault described them (`grant.list`, VAULT-MESSAGING 0.21.0
     * §10.12; not refreshed): what it holds before it is fetched. Empty for a given grant and from an older vault.
     */
    val labels: List<FieldLabel> = emptyList(),
) {
    val active: Boolean get() = state == STATE_ACTIVE

    /** Uses left of a counted grant; null when not counted. */
    val usesLeft: Int? get() = uses?.let { maxOf(0, it - used) }

    companion object {
        const val STATE_ACTIVE = "active"
    }
}

data class GrantLists(val given: List<GrantView>, val received: List<GrantView>, val requested: List<GrantAsk> = emptyList())

/** One entry of a request this vault made (§10.12 `grant.request`, as sent): an `item` or a `category` [ref], the asker's [label]. */
data class GrantAskEntry(val kind: String, val ref: String, val label: String? = null)

/**
 * A request this vault made of a connection (`grant.list`'s `requested`, §10.12 as 0.21.0 pins it): its [entries]
 * exactly as sent, and [state] `pending`, `granted` (some entries granted: they arrive as received grants) or
 * `denied` (denied, or not decided within 7 days).
 */
data class GrantAsk(val requestId: String, val connectionId: String, val entries: List<GrantAskEntry>, val state: String)

/** Who a share rule is for (§10.12 `subject`): a connection or an agent. */
data class ShareSubject(val connectionId: String? = null, val agentId: String? = null)

/** A rule an item would gain (§10.7 Dry run): asked about (`ask`) or included at once (`auto`); [usable] for a critical item. */
data class EffectShare(val ruleId: String, val subject: ShareSubject, val mode: ShareMode, val usable: Boolean = false)

/** A rule an item would leave, where it is [state] `pending` or `included` (§10.7 Dry run). */
data class EffectWithdrawal(val ruleId: String, val subject: ShareSubject, val state: String)

/**
 * What saving an item (or its tags) would do to sharing, as the vault plans it (`item.put` / `item.tag` with
 * `dry_run`, VAULT-MESSAGING 0.21.0 §10.7): the rules it gains ([shares]) and leaves ([withdrawals]); [version] the
 * item's current one (null for a new item).
 */
data class ShareEffect(
    val version: Long? = null,
    val shares: List<EffectShare> = emptyList(),
    val withdrawals: List<EffectWithdrawal> = emptyList(),
)

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
