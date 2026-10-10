package com.vettid.feature.history

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.vault.AuditRecord
import com.vettid.core.ui.components.ShellChrome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * The device of a History entry (VAULT-MESSAGING 0.23.2: `vault.unlocked` and a device's `vault.locked` carry
 * `device_id`; ANDROID-PLAN 0.1.27): its name from `device.list` on the row's second line and the entry page,
 * "Removed device" for a device no longer listed, nothing for an entry without a device, "(this phone)" for this one.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    private val t0: Instant = Instant.parse("2026-10-10T09:00:00Z")
    private val devices = mapOf("d-pixel" to "Pixel 10 Pro", "d-desk" to "Desktop")

    private fun rec(seq: Long, kind: String, device: String? = null, conn: String? = null) =
        AuditRecord("e$seq", seq, t0.plusSeconds(seq), kind, connectionId = conn, deviceId = device)

    @Test
    fun theNameIsTheListedOneRemovedOnceUnlistedAndNothingWithoutADeviceOrAList() {
        assertEquals("Pixel 10 Pro", HistoryDevice.name("d-pixel", devices, "Removed device"))
        assertEquals("Removed device", HistoryDevice.name("d-gone", devices, "Removed device"))
        assertNull(HistoryDevice.name(null, devices, "Removed device"))
        assertNull(HistoryDevice.name("d-pixel", null, "Removed device"))
        assertEquals("Ada · Pixel 10 Pro", HistoryDevice.supporting(listOf(null, "Ada", "Pixel 10 Pro"), "Category"))
        assertEquals("Category", HistoryDevice.supporting(listOf(null, null, null), "Category"))
    }

    private fun list(deviceNames: Map<String, String>?) = rule.setContent {
        HistoryScreen(
            state = HistoryUiState(
                entries = listOf(
                    rec(5, "message.sent", "d-desk", conn = "c1"),
                    rec(4, "vault.unlocked", "d-pixel"),
                    rec(3, "vault.locked", "d-gone"),
                    rec(2, "vault.locked"),
                ),
                connectionNames = mapOf("c1" to "Ada Lovelace"),
                deviceNames = deviceNames,
                loading = false,
                end = true,
            ),
            chrome = ShellChrome(accountName = "Me", onMenuClick = {}, onAvatarClick = {}),
            actions = HistoryActions(),
        )
    }

    @Test
    fun rowsNameTheirDeviceAfterWhatTheyAlreadyShow() {
        list(devices)
        rule.onNodeWithTag("history_entry_5").assertTextContains("Ada Lovelace · Desktop")
        rule.onNodeWithTag("history_entry_4").assertTextContains("Vault unlocked").assertTextContains("Pixel 10 Pro")
        rule.onNodeWithTag("history_entry_3").assertTextContains("Vault locked").assertTextContains("Removed device")
        assertEquals(1, rule.onAllNodesWithText("Removed device").fetchSemanticsNodes().size)
        // No device: unchanged, the category, no placeholder.
        rule.onNodeWithTag("history_list").performScrollToNode(hasTestTag("history_entry_2"))
        rule.onNodeWithTag("history_entry_2").assertTextContains("Unlocks and owner checks")
    }

    @Test
    fun withoutTheDeviceListRowsNameNoDevice() {
        list(null)
        rule.onNodeWithTag("history_entry_4").assertTextContains("Unlocks and owner checks")
        assertEquals(0, rule.onAllNodesWithText("Removed device").fetchSemanticsNodes().size)
        rule.onNodeWithTag("history_entry_5").assertTextContains("Ada Lovelace")
        assertEquals(0, rule.onAllNodesWithText("Desktop", substring = true).fetchSemanticsNodes().size)
    }

    private fun detail(e: AuditRecord, state: HistoryEntryUiState) = rule.setContent {
        HistoryEntryScreen(state.copy(entry = e, loading = false), onBack = {})
    }

    @Test
    fun theEntryPageNamesTheDeviceAndMarksThisPhone() {
        detail(
            rec(4, "vault.unlocked", "d-pixel"),
            HistoryEntryUiState(deviceName = "Pixel 10 Pro", devicesKnown = true, thisDevice = true),
        )
        rule.onNodeWithTag("history_entry_device").assertTextContains("Device").assertTextContains("Pixel 10 Pro (this phone)")
        rule.onNodeWithText("d-pixel").assertExists()
    }

    @Test
    fun theEntryPageNamesAnotherDeviceWithoutTheMarker() {
        detail(rec(4, "vault.unlocked", "d-desk"), HistoryEntryUiState(deviceName = "Desktop", devicesKnown = true))
        rule.onNodeWithTag("history_entry_device").assertTextContains("Desktop")
        assertEquals(0, rule.onAllNodesWithText("this phone", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
    }

    @Test
    fun theEntryPageSaysRemovedDeviceForAnUnlistedOne() {
        detail(rec(3, "vault.locked", "d-gone"), HistoryEntryUiState(devicesKnown = true))
        rule.onNodeWithTag("history_entry_device").assertTextContains("Removed device")
        rule.onNodeWithText("d-gone").assertExists()
    }

    @Test
    fun theEntryPageHasNoDeviceLineWithoutADevice() {
        detail(rec(2, "vault.locked"), HistoryEntryUiState(devicesKnown = true))
        assertEquals(0, rule.onAllNodesWithTag("history_entry_device").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Device", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun withoutTheDeviceListTheEntryPageShowsTheIdAlone() {
        detail(rec(4, "vault.unlocked", "d-pixel"), HistoryEntryUiState())
        assertEquals(0, rule.onAllNodesWithTag("history_entry_device").fetchSemanticsNodes().size)
        rule.onNodeWithText("d-pixel").assertExists()
    }
}
