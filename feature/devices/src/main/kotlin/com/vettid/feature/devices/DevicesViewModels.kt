package com.vettid.feature.devices

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.devices.AgentChange
import com.vettid.core.data.devices.AgentGrant
import com.vettid.core.data.devices.AgentResumption
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.devices.DevicesRepository
import com.vettid.core.data.devices.PermissionRemoval
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.items.ShareRule
import com.vettid.core.data.items.SharingRepository
import com.vettid.core.data.social.ConnectionsRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckRepository
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.checkCleared
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Immutable UI state of the Devices & agents list (ANDROID-PLAN 0.1.53 AA1). */
data class DevicesUiState(
    /** This phone first, then agents, then desktops. */
    val devices: List<DeviceInfo> = emptyList(),
    val load: ListLoad = ListLoad.NOT_LOADED,
    val error: FailureKind? = null,
    /** (ANDROID-PLAN 0.1.57 (2)) The code `error_vault_code` names with [error], or null. */
    val errorCode: String? = null,
    /** The device just removed on its page: the snackbar "<name> was removed". */
    val removedDevice: DeviceInfo? = null,
) {
    /** Only this phone is listed: "No agents yet". */
    val onlyThisPhone: Boolean get() = devices.none { !it.thisPhone }

    val loading: Boolean get() = devices.isEmpty() && (load == ListLoad.NOT_LOADED || load == ListLoad.LOADING) && error == null
}

/**
 * The list of the member's devices and agents (ANDROID-PLAN 0.1.53 AA1): read when the screen opens and on return to
 * it; `sync.event`s re-read it in the repository. Held in memory only.
 */
@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val repo: DevicesRepository,
    ownerCheck: OwnerCheckRepository,
) : ViewModel() {
    private val local = MutableStateFlow(DevicesUiState())

    val uiState: StateFlow<DevicesUiState> =
        combine(local, repo.devices, repo.load, repo.removed) { s, list, load, removed ->
            s.copy(devices = list, load = load, removedDevice = removed)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, DevicesUiState())

    init {
        // The screen reads the list when it shows (`refresh`): once on opening, once on every return.
        // The daily check passed (or the hold ended): a list refused because it was due reads again by itself.
        viewModelScope.launch {
            ownerCheck.checkCleared().collect {
                if (local.value.error == FailureKind.OWNER_CHECK_REQUIRED) refresh()
            }
        }
    }

    /** On opening and on every return to the screen. */
    fun refresh() {
        local.update { it.copy(error = null, errorCode = null) }
        viewModelScope.launch {
            try {
                repo.refresh()
            } catch (e: VaultFailure) {
                local.update { it.copy(error = e.kind, errorCode = e.shownCode) }
            }
        }
    }

    fun removedShown() = repo.dismissRemoved()
}

/** What the page's confirmation dialog is for. */
enum class DeviceConfirm { END_ACCESS, REMOVE, REMOVE_PERMISSION }

/** What a failed request on a device's page says (the vault's own failure text for a refused owner check). */
enum class DeviceFailure { END_ACCESS, REMOVE, REMOVE_PERMISSION, RESUME }

/** What the page says once, as a snackbar, after a change to an agent's permissions (AA3). */
enum class PermissionNotice { REMOVED, ALREADY_REMOVED, RESUMED, NOT_PAUSED }

/** Immutable UI state of a device's page. */
data class DeviceUiState(
    val device: DeviceInfo? = null,
    /** The list was read at least once: a [device] still null is a device that is no longer there. */
    val loaded: Boolean = false,
    val confirm: DeviceConfirm? = null,
    /** With [DeviceConfirm.REMOVE] of an agent: seconds a service may still accept its permissions (0: none). */
    val removalBoundSeconds: Long = 0,
    val busy: Boolean = false,
    val failure: DeviceFailure? = null,
    val error: FailureKind? = null,
    val errorCode: String? = null,
    /** "Access ended", once. */
    val accessEnded: Boolean = false,
    /** The device was removed here: the screen leaves. */
    val removed: Boolean = false,
    /** The list could not be read and no device is known: the page says so and offers Retry. */
    val loadError: FailureKind? = null,
    val loadErrorCode: String? = null,
    /** AA4: the agent's share rules (`share.rule.list{agent_id}`), "What it may use"; memory only. */
    val rules: List<ShareRule> = emptyList(),
    val rulesLoaded: Boolean = false,
    val rulesError: FailureKind? = null,
    /** The vault lists `item_ways` (decision 17): without it every rule row carries the warning line. */
    val itemWays: Boolean = false,
    /** "Rule saved", once. */
    val ruleSaved: Boolean = false,
    /** AA3: the agent's grants that are not share rules, "Other permissions" (`leash.grant.list{agent_id}`); memory only. */
    val permissions: List<AgentGrant> = emptyList(),
    val permissionsLoaded: Boolean = false,
    val permissionsError: FailureKind? = null,
    /** The names of the member's connections by id (blank: no name yet); an id not in it is a removed connection. */
    val connectionNames: Map<String, String> = emptyMap(),
    /** With [DeviceConfirm.REMOVE_PERMISSION]: the permission the confirmation is about. */
    val confirmGrant: AgentGrant? = null,
    /** "Permission removed", "Resumed" and the like, once. */
    val notice: PermissionNotice? = null,
) {
    /** The agent has the most rules it can have (64, §10.12 `share_rules_subject`): no new one. */
    val atRuleLimit: Boolean get() = rules.size >= RULE_LIMIT

    /** This phone's own entry has no page (it has no actions). */
    val gone: Boolean get() = loaded && (device == null || device.thisPhone)

    /** Holds a device's name, its rules and the names of connections: nothing but the type is printed. */
    override fun toString(): String = "DeviceUiState"
}

/**
 * A device's page (ANDROID-PLAN 0.1.53 AA1): its facts, End access (confirmed) and Remove (confirmed). It signs nothing
 * and never asks for the credential password. Fails secure: a request that fails leaves the page as it was and says so.
 */
@HiltViewModel
@Suppress("TooManyFunctions")
class DeviceViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: DevicesRepository,
    private val sharing: SharingRepository,
    private val connections: ConnectionsRepository,
) : ViewModel() {
    private val id: String = saved.get<String>(DeviceRoute.ARG).orEmpty()
    private val local = MutableStateFlow(DeviceUiState())

    /** The number of the latest rules read; declared before `init`, which may start the first read at once. */
    private var rulesRead = 0

    /** The same for the permissions read: only the latest read's failure is shown. */
    private var permissionsRead = 0

    private val base = combine(local, repo.devices, repo.load, sharing.ruleSaved) { s, list, load, saved ->
        s.copy(
            device = list.firstOrNull { it.id == id }, loaded = load == ListLoad.LOADED, ruleSaved = saved == id,
            itemWays = sharing.itemWays,
        )
    }

    val uiState: StateFlow<DeviceUiState> = combine(base, repo.permissions, connections.connections) { s, perms, conns ->
        val mine = perms[id]
        s.copy(
            permissions = mine?.others.orEmpty(), permissionsLoaded = mine != null,
            connectionNames = conns.associate { it.id to it.displayName },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DeviceUiState())

    init {
        // A page opened from a feed item (or after a process start) may be the first reader of the list.
        refresh()
        // The rules are an agent's: read once the list shows this device is one (and on every return to the page).
        viewModelScope.launch {
            repo.devices.map { list -> list.firstOrNull { it.id == id }?.isAgent == true }.distinctUntilChanged().filter { it }
                .collect {
                    readRules()
                    readPermissions()
                }
        }
        // `sync.event`s of other devices: this agent's grants, its pause, and share rules (AA3, §10.1).
        viewModelScope.launch {
            repo.changes.collect { change ->
                if (uiState.value.device?.isAgent != true || !change.concerns(id)) return@collect
                readPermissions()
                // A rule event carries only `rule_id`: the rules are read again with the grants.
                if (change is AgentChange.Rule) readRules()
            }
        }
    }

    /** On opening, on Retry. */
    fun refresh() {
        viewModelScope.launch {
            try {
                repo.refresh()
                local.update { it.copy(loadError = null, loadErrorCode = null) }
            } catch (e: VaultFailure) {
                local.update { it.copy(loadError = e.kind, loadErrorCode = e.shownCode) }
            }
        }
    }

    /** The agent's share rules; on opening and on every return to the page (the editor may have changed them). */
    fun refreshRules() {
        if (uiState.value.device?.isAgent == true) readRules()
    }

    /** The agent's other permissions; on opening, on every return to the page, on Retry. */
    fun refreshPermissions() {
        if (uiState.value.device?.isAgent == true) readPermissions()
    }

    private fun readPermissions() {
        val mine = ++permissionsRead
        viewModelScope.launch {
            try {
                repo.refreshPermissions(id)
                if (mine == permissionsRead) local.update { it.copy(permissionsError = null) }
            } catch (e: VaultFailure) {
                if (mine == permissionsRead) local.update { it.copy(permissionsError = e.kind) }
            }
        }
    }

    private fun readRules() {
        val mine = ++rulesRead
        viewModelScope.launch {
            try {
                val rules = sharing.agentRules(id)
                // An older answer never replaces a newer one.
                if (mine == rulesRead) local.update { it.copy(rules = rules, rulesLoaded = true, rulesError = null) }
            } catch (e: VaultFailure) {
                if (mine == rulesRead) local.update { it.copy(rulesError = e.kind) }
            }
        }
    }


    fun ruleSavedShown() = sharing.ruleSavedShown(id)

    fun askEndAccess() {
        val d = uiState.value.device
        if (local.value.busy || d == null || !d.actionable) return
        local.update { it.copy(confirm = DeviceConfirm.END_ACCESS, failure = null, error = null, errorCode = null) }
    }

    /**
     * Asks to remove the device. For an agent the confirmation names how long a service may still accept its
     * permissions, which the vault is asked for first (the worst case when it cannot be: never a shorter time than true).
     */
    fun askRemove() {
        val d = uiState.value.device ?: return
        if (local.value.busy || !d.actionable) return
        local.update { it.copy(busy = true, failure = null, error = null, errorCode = null) }
        viewModelScope.launch {
            val bound = if (d.isAgent) repo.removalBoundSeconds(d.id) else 0L
            local.update { it.copy(busy = false, confirm = DeviceConfirm.REMOVE, removalBoundSeconds = bound) }
        }
    }

    /** The ⋯ menu's **Remove** on a permission: asks first; nothing is sent until the confirmation. */
    fun askRemovePermission(grantId: String) {
        val grant = uiState.value.permissions.firstOrNull { it.grantId == grantId }
        if (local.value.busy || grant == null || uiState.value.device?.isAgent != true) return
        local.update {
            it.copy(confirm = DeviceConfirm.REMOVE_PERMISSION, confirmGrant = grant, failure = null, error = null, errorCode = null)
        }
    }

    /** **Resume** on the paused banner: no confirmation and no unlock window (it signs nothing). */
    fun resume() {
        val d = uiState.value.device?.takeIf { it.isAgent && it.paused }
        if (local.value.busy || d == null) return
        local.update { it.copy(busy = true, failure = null, error = null, errorCode = null) }
        viewModelScope.launch {
            try {
                val done = repo.resume(d.id)
                val notice = if (done == AgentResumption.RESUMED) PermissionNotice.RESUMED else PermissionNotice.NOT_PAUSED
                local.update { it.copy(busy = false, notice = notice) }
            } catch (e: VaultFailure) {
                local.update { it.copy(busy = false, failure = DeviceFailure.RESUME, error = e.kind, errorCode = e.shownCode) }
            }
        }
    }

    fun noticeShown() = local.update { it.copy(notice = null) }

    fun dismissConfirm() = local.update { it.copy(confirm = null, confirmGrant = null) }

    @Suppress("CyclomaticComplexMethod")
    fun confirm() {
        val s = local.value
        val d = uiState.value.device?.takeIf { it.actionable && !s.busy }
        val what = s.confirm
        if (d == null || what == null) return
        val grant = s.confirmGrant
        if (what == DeviceConfirm.REMOVE_PERMISSION && grant == null) return
        local.update { it.copy(confirm = null, confirmGrant = null, busy = true) }
        viewModelScope.launch {
            try {
                when (what) {
                    DeviceConfirm.REMOVE_PERMISSION -> {
                        val gone = repo.removePermission(d.id, checkNotNull(grant).grantId)
                        val notice = if (gone == PermissionRemoval.REMOVED) PermissionNotice.REMOVED else PermissionNotice.ALREADY_REMOVED
                        local.update { it.copy(busy = false, notice = notice) }
                    }
                    DeviceConfirm.END_ACCESS -> {
                        repo.endAccess(d.id)
                        local.update { it.copy(busy = false, accessEnded = true) }
                    }
                    DeviceConfirm.REMOVE -> {
                        repo.remove(d.id)
                        local.update { it.copy(busy = false, removed = true) }
                    }
                }
            } catch (e: VaultFailure) {
                val failure = when (what) {
                    DeviceConfirm.END_ACCESS -> DeviceFailure.END_ACCESS
                    DeviceConfirm.REMOVE -> DeviceFailure.REMOVE
                    DeviceConfirm.REMOVE_PERMISSION -> DeviceFailure.REMOVE_PERMISSION
                }
                local.update { it.copy(busy = false, failure = failure, error = e.kind, errorCode = e.shownCode) }
                // What the vault says now settles whether the device is still there, with or without access. A refused
                // permission removal keeps its row: the grants are not read again.
                if (what != DeviceConfirm.REMOVE_PERMISSION) runCatching { repo.refresh() }
            }
        }
    }

    fun accessEndedShown() = local.update { it.copy(accessEnded = false) }

    fun dismissFailure() = local.update { it.copy(failure = null, error = null, errorCode = null) }
}

/** A vault holds at most 64 share rules per subject (§10.12 `share_rules_subject`). */
private const val RULE_LIMIT = 64
