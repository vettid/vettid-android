package com.vettid.feature.history

import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vettid.core.data.vault.AuditFilter
import com.vettid.core.data.vault.AuditOps
import com.vettid.core.data.vault.AuditRecord
import com.vettid.core.data.vault.ExportAnswer
import com.vettid.core.data.vault.ExportFormat
import com.vettid.core.data.vault.HistoryExportRepository
import com.vettid.core.data.vault.HistoryManager
import com.vettid.core.testing.FakeItems
import com.vettid.core.testing.FakeSocial
import com.vettid.core.ui.components.ShellChrome
import com.vettid.core.vault.AuditEntry
import com.vettid.core.vault.AuditPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import java.io.OutputStream
import java.time.Duration
import java.time.Instant

/**
 * Owner, 2026-10-09 (staging S7, Pixel 10): after an export, History's top stayed the previous "History exported", also
 * after the drawer took it away and back; a search showed the new one. The ViewModel did read it (PR #97) and put it
 * first, but the list is keyed by `seq` and a LazyColumn keeps its first visible key in place when rows are inserted
 * above it, so the new entry sat above the screen's top. This runs History as the app does: one ViewModel in a
 * NavHost destination that the drawer saves and restores, the export in the same destination, and checks the screen.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryReturnTest {
    @get:Rule
    val rule = createComposeRule()

    /** The vault's log: `audit.list` newest first in pages, `next_before_seq` the last seq returned. */
    private class Log(n: Int) : AuditOps {
        @Volatile
        var all: List<AuditEntry> = (1L..n).map { entry(it, "vault.unlocked") }

        @Volatile
        var calls = 0

        fun append(kind: String) {
            all = all + entry(all.size + 1L, kind)
        }

        override suspend fun auditList(
            connectionId: String?,
            kinds: List<String>?,
            beforeSeq: Long?,
            limit: Int,
            q: String?,
            since: String?,
            until: String?,
        ): AuditPage {
            calls++
            val older = all.asReversed().filter { beforeSeq == null || it.seq < beforeSeq }
            val page = older.take(limit)
            return AuditPage(page, seq = all.size.toLong(), nextBeforeSeq = page.lastOrNull()?.seq?.takeIf { older.size > limit })
        }

        companion object {
            private val t0: Instant = Instant.parse("2026-10-09T16:00:00Z")

            fun entry(seq: Long, kind: String) =
                AuditEntry("e$seq", seq, t0.plus(Duration.ofMinutes(seq)).toString(), kind, null, null, null, null, "", "")
        }
    }

    /** An export whose preview answers (the PIN, reading and saving are the export ViewModel's own tests). */
    private object Exports : HistoryExportRepository {
        override suspend fun preview(filter: AuditFilter) = ExportAnswer(
            count = 56, more = false, uptoSeq = 56, uptoHash = "AA==", oldestSeq = 1, newestSeq = 56,
            oldestAt = Instant.parse("2026-10-09T16:01:00Z"), newestAt = Instant.parse("2026-10-09T16:56:00Z"),
        )

        override suspend fun export(filter: AuditFilter, format: ExportFormat, uptoSeq: Long, pin: String) = preview(filter)

        override suspend fun entries(filter: AuditFilter, answer: ExportAnswer, onProgress: (Int) -> Unit) = emptyList<AuditRecord>()

        override suspend fun deviceNames() = emptyMap<String, String>()
    }

    private object NoDocuments : ExportDocuments {
        override fun write(uri: String, block: (OutputStream) -> Unit) = Unit

        override fun discard(uri: String) = Unit
    }

    /** The drawer's navigation (AppShell's `navigateTopLevel`): one copy of each screen, its state saved and restored. */
    private fun NavHostController.drawer(route: String) = navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    private var log: Log? = null

    private fun waitForHead(vm: () -> HistoryViewModel?, seq: Long) = try {
        rule.waitUntil(WAIT_MS) {
            // The vault call returns on the IO dispatcher; Robolectric's paused main looper runs its continuation.
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            vm()?.uiState?.value?.let { !it.loading && it.entries.firstOrNull()?.seq == seq } == true }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        val s = vm()?.uiState?.value
        val top = s?.entries?.take(2)?.map { it.seq }
        throw AssertionError("head never $seq: loading=${s?.loading} error=${s?.error} top=$top vault calls=${log?.calls}", e)
    }

    @Test
    fun theNewestEntryIsOnTopAfterAnExportAndAfterTheDrawerBringsHistoryBack() {
        val log = Log(56).also { this.log = it }
        val repo = HistoryManager { log }
        var history: HistoryViewModel? = null
        var export: HistoryExportViewModel? = null
        lateinit var nav: NavHostController
        val chrome = ShellChrome(accountName = "Me", onMenuClick = {}, onAvatarClick = {})
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "messages") {
                composable("messages") { Text("Messages") }
                composable("history") {
                    val vm = viewModel {
                        HistoryViewModel(createSavedStateHandle(), repo, FakeSocial(), RuntimeEnvironment.getApplication(), FakeItems())
                    }
                    val ex = viewModel { HistoryExportViewModel(Exports, NoDocuments, RuntimeEnvironment.getApplication()) }
                    history = vm
                    export = ex
                    HistoryContent(vm, ex, chrome, HistoryHost(navigate = {}, onBack = {}, onOpenConnection = {}), onBack = null)
                }
            }
        }
        rule.runOnIdle { nav.drawer("history") }
        waitForHead({ history }, 56)
        rule.onNodeWithTag("history_entry_56").assertIsDisplayed()
        val first = history

        // ⋯ → Export…: the export replaces the list; the vault records `audit.exported` (seq 57); Close.
        rule.runOnIdle { export!!.start(ExportContext(AuditFilter())) }
        rule.waitUntil(WAIT_MS) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            export!!.step.value is ExportStep.Confirm
        }
        log.append("audit.exported")
        rule.runOnIdle { export!!.close() }
        waitForHead({ history }, 57)
        rule.waitForIdle()
        rule.onNodeWithTag("history_entry_57").assertIsDisplayed()

        // Drawer → Messages → History: the same (restored) ViewModel, and what the vault recorded meanwhile on top.
        rule.runOnIdle { nav.drawer("messages") }
        log.append("vault.unlocked")
        rule.runOnIdle { nav.drawer("history") }
        waitForHead({ history }, 58)
        rule.waitForIdle()
        assertSame(first, history)
        rule.onNodeWithTag("history_entry_58").assertIsDisplayed()
        // The two new entries on top of the first page read (56 to 7); older ones still load on scrolling.
        assertEquals((58L downTo 57L) + (56L downTo 7L), history!!.uiState.value.entries.map { it.seq })
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
