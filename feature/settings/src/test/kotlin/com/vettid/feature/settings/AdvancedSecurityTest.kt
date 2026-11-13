package com.vettid.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vettid.core.data.feed.AdvancedSecurityRow
import com.vettid.core.data.prefs.AppLockTimeout
import com.vettid.core.data.prefs.AppPreferences
import com.vettid.core.data.vault.IdleLockSetting
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.prefs.ThemePreference
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * ANDROID-PLAN 0.1.124 §4 "Advanced security": Security keeps its six rows (Credential, Recovery, the app lock and its
 * method, Check now, Move to a new phone, Advanced security) and the rest moved, in the plan's order, each opening as before.
 */
@RunWith(RobolectricTestRunner::class)
class AdvancedSecurityTest {
    @get:Rule
    val rule = createComposeRule()

    private val view = OwnerCheckView(OwnerCheckState.OK, Instant.now().plusSeconds(1_800), 86_400, 0, hold = true, holdOffUntil = null)
    private val prefs = AppPreferences(ThemePreference.SYSTEM, true, AppLockTimeout.FIVE_MINUTES)
    private val ownerCheck = OwnerCheckSettingsUiState(view = view, idleLock = IdleLockSetting(604_800))
    private val calls = mutableListOf<String>()
    private val actions = SettingsActions(
        attestation = { calls += "attestation" },
        newPhoneSecret = { calls += "new_phone_secret" },
        advancedSecurity = { calls += "advanced" },
        credential = { calls += "credential" },
        recovery = { calls += "recovery" },
        transfer = { calls += "transfer" },
        setTimeout = { calls += "timeout $it" },
    )
    private val ownerCheckActions = OwnerCheckSettingsActions(
        checkNow = { calls += "check_now" },
        setHold = { calls += "hold $it" },
        setIntervalHours = { calls += "interval $it" },
    )

    private val moved = listOf(
        "owner_check_interval", "owner_check_hold", "idle_lock", "app_lock_timeout", "attestation", "new_phone_secret_row",
        "allow_screenshots",
    )

    @Test
    fun securityKeepsItsSixRowsAndNoneOfTheMovedOnes() {
        rule.setContent {
            SettingsContent(
                SettingsUiState(phoneSecret = true, appLockOn = true, preferences = prefs), actions, ownerCheck, ownerCheckActions,
            )
        }
        for (tag in listOf("recovery", "app_lock", "app_lock_method", "owner_check_now", "transfer", "advanced_security")) {
            rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithText("Credential").performScrollTo().assertIsDisplayed()
        for (tag in moved) assertEquals(tag, 0, rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size)
        // By their texts too (on Settings some of them had no tag).
        val movedTexts = listOf(
            "Daily check", "Hold the vault when a check is late", "Lock my vault by itself after", "Lock after", "Attestation",
            "New phone secret", "Allow screenshots",
        )
        for (text in movedTexts) {
            assertEquals(text, 0, rule.onAllNodesWithText(text).fetchSemanticsNodes().size)
        }
        // In the plan's order: Credential, Recovery, the app lock, its method, Check now, Move, Advanced security.
        val tops = listOf("recovery", "app_lock", "app_lock_method", "owner_check_now", "transfer", "advanced_security")
            .map { rule.onNodeWithTag(it).fetchSemanticsNode().positionInRoot.y }
        assertEquals(tops.sorted(), tops)
        rule.onNodeWithText("Check interval, idle lock, attestation and more").assertExists()
        rule.onNodeWithTag("advanced_security").performScrollTo().performClick()
        rule.onNodeWithTag("owner_check_now").performScrollTo().performClick()
        assertEquals(listOf("advanced", "check_now"), calls)
    }

    @Test
    fun advancedSecurityHoldsTheMovedRowsInOrderEachOpeningAsBefore() {
        rule.setContent {
            AdvancedSecurityContent(
                SettingsUiState(phoneSecret = true, appLockOn = true, preferences = prefs), actions, ownerCheck, ownerCheckActions,
            )
        }
        for (tag in moved) rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        val tops = moved.map { rule.onNodeWithTag(it).fetchSemanticsNode().positionInRoot.y }
        assertEquals(tops.sorted(), tops)
        assertEquals(0, rule.onAllNodesWithTag("owner_check_now").fetchSemanticsNodes().size)
        rule.onNodeWithTag("attestation").performScrollTo().performClick()
        rule.onNodeWithTag("new_phone_secret_row").performScrollTo().performClick()
        rule.onNodeWithTag("owner_check_hold").performScrollTo().performClick()
        assertEquals(listOf("attestation", "new_phone_secret", "hold false"), calls)
        // The pickers open as before.
        rule.onNodeWithTag("owner_check_interval").performScrollTo().performClick()
        rule.onNodeWithText("Every 12 hours").performClick()
        rule.onNodeWithTag("app_lock_timeout").performScrollTo().performClick()
        rule.onNodeWithText("Immediately").performClick()
        assertEquals("interval 12", calls[3])
        assertEquals("timeout ${AppLockTimeout.IMMEDIATELY}", calls[4])
    }

    @Test
    fun theTimeoutOnlyWithTheAppLockAndTheSecretOnlyWhenItApplies() {
        rule.setContent { AdvancedSecurityContent(SettingsUiState(), actions) }
        rule.onNodeWithTag("attestation").assertExists()
        rule.onNodeWithTag("allow_screenshots").performScrollTo().assertIsDisplayed()
        for (tag in listOf("app_lock_timeout", "new_phone_secret_row", "owner_check_interval", "owner_check_hold", "idle_lock")) {
            assertEquals(tag, 0, rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size)
        }
    }

    /** A short screen, so that the lower rows are out of view until the screen scrolls to one. */
    private fun showShort(focus: AdvancedSecurityRow?, state: androidx.compose.runtime.State<SettingsUiState>) = rule.setContent {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.height(320.dp)) {
            AdvancedSecurityContent(state.value, actions, ownerCheck, ownerCheckActions, focus = focus)
        }
    }

    private val full = SettingsUiState(phoneSecret = true, appLockOn = true, preferences = prefs)

    @Test
    fun withoutAFocusTheLowerRowsAreOutOfView() {
        showShort(null, androidx.compose.runtime.mutableStateOf(full))
        rule.onNodeWithTag("new_phone_secret_row").assertIsNotDisplayed()
    }

    /** Opened at a row (a notification): scrolled into view, also when what shows the row arrives after the screen. */
    @Test
    fun openedAtARowItIsBroughtIntoViewOnceItIsThere() {
        val state = androidx.compose.runtime.mutableStateOf(full.copy(phoneSecret = false))
        showShort(AdvancedSecurityRow.NEW_PHONE_SECRET, state)
        rule.onNodeWithTag("new_phone_secret_row").assertDoesNotExist()
        state.value = full
        rule.waitForIdle()
        rule.onNodeWithTag("new_phone_secret_row").assertIsDisplayed()
    }

    @Test
    fun theRouteNamesItsRow() {
        for (row in AdvancedSecurityRow.entries) assertEquals(row, AdvancedSecurityRoute(row).row)
        assertEquals(null, AdvancedSecurityRoute().row)
        assertEquals("an unknown name is no row", null, AdvancedSecurityRoute("NOPE").row)
        assertTrue(calls.isEmpty())
    }
}
