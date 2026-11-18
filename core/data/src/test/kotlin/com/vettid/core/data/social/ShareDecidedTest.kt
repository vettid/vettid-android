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
        obsoleteStore = InMemoryDeviceStateStore(),
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
        decideShareWith(rule, include, emptyList(), send = { _, _ -> o(answer) })

    /**
     * The app requires the vault's current release, which takes `share.decide{include, decline}`: a `bad_request` (or a
     * `limit`) is a refusal like any other. It is thrown to the screen, nothing else is sent (no `{items, approve}` calls),
     * and the question stays as it was, its feed item unread.
     */
    @Test
    fun aRefusalIsThrownWithNoSecondCallAndTheQuestionStays() = runTest {
        listOf(VaultFailure(FailureKind.OTHER, "bad_request"), VaultFailure(FailureKind.LIMIT, "limit")).forEach { refusal ->
            val m = manager()
            m.refresh(entry("r1", "i1"), entry("r1", "i2"))
            runCurrent()
            feedReads.clear()
            var sent = 0
            try {
                m.decideShareWith("r1", listOf("i1"), listOf("i2"), send = { _, _ -> sent++; throw refusal })
                error("${refusal.code} must be thrown")
            } catch (e: VaultFailure) {
                assertEquals(refusal.kind, e.kind)
                assertEquals(refusal.code, e.code)
            }
            runCurrent()
            assertEquals("sent once, nothing after it", 1, sent)
            assertEquals(setOf("i1", "i2"), m.items("r1")?.toSet())
            assertTrue(feedReads.isEmpty())
        }
    }

    /**
     * §10.12: none pending is `bad_request` (decided on another device, or withdrawn with the event missed). The question
     * is kept and the error thrown, and the lists are read again in the background so a stale question clears; another
     * refusal (a `limit`) reads nothing.
     */
    @Test
    fun aBadRequestReadsTheListsAgainAndOnlyItDoes() = runTest {
        val m = manager()
        var rereads = 0
        m.staleShareReread = { rereads++ }
        m.refresh(entry("r1", "i1"))
        runCurrent()
        runCatching { m.decideShareWith("r1", emptyList(), listOf("i1"), send = { _, _ -> throw VaultFailure(FailureKind.LIMIT, "limit") }) }
        runCurrent()
        assertEquals("a limit reads nothing", 0, rereads)
        try {
            m.decideShareWith("r1", emptyList(), listOf("i1"), send = { _, _ -> throw VaultFailure(FailureKind.OTHER, "bad_request") })
            error("bad_request must be thrown")
        } catch (e: VaultFailure) {
            assertEquals("bad_request", e.code)
        }
        assertEquals(listOf("i1"), m.items("r1"))
        runCurrent()
        assertEquals("the lists are read again", 1, rereads)
        // The question stays until a list says otherwise; one that no longer lists it clears it.
        assertEquals(listOf("i1"), m.items("r1"))
        val empty = read()
        m.refreshApprovalsFrom(now) { empty }
        runCurrent()
        assertNull(m.share("r1"))
    }

    /** The re-read itself is `refreshApprovals`: it asks for the vault (here on the IO dispatcher, so waited for in real time). */
    @Test
    fun theRereadAsksForTheVault() = runTest {
        val asked = CompletableDeferred<Unit>()
        val m = SocialManager(
            backgroundScope,
            api = { asked.complete(Unit); throw VaultFailure(FailureKind.OTHER) },
            credential = credential,
            obsoleteStore = InMemoryDeviceStateStore(),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        m.refresh(entry("r1", "i1"))
        runCurrent()
        runCatching { m.decideShareWith("r1", emptyList(), listOf("i1"), send = { _, _ -> throw VaultFailure(FailureKind.OTHER, "bad_request") }) }
        runCurrent()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.withTimeout(10_000) { asked.await() } }
        assertEquals(listOf("i1"), m.items("r1"))
    }

    /**
     * An answer that names neither list (`{}`): what was sent is taken as decided, included and declined alike, so both
     * leave the rule and the subject's other rule too (a decision reaches every rule of the subject).
     */
    @Test
    fun anAnswerWithoutListsTakesWhatWasSent() = runTest {
        val m = manager()
        m.refresh(entry("r1", "i1"), entry("r1", "i2"), entry("r1", "i3"), entry("r2", "i1"), entry("r2", "i2"), entry("r2", "i4"))
        runCurrent()
        m.decideShareWith("r1", listOf("i1"), listOf("i2"), send = { _, _ -> o("{}") })
        runCurrent()
        assertEquals(listOf("i3"), m.items("r1"))
        assertEquals(listOf("i4"), m.items("r2"))
    }

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

    /** A decline's answer too: the items it names as declined leave, also another rule's (a decline reaches the subject). */
    @Test
    fun theAnswersDeclinedItemsLeaveToo() = runTest {
        val m = manager()
        m.refresh(entry("r1", "i1"), entry("r2", "i3"), entry("r2", "i4"))
        runCurrent()
        m.decideShareWith("r1", emptyList(), listOf("i1"), send = { _, _ -> o("""{"included":[],"declined":["i1","i3"]}""") })
        runCurrent()
        assertNull(m.share("r1"))
        assertEquals(listOf("i4"), m.items("r2"))
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
            decideShareWith(rule, include, emptyList(), send = { _, _ -> throw VaultFailure(FailureKind.NOT_FOUND) })
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

    // --- A feed item is read once nothing of its question is left, also when another rule's decision emptied it ---

    private suspend fun TestScope.hiddenUnderR1(m: SocialManager) {
        // A waits in r1 and r2 (shown under r1), B only in r2.
        m.refresh(entry("r1", "A"), entry("r2", "A"), entry("r2", "B"))
        runCurrent()
        assertEquals(listOf("A"), m.items("r1"))
        assertEquals(listOf("B"), m.items("r2"))
        m.decide("r2", listOf("B"), """{"included":["B"],"declined":[]}""")
        runCurrent()
        // r2 still holds A in the raw list: unread.
        assertTrue(feedReads.isEmpty())
    }

    @Test
    fun aDecisionUnderOneRuleReadsTheOtherRulesFeedItemItEmptied() = runTest {
        val m = manager()
        hiddenUnderR1(m)
        m.decide("r1", listOf("A"), """{"included":["A"],"declined":[]}""")
        runCurrent()
        assertEquals(setOf<String?>("r1", "r2"), feedReads.toSet())
    }

    @Test
    fun aQuestionStillOpenKeepsItsFeedItemUnread() = runTest {
        val m = manager()
        m.refresh(entry("r1", "A"), entry("r2", "A"), entry("r2", "B"), entry("r2", "C"))
        runCurrent()
        m.decide("r1", listOf("A"), """{"included":["A"],"declined":[]}""")
        runCurrent()
        assertEquals(listOf<String?>("r1"), feedReads)
        assertEquals(listOf("B", "C"), m.items("r2"))
    }

    @Test
    fun aDecisionOnAnotherDeviceReadsTheOtherRulesFeedItemToo() = runTest {
        val m = manager()
        hiddenUnderR1(m)
        m.decided("r1", """"A"""")
        runCurrent()
        assertEquals(setOf<String?>("r1", "r2"), feedReads.toSet())
    }

    /**
     * Owner decision a (2026-11-18): a question shows at most one `share.decide`'s items (§10.12: 500), and the rest are the
     * next round once those are decided.
     */
    @Test
    fun aQuestionOfMoreThanOneDecisionShowsTheFirst500AndTheRestComeNext() = runTest {
        val m = manager()
        val ids = (1..600).map { "i%03d".format(it) }
        m.refresh(*ids.map { entry("r1", it) }.toTypedArray())
        runCurrent()
        assertEquals(ids.take(500), m.items("r1"))

        val shown = m.items("r1")!!
        var sent: Pair<List<String>, List<String>>? = null
        m.decideShareWith("r1", shown.take(300), shown.drop(300), send = { inc, dec ->
            sent = inc to dec
            o("""{"included":[${inc.joinToString(",") { "\"$it\"" }}],"declined":[${dec.joinToString(",") { "\"$it\"" }}]}""")
        })
        runCurrent()
        assertEquals(ids.take(300) to ids.subList(300, 500), sent)
        assertEquals(ids.drop(500), m.items("r1"))
        // The question is still open, so its feed item stays unread.
        assertTrue(feedReads.isEmpty())
    }

    /** The 500 shown are the lowest `item_id`s, in the order they are shown, whatever order a batch added them in. */
    @Test
    fun theCutKeepsTheLowestItemIdsWhateverTheArrivalOrder() = runTest {
        val m = manager()
        val ids = (1..500).map { "i%03d".format(it) }
        m.refresh(*ids.drop(1).map { entry("r1", it) }.toTypedArray(), entry("r1", "i900"))
        runCurrent()
        // A batch adds the lowest id after the rest: it is shown, and the highest goes to the next round.
        m.batch("r1", "i001")
        runCurrent()
        assertEquals(ids.toSet(), m.items("r1")!!.toSet())
        assertEquals(500, m.items("r1")!!.size)
        assertEquals("i001", m.items("r1")!!.last())
    }

    /** The cut comes after the one-question-per-item rule: a rule's own items are counted, not the ones another rule asks. */
    @Test
    fun theCutCountsOnlyTheItemsARuleShows() = runTest {
        val m = manager()
        val shared = (1..100).map { "i%03d".format(it) }
        val own = (1..450).map { "j%03d".format(it) }
        m.refresh(*shared.map { entry("r1", it) }.toTypedArray(), *(shared + own).map { entry("r2", it) }.toTypedArray())
        runCurrent()
        assertEquals(shared, m.items("r1"))
        // r2 waits on 550 items, but 100 are asked under r1: its 450 own all show.
        assertEquals(own, m.items("r2"))
    }

    /** What is sent is what was checked: each id once, 1 to 500 of them, none in both lists; else nothing is sent. */
    @Test
    fun aDecisionIsSentDedupedAndRefusedOutsideOneToFiveHundred() = runTest {
        val m = manager()
        val ids = (1..501).map { "i$it" }
        var sends = 0
        var rereads = 0
        m.staleShareReread = { rereads++ }
        suspend fun refused(include: List<String>, decline: List<String>) {
            val e = runCatching { m.decideShareWith("r1", include, decline, send = { _, _ -> sends++; o("{}") }) }.exceptionOrNull()
            assertEquals("bad_request", (e as VaultFailure).code)
        }
        refused(emptyList(), emptyList())
        refused(ids.take(250), ids.drop(250))
        refused(listOf("i1", "i2"), listOf("i2"))
        runCurrent()
        assertEquals(0, sends)
        // A refusal made here is not the vault's `bad_request`: the lists are not read again for it.
        assertEquals(0, rereads)
        // 500 distinct ids and a repeat are sent as the 500, once each.
        var sent: Pair<List<String>, List<String>>? = null
        m.decideShareWith("r1", ids.take(400) + ids.first(), ids.subList(400, 500) + ids[450], send = { inc, dec -> sent = inc to dec; o("{}") })
        assertEquals(ids.take(400) to ids.subList(400, 500), sent)
    }
}
