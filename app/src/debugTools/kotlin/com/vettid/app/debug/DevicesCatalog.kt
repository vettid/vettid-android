// Debug-only sample data for screenshots: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength", "MagicNumber")

package com.vettid.app.debug

import androidx.compose.runtime.Composable
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.ui.components.ShellChrome
import com.vettid.feature.devices.DeviceConfirm
import com.vettid.feature.devices.DeviceFailure
import com.vettid.core.data.devices.PairingCode
import com.vettid.core.data.devices.PairingStage
import com.vettid.core.data.devices.PairingState
import com.vettid.core.data.devices.PendingPairing
import com.vettid.feature.devices.DevicePageActions
import com.vettid.feature.devices.PairActions
import com.vettid.feature.devices.PairScreen
import com.vettid.feature.devices.PairUiState
import com.vettid.feature.devices.DevicePageScreen
import com.vettid.feature.devices.DeviceUiState
import com.vettid.feature.devices.DevicesScreen
import com.vettid.feature.devices.DevicesUiState
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Devices & agents (ANDROID-PLAN 0.1.53 AA1): the list (empty, agents and desktops, a paused agent, failed), a device's
 * page with and without access, and each confirmation and notice.
 */
internal object DevicesCatalog {
    private val now = Instant.now()
    private val chrome = ShellChrome(accountName = "Sam Rivera", onMenuClick = {}, onAvatarClick = {})
    private const val IK = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    private val phone = DeviceInfo("me", "app", "Pixel 10 Pro", null, null, null, null, thisPhone = true)
    private val agent = DeviceInfo(
        "a1", "agent", "Claude on work laptop", IK, now.minus(3, ChronoUnit.DAYS), now.minus(10, ChronoUnit.MINUTES), now.plus(90, ChronoUnit.MINUTES),
    )
    private val paused = DeviceInfo("a2", "agent", "Research agent", IK, now.minus(9, ChronoUnit.DAYS), now.minus(2, ChronoUnit.HOURS), null, paused = true)
    private val desktop = DeviceInfo("d1", "desktop", "Office desktop", IK, now.minus(40, ChronoUnit.DAYS), null, null)
    private val unknown = DeviceInfo("x1", "kiosk", "Front desk", IK, now.minus(20, ChronoUnit.DAYS), now.minus(5, ChronoUnit.DAYS), null)

    private fun list(vararg d: DeviceInfo) = DevicesUiState(devices = d.toList(), load = ListLoad.LOADED)

    @Composable
    private fun Screen(s: DevicesUiState) = DevicesScreen(s, chrome)

    @Composable
    private fun Page(s: DeviceUiState) = DevicePageScreen(s, DevicePageActions())

    private val code = PairingCode("p1", "eyJ2IjoyLCJ0IjoiYSIsInIiOiJodHRwczovL3JlbGF5LnZldHRpZC5vcmcifQ", now.plus(581, ChronoUnit.SECONDS))
    private val pending = PendingPairing("p1", "Claude on work laptop", "123456", now.plus(10, ChronoUnit.MINUTES))

    @Composable
    private fun Pair(stage: PairingStage, sheet: Boolean = false, held: Boolean = false, error: FailureKind? = null) =
        PairScreen(PairUiState(PairingState(stage, error = error), held = held, sheet = sheet), PairActions())

    val screens: Map<String, @Composable () -> Unit> = linkedMapOf(
        "devices" to { Screen(list(phone, agent, paused, desktop, unknown)) },
        "devices.empty" to { Screen(list(phone)) },
        "devices.loading" to { Screen(DevicesUiState(load = ListLoad.LOADING)) },
        "devices.failed" to { Screen(DevicesUiState(load = ListLoad.FAILED, error = FailureKind.NETWORK)) },
        "devices.removed" to { Screen(list(phone, agent).copy(removedDevice = agent.copy(name = "Old agent"))) },
        "devices.page_agent" to { Page(DeviceUiState(device = agent, loaded = true)) },
        "devices.page_no_access" to { Page(DeviceUiState(device = paused, loaded = true)) },
        "devices.page_desktop" to { Page(DeviceUiState(device = desktop, loaded = true)) },
        "devices.end_access_confirm" to { Page(DeviceUiState(device = agent, loaded = true, confirm = DeviceConfirm.END_ACCESS)) },
        "devices.remove_agent_confirm" to { Page(DeviceUiState(device = agent, loaded = true, confirm = DeviceConfirm.REMOVE, removalBoundSeconds = 960)) },
        "devices.remove_agent_no_permissions" to { Page(DeviceUiState(device = agent, loaded = true, confirm = DeviceConfirm.REMOVE)) },
        "devices.remove_desktop_confirm" to { Page(DeviceUiState(device = desktop, loaded = true, confirm = DeviceConfirm.REMOVE)) },
        "devices.end_failed" to { Page(DeviceUiState(device = agent, loaded = true, failure = DeviceFailure.END_ACCESS, error = FailureKind.NETWORK)) },
        "devices.remove_failed" to { Page(DeviceUiState(device = agent, loaded = true, failure = DeviceFailure.REMOVE, error = FailureKind.NETWORK)) },
        "devices.pair_code" to { Pair(PairingStage.Code(code)) },
        "devices.pair_expired" to { Pair(PairingStage.CodeExpired("p1", now)) },
        "devices.pair_failed" to { Pair(PairingStage.Idle, error = FailureKind.NETWORK) },
        "devices.pair_compare" to { Pair(PairingStage.Compare(pending)) },
        "devices.pair_compare_sheet" to { Pair(PairingStage.Compare(pending), sheet = true) },
        "devices.pair_cannot_compare" to { Pair(PairingStage.Compare(PendingPairing("p1", "Claude", null, pending.ends))) },
        "devices.pair_rejected" to { Pair(PairingStage.Rejected(told = true)) },
        "devices.pair_paired" to { Pair(PairingStage.Paired("Claude on work laptop", "a1")) },
        "devices.pair_ended" to { Pair(PairingStage.Ended) },
        "devices.pair_lost" to { Pair(PairingStage.Idle, held = true) },
    )
}
