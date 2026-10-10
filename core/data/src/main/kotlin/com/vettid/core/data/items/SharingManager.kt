package com.vettid.core.data.items

import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.envelope.Timestamps
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.vaultGuard
import com.vettid.core.vault.GrantFetched
import com.vettid.core.vault.TagPage
import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Tags and sharing (VAULT-MESSAGING §10.8 tags, §10.12 share rules and grants). Every failure is a
 * [VaultFailure]; a tag a share rule names cannot be deleted ([FailureKind.IN_USE]).
 */
@Suppress("TooManyFunctions")
interface SharingRepository {
    /** The tag registry and the tags in use, as last listed. */
    val tags: StateFlow<TagRegistry?>

    /**
     * The colours the registry stores (§10.8 `color`), by tag, as last listed: kept while the registry is read again
     * after a `tag.changed`, empty once the vault locks. Tags without one show their hash colour.
     */
    val colors: StateFlow<Map<String, String>>

    suspend fun refreshTags(): TagRegistry

    /**
     * Creates or replaces a registry entry (`tag.set`); [description] at most 256 bytes. `tag.set` clears what it is
     * not sent, so the entry's stored icon goes with it unchanged.
     */
    suspend fun setTag(tag: String, description: String? = null, color: String? = null)

    /**
     * The member's choice of colour for [tag] (`tag.set`, owner decision 2026-10-09), its description and icon kept;
     * after a `conflict` (another device changed the registry) the registry is read again and the choice sent once
     * more. Never for an `@` tag (`@profile` is always the member's gold).
     */
    suspend fun setTagColor(tag: String, color: String)

    /**
     * Stores a colour for every tag that has none (owner decision 2026-10-09): [next] picks the next tag and its
     * `#rrggbb` from the registry, null when none is left; each is sent with `tag.set` (the registry's version, the
     * tag's description and icon kept), one at a time. A `conflict` re-reads the registry and goes on with what is
     * stored there (a colour another device stored is kept); any other failure stops, the rest waiting for the next
     * time. Returns how many were stored.
     */
    suspend fun assignColors(next: (List<TagView>) -> Pair<String, String>?): Int

    /** Tags just put on an item: one the registry has no colour for makes it read again (and so coloured). */
    fun onTagsUsed(tags: List<String>)

    /** Renames [from] to [into] (a merge of one tag, §10.8); [dryRun] only says what it would do. */
    suspend fun renameTag(from: String, into: String, dryRun: Boolean): TagChange

    /** Removes the tag from the registry and every item (`tag.delete`); `in_use` while a share rule names it. */
    suspend fun deleteTag(tag: String, dryRun: Boolean): TagChange

    /** Every share rule (of [connectionId] when given), sorted by `rule_id`. */
    suspend fun rules(connectionId: String? = null): List<ShareRule>

    /** What [draft] would match (`share.rule.set{dry_run: true}`), changing nothing. */
    suspend fun preview(draft: RuleDraft): RulePreview

    suspend fun saveRule(draft: RuleDraft): ShareRule

    suspend fun deleteRule(ruleId: String)

    /** Grants given and received (`grant.list`). */
    suspend fun grants(): GrantLists

    /** Revokes a grant given, or gives up one received. */
    suspend fun revokeGrant(grantId: String)

    /**
     * Asks a connection for "your <category>" (§10.12 `grant.request`, a category entry the other member answers
     * with one of their items); [label] (≤ 128 bytes) describes it, [reason] (≤ 256 bytes) says why. Returns the request id.
     */
    suspend fun requestGrant(connectionId: String, category: String, label: String?, reason: String?): String

    /** Fetches what a connection shares with the member under a received grant (each fetch may count a use). */
    suspend fun fetchShared(grantId: String): FetchOutcome
}

/** The vault calls behind [SharingManager]. */
@Suppress("TooManyFunctions")
interface SharingOps {
    suspend fun tagList(after: String?): TagPage

    suspend fun tagSet(version: Long, tag: String, color: String?, icon: String?, description: String?): Long

    suspend fun tagMerge(version: Long, from: List<String>, into: String, dryRun: Boolean): JsonObject

    suspend fun tagDelete(version: Long, tag: String, dryRun: Boolean): JsonObject

    suspend fun ruleList(connectionId: String?, after: String?): JsonObject

    suspend fun ruleSet(body: JsonObject): JsonObject

    suspend fun ruleDelete(ruleId: String)

    suspend fun grantList(): JsonObject

    suspend fun grantRevoke(grantId: String)

    suspend fun grantRequest(connectionId: String, items: JsonArray, reason: String?): String

    suspend fun grantFetch(grantId: String): GrantFetched
}

/** [SharingOps] over the vault client. */
@Suppress("TooManyFunctions")
internal class VaultSharingOps(private val api: VaultApi) : SharingOps {
    override suspend fun tagList(after: String?): TagPage = api.tagList(after = after)

    override suspend fun tagSet(version: Long, tag: String, color: String?, icon: String?, description: String?): Long =
        api.tagSet(version, tag, color = color, icon = icon, description = description)

    override suspend fun tagMerge(version: Long, from: List<String>, into: String, dryRun: Boolean): JsonObject =
        api.tagMerge(version, from, into, dryRun)

    override suspend fun tagDelete(version: Long, tag: String, dryRun: Boolean): JsonObject = api.tagDelete(version, tag, dryRun)

    override suspend fun ruleList(connectionId: String?, after: String?): JsonObject = api.shareRuleList(connectionId, after = after)

    override suspend fun ruleSet(body: JsonObject): JsonObject = api.shareRuleSet(body)

    override suspend fun ruleDelete(ruleId: String) = api.shareRuleDelete(ruleId)

    override suspend fun grantList(): JsonObject = api.grantList()

    override suspend fun grantRevoke(grantId: String) = api.grantRevoke(grantId)

    override suspend fun grantRequest(connectionId: String, items: JsonArray, reason: String?): String =
        api.grantRequest(connectionId, items, reason = reason)

    override suspend fun grantFetch(grantId: String): GrantFetched = api.grantFetch(grantId)
}

/**
 * [SharingRepository] over [SharingOps]. [onItemsChanged] re-reads the item list after a tag change (the items'
 * tags change with it, without `item.changed` notices, §10.8).
 */
@Suppress("TooManyFunctions")
class SharingManager(
    private val ops: suspend () -> SharingOps,
    private val onItemsChanged: suspend () -> Unit = {},
) : SharingRepository {
    private val registry = MutableStateFlow<TagRegistry?>(null)
    override val tags: StateFlow<TagRegistry?> = registry.asStateFlow()
    private val stored = MutableStateFlow<Map<String, String>>(emptyMap())
    override val colors: StateFlow<Map<String, String>> = stored.asStateFlow()

    fun clear() {
        registry.value = null
        stored.value = emptyMap()
    }

    private fun publish(r: TagRegistry) {
        registry.value = r
        stored.value = r.tags.mapNotNull { t -> t.color?.let { t.tag to it } }.toMap()
    }

    /**
     * `tag.changed` from another device: the registry is re-read when next shown (and at once by the root of the UI,
     * which keeps the tags' colours current); the colours stay until then.
     */
    fun onEvent(m: VaultMessage) {
        if (m.type == "sync.event" && VaultJson.str(m.body, "kind") == "tag.changed") registry.value = null
    }

    override suspend fun refreshTags(): TagRegistry {
        val out = mutableListOf<TagView>()
        var version = 0L
        var after: String? = null
        do {
            val page = vaultGuard { ops().tagList(after) }
            version = page.version
            out += page.tags.map { TagView(it.tag, it.items, it.rules, it.color, it.description, it.icon) }
            after = page.next?.takeIf { page.tags.isNotEmpty() }
        } while (after != null && out.size < MAX_TAGS_LISTED)
        return TagRegistry(version, out).also { publish(it) }
    }

    private suspend fun version(): Long = (registry.value ?: refreshTags()).version

    override suspend fun setTag(tag: String, description: String?, color: String?) {
        val n = ItemChecks.normalizeTag(tag, reserved = false) ?: throw VaultFailure(FailureKind.OTHER, CODE_BAD_REQUEST)
        val icon = registry.value?.tags?.firstOrNull { it.tag == n }?.icon
        vaultGuard { ops().tagSet(version(), n, color, icon, description?.takeIf { it.isNotBlank() }) }
        refreshTags()
    }

    override suspend fun setTagColor(tag: String, color: String) {
        if (tag.startsWith("@")) throw VaultFailure(FailureKind.OTHER, CODE_BAD_REQUEST)
        try {
            putColor(registry.value ?: refreshTags(), tag, color)
        } catch (e: VaultFailure) {
            if (e.kind != FailureKind.CONFLICT) throw e
            putColor(refreshTags(), tag, color)
        }
        refreshTags()
    }

    /** `tag.set` of [tag] in [r] with [color], everything else of its entry as stored; the registry's new version. */
    private suspend fun putColor(r: TagRegistry, tag: String, color: String): Long {
        val t = r.tags.firstOrNull { it.tag == tag } ?: TagView(tag)
        return vaultGuard { ops().tagSet(r.version, tag, color, t.icon, t.description) }
    }

    override suspend fun assignColors(next: (List<TagView>) -> Pair<String, String>?): Int {
        var r = registry.value ?: refreshTags()
        var count = 0
        var conflicts = 0
        while (count < MAX_ASSIGNED) {
            val (tag, color) = next(r.tags) ?: break
            if (tag.startsWith("@")) break
            r = try {
                val v = putColor(r, tag, color)
                count++
                val tags = r.tags.map { if (it.tag == tag) it.copy(color = color) else it }
                // Without a version in the answer the registry is read again for the next one.
                if (v > 0) TagRegistry(v, tags).also { publish(it) } else refreshTags()
            } catch (e: VaultFailure) {
                if (e.kind != FailureKind.CONFLICT || ++conflicts > MAX_CONFLICTS) break
                refreshTags()
            }
        }
        return count
    }

    override fun onTagsUsed(tags: List<String>) {
        val known = stored.value
        if (tags.any { !it.startsWith("@") && it !in known }) registry.value = null
    }

    override suspend fun renameTag(from: String, into: String, dryRun: Boolean): TagChange {
        val n = ItemChecks.normalizeTag(into, reserved = false) ?: throw VaultFailure(FailureKind.OTHER, CODE_BAD_REQUEST)
        if (from.startsWith("@")) throw VaultFailure(FailureKind.OTHER, CODE_BAD_REQUEST)
        val o = vaultGuard { ops().tagMerge(version(), listOf(from), n, dryRun) }
        if (!dryRun) afterTagChange()
        return change(o)
    }

    override suspend fun deleteTag(tag: String, dryRun: Boolean): TagChange {
        if (tag.startsWith("@")) throw VaultFailure(FailureKind.OTHER, CODE_BAD_REQUEST)
        val o = vaultGuard { ops().tagDelete(version(), tag, dryRun) }
        if (!dryRun) afterTagChange()
        return change(o)
    }

    private suspend fun afterTagChange() {
        runCatching { refreshTags() }
        runCatching { onItemsChanged() }
    }

    override suspend fun rules(connectionId: String?): List<ShareRule> {
        val out = mutableListOf<ShareRule>()
        var after: String? = null
        do {
            val page = vaultGuard { ops().ruleList(connectionId, after) }
            val list = arr(page, "rules").mapNotNull(::rule)
            out += list
            after = VaultJson.str(page, "next")?.takeIf { list.isNotEmpty() }
        } while (after != null)
        return out
    }

    override suspend fun preview(draft: RuleDraft): RulePreview {
        val o = vaultGuard { ops().ruleSet(ruleBody(draft, dryRun = true)) }
        val matches = arr(o, "matches").mapNotNull { m ->
            val id = VaultJson.str(m, "item_id") ?: return@mapNotNull null
            RuleMatch(
                id, VaultJson.str(m, "name") ?: "", VaultJson.str(m, "category") ?: "other",
                Sensitivity.of(VaultJson.str(m, "sensitivity")), VaultJson.str(m, "state"),
                outcome = VaultJson.str(m, "outcome"), askRuleId = VaultJson.str(m, "ask_rule_id"),
            )
        }
        return RulePreview(matches, (VaultJson.long(o, "total") ?: matches.size.toLong()).toInt())
    }

    override suspend fun saveRule(draft: RuleDraft): ShareRule {
        if (!draft.valid(Instant.now())) throw VaultFailure(FailureKind.OTHER, CODE_BAD_REQUEST)
        val o = vaultGuard { ops().ruleSet(ruleBody(draft, dryRun = false)) }
        runCatching { refreshTags() }
        return rule(o) ?: throw VaultFailure(FailureKind.OTHER, "unreadable_rule")
    }

    override suspend fun deleteRule(ruleId: String) {
        vaultGuard { ops().ruleDelete(ruleId) }
        runCatching { refreshTags() }
    }

    override suspend fun grants(): GrantLists {
        val o = vaultGuard { ops().grantList() }
        return GrantLists(
            given = arr(o, "given").mapNotNull { grant(it, GrantDirection.GIVEN) },
            received = arr(o, "received").mapNotNull { grant(it, GrantDirection.RECEIVED) },
            requested = arr(o, "requested").mapNotNull { r ->
                val id = VaultJson.str(r, "request_id") ?: return@mapNotNull null
                val conn = VaultJson.str(r, "connection_id") ?: return@mapNotNull null
                // §10.12 (0.21.0): the entries exactly as sent; the label is the member's own description.
                val entries = arr(r, "items").mapNotNull { e ->
                    val kind = VaultJson.str(e, "kind") ?: return@mapNotNull null
                    GrantAskEntry(kind, VaultJson.str(e, "ref") ?: "", VaultJson.str(e, "label")?.takeIf { it.isNotBlank() })
                }
                GrantAsk(id, conn, entries, VaultJson.str(r, "state") ?: "pending")
            },
        )
    }

    override suspend fun revokeGrant(grantId: String) = vaultGuard { ops().grantRevoke(grantId) }

    override suspend fun requestGrant(connectionId: String, category: String, label: String?, reason: String?): String {
        val l = label?.trim()?.takeIf { it.isNotEmpty() }
        val r = reason?.trim()?.takeIf { it.isNotEmpty() }
        val tooLong = (l?.toByteArray()?.size ?: 0) > MAX_LABEL || (r?.toByteArray()?.size ?: 0) > MAX_REASON
        if (!ItemChecks.isValidCategory(category) || tooLong) {
            throw VaultFailure(FailureKind.OTHER, CODE_BAD_REQUEST)
        }
        val items = kotlinx.serialization.json.buildJsonArray {
            add(
                buildJsonObject {
                    put("kind", "category")
                    put("ref", category)
                    l?.let { put("label", it) }
                },
            )
        }
        return vaultGuard { ops().grantRequest(connectionId, items, r) }
    }

    @Suppress("ReturnCount")
    override suspend fun fetchShared(grantId: String): FetchOutcome {
        val r = vaultGuard { ops().grantFetch(grantId) }
        r.error?.let { return FetchOutcome.Refused(it, r.retryAfter?.takeIf { s -> s > 0 }) }
        val pt = r.content ?: return FetchOutcome.Refused("unavailable")
        return try {
            FetchOutcome.Shared(content(pt, r.usesLeft))
        } finally {
            Bytes.wipe(pt)
        }
    }

    companion object {
        private const val CODE_BAD_REQUEST = "bad_request"
        private const val MAX_TAGS_LISTED = 4_096

        /** At most this many colours stored in one go (the registry holds 512 entries, §10.8). */
        private const val MAX_ASSIGNED = 512

        /** Version conflicts tolerated in one go (another device assigning at the same time) before giving up. */
        private const val MAX_CONFLICTS = 8

        /** §10.12: a request entry's label at most 128 bytes, its reason at most 256. */
        const val MAX_LABEL = 128
        const val MAX_REASON = 256

        private fun arr(o: JsonObject, k: String): List<JsonObject> = (o[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

        private fun strings(o: JsonObject, k: String): List<String> =
            (o[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

        private fun bool(o: JsonObject, k: String): Boolean? =
            (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

        private fun instant(s: String?): Instant? = s?.let {
            try {
                Instant.parse(it)
            } catch (_: DateTimeParseException) {
                null
            }
        }

        fun change(o: JsonObject): TagChange = TagChange(
            version = VaultJson.long(o, "version") ?: 0,
            items = (VaultJson.long(o, "items") ?: 0).toInt(),
            rules = (VaultJson.long(o, "rules") ?: 0).toInt(),
            shares = arr(o, "shares").mapNotNull { s ->
                val r = VaultJson.str(s, "rule_id") ?: return@mapNotNull null
                val i = VaultJson.str(s, "item_id") ?: return@mapNotNull null
                SharePreview(r, i, ShareMode.of(VaultJson.str(s, "mode")))
            },
            sharesTotal = (VaultJson.long(o, "shares_total") ?: 0).toInt(),
        )

        /** A `<share_rule>` (§10.12); null without its id. */
        fun rule(o: JsonObject): ShareRule? {
            val id = VaultJson.str(o, "rule_id") ?: return null
            val subject = o["subject"] as? JsonObject
            return ShareRule(
                ruleId = id,
                version = VaultJson.long(o, "version") ?: 0,
                connectionId = subject?.let { VaultJson.str(it, "connection_id") },
                agentId = subject?.let { VaultJson.str(it, "agent_id") },
                tags = strings(o, "tags"),
                match = TagMatch.of(VaultJson.str(o, "match")),
                mode = ShareMode.of(VaultJson.str(o, "mode")),
                uses = VaultJson.long(o, "uses")?.toInt(),
                expiresAt = instant(VaultJson.str(o, "expires_at")),
                includeExisting = bool(o, "include_existing") ?: true,
                perHour = VaultJson.long(o, "per_hour")?.toInt(),
                perDay = VaultJson.long(o, "per_day")?.toInt(),
                included = strings(o, "included"),
                pending = strings(o, "pending"),
                declined = strings(o, "declined"),
                createdAt = instant(VaultJson.str(o, "created_at")),
                updatedAt = instant(VaultJson.str(o, "updated_at")),
            )
        }

        /** `share.rule.set`'s body (§10.12): a connection subject, read access; `per_hour`/`per_day` only when set (0.23.0). */
        fun ruleBody(d: RuleDraft, dryRun: Boolean): JsonObject = buildJsonObject {
            d.ruleId?.let { put("rule_id", it) }
            if (d.ruleId != null) d.version?.let { put("version", it) }
            putJsonObject("subject") { put("connection_id", d.connectionId) }
            putJsonArray("tags") { d.tags.forEach { add(JsonPrimitive(it)) } }
            put("match", d.match.wire)
            put("access", "read")
            put("mode", d.mode.wire)
            d.uses?.let { put("uses", it) }
            d.perHour?.let { put("per_hour", it) }
            d.perDay?.let { put("per_day", it) }
            d.expiresAt?.let { put("expires_at", Timestamps.formatMillis(it)) }
            put("include_existing", d.includeExisting)
            if (dryRun) put("dry_run", true)
        }

        @Suppress("ReturnCount")
        fun grant(o: JsonObject, direction: GrantDirection): GrantView? {
            val id = VaultJson.str(o, "grant_id") ?: return null
            return GrantView(
                grantId = id,
                connectionId = VaultJson.str(o, "connection_id") ?: return null,
                direction = when (VaultJson.str(o, "direction")) {
                    "given" -> GrantDirection.GIVEN
                    "received" -> GrantDirection.RECEIVED
                    else -> direction
                },
                itemRef = VaultJson.str(o, "ref") ?: "",
                name = VaultJson.str(o, "name") ?: VaultJson.str(o, "label") ?: "",
                category = VaultJson.str(o, "category") ?: "other",
                fields = (o["fields"] as? JsonArray)?.let { strings(o, "fields") },
                label = VaultJson.str(o, "label"),
                ruleId = VaultJson.str(o, "rule_id"),
                uses = VaultJson.long(o, "uses")?.toInt(),
                used = (VaultJson.long(o, "used") ?: 0).toInt(),
                expiresAt = instant(VaultJson.str(o, "expires_at")),
                state = VaultJson.str(o, "state") ?: GrantView.STATE_ACTIVE,
                createdAt = instant(VaultJson.str(o, "created_at")),
                labels = labels(o),
                perHour = limit(o, "per_hour"),
                perDay = limit(o, "per_day"),
            )
        }

        /** A grant's `limits` member [k] (0.23.0 §10.12: `{per_hour?, per_day?}`); null when absent. */
        private fun limit(o: JsonObject, k: String): Int? = (o["limits"] as? JsonObject)?.let { VaultJson.long(it, k)?.toInt() }

        /** `labels: [{field_id, label, kind}]` (§10.12: a received grant's, a grant request entry's); malformed entries are skipped. */
        fun labels(o: JsonObject): List<FieldLabel> = arr(o, "labels").mapNotNull { l ->
            val id = VaultJson.str(l, "field_id") ?: return@mapNotNull null
            FieldLabel(id, VaultJson.str(l, "label") ?: "", VaultJson.str(l, "kind") ?: FieldKinds.TEXT)
        }

        /** A fetched content (`{item_id, version, name, category, fields: [{field_id, label, kind, value}], notes?}`, §10.12). */
        fun content(pt: ByteArray, usesLeft: Long?): SharedContent {
            val o = try {
                VaultJson.parseObject(pt)
            } catch (e: IllegalArgumentException) {
                throw VaultFailure(FailureKind.OTHER, "unreadable_value", cause = e)
            }
            return SharedContent(
                itemId = VaultJson.str(o, "item_id") ?: "",
                name = VaultJson.str(o, "name") ?: "",
                category = VaultJson.str(o, "category") ?: "other",
                fields = arr(o, "fields").map { f ->
                    val kind = VaultJson.str(f, "kind") ?: FieldKinds.TEXT
                    val label = VaultJson.str(f, "label") ?: ""
                    ItemFieldView(VaultJson.str(f, "field_id"), label, kind, ItemsManager.fieldValue(kind, f["value"]))
                },
                notes = VaultJson.str(o, "notes"),
                usesLeft = usesLeft,
            )
        }
    }
}
