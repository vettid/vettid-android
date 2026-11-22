package com.vettid.feature.devices

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.devices.AgentChange
import com.vettid.core.data.devices.DevicesRepository
import com.vettid.core.data.devices.HostProblem
import com.vettid.core.data.devices.ItemWay
import com.vettid.core.data.devices.ItemWays
import com.vettid.core.data.devices.ItemWaysRead
import com.vettid.core.data.devices.ItemWaysRepository
import com.vettid.core.data.devices.ItemWaysRules
import com.vettid.core.data.devices.ItemWaysSave
import com.vettid.core.data.devices.ProgramProblem
import com.vettid.core.data.devices.ProxySettings
import com.vettid.core.data.devices.RunProgram
import com.vettid.core.data.devices.RunSettings
import com.vettid.core.data.devices.VariableProblem
import com.vettid.core.data.items.ItemsRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.votes.WindowNotClosed
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A confirmation on the item's page (AA7): each asks before the change is made in the draft. */
sealed interface WaysConfirm {
    data object RunOff : WaysConfirm

    data object ProxyOff : WaysConfirm

    data object RevealOn : WaysConfirm

    data object EachOff : WaysConfirm

    data class RemoveLastProgram(val index: Int) : WaysConfirm

    data class ForgetPin(val index: Int) : WaysConfirm
}

/** What the last save or event came to, shown once. */
sealed interface WaysNotice {
    data object Saved : WaysNotice

    data object LockedAgain : WaysNotice

    data object Conflict : WaysNotice

    data object ReadOnlyLocked : WaysNotice


    data class Failed(val failure: VaultFailure) : WaysNotice
}

/** The unlock window prompt of a widening save (AA7 "Saving"). [password] lives here only while the prompt is up. */
data class WaysPrompt(
    val password: String = "",
    val sending: Boolean = false,
    /** The prompt follows a `credential_locked`: its request is the same request, sent once more. */
    val resend: Boolean = false,
    val error: FailureKind? = null,
    val errorCode: String? = null,
    val retryUntil: Instant? = null,
) {
    val alarm: Boolean get() = error == FailureKind.CREDENTIAL_FROZEN || error == FailureKind.ROTATION_REQUIRED

    override fun toString(): String = "WaysPrompt"
}

/**
 * The item's page (ANDROID-PLAN 0.1.137 AA7). [read] is the vault's `item_ways` as last read, [draft] the member's edits
 * (null for a read-only page, which offers only to turn a whole way off). Nothing is sent while the member edits.
 */
data class ItemWaysUiState(
    val agentName: String = "",
    val itemName: String? = null,
    val loading: Boolean = true,
    val loadError: VaultFailure? = null,
    val read: ItemWaysRead? = null,
    val draft: ItemWays? = null,
    val saving: Boolean = false,
    val confirm: WaysConfirm? = null,
    val prompt: WaysPrompt? = null,
    val notice: WaysNotice? = null,
    /** `not_found`, or the event that says the settings were deleted: the page says so and saves nothing. */
    val notIncluded: Boolean = false,
    /** `not_found` to a save: the page says so, then goes back. */
    val leaving: Boolean = false,
    /** A `leash.item.changed` for this item while the draft holds changes: kept, with **Show the current ones**. */
    val changedElsewhere: Boolean = false,
    /** The program whose page is open (its index in the draft), or null. */
    val openProgram: Int? = null,
    val programInput: String = "",
    val programProblem: ProgramProblem? = null,
    /** The open program's text as edited (changing it forgets the fingerprint). */
    val editInput: String = "",
    val editProblem: ProgramProblem? = null,
    val variableInput: String = "",
    val variableProblem: VariableProblem? = null,
    val hostInput: String = "",
    val hostProblem: HostProblem? = null,
    /** "Add a host" was opened by turning the API switch on (`proxy` needs one host): its field takes focus. */
    val addingHost: Boolean = false,
    /** The fingerprint shown in full (all 64 digits), or null. */
    val fullPin: String? = null,
    val windowNotClosed: WindowNotClosed? = null,
    val ownerCheck: Boolean = false,
    /**
     * The last save was refused with `bad_request` (AA7 "Saving"): the vault's message as shown ([VaultReason]), "" when it
     * is shown by the fallback text; null when there is none. Kept on the page until the next save or edit.
     */
    val refused: String? = null,
) {
    val readOnly: Boolean get() = read != null && read.readOnly

    /** The draft differs from what was read. */
    val dirty: Boolean get() = draft != null && draft != read?.ways

    /** The open program's text was changed in its field and not applied yet. */
    val editPending: Boolean
        get() = openProgram != null && draft?.run?.programs?.getOrNull(openProgram)?.program?.let { it != editInput } == true

    /** Whether the draft can be saved: changed, and every field the vault checks reads as it would accept it. */
    val canSave: Boolean get() = (dirty || editPending) && !saving && !notIncluded && draftValid

    private val draftValid: Boolean
        get() {
            val p = draft?.proxy ?: return true
            return p.hosts.isNotEmpty() && ItemWaysRules.headerValid(p.header) && ItemWaysRules.prefixValid(p.prefix)
        }

    /** The screen holds input the member would lose by leaving (the shell's `HoldsInput`). */
    val holdsInput: Boolean
        get() = dirty || editPending || prompt != null || programInput.isNotEmpty() || variableInput.isNotEmpty() ||
            hostInput.isNotEmpty()

    /** The open program's recorded pin was taken out in the draft: "Fingerprint forgotten when you save". */
    fun pinForgotten(p: RunProgram): Boolean {
        if (p.sha256 != null) return false
        return read?.ways?.run?.programs?.any { it.program == p.program && it.sha256 != null } == true
    }

    /**
     * After a `Saved` of [sent]: the draft starts again from [saved], unless the member changed it while the save was out.
     * Then the change is kept and moved onto the saved version when the vault saved exactly what was sent; otherwise it
     * stays on the read it was made from, with "Another device changed these settings." (its save meets `conflict`).
     */
    internal fun afterSaved(saved: ItemWaysRead, sent: ItemWays?): ItemWaysUiState {
        val d = draft
        val now = saved.ways
        val base = copy(prompt = null, notice = WaysNotice.Saved)
        return when {
            sent == null || d == null || d == sent -> base.copy(read = saved, draft = now, changedElsewhere = false)
            now != null && sent.copy(version = now.version) == now ->
                base.copy(read = saved, draft = d.copy(version = now.version), changedElsewhere = false)
            else -> base.copy(changedElsewhere = true)
        }
    }

    override fun toString(): String = "ItemWaysUiState"
}

/** The item's page (AA7): one `item_ways`, edited in a draft and saved with **Save**. */
@HiltViewModel
@Suppress("TooManyFunctions")
class ItemWaysViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: ItemWaysRepository,
    private val devices: DevicesRepository,
    private val items: ItemsRepository,
) : ViewModel() {
    private val agentId: String = saved.get<String>(ItemWaysRoute.AGENT).orEmpty()
    private val itemId: String = saved.get<String>(ItemWaysRoute.ITEM).orEmpty()
    private val local = MutableStateFlow(ItemWaysUiState())

    /** The latest read started: an older one's answer is never applied (it may be of an older version). */
    private var loads = 0

    val uiState: StateFlow<ItemWaysUiState> =
        combine(local, devices.devices, items.items, repo.windowNotClosed) { s, ds, its, w ->
            s.copy(
                agentName = ds.firstOrNull { it.id == agentId }?.name.orEmpty(),
                itemName = its.firstOrNull { it.itemId == itemId }?.name,
                windowNotClosed = w,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ItemWaysUiState())

    init {
        load(force = true)
        viewModelScope.launch {
            devices.changes.collect { c ->
                if (c is AgentChange.Item && c.agentId == agentId && c.itemId == itemId) onChanged(c.version)
            }
        }
    }

    /** On opening, Try again and **Show the current ones**: the vault's settings, the draft started again from them. */
    fun load() = load(force = true)

    /**
     * Reads the settings. Only the latest read is applied. [force] (opening, Try again, Show the current ones, a
     * conflict) starts the draft again from them; otherwise (an event's re-read of a clean page) a draft the member
     * changed while the read was out is kept, with "Another device changed these settings." (AA7 "While the page is open").
     */
    private fun load(force: Boolean) {
        val n = ++loads
        val before = local.value.draft
        local.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            try {
                val r = repo.read(agentId, itemId)
                local.update {
                    when {
                        n != loads -> it
                        r == null -> it.copy(loading = false, notIncluded = true, prompt = null)
                        // The kept draft stays on the read it was made from: its save meets the vault's `conflict`, no prompt.
                        !force && it.draft != before -> it.copy(loading = false, notIncluded = false, changedElsewhere = true)
                        else -> it.copy(
                            loading = false, read = r, draft = r.ways, notIncluded = false, changedElsewhere = false,
                            openProgram = null, editInput = "", addingHost = false, confirm = null, fullPin = null,
                        )
                    }
                }
            } catch (e: VaultFailure) {
                local.update {
                    if (n != loads) it else it.copy(loading = false, loadError = e, ownerCheck = e.kind == FailureKind.OWNER_CHECK_REQUIRED)
                }
            }
        }
    }

    private fun onChanged(version: Long?) {
        when {
            version == null -> local.update { it.copy(notIncluded = true, prompt = null) }
            local.value.dirty -> local.update { it.copy(changedElsewhere = true) }
            else -> load(force = false)
        }
    }

    private fun edit(change: (ItemWays) -> ItemWays) =
        local.update { s -> s.draft?.let { s.copy(draft = change(it), refused = null) } ?: s }

    // The switches.

    fun setRun(on: Boolean) {
        if (on) {
            edit { it.copy(ways = it.ways + ItemWay.RUN, run = RunSettings()) }
        } else {
            local.update { it.copy(confirm = WaysConfirm.RunOff) }
        }
    }

    fun setProxy(on: Boolean) {
        if (on) {
            edit { it.copy(ways = it.ways + ItemWay.PROXY, proxy = ProxySettings(emptyList(), DEFAULT_HEADER, DEFAULT_PREFIX)) }
            local.update { it.copy(addingHost = true) }
        } else {
            local.update { it.copy(confirm = WaysConfirm.ProxyOff) }
        }
    }

    fun setReveal(on: Boolean) {
        if (on) {
            local.update { it.copy(confirm = WaysConfirm.RevealOn) }
        } else if (local.value.readOnly) {
            turnOff(ItemWay.REVEAL)
        } else {
            edit { it.copy(ways = it.ways - ItemWay.REVEAL) }
        }
    }

    fun setEach(on: Boolean) {
        when {
            on -> edit { it.copy(run = it.run?.copy(each = true)) }
            // Off only with a program (AA7): the switch is disabled then, and the view model holds to it too.
            local.value.draft?.run?.programs.isNullOrEmpty() -> Unit
            else -> local.update { it.copy(confirm = WaysConfirm.EachOff) }
        }
    }

    fun dismissConfirm() = local.update { it.copy(confirm = null) }

    /** The confirmation's affirmative button. */
    fun confirm() {
        val c = local.value.confirm ?: return
        local.update { it.copy(confirm = null) }
        when (c) {
            WaysConfirm.RunOff ->
                if (local.value.readOnly) turnOff(ItemWay.RUN) else edit { it.copy(ways = it.ways - ItemWay.RUN, run = null) }
            WaysConfirm.ProxyOff ->
                if (local.value.readOnly) turnOff(ItemWay.PROXY) else edit { it.copy(ways = it.ways - ItemWay.PROXY, proxy = null) }
            WaysConfirm.RevealOn -> edit { it.copy(ways = it.ways + ItemWay.REVEAL) }
            WaysConfirm.EachOff -> edit { it.copy(run = it.run?.copy(each = false)) }
            is WaysConfirm.RemoveLastProgram -> {
                removeProgramAt(c.index)
                local.update { it.copy(openProgram = null) }
            }
            is WaysConfirm.ForgetPin -> editProgram(c.index) { it.copy(sha256 = null, path = null) }
        }
    }

    // Programs.

    fun setProgramInput(v: String) = local.update { it.copy(programInput = v, programProblem = null) }

    fun addProgram() {
        val s = local.value
        val programs = s.draft?.run?.programs ?: return
        val p = s.programInput
        val problem = ItemWaysRules.addProgramProblem(programs, p)
        if (problem != null) {
            local.update { it.copy(programProblem = problem) }
            return
        }
        edit { it.copy(run = it.run?.copy(programs = programs + RunProgram(p))) }
        local.update { it.copy(programInput = "", programProblem = null) }
    }

    fun openProgram(index: Int) {
        val p = local.value.draft?.run?.programs?.getOrNull(index) ?: return
        local.update { it.copy(openProgram = index, editInput = p.program, editProblem = null, variableInput = "", variableProblem = null) }
    }

    /**
     * Back from the program's page: a changed program text is applied first. When it is refused the page stays with the
     * problem shown; Back again leaves and drops that text.
     */
    fun closeProgram(): Boolean {
        val refusedBefore = local.value.editProblem != null
        if (!refusedBefore && !applyEdit()) return false
        local.update { it.copy(openProgram = null, editInput = "", editProblem = null, variableInput = "", variableProblem = null) }
        return true
    }

    fun setEditInput(v: String) = local.update { it.copy(editInput = v, editProblem = null) }

    /**
     * Changing the program is removing it and adding another (AA7): checked as an added program against the others, its
     * fingerprint forgotten, its variables kept. False, with the problem shown, when the new text is refused.
     */
    @Suppress("ReturnCount") // nothing to apply is one answer per missing part
    fun applyEdit(): Boolean {
        val s = local.value
        val i = s.openProgram ?: return true
        val programs = s.draft?.run?.programs ?: return true
        val cur = programs.getOrNull(i) ?: return true
        if (s.editInput == cur.program) return true
        val others = programs.filterIndexed { j, _ -> j != i }
        val problem = ItemWaysRules.addProgramProblem(others, s.editInput)
        if (problem != null) {
            local.update { it.copy(editProblem = problem) }
            return false
        }
        editProgram(i) { RunProgram(s.editInput, env = it.env) }
        return true
    }

    fun removeProgram(index: Int) {
        val programs = local.value.draft?.run?.programs ?: return
        if (programs.size == 1) {
            // Removing the last program is a widening (the item becomes unbound): it asks first.
            local.update { it.copy(confirm = WaysConfirm.RemoveLastProgram(index)) }
            return
        }
        removeProgramAt(index)
        local.update { it.copy(openProgram = null) }
    }

    private fun removeProgramAt(index: Int) = edit { w ->
        val r = w.run ?: return@edit w
        val left = r.programs.filterIndexed { j, _ -> j != index }
        // Emptying the program list turns Ask me before each run back on.
        w.copy(run = r.copy(programs = left, each = if (left.isEmpty()) true else r.each))
    }

    private fun editProgram(index: Int, change: (RunProgram) -> RunProgram) = edit { w ->
        val r = w.run ?: return@edit w
        w.copy(run = r.copy(programs = r.programs.mapIndexed { j, p -> if (j == index) change(p) else p }))
    }

    fun forgetPin(index: Int) = local.update { it.copy(confirm = WaysConfirm.ForgetPin(index)) }

    fun showFullPin(sha256: String?) = local.update { it.copy(fullPin = sha256) }

    // Variables.

    fun setVariableInput(v: String) = local.update { it.copy(variableInput = v, variableProblem = null) }

    @Suppress("ReturnCount")
    fun addVariable() {
        val s = local.value
        val i = s.openProgram ?: return
        val env = s.draft?.run?.programs?.getOrNull(i)?.env ?: return
        val name = s.variableInput
        val problem = ItemWaysRules.variableProblem(env, name)
        if (problem != null) {
            local.update { it.copy(variableProblem = problem) }
            return
        }
        if (name !in env) editProgram(i) { it.copy(env = it.env + name) }
        local.update { it.copy(variableInput = "", variableProblem = null) }
    }

    fun removeVariable(name: String) {
        val i = local.value.openProgram ?: return
        editProgram(i) { it.copy(env = it.env - name) }
    }

    // The API settings.

    fun setHostInput(v: String) = local.update { it.copy(hostInput = v, hostProblem = null) }

    fun addHost() {
        val s = local.value
        val p = s.draft?.proxy ?: return
        val host = ItemWaysRules.normalizeHost(s.hostInput)
        val problem = ItemWaysRules.addHostProblem(p.hosts, host)
        if (problem != null) {
            local.update { it.copy(hostProblem = problem) }
            return
        }
        edit { it.copy(proxy = it.proxy?.copy(hosts = p.hosts + host)) }
        local.update { it.copy(hostInput = "", hostProblem = null, addingHost = false) }
    }

    /** The last host cannot be removed (turn the switch off instead). */
    fun removeHost(host: String) {
        val p = local.value.draft?.proxy ?: return
        if (p.hosts.size <= 1) return
        edit { it.copy(proxy = it.proxy?.copy(hosts = p.hosts - host)) }
    }

    fun setHeader(v: String) = edit { it.copy(proxy = it.proxy?.copy(header = v)) }

    fun setPrefix(v: String) = edit { it.copy(proxy = it.proxy?.copy(prefix = v)) }

    // Saving.

    fun save() {
        val s = local.value
        if (!s.canSave) return
        if (s.openProgram != null && !applyEdit()) return
        send(password = null, resent = false)
    }

    fun setPassword(v: String) = local.update { it.copy(prompt = it.prompt?.copy(password = v, error = null)) }

    fun submitPassword() {
        val p = local.value.prompt ?: return
        if (p.password.isEmpty() || p.sending) return
        local.update { it.copy(prompt = p.copy(password = "", sending = true)) }
        send(password = p.password, resent = p.resend)
    }

    fun dismissPrompt() {
        if (local.value.prompt?.sending == true) return
        local.update { it.copy(prompt = null) }
    }

    private fun send(password: String?, resent: Boolean) {
        val s = local.value
        val cur = s.read
        val next = s.draft
        // An item no longer included saves nothing (AA7), also from a prompt that was open when that was learnt.
        if (cur == null || next == null || s.notIncluded) return
        local.update { it.copy(saving = true, refused = null) }
        viewModelScope.launch {
            val r = try {
                repo.save(cur, next, password, resent)
            } finally {
                local.update { it.copy(saving = false) }
            }
            // One resend after `credential_locked`, whatever the repository answers (AA7 "Saving").
            onSaved(if (resent && r == ItemWaysSave.CredentialLocked) ItemWaysSave.LockedAgain else r, sent = next)
        }
    }

    private fun turnOff(way: ItemWay) {
        val cur = local.value.read ?: return
        if (local.value.notIncluded) return
        local.update { it.copy(saving = true, refused = null) }
        viewModelScope.launch {
            val r = try {
                repo.turnOff(cur, way.wire)
            } finally {
                local.update { it.copy(saving = false) }
            }
            onSaved(r)
        }
    }

    /** [sent] is the draft the save sent: an edit made while it was out stays in the draft after a `Saved`. */
    @Suppress("CyclomaticComplexMethod")
    private fun onSaved(r: ItemWaysSave, sent: ItemWays? = null) {
        when (r) {
            is ItemWaysSave.Saved -> {
                loads++ // a read started before this answer is older than it
                local.update { it.afterSaved(r.read, sent) }
            }
            ItemWaysSave.NeedsPassword -> local.update { it.copy(prompt = WaysPrompt()) }
            ItemWaysSave.CredentialLocked -> local.update { it.copy(prompt = WaysPrompt(resend = true)) }
            ItemWaysSave.LockedAgain -> local.update { it.copy(prompt = null, notice = WaysNotice.LockedAgain) }
            ItemWaysSave.ReadOnlyLocked -> local.update { it.copy(prompt = null, notice = WaysNotice.ReadOnlyLocked) }
            ItemWaysSave.Conflict -> {
                local.update { it.copy(prompt = null, notice = WaysNotice.Conflict) }
                load()
            }
            ItemWaysSave.NotIncluded -> local.update { it.copy(prompt = null, notIncluded = true, leaving = true) }
            is ItemWaysSave.BadRequest -> local.update { it.copy(prompt = null, refused = VaultReason.shown(r.message).orEmpty()) }
            ItemWaysSave.OwnerCheck -> local.update { it.copy(prompt = null, ownerCheck = true) }
            ItemWaysSave.AlarmOpen -> local.update {
                it.copy(prompt = (it.prompt ?: WaysPrompt()).copy(password = "", sending = false, error = FailureKind.CREDENTIAL_FROZEN))
            }
            is ItemWaysSave.PasswordRefused -> local.update { s ->
                val e = r.failure
                val backoff = e.kind == FailureKind.BACKOFF && e.retryAfterSeconds > 0
                val until = if (backoff) Instant.now().plusSeconds(e.retryAfterSeconds) else null
                s.copy(
                    prompt = (s.prompt ?: WaysPrompt()).copy(
                        password = "", sending = false, error = e.kind, errorCode = e.shownCode, retryUntil = until,
                    ),
                )
            }
            is ItemWaysSave.Failed -> local.update { it.copy(prompt = null, notice = WaysNotice.Failed(r.failure)) }
        }
    }

    fun noticeShown() = local.update { it.copy(notice = null) }

    fun ownerCheckShown() = local.update { it.copy(ownerCheck = false) }

    fun closeWindowNow() {
        viewModelScope.launch { runCatching { repo.closeWindowNow() } }
    }

    override fun onCleared() {
        // The password lives only in the prompt; nothing to keep.
        local.update { it.copy(prompt = null) }
    }

    companion object {
        /** The app's suggestions when the API switch is turned on (not vault defaults; owner-approved, open item 6). */
        const val DEFAULT_HEADER = "Authorization"
        const val DEFAULT_PREFIX = "Bearer "
    }
}
