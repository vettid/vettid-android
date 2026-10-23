// Event bodies and fixtures stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.InMemoryDeviceStateStore
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * VAULT-MESSAGING §10.12 One answer per item and subject: an item decided leaves every question of the subject, here and
 * on another device, also from a list read on its way; a `share.pending` batch for a listed rule shows at once.
 * (Found by the iOS agent, vettid-ios#120 `ApprovalsSharesTests`.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareDecidedTest {
    private val now = Instant.parse("2026-10-08T12:00:00Z")
    private val credential = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CredentialRepository::class.java)) { _, m, _ ->
        error("unexpected credential call ${m.name}")
    } as CredentialRepository

    /** The `ref`s of the feed items a decision marked read. */
    private val feedReads = mutableListOf<String?>()

    private fun TestScope.manager() = SocialManager(
        backgroundScope,
        api = { throw VaultFailure(FailureKind.OTHER) },
        credential = credential,
        store = InMemoryDeviceStateStore(),
        clock = Clock.fixed(now, ZoneOffset.UTC),
        feedRead = { _, ref, _ -> feedReads += ref },
    )

    private fun o(s: String): JsonObject = VaultJson.json.parseToJsonElement(s).jsonObject

    private fun event(type: String, body: String) = VaultMessage(Inner(id = Ulid.new(), type = type, ts = now, body = body.toByteArray()))

    private fun entry(rule: String, item: String, extra: String = "") =
        """{"rule_id":"$rule","subject":{"connection_id":"c1"},"item_id":"$item","name":"$item","category":"other","sensitivity":"data"$extra}"""

    private val rules = o(
        """{"rules":[{"rule_id":"r1","subject":{"connection_id":"c1"},"tags":["medical"]},{"rule_id":"r2","subject":{"connection_id":"c1"},"tags":["insurance"],"mode":"auto"}]}""",
    )

    private suspend fun read(vararg entries: String): SocialManager.ListRead {
        val d = SharePending.decisions(
            pendingPage = { o("""{"pending":[${entries.joinToString(",")}]}""") },
            rulePage = { rules },
            names = emptyMap(),
            t = now,
        )
        return SocialManager.ListRead(d, grantsOk = true, criticalOk = true, sharesOk = true)
    }

    private suspend fun SocialManager.refresh(vararg entries: String) {
        val r = read(*entries)
        refreshApprovalsFrom(now) { r }
    }

    private fun SocialManager.share(rule: String) = approvals.value.filterIsInstance<Approval.ShareDecision>().firstOrNull { it.ruleId == rule }

    private fun SocialManager.items(rule: String) = share(rule)?.items?.map { it.itemId }

    private suspend fun SocialManager.decided(rule: String, included: String, declined: String = "") =
        onEvent(event("sync.event", """{"kind":"share.decided","rule_id":"$rule","included":[$included],"declined":[$declined]}"""))

    private suspend fun SocialManager.batch(rule: String, item: String, extra: String = "") =
        onEvent(event("share.pending", """{"rule_id":"$rule","subject":{"connection_id":"c1"},"items":[{"item_id":"$item","name":"$item","category":"other","sensitivity":"data"$extra}]}"""))

    @Test
    fun aDecidedItemDoesNotComeBackUnderTheSubjectsOtherRule() = runTest {
        val m = manager()
        m.refresh(entry("r1", "i1"), entry("r1", "i2"), entry("r2", "i1", ""","ask_rule_id":"r1""""))
        runCurrent()
        assertEquals(listOf("i1", "i2"), m.items("r1"))
        assertNull(m.share("r2"))

        m.decided("r1", """"i1"""")
        runCurrent()
        assertEquals(listOf("i2"), m.items("r1"))
        assertNull(m.share("r2"))
        m.decided("r1", "", """"i2"""")
        runCurrent()
        assertTrue(m.approvals.value.isEmpty())
    }

    @Test
    fun theOtherRulesQuestionLosesTheDecidedItemButKeepsItsOwn() = runTest {
        val m = manager()
        m.refresh(entry("r1", "i1"), entry("r2", "i1"), entry("r2", "i3"))
        runCurrent()
        // i1 is asked under r1 only (first rule); r2 asks i3.
        assertEquals(listOf("i1"), m.items("r1"))
        assertEquals(listOf("i3"), m.items("r2"))
        m.decided("r1", """"i1"""")
        runCurrent()
        assertNull(m.share("r1"))
        assertEquals(listOf("i3"), m.items("r2"))
        // Another subject is not touched.
        val other = """{"rule_id":"r3","subject":{"connection_id":"c2"},"item_id":"i1","name":"i1","category":"other","sensitivity":"data"}"""
        m.refresh(entry("r1", "i1"), other)
        runCurrent()
        m.decided("r1", """"i1"""")
        runCurrent()
        assertEquals(listOf("i1"), m.items("r3"))
    }

    @Test
    fun aDecisionDuringTheReadIsNotUndoneByIt() = runTest {
        val m = manager()
        val r = read(entry("r1", "A"), entry("r1", "C"), entry("r2", "C"), entry("r2", "D"))
        val gate = CompletableDeferred<Unit>()
        val job = backgroundScope.launch { m.refreshApprovalsFrom(now) { gate.await(); r } }
        runCurrent()
        // Decided on another device while the read had it: also leaves the subject's other rule.
        m.decided("r1", """"C"""")
        gate.complete(Unit)
        job.join()
        runCurrent()
        assertEquals(listOf("A"), m.items("r1"))
        assertEquals(listOf("D"), m.items("r2"))
    }

    @Test
    fun overlappingReadsKeepTheMarksTheOlderNeeds() = runTest {
        val m = manager()
        val older = read(entry("r1", "A"), entry("r1", "C"))
        val newer = read(entry("r1", "A"))
        val g1 = CompletableDeferred<Unit>()
        val g2 = CompletableDeferred<Unit>()
        val j1 = backgroundScope.launch { m.refreshApprovalsFrom(now) { g1.await(); older } }
        runCurrent()
        m.decided("r1", """"C"""")
        val j2 = backgroundScope.launch { m.refreshApprovalsFrom(now) { g2.await(); newer } }
        runCurrent()
        g2.complete(Unit)
        j2.join()
        g1.complete(Unit)
        j1.join()
        runCurrent()
        assertEquals(listOf("A"), m.items("r1"))
    }

    @Test
    fun aBatchForAListedQuestionShowsAtOnceKeepingTheListedItemsFields() = runTest {
        val m = manager()
        m.refresh(entry("r1", "A", ""","ask_rule_id":"r0""""))
        runCurrent()
        m.batch("r1", "B")
        runCurrent()
        assertEquals(listOf("A", "B"), m.items("r1"))
        // The same item again, without the name: the listed one's name and ask_rule_id stay.
        m.refresh(entry("r1", "A", ""","ask_rule_id":"r0""""))
        runCurrent()
        m.onEvent(event("share.pending", """{"rule_id":"r1","subject":{"connection_id":"c1"},"items":[{"item_id":"A","name":"","category":"other","sensitivity":"data"}]}"""))
        runCurrent()
        val a = m.share("r1")!!.items.single { it.itemId == "A" }
        assertEquals("A", a.name)
        assertEquals("r0", a.askRuleId)
    }

    @Test
    fun aBatchThatArrivesDuringTheReadIsKeptWithTheListedItems() = runTest {
        val m = manager()
        val r = read(entry("r1", "A"), entry("r1", "C"))
        val gate = CompletableDeferred<Unit>()
        val job = backgroundScope.launch { m.refreshApprovalsFrom(now) { gate.await(); r } }
        runCurrent()
        m.batch("r1", "B")
        m.decided("r1", """"C"""")
        gate.complete(Unit)
        job.join()
        runCurrent()
        assertEquals(setOf("A", "B"), m.items("r1")?.toSet())
    }

    private suspend fun SocialManager.decide(rule: String, include: List<String>, answer: String) =
        decideShareWith(rule, include, emptyList(), both = { o(answer) }, one = { _, _ -> o(answer) })

    @Test
    fun aDecisionMadeHereLeavesTheSubjectsOtherRule() = runTest {
        val m = manager()
        m.refresh(entry("r1", "i1"), entry("r2", "i1"), entry("r2", "i3"))
        runCurrent()
        m.decide("r1", listOf("i1"), """{"included":["i1"],"declined":[]}""")
        runCurrent()
        assertNull(m.share("r1"))
        assertEquals(listOf("i3"), m.items("r2"))
    }

    @Test
    fun theAnswersItemsDecideWhatLeavesNotTheRequests() = runTest {
        val m = manager()
        m.refresh(entry("r1", "i1"), entry("r2", "i3"))
        runCurrent()
        m.decide("r1", listOf("i1"), """{"included":["i1","i3"],"declined":[]}""")
        runCurrent()
        assertNull(m.share("r2"))
    }

    @Test
    fun aRuleHiddenByTheDedupeStillFindsItsSubject() = runTest {
        val m = manager()
        // r2's only item is asked under r1: r2 is not shown, but the decision of r2 still reaches r1's question.
        m.refresh(entry("r1", "i1"), entry("r1", "i2"), entry("r2", "i1"))
        runCurrent()
        assertNull(m.share("r2"))
        m.decide("r2", listOf("i1"), """{"included":["i1"],"declined":[]}""")
        runCurrent()
        assertEquals(listOf("i2"), m.items("r1"))
    }

    @Test
    fun aDecisionMadeHereDuringTheReadIsNotUndoneByIt() = runTest {
        val m = manager()
        m.refresh(entry("r1", "i1"), entry("r2", "i1"), entry("r2", "i3"))
        runCurrent()
        val r = read(entry("r1", "i1"), entry("r2", "i1"), entry("r2", "i3"))
        val gate = CompletableDeferred<Unit>()
        val job = backgroundScope.launch { m.refreshApprovalsFrom(now) { gate.await(); r } }
        runCurrent()
        m.decide("r1", listOf("i1"), """{"included":["i1"],"declined":[]}""")
        gate.complete(Unit)
        job.join()
        runCurrent()
        assertNull(m.share("r1"))
        assertEquals(listOf("i3"), m.items("r2"))
    }

    @Test
    fun aBatchIsNotResurrectedIfItsItemWasWithdrawnBeforeTheRead() = runTest {
        val m = manager()
        m.batch("r1", "X")
        val r = read(entry("r1", "A"))
        val gate = CompletableDeferred<Unit>()
        val job = backgroundScope.launch { m.refreshApprovalsFrom(now) { gate.await(); r } }
        runCurrent()
        m.batch("r1", "B")
        gate.complete(Unit)
        job.join()
        runCurrent()
        assertEquals(setOf("A", "B"), m.items("r1")?.toSet())
    }

    // --- VAULT-MESSAGING 0.55.7 §10.12 "Share questions that change while the app reads the lists" ---

    /** Starts a read that returns [r] once [gate] opens. */
    private fun TestScope.reading(m: SocialManager, r: SocialManager.ListRead, gate: CompletableDeferred<Unit>) =
        backgroundScope.launch { m.refreshApprovalsFrom(now) { gate.await(); r } }

    /** A read that could not list the shares: the kept events show as they are. */
    private suspend fun SocialManager.refreshWithoutShares() =
        refreshApprovalsFrom(now) { SocialManager.ListRead(emptyList(), grantsOk = true, criticalOk = true, sharesOk = false) }

    private suspend fun SocialManager.decideNotFound(rule: String, include: List<String>) {
        try {
            decideShareWith(rule, include, emptyList(), both = { throw VaultFailure(FailureKind.NOT_FOUND) }, one = { _, _ -> o("{}") })
            error("expected not_found")
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.NOT_FOUND, e.kind)
        }
    }

    /** Rule 1: a decision drops a batch's item only when it was received after that batch; a batch after it asks again. */
    @Test
    fun aDecidedItemAskedAgainDuringTheReadStays() = runTest {
        val m = manager()
        val gate = CompletableDeferred<Unit>()
        val job = reading(m, read(entry("r1", "A"), entry("r1", "C")), gate)
        runCurrent()
        m.decided("r1", """"A"""")
        m.batch("r1", "A")
        m.batch("r1", "B")
        m.decided("r1", """"B"""")
        gate.complete(Unit)
        job.join()
        runCurrent()
        assertEquals(setOf("A", "C"), m.items("r1")?.toSet())
    }

    /** Rule 1: decisions for the same subject under another rule count; another subject's do not. */
    @Test
    fun decisionsUnderTheSubjectsOtherRuleDropABatchItemInOrder() = runTest {
        val m = manager()
        val other = """{"rule_id":"r3","subject":{"connection_id":"c2"},"item_id":"Z","name":"Z","category":"other","sensitivity":"data"}"""
        val gate = CompletableDeferred<Unit>()
        val job = reading(m, read(entry("r1", "A"), entry("r2", "D"), other), gate)
        runCurrent()
        m.batch("r2", "B")
        m.decided("r1", """"B"""")
        m.decided("r1", "", """"F"""")
        m.batch("r2", "F")
        m.batch("r2", "G")
        m.decided("r3", """"G"""")
        gate.complete(Unit)
        job.join()
        runCurrent()
        assertEquals(listOf("A"), m.items("r1"))
        assertEquals(setOf("D", "F", "G"), m.items("r2")?.toSet())
        // A read that could not list the shares covers nothing: D, still pending at the vault, stays; B does not return.
        m.refreshWithoutShares()
        runCurrent()
        assertEquals(setOf("D", "F", "G"), m.items("r2")?.toSet())
    }

    /** Rule 2: a withdrawn item X does not come back with a batch B for a question the read does not list. */
    @Test
    fun anUnlistedQuestionKeepsOnlyTheBatchesOfTheRead() = runTest {
        val m = manager()
        m.batch("r1", "X")
        val gate = CompletableDeferred<Unit>()
        val job = reading(m, read(entry("r2", "D")), gate)
        runCurrent()
        m.batch("r1", "B")
        gate.complete(Unit)
        job.join()
        runCurrent()
        assertEquals(listOf("B"), m.items("r1"))
        // The kept event holds only B too.
        m.refreshWithoutShares()
        runCurrent()
        assertEquals(listOf("B"), m.items("r1"))
    }

    /** Rule 2: an unlisted question whose batch items were all decided after their batch goes. */
    @Test
    fun anUnlistedQuestionWithEveryBatchItemDecidedGoes() = runTest {
        val m = manager()
        m.batch("r1", "X")
        val gate = CompletableDeferred<Unit>()
        val job = reading(m, read(), gate)
        runCurrent()
        m.batch("r1", "B")
        m.decided("r1", """"B"""")
        gate.complete(Unit)
        job.join()
        runCurrent()
        assertNull(m.share("r1"))
        m.refreshWithoutShares()
        runCurrent()
        assertNull(m.share("r1"))
    }

    /** Base rule: for a listed question, an older item the read does not list is not kept (not even in the event). */
    @Test
    fun aListedQuestionDropsOlderItemsTheReadDoesNotList() = runTest {
        val m = manager()
        m.batch("r1", "X")
        m.refresh(entry("r1", "A"))
        runCurrent()
        assertEquals(listOf("A"), m.items("r1"))
        m.decided("r1", """"A"""")
        runCurrent()
        assertNull(m.share("r1"))
    }

    /** Rule 3: on not_found the question's noted batches are forgotten; a batch after it counts. */
    @Test
    fun aNotFoundDecisionForgetsTheBatchesBeforeIt() = runTest {
        val m = manager()
        val gate = CompletableDeferred<Unit>()
        val job = reading(m, read(entry("r1", "A")), gate)
        runCurrent()
        m.batch("r1", "B")
        m.decideNotFound("r1", listOf("B"))
        m.batch("r1", "C")
        gate.complete(Unit)
        job.join()
        runCurrent()
        assertEquals(listOf("A", "C"), m.items("r1"))
    }

    @Test
    fun aNotFoundDecisionOnAnUnlistedQuestionKeepsOnlyTheLaterBatch() = runTest {
        val m = manager()
        val gate = CompletableDeferred<Unit>()
        val job = reading(m, read(), gate)
        runCurrent()
        m.batch("r1", "B")
        m.decideNotFound("r1", listOf("B"))
        m.batch("r1", "C")
        gate.complete(Unit)
        job.join()
        runCurrent()
        assertEquals(listOf("C"), m.items("r1"))
    }

    /** A successful decision does not forget the question's batches: an undecided batch item stays, during and after. */
    @Test
    fun aSuccessfulDecisionKeepsAnUndecidedBatchItem() = runTest {
        val m = manager()
        val gate = CompletableDeferred<Unit>()
        val job = reading(m, read(entry("r1", "A")), gate)
        runCurrent()
        m.batch("r1", "B")
        m.decide("r1", listOf("A"), """{"included":["A"],"declined":[]}""")
        runCurrent()
        assertEquals(listOf("B"), m.items("r1"))
        // The question is still open: its feed item stays unread.
        assertTrue(feedReads.isEmpty())
        gate.complete(Unit)
        job.join()
        runCurrent()
        assertEquals(listOf("B"), m.items("r1"))
        m.decide("r1", listOf("B"), """{"included":[],"declined":["B"]}""")
        runCurrent()
        assertNull(m.share("r1"))
        assertEquals(listOf<String?>("r1"), feedReads)
    }

    /** A read that could not list the shares covers nothing: the questions known from the last read stay. */
    @Test
    fun aReadWithoutTheShareListKeepsTheKnownQuestions() = runTest {
        val m = manager()
        m.refresh(entry("r1", "A"))
        runCurrent()
        m.refreshWithoutShares()
        runCurrent()
        assertEquals(listOf("A"), m.items("r1"))
    }

    @Test
    fun aSuccessfulDecisionOnAnUnlistedQuestionKeepsTheOtherBatchItem() = runTest {
        val m = manager()
        val gate = CompletableDeferred<Unit>()
        val job = reading(m, read(), gate)
        runCurrent()
        m.batch("r1", "B")
        m.batch("r1", "C")
        m.decide("r1", listOf("B"), """{"included":["B"],"declined":[]}""")
        gate.complete(Unit)
        job.join()
        runCurrent()
        assertEquals(listOf("C"), m.items("r1"))
        m.refreshWithoutShares()
        runCurrent()
        assertEquals(listOf("C"), m.items("r1"))
    }

    /** A sent item the answer does not name (the vault did not hold it pending) leaves the question, without a mark. */
    @Test
    fun aSentItemTheAnswerDoesNotNameLeavesTheQuestion() = runTest {
        val m = manager()
        m.refresh(entry("r1", "A"), entry("r1", "B"))
        runCurrent()
        m.decide("r1", listOf("A", "B"), """{"included":["A"],"declined":[]}""")
        runCurrent()
        assertNull(m.share("r1"))
    }
}
