package com.vettid.feature.items

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.items.AddressValue
import com.vettid.core.data.items.DraftCheck
import com.vettid.core.data.items.DraftField
import com.vettid.core.data.items.FieldKinds
import com.vettid.core.data.items.ItemCategories
import com.vettid.core.data.items.ItemChecks
import com.vettid.core.data.items.ItemDetail
import com.vettid.core.data.items.ItemDraft
import com.vettid.core.data.items.ItemFilter
import com.vettid.core.data.items.ItemSummary
import com.vettid.core.data.items.ItemsRepository
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.items.ShareMode
import com.vettid.core.data.items.ShareRule
import com.vettid.core.data.items.SharingRepository
import com.vettid.core.data.social.ConnectionsRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.VaultLimit
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

// --- the list ---

/** Immutable UI state of the Vault list. */
data class ItemsUiState(
    val items: List<ItemSummary> = emptyList(),
    val filter: ItemFilter = ItemFilter(),
    val load: ListLoad = ListLoad.NOT_LOADED,
    val error: FailureKind? = null,
) {
    val visible: List<ItemSummary> get() = filter.apply(items)

    /** The tags on the member's items, for the tag filter. */
    val tags: List<String> get() = items.flatMap { it.tags }.distinct().sorted()

    /** The categories in use, for the category filter: the recommended ones in their order, then the member's own (§10.7). */
    val categories: List<String>
        get() = items.map { it.category }.toSet().let { used ->
            ItemChecks.RECOMMENDED_CATEGORIES.filter { it in used } + ItemCategories.customs(used)
        }

    val loading: Boolean get() = items.isEmpty() && (load == ListLoad.NOT_LOADED || load == ListLoad.LOADING)
}

/** The Vault list (ANDROID-PLAN §4): every item's metadata, searched and filtered on the phone. */
@HiltViewModel
class ItemsViewModel @Inject constructor(private val items: ItemsRepository) : ViewModel() {
    private val filter = MutableStateFlow(ItemFilter())
    private val error = MutableStateFlow<FailureKind?>(null)

    val uiState: StateFlow<ItemsUiState> = combine(items.items, items.load, filter, error) { list, load, f, e ->
        ItemsUiState(list, f, load, e)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ItemsUiState())

    init {
        refresh()
    }

    fun refresh() {
        error.value = null
        viewModelScope.launch {
            try {
                items.refresh()
            } catch (e: VaultFailure) {
                error.value = e.kind
            }
        }
    }

    fun setQuery(q: String) = filter.update { it.copy(query = q) }

    /** One tag at a time; the selected one again clears it. */
    fun setTag(tag: String?) = filter.update { it.copy(tag = tag?.takeIf { t -> t != it.tag }) }

    fun setCategory(c: String?) = filter.update { it.copy(category = c?.takeIf { x -> x != it.category }) }

    fun setSensitivity(s: Sensitivity?) = filter.update { it.copy(sensitivity = s?.takeIf { x -> x != it.sensitivity }) }

    fun clearFilters() = filter.update { ItemFilter() }
}

// --- one item ---

/** What the detail screen is confirming. */
enum class DetailDialog { DELETE, PROTECTION_PICK, LEAVE_CRITICAL }

/** Immutable UI state of an item's detail. */
data class ItemDetailUiState(
    val itemId: String,
    val item: ItemDetail? = null,
    val loading: Boolean = true,
    /** The item is gone (`not_found`): deleted here or on another device. */
    val missing: Boolean = false,
    val error: FailureKind? = null,
    val busy: Boolean = false,
    /** Masked fields (passwords, one-time code seeds) the member chose to see, by field id. */
    val shown: Set<String> = emptySet(),
    val prompt: PasswordPrompt? = null,
    val dialog: DetailDialog? = null,
    /** The sensitivity the member picked, waiting for the password or the warning. */
    val target: Sensitivity? = null,
    val deleted: Boolean = false,
    /** The limit a `limit` error named (VAULT-MESSAGING 0.21.0 §10.1). */
    val limit: VaultLimit? = null,
) {
    val revealed: Boolean get() = item?.revealed == true
}

/**
 * An item (§10.7): `data` values at once; a `secret` item's values after the member confirms with the phone's lock
 * ([reveal], audited by the vault); a `critical` item's after the credential password ([open]). Values are dropped
 * again when the screen stops ([hide]). Delete and the sensitivity change follow §10.7: a critical item's need the
 * password, and leaving `critical` warns first.
 */
@HiltViewModel
@Suppress("TooManyFunctions")
class ItemDetailViewModel @Inject constructor(saved: SavedStateHandle, private val items: ItemsRepository) : ViewModel() {
    private val id: String = checkNotNull(saved[ItemDetailRoute.ARG])
    private val state = MutableStateFlow(ItemDetailUiState(id))
    val uiState: StateFlow<ItemDetailUiState> = state.asStateFlow()

    init {
        load()
    }

    fun load() {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val d = items.get(id)
                state.update { it.copy(item = d, loading = false, missing = false) }
            } catch (e: VaultFailure) {
                val gone = e.kind == FailureKind.NOT_FOUND
                state.update { it.copy(loading = false, missing = gone, error = e.kind.takeIf { !gone }) }
            }
        }
    }

    /** A secret item's values (`item.reveal`), after the screen confirmed the holder. */
    fun reveal() {
        val item = state.value.item ?: return
        if (item.sensitivity == Sensitivity.CRITICAL) return open()
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val d = items.reveal(id)
                state.update { it.copy(item = d, busy = false) }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false, error = e.kind, missing = e.kind == FailureKind.NOT_FOUND) }
            }
        }
    }

    /** A critical item's values: the credential password first. */
    fun open() = state.update { it.copy(prompt = PasswordPrompt(PasswordPurpose.OPEN), error = null) }

    /** Drops the values (the screen stopped, or the member hid them); a data item keeps its own. */
    fun hide() = state.update { s -> s.copy(item = s.item?.hidden(), shown = emptySet()) }

    fun toggleShown(fieldId: String) = state.update { s ->
        s.copy(shown = if (fieldId in s.shown) s.shown - fieldId else s.shown + fieldId)
    }

    fun setPassword(v: String) = state.update { s -> s.copy(prompt = s.prompt?.copy(password = v, error = null)) }

    fun cancelPassword() = state.update { it.copy(prompt = null, target = null) }

    fun askDelete() = state.update { it.copy(dialog = DetailDialog.DELETE) }

    fun askProtection() = state.update { it.copy(dialog = DetailDialog.PROTECTION_PICK) }

    fun dismissDialog() = state.update { it.copy(dialog = null, target = null) }

    fun dismissError() = state.update { it.copy(error = null, limit = null) }

    /** Delete confirmed: a critical item asks for the password next. */
    fun confirmDelete() {
        val item = state.value.item ?: return
        if (item.sensitivity == Sensitivity.CRITICAL) {
            state.update { it.copy(dialog = null, prompt = PasswordPrompt(PasswordPurpose.DELETE)) }
        } else {
            state.update { it.copy(dialog = null) }
            run(null, deleting = true) { items.delete(id, item.sensitivity) }
        }
    }

    /** The member picked [to]: `data` ↔ `secret` at once; into `critical` with the password; out of it after a warning. */
    fun pickProtection(to: Sensitivity) {
        val item = state.value.item ?: return
        when {
            to == item.sensitivity -> dismissDialog()
            item.sensitivity == Sensitivity.CRITICAL -> state.update { it.copy(dialog = DetailDialog.LEAVE_CRITICAL, target = to) }
            to == Sensitivity.CRITICAL -> state.update {
                it.copy(dialog = null, target = to, prompt = PasswordPrompt(PasswordPurpose.PROTECTION))
            }
            else -> {
                state.update { it.copy(dialog = null) }
                run(null) {
                    items.setSensitivity(id, item.version, item.sensitivity, to)
                    reload()
                }
            }
        }
    }

    /** "Move it out of the credential" after the warning: the password next. */
    fun confirmLeaveCritical() = state.update { it.copy(dialog = null, prompt = PasswordPrompt(PasswordPurpose.PROTECTION)) }

    @Suppress("ReturnCount")
    fun submitPassword() {
        val s = state.value
        val p = s.prompt ?: return
        val item = s.item ?: return
        if (!p.canSend(Instant.now())) return
        val pw = p.password
        state.update { it.copy(prompt = p.copy(busy = true, password = "", error = null)) }
        when (p.purpose) {
            PasswordPurpose.OPEN -> run(p) {
                val d = items.revealCritical(id, pw)
                state.update { it.copy(item = d, prompt = null) }
            }
            PasswordPurpose.DELETE -> run(p, deleting = true) { items.delete(id, item.sensitivity, pw) }
            PasswordPurpose.PROTECTION -> {
                val to = s.target ?: return cancelPassword()
                run(p) {
                    items.setSensitivity(id, item.version, item.sensitivity, to, pw)
                    state.update { it.copy(prompt = null, target = null) }
                    reload()
                }
            }
            PasswordPurpose.SAVE -> cancelPassword()
        }
    }

    private suspend fun reload() {
        val d = items.get(id)
        state.update { it.copy(item = d, shown = emptySet()) }
    }

    private fun run(prompt: PasswordPrompt?, deleting: Boolean = false, block: suspend () -> Unit) {
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                state.update { it.copy(busy = false, prompt = null, deleted = it.deleted || deleting) }
            } catch (e: VaultFailure) {
                state.update {
                    if (prompt != null && (e.kind == FailureKind.BAD_PASSWORD || e.kind == FailureKind.BACKOFF)) {
                        it.copy(busy = false, prompt = prompt.refused(e, Instant.now()))
                    } else {
                        it.copy(
                            busy = false,
                            prompt = null,
                            target = null,
                            error = e.kind,
                            limit = e.limit,
                            missing = e.kind == FailureKind.NOT_FOUND,
                        )
                    }
                }
            }
        }
    }
}

// --- adding and editing ---

/**
 * A rule the item would gain ([withdrawn] false) or leave ([withdrawn] true) when saved, for one connection
 * ([connectionName] "" before its names arrived); [usableOnly] for a critical item, which a rule makes only usable.
 */
data class ShareImpact(
    val connectionName: String,
    val mode: ShareMode,
    val usableOnly: Boolean = false,
    val withdrawn: Boolean = false,
)

/**
 * A small dialog of the add/edit screen. Fields are value-first (owner feedback 2026-10-08): each shows one input
 * captioned with its label, and the label and type change through these dialogs.
 */
sealed interface EditDialog {
    /** "Add a field": what it is called and its type, before it is added. */
    data class AddField(val label: String = "", val kind: String = FieldKinds.TEXT) : EditDialog

    data class RenameField(val index: Int, val label: String) : EditDialog

    /** Only for a field not yet saved (a saved field keeps its kind, §10.7). */
    data class FieldKind(val index: Int, val kind: String) : EditDialog

    /** A member-defined category (§10.7): the typed name; the identifier is derived from it. */
    data class NewCategory(val name: String = "") : EditDialog
}

/** Immutable UI state of the add/edit screen. */
data class ItemEditUiState(
    val itemId: String? = null,
    val draft: ItemDraft = ItemDraft(),
    val check: DraftCheck = DraftCheck(),
    val version: Long = 0,
    val loading: Boolean = false,
    /** Problems are shown once the member tried to save. */
    val showErrors: Boolean = false,
    val busy: Boolean = false,
    val prompt: PasswordPrompt? = null,
    val error: FailureKind? = null,
    /** The limit a `limit` error named (VAULT-MESSAGING 0.21.0 §10.1); null from an older vault. */
    val limit: VaultLimit? = null,
    val tagInput: String = "",
    val dirty: Boolean = false,
    val confirmDiscard: Boolean = false,
    /** Set when saved: the item to show. */
    val savedId: String? = null,
    /**
     * What saving does to sharing with the tags as they are now: the vault's dry run (§10.7, 0.21.0), or for an
     * older vault the share rules the item newly matches.
     */
    val shareImpact: List<ShareImpact> = emptyList(),
    val dialog: EditDialog? = null,
    /** The field whose value input takes the focus (once: just added). */
    val focusField: Int? = null,
    /** The member's own categories in their vault (distinct, sorted), offered after the recommended ones. */
    val customCategories: List<String> = emptyList(),
) {
    val isNew: Boolean get() = itemId == null

    /** The custom categories the picker lists: the member's own, and this item's when it is a custom one. */
    val pickerCustoms: List<String>
        get() = (customCategories + ItemCategories.customs(listOf(draft.category))).distinct().sorted()

    /** Whether the item carries the reserved `@profile` tag (connections see it in the shared profile, §10.8). */
    val inProfile: Boolean get() = ItemChecks.PROFILE_TAG in draft.tags

    override fun toString(): String = "ItemEditUiState(itemId=$itemId, busy=$busy, error=$error)"
}

/**
 * Adding an item from a template or blank, or editing one (§10.7). A `data` item is edited with its values; a
 * `secret` or `critical` one with its stored values kept (VAULT-MESSAGING 0.21.0 §10.7 Kept values): each shows as
 * kept, never revealed, and only what the member types is sent. A critical item is saved with the credential password
 * (one credential operation, §3.5.3); a secret one with nothing more. Sensitivity is chosen at creation; changing it
 * later is the detail screen's (`item.sensitivity`). The sharing notice is the vault's dry run.
 */
@HiltViewModel
@Suppress("TooManyFunctions")
class ItemEditViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val items: ItemsRepository,
    @param:ApplicationContext private val context: Context,
    private val sharing: SharingRepository,
    private val connections: ConnectionsRepository,
) : ViewModel() {
    /** The share rules, read only for a vault without the dry run (before 0.21.0). */
    private var rules: List<ShareRule>? = null
    private var noDryRun = false
    private var savedTags: List<String> = emptyList()
    private var impactKey: Pair<List<String>, Sensitivity>? = null
    private var impactJob: Job? = null

    private val route = ItemEditRoute(saved[ItemEditRoute.ARG_ITEM], saved[ItemEditRoute.ARG_TEMPLATE])
    private val state = MutableStateFlow(ItemEditUiState(itemId = route.itemId))
    val uiState: StateFlow<ItemEditUiState> = state.asStateFlow()

    init {
        state.update { it.copy(customCategories = ItemCategories.customs(items.items.value.map { i -> i.category })) }
        items.items.onEach { l -> state.update { it.copy(customCategories = ItemCategories.customs(l.map { i -> i.category })) } }
            .launchIn(viewModelScope)
        if (route.itemId == null) {
            val draft = route.template?.let { ItemTemplates.template(it)?.draft(context) } ?: ItemTemplates.blank(context)
            setDraft(draft, dirty = false)
        } else {
            load(route.itemId)
        }
    }

    /**
     * What saving would do to sharing (§10.7 Dry run): asked again when the tags (or a new item's sensitivity)
     * change, after a short pause. An existing item whose tags did not change gains and leaves nothing.
     */
    @Suppress("ReturnCount")
    private fun impact() {
        val s = state.value
        if (s.loading) return
        val d = s.draft
        val tags = ItemChecks.normalizeTags(d.tags) ?: return
        val key = tags to d.sensitivity
        if (key == impactKey) return
        impactKey = key
        impactJob?.cancel()
        if (!s.isNew && tags == savedTags) return state.update { it.copy(shareImpact = emptyList()) }
        impactJob = viewModelScope.launch {
            delay(IMPACT_DEBOUNCE_MS)
            val impact = try {
                if (noDryRun) localImpact(tags, d.sensitivity) else dryRun(s, tags, d.sensitivity)
            } catch (e: VaultFailure) {
                // A vault before 0.21.0 refuses the dry run (no content: nothing is saved); its rules are read instead.
                if (e.kind == FailureKind.NOT_SUPPORTED || e.kind == FailureKind.OTHER && e.code == CODE_BAD_REQUEST) {
                    noDryRun = true
                    runCatching { localImpact(tags, d.sensitivity) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            }
            state.update { it.copy(shareImpact = impact) }
        }
    }

    private suspend fun dryRun(s: ItemEditUiState, tags: List<String>, sensitivity: Sensitivity): List<ShareImpact> {
        val e = items.shareEffect(s.itemId, s.version.takeIf { s.itemId != null }, sensitivity, tags)
        val names = connections.connections.value.associate { it.id to it.displayName }
        // v1 pairs no agents (D3): only connection rules are shown.
        return e.shares.mapNotNull { x -> x.subject.connectionId?.let { ShareImpact(names[it].orEmpty(), x.mode, x.usable) } } +
            e.withdrawals.mapNotNull { x ->
                x.subject.connectionId?.let { ShareImpact(names[it].orEmpty(), ShareMode.ASK, withdrawn = true) }
            }
    }

    /** Before 0.21.0: the connection rules the item newly matches by its tags (§10.12), as the app reads them. */
    private suspend fun localImpact(tags: List<String>, sensitivity: Sensitivity): List<ShareImpact> {
        val all = rules ?: sharing.rules().also { rules = it }
        val names = connections.connections.value.associate { it.id to it.displayName }
        return all.filter { r -> r.connectionId != null && r.matches(tags) && !r.matches(savedTags) }
            .map { r -> ShareImpact(names[r.connectionId].orEmpty(), r.mode, usableOnly = sensitivity == Sensitivity.CRITICAL) }
    }

    /** The item's metadata (`item.get`): a data item with its values, a secret or critical one with them kept. */
    private fun load(itemId: String) {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                edit(items.get(itemId))
            } catch (e: VaultFailure) {
                state.update { it.copy(loading = false, error = e.kind, limit = e.limit) }
            }
        }
    }

    private fun edit(d: ItemDetail) {
        savedTags = d.tags
        state.update { it.copy(loading = false, version = d.version, prompt = null) }
        setDraft(ItemDraft.of(d), dirty = false)
    }

    private fun setDraft(d: ItemDraft, dirty: Boolean = true) {
        state.update { it.copy(draft = d, check = ItemChecks.check(d), dirty = it.dirty || dirty, error = null, limit = null) }
        impact()
    }

    private fun change(f: (ItemDraft) -> ItemDraft) = setDraft(f(state.value.draft))

    private fun changeField(i: Int, f: (DraftField) -> DraftField) =
        change { d -> d.copy(fields = d.fields.mapIndexed { j, x -> if (j == i) f(x) else x }) }

    fun setName(v: String) = change { it.copy(name = v) }

    fun setCategory(v: String) = change { it.copy(category = v) }

    /** Only for a new item; an existing one changes sensitivity on its detail screen. */
    fun setSensitivity(s: Sensitivity) {
        if (state.value.isNew) change { it.copy(sensitivity = s) }
    }

    /** Typing replaces kept notes (§10.7: `notes` instead of `keep_notes`). */
    fun setNotes(v: String) = change { it.copy(notes = v, keepNotes = false) }

    /** Removes kept notes: neither `notes` nor `keep_notes` is sent (§10.7). */
    fun removeNotes() = change { it.copy(notes = "", keepNotes = false) }

    fun setTagInput(v: String) = state.update { it.copy(tagInput = v) }

    /** Adds the typed tag (normalised as the vault does, §10.8); an invalid one stays in the field. */
    fun addTag() {
        val n = ItemChecks.normalizeTag(state.value.tagInput) ?: return
        change { d -> d.copy(tags = (d.tags + n).distinct()) }
        state.update { it.copy(tagInput = "") }
    }

    fun removeTag(tag: String) = change { d -> d.copy(tags = d.tags - tag) }

    fun setFieldLabel(i: Int, v: String) = changeField(i) { it.copy(label = v) }

    /** Typing into a kept field replaces its stored value (it is sent with `value` from now on). */
    fun setFieldText(i: Int, v: String) = changeField(i) { it.copy(text = v, kept = false) }

    fun setFieldAddress(i: Int, v: AddressValue) = changeField(i) { it.copy(address = v, kept = false) }

    /** The kind of a field not yet saved (a saved field keeps its kind: a kept value must keep it, §10.7). */
    fun setFieldKind(i: Int, kind: String) = changeField(i) { if (it.fieldId == null) it.copy(kind = kind) else it }

    fun addField(label: String, kind: String) {
        if (kind !in FieldKinds.CHOOSABLE) return
        change { d -> d.copy(fields = d.fields + DraftField(label = label, kind = kind)) }
    }

    fun askAddField() {
        if (state.value.draft.fields.size < ItemChecks.MAX_FIELDS) state.update { it.copy(dialog = EditDialog.AddField()) }
    }

    fun askRenameField(i: Int) {
        val f = state.value.draft.fields.getOrNull(i) ?: return
        state.update { it.copy(dialog = EditDialog.RenameField(i, f.label)) }
    }

    /** A saved field keeps its kind (§10.7): nothing to ask. */
    fun askFieldKind(i: Int) {
        val f = state.value.draft.fields.getOrNull(i)?.takeIf { it.fieldId == null } ?: return
        state.update { it.copy(dialog = EditDialog.FieldKind(i, f.kind)) }
    }

    fun askNewCategory() = state.update { it.copy(dialog = EditDialog.NewCategory()) }

    /** The label typed in the add or rename dialog, or the name in the new-category one. */
    fun setDialogText(v: String) = state.update { s ->
        s.copy(
            dialog = when (val d = s.dialog) {
                is EditDialog.AddField -> d.copy(label = v)
                is EditDialog.RenameField -> d.copy(label = v)
                is EditDialog.NewCategory -> d.copy(name = v)
                else -> d
            },
        )
    }

    fun setDialogKind(kind: String) = state.update { s ->
        if (kind !in FieldKinds.CHOOSABLE) return@update s
        s.copy(
            dialog = when (val d = s.dialog) {
                is EditDialog.AddField -> d.copy(kind = kind)
                is EditDialog.FieldKind -> d.copy(kind = kind)
                else -> d
            },
        )
    }

    fun dismissDialog() = state.update { it.copy(dialog = null) }

    /**
     * Applies the open dialog when what it holds is valid (the label checks of §10.7; a category name that gives an
     * identifier); otherwise it stays open. A new field is appended, ready for its value.
     */
    fun confirmDialog() {
        val applied = when (val d = state.value.dialog) {
            is EditDialog.AddField -> validLabel(d.label)?.let { label ->
                val at = state.value.draft.fields.size
                addField(label, d.kind)
                if (state.value.draft.fields.size > at) state.update { it.copy(focusField = at) }
            }
            is EditDialog.RenameField -> validLabel(d.label)?.let { setFieldLabel(d.index, it) }
            is EditDialog.FieldKind -> setFieldKind(d.index, d.kind)
            is EditDialog.NewCategory -> ItemCategories.derive(d.name).id?.let { setCategory(it) }
            null -> null
        }
        if (applied != null) state.update { it.copy(dialog = null) }
    }

    /** The trimmed label when it passes §10.7's checks, else null. */
    private fun validLabel(v: String): String? = v.trim().takeIf { ItemChecks.labelProblems(it).isEmpty() }

    fun fieldFocused() = state.update { it.copy(focusField = null) }

    fun removeField(i: Int) = change { d -> d.copy(fields = d.fields.filterIndexed { j, _ -> j != i }) }

    fun moveField(i: Int, delta: Int) = change { d ->
        val j = i + delta
        if (j !in d.fields.indices || i !in d.fields.indices) {
            d
        } else {
            d.copy(fields = d.fields.toMutableList().also { l -> l.add(j, l.removeAt(i)) })
        }
    }

    fun askDiscard(show: Boolean) = state.update { it.copy(confirmDiscard = show) }

    fun dismissError() = state.update { it.copy(error = null, limit = null) }

    fun save() {
        val s = state.value
        if (s.busy || s.loading) return
        val check = ItemChecks.check(s.draft)
        if (!check.ok) return state.update { it.copy(check = check, showErrors = true) }
        if (s.draft.sensitivity == Sensitivity.CRITICAL) {
            state.update { it.copy(prompt = PasswordPrompt(PasswordPurpose.SAVE), showErrors = true) }
        } else {
            send(null, "")
        }
    }

    fun setPassword(v: String) = state.update { s -> s.copy(prompt = s.prompt?.copy(password = v, error = null)) }

    fun cancelPassword() = state.update { it.copy(prompt = null) }

    fun submitPassword() {
        val p = state.value.prompt ?: return
        if (!p.canSend(Instant.now())) return
        val pw = p.password
        state.update { it.copy(prompt = p.copy(busy = true, password = "", error = null)) }
        send(p, pw)
    }

    private fun send(p: PasswordPrompt?, pw: String) {
        val s = state.value
        state.update { it.copy(busy = true, error = null, limit = null) }
        viewModelScope.launch {
            try {
                val critical = s.draft.sensitivity == Sensitivity.CRITICAL
                val id = when {
                    s.itemId == null && critical -> items.createCritical(s.draft, pw)
                    s.itemId == null -> items.create(s.draft)
                    critical -> s.itemId.also { items.updateCritical(it, s.version, s.draft, pw) }
                    else -> s.itemId.also { items.update(it, s.version, s.draft) }
                }
                state.update { it.copy(busy = false, prompt = null, savedId = id, dirty = false) }
            } catch (e: VaultFailure) {
                state.update { it.copy(busy = false) }
                if (p != null) refused(p, e) else state.update { it.copy(error = e.kind, limit = e.limit) }
            }
        }
    }

    private fun refused(p: PasswordPrompt, e: VaultFailure) = state.update {
        if (e.kind == FailureKind.BAD_PASSWORD || e.kind == FailureKind.BACKOFF) {
            it.copy(busy = false, prompt = p.refused(e, Instant.now()))
        } else {
            it.copy(busy = false, prompt = null, error = e.kind, limit = e.limit)
        }
    }

    private companion object {
        const val IMPACT_DEBOUNCE_MS = 400L
        const val CODE_BAD_REQUEST = "bad_request"
    }
}
