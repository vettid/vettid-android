package com.vettid.feature.devices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
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

/** Pairing an agent (AA2); [held]: opened from the feed item "A device asks to pair", so it shows what is held, never a new code. */
@Serializable
data class PairRoute(val held: Boolean = false) {
    companion object {
        const val HELD = "held"
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
}

@Composable
private fun DevicePageContent(vm: DeviceViewModel, state: DeviceUiState, host: DevicesHost) {
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
        ),
        now = rememberNow(),
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
            // The agent's page (what it may use is chosen there from AA4 on).
            onOpenPage = { id ->
                vm.done()
                host.onBack()
                host.navigate(DeviceRoute(id))
            },
            onOwnerCheck = host.onOwnerCheck,
        ),
    )
}
