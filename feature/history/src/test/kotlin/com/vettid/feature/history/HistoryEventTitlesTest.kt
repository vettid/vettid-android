// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.history

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.vault.AuditOps
import com.vettid.core.data.vault.AuditRecord
import com.vettid.core.data.vault.HistoryManager
import com.vettid.core.testing.FakeItems
import com.vettid.core.testing.FakeSocial
import com.vettid.core.vault.AuditEntry
import com.vettid.core.vault.AuditPage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

/** History's titles, detail lines and hidden kinds of ANDROID-PLAN 0.1.52 §4 "Event titles" (approved 2026-10-19). */
@RunWith(RobolectricTestRunner::class)
class HistoryEventTitlesTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun title(kind: String, ref: String? = null, names: EntryNames = EntryNames()) = HistoryText.title(context, kind, ref, names)

    private fun lines(kind: String, ref: String?, outcome: AuditKinds.Outcome = AuditKinds.Outcome.Unknown) =
        AuditKinds.details(kind, ref, outcome).map {
            when (it) {
                is AuditKinds.Detail.Line -> context.getString(it.res)
                is AuditKinds.Detail.Scaled -> context.getString(it.res)
                is AuditKinds.Detail.Named -> context.getString(it.res, it.name)
            }
        }

    @Test
    fun theTitlesReadAsApproved() {
        val expected = listOf(
            Triple("vault.pin_changed", null, "PIN changed"),
            Triple("device.secret_renewed", null, "New phone secret set"),
            Triple("identity.rotated", null, "Your vault's keys were renewed"),
            Triple("credential.rotated", null, "Your credential was renewed"),
            Triple("owner_check.passed", null, "Daily check passed"),
            Triple("owner_check.held", null, "Vault held for the daily check"),
            Triple("owner_check.failed", "pin", "Daily check failed"),
            Triple("owner_check.locked", "10", "Vault locked after failed daily checks"),
            Triple("owner_check.hold_changed", "on", "Daily check hold changed"),
            Triple("recovery.attempt", "2/3/not_success/counter", "Recovery attempt 3 of 10"),
            Triple("recovery.attempt", "garbled", "Recovery attempt"),
            Triple("recovery.attempts_used", "2/4", "Recovery attempts used: 4 of 10"),
            Triple("recovery.armed", "3", "Recovery is set up"),
            Triple("recovery.generation_closed", "2/pin", "Recovery attempts reset after a PIN change"),
            Triple("recovery.generation_closed", "2/password", "Recovery attempts reset after a password change"),
            Triple("recovery.generation_closed", "2/reset", "Recovery attempts reset after a credential reset"),
            Triple("recovery.generation_closed", "2/backup_off", "Recovery turned off"),
            Triple("recovery.generation_closed", "2/exhausted", "Recovery turned off: all 10 attempts used"),
            Triple("recovery.generation_closed", "2/delete", "Recovery ended"),
            Triple("recovery.generation_closed", "2/delete/unconfirmed", "Recovery ended"),
            Triple("recovery.generation_closed", "2/newer", "Recovery attempts reset"),
            Triple("recovery.generation_closed", "4/rollback", "Your vault was given an older recovery record"),
            Triple("recovery.presence_unrecorded", null, "Your vault could not record that you were here"),
            Triple("identity.rotation_failed", "recovery", "Key update failed"),
            Triple("credential.utk_resync", null, "One-time keys renewed"),
            Triple("credential.holder_cleared", null, "Credential holder cleared"),
            Triple("connection.request.approved", "p1", "You accepted a connection request"),
            Triple("connection.request.declined", "p1", "You declined a connection request"),
            Triple("drop.call_off", null, "Call refused: calls are off"),
            Triple("drop.call_rate", null, "Call refused: too many calls"),
            Triple("drop.call_share", null, "Call refused: it could not be verified"),
            Triple("drop.one_app", null, "Refused: a second phone tried to connect"),
            Triple("drop.owner_check", null, "Refused: your daily check is due"),
            Triple("drop.profile_ik_mismatch", null, "Profile refused: it did not match the connection"),
            Triple("drop.profile_malformed", null, "Message refused: it was malformed"),
            Triple("drop.location_malformed", null, "Message refused: it was malformed"),
            Triple("drop.attachment_malformed", null, "Photo refused: it was malformed"),
            Triple("drop.grant_duplicate", null, "Request refused: already asked"),
            Triple("drop.grant_limit", null, "Request refused: too many waiting"),
            Triple("drop.critical_signature", null, "Request refused: it could not be verified"),
            Triple("drop.rate_limited", null, "Message refused: too many messages"),
        )
        expected.forEach { (kind, ref, text) -> assertEquals("$kind $ref", text, title(kind, ref)) }
        assertEquals("Unlocks and daily checks", context.getString(R.string.history_category_vault_access))
    }

    @Test
    fun namedTitlesShowTheConnectionDeviceOrItem() {
        val n = EntryNames(connection = "Sam Lee", device = "Pixel 7", item = "GitHub token")
        assertEquals("Connection with \u2068Sam Lee\u2069 paused", title("identity.rotate_conflict", names = n))
        assertEquals("Connection with \u2068Sam Lee\u2069 checked and resumed", title("identity.rotate_resolved", "fp", n))
        assertEquals("Calls from \u2068Sam Lee\u2069 turned off", title("call.permission_off", names = n))
        assertEquals("Calls from \u2068Sam Lee\u2069 turned on", title("call.permission_on", names = n))
        assertEquals("Notifications turned on for \u2068Pixel 7\u2069", title("push.registered", "fcm", n))
        assertEquals("Notifications turned off for \u2068Pixel 7\u2069", title("push.unregistered", "app", n))
        assertEquals("Notifications stopped reaching \u2068Pixel 7\u2069", title("push.token_gone", "token_gone", n))
        assertEquals("An agent used \u2068GitHub token\u2069", title("leash.item.run", "01ITEM", n))
        assertEquals("An agent used \u2068GitHub token\u2069", title("leash.item.proxy", "01ITEM", n))
        assertEquals("You changed how an agent may use \u2068GitHub token\u2069", title("leash.item.changed", "01ITEM", n))
        // Without the name (gone or not known), the title drops it.
        assertEquals("Connection paused", title("identity.rotate_conflict"))
        assertEquals("Connection checked and resumed", title("identity.rotate_resolved"))
        assertEquals("Calls turned off", title("call.permission_off"))
        assertEquals("Calls turned on", title("call.permission_on"))
        assertEquals("Notifications turned on", title("push.registered", "fcm"))
        assertEquals("Notifications turned off", title("push.unregistered", "app"))
        assertEquals("Notifications stopped", title("push.token_gone"))
        assertEquals("An agent used an item", title("leash.item.run", "01GONE"))
        assertEquals("You changed how an agent may use an item", title("leash.item.changed"))
        assertEquals("01ITEM", AuditKinds.itemOf("leash.item.run", "01ITEM"))
        assertEquals("01ITEM", AuditKinds.itemOf("leash.item.changed", "01ITEM"))
    }

    @Test
    fun theDetailLinesReadAsApproved() {
        assertEquals(listOf("Successful."), lines("recovery.attempt", "2/4/success/counter"))
        val notYou = "Not successful. If this was not you, change your PIN or your password now."
        assertEquals(listOf(notYou), lines("recovery.attempt", "2/3/not_success/counter"))
        assertEquals(listOf(notYou, "Details not available."), lines("recovery.attempt", "2/3/not_success/counter", AuditKinds.Outcome.None))
        val outcomes = mapOf(
            "wrong" to "Wrong PIN or password.",
            "attempt_lost" to "Counted as an attempt, but it did not finish.",
            "quiet" to "Refused: you used your vault in the last 3 days.",
            "wait" to "Refused: too soon after the last try.",
            "exhausted" to "Refused: all 10 attempts used.",
            "closed" to "Refused: recovery was reset.",
        )
        outcomes.forEach { (o, text) ->
            assertEquals(o, listOf(notYou, text), lines("recovery.attempt", "2/3/not_success/counter", AuditKinds.Outcome.Matched(o)))
        }
        assertEquals(emptyList<String>(), lines("recovery.attempt", "garbled", AuditKinds.Outcome.None))
        assertEquals(listOf("Later successful."), lines("recovery.attempt", "2/3/not_success/counter", AuditKinds.Outcome.LaterSuccessful))
        assertEquals(
            listOf(
                "A host may have served an older copy of your vault's recovery record, one on which recovery attempts had already been used. " +
                    "Your vault moved past it. If you did not try to recover your vault, change your PIN and your password now, and contact VettID.",
            ),
            lines("recovery.generation_closed", "4/rollback"),
        )
        assertEquals(emptyList<String>(), lines("recovery.generation_closed", "4/stale"))
        // The attempting phone's self-reported name, only in that form; nothing without one.
        assertEquals(
            listOf(notYou, "Wrong PIN or password.", "From a phone that calls itself “Pixel 7”."),
            lines("recovery.attempt", "2/3/not_success/counter", AuditKinds.Outcome.Matched("wrong", "Pixel 7")),
        )
        assertEquals(
            listOf(notYou, "Wrong PIN or password."),
            lines("recovery.attempt", "2/3/not_success/counter", AuditKinds.Outcome.Matched("wrong")),
        )
        assertEquals(listOf("If that was not you, change your password now, and your PIN as well."), lines("recovery.attempts_used", "2/4"))
        assertEquals(listOf("It works with your current PIN and password, with 10 attempts."), lines("recovery.armed", "3"))
        for (r in listOf("pin", "password", "reset")) assertEquals(listOf("Earlier attempts no longer count."), lines("recovery.generation_closed", "2/$r"))
        assertEquals(listOf("You turned off the credential backup."), lines("recovery.generation_closed", "2/backup_off"))
        assertEquals(listOf("Change your PIN or your password to turn it back on."), lines("recovery.generation_closed", "2/exhausted"))
        assertEquals(listOf("Your vault is being deleted."), lines("recovery.generation_closed", "2/delete"))
        assertEquals(
            listOf("Your vault is being deleted.", "The service could not confirm this step."),
            lines("recovery.generation_closed", "2/delete/unconfirmed"),
        )
        assertEquals(
            listOf("If this lasts three days, your vault is no longer protected against a recovery started by someone else."),
            lines("recovery.presence_unrecorded", null),
        )
        assertEquals(listOf("Your connections still know your old keys. Your vault will try again the next time you unlock it."), lines("identity.rotation_failed", "recovery"))
        assertEquals(listOf("Replaced when you moved your vault to a new phone."), lines("identity.rotated", "t-1"))
        assertEquals(emptyList<String>(), lines("identity.rotated", null))
        assertEquals(listOf("This phone's one-time keys were replaced with a fresh set."), lines("credential.utk_resync", null))
        assertEquals(
            listOf("Your vault found its credential holder missing and cleared it. If you didn't remove a phone, contact VettID."),
            lines("credential.holder_cleared", null),
        )
        assertEquals(
            listOf("This connection's vault announced two different new keys. That can mean someone holds an old copy of their vault. Nothing is sent or received until you check."),
            lines("identity.rotate_conflict", null),
        )
        assertEquals(listOf("Through Google."), lines("push.registered", "fcm"))
        assertEquals(listOf("Through Apple."), lines("push.registered", "apns"))
        assertEquals(listOf("Turned off on that phone."), lines("push.unregistered", "app"))
        assertEquals(listOf("Your vault moved to a new phone."), lines("push.unregistered", "transferred"))
        assertEquals(listOf("That phone was replaced after a recovery."), lines("push.unregistered", "replaced"))
        assertEquals(listOf("Open VettID on that phone to turn them back on."), lines("push.token_gone", "blob_invalid"))
        assertEquals(
            listOf("The secret went to your VettID connector for one command on your computer. The agent was not given it."),
            lines("leash.item.run", "01ITEM"),
        )
        assertEquals(
            listOf("The secret went to your VettID connector, which added it to the agent's requests to a site you allowed. The agent was not given it."),
            lines("leash.item.proxy", "01ITEM"),
        )
        assertEquals(emptyList<String>(), lines("vault.unlocked", null))
    }

    /** Decision 4: four kinds are read but not listed; they stay in [HistoryUiState.entries] for the chain check. */
    @Test
    fun theHiddenKindsAreNotShown() {
        assertTrue(AuditKinds.hidden("recovery.open", "01JREC/wrong"))
        // Owner decision of 2026-10-20: the harmless `stale` is hidden; `rollback` is shown.
        assertTrue(AuditKinds.hidden("recovery.generation_closed", "4/stale"))
        assertFalse(AuditKinds.hidden("recovery.generation_closed", "4/rollback"))
        assertTrue(AuditKinds.hidden("device.unlinked_sent", "01JTRANSFER"))
        assertTrue(AuditKinds.hidden("drop.call_glare", "call1"))
        assertFalse(AuditKinds.hidden("recovery.generation_closed", "4/pin"))
        assertFalse(AuditKinds.hidden("recovery.generation_closed", null))
        assertFalse(AuditKinds.hidden("recovery.attempt", "2/3/not_success/counter"))
        assertFalse(AuditKinds.hidden("drop.call_off", "call1"))
        val entries = listOf(
            AuditRecord("e5", 5, null, "recovery.attempt", ref = "2/3/not_success/counter"),
            AuditRecord("e4", 4, null, "recovery.open", ref = "01JREC/wrong"),
            AuditRecord("e3", 3, null, "recovery.generation_closed", ref = "4/stale"),
            AuditRecord("e2", 2, null, "device.unlinked_sent", ref = "t1"),
            AuditRecord("e1", 1, null, "drop.call_glare"),
        )
        val s = HistoryUiState(entries = entries)
        assertEquals(listOf(5L), s.shown.map { it.seq })
        assertEquals(5, s.entries.size)
    }

    // --- the attempt's outcome on its detail screen ---

    private val t0: Instant = Instant.parse("2026-10-19T12:00:00Z")

    private class Vault(vararg pages: () -> AuditPage) : AuditOps {
        private val queue = ArrayDeque(pages.toList())
        val calls = mutableListOf<Map<String, Any?>>()

        override suspend fun auditList(
            connectionId: String?,
            kinds: List<String>?,
            beforeSeq: Long?,
            limit: Int,
            q: String?,
            since: String?,
            until: String?,
        ): AuditPage {
            calls += mapOf("kinds" to kinds, "before_seq" to beforeSeq, "since" to since, "until" to until, "limit" to limit)
            return queue.removeFirstOrNull()?.invoke() ?: error("unexpected request ${calls.last()}")
        }
    }

    private fun entry(seq: Long, kind: String, ref: String, seconds: Long) =
        AuditEntry("e$seq", seq, t0.plusSeconds(seconds).toString(), kind, null, null, ref, null, "", "")

    private val attempt = entry(20, "recovery.attempt", "2/3/not_success/counter", 0)

    private fun entryVm(vault: Vault) = HistoryEntryViewModel(
        SavedStateHandle(mapOf("seq" to 20L)),
        HistoryManager { vault },
        FakeSocial(),
        FakeItems(),
    )

    private fun TestScope.settle(model: HistoryEntryViewModel, done: (HistoryEntryUiState) -> Boolean): HistoryEntryUiState {
        repeat(SETTLE_ROUNDS) {
            advanceUntilIdle()
            if (done(model.uiState.value)) return model.uiState.value
            Thread.sleep(SETTLE_SLEEP_MS)
        }
        throw AssertionError("never settled: ${model.uiState.value}")
    }

    @Test
    fun anAttemptsDetailReadsTheMatchingOpen() = runTest(main.dispatcher) {
        val vault = Vault(
            { AuditPage(listOf(attempt), seq = 30) },
            // The vault's answer for the hour around it: a counted open 40 s before, an uncounted one nearer.
            // The open names its phone ("Pixel 7", base64url), as VAULT-MESSAGING 0.50.0 records it.
            { AuditPage(listOf(attempt, entry(12, "recovery.open", "01JREC/busy", -5), entry(11, "recovery.open", "01JREC/wrong/UGl4ZWwgNw", -40)), seq = 30) },
        )
        val s = settle(entryVm(vault)) { it.outcome != AuditKinds.Outcome.Unknown }
        assertEquals(AuditKinds.Outcome.Matched("wrong", "Pixel 7"), s.outcome)
        val read = vault.calls[1]
        assertEquals(listOf("recovery.open", "recovery.attempt"), read["kinds"])
        assertEquals("2026-10-19T11:00:00Z", Instant.parse(read["since"] as String).toString())
        assertEquals("2026-10-19T13:00:00Z", Instant.parse(read["until"] as String).toString())
    }

    @Test
    fun anAttemptWithoutAMatchingOpenSaysDetailsNotAvailable() = runTest(main.dispatcher) {
        val vault = Vault(
            { AuditPage(listOf(attempt), seq = 30) },
            // An open outside the window, and one a vault that ignored the range returned from long before.
            { AuditPage(listOf(attempt, entry(11, "recovery.open", "01JREC/wrong", -1200), entry(3, "recovery.open", "01JOLD/wrong", -86_400)), seq = 30) },
        )
        val s = settle(entryVm(vault)) { it.outcome != AuditKinds.Outcome.Unknown }
        assertEquals(AuditKinds.Outcome.None, s.outcome)
    }

    /** The vault wrote the attempt again as `success` (same `at`): the older row says "Later successful.". */
    @Test
    fun anAttemptWrittenAgainAsSuccessIsLaterSuccessful() = runTest(main.dispatcher) {
        val vault = Vault(
            { AuditPage(listOf(attempt), seq = 30) },
            {
                AuditPage(
                    listOf(entry(25, "recovery.attempt", "2/3/success/counter", 0), attempt, entry(11, "recovery.open", "01JREC/wrong", -40)),
                    seq = 30,
                )
            },
        )
        val s = settle(entryVm(vault)) { it.outcome != AuditKinds.Outcome.Unknown }
        assertEquals(AuditKinds.Outcome.LaterSuccessful, s.outcome)
    }

    @Test
    fun aFailedReadLeavesTheOutcomeUnknown() = runTest(main.dispatcher) {
        val vault = Vault(
            { AuditPage(listOf(attempt), seq = 30) },
            { throw VaultOpException("audit.list", "internal") },
        )
        val model = entryVm(vault)
        settle(model) { !it.loading && vault.calls.size == 2 }
        advanceUntilIdle()
        assertEquals(AuditKinds.Outcome.Unknown, model.uiState.value.outcome)
    }

    @Test
    fun aSuccessfulAttemptReadsNothingMore() = runTest(main.dispatcher) {
        val vault = Vault({ AuditPage(listOf(entry(20, "recovery.attempt", "2/4/success/counter", 0)), seq = 30) })
        val model = entryVm(vault)
        settle(model) { !it.loading }
        advanceUntilIdle()
        assertEquals(1, vault.calls.size)
        assertEquals(AuditKinds.Outcome.Unknown, model.uiState.value.outcome)
    }

    private companion object {
        const val SETTLE_ROUNDS = 100
        const val SETTLE_SLEEP_MS = 20L
    }
}
