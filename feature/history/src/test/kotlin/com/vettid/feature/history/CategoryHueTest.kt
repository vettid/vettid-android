package com.vettid.feature.history

import com.vettid.core.data.vault.AuditCategory
import com.vettid.core.ui.theme.CategoryHue
import org.junit.Assert.assertEquals
import org.junit.Test

/** History's icon colours by category (owner request 2026-10-09), and the 0.23.0 kinds' titles and groups. */
class CategoryHueTest {
    @Test
    fun eachCategoryHasTheOwnersColour() {
        val expected = mapOf(
            AuditCategory.VAULT_ACCESS to CategoryHue.GREEN,
            AuditCategory.SECURITY to CategoryHue.AMBER,
            AuditCategory.DROPPED to CategoryHue.RED,
            AuditCategory.CONNECTIONS to CategoryHue.TEAL,
            AuditCategory.MESSAGES to CategoryHue.BLUE,
            AuditCategory.ITEMS to CategoryHue.GOLD,
            AuditCategory.DEVICES to CategoryHue.PURPLE,
            AuditCategory.AGENTS to CategoryHue.ORANGE,
            AuditCategory.LOCATION to CategoryHue.INDIGO,
            AuditCategory.ACCOUNT to CategoryHue.GREY,
            AuditCategory.OTHER to CategoryHue.GREY,
        )
        assertEquals(AuditCategory.entries.toSet(), expected.keys)
        expected.forEach { (c, h) -> assertEquals(c.name, h, categoryHue(c)) }
        // Deterministic: the same entry kind always gets the same colour.
        listOf("vault.unlocked", "drop.ask_muted", "connection.asks_paused", "zebra.new").forEach { k ->
            assertEquals(k, categoryHue(AuditCategory.of(k)), categoryHue(AuditCategory.of(k)))
        }
    }

    /** VAULT-MESSAGING 0.23.0 §10.4.1, §10.12: suppressed asks under Dropped, the asks' changes under Connections. */
    @Test
    fun theAsksKindsHaveTitlesAndGroups() {
        mapOf(
            "drop.ask_muted" to R.string.history_kind_drop_ask_muted,
            "drop.ask_paused" to R.string.history_kind_drop_ask_paused,
            "drop.ask_cooldown" to R.string.history_kind_drop_ask_cooldown,
            "drop.ask_pending" to R.string.history_kind_drop_ask_pending,
            "drop.ask_rate" to R.string.history_kind_drop_ask_rate,
            "drop.grant_rate_limited" to R.string.history_kind_drop_grant_rate_limited,
        ).forEach { (k, res) ->
            assertEquals(k, AuditKinds.Title.Known(res), AuditKinds.title(k))
            assertEquals(k, AuditCategory.DROPPED, AuditCategory.of(k))
        }
        mapOf(
            "connection.asks_paused" to R.string.history_kind_connection_asks_paused,
            "connection.asks_resumed" to R.string.history_kind_connection_asks_resumed,
            "connection.asks_muted" to R.string.history_kind_connection_asks_muted,
            "connection.asks_unmuted" to R.string.history_kind_connection_asks_unmuted,
        ).forEach { (k, res) ->
            assertEquals(k, AuditKinds.Title.Known(res), AuditKinds.title(k))
            assertEquals(k, AuditCategory.CONNECTIONS, AuditCategory.of(k))
        }
    }
}
