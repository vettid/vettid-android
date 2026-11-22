package com.vettid.feature.devices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.vettid.core.data.devices.AgentRuleRoute
import com.vettid.core.ui.components.ShellChrome
import kotlinx.serialization.Serializable

/** Devices & agents (the drawer, after Vault): ANDROID-PLAN 0.1.53 AA1. */
@Serializable
data object DevicesRoute

/** One device's page, by its `device_id`. */
@Serializable
data class DeviceRoute(val deviceId: String) {
    companion object {
        const val ARG = "deviceId"
    }
}

/**
 * Pairing an agent (AA2) or, with [desktop] (owner decision 2026-11-05), a desktop; [held]: opened from the feed item "A device
 * asks to pair", so it shows what is held, never a new code (what is held says its own role).
 */
@Serializable
data class PairRoute(val held: Boolean = false, val desktop: Boolean = false) {
    companion object {
        const val HELD = "held"
        const val DESKTOP = "desktop"
    }
}

/** How an agent may use one item (ANDROID-PLAN 0.1.137 AA7), by the agent's `device_id` and the item's id. */
@Serializable
data class ItemWaysRoute(val agentId: String, val itemId: String) {
    companion object {
        const val AGENT = "agentId"
        const val ITEM = "itemId"
    }
}

/** What the Devices & agents destinations need from the app shell. */
data class DevicesHost(
    val navigate: (Any) -> Unit,
    val onBack: () -> Unit,
    /** Opens History with its search set to the device's name (§10.9: the vault's search matches device names). */
    val onOpenHistory: (String) -> Unit,
    /** The daily owner check (a refused pairing request says it is due). */
    val onOwnerCheck: () -> Unit = {},
    /** The credential alarm (AA7: a widening refused while an alarm is open). */
    val onOpenAlarm: () -> Unit = {},
)

/** Registers the list and a device's page. */
fun NavGraphBuilder.devicesDestination(chrome: ShellChrome, host: DevicesHost) {
    composable<DevicesRoute> {
        val vm: DevicesViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        // Read when the screen opens and on return to it (the ViewModel outlives the screen).
        LaunchedEffect(Unit) { vm.refresh() }
        DevicesScreen(
            state,
            chrome,
            now = rememberNow(),
            onOpen = { host.navigate(DeviceRoute(it)) },
            onRetry = vm::refresh,
            onRemovedShown = vm::removedShown,
            onPair = { host.navigate(PairRoute()) },
            onPairDesktop = { host.navigate(PairRoute(desktop = true)) },
        )
    }
    composable<PairRoute> {
        val vm: PairViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        PairContent(vm, state, host)
    }
    composable<DeviceRoute> {
        val vm: DeviceViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        DevicePageContent(vm, state, host)
    }
    composable<ItemWaysRoute> {
        val vm: ItemWaysViewModel = hiltViewModel()
        val state by vm.uiState.collectAsStateWithLifecycle()
        ItemWaysContent(vm, state, host)
    }
}

@Composable
private fun DevicePageContent(vm: DeviceViewModel, state: DeviceUiState, host: DevicesHost) {
    // The editor (AA4) may have changed the rules: read them on every return to the page.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshRules()
        vm.refreshPermissions()
    }
    DevicePageScreen(
        state,
        DevicePageActions(
            onBack = host.onBack,
            onEndAccess = vm::askEndAccess,
            onRemove = vm::askRemove,
            onHistory = host.onOpenHistory,
            onConfirm = vm::confirm,
            onDismissConfirm = vm::dismissConfirm,
            onDismissFailure = vm::dismissFailure,
            onAccessEndedShown = vm::accessEndedShown,
            onRetry = vm::refresh,
            onNewRule = { state.device?.let { host.navigate(AgentRuleRoute(it.id)) } },
            onOpenRule = { rule -> state.device?.let { host.navigate(AgentRuleRoute(it.id, rule)) } },
            onRetryRules = vm::refreshRules,
            onRuleSavedShown = vm::ruleSavedShown,
            onOpenWayItem = { item -> state.device?.let { host.navigate(ItemWaysRoute(it.id, item)) } },
            onRetryWayItems = vm::refreshWayItems,
            onRemovePermission = vm::askRemovePermission,
            onRetryPermissions = vm::refreshPermissions,
            onResume = vm::resume,
            onNoticeShown = vm::noticeShown,
            onRemovedNoticeShown = vm::removedNoticeShown,
        ),
        now = rememberNow(),
    )
}

@Composable
private fun ItemWaysContent(vm: ItemWaysViewModel, state: ItemWaysUiState, host: DevicesHost) {
    // Back on the page (not the first showing, which reads already): read again if nothing is unsaved.
    val shown = remember { BooleanArray(1) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (shown[0]) vm.returned() else shown[0] = true
    }
    LaunchedEffect(state.ownerCheck) {
        if (!state.ownerCheck) return@LaunchedEffect
        vm.ownerCheckShown()
        host.onOwnerCheck()
    }
    ItemWaysScreen(
        state,
        ItemWaysActions(
            onBack = host.onBack,
            onSave = vm::save,
            onRetry = vm::retry,
            onShowCurrent = vm::load,
            onRun = vm::setRun,
            onProxy = vm::setProxy,
            onReveal = vm::setReveal,
            onEach = vm::setEach,
            onConfirm = vm::confirm,
            onDismissConfirm = vm::dismissConfirm,
            onProgramInput = vm::setProgramInput,
            onAddProgram = vm::addProgram,
            onOpenProgram = vm::openProgram,
            onCloseProgram = { vm.closeProgram() },
            onEditInput = vm::setEditInput,
            onApplyEdit = { vm.applyEdit() },
            onRemoveProgram = vm::removeProgram,
            onForgetPin = vm::forgetPin,
            onShowPin = vm::showFullPin,
            onVariableInput = vm::setVariableInput,
            onAddVariable = vm::addVariable,
            onRemoveVariable = vm::removeVariable,
            onHostInput = vm::setHostInput,
            onAddHost = vm::addHost,
            onRemoveHost = vm::removeHost,
            onHeader = vm::setHeader,
            onPrefix = vm::setPrefix,
            onPassword = vm::setPassword,
            onSubmitPassword = vm::submitPassword,
            onDismissPrompt = vm::dismissPrompt,
            onOpenAlarm = host.onOpenAlarm,
            onNoticeShown = vm::noticeShown,
            onCloseWindowNow = vm::closeWindowNow,
        ),
    )
}

@Composable
private fun PairContent(vm: PairViewModel, state: PairUiState, host: DevicesHost) {
    PairScreen(
        state,
        PairActions(
            // Leaving a pairing not approved rejects it (the view model's end); a final stage is read.
            onBack = {
                vm.done()
                host.onBack()
            },
            onNewCode = vm::newCode,
            onCopied = vm::copied,
            onCancel = {
                vm.cancel()
                host.onBack()
            },
            onCodesMatch = vm::codesMatch,
            onCloseSheet = vm::closeSheet,
            onChoose = vm::choose,
            onPair = vm::pair,
            onReject = vm::reject,
            onDone = {
                vm.done()
                host.onBack()
            },
            // Choose what it may use (AA4): the agent's page, then its share-rule editor on top; no id known: the list.
            onChooseItems = { id ->
                vm.done()
                host.onBack()
                if (id != null) {
                    host.navigate(DeviceRoute(id))
                    host.navigate(AgentRuleRoute(id))
                }
            },
            onOwnerCheck = host.onOwnerCheck,
        ),
    )
}
