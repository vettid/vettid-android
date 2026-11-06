// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.votes

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.crypto.vote.VoteMerkle
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

private fun obj(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject

private const val CONN = "conn-1"
private const val PID = "01JBQZ0000000000000000000A"
private const val OPEN_PID = "01JBQZ0000000000000000000E"

/** The votes list, the cast, the site sync and the results check (ANDROID-PLAN 0.1.108 "Votes"; VAULT-MESSAGING 0.81.3 §10.19.8). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@Suppress("LargeClass") // one manager, one set of fakes: the cases read best side by side
class VotesManagerTest {
    private var clock = Instant.parse("2026-11-01T12:00:00Z")

    private fun summary(
        id: String = PID,
        conn: String = CONN,
        state: String = "open",
        extra: String = "",
    ) = """{"proposal_id":"$id","connection_id":"$conn","title":"Adopt the budget","state":"$state","opens_at":"2026-11-01T09:00:00.000Z","closes_at":"2026-11-06T17:00:00.000Z","can_cast":true$extra}"""

    private fun listing(vararg votes: String, tail: String = "") = obj("""{"votes":[${votes.joinToString(",")}],"dropped":[],"problems":[],"reconnect":[]$tail}""")

    /** The quiet refresh runs on the IO dispatcher: wait for it in real time. */
    private suspend fun eventually(cond: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (!cond()) delay(10)
            }
        }
    }

    private fun event(type: String, body: String) = VaultMessage(Inner(id = Ulid.new(), type = type, ts = clock, body = body.toByteArray()))

    private class Ops : VoteOps {
        var list: JsonObject = JsonObject(emptyMap())
        var detail: JsonObject = JsonObject(emptyMap())
        var evidence: JsonObject = obj("""{"evidence":{"proposal":"AAAA","member":{"ballot":{"choice_id":"yes"}}}}""")
        val calls = mutableListOf<String>()
        val acks = mutableListOf<List<String>>()
        val synced = mutableListOf<JsonObject>()
        val blobs = mutableListOf<Int>()
        val submitted = mutableListOf<JsonObject>()
        var refuse = mutableMapOf<String, String>()
        var castGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var submitAnswer: JsonObject = obj("""{"proposal_id":"$PID","check":{"state":"verified"}}""")

        private fun go(type: String) {
            synchronized(this) { calls += type }
            refuse[type]?.let { throw VaultOpException(type, it) }
        }

        override suspend fun list(after: String?, ack: List<String>): JsonObject {
            go("list")
            acks += ack
            return list
        }

        override suspend fun get(connectionId: String, proposalId: String): JsonObject {
            go("get:$connectionId/$proposalId")
            return detail
        }

        override suspend fun cast(connectionId: String, proposalId: String, choiceId: String): JsonObject {
            castGate?.await()
            go("cast:$connectionId/$proposalId:$choiceId")
            return obj("""{"proposal_id":"$proposalId","ballot":{"state":"sending"}}""")
        }

        override suspend fun check(connectionId: String, proposalId: String) = go("check:$connectionId/$proposalId")

        override suspend fun confirm(connectionId: String, proposalId: String, mine: Boolean): JsonObject {
            go("confirm:$connectionId/$proposalId:$mine")
            return obj("""{"ballot":{"state":"${if (mine) "recorded" else "disputed"}"}}""")
        }

        override suspend fun evidence(connectionId: String, proposalId: String): JsonObject {
            go("evidence:$connectionId/$proposalId")
            return evidence
        }

        override suspend fun release(connectionId: String, proposalId: String, pin: String) = go("release:$connectionId/$proposalId")

        /** A `vote.site.sync` body the vault refuses as a whole (`bad_request`), as it did before 0.81.5's checks. */
        var refuseSync: (JsonObject) -> Boolean = { false }

        override suspend fun sync(body: JsonObject): JsonObject {
            go("sync")
            if (refuseSync(body)) throw VaultOpException("vote.site.sync", "bad_request")
            synced += body
            return obj("""{"entries":[]}""")
        }

        override suspend fun syncBlob(blobId: String, keyB64: String, sha256B64: String, size: Int): JsonObject {
            go("syncBlob:$blobId")
            blobs += size
            return obj("""{"entries":[]}""")
        }

        override suspend fun uploadBlob(blob: ByteArray): String {
            go("upload")
            return "01JBQZ0000000000000000000B"
        }

        override suspend fun submit(body: JsonObject): JsonObject {
            go("submit")
            submitted += body
            return submitAnswer
        }
    }

    private class Site(var hasSite: Boolean = true) : VoteSite {
        val answers = mutableMapOf<String, SiteAnswer>()
        val reads = mutableListOf<String>()
        val maxes = mutableMapOf<String, Long>()
        override val configured: Boolean get() = hasSite

        override suspend fun get(path: String, maxBytes: Long): SiteAnswer {
            synchronized(this) {
                reads += path
                maxes[path] = maxBytes
            }
            return answers[path] ?: SiteAnswer.Absent
        }
    }

    private class Window {
        var until: Instant? = null
        val opened = mutableListOf<String>()
        var closed = 0
        var openFail: VaultFailure? = null
        var closeFail: Exception? = null
    }

    private fun TestScope.manager(
        ops: Ops,
        site: Site = Site(),
        window: Window = Window(),
        compute: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
        observeLimit: Duration = Duration.ofSeconds(60),
        computeStep: (String) -> Unit = {},
    ) = VotesManager(
        this,
        ops = { ops },
        site = site,
        windowUntil = { window.until },
        openWindow = {
            window.opened += it
            window.until = clock.plusSeconds(300)
            window.openFail?.let { f -> throw f }
        },
        closeWindow = {
            window.closed++
            window.until = null
            window.closeFail?.let { f -> throw f }
        },
        unlockTtl = { 300 },
        now = { clock },
        compute = compute,
        computeStep = computeStep,
        observeLimit = observeLimit,
    )

    @Test
    fun theListIsKeptByThePairAndNeverCarriesAChoice() = runTest {
        val ops = Ops().apply {
            list = listing(summary(extra = ""), summary(conn = "conn-2"), tail = "")
        }
        val m = manager(ops)
        m.refresh()
        assertEquals(VotesAvailability.AVAILABLE, m.availability.value)
        assertEquals(ListLoad.LOADED, m.load.value)
        // Two services with the same proposal id are two votes.
        assertEquals(listOf("conn-1/$PID", "conn-2/$PID"), m.votes.value.map { it.key })
        assertEquals("Adopt the budget", m.titleOf(CONN, PID))
        assertNull(m.titleOf("conn-3", PID))
    }

    @Test
    fun aTitleWithControlAndBidirectionalCharactersIsShownClean() = runTest {
        val ops = Ops().apply { list = obj("""{"votes":[{"proposal_id":"$PID","connection_id":"$CONN","title":"Budget‮decided\n\u0007now","state":"open","can_cast":true}],"dropped":[],"problems":[],"reconnect":[]}""") }
        val m = manager(ops)
        m.refresh()
        val t = m.votes.value.single().title
        assertFalse(t.any { it == '‮' || it == '\n' || it == '\u0007' })
        assertTrue(t.startsWith("Budget"))
    }

    @Test
    fun aVaultWithoutVotesIsAskedAgainOnlyAfterTheNextUnlock() = runTest {
        val ops = Ops().apply { refuse["list"] = "unsupported_type" }
        val m = manager(ops)
        try {
            m.refresh()
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.NOT_SUPPORTED, e.kind)
        }
        assertEquals(VotesAvailability.UNSUPPORTED, m.availability.value)
        m.refreshQuietly()
        m.refresh()
        advanceUntilIdle()
        assertEquals(1, ops.calls.count { it == "list" })
        m.clear()
        assertEquals(VotesAvailability.UNKNOWN, m.availability.value)
        ops.refuse.clear()
        ops.list = listing(summary())
        m.refresh()
        assertEquals(VotesAvailability.AVAILABLE, m.availability.value)
    }

    @Test
    fun theListAnnouncesDroppedProblemsAndReconnect() = runTest {
        val ops = Ops().apply {
            list = obj("""{"votes":[],"dropped":[{"connection_id":"c1","count":3,"last_at":"2026-11-01T10:00:00.000Z"}],"problems":[{"connection_id":"c1","count":16}],"reconnect":[{"connection_id":"c2","service_id":"svc","name":"Example Org"}],"list_version":2,"list_sha256":"AAAA"}""")
        }
        val m = manager(ops)
        m.refresh()
        assertEquals(listOf(DroppedNotice("c1", 3)), m.dropped.value)
        assertEquals(listOf(ProblemsNotice("c1", 16)), m.problems.value)
        assertEquals(listOf(ReconnectEntry("c2", "svc", "Example Org")), m.reconnect.value)
    }

    @Test
    fun aShownDroppedNoticeIsAcknowledgedWithTheNextListAndDoesNotReturn() = runTest {
        val ops = Ops().apply {
            list = obj("""{"votes":[],"dropped":[{"connection_id":"c1","count":3,"last_at":"2026-11-01T10:00:00.000Z"}],"problems":[],"reconnect":[]}""")
        }
        val m = manager(ops)
        m.refresh()
        m.acknowledgeDropped(listOf("c1"))
        assertTrue(m.dropped.value.isEmpty())
        // The vault drops the entry once it has the acknowledgement.
        ops.list = obj("""{"votes":[],"dropped":[],"problems":[],"reconnect":[]}""")
        eventually { ops.acks.size >= 2 }
        assertEquals(listOf("c1"), ops.acks.last())
        assertTrue(m.dropped.value.isEmpty())
        m.refresh()
        assertTrue(ops.acks.last().isEmpty())
    }

    @Test
    fun aCastOpensTheWindowOnlyWhenItIsClosedAndClosesWhatItOpened() = runTest {
        val ops = Ops().apply { list = listing(summary()) }
        val window = Window()
        val m = manager(ops, window = window)
        m.refresh()
        assertEquals(BallotState.SENDING, m.cast(CONN, PID, "yes", "secret"))
        assertEquals(listOf("secret"), window.opened)
        assertEquals(1, window.closed)
        assertTrue("cast:$CONN/$PID:yes" in ops.calls)

        // A window that is already open is used and left as it was; the password is not asked for or used.
        window.until = clock.plusSeconds(120)
        assertTrue(m.windowOpen())
        m.cast(CONN, PID, "no", null)
        assertEquals(1, window.opened.size)
        assertEquals(1, window.closed)
    }

    @Test
    fun aCastWithAClosedWindowAndNoPasswordIsRefusedByTheVaultAndSaysSo() = runTest {
        val ops = Ops().apply {
            list = listing(summary())
            refuse["cast:$CONN/$PID:yes"] = "credential_locked"
        }
        val m = manager(ops)
        m.refresh()
        try {
            m.cast(CONN, PID, "yes", null)
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.CREDENTIAL_LOCKED, e.kind)
        }
    }

    @Test
    fun noCastWhileARestoreWaitsForConfirmation() = runTest {
        val ops = Ops().apply { list = listing(summary(extra = ""","confirm_pending":true""")) }
        val m = manager(ops)
        m.refresh()
        try {
            m.cast(CONN, PID, "yes", "pw")
            fail()
        } catch (e: VaultFailure) {
            assertEquals("checking", e.code)
        }
        // Nothing was sent, and no window was opened for it.
        assertTrue(ops.calls.none { it.startsWith("cast") })
    }

    @Test
    fun aWindowThePromptOpenedIsClosedEvenWhenTheCastFails() = runTest {
        val ops = Ops().apply { refuse["cast:$CONN/$PID:yes"] = "not_open" }
        val window = Window()
        val m = manager(ops, window = window)
        try {
            m.cast(CONN, PID, "yes", "pw")
            fail()
        } catch (_: VaultFailure) {
        }
        assertEquals(1, window.closed)
    }

    @Test
    fun confirmCheckEvidenceAndReleaseNameTheConnection() = runTest {
        val ops = Ops()
        val m = manager(ops)
        assertEquals(BallotState.RECORDED, m.confirm(CONN, PID, true))
        assertEquals(BallotState.DISPUTED, m.confirm(CONN, PID, false))
        m.check(CONN, PID)
        m.release(CONN, PID, "123456")
        assertTrue(ops.calls.containsAll(listOf("confirm:$CONN/$PID:true", "confirm:$CONN/$PID:false", "check:$CONN/$PID", "release:$CONN/$PID")))
    }

    @Test
    fun theEvidenceIsTheVaultsOwnObjectUnchanged() = runTest {
        val ops = Ops()
        val m = manager(ops)
        val text = m.evidence(CONN, PID)
        assertEquals(obj("""{"proposal":"AAAA","member":{"ballot":{"choice_id":"yes"}}}"""), Json.parseToJsonElement(text))
        assertTrue("evidence:$CONN/$PID" in ops.calls)
    }

    @Test
    fun theDetailKeepsTheChoicesInTheOrderSentAndTheMembersOwnChoice() = runTest {
        val ops = Ops().apply {
            detail = obj(
                """{"proposal_id":"$PID","connection_id":"$CONN","title":"T","state":"open","can_cast":true,"text":"Line one\n\nLine two <b>x</b> http://example.test","choices":[{"id":"no","label":"No"},{"id":"abstain","label":"Yes, really"},{"id":"yes","label":"Yes"}],"quorum":{"text":"Half","min_ballots":2},"binding":true,"record_tag":"AAAA","choice_id":"yes","extensions":1}""",
            )
        }
        val m = manager(ops)
        val d = m.get(CONN, PID)
        assertEquals(listOf("no", "abstain", "yes"), d.choices.map { it.id })
        // The reserved abstain keeps the label it was sent with.
        assertEquals("Yes, really", d.choices[1].label)
        assertEquals("yes", d.choiceId)
        assertEquals("Line one\n\nLine two <b>x</b> http://example.test", d.text)
        assertEquals(2L, d.minBallots)
        assertEquals(true, d.binding)
        assertFalse(d.toString().contains("yes"))
    }

    @Test
    fun aVoteEventReadsTheListAgain() = runTest {
        val ops = Ops().apply { list = listing(summary()) }
        val m = manager(ops)
        m.onEvent(event("vote.event", """{"proposal_id":"$PID","connection_id":"$CONN","event":"ballot","state":"open"}"""))
        eventually { m.votes.value.size == 1 }
        assertEquals(1, ops.calls.count { it == "list" })
        m.onEvent(event("sync.event", """{"kind":"x"}"""))
        delay(100)
        assertEquals(1, ops.calls.count { it == "list" })
    }

    @Test
    fun aLockForgetsEverythingAndALateAnswerIsNotApplied() = runTest {
        val ops = Ops().apply { list = listing(summary()) }
        val m = manager(ops)
        m.refresh()
        m.clear()
        assertTrue(m.votes.value.isEmpty())
        assertEquals(ListLoad.NOT_LOADED, m.load.value)
        assertTrue(m.hints.value.isEmpty())
    }

    @Test
    fun aVoteMissingFromTheSiteIsLateAfterTenMinutesAndOnlyThen() = runTest {
        val ops = Ops().apply { list = listing(summary(extra = ""","site":"not_listed"""")) }
        val m = manager(ops)
        m.refresh()
        val h = m.hints.value.getValue("$CONN/$PID")
        assertFalse(h.notListedLate(clock))
        assertFalse(h.notListedLate(clock.plus(Duration.ofMinutes(9))))
        assertTrue(h.notListedLate(clock.plus(Duration.ofMinutes(10))))
        // Listed again: the stamp goes.
        ops.list = listing(summary(extra = ""","site":"matches""""))
        m.refresh()
        assertTrue(m.hints.value.isEmpty())
    }

    // ---- The public site ----

    private fun ok(s: String) = SiteAnswer.Found(s.toByteArray())

    private val servicesDoc = """{"v":1,"version":3,"previous":"AAAA","services":[]}"""

    @Test
    fun theSyncSendsTheProposalsTheVaultHoldsAbsentWithNoTime() = runTest {
        val ops = Ops().apply { list = listing(summary(), summary(id = "01JBQZ0000000000000000000C"), tail = ""","list_version":3,"list_sha256":"${Base64s.encodeStd(Bytes.sha256(servicesDoc.toByteArray()))}"""") }
        val site = Site().apply {
            answers["services.json"] = ok(servicesDoc)
            answers["services.json.sig"] = SiteAnswer.Found(ByteArray(64))
            answers["index.json"] = ok("[]")
            answers["p/$PID/proposal.json"] = ok("""{"proposal_id":"$PID"}""")
            answers["p/$PID/proposal.json.sig"] = SiteAnswer.Found(ByteArray(64) { 1 })
            answers["p/$PID/proposal.json.sigstore.json"] = ok("""{"mediaType":"x"}""")
            answers["p/01JBQZ0000000000000000000C/proposal.json"] = SiteAnswer.Unreachable
        }
        val m = manager(ops, site)
        m.syncOnce()
        // The list the vault holds is only confirmed.
        val confirm = ops.synced.first()
        assertEquals("3", ((confirm["services_confirm"] as JsonObject)["version"] as JsonPrimitive).content)
        assertNull(confirm["services"])
        val entries = (ops.synced.last()["entries"] as JsonArray).map { it as JsonObject }
        assertEquals(2, entries.size)
        val first = entries[0]["document"] as JsonObject
        assertEquals("found", (first["status"] as JsonPrimitive).content)
        assertEquals("unreachable", ((entries[1]["document"] as JsonObject)["status"] as JsonPrimitive).content)
        // No time from the app, anywhere.
        assertFalse(ops.synced.joinToString { it.toString() }.contains("\"at\""))
    }

    @Test
    fun anAbsentProposalIsSentAbsentAndFetchedAgainAtTheNextSync() = runTest {
        val ops = Ops().apply { list = listing(summary()) }
        val site = Site().apply { answers["index.json"] = ok("[]") }
        val m = manager(ops, site)
        m.syncOnce()
        val doc = (ops.synced.last()["entries"] as JsonArray)[0].let { (it as JsonObject)["document"] as JsonObject }
        assertEquals("absent", (doc["status"] as JsonPrimitive).content)
        m.syncOnce()
        assertEquals(2, site.reads.count { it == "p/$PID/proposal.json" })
    }

    @Test
    fun theSyncDoesNothingWhereThereIsNoSite() = runTest {
        val ops = Ops().apply { list = listing(summary()) }
        val m = manager(ops, Site(hasSite = false))
        m.syncOnce()
        assertTrue(ops.calls.isEmpty())
    }

    @Test
    fun aProposalTheIndexListsButTheVaultLacksIsOfferedWithItsIndexElement() = runTest {
        val newer = "01JBQZ0000000000000000000D"
        val ops = Ops().apply { list = listing(summary()) }
        val site = Site().apply {
            answers["index.json"] = ok("""[{"proposal_id":"$newer","service_id":"s","proposal_hash":"AAAA","opens_at":1,"closes_at":2,"extended":0,"published":false}]""")
            answers["p/$newer/proposal.json"] = ok("""{"proposal_id":"$newer"}""")
        }
        val m = manager(ops, site)
        m.syncOnce()
        val entries = (ops.synced.last()["entries"] as JsonArray).map { it as JsonObject }
        assertEquals(listOf(PID, newer), entries.map { (it["proposal_id"] as JsonPrimitive).content })
        assertNull(entries[0]["index"])
        assertTrue(entries[1]["index"] is JsonObject)
        // The index said 0 extensions: none is probed.
        assertTrue(site.reads.none { it.contains("extension-") })
    }

    @Test
    fun aProposalWithoutAnIndexElementProbesItsExtensionsInOrder() = runTest {
        val ops = Ops().apply { list = listing(summary()) }
        val site = Site().apply {
            answers["index.json"] = ok("[]")
            answers["p/$PID/proposal.json"] = ok("""{"proposal_id":"$PID"}""")
            answers["p/$PID/extension-1.json"] = ok("""{"n":1}""")
            answers["p/$PID/extension-1.json.sig"] = SiteAnswer.Found(ByteArray(64))
            answers["p/$PID/extension-1.json.sigstore.json"] = ok("{}")
        }
        val m = manager(ops, site)
        m.syncOnce()
        val exts = (((ops.synced.last()["entries"] as JsonArray)[0] as JsonObject)["document"] as JsonObject)["extensions"] as JsonArray
        assertEquals(1, exts.size)
        assertTrue("p/$PID/extension-2.json" in site.reads)
        assertFalse("p/$PID/extension-3.json" in site.reads)
    }

    @Test
    fun aCatchUpIsHandedOverOldestFirstAndASmallBodyGoesInline() = runTest {
        val v2 = """{"v":1,"version":2,"previous":"AAAA","services":[]}"""
        val v3 = """{"v":1,"version":3,"previous":"AAAA","services":[]}"""
        val v4 = """{"v":1,"version":4,"previous":"AAAA","services":[]}"""
        val ops = Ops().apply { list = listing(tail = ""","list_version":1,"list_sha256":"AAAA"""") }
        val site = Site().apply {
            answers["services.json"] = ok(v4)
            answers["services.json.sig"] = SiteAnswer.Found(ByteArray(64))
            answers["services/2.json"] = ok(v2)
            answers["services/2.json.sig"] = SiteAnswer.Found(ByteArray(64))
            answers["services/3.json"] = ok(v3)
            answers["services/3.json.sig"] = SiteAnswer.Found(ByteArray(64))
            answers["index.json"] = ok("[]")
        }
        val m = manager(ops, site)
        m.syncOnce()
        val lists = (ops.synced.first()["services"] as JsonArray).map { Base64s.decodeStd(((it as JsonObject)["doc"] as JsonPrimitive).content).toString(Charsets.UTF_8) }
        assertEquals(listOf(v2, v3, v4), lists)
        assertTrue(ops.blobs.isEmpty())
    }

    @Test
    fun aCatchUpMissingAVersionSendsOnlyWhatChains() = runTest {
        val v2 = """{"v":1,"version":2,"previous":"AAAA","services":[]}"""
        val v4 = """{"v":1,"version":4,"previous":"AAAA","services":[]}"""
        val ops = Ops().apply { list = listing(tail = ""","list_version":1,"list_sha256":"AAAA"""") }
        val site = Site().apply {
            answers["services.json"] = ok(v4)
            answers["services.json.sig"] = SiteAnswer.Found(ByteArray(64))
            answers["services/2.json"] = ok(v2)
            answers["services/2.json.sig"] = SiteAnswer.Found(ByteArray(64))
            // services/3.json is not served yet (404): the current list cannot chain without it.
            answers["index.json"] = ok("[]")
        }
        val m = manager(ops, site)
        m.syncOnce()
        val lists = ops.synced.first()["services"] as JsonArray
        assertEquals(1, lists.size)
    }

    @Test
    fun aRequestOverTheInlineLimitTravelsAsABlobAndOneOverTheBodyLimitIsNeverSent() = runTest {
        // Four proposals of 60,000 bytes (each within the vault's 65,536) are about 320 kB encoded together.
        val ids = (1..4).map { "01JBQZ0000000000000000001$it" }
        val ops = Ops().apply { list = listing(*ids.map { summary(id = it) }.toTypedArray()) }
        val site = Site().apply {
            answers["index.json"] = ok("[]")
            for (id in ids) answers["p/$id/proposal.json"] = ok("x".repeat(60_000))
        }
        val m = manager(ops, site)
        m.syncOnce()
        // Over 245,760, so a blob call; the inline call is not used for it.
        assertEquals(1, ops.blobs.size)
        assertTrue(ops.blobs[0] > VoteSync.INLINE_MAX)
        assertTrue(ops.synced.isEmpty())
        assertTrue(ops.calls.contains("upload"))
    }

    // ---- The results ----

    private fun tagOf(n: Int) = ByteArray(32) { if (it == 0) n.toByte() else 7 }

    private fun resultsFor(rowsJson: String, rowsRoot: ByteArray, eligible: Int): String {
        val hash = Base64s.encodeStd(Bytes.sha256(rowsJson.toByteArray()))
        return """{"counts":{"ballots":0,"eligible":$eligible,"removed":0,"unhandled":0},"extensions":[],"proposal":{"doc":"AA==","sig":"AA=="},"proposal_id":"$PID","rows_hash":"$hash","rows_root":"${Base64s.encodeStd(rowsRoot)}","tally":{},"v":1}"""
    }

    private fun closedWithPendingCheck(extra: String = "") =
        summary(state = "closed", extra = ""","ballot":{"state":"recorded"},"check":{"state":"pending","due_at":"2026-11-01T11:00:00.000Z"}$extra""")

    @Test
    fun aDueCheckHandsTheMembersRowAndProofToTheVaultWhichAloneJudges() = runTest {
        val tags = (1..3).map(::tagOf)
        val rowsJson = """{"proposal_id":"$PID","rows":[${tags.joinToString(",") { """{"ballot":null,"record_tag":"${Base64s.encodeStd(it)}"}""" }}]}"""
        val results = resultsFor(rowsJson, VoteMerkle.root(tags), 3)
        val ops = Ops().apply {
            list = listing(closedWithPendingCheck())
            detail = obj("""{"proposal_id":"$PID","connection_id":"$CONN","title":"T","state":"closed","record_tag":"${Base64s.encodeStd(tags[1])}","choice_id":"yes"}""")
        }
        val site = Site().apply {
            answers["index.json"] = ok("[]")
            answers["p/$PID/results.json"] = ok(results)
            answers["p/$PID/results.json.sig"] = SiteAnswer.Found(ByteArray(64) { 9 })
            answers["p/$PID/results.json.sigstore.json"] = ok("""{"mediaType":"x"}""")
            answers["p/$PID/rows.json"] = ok(rowsJson)
        }
        val m = manager(ops, site)
        m.syncOnce()
        val b = ops.submitted.single()
        assertEquals(CONN, (b["connection_id"] as JsonPrimitive).content)
        assertEquals(PID, (b["proposal_id"] as JsonPrimitive).content)
        val proof = b["row_proof"] as JsonObject
        assertEquals("1", (proof["index"] as JsonPrimitive).content)
        assertEquals("3", (proof["tree_size"] as JsonPrimitive).content)
        assertEquals(2, (proof["path"] as JsonArray).size)
        assertEquals("""{"ballot":null}""", (b["row"] as JsonObject).toString())
        assertNull("the vault checks the signature and the bundle itself", b["verdict"])
    }

    // ---- One bad site item stops nothing else (VAULT-MESSAGING 0.81.5 §10.19.8) ----

    /** A vault with one open proposal (held) and one closed vote whose results check is due, and a site serving all of it. */
    private fun dueScenario(): Pair<Ops, Site> {
        val tags = (1..3).map(::tagOf)
        val rowsJson = """{"proposal_id":"$PID","rows":[${tags.joinToString(",") { """{"ballot":null,"record_tag":"${Base64s.encodeStd(it)}"}""" }}]}"""
        val ops = Ops().apply {
            list = listing(closedWithPendingCheck(), summary(id = OPEN_PID))
            detail = obj("""{"proposal_id":"$PID","connection_id":"$CONN","title":"T","state":"closed","record_tag":"${Base64s.encodeStd(tags[1])}","choice_id":"yes"}""")
        }
        val site = Site().apply {
            answers["services.json"] = ok(servicesDoc)
            answers["services.json.sig"] = SiteAnswer.Found(ByteArray(64))
            answers["index.json"] = ok("[]")
            answers["p/$OPEN_PID/proposal.json"] = ok("""{"proposal_id":"$OPEN_PID"}""")
            answers["p/$OPEN_PID/proposal.json.sig"] = SiteAnswer.Found(ByteArray(64))
            answers["p/$PID/results.json"] = ok(resultsFor(rowsJson, VoteMerkle.root(tags), 3))
            answers["p/$PID/results.json.sig"] = SiteAnswer.Found(ByteArray(64) { 9 })
            answers["p/$PID/results.json.sigstore.json"] = ok("""{"mediaType":"x"}""")
            answers["p/$PID/rows.json"] = ok(rowsJson)
        }
        return ops to site
    }

    private fun Ops.entriesSent(): List<JsonObject> = synced.flatMap { (it["entries"] as? JsonArray).orEmpty().map { e -> e as JsonObject } }

    private fun Ops.listsSent(): Int = synced.sumOf { (it["services"] as? JsonArray)?.size ?: 0 }

    private fun JsonObject.status(): String = ((this["document"] as JsonObject)["status"] as JsonPrimitive).content

    @Test
    fun theWholeRunGoesWhenEverythingIsWellFormed() = runTest {
        val (ops, site) = dueScenario()
        manager(ops, site).syncOnce()
        assertEquals(1, ops.listsSent())
        assertEquals(2, ops.entriesSent().size)
        assertEquals(1, ops.submitted.size)
    }

    @Test
    fun aListWithABadSigOrAnEmptyDocIsNotSentAndTheRestGoes() = runTest {
        for (bad in listOf<Pair<String, SiteAnswer>>("services.json.sig" to SiteAnswer.Found(ByteArray(63)), "services.json" to SiteAnswer.Found(ByteArray(0)))) {
            val (ops, site) = dueScenario()
            site.answers[bad.first] = bad.second
            manager(ops, site).syncOnce()
            assertEquals(bad.first, 0, ops.listsSent())
            assertEquals(bad.first, 2, ops.entriesSent().size)
            assertEquals(bad.first, 1, ops.submitted.size)
        }
    }

    @Test
    fun aProposalOverTheLimitGoesUnreachableAndTheRestGoes() = runTest {
        val (ops, site) = dueScenario()
        site.answers["p/$OPEN_PID/proposal.json"] = SiteAnswer.Found(ByteArray(VoteSync.PROPOSAL_MAX + 1))
        manager(ops, site).syncOnce()
        val e = ops.entriesSent().single { (it["proposal_id"] as JsonPrimitive).content == OPEN_PID }
        assertEquals("unreachable", e.status())
        assertEquals(1, ops.submitted.size)
    }

    @Test
    fun anExtensionOverTheLimitEndsTheListAndTheEntryGoesWithTheOnesBefore() = runTest {
        val (ops, site) = dueScenario()
        for (n in 1..3) {
            site.answers["p/$OPEN_PID/extension-$n.json"] = SiteAnswer.Found(ByteArray(if (n == 2) VoteSync.EXTENSION_MAX + 1 else 10))
            site.answers["p/$OPEN_PID/extension-$n.json.sig"] = SiteAnswer.Found(ByteArray(64))
            site.answers["p/$OPEN_PID/extension-$n.json.sigstore.json"] = ok("{}")
        }
        manager(ops, site).syncOnce()
        val e = ops.entriesSent().single { (it["proposal_id"] as JsonPrimitive).content == OPEN_PID }
        assertEquals(1, ((e["document"] as JsonObject)["extensions"] as JsonArray).size)
        assertEquals(1, ops.submitted.size)
    }

    @Test
    fun anIndexElementWithANonStringServiceIdIsLeftOutAndTheEntryGoes() = runTest {
        val (ops, site) = dueScenario()
        site.answers["index.json"] = ok("""[{"proposal_id":"$OPEN_PID","service_id":7,"proposal_hash":"AAAA","opens_at":1,"closes_at":2,"extended":0,"published":true}]""")
        manager(ops, site).syncOnce()
        val e = ops.entriesSent().single { (it["proposal_id"] as JsonPrimitive).content == OPEN_PID }
        assertNull(e["index"])
        assertEquals("found", e.status())
        assertEquals(1, ops.submitted.size)
    }

    @Test
    fun resultsWithABadSigOrNothingInThemAreNotSubmittedAndShowTheProblem() = runTest {
        for (bad in listOf<Pair<String, SiteAnswer>>("p/$PID/results.json.sig" to SiteAnswer.Found(ByteArray(63)), "p/$PID/results.json" to SiteAnswer.Found(ByteArray(0)))) {
            val (ops, site) = dueScenario()
            site.answers[bad.first] = bad.second
            val m = manager(ops, site)
            m.syncOnce()
            assertTrue(bad.first, ops.submitted.isEmpty())
            assertTrue(bad.first, m.hints.value.values.any { it.resultsProblem })
            // The lists and the entries went all the same.
            assertEquals(bad.first, 1, ops.listsSent())
            assertEquals(bad.first, 2, ops.entriesSent().size)
        }
    }

    @Test
    fun anIndexElementThatIsNotStrictOrHasNoValidIdIsLeftOut() = runTest {
        val (ops, site) = dueScenario()
        val unheld = "01JBQZ0000000000000000000F"
        // The held proposal's element has a duplicate name; another (not held) has a proposal_id that is not a ULID; a third is good.
        site.answers["index.json"] = ok("""[{"proposal_id":"$OPEN_PID","service_id":"s","service_id":"t"},{"proposal_id":"bad id","service_id":"s"},{"proposal_id":"$unheld","service_id":"s","proposal_hash":"AAAA","opens_at":1,"closes_at":2,"extended":0,"published":true}]""")
        manager(ops, site).syncOnce()
        val byId = ops.entriesSent().associateBy { (it["proposal_id"] as JsonPrimitive).content }
        assertNull(byId.getValue(OPEN_PID)["index"])
        assertTrue(byId.getValue(unheld)["index"] is JsonObject)
        assertFalse(byId.containsKey("bad id"))
        assertEquals(1, ops.submitted.size)
    }

    @Test
    fun aBundleThatIsNotStrictIsLeftOutAndAnExtensionsEndsTheList() = runTest {
        val (ops, site) = dueScenario()
        site.answers["p/$OPEN_PID/proposal.json.sigstore.json"] = ok("""{"a":1,"a":1}""")
        for (n in 1..2) {
            site.answers["p/$OPEN_PID/extension-$n.json"] = SiteAnswer.Found(ByteArray(10))
            site.answers["p/$OPEN_PID/extension-$n.json.sig"] = SiteAnswer.Found(ByteArray(64))
            site.answers["p/$OPEN_PID/extension-$n.json.sigstore.json"] = ok(if (n == 1) "{}" else """{"x":{"b":1,"b":2}}""")
        }
        manager(ops, site).syncOnce()
        val doc = ops.entriesSent().single { (it["proposal_id"] as JsonPrimitive).content == OPEN_PID }["document"] as JsonObject
        assertNull(doc["bundle"])
        assertEquals(1, (doc["extensions"] as JsonArray).size)
        assertEquals(1, ops.submitted.size)
    }

    @Test
    fun aChoiceIdTheVaultWouldRefuseIsNotSubmitted() = runTest {
        val (ops, site) = dueScenario()
        // The member's own row (index 1) carries a ballot whose choice_id is not the vault's form (1-32 of [a-z0-9-]).
        val tags = (1..3).map(::tagOf)
        val vk = ByteArray(32) { 3 }
        val sig = ByteArray(64) { 4 }
        val choice = "Bad_Id"
        fun lp(x: ByteArray) = Bytes.concat(Bytes.uintBE(x.size.toLong(), 2), x)
        val leaves = listOf(tags[0], Bytes.concat(tags[1], lp(vk), lp(choice.toByteArray()), lp(sig)), tags[2])
        val ballot = """{"vk":"${Base64s.encodeStd(vk)}","choice_id":"$choice","ballot_sig":"${Base64s.encodeStd(sig)}"}"""
        val rowsJson = """{"proposal_id":"$PID","rows":[${tags.mapIndexed { n, t -> """{"ballot":${if (n == 1) ballot else "null"},"record_tag":"${Base64s.encodeStd(t)}"}""" }.joinToString(",")}]}"""
        site.answers["p/$PID/rows.json"] = ok(rowsJson)
        site.answers["p/$PID/results.json"] = ok(resultsFor(rowsJson, VoteMerkle.root(leaves), 3))
        val m = manager(ops, site)
        m.syncOnce()
        assertTrue(ops.submitted.isEmpty())
        assertTrue(m.hints.value.values.any { it.resultsProblem && !it.resultsTooLarge })
        assertEquals(2, ops.entriesSent().size)
    }

    @Test
    fun aRefusedStageDoesNotStopTheNext() = runTest {
        val (ops, site) = dueScenario()
        // The vault refuses the list call: the entries and the results check go all the same, and the refusal is told after.
        ops.refuseSync = { it["services"] != null }
        val e = runCatching { manager(ops, site).syncOnce() }.exceptionOrNull()
        assertTrue("$e", e is VaultFailure)
        assertEquals(2, ops.entriesSent().size)
        assertEquals(1, ops.submitted.size)
    }

    @Test
    fun aRefusedEntryCallDoesNotStopTheOtherCalls() = runTest {
        val (ops, site) = dueScenario()
        // More held proposals than one call carries: two entry calls; the vault refuses the first.
        val ids = (1..VoteSync.MAX_ENTRIES + 2).map { "01JBQZ00000000000000000%03d".format(it) }
        ops.list = listing(closedWithPendingCheck(), *ids.map { summary(id = it) }.toTypedArray())
        var calls = 0
        ops.refuseSync = { it["entries"] != null && calls++ == 0 }
        runCatching { manager(ops, site).syncOnce() }
        assertEquals(2, calls)
        assertTrue(ops.entriesSent().isNotEmpty())
        assertEquals(1, ops.submitted.size)
    }

    @Test
    fun aCheckIsNotRunBeforeItIsDue() = runTest {
        val ops = Ops().apply { list = listing(closedWithPendingCheck().replace("2026-11-01T11:00:00.000Z", "2026-11-01T13:00:00.000Z").let { it }) }
        val site = Site().apply { answers["index.json"] = ok("[]") }
        val m = manager(ops, site)
        m.syncOnce()
        assertTrue(ops.submitted.isEmpty())
        assertTrue(site.reads.none { it.endsWith("results.json") })
    }

    @Test
    fun resultsThatAreNotPublishedOrNotReachableAreTold() = runTest {
        val ops = Ops().apply {
            list = listing(closedWithPendingCheck())
            detail = obj("""{"proposal_id":"$PID","connection_id":"$CONN","title":"T","state":"closed","record_tag":"${Base64s.encodeStd(tagOf(1))}"}""")
        }
        val site = Site().apply { answers["index.json"] = ok("[]") }
        val m = manager(ops, site)
        m.refresh()
        assertEquals(ResultsOutcome.NotPublished, m.checkResults(CONN, PID))
        assertEquals(GaveUpWhy.UNPUBLISHED, m.hints.value.getValue("$CONN/$PID").gaveUp)
        site.answers["p/$PID/results.json"] = SiteAnswer.Unreachable
        assertEquals(ResultsOutcome.SiteUnreachable, m.checkResults(CONN, PID))
        assertEquals(GaveUpWhy.SITE, m.hints.value.getValue("$CONN/$PID").gaveUp)
        assertTrue(ops.submitted.isEmpty())
    }

    @Test
    fun rowsThatDoNotMatchTheResultsAreAProblemAndNothingIsSubmitted() = runTest {
        val tags = (1..3).map(::tagOf)
        val rowsJson = """{"proposal_id":"$PID","rows":[${tags.joinToString(",") { """{"ballot":null,"record_tag":"${Base64s.encodeStd(it)}"}""" }}]}"""
        val results = resultsFor(rowsJson, ByteArray(32) { 5 }, 3) // not the root of these rows
        val ops = Ops().apply {
            list = listing(closedWithPendingCheck())
            detail = obj("""{"proposal_id":"$PID","connection_id":"$CONN","title":"T","state":"closed","record_tag":"${Base64s.encodeStd(tags[1])}"}""")
        }
        val site = Site().apply {
            answers["index.json"] = ok("[]")
            answers["p/$PID/results.json"] = ok(results)
            answers["p/$PID/results.json.sig"] = SiteAnswer.Found(ByteArray(64))
            answers["p/$PID/results.json.sigstore.json"] = ok("{}")
            answers["p/$PID/rows.json"] = ok(rowsJson)
        }
        val m = manager(ops, site)
        m.refresh()
        assertEquals(ResultsOutcome.Problem, m.checkResults(CONN, PID))
        assertTrue(m.hints.value.getValue("$CONN/$PID").resultsProblem)
        assertTrue(ops.submitted.isEmpty())
    }

    @Test
    fun aSubmissionTheVaultLeavesPendingWithReasonInputIsAProblemWithTheResultsNotAFailure() = runTest {
        val tags = (1..3).map(::tagOf)
        val rowsJson = """{"proposal_id":"$PID","rows":[${tags.joinToString(",") { """{"ballot":null,"record_tag":"${Base64s.encodeStd(it)}"}""" }}]}"""
        val ops = Ops().apply {
            list = listing(closedWithPendingCheck())
            detail = obj("""{"proposal_id":"$PID","connection_id":"$CONN","title":"T","state":"closed","record_tag":"${Base64s.encodeStd(tags[1])}"}""")
            submitAnswer = obj("""{"proposal_id":"$PID","check":{"state":"pending","reason":"input"}}""")
        }
        val site = Site().apply {
            answers["index.json"] = ok("[]")
            answers["p/$PID/results.json"] = ok(resultsFor(rowsJson, VoteMerkle.root(tags), 3))
            answers["p/$PID/results.json.sig"] = SiteAnswer.Found(ByteArray(64))
            answers["p/$PID/results.json.sigstore.json"] = ok("{}")
            answers["p/$PID/rows.json"] = ok(rowsJson)
        }
        val m = manager(ops, site)
        m.refresh()
        val out = m.checkResults(CONN, PID) as ResultsOutcome.Submitted
        assertEquals(CheckState.PENDING, out.state)
        assertTrue(m.hints.value.getValue("$CONN/$PID").resultsProblem)
    }

    @Test
    fun aGaveUpCheckLearnsWhichOfTwoThingsFromTheSite() = runTest {
        val ops = Ops().apply { list = listing(summary(state = "closed", extra = ""","check":{"state":"gave_up"}""")) }
        val site = Site()
        val m = manager(ops, site)
        m.refresh()
        m.diagnoseGaveUp(CONN, PID)
        assertEquals(GaveUpWhy.UNPUBLISHED, m.hints.value.getValue("$CONN/$PID").gaveUp)
    }

    // ---- The unlock window a cast opens ----

    @Test
    fun aCancelledCastStillLocksTheWindowItOpened() = runTest {
        val ops = Ops().apply {
            list = listing(summary())
            castGate = kotlinx.coroutines.CompletableDeferred()
        }
        val window = Window()
        val m = manager(ops, window = window)
        m.refresh()
        val job = launch(Dispatchers.Default) { m.cast(CONN, PID, "yes", "pw") }
        eventually { window.opened.isNotEmpty() }
        job.cancel()
        job.join()
        // The lock ran to its end although its caller was cancelled (a screen that left).
        assertEquals(1, window.closed)
        assertNull(m.windowNotClosed.value)
    }

    @Test
    fun anUnlockWhoseAnswerWasLostIsStillLocked() = runTest {
        val ops = Ops().apply { list = listing(summary()) }
        val window = Window().apply { openFail = VaultFailure(FailureKind.NO_RESPONSE) }
        val m = manager(ops, window = window)
        m.refresh()
        try {
            m.cast(CONN, PID, "yes", "pw")
            fail()
        } catch (e: VaultFailure) {
            assertEquals(FailureKind.NO_RESPONSE, e.kind)
        }
        assertEquals(1, window.closed)
        assertTrue(ops.calls.none { it.startsWith("cast") })
    }

    @Test
    fun aWrongPasswordOpenedNothingSoNothingIsLocked() = runTest {
        val window = Window().apply { openFail = VaultFailure(FailureKind.BAD_PASSWORD, "bad_password") }
        val m = manager(Ops(), window = window)
        try {
            m.cast(CONN, PID, "yes", "pw")
            fail()
        } catch (_: VaultFailure) {
        }
        assertEquals(0, window.closed)
    }

    @Test
    fun aLockThatFailedIsToldNotSwallowed() = runTest {
        val ops = Ops().apply { list = listing(summary()) }
        val window = Window().apply { closeFail = VaultFailure(FailureKind.NO_RESPONSE) }
        val m = manager(ops, window = window)
        m.refresh()
        m.cast(CONN, PID, "yes", "pw")
        // The notice knows how long the window stays open unused (the end time the unlock answered with).
        assertEquals(WindowNotClosed(300), m.windowNotClosed.value)
        // Close it now: the notice stays while the lock fails, and goes only when it went through.
        try {
            m.closeWindowNow()
            fail()
        } catch (_: VaultFailure) {
        }
        assertEquals(WindowNotClosed(300), m.windowNotClosed.value)
        window.closeFail = null
        m.closeWindowNow()
        assertNull(m.windowNotClosed.value)
    }

    @Test
    fun anUnlockThatFailsInAnyWayButARefusalIsStillLocked() = runTest {
        for (failure in listOf(VaultFailure(FailureKind.OTHER, "unreadable_result"), VaultFailure(FailureKind.NETWORK))) {
            val window = Window().apply { openFail = failure }
            val m = manager(Ops(), window = window)
            try {
                m.cast(CONN, PID, "yes", "pw")
                fail()
            } catch (_: VaultFailure) {
            }
            assertEquals("${failure.kind}", 1, window.closed)
        }
        // Only a definite refusal opened nothing.
        for (kind in listOf(
            FailureKind.BAD_PASSWORD, FailureKind.BACKOFF, FailureKind.CREDENTIAL_FROZEN, FailureKind.ROTATION_REQUIRED,
            FailureKind.OWNER_CHECK_REQUIRED, FailureKind.CREDENTIAL_UNSETTLED,
        )) {
            val window = Window().apply { openFail = VaultFailure(kind) }
            val m = manager(Ops(), window = window)
            try {
                m.cast(CONN, PID, "yes", "pw")
                fail()
            } catch (_: VaultFailure) {
            }
            assertEquals("$kind", 0, window.closed)
        }
    }

    // ---- Limits ----

    @Test
    fun aHugeVersionInAnUnverifiedListFetchesAtMostEightAndSendsWhatWasFetched() = runTest {
        val ops = Ops().apply { list = listing(tail = ""","list_version":1,"list_sha256":"AAAA"""") }
        val site = Site().apply {
            answers["services.json"] = ok("""{"v":1,"version":1000000000000,"previous":"AAAA","services":[]}""")
            answers["services.json.sig"] = SiteAnswer.Found(ByteArray(64))
            answers["index.json"] = ok("[]")
            for (v in 2L..20L) {
                answers["services/$v.json"] = ok("""{"v":1,"version":$v,"previous":"AAAA","services":[]}""")
                answers["services/$v.json.sig"] = SiteAnswer.Found(ByteArray(64))
            }
        }
        val m = manager(ops, site)
        m.syncOnce()
        assertEquals(8, site.reads.count { it.startsWith("services/") && it.endsWith(".json") })
        val lists = (ops.synced.first()["services"] as JsonArray).size
        assertEquals(8, lists)
    }

    @Test
    fun resultsOverTheVaultsLimitAreAProblemWithTheResultsNotASiteThatIsDown() = runTest {
        val ops = Ops().apply {
            list = listing(closedWithPendingCheck())
            detail = obj("""{"proposal_id":"$PID","connection_id":"$CONN","title":"T","state":"closed","record_tag":"${Base64s.encodeStd(tagOf(1))}"}""")
        }
        val site = Site().apply {
            answers["index.json"] = ok("[]")
            answers["p/$PID/results.json"] = SiteAnswer.TooLarge
        }
        val m = manager(ops, site)
        m.refresh()
        assertEquals(ResultsOutcome.Problem, m.checkResults(CONN, PID))
        assertTrue(m.hints.value.getValue("$CONN/$PID").resultsTooLarge)
        assertTrue(m.hints.value.getValue("$CONN/$PID").resultsProblem)
        assertTrue(ops.submitted.isEmpty())
    }

    // ---- The observer's check (VAULT-MESSAGING 0.86.4 §10.19.6 step 1, ANDROID-PLAN 0.1.116/0.1.117) ----

    /**
     * Results that pass the app's whole observer check: a proposal naming three choices, the tags' record root and three
     * eligible members, rows.json exactly `{v, proposal_id, rows}` with three empty rows, and a tally of zeros.
     */
    private class Observed(val rowsJson: String, val results: String, val tags: List<ByteArray>)

    private fun observed(proposalIdInResults: String = PID, tally: String = """{"abstain":0,"no":0,"yes":0}""", n: Int = 3): Observed {
        val tags = (1..n).map { i -> ByteArray(32) { 7 }.also { it[0] = (i shr 8).toByte(); it[1] = i.toByte() } }
        val rowsJson = """{"proposal_id":"$PID","rows":[${tags.joinToString(",") { """{"ballot":null,"record_tag":"${Base64s.encodeStd(it)}"}""" }}],"v":1}"""
        val proposal = """{"choices":[{"id":"yes","label":"Yes"},{"id":"no","label":"No"},{"id":"abstain","label":"Abstain"}],"eligible":$n,"record_root":"${Base64s.encodeStd(VoteMerkle.root(tags))}"}"""
        val hash = Base64s.encodeStd(Bytes.sha256(rowsJson.toByteArray()))
        val results = """{"counts":{"ballots":0,"eligible":$n,"removed":0,"unhandled":0},"extensions":[],"proposal":{"doc":"${Base64s.encodeStd(proposal.toByteArray())}","sig":"AA=="},"proposal_id":"$proposalIdInResults","rows_hash":"$hash","rows_root":"${Base64s.encodeStd(VoteMerkle.root(tags))}","tally":$tally,"v":1}"""
        return Observed(rowsJson, results, tags)
    }

    private fun observedScenario(o: Observed, detailExtra: String = ""): Pair<Ops, Site> {
        val ops = Ops().apply {
            list = listing(closedWithPendingCheck())
            detail = obj("""{"proposal_id":"$PID","connection_id":"$CONN","title":"T","state":"closed","record_tag":"${Base64s.encodeStd(o.tags[1])}"$detailExtra}""")
        }
        val site = Site().apply {
            answers["index.json"] = ok("[]")
            answers["p/$PID/results.json"] = ok(o.results)
            answers["p/$PID/results.json.sig"] = SiteAnswer.Found(ByteArray(64) { 9 })
            answers["p/$PID/results.json.sigstore.json"] = ok("""{"mediaType":"x"}""")
            answers["p/$PID/rows.json"] = ok(o.rowsJson)
        }
        return ops to site
    }

    private suspend fun TestScope.observedFlag(ops: Ops, site: Site): Boolean {
        val m = manager(ops, site)
        m.refresh()
        assertTrue(m.checkResults(CONN, PID) is ResultsOutcome.Submitted)
        assertEquals("submitted whatever the observer found", 1, ops.submitted.size)
        return m.hints.value["$CONN/$PID"]?.observerFailed == true
    }

    @Test
    fun resultsThatPassTheObserversCheckAreSubmittedWithoutTheFlag() = runTest {
        val (ops, site) = observedScenario(observed())
        assertFalse(observedFlag(ops, site))
        // The vault's own tally and counts, when it holds them, agree too.
        val (ops2, site2) = observedScenario(
            observed(),
            ""","tally":{"abstain":0,"no":0,"yes":0},"counts":{"eligible":3,"ballots":0,"removed":0,"unhandled":0}""",
        )
        assertFalse(observedFlag(ops2, site2))
    }

    @Test
    fun aFailedObserverCheckStillSubmitsAndSetsTheFlag() = runTest {
        // A tally that does not recompute.
        val (ops, site) = observedScenario(observed(tally = """{"abstain":0,"no":1,"yes":0}"""))
        assertTrue(observedFlag(ops, site))
    }

    @Test
    fun whatIsOnlyReportedSetsTheFlagAndStillSubmits() = runTest {
        // results.json names another proposal.
        val (ops, site) = observedScenario(observed(proposalIdInResults = "01JBQZ0000000000000000000Z"))
        assertTrue(observedFlag(ops, site))
        // rows.json is not exactly {v, proposal_id, rows} (rebuilt without v, with its own hash).
        val o = observed()
        val noV = o.rowsJson.replace(""","v":1""", "")
        val results = o.results.replace(Base64s.encodeStd(Bytes.sha256(o.rowsJson.toByteArray())), Base64s.encodeStd(Bytes.sha256(noV.toByteArray())))
        val (ops2, site2) = observedScenario(Observed(noV, results, o.tags))
        assertTrue(observedFlag(ops2, site2))
    }

    @Test
    fun aTallyOrCountsOtherThanTheVaultsSetTheFlag() = runTest {
        val (ops, site) = observedScenario(observed(), ""","tally":{"abstain":0,"no":0,"yes":1}""")
        assertTrue(observedFlag(ops, site))
        val (ops2, site2) = observedScenario(observed(), ""","counts":{"eligible":3,"ballots":0,"removed":1,"unhandled":0}""")
        assertTrue(observedFlag(ops2, site2))
    }

    @Test
    fun anAttemptThatSubmitsNothingClearsTheFlagAndALostSubmissionKeepsIt() = runTest {
        val (ops, site) = observedScenario(observed(tally = """{"abstain":0,"no":1,"yes":0}"""))
        val m = manager(ops, site)
        m.refresh()
        // A submission the vault refuses: the flag stays (ANDROID-PLAN 0.1.117).
        ops.refuse["submit"] = "internal"
        try {
            m.checkResults(CONN, PID)
            fail("refused")
        } catch (_: VaultFailure) {
        }
        assertTrue(m.hints.value.getValue("$CONN/$PID").observerFailed)
        // An attempt that submits nothing (the signature is not 64 bytes): the flag goes.
        ops.refuse.clear()
        site.answers["p/$PID/results.json.sig"] = SiteAnswer.Found(ByteArray(63))
        assertEquals(ResultsOutcome.Problem, m.checkResults(CONN, PID))
        assertFalse(m.hints.value.getValue("$CONN/$PID").observerFailed)
    }

    @Test
    fun rowsJsonIsDownloadedUpToItsCapForTheEligibleCount() = runTest {
        val (ops, site) = observedScenario(observed())
        observedFlag(ops, site)
        assertEquals(3 * 512L + 4_096, site.maxes["p/$PID/rows.json"])
        // Results whose counts do not read: a problem, and rows.json is not fetched.
        val (ops2, site2) = observedScenario(observed())
        site2.answers["p/$PID/results.json"] = ok("""{"counts":{"eligible":0}}""")
        val m = manager(ops2, site2)
        m.refresh()
        assertEquals(ResultsOutcome.Problem, m.checkResults(CONN, PID))
        assertFalse(site2.reads.contains("p/$PID/rows.json"))
        assertTrue(ops2.submitted.isEmpty())
    }

    // ---- Review 1 ----

    @Test
    fun theResultsAreReadProvedAndObservedOffTheCallersThread() = runTest {
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "vote-compute") }
        try {
            val steps = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
            val (ops, site) = observedScenario(observed())
            val m = manager(ops, site, compute = pool.asCoroutineDispatcher()) { steps += it to Thread.currentThread().name }
            m.refresh()
            assertTrue(m.checkResults(CONN, PID) is ResultsOutcome.Submitted)
            val stages = steps.map { it.first.substringAfterLast(':') }.toSet()
            assertEquals(setOf("read", "row", "observe"), stages)
            assertTrue("$steps", steps.all { it.second == "vote-compute" })
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun aCancelledCheckStopsAtItsNextStepAndSubmitsNothing() = runTest {
        val (ops, site) = observedScenario(observed())
        val steps = java.util.Collections.synchronizedList(mutableListOf<String>())
        var job: Job? = null
        val m = manager(ops, site) {
            steps += it
            if (steps.size == 1) job!!.cancel()
        }
        m.refresh()
        job = launch(start = CoroutineStart.LAZY) { m.checkResults(CONN, PID) }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals("$steps", 1, steps.size)
        assertTrue(ops.submitted.isEmpty())
    }

    @Test
    fun aLargeVoteChecksForCancellationEvery256Rows() = runTest {
        val (ops, site) = observedScenario(observed(n = 1024))
        val steps = java.util.Collections.synchronizedList(mutableListOf<String>())
        val m = manager(ops, site) { steps += it.substringAfterLast(':') }
        m.refresh()
        assertTrue(m.checkResults(CONN, PID) is ResultsOutcome.Submitted)
        // The reading, the tree and the signature loop each look at least once per 256 rows.
        assertTrue("$steps", steps.count { it == "read" } >= 1 + 1024 / 256)
        assertTrue("$steps", steps.count { it == "observe" } >= 1 + 1024 / 256)
    }

    @Test
    fun oneVotesLongCheckDoesNotHoldUpAnother() = runTest {
        val other = "01JBQZ0000000000000000000C"
        val (ops, site) = observedScenario(observed())
        for (name in listOf("results.json", "results.json.sig", "results.json.sigstore.json", "rows.json")) {
            site.answers["p/$other/$name"] = site.answers.getValue("p/$PID/$name")
        }
        val gate = CompletableDeferred<Unit>()
        // The gate is bounded and always opened, so a shared lock makes this fail instead of hanging.
        val m = manager(ops, site) { if (it.startsWith("$CONN/$PID:")) runBlocking { withTimeoutOrNull(15_000) { gate.await() } } }
        m.refresh()
        val first = launch(Dispatchers.Default) { m.checkResults(CONN, PID) }
        try {
            // While the first vote's check is held in its work, the other vote's check runs to its end.
            val second = withContext(Dispatchers.Default) { withTimeoutOrNull(5_000) { m.checkResults(CONN, other) } }
            assertTrue("the other vote waited for the first", second is ResultsOutcome.Submitted)
        } finally {
            gate.complete(Unit)
            first.join()
        }
        assertEquals(2, ops.submitted.size)
    }

    @Test
    fun anythingThrownInTheObserversWorkIsAnObserverFailureAndTheRowIsStillSubmitted() = runTest {
        val (ops, site) = observedScenario(observed())
        var observeSteps = 0
        val m = manager(ops, site) {
            // The second "observe" step is inside the observer's own work (its first checkpoint).
            if (it.endsWith(":observe") && ++observeSteps == 2) error("a library failure")
        }
        m.refresh()
        assertTrue(m.checkResults(CONN, PID) is ResultsOutcome.Submitted)
        assertEquals(1, ops.submitted.size)
        assertTrue(m.hints.value.getValue("$CONN/$PID").observerFailed)
    }

    @Test
    fun aCancellationInTheObserversWorkIsNotSwallowed() = runTest {
        val (ops, site) = observedScenario(observed())
        var job: Job? = null
        var observeSteps = 0
        val m = manager(ops, site) { if (it.endsWith(":observe") && ++observeSteps == 2) job!!.cancel() }
        m.refresh()
        job = launch(start = CoroutineStart.LAZY) { m.checkResults(CONN, PID) }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(ops.submitted.isEmpty())
    }

    // ---- Lead review ----

    @Test
    fun anObserverCheckThatRunsOutOfTimeIsAnObserverFailureAndTheRowIsStillSubmitted() = runTest {
        val (ops, site) = observedScenario(observed())
        var observeSteps = 0
        // The observer's own work (from its first checkpoint) is slower than the limit: results that would pass, given time.
        val m = manager(ops, site, observeLimit = Duration.ofMillis(100)) {
            if (it.endsWith(":observe") && ++observeSteps == 2) Thread.sleep(600)
        }
        m.refresh()
        assertTrue(m.checkResults(CONN, PID) is ResultsOutcome.Submitted)
        assertEquals(1, ops.submitted.size)
        assertTrue(m.hints.value.getValue("$CONN/$PID").observerFailed)
        assertEquals(ObserverVerdict.FAILED, m.hints.value.getValue("$CONN/$PID").verdict)
    }

    @Test
    fun theVerdictIsSetOnlyByAnAnsweredSubmitAndANonSubmitKeepsIt() = runTest {
        val (ops, site) = observedScenario(observed(tally = """{"abstain":0,"no":1,"yes":0}"""))
        val m = manager(ops, site)
        m.refresh()
        val key = "$CONN/$PID"
        // A refused submission: the line is set, but the vault answered nothing, so there is no verdict.
        ops.refuse["submit"] = "internal"
        try {
            m.checkResults(CONN, PID)
            fail("refused")
        } catch (_: VaultFailure) {
        }
        assertTrue(m.hints.value.getValue(key).observerFailed)
        assertNull(m.hints.value.getValue(key).verdict)
        // Answered: the verdict is the observer's.
        ops.refuse.clear()
        assertTrue(m.checkResults(CONN, PID) is ResultsOutcome.Submitted)
        assertEquals(ObserverVerdict.FAILED, m.hints.value.getValue(key).verdict)
        // Check again with the site down: nothing submitted, the line goes, the verdict stays (no numbers come back).
        site.answers["p/$PID/results.json"] = SiteAnswer.Unreachable
        assertEquals(ResultsOutcome.SiteUnreachable, m.checkResults(CONN, PID))
        assertFalse(m.hints.value.getValue(key).observerFailed)
        assertEquals(ObserverVerdict.FAILED, m.hints.value.getValue(key).verdict)
        // Results that add up, answered: the verdict passes.
        val (ops2, site2) = observedScenario(observed())
        val m2 = manager(ops2, site2)
        m2.refresh()
        assertTrue(m2.checkResults(CONN, PID) is ResultsOutcome.Submitted)
        assertEquals(ObserverVerdict.PASSED, m2.hints.value.getValue(key).verdict)
    }

    /** Observed results around rows given as (tag, ballot JSON or null), in the order given. */
    private fun observedRows(rows: List<Pair<ByteArray, Triple<ByteArray, String, ByteArray>?>>): Observed {
        val o = observed()
        fun lp(x: ByteArray) = Bytes.concat(byteArrayOf((x.size shr 8).toByte(), x.size.toByte()), x)
        val rowsJson = """{"proposal_id":"$PID","rows":[""" + rows.joinToString(",") { (tag, b) ->
            val ballot = b?.let { (vk, choice, sig) ->
                """{"ballot_sig":"${Base64s.encodeStd(sig)}","choice_id":"$choice","vk":"${Base64s.encodeStd(vk)}"}"""
            } ?: "null"
            """{"ballot":$ballot,"record_tag":"${Base64s.encodeStd(tag)}"}"""
        } + """],"v":1}"""
        val leaves = rows.map { (tag, b) -> b?.let { (vk, choice, sig) -> Bytes.concat(tag, lp(vk), lp(choice.toByteArray()), lp(sig)) } ?: tag }
        val results = o.results
            .replace(Base64s.encodeStd(Bytes.sha256(o.rowsJson.toByteArray())), Base64s.encodeStd(Bytes.sha256(rowsJson.toByteArray())))
            .replace(""""rows_root":"${Base64s.encodeStd(VoteMerkle.root(o.tags))}"""", """"rows_root":"${Base64s.encodeStd(VoteMerkle.root(leaves))}"""")
        return Observed(rowsJson, results, o.tags)
    }

    @Test
    fun anOwnRowWithAVkOfAnotherLengthIsStillSubmitted() = runTest {
        // The vault judges a row whose ballot is not the protocol's (a signed contradiction); the app only reports it.
        val tags = observed().tags
        val ballot = Triple(ByteArray(33) { 3 }, "yes", ByteArray(64) { 4 })
        val (ops, site) = observedScenario(observedRows(listOf(tags[0] to null, tags[1] to ballot, tags[2] to null)))
        assertTrue(observedFlag(ops, site))
        val row = (ops.submitted.single()["row"] as JsonObject)["ballot"] as JsonObject
        assertEquals(Base64s.encodeStd(ByteArray(33) { 3 }), (row["vk"] as JsonPrimitive).content)
    }

    @Test
    fun rowsOutOfOrderAreStillSubmittedWithTheOwnRowAtItsIndex() = runTest {
        val tags = observed().tags
        val (ops, site) = observedScenario(observedRows(listOf(tags[1] to null, tags[0] to null, tags[2] to null)))
        assertTrue(observedFlag(ops, site))
        val proof = ops.submitted.single()["row_proof"] as JsonObject
        assertEquals("0", (proof["index"] as JsonPrimitive).content)
    }

    @Test
    fun aLaterFailedCheckWhoseSubmitIsRefusedHidesTheNumbersOfAPassedVerdict() = runTest {
        // Recheck 1, R1-1: a first submit passes; the site later serves results that do not add up, and that submit is refused.
        val (ops, site) = observedScenario(observed())
        val m = manager(ops, site)
        m.refresh()
        val key = "$CONN/$PID"
        assertTrue(m.checkResults(CONN, PID) is ResultsOutcome.Submitted)
        assertEquals(ObserverVerdict.PASSED, m.hints.value.getValue(key).verdict)
        val bad = observed(tally = """{"abstain":0,"no":1,"yes":0}""")
        site.answers["p/$PID/results.json"] = ok(bad.results)
        site.answers["p/$PID/rows.json"] = ok(bad.rowsJson)
        ops.refuse["submit"] = "internal"
        try {
            m.checkResults(CONN, PID)
            fail("refused")
        } catch (_: VaultFailure) {
        }
        val h = m.hints.value.getValue(key)
        assertEquals(ObserverVerdict.PASSED, h.verdict)
        assertTrue(h.observerFailed)
        val verified = VoteWire.summary(obj("""{"proposal_id":"$PID","connection_id":"$CONN","state":"closed","ballot":{"state":"recorded"},"check":{"state":"verified"}}"""))!!
        assertFalse(VoteRules.showNumbers(verified, h.verdict, h.observerFailed))
        assertTrue(VoteNote.RESULTS_DONT_ADD_UP in VoteRules.notes(verified, observerFailed = h.observerFailed))
    }

    @Test
    fun aLockDuringACheckVoidsItsLineItsFlagAndItsSubmit() = runTest {
        // Recheck 1, R1-2: Check again on results that do not add up, the member locks mid-check, then unlocks.
        val (ops, site) = observedScenario(observed(tally = """{"abstain":0,"no":1,"yes":0}"""))
        var m: VotesManager? = null
        var locked = false
        m = manager(ops, site) {
            if (it.endsWith(":observe") && !locked) {
                locked = true
                m!!.clear()
            }
        }
        m.refresh()
        assertEquals(ResultsOutcome.Voided, m.checkResults(CONN, PID))
        assertTrue(ops.submitted.isEmpty())
        m.refresh() // the unlock
        assertNull(m.hints.value["$CONN/$PID"])
    }

    @Test
    fun aLockDuringACheckThatFailsLeavesNoProblemBehind() = runTest {
        val o = observed()
        // A tag the rows do not have: the check ends as a problem, but the lock came first.
        val (ops, site) = observedScenario(Observed(o.rowsJson, o.results, listOf(o.tags[0], ByteArray(32) { 9 }, o.tags[2])))
        var m: VotesManager? = null
        m = manager(ops, site) { if (it.endsWith(":read")) m!!.clear() }
        m.refresh()
        assertEquals(ResultsOutcome.Problem, m.checkResults(CONN, PID))
        m.refresh()
        assertNull(m.hints.value["$CONN/$PID"])
    }
}
