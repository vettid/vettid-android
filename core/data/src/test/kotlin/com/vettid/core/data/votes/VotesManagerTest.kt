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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

/** The votes list, the cast, the site sync and the results check (ANDROID-PLAN 0.1.108 "Votes"; VAULT-MESSAGING 0.81.3 §10.19.8). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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

        override suspend fun sync(body: JsonObject): JsonObject {
            go("sync")
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
        override val configured: Boolean get() = hasSite

        override suspend fun get(path: String, maxBytes: Long): SiteAnswer {
            synchronized(this) { reads += path }
            return answers[path] ?: SiteAnswer.Absent
        }
    }

    private class Window {
        var until: Instant? = null
        val opened = mutableListOf<String>()
        var closed = 0
    }

    private fun TestScope.manager(ops: Ops, site: Site = Site(), window: Window = Window()) = VotesManager(
        this,
        ops = { ops },
        site = site,
        windowUntil = { window.until },
        openWindow = {
            window.opened += it
            window.until = clock.plusSeconds(300)
        },
        closeWindow = {
            window.closed++
            window.until = null
        },
        now = { clock },
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
        val big = "x".repeat(250_000)
        val ops = Ops().apply { list = listing(summary()) }
        val site = Site().apply {
            answers["index.json"] = ok("[]")
            answers["p/$PID/proposal.json"] = ok(big)
        }
        val m = manager(ops, site)
        m.syncOnce()
        // About 333 kB encoded: over 245,760, so a blob call; the inline call is not used for it.
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
}
