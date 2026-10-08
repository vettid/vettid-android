package com.vettid.feature.history

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.vault.AuditOps
import com.vettid.core.data.vault.FailureKind
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Duration
import java.time.Instant

/**
 * The History screen's loads end (owner, 2026-10-08, staging S5): History of one connection, opened from its detail,
 * spun forever; so did the full History and every search. The pager read the vault's pages, but the ViewModel never
 * showed them (its `run { }` inside `launch { }` was the standard library's `CoroutineScope.run`). Each test is the
 * vault's exact answers through [HistoryManager] and checks the state the screen shows.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val t0: Instant = Instant.parse("2026-10-08T12:00:00Z")

    /** Answers `audit.list` / `connection.audit.list` from [pages] in order and records each request. */
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
            calls += mapOf("connection_id" to connectionId, "before_seq" to beforeSeq, "q" to q, "limit" to limit)
            return queue.removeFirstOrNull()?.invoke() ?: error("unexpected request ${calls.last()}")
        }
    }

    private fun entry(seq: Long, kind: String = "message.sent", conn: String? = "c1") = AuditEntry(
        "e$seq", seq, t0.minus(Duration.ofMinutes(1000 - seq)).toString(), kind, conn, null, null, null, "", "",
    )

    private fun page(vararg seqs: Long, next: Long? = null, partial: Boolean = false, kind: String = "message.sent") =
        AuditPage(seqs.map { entry(it, kind) }, seq = 1000, nextBeforeSeq = next, partial = partial)

    private val social = FakeSocial().apply {
        // The connection whose names have not arrived ("Name not shared yet").
        seed(listOf(ConnectionInfo("c1", "", ConnectionState.ACTIVE)))
    }

    private fun vm(vault: Vault, connectionId: String? = "c1") = HistoryViewModel(
        SavedStateHandle(listOfNotNull(connectionId?.let { "connectionId" to it }).toMap()),
        HistoryManager { vault },
        social,
        RuntimeEnvironment.getApplication(),
        FakeItems(),
    )

    /** Runs the test dispatcher until [done] (the vault call itself runs on the IO dispatcher). */
    private fun TestScope.settle(model: HistoryViewModel, done: (HistoryUiState) -> Boolean): HistoryUiState {
        repeat(SETTLE_ROUNDS) {
            advanceUntilIdle()
            if (done(model.uiState.value)) return model.uiState.value
            Thread.sleep(SETTLE_SLEEP_MS)
        }
        throw AssertionError("History never settled: ${model.uiState.value}")
    }

    private fun idle(s: HistoryUiState) = !s.loading && !s.loadingMore

    @Test
    fun aConnectionsHistoryShowsItsEntriesAndStopsLoading() = runTest(main.dispatcher) {
        val vault = Vault({ page(900, 700, 650) })
        val model = vm(vault)
        val s = settle(model, ::idle)
        assertEquals("c1", vault.calls.single()["connection_id"])
        assertNull(vault.calls.single()["q"])
        assertEquals(listOf(900L, 700L, 650L), s.entries.map { it.seq })
        assertTrue(s.end)
        assertNull(s.error)
    }

    @Test
    fun aConnectionWithoutEntriesEndsEmptyNotSpinning() = runTest(main.dispatcher) {
        val model = vm(Vault({ page() }))
        val s = settle(model, ::idle)
        assertTrue(s.entries.isEmpty())
        assertTrue(s.end)
    }

    @Test
    fun theFullHistoryLoadsToo() = runTest(main.dispatcher) {
        val vault = Vault({ AuditPage(listOf(entry(5, "vault.unlocked", null)), seq = 5) })
        val model = vm(vault, connectionId = null)
        val s = settle(model, ::idle)
        assertNull(vault.calls.single()["connection_id"])
        assertEquals(listOf(5L), s.entries.map { it.seq })
        assertTrue(s.end)
    }

    @Test
    fun aSearchOfAConnectionThroughPartialPagesEnds() = runTest(main.dispatcher) {
        val vault = Vault(
            { page(900, 800) },
            // q "unlock": the budget runs out twice (S5, §10.9 partial), then the last page.
            { page(next = 500, partial = true) },
            { page(450, next = 200, partial = true, kind = "vault.unlocked") },
            { page() },
        )
        val model = vm(vault)
        settle(model, ::idle)
        model.setQuery("unlock")
        val s = settle(model) { idle(it) && it.filter.q == "unlock" && vault.calls.size == 4 }
        assertEquals(listOf(null, "unlock", "unlock", "unlock"), vault.calls.map { it["q"] })
        assertEquals(listOf(null, null, 500L, 200L), vault.calls.map { it["before_seq"] })
        assertEquals(listOf(450L), s.entries.map { it.seq })
        assertTrue(s.end)
        assertFalse(s.partial)
        assertFalse(s.localSearch)
    }

    @Test
    fun aSearchWithNoMatchEndsAndShowsNoMatch() = runTest(main.dispatcher) {
        val vault = Vault({ page(900) }, { page() })
        val model = vm(vault)
        settle(model, ::idle)
        model.setQuery("zzz")
        val s = settle(model) { idle(it) && vault.calls.size == 2 }
        assertTrue(s.entries.isEmpty())
        assertTrue(s.end)
    }

    @Test
    fun moreEntriesLoadAndLoadingMoreClears() = runTest(main.dispatcher) {
        val first = (999L downTo 950L).toList().toLongArray()
        val vault = Vault({ page(*first, next = 950) }, { page(949, 948) })
        val model = vm(vault)
        val s0 = settle(model, ::idle)
        assertEquals(50, s0.entries.size)
        assertFalse(s0.end)
        model.loadMore()
        val s = settle(model) { idle(it) && it.end }
        assertEquals(52, s.entries.size)
        assertEquals(950L, vault.calls.last()["before_seq"])
    }

    @Test
    fun aFailureIsShownNotSpunOn() = runTest(main.dispatcher) {
        val model = vm(Vault({ throw VaultOpException("connection.audit.list", "internal") }))
        val s = settle(model) { idle(it) }
        assertTrue(s.entries.isEmpty())
        assertEquals(FailureKind.OTHER, s.error)
    }

    private companion object {
        const val SETTLE_ROUNDS = 500
        const val SETTLE_SLEEP_MS = 10L
    }
}
