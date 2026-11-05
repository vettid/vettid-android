// Debug-only sample data for screenshots: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength", "MagicNumber")

package com.vettid.app.debug

import androidx.compose.runtime.Composable
import com.vettid.core.data.devices.AgentGrant
import com.vettid.core.data.devices.DeviceInfo
import com.vettid.core.data.devices.GrantApproval
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.items.ShareMode
import com.vettid.core.data.items.ShareRule
import com.vettid.core.data.items.TagMatch
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.ui.components.ShellChrome
import com.vettid.feature.devices.DeviceConfirm
import com.vettid.feature.devices.DeviceFailure
import com.vettid.core.data.devices.PairRole
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

    // AA4: the agent's share rules (ANDROID-PLAN 0.1.116).
    private val askRule = ShareRule(
        "r1", 1, null, "a1", listOf("api"), perHour = 60, perDay = 1_000, expiresAt = now.plus(28, ChronoUnit.DAYS),
        included = listOf("i1", "i2"), pending = listOf("i3"), statusTtl = 900,
    )
    private val autoRule = ShareRule("r2", 1, null, "a1", listOf("keys", "ops"), TagMatch.ANY, ShareMode.AUTO, perHour = 5, perDay = 50, included = listOf("i4"))

    // AA3: the agent's other permissions (ANDROID-PLAN 0.1.116 §4 "An agent's permissions").
    private val askGrant = AgentGrant("g1", "connection.list", 1, GrantApproval.ASK, 60, 1_000, null, emptyList(), 900)
    private val autoGrant = AgentGrant(
        "g2", "message.send", 2, GrantApproval.AUTO, 60, 1_000, now.plus(28, ChronoUnit.DAYS), listOf("c1", "c2", "gone"), 3_600,
    )
    private val unknownGrant = AgentGrant("g3", "wallet.send", 1, GrantApproval.ASK, 60, 1_000, null, emptyList(), 900)
    private val names = mapOf("c1" to "Sam Lee", "c2" to "Ana Ruiz")

    private val code = PairingCode("p1", "eyJ2IjoyLCJ0IjoiYSIsInIiOiJodHRwczovL3JlbGF5LnZldHRpZC5vcmcifQ", now.plus(581, ChronoUnit.SECONDS))
    private val pending = PendingPairing("p1", "Claude on work laptop", "123456", now.plus(10, ChronoUnit.MINUTES))

    @Composable
    private fun Pair(
        stage: PairingStage,
        sheet: Boolean = false,
        held: Boolean = false,
        error: FailureKind? = null,
        role: PairRole = PairRole.AGENT,
    ) = PairScreen(PairUiState(PairingState(stage, error = error, role = role), held = held, sheet = sheet), PairActions())

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
        "devices.page_agent_rules" to { Page(DeviceUiState(device = agent, loaded = true, rules = listOf(askRule), rulesLoaded = true)) },
        "devices.page_agent_rules_ways" to {
            Page(DeviceUiState(device = agent, loaded = true, rules = listOf(askRule, autoRule), rulesLoaded = true, itemWays = true))
        },
        "devices.page_agent_rules_warning" to {
            Page(DeviceUiState(device = agent, loaded = true, rules = listOf(askRule, autoRule), rulesLoaded = true, itemWays = false))
        },
        "devices.page_agent_rules_none" to { Page(DeviceUiState(device = agent, loaded = true, rulesLoaded = true)) },
        "devices.page_agent_rules_failed" to { Page(DeviceUiState(device = agent, loaded = true, rulesError = FailureKind.NETWORK)) },
        "devices.page_agent_rules_limit" to {
            Page(DeviceUiState(device = agent, loaded = true, rulesLoaded = true, rules = (1..64).map { askRule.copy(ruleId = "r$it", tags = listOf("tag$it")) }))
        },
        "devices.page_agent_rule_saved" to { Page(DeviceUiState(device = agent, loaded = true, rules = listOf(askRule), rulesLoaded = true, ruleSaved = true)) },
        "devices.page_agent_permissions" to {
            Page(DeviceUiState(device = agent, loaded = true, permissions = listOf(askGrant, autoGrant, unknownGrant), permissionsLoaded = true, connectionNames = names))
        },
        "devices.page_agent_permissions_failed" to { Page(DeviceUiState(device = agent, loaded = true, permissionsError = FailureKind.NETWORK)) },
        "devices.page_agent_permissions_failed_rows" to {
            Page(DeviceUiState(device = agent, loaded = true, permissions = listOf(askGrant), permissionsLoaded = true, permissionsError = FailureKind.NETWORK))
        },
        "devices.page_agent_permissions_unreadable_grant" to {
            Page(
                DeviceUiState(
                    device = agent, loaded = true, permissionsLoaded = true,
                    permissions = listOf(AgentGrant(null, "", 0, GrantApproval.UNKNOWN, 60, 1_000, null, emptyList(), 3_600), askGrant),
                ),
            )
        },
        "devices.page_agent_paused" to {
            Page(DeviceUiState(device = agent.copy(paused = true), loaded = true, permissions = listOf(askGrant), permissionsLoaded = true))
        },
        "devices.page_agent_paused_failed" to {
            Page(DeviceUiState(device = agent.copy(paused = true), loaded = true, failure = DeviceFailure.RESUME, error = FailureKind.NETWORK))
        },
        "devices.page_agent_permission_remove" to {
            Page(
                DeviceUiState(
                    device = agent, loaded = true, permissions = listOf(autoGrant), permissionsLoaded = true, connectionNames = names,
                    confirm = DeviceConfirm.REMOVE_PERMISSION, confirmGrant = autoGrant,
                ),
            )
        },
        "devices.page_agent_permission_remove_unknown" to {
            Page(
                DeviceUiState(
                    device = agent, loaded = true, permissions = listOf(unknownGrant), permissionsLoaded = true,
                    confirm = DeviceConfirm.REMOVE_PERMISSION, confirmGrant = unknownGrant,
                ),
            )
        },
        "devices.page_agent_permission_remove_failed" to {
            Page(
                DeviceUiState(
                    device = agent, loaded = true, permissions = listOf(askGrant), permissionsLoaded = true,
                    failure = DeviceFailure.REMOVE_PERMISSION, error = FailureKind.NETWORK,
                ),
            )
        },
        "devices.pair_paired_unknown" to { Pair(PairingStage.Paired("Claude on work laptop", null)) },
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
        // Pair a desktop (owner decision 2026-11-05): the desktop's variants of the pairing screens.
        "devices.pair_desktop_code" to { Pair(PairingStage.Code(code), role = PairRole.DESKTOP) },
        "devices.pair_desktop_expired" to { Pair(PairingStage.CodeExpired("p1", now), role = PairRole.DESKTOP) },
        "devices.pair_desktop_failed" to { Pair(PairingStage.Idle, error = FailureKind.NETWORK, role = PairRole.DESKTOP) },
        "devices.pair_desktop_compare" to { Pair(PairingStage.Compare(pending), role = PairRole.DESKTOP) },
        "devices.pair_desktop_compare_sheet" to { Pair(PairingStage.Compare(pending), sheet = true, role = PairRole.DESKTOP) },
        "devices.pair_desktop_cannot_compare" to {
            Pair(PairingStage.Compare(PendingPairing("p1", "Work laptop", null, pending.ends)), role = PairRole.DESKTOP)
        },
        "devices.pair_desktop_rejected" to { Pair(PairingStage.Rejected(told = true), role = PairRole.DESKTOP) },
        "devices.pair_desktop_paired" to { Pair(PairingStage.Paired("Work laptop", "d1"), role = PairRole.DESKTOP) },
        "devices.pair_desktop_ended" to { Pair(PairingStage.Ended, role = PairRole.DESKTOP) },
    )
}
