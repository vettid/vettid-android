package com.vettid.feature.items

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.items.AddressValue
import com.vettid.core.data.items.DraftCheck
import com.vettid.core.data.items.DraftField
import com.vettid.core.data.items.ItemChecks
import com.vettid.core.data.items.ItemDetail
import com.vettid.core.data.items.ItemDraft
import com.vettid.core.data.items.ItemFilter
import com.vettid.core.data.items.ItemSummary
import com.vettid.core.data.items.ItemsRepository
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    /** The categories in use, for the category filter. */
    val categories: List<String> get() = items.map { it.category }.distinct().sorted()

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

    fun dismissError() = state.update { it.copy(error = null) }

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

    /** Before the edit screen opens: revealed values go with it (in memory), so that it does not reveal them again. */
    fun handOff() {
        state.value.item?.takeIf { it.sensitivity != Sensitivity.DATA && it.revealed }?.let { items.keepOpened(it) }
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
                        it.copy(busy = false, prompt = null, target = null, error = e.kind, missing = e.kind == FailureKind.NOT_FOUND)
                    }
                }
            }
        }
    }
}

// --- adding and editing ---

/** Immutable UI state of the add/edit screen. */
data class ItemEditUiState(
    val itemId: String? = null,
    val draft: ItemDraft = ItemDraft(),
    val check: DraftCheck = DraftCheck(),
    val version: Long = 0,
    val loading: Boolean = false,
    /** A critical item opened without its values at hand: the password opens it first. */
    val needsOpen: Boolean = false,
    /** Problems are shown once the member tried to save. */
    val showErrors: Boolean = false,
    val busy: Boolean = false,
    val prompt: PasswordPrompt? = null,
    val error: FailureKind? = null,
    val tagInput: String = "",
    val dirty: Boolean = false,
    val confirmDiscard: Boolean = false,
    /** Set when saved: the item to show. */
    val savedId: String? = null,
) {
    val isNew: Boolean get() = itemId == null

    /** Whether the item carries the reserved `@profile` tag (connections see it in the shared profile, §10.8). */
    val inProfile: Boolean get() = ItemChecks.PROFILE_TAG in draft.tags
}

/**
 * Adding an item from a template or blank, or editing one (§10.7). A secret item is edited from its revealed values
 * (the detail screen hands them over, else `item.reveal`); a critical one from values opened with the password, and
 * saved with the password again (a credential operation each time, §3.5.3). Sensitivity is chosen at creation;
 * changing it later is the detail screen's (`item.sensitivity`).
 */
@HiltViewModel
@Suppress("TooManyFunctions")
class ItemEditViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val items: ItemsRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val route = ItemEditRoute(saved[ItemEditRoute.ARG_ITEM], saved[ItemEditRoute.ARG_TEMPLATE])
    private val state = MutableStateFlow(ItemEditUiState(itemId = route.itemId))
    val uiState: StateFlow<ItemEditUiState> = state.asStateFlow()

    init {
        if (route.itemId == null) {
            val draft = route.template?.let { ItemTemplates.template(it)?.draft(context) } ?: ItemTemplates.blank(context)
            setDraft(draft, dirty = false)
        } else {
            load(route.itemId)
        }
    }

    private fun load(itemId: String) {
        val opened = items.takeOpened(itemId)
        if (opened != null) return edit(opened)
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val meta = items.get(itemId)
                when (meta.sensitivity) {
                    Sensitivity.DATA -> edit(meta)
                    Sensitivity.SECRET -> edit(items.reveal(itemId))
                    Sensitivity.CRITICAL -> state.update {
                        it.copy(
                            loading = false,
                            needsOpen = true,
                            version = meta.version,
                            draft = ItemDraft.of(meta),
                            prompt = PasswordPrompt(PasswordPurpose.OPEN),
                        )
                    }
                }
            } catch (e: VaultFailure) {
                state.update { it.copy(loading = false, error = e.kind) }
            }
        }
    }

    private fun edit(d: ItemDetail) {
        state.update { it.copy(loading = false, needsOpen = false, version = d.version, prompt = null) }
        setDraft(ItemDraft.of(d), dirty = false)
    }

    private fun setDraft(d: ItemDraft, dirty: Boolean = true) =
        state.update { it.copy(draft = d, check = ItemChecks.check(d), dirty = it.dirty || dirty, error = null) }

    private fun change(f: (ItemDraft) -> ItemDraft) = setDraft(f(state.value.draft))

    private fun changeField(i: Int, f: (DraftField) -> DraftField) =
        change { d -> d.copy(fields = d.fields.mapIndexed { j, x -> if (j == i) f(x) else x }) }

    fun setName(v: String) = change { it.copy(name = v) }

    fun setCategory(v: String) = change { it.copy(category = v) }

    /** Only for a new item; an existing one changes sensitivity on its detail screen. */
    fun setSensitivity(s: Sensitivity) {
        if (state.value.isNew) change { it.copy(sensitivity = s) }
    }

    fun setNotes(v: String) = change { it.copy(notes = v) }

    fun setTagInput(v: String) = state.update { it.copy(tagInput = v) }

    /** Adds the typed tag (normalised as the vault does, §10.8); an invalid one stays in the field. */
    fun addTag() {
        val n = ItemChecks.normalizeTag(state.value.tagInput) ?: return
        change { d -> d.copy(tags = (d.tags + n).distinct()) }
        state.update { it.copy(tagInput = "") }
    }

    fun removeTag(tag: String) = change { d -> d.copy(tags = d.tags - tag) }

    fun setFieldLabel(i: Int, v: String) = changeField(i) { it.copy(label = v) }

    fun setFieldText(i: Int, v: String) = changeField(i) { it.copy(text = v) }

    fun setFieldAddress(i: Int, v: AddressValue) = changeField(i) { it.copy(address = v) }

    /** The kind of a field not yet saved (a saved field keeps its kind: its id names the same field, §10.7). */
    fun setFieldKind(i: Int, kind: String) = changeField(i) { if (it.fieldId == null) it.copy(kind = kind) else it }

    fun addField(label: String, kind: String) = change { d -> d.copy(fields = d.fields + DraftField(label = label, kind = kind)) }

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

    fun dismissError() = state.update { it.copy(error = null) }

    fun save() {
        val s = state.value
        if (s.busy || s.needsOpen) return
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
        if (p.purpose == PasswordPurpose.OPEN) open(p, pw) else send(p, pw)
    }

    private fun open(p: PasswordPrompt, pw: String) {
        val itemId = state.value.itemId ?: return
        viewModelScope.launch {
            try {
                edit(items.revealCritical(itemId, pw))
            } catch (e: VaultFailure) {
                refused(p, e)
            }
        }
    }

    private fun send(p: PasswordPrompt?, pw: String) {
        val s = state.value
        state.update { it.copy(busy = true, error = null) }
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
                if (p != null) refused(p, e) else state.update { it.copy(error = e.kind) }
            }
        }
    }

    private fun refused(p: PasswordPrompt, e: VaultFailure) = state.update {
        if (e.kind == FailureKind.BAD_PASSWORD || e.kind == FailureKind.BACKOFF) {
            it.copy(busy = false, prompt = p.refused(e, Instant.now()))
        } else {
            it.copy(busy = false, prompt = null, error = e.kind)
        }
    }
}
