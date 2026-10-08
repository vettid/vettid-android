package com.vettid.core.data.items

import com.vettid.core.crypto.Bytes
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.vaultGuard
import com.vettid.core.vault.Item
import com.vettid.core.vault.ItemContent
import com.vettid.core.vault.ItemField
import com.vettid.core.vault.ItemPage
import com.vettid.core.vault.ItemRef
import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.format.DateTimeParseException

/** The vault calls behind [ItemsManager] (§10.7), so that it can be tested without a vault. */
@Suppress("TooManyFunctions")
interface ItemsOps {
    suspend fun list(after: String?, limit: Int): ItemPage

    suspend fun get(itemId: String): Item

    suspend fun reveal(itemId: String): Item

    /** The opened `values_sealed` plaintext, `{"fields": [{field_id, value}], "notes"?}`; the caller wipes it. */
    suspend fun revealCritical(password: String, itemId: String): ByteArray

    suspend fun put(content: ItemContent, sensitivity: String?, tags: List<String>?, itemId: String?, version: Long?): ItemRef

    suspend fun putCritical(password: String, content: ItemContent, tags: List<String>?, itemId: String?, version: Long?): ItemRef

    suspend fun tag(itemId: String, version: Long, tags: List<String>): Long

    /** `item.put{dry_run: true, item_id?, version?, sensitivity?, tags?}` (§10.7 Dry run, 0.21.0): never the content. */
    suspend fun putDryRun(itemId: String?, version: Long?, sensitivity: String?, tags: List<String>?): JsonObject

    suspend fun sensitivity(itemId: String, version: Long, sensitivity: String, password: String?): Long

    suspend fun delete(itemId: String, password: String?)
}

/** [ItemsOps] over the vault client. */
internal class VaultItemsOps(private val api: VaultApi) : ItemsOps {
    override suspend fun list(after: String?, limit: Int): ItemPage = api.itemList(after = after, limit = limit)

    override suspend fun get(itemId: String): Item = api.itemGet(itemId)

    override suspend fun reveal(itemId: String): Item = api.itemReveal(itemId)

    override suspend fun revealCritical(password: String, itemId: String): ByteArray {
        val r = api.itemRevealCritical(password, itemId)
        return try {
            r.plaintext()
        } finally {
            r.wipe()
        }
    }

    override suspend fun put(content: ItemContent, sensitivity: String?, tags: List<String>?, itemId: String?, version: Long?): ItemRef =
        api.itemPut(content, sensitivity, tags, itemId, version)

    override suspend fun putCritical(
        password: String,
        content: ItemContent,
        tags: List<String>?,
        itemId: String?,
        version: Long?,
    ): ItemRef =
        api.itemPutCritical(password, content, tags, itemId, version)

    override suspend fun tag(itemId: String, version: Long, tags: List<String>): Long = api.itemTag(itemId, version, tags)

    override suspend fun putDryRun(itemId: String?, version: Long?, sensitivity: String?, tags: List<String>?): JsonObject =
        api.itemPutDryRun(itemId, version, sensitivity, tags)

    override suspend fun sensitivity(itemId: String, version: Long, sensitivity: String, password: String?): Long =
        api.itemSensitivity(itemId, version, sensitivity, password)

    override suspend fun delete(itemId: String, password: String?) = api.itemDelete(itemId, password)
}

/**
 * [ItemsRepository] over [ItemsOps]. The list (metadata of every item, at most 2,000) is kept in memory while the
 * vault is open and re-read when another device changes an item (`sync.event` `item.changed`, `item.deleted`,
 * `tag.changed`, §10.1). Values are never kept here: an edit leaves the stored ones in the vault (§10.7 Kept values).
 */
@Suppress("TooManyFunctions")
class ItemsManager(
    private val scope: CoroutineScope,
    private val ops: suspend () -> ItemsOps,
) : ItemsRepository {
    private val list = MutableStateFlow<List<ItemSummary>>(emptyList())
    private val loadState = MutableStateFlow(ListLoad.NOT_LOADED)
    override val items: StateFlow<List<ItemSummary>> = list.asStateFlow()
    override val load: StateFlow<ListLoad> = loadState.asStateFlow()
    private var pending: Job? = null

    /** Forgets everything (the vault locked, or this phone was wiped). */
    fun clear() {
        pending?.cancel()
        list.value = emptyList()
        loadState.value = ListLoad.NOT_LOADED
    }

    /** The vault opened: the list is read in the background. */
    fun refreshQuietly() {
        scope.launch { runCatching { refresh() } }
    }

    /** Follows the owner's other devices (§10.1): a change of an item or of the tags re-reads the list, once per burst. */
    fun onEvent(m: VaultMessage) = onEvent(m.type, m.body)

    fun onEvent(type: String, body: JsonObject) {
        if (type != "sync.event") return
        when (val kind = VaultJson.str(body, "kind")) {
            "item.changed", "item.deleted", "tag.changed" -> {
                if (kind == "item.deleted") {
                    VaultJson.str(body, "item_id")?.let { id -> list.update { l -> l.filterNot { it.itemId == id } } }
                }
                pending?.cancel()
                pending = scope.launch {
                    delay(EVENT_DEBOUNCE_MS)
                    runCatching { refresh() }
                }
            }
        }
    }

    override suspend fun refresh() {
        loadState.value = ListLoad.LOADING
        try {
            val out = mutableListOf<ItemSummary>()
            var after: String? = null
            do {
                val page = vaultGuard { ops().list(after, PAGE) }
                page.items.mapTo(out) { summary(it) }
                after = page.next?.takeIf { it.isNotEmpty() && page.items.isNotEmpty() }
            } while (after != null && out.size <= ItemChecks.MAX_ITEMS)
            list.value = out
            loadState.value = ListLoad.LOADED
        } catch (e: VaultFailure) {
            loadState.value = ListLoad.FAILED
            throw e
        }
    }

    override suspend fun get(itemId: String): ItemDetail = detail(vaultGuard { ops().get(itemId) }).also(::remember)

    override suspend fun reveal(itemId: String): ItemDetail = detail(vaultGuard { ops().reveal(itemId) }).copy(revealed = true)

    override suspend fun revealCritical(itemId: String, password: String): ItemDetail {
        // The metadata (names, labels, kinds) first: the values come only as {field_id, value}.
        val meta = detail(vaultGuard { ops().get(itemId) })
        val pt = vaultGuard { ops().revealCritical(password, itemId) }
        return try {
            openValues(meta, pt)
        } finally {
            Bytes.wipe(pt)
        }
    }

    override suspend fun create(draft: ItemDraft): String {
        require(draft.sensitivity != Sensitivity.CRITICAL) { "a critical item is created with the password" }
        val (content, tags) = content(draft)
        val ref = vaultGuard { ops().put(content, draft.sensitivity.wire, tags, null, null) }
        afterWrite()
        return ref.itemId
    }

    override suspend fun createCritical(draft: ItemDraft, password: String): String {
        val (content, tags) = content(draft.copy(sensitivity = Sensitivity.CRITICAL))
        val ref = vaultGuard { ops().putCritical(password, content, tags, null, null) }
        afterWrite()
        return ref.itemId
    }

    override suspend fun update(itemId: String, version: Long, draft: ItemDraft): Long {
        // Every `item.get` of a secret item from a 0.21.0 vault has `size`: without it, the vault keeps no values.
        val d = if (draft.keeps && draft.base?.bytes == null) {
            merged(draft, detail(vaultGuard { ops().reveal(itemId) }))
        } else {
            draft
        }
        val (content, tags) = content(d)
        val ref = vaultGuard { ops().put(content, null, tags, itemId, version) }
        afterWrite()
        return ref.version
    }

    @Suppress("ReturnCount")
    override suspend fun updateCritical(itemId: String, version: Long, draft: ItemDraft, password: String): Long {
        val d = draft.copy(sensitivity = Sensitivity.CRITICAL)
        // A critical item without `size` is kept by an older vault, or not written since the upgrade. Notes kept without
        // a kept field would pass an older vault, which ignores `keep_notes`, and lose them: the values are opened first.
        val unsure = d.keeps && d.base?.bytes == null
        if (unsure && d.fields.none { it.kept }) return putCriticalMerged(itemId, version, d, password)
        val (content, tags) = content(d)
        val ref = try {
            vaultGuard { ops().putCritical(password, content, tags, itemId, version) }
        } catch (e: VaultFailure) {
            if (!(unsure && e.kind == FailureKind.OTHER && e.code == CODE_BAD_REQUEST)) throw e
            return putCriticalMerged(itemId, version, d, password)
        }
        afterWrite()
        return ref.version
    }

    /** For a vault before 0.21.0: the stored values opened with the same password, merged, and sent in full. */
    private suspend fun putCriticalMerged(itemId: String, version: Long, d: ItemDraft, password: String): Long {
        val full = merged(d, revealCritical(itemId, password))
        val (content, tags) = content(full)
        val ref = vaultGuard { ops().putCritical(password, content, tags, itemId, version) }
        afterWrite()
        return ref.version
    }

    override suspend fun delete(itemId: String, sensitivity: Sensitivity, password: String?) {
        if (sensitivity == Sensitivity.CRITICAL && password == null) throw VaultFailure(FailureKind.BAD_PASSWORD, "password_required")
        vaultGuard { ops().delete(itemId, password.takeIf { sensitivity == Sensitivity.CRITICAL }) }
        list.update { l -> l.filterNot { it.itemId == itemId } }
    }

    override suspend fun setTags(itemId: String, version: Long, tags: List<String>): Long {
        val n = ItemChecks.normalizeTags(tags) ?: throw VaultFailure(FailureKind.OTHER, CODE_BAD_REQUEST)
        val v = vaultGuard { ops().tag(itemId, version, n) }
        afterWrite()
        return v
    }

    override suspend fun setSensitivity(itemId: String, version: Long, from: Sensitivity, to: Sensitivity, password: String?): Long {
        val credential = from == Sensitivity.CRITICAL || to == Sensitivity.CRITICAL
        if (credential && password == null) throw VaultFailure(FailureKind.BAD_PASSWORD, "password_required")
        val v = vaultGuard { ops().sensitivity(itemId, version, to.wire, password.takeIf { credential }) }
        afterWrite()
        return v
    }

    override suspend fun shareEffect(itemId: String?, version: Long?, sensitivity: Sensitivity, tags: List<String>): ShareEffect {
        val n = ItemChecks.normalizeTags(tags) ?: throw VaultFailure(FailureKind.OTHER, CODE_BAD_REQUEST)
        val o = vaultGuard {
            ops().putDryRun(itemId, version.takeIf { itemId != null }, sensitivity.wire.takeIf { itemId == null }, n)
        }
        return effect(o)
    }

    /** A detail read updates its row (the list may be older). */
    private fun remember(d: ItemDetail) {
        list.update { l -> l.map { if (it.itemId == d.itemId && it.version <= d.version) d.summary else it } }
    }

    private suspend fun afterWrite() {
        runCatching { refresh() }
    }

    /**
     * The content and tags `item.put` sends (§10.7): names trimmed, tags normalised, a kept field without `value` and
     * kept notes as `keep_notes` (0.21.0); refused here if the vault would refuse it.
     */
    private fun content(d: ItemDraft): Pair<ItemContent, List<String>> {
        val check = ItemChecks.check(d)
        if (!check.ok) throw VaultFailure(FailureKind.OTHER, CODE_BAD_REQUEST)
        val fields = d.fields.map { f ->
            val kept = f.kept && f.fieldId != null
            ItemField(f.fieldId, f.label.trim(), f.kind, if (kept) null else valueElement(f.value))
        }
        val content = ItemContent(
            name = d.name.trim(),
            category = d.category,
            template = d.template,
            fields = fields,
            notes = d.notes.takeIf { it.isNotEmpty() && !d.keepNotes },
            keepNotes = d.keepNotes,
        )
        return content to (check.tags ?: emptyList())
    }

    companion object {
        /** `item.list`'s largest page (§10.7: 1–500). */
        const val PAGE = 500
        private const val EVENT_DEBOUNCE_MS = 400L
        private const val CODE_BAD_REQUEST = "bad_request"

        fun valueElement(v: FieldValue): JsonElement = when (v) {
            is FieldValue.Text -> JsonPrimitive(v.text)
            is FieldValue.Address -> JsonObject(
                v.address.parts().filter { it.second.isNotEmpty() }.associate { it.first to JsonPrimitive(it.second) },
            )
        }

        /** A field value from the vault: a string, or an address object; anything else is shown as its JSON. */
        fun fieldValue(kind: String, e: JsonElement?): FieldValue? = when (e) {
            null -> null
            is JsonPrimitive -> if (e.isString) FieldValue.Text(e.content) else FieldValue.Text(e.toString())
            is JsonObject -> if (kind == FieldKinds.ADDRESS) {
                val parts = e.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
                FieldValue.Address(AddressValue.of(parts))
            } else {
                FieldValue.Text(e.toString())
            }
            else -> FieldValue.Text(e.toString())
        }

        fun summary(i: Item): ItemSummary = ItemSummary(
            itemId = i.itemId,
            version = i.version,
            name = i.name,
            category = i.category,
            sensitivity = Sensitivity.of(i.sensitivity),
            template = i.template,
            tags = i.tags,
            labels = i.fields.map { it.label },
            updatedAt = instant(i.updatedAt),
        )

        fun detail(i: Item): ItemDetail {
            val s = Sensitivity.of(i.sensitivity)
            val hasValues = s == Sensitivity.DATA || i.fields.any { it.value != null } || i.notes != null
            return ItemDetail(
                itemId = i.itemId,
                version = i.version,
                name = i.name,
                category = i.category,
                sensitivity = s,
                template = i.template,
                tags = i.tags,
                fields = i.fields.map { f -> ItemFieldView(f.fieldId, f.label, f.kind, fieldValue(f.kind, f.value)) },
                notes = i.notes,
                hasNotes = i.hasNotes || !i.notes.isNullOrEmpty(),
                createdAt = instant(i.createdAt),
                updatedAt = instant(i.updatedAt),
                revealed = hasValues,
                size = i.size?.takeIf { it in 0..Int.MAX_VALUE }?.toInt(),
            )
        }

        /**
         * [d] with its kept values taken from [revealed] (the item's values, opened), for a vault that keeps none
         * (before 0.21.0). A field the item no longer has keeps "" (or `{}`), as the vault would.
         */
        fun merged(d: ItemDraft, revealed: ItemDetail): ItemDraft {
            val values = revealed.fields.associate { it.fieldId to it.value }
            return d.copy(
                fields = d.fields.map { f ->
                    if (!f.kept) return@map f
                    when (val v = values[f.fieldId]) {
                        is FieldValue.Address -> f.copy(address = v.address, kept = false)
                        is FieldValue.Text -> f.copy(text = v.text, kept = false)
                        null -> f.copy(kept = false)
                    }
                },
                notes = if (d.keepNotes) revealed.notes.orEmpty() else d.notes,
                keepNotes = false,
                base = null,
            )
        }

        /** A dry run's answer (§10.7: `{version?, shares, withdrawals}`); entries without `rule_id` are skipped. */
        fun effect(o: JsonObject): ShareEffect {
            fun arr(k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            fun subject(e: JsonObject) = (e["subject"] as? JsonObject).let { s ->
                ShareSubject(s?.let { VaultJson.str(it, "connection_id") }, s?.let { VaultJson.str(it, "agent_id") })
            }
            return ShareEffect(
                version = VaultJson.long(o, "version"),
                shares = arr("shares").mapNotNull { e ->
                    val r = VaultJson.str(e, "rule_id") ?: return@mapNotNull null
                    val usable = (e["usable"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"
                    EffectShare(r, subject(e), ShareMode.of(VaultJson.str(e, "mode")), usable)
                },
                withdrawals = arr("withdrawals").mapNotNull { e ->
                    val r = VaultJson.str(e, "rule_id") ?: return@mapNotNull null
                    EffectWithdrawal(r, subject(e), VaultJson.str(e, "state") ?: "included")
                },
            )
        }

        /** Merges a critical item's opened values (`{"fields": [{field_id, value}], "notes"?}`, §10.7) into its metadata. */
        fun openValues(meta: ItemDetail, plaintext: ByteArray): ItemDetail {
            val o = try {
                VaultJson.parseObject(plaintext)
            } catch (e: IllegalArgumentException) {
                throw VaultFailure(FailureKind.OTHER, "unreadable_values", cause = e)
            }
            val values = (o["fields"] as? JsonArray).orEmpty().mapNotNull { e ->
                val f = e as? JsonObject ?: return@mapNotNull null
                VaultJson.str(f, "field_id")?.let { it to f["value"] }
            }.toMap()
            return meta.copy(
                fields = meta.fields.map { f -> f.copy(value = fieldValue(f.kind, values[f.fieldId]) ?: FieldValue.Text("")) },
                notes = VaultJson.str(o, "notes"),
                revealed = true,
            )
        }

        private fun instant(s: String?): Instant? = s?.let {
            try {
                Instant.parse(it)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
