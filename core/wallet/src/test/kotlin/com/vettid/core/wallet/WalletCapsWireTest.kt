package com.vettid.core.wallet

import com.vettid.core.chain.InspectSummary
import com.vettid.core.chain.SummaryOutput
import com.vettid.core.vault.VaultJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The spending-limit members as VAULT-MESSAGING §10.18 "Spend caps" gives them: `<wallet>.caps` (the limits absent when unlimited,
 * 1 to 2,100,000,000,000,000; `period_seconds` and `spent_sats` always there) and `wallet.psbt.inspect`'s `cap` (`ok` always; the
 * rest optional). A member that is there and mistyped or out of range does not read: it is never "no limit".
 */
class WalletCapsWireTest {
    private val max = WalletWire.MAX_SATS

    /** What a member can be sent as, other than a good value. `null` means "left out". */
    private val bad = mapOf(
        "a string" to "\"500000\"",
        "a boolean" to "true",
        "a fraction" to "1.5",
        "an exponent" to "1e3",
        "null" to "null",
        "zero" to "0",
        "negative" to "-1",
        "too large" to "${max + 1}",
        "an object" to "{}",
    )

    private fun capsJson(members: Map<String, String?>): String {
        val base = linkedMapOf<String, String?>(
            "per_tx_sats" to "500000", "per_period_sats" to "2000000", "period_seconds" to "86400", "spent_sats" to "750000",
        )
        base.putAll(members)
        return base.entries.filter { it.value != null }.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
    }

    private fun walletWith(members: Map<String, String?>): Result<VaultWallet> =
        runCatching { WalletFixtures.wallet(caps = capsJson(members)) }

    private fun assertUnreadable(what: String, member: String, r: Result<*>) {
        val e = r.exceptionOrNull()
        assertTrue("$member as $what must not read, got $r", e is WalletWireException)
        assertEquals("wallet answer: $member", e?.message)
    }

    @Test
    fun aLimitIsAbsentOrAnIntegerFromOneToTheMostSats() {
        for (member in listOf("per_tx_sats", "per_period_sats")) {
            for ((what, value) in bad) assertUnreadable(what, member, walletWith(mapOf(member to value)))
            // Left out: no limit of that kind.
            val none = walletWith(mapOf(member to null)).getOrThrow()
            assertTrue(none.capped)
            // The lower bound reads.
            val one = walletWith(mapOf(member to "1")).getOrThrow()
            assertEquals(1L, if (member == "per_tx_sats") one.perTxSats else one.perPeriodSats)
        }
        val maxBoth = walletWith(mapOf("per_tx_sats" to "$max", "per_period_sats" to "$max")).getOrThrow()
        assertEquals(max, maxBoth.perTxSats)
        assertEquals(max, maxBoth.perPeriodSats)
    }

    @Test
    fun cappedIsWhatTheLimitsRead() {
        val both = walletWith(emptyMap()).getOrThrow()
        assertTrue(both.capped)
        assertEquals(500_000L, both.perTxSats)
        assertEquals(2_000_000L, both.perPeriodSats)
        val tx = walletWith(mapOf("per_period_sats" to null)).getOrThrow()
        assertTrue(tx.capped)
        assertNull(tx.perPeriodSats)
        val period = walletWith(mapOf("per_tx_sats" to null)).getOrThrow()
        assertTrue(period.capped)
        assertNull(period.perTxSats)
        val none = walletWith(mapOf("per_tx_sats" to null, "per_period_sats" to null)).getOrThrow()
        assertFalse(none.capped)
        assertNull(none.perTxSats)
        assertNull(none.perPeriodSats)
    }

    @Test
    fun spentSatsIsAlwaysThereAndFromZero() {
        for ((what, value) in bad - "zero") assertUnreadable(what, "spent_sats", walletWith(mapOf("spent_sats" to value)))
        assertUnreadable("missing", "spent_sats", walletWith(mapOf("spent_sats" to null)))
        assertEquals(0L, walletWith(mapOf("spent_sats" to "0")).getOrThrow().spentSats)
        assertEquals(max, walletWith(mapOf("spent_sats" to "$max")).getOrThrow().spentSats)
    }

    @Test
    fun periodSecondsIsAlwaysThereAndAtMostADay() {
        for ((what, value) in bad) assertUnreadable(what, "period_seconds", walletWith(mapOf("period_seconds" to value)))
        assertUnreadable("missing", "period_seconds", walletWith(mapOf("period_seconds" to null)))
        assertUnreadable("over a day", "period_seconds", walletWith(mapOf("period_seconds" to "86401")))
        assertEquals(1L, walletWith(mapOf("period_seconds" to "1")).getOrThrow().periodSeconds)
        assertEquals(3_600L, walletWith(mapOf("period_seconds" to "3600")).getOrThrow().periodSeconds)
        assertEquals(86_400L, walletWith(mapOf("period_seconds" to "86400")).getOrThrow().periodSeconds)
    }

    // The inspect's `cap`

    private val summary = InspectSummary(
        txid = "aa".repeat(32),
        inputs = emptyList(),
        outputs = listOf(SummaryOutput("tb1qpayee", 40_000, false, null, null)),
        totalInSats = 41_200, sendingSats = 40_000, changeSats = 0, feeSats = 1_200, vsize = 150,
    ).toJson()

    private fun capOf(members: Map<String, String?>): Result<SpendCap> {
        val base = linkedMapOf<String, String?>(
            "ok" to "false", "reason" to "\"per_period\"", "limit_sats" to "2000000", "remaining_sats" to "15000",
            "allowed_after" to "\"2026-11-02T08:30:00Z\"",
        )
        base.putAll(members)
        val cap = base.entries.filter { it.value != null }.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
        val o = VaultJson.parseObject((summary.dropLast(1) + ",\"cap\":" + cap + "}").encodeToByteArray())
        return runCatching { WalletWire.inspection(o).cap }
    }

    @Test
    fun theCapReadsAsTheVaultWritesIt() {
        val c = capOf(emptyMap()).getOrThrow()
        assertFalse(c.ok)
        assertEquals("per_period", c.reason)
        assertEquals(2_000_000L, c.limitSats)
        assertEquals(15_000L, c.remainingSats)
        assertEquals(Instant.parse("2026-11-02T08:30:00Z"), c.allowedAfter)
        // `{ok: true}` for a wallet without limits: the rest absent.
        val ok = capOf(mapOf("ok" to "true", "reason" to null, "limit_sats" to null, "remaining_sats" to null, "allowed_after" to null))
            .getOrThrow()
        assertTrue(ok.ok)
        assertNull(ok.reason)
        assertNull(ok.limitSats)
        assertNull(ok.remainingSats)
        assertNull(ok.allowedAfter)
    }

    @Test
    fun theCapsMembersAreReadStrictly() {
        // `ok`: always there, a boolean.
        for ((what, value) in mapOf("missing" to null, "a string" to "\"false\"", "a number" to "0", "null" to "null")) {
            assertUnreadable(what, "ok", capOf(mapOf("ok" to value)))
        }
        // `limit_sats`: absent, or 1 to the most sats.
        for ((what, value) in bad) assertUnreadable(what, "limit_sats", capOf(mapOf("limit_sats" to value)))
        assertEquals(1L, capOf(mapOf("limit_sats" to "1")).getOrThrow().limitSats)
        assertEquals(max, capOf(mapOf("limit_sats" to "$max")).getOrThrow().limitSats)
        // `remaining_sats`: absent, or 0 to the most sats.
        for ((what, value) in bad - "zero") assertUnreadable(what, "remaining_sats", capOf(mapOf("remaining_sats" to value)))
        assertEquals(0L, capOf(mapOf("remaining_sats" to "0")).getOrThrow().remainingSats)
        // `reason`: absent, or a string.
        for ((what, value) in mapOf("a number" to "5", "a boolean" to "true", "empty" to "\"\"")) {
            assertUnreadable(what, "reason", capOf(mapOf("reason" to value)))
        }
        // `allowed_after`: absent, or an RFC 3339 time.
        for ((what, value) in mapOf("not a time" to "\"tomorrow\"", "a number" to "5", "a boolean" to "true")) {
            assertUnreadable(what, "allowed_after", capOf(mapOf("allowed_after" to value)))
        }
        // A member the spec does not name is not read as one it does.
        assertNull(capOf(mapOf("limit_sats" to null, "limit" to "700")).getOrThrow().limitSats)
    }
}
