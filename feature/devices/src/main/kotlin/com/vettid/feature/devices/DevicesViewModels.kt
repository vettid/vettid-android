package com.vettid.feature.devices

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.devices.DevicesRepository
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.OwnerCheckRepository
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.checkCleared
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    /** The name of the device just removed on its page ("" when it had none): the snackbar "<name> was removed". */
    val removedName: String? = null,
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
            s.copy(devices = list, load = load, removedName = removed)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, DevicesUiState())

    init {
        refresh()
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
enum class DeviceConfirm { END_ACCESS, REMOVE }

/** What a failed request on a device's page says (the vault's own failure text for a refused owner check). */
enum class DeviceFailure { END_ACCESS, REMOVE }

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
) {
    /** This phone's own entry has no page (it has no actions). */
    val gone: Boolean get() = loaded && (device == null || device.thisPhone)
}

/**
 * A device's page (ANDROID-PLAN 0.1.53 AA1): its facts, End access (confirmed) and Remove (confirmed). It signs nothing
 * and never asks for the credential password. Fails secure: a request that fails leaves the page as it was and says so.
 */
@HiltViewModel
class DeviceViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: DevicesRepository,
) : ViewModel() {
    private val id: String = saved.get<String>(DeviceRoute.ARG).orEmpty()
    private val local = MutableStateFlow(DeviceUiState())

    val uiState: StateFlow<DeviceUiState> = combine(local, repo.devices, repo.load) { s, list, load ->
        s.copy(device = list.firstOrNull { it.id == id }, loaded = load == ListLoad.LOADED)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DeviceUiState())

    init {
        // A page opened from a feed item (or after a process start) may be the first reader of the list.
        viewModelScope.launch { runCatching { repo.refresh() } }
    }

    /** On return to the page. */
    fun refresh() {
        viewModelScope.launch { runCatching { repo.refresh() } }
    }

    fun askEndAccess() {
        if (local.value.busy) return
        local.update { it.copy(confirm = DeviceConfirm.END_ACCESS, failure = null, error = null, errorCode = null) }
    }

    /**
     * Asks to remove the device. For an agent the confirmation names how long a service may still accept its
     * permissions, which the vault is asked for first (the worst case when it cannot be: never a shorter time than true).
     */
    fun askRemove() {
        val d = uiState.value.device ?: return
        if (local.value.busy || d.thisPhone) return
        local.update { it.copy(busy = true, failure = null, error = null, errorCode = null) }
        viewModelScope.launch {
            val bound = if (d.isAgent) repo.removalBoundSeconds(d.id) else 0L
            local.update { it.copy(busy = false, confirm = DeviceConfirm.REMOVE, removalBoundSeconds = bound) }
        }
    }

    fun dismissConfirm() = local.update { it.copy(confirm = null) }

    fun confirm() {
        val s = local.value
        val d = uiState.value.device?.takeIf { !it.thisPhone && !s.busy }
        val what = s.confirm
        if (d == null || what == null) return
        local.update { it.copy(confirm = null, busy = true) }
        viewModelScope.launch {
            try {
                when (what) {
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
                val failure = if (what == DeviceConfirm.END_ACCESS) DeviceFailure.END_ACCESS else DeviceFailure.REMOVE
                local.update { it.copy(busy = false, failure = failure, error = e.kind, errorCode = e.shownCode) }
                // What the vault says now settles whether the device is still there, with or without access.
                runCatching { repo.refresh() }
            }
        }
    }

    fun accessEndedShown() = local.update { it.copy(accessEnded = false) }

    fun dismissFailure() = local.update { it.copy(failure = null, error = null, errorCode = null) }
}
