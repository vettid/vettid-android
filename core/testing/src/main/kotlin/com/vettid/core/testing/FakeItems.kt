package com.vettid.core.testing

import com.vettid.core.data.items.DraftField
import com.vettid.core.data.items.FieldValue
import com.vettid.core.data.items.ItemChecks
import com.vettid.core.data.items.ItemDetail
import com.vettid.core.data.items.ItemDraft
import com.vettid.core.data.items.ItemFieldView
import com.vettid.core.data.items.ItemSummary
import com.vettid.core.data.items.ItemsManager
import com.vettid.core.data.items.ItemsRepository
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.items.ShareEffect
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * TEST ONLY. An in-memory vault of items for ViewModel tests: [stored] holds every item with its values; calls are
 * recorded in [calls]; [fail] makes the next call of a name throw; [password] is the credential password critical
 * operations need (a wrong one is `bad_password`). `get` reports each item's `size` (VAULT-MESSAGING 0.21.0) unless
 * [reportSize] is off (an older vault); an update keeps the stored value of a kept field and the kept notes, and the
 * last draft sent is [lastDraft]. [effect] is what [shareEffect] answers.
 */
@Suppress("TooManyFunctions")
class FakeItems : ItemsRepository {
    val calls = mutableListOf<String>()
    val fail = mutableMapOf<String, VaultFailure>()
    var password = "correct horse"
    val stored = linkedMapOf<String, ItemDetail>()
    private var next = 1
    var reportSize = true
    var lastDraft: ItemDraft? = null
    var effect = ShareEffect()
    val effectAsked = mutableListOf<List<String>>()

    override val items = MutableStateFlow<List<ItemSummary>>(emptyList())
    override val load = MutableStateFlow(ListLoad.NOT_LOADED)

    fun add(d: ItemDetail) {
        stored[d.itemId] = d
        publish()
    }

    private fun publish() {
        items.value = stored.values.map { it.summary }
    }

    private fun call(name: String) {
        calls += name
        fail.remove(name)?.let { throw it }
    }

    private fun checkPassword(pw: String?) {
        if (pw != password) throw VaultFailure(FailureKind.BAD_PASSWORD, "bad_password")
    }

    private fun find(id: String): ItemDetail = stored[id] ?: throw VaultFailure(FailureKind.NOT_FOUND, "not_found")

    override suspend fun refresh() {
        call("refresh")
        load.value = ListLoad.LOADED
        publish()
    }

    override suspend fun get(itemId: String): ItemDetail {
        call("get")
        val d = find(itemId)
        val size = ItemChecks.check(ItemDraft.of(d.copy(revealed = true))).size.takeIf { reportSize }
        return d.hidden().copy(size = size)
    }

    override suspend fun reveal(itemId: String): ItemDetail {
        call("reveal")
        return find(itemId).copy(revealed = true)
    }

    override suspend fun revealCritical(itemId: String, password: String): ItemDetail {
        call("revealCritical")
        checkPassword(password)
        return find(itemId).copy(revealed = true)
    }

    private fun detail(id: String, version: Long, d: ItemDraft): ItemDetail = ItemDetail(
        itemId = id,
        version = version,
        name = d.name.trim(),
        category = d.category,
        sensitivity = d.sensitivity,
        template = d.template,
        tags = ItemChecks.normalizeTags(d.tags) ?: emptyList(),
        fields = d.fields.mapIndexed { i, f: DraftField -> ItemFieldView(f.fieldId ?: "f${i + 1}", f.label.trim(), f.kind, f.value) },
        notes = d.notes.ifEmpty { null },
        revealed = true,
    )

    override suspend fun create(draft: ItemDraft): String {
        call("create")
        lastDraft = draft
        val id = "01ITEM${next++}"
        stored[id] = detail(id, 1, draft)
        publish()
        return id
    }

    override suspend fun createCritical(draft: ItemDraft, password: String): String {
        call("createCritical")
        lastDraft = draft
        checkPassword(password)
        val id = "01CRIT${next++}"
        stored[id] = detail(id, 1, draft.copy(sensitivity = Sensitivity.CRITICAL))
        publish()
        return id
    }

    override suspend fun update(itemId: String, version: Long, draft: ItemDraft): Long {
        call("update")
        lastDraft = draft
        val cur = find(itemId)
        if (cur.version != version) throw VaultFailure(FailureKind.CONFLICT, "conflict")
        stored[itemId] = detail(itemId, version + 1, ItemsManager.merged(draft, cur).copy(sensitivity = cur.sensitivity))
        publish()
        return version + 1
    }

    override suspend fun updateCritical(itemId: String, version: Long, draft: ItemDraft, password: String): Long {
        call("updateCritical")
        lastDraft = draft
        checkPassword(password)
        val cur = find(itemId)
        if (cur.version != version) throw VaultFailure(FailureKind.CONFLICT, "conflict")
        stored[itemId] = detail(itemId, version + 1, ItemsManager.merged(draft, cur).copy(sensitivity = Sensitivity.CRITICAL))
        publish()
        return version + 1
    }

    override suspend fun delete(itemId: String, sensitivity: Sensitivity, password: String?) {
        call("delete")
        if (sensitivity == Sensitivity.CRITICAL) checkPassword(password)
        find(itemId)
        stored.remove(itemId)
        publish()
    }

    override suspend fun setTags(itemId: String, version: Long, tags: List<String>): Long {
        call("setTags")
        val cur = find(itemId)
        stored[itemId] = cur.copy(version = version + 1, tags = ItemChecks.normalizeTags(tags) ?: cur.tags)
        publish()
        return version + 1
    }

    override suspend fun setSensitivity(itemId: String, version: Long, from: Sensitivity, to: Sensitivity, password: String?): Long {
        call("setSensitivity")
        if (from == Sensitivity.CRITICAL || to == Sensitivity.CRITICAL) checkPassword(password)
        val cur = find(itemId)
        stored[itemId] = cur.copy(version = version + 1, sensitivity = to)
        publish()
        return version + 1
    }

    override suspend fun shareEffect(itemId: String?, version: Long?, sensitivity: Sensitivity, tags: List<String>): ShareEffect {
        call("shareEffect")
        effectAsked += ItemChecks.normalizeTags(tags) ?: tags
        return effect
    }

    companion object {
        /** A sample item with string values. */
        fun item(
            id: String,
            name: String,
            sensitivity: Sensitivity = Sensitivity.DATA,
            tags: List<String> = emptyList(),
            category: String = "other",
            fields: List<Pair<String, String>> = listOf("Number" to "123"),
        ): ItemDetail = ItemDetail(
            itemId = id,
            version = 1,
            name = name,
            category = category,
            sensitivity = sensitivity,
            tags = tags,
            fields = fields.mapIndexed { i, (l, v) -> ItemFieldView("f${i + 1}", l, "text", FieldValue.Text(v)) },
            revealed = true,
        )
    }
}
