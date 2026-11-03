// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.approvals

import com.vettid.core.data.items.FieldLabel
import com.vettid.core.data.items.ItemSummary
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.DataView
import com.vettid.core.data.social.HeldRequest
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.time.Instant

/** Nothing an agent sent, no name, id or item is printed or logged (AA5; the same rule as every vault content). */
class DeviceAsksPrivacyTest {
    @Test
    fun noStateOrModelPrintsWhatItHolds() {
        val items = listOf(ItemSummary("item-id-7", 1, "My Secret Item", "other", Sensitivity.SECRET, fieldLabels = listOf(FieldLabel("fld-9", "Secret Label", "text"))))
        val a = Approval.DeviceRequest(
            "approval.pending", "appr-5", "Evil Name", "agent", "agent.request", Instant.EPOCH, null, deviceId = "dev-3",
            held = HeldRequest.ItemUse("item-id-7", "fld-9", "payload-data".toByteArray()),
        )
        val shown = listOf(
            a.toString(),
            DeviceAsks.show(a, items).toString(),
            DeviceAsks.show(a.copy(held = HeldRequest.ItemGet("item-id-7", listOf("fld-9"))), items).toString(),
            DeviceAsks.show(a.copy(held = HeldRequest.Later("item-id-7")), items).toString(),
            HeldRequest.ItemUse("item-id-7", "fld-9", "payload-data".toByteArray()).toString(),
            DeviceOutcome.Denied.toString(),
        )
        // The state prints its key (the ask's id) and nothing else of the ask.
        val state = ApprovalDetailUiState(a.key, a, items = items).toString()
        listOf("payload-data", "Evil Name", "dev-3", "My Secret Item", "Secret Label", "fld-9").forEach { assertFalse(it in state) }
        val forbidden = listOf("payload-data", "Evil Name", "dev-3", "appr-5", "My Secret Item", "Secret Label", "item-id-7", "fld-9")
        shown.forEach { s -> forbidden.forEach { assertFalse("$s holds $it", it in s) } }
        // The decoded view itself is the one place the data is, and only for the screen.
        assertFalse(DataView.of("payload-data".toByteArray()).toString().isEmpty())
    }

    @Test
    fun theSourcesOfTheDeviceRequestsLogNothing() {
        val roots = listOf(
            "src/main/kotlin/com/vettid/feature/approvals/DeviceAsks.kt",
            "src/main/kotlin/com/vettid/feature/approvals/DeviceAsksScreen.kt",
            "../../core/data/src/main/kotlin/com/vettid/core/data/social/HeldRequests.kt",
            "../../core/data/src/main/kotlin/com/vettid/core/data/social/FeedOnlyAsks.kt",
        )
        for (r in roots) {
            val text = File(r).readText()
            for (call in listOf("Log.", "println(", "Timber.", "printStackTrace", "Logger")) {
                assertFalse("$r calls $call", call in text)
            }
        }
    }
}
