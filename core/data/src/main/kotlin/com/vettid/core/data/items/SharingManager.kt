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

    suspend fun refreshTags(): TagRegistry

    /** Creates a registry entry (`tag.set`); [description] at most 256 bytes. */
    suspend fun setTag(tag: String, description: String? = null, color: String? = null)

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

    suspend fun tagSet(version: Long, tag: String, color: String?, description: String?): Long

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

    override suspend fun tagSet(version: Long, tag: String, color: String?, description: String?): Long =
        api.tagSet(version, tag, color = color, description = description)

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

    fun clear() {
        registry.value = null
    }

    /** `tag.changed` from another device: the registry is re-read when next shown. */
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
            out += page.tags.map { TagView(it.tag, it.items, it.rules, it.color, it.description) }
            after = page.next?.takeIf { page.tags.isNotEmpty() }
        } while (after != null && out.size < MAX_TAGS_LISTED)
        return TagRegistry(version, out).also { registry.value = it }
    }

    private suspend fun version(): Long = (registry.value ?: refreshTags()).version

    override suspend fun setTag(tag: String, description: String?, color: String?) {
        val n = ItemChecks.normalizeTag(tag, reserved = false) ?: throw VaultFailure(FailureKind.OTHER, CODE_BAD_REQUEST)
        vaultGuard { ops().tagSet(version(), n, color, description?.takeIf { it.isNotBlank() }) }
        refreshTags()
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
                val labels = arr(r, "items").mapNotNull { VaultJson.str(it, "label") ?: VaultJson.str(it, "ref") }
                GrantAsk(id, conn, labels, VaultJson.str(r, "state") ?: "")
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
        r.error?.let { return FetchOutcome.Refused(it) }
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
                included = strings(o, "included"),
                pending = strings(o, "pending"),
                declined = strings(o, "declined"),
                createdAt = instant(VaultJson.str(o, "created_at")),
                updatedAt = instant(VaultJson.str(o, "updated_at")),
            )
        }

        /** `share.rule.set`'s body (§10.12): a connection subject, read access. */
        fun ruleBody(d: RuleDraft, dryRun: Boolean): JsonObject = buildJsonObject {
            d.ruleId?.let { put("rule_id", it) }
            if (d.ruleId != null) d.version?.let { put("version", it) }
            putJsonObject("subject") { put("connection_id", d.connectionId) }
            putJsonArray("tags") { d.tags.forEach { add(JsonPrimitive(it)) } }
            put("match", d.match.wire)
            put("access", "read")
            put("mode", d.mode.wire)
            d.uses?.let { put("uses", it) }
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
            )
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
