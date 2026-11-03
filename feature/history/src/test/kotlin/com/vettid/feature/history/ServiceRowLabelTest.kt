package com.vettid.feature.history

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.vettid.core.data.vault.AuditRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** "Service" beside the name of a History row that names a service connection (ANDROID-PLAN 0.1.90). */
@RunWith(RobolectricTestRunner::class)
class ServiceRowLabelTest {
    @get:Rule
    val rule = createComposeRule()

    private val names = RowNames(
        connections = mapOf("s1" to "Acme Co-op", "c1" to "Ada Lovelace"),
        items = emptyMap(),
        devices = null,
        services = setOf("s1"),
    )

    private val at = Instant.parse("2026-11-03T12:00:00Z")

    private fun entry(conn: String) = AuditRecord("e-$conn", 1, at, "connection.added", connectionId = conn)

    private fun serviceTexts() = rule.onAllNodesWithText("Service", substring = true).fetchSemanticsNodes().size

    @Test
    fun aServiceRowCarriesTheLabel() {
        rule.setContent { EntryRow(entry("s1"), names) {} }
        assertTrue(serviceTexts() > 0)
    }

    @Test
    fun aPersonRowDoesNot() {
        rule.setContent { EntryRow(entry("c1"), names) {} }
        assertEquals(0, serviceTexts())
    }
}
