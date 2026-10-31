package com.vettid.core.data.votes

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.ClaimBlob
import com.vettid.core.crypto.vote.ResultsProblem
import com.vettid.core.crypto.vote.VoteRows
import com.vettid.core.data.items.ListLoad
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.vaultGuard
import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.uploadBlob
import com.vettid.core.vault.voteCast
import com.vettid.core.vault.voteCheck
import com.vettid.core.vault.voteConfirm
import com.vettid.core.vault.voteEvidence
import com.vettid.core.vault.voteGet
import com.vettid.core.vault.voteList
import com.vettid.core.vault.voteRelease
import com.vettid.core.vault.voteResultsSubmit
import com.vettid.core.vault.voteSiteSync
import com.vettid.core.vault.voteSiteSyncBlob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant

/** Whether the vault has the votes feature (VAULT-MESSAGING §10.19.1): learned from `vote.list`, asked again after an unlock. */
enum class VotesAvailability { UNKNOWN, AVAILABLE, UNSUPPORTED }

/** The lock after a cast failed; [ttlSeconds] is the vault's `credential.unlock_ttl_seconds` (30–3,600), when the app knows it. */
data class WindowNotClosed(val ttlSeconds: Long?)

/** What the app knows about a vote that the vault's list does not say (memory only). */
data class VoteHint(
    /** Why the app's last attempt at the results failed (a `gave_up` check says which of two things). */
    val gaveUp: GaveUpWhy? = null,
    /** The app found a problem in the site's results (they could not be checked); kept until the next attempt. */
    val resultsProblem: Boolean = false,
    /** The site's results are larger than the vault could ever take: no retry will help. */
    val resultsTooLarge: Boolean = false,
    /** When the app first saw the vote missing from the public site. */
    val notListedSince: Instant? = null,
) {
    /** `vote_not_listed_late`: missing from the site for [LATE_AFTER] or more. */
    fun notListedLate(now: Instant): Boolean = notListedSince?.let { Duration.between(it, now) >= LATE_AFTER } == true

    companion object {
        val LATE_AFTER: Duration = Duration.ofMinutes(10)
    }
}

/** What an attempt at a vote's results came to. */
sealed interface ResultsOutcome {
    /** Handed to the vault, which answered [state] (it alone judges). */
    data class Submitted(val state: CheckState) : ResultsOutcome

    /** The site had no results yet (HTTP 404). */
    data object NotPublished : ResultsOutcome

    /** The site could not be reached, or answered in a way that is no answer. */
    data object SiteUnreachable : ResultsOutcome

    /** The documents did not check out in the app's own part of the observer check (BALLOT-PROTOCOL §8.4). */
    data object Problem : ResultsOutcome
}

/**
 * The member's votes (ANDROID-PLAN 0.1.102 to 0.1.108 §4 "Votes"; VAULT-MESSAGING 0.81.3 §10.19.8). Every failure is a
 * [VaultFailure]; a vote is identified by the pair (`connection_id`, `proposal_id`) throughout (0.81.2).
 */
@Suppress("TooManyFunctions")
interface VotesRepository {
    /** The vault's list in its order; empty until read and after a lock. Never carries a choice. */
    val votes: StateFlow<List<VoteSummary>>
    val reconnect: StateFlow<List<ReconnectEntry>>
    val dropped: StateFlow<List<DroppedNotice>>
    val problems: StateFlow<List<ProblemsNotice>>
    val availability: StateFlow<VotesAvailability>
    val load: StateFlow<ListLoad>

    /** What the app itself knows of each vote, by [VoteSummary.key]. */
    val hints: StateFlow<Map<String, VoteHint>>

    /** The lock of the unlock window a cast opened failed: the window stays open until it closes by itself. Told once. */
    val windowNotClosed: StateFlow<WindowNotClosed?>

    /** **Close it now**: locks the window; the notice goes only when that went through, else the failure is thrown. */
    suspend fun closeWindowNow()

    /** Reads `vote.list`. Throws the vault's failure. */
    suspend fun refresh()

    fun refreshQuietly()

    suspend fun get(connectionId: String, proposalId: String): VoteDetail

    /**
     * `vote.cast`: [password] opens the unlock window first when it is closed (and the app closes it again afterwards).
     * Returns the ballot's state. Refused before anything is sent while a restore waits for [confirm].
     */
    suspend fun cast(connectionId: String, proposalId: String, choiceId: String, password: String?): BallotState

    /** Whether the credential's unlock window is open now. */
    fun windowOpen(): Boolean

    suspend fun check(connectionId: String, proposalId: String)

    suspend fun confirm(connectionId: String, proposalId: String, mine: Boolean): BallotState

    /** `vote.evidence`: the vault's evidence as JSON text, unchanged. Held by the caller until the member saves it, then dropped. */
    suspend fun evidence(connectionId: String, proposalId: String): String

    suspend fun release(connectionId: String, proposalId: String, pin: String)

    /** The documents of one vote and the index, fetched and handed to the vault when its screen opens (0.1.107). */
    suspend fun siteCheck(connectionId: String, proposalId: String)

    /** The results of a closed vote, fetched and handed to the vault (the member's **Check again**, and the retry). */
    suspend fun checkResults(connectionId: String, proposalId: String): ResultsOutcome

    /** For a `gave_up` check the app has no reason for: looks at the site once and remembers which of two things. */
    suspend fun diagnoseGaveUp(connectionId: String, proposalId: String)

    /** The `dropped` notice of these connections was shown: sent as `ack_dropped` with the next list. */
    fun acknowledgeDropped(connectionIds: List<String>)

    /** The title of a vote as the list has it, for a notification or History row; null when not known. */
    fun titleOf(connectionId: String, proposalId: String): String?
}

/** The vault calls behind [VotesManager], so that it can be tested without a vault. */
@Suppress("TooManyFunctions")
interface VoteOps {
    suspend fun list(after: String?, ack: List<String>): JsonObject

    suspend fun get(connectionId: String, proposalId: String): JsonObject

    suspend fun cast(connectionId: String, proposalId: String, choiceId: String): JsonObject

    suspend fun check(connectionId: String, proposalId: String)

    suspend fun confirm(connectionId: String, proposalId: String, mine: Boolean): JsonObject

    suspend fun evidence(connectionId: String, proposalId: String): JsonObject

    suspend fun release(connectionId: String, proposalId: String, pin: String)

    suspend fun sync(body: JsonObject): JsonObject

    suspend fun syncBlob(blobId: String, keyB64: String, sha256B64: String, size: Int): JsonObject

    suspend fun uploadBlob(blob: ByteArray): String

    suspend fun submit(body: JsonObject): JsonObject
}

/** [VoteOps] over the vault client. */
@Suppress("TooManyFunctions")
internal class VaultVoteOps(private val api: VaultApi) : VoteOps {
    override suspend fun list(after: String?, ack: List<String>) =
        api.voteList(state = "all", after = after, limit = PAGE, ackDropped = ack)

    override suspend fun get(connectionId: String, proposalId: String) = api.voteGet(connectionId, proposalId)

    override suspend fun cast(connectionId: String, proposalId: String, choiceId: String) = api.voteCast(connectionId, proposalId, choiceId)

    override suspend fun check(connectionId: String, proposalId: String) = api.voteCheck(connectionId, proposalId)

    override suspend fun confirm(connectionId: String, proposalId: String, mine: Boolean) = api.voteConfirm(connectionId, proposalId, mine)

    override suspend fun evidence(connectionId: String, proposalId: String) = api.voteEvidence(connectionId, proposalId)

    override suspend fun release(connectionId: String, proposalId: String, pin: String) = api.voteRelease(connectionId, proposalId, pin)

    override suspend fun sync(body: JsonObject) = api.voteSiteSync(body)

    override suspend fun syncBlob(blobId: String, keyB64: String, sha256B64: String, size: Int) =
        api.voteSiteSyncBlob(blobId, keyB64, sha256B64, size)

    override suspend fun uploadBlob(blob: ByteArray) = api.uploadBlob(blob)

    override suspend fun submit(body: JsonObject) = api.voteResultsSubmit(body)

    private companion object {
        const val PAGE = 100
    }
}

/**
 * [VotesRepository] over [VoteOps] and the public [VoteSite]. Held in memory only, while the vault is open: cleared at a
 * lock, at the hold, on a wipe and with the process (ANDROID-PLAN 0.1.102). The list is read on open and return, and
 * again after a `vote.event`. The hourly work with the site ([startSync]) runs only while the vault is open and unlocked.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class VotesManager(
    private val scope: CoroutineScope,
    private val ops: suspend () -> VoteOps,
    private val site: VoteSite,
    /** The credential's unlock window (§3.5.3): until when it is open, as this app opened it. */
    private val windowUntil: () -> Instant?,
    private val openWindow: suspend (String) -> Unit,
    private val closeWindow: suspend () -> Unit,
    /** The vault setting `credential.unlock_ttl_seconds` (VaultSettings.unlockTtlSeconds); null when it can't be read. */
    private val unlockTtl: suspend () -> Int? = { null },
    private val now: () -> Instant = Instant::now,
    private val syncEvery: Duration = SYNC_EVERY,
) : VotesRepository {
    private val lock = Any()
    private val votesFlow = MutableStateFlow<List<VoteSummary>>(emptyList())
    private val reconnectFlow = MutableStateFlow<List<ReconnectEntry>>(emptyList())
    private val droppedFlow = MutableStateFlow<List<DroppedNotice>>(emptyList())
    private val problemsFlow = MutableStateFlow<List<ProblemsNotice>>(emptyList())
    private val availabilityFlow = MutableStateFlow(VotesAvailability.UNKNOWN)
    private val loadState = MutableStateFlow(ListLoad.NOT_LOADED)
    private val hintsFlow = MutableStateFlow<Map<String, VoteHint>>(emptyMap())
    private val windowNotClosedFlow = MutableStateFlow<WindowNotClosed?>(null)
    private val reads = Mutex()
    private val syncs = Mutex()
    private val results = Mutex()

    /** Bumped by [clear]: a read that started before it is not applied after it. */
    private var generation = 0

    @Volatile
    private var queued = false

    /** `dropped` notices shown and not yet acknowledged to the vault. */
    private val pendingAck = LinkedHashSet<String>()

    @Volatile
    private var listVersion: Long? = null

    @Volatile
    private var listSha256: String? = null
    private var syncJob: Job? = null

    override val votes: StateFlow<List<VoteSummary>> = votesFlow.asStateFlow()
    override val reconnect: StateFlow<List<ReconnectEntry>> = reconnectFlow.asStateFlow()
    override val dropped: StateFlow<List<DroppedNotice>> = droppedFlow.asStateFlow()
    override val problems: StateFlow<List<ProblemsNotice>> = problemsFlow.asStateFlow()
    override val availability: StateFlow<VotesAvailability> = availabilityFlow.asStateFlow()
    override val load: StateFlow<ListLoad> = loadState.asStateFlow()
    override val hints: StateFlow<Map<String, VoteHint>> = hintsFlow.asStateFlow()
    override val windowNotClosed: StateFlow<WindowNotClosed?> = windowNotClosedFlow.asStateFlow()

    /** Forgets everything: the vault locked, was held or wiped. */
    fun clear() {
        stopSync()
        synchronized(lock) {
            generation++
            queued = false
            loadState.value = ListLoad.NOT_LOADED
            availabilityFlow.value = VotesAvailability.UNKNOWN
            votesFlow.value = emptyList()
            reconnectFlow.value = emptyList()
            droppedFlow.value = emptyList()
            problemsFlow.value = emptyList()
            hintsFlow.value = emptyMap()
            windowNotClosedFlow.value = null
            pendingAck.clear()
            listVersion = null
            listSha256 = null
        }
    }

    /** `vote.event` (§10.19.8): the app only; a change of any vote re-reads the list; a due check is run. */
    fun onEvent(m: VaultMessage) {
        if (m.type != "vote.event") return
        refreshQuietly()
        if (VaultJson.str(m.body, "event") == "check_due") scope.launch { runCatching { submitDue() } }
    }

    override fun refreshQuietly() {
        if (queued || availabilityFlow.value == VotesAvailability.UNSUPPORTED) return
        queued = true
        scope.launch { runCatching { read(fromQueue = true) } }
    }

    override suspend fun refresh() = read(fromQueue = false)

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun read(fromQueue: Boolean) {
        reads.withLock {
            if (fromQueue) queued = false
            // A vault without the feature is asked again only after the next unlock (§10.19.8).
            if (availabilityFlow.value == VotesAvailability.UNSUPPORTED) return@withLock
            val gen = synchronized(lock) { generation }
            if (votesFlow.value.isEmpty()) loadState.value = ListLoad.LOADING
            val acks = synchronized(lock) { pendingAck.toList() }
            try {
                val all = ArrayList<VoteSummary>()
                var first: VoteListing? = null
                var after: String? = null
                var pages = 0
                do {
                    val page = VoteWire.listing(vaultGuard(resend = true) { ops().list(after, if (pages == 0) acks else emptyList()) })
                    if (first == null) first = page
                    all += page.votes
                    after = page.next
                    pages++
                } while (after != null && pages < MAX_PAGES)
                val head = first ?: return@withLock
                val shown = all.distinctBy { it.key }
                synchronized(lock) {
                    if (gen != generation) return@withLock
                    votesFlow.value = shown
                    reconnectFlow.value = head.reconnect
                    droppedFlow.value = head.dropped
                    problemsFlow.value = head.problems
                    listVersion = head.listVersion
                    listSha256 = head.listSha256
                    pendingAck.removeAll(acks.toSet())
                    availabilityFlow.value = VotesAvailability.AVAILABLE
                    loadState.value = ListLoad.LOADED
                    hintsFlow.value = hintsFor(shown, hintsFlow.value)
                }
            } catch (e: VaultFailure) {
                synchronized(lock) {
                    if (gen == generation) {
                        if (e.kind == FailureKind.NOT_SUPPORTED) {
                            availabilityFlow.value = VotesAvailability.UNSUPPORTED
                            loadState.value = ListLoad.LOADED
                        } else {
                            loadState.value = ListLoad.FAILED
                        }
                    }
                }
                throw e
            }
        }
    }

    /** The hints of the votes now held: those of a vote that is gone go; a vote seen missing from the site is stamped once. */
    private fun hintsFor(list: List<VoteSummary>, old: Map<String, VoteHint>): Map<String, VoteHint> {
        val at = now()
        val out = LinkedHashMap<String, VoteHint>()
        for (v in list) {
            val h = old[v.key] ?: VoteHint()
            val stamped = when {
                v.site == SiteState.NOT_LISTED -> h.copy(notListedSince = h.notListedSince ?: at)
                h.notListedSince != null -> h.copy(notListedSince = null)
                else -> h
            }
            if (stamped != VoteHint()) out[v.key] = stamped
        }
        return out
    }

    private fun hint(key: String, change: (VoteHint) -> VoteHint) {
        synchronized(lock) {
            val h = change(hintsFlow.value[key] ?: VoteHint())
            hintsFlow.value = if (h == VoteHint()) hintsFlow.value - key else hintsFlow.value + (key to h)
        }
    }

    override suspend fun get(connectionId: String, proposalId: String): VoteDetail {
        val body = vaultGuard(resend = true) { ops().get(connectionId, proposalId) }
        return VoteWire.detail(body) ?: throw VaultFailure(FailureKind.OTHER, "unreadable")
    }

    override fun windowOpen(): Boolean = windowUntil()?.isAfter(now()) == true

    override suspend fun cast(connectionId: String, proposalId: String, choiceId: String, password: String?): BallotState {
        // Fail secure: a vote that waits on "Is this your vote?" is never cast, whatever the screen thought.
        val held = votesFlow.value.firstOrNull { it.connectionId == connectionId && it.proposalId == proposalId }
        if (held?.confirmPending == true) throw VaultFailure(FailureKind.OTHER, "checking")
        // Set before the unlock is sent: an unlock whose answer was lost may still have opened the window, so it is locked.
        val unlocked = Unlocked(password != null && !windowOpen())
        try {
            if (password != null && unlocked.toLock) unlock(password, unlocked)
            val body = vaultGuard { ops().cast(connectionId, proposalId, choiceId) }
            refreshQuietly()
            return BallotState.of(((body["ballot"] as? JsonObject)?.let { VaultJson.str(it, "state") }))
        } finally {
            // A window this prompt opened does not stay open for its full length; one that was open is left as it was.
            if (unlocked.toLock) closeOpenedWindow()
        }
    }

    /**
     * The lock after a cast, which runs to its end even when the caller was cancelled (a screen that left); a lock that
     * failed is not swallowed: the member is told that the window stays open until it closes by itself.
     */
    private suspend fun unlock(password: String, unlocked: Unlocked) {
        try {
            openWindow(password)
        } catch (e: VaultFailure) {
            // Only a definite refusal opened nothing. Any other failure (no answer, an answer that did not read…) may have
            // come after the vault opened the window, so it is locked.
            if (e.kind in DEFINITE_REFUSALS) unlocked.toLock = false
            throw e
        }
    }

    /** Whether the window this cast opened is to be locked. */
    private class Unlocked(var toLock: Boolean)

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun closeOpenedWindow() {
        withContext(NonCancellable) {
            try {
                closeWindow()
            } catch (e: Exception) {
                // The unlock time is the vault's setting; plain words when it can't be read.
                windowNotClosedFlow.value = WindowNotClosed(runCatching { unlockTtl() }.getOrNull()?.toLong())
            }
        }
    }

    override suspend fun closeWindowNow() {
        // The notice goes only when the lock went through; a failure is the caller's to show.
        withContext(NonCancellable) { closeWindow() }
        windowNotClosedFlow.value = null
    }

    override suspend fun check(connectionId: String, proposalId: String) {
        vaultGuard { ops().check(connectionId, proposalId) }
        refreshQuietly()
    }

    override suspend fun confirm(connectionId: String, proposalId: String, mine: Boolean): BallotState {
        val body = vaultGuard { ops().confirm(connectionId, proposalId, mine) }
        refreshQuietly()
        return BallotState.of((body["ballot"] as? JsonObject)?.let { VaultJson.str(it, "state") })
    }

    override suspend fun evidence(connectionId: String, proposalId: String): String {
        val body = vaultGuard { ops().evidence(connectionId, proposalId) }
        val evidence = body["evidence"] as? JsonObject ?: throw VaultFailure(FailureKind.OTHER, "unreadable")
        return evidence.toString()
    }

    override suspend fun release(connectionId: String, proposalId: String, pin: String) {
        vaultGuard { ops().release(connectionId, proposalId, pin) }
        refreshQuietly()
    }

    override fun acknowledgeDropped(connectionIds: List<String>) {
        if (connectionIds.isEmpty()) return
        synchronized(lock) {
            pendingAck += connectionIds
            droppedFlow.value = droppedFlow.value.filterNot { it.connectionId in connectionIds }
        }
        // The acknowledgement goes with the next list read.
        refreshQuietly()
    }

    override fun titleOf(connectionId: String, proposalId: String): String? =
        votesFlow.value.firstOrNull { it.connectionId == connectionId && it.proposalId == proposalId }?.title?.takeIf { it.isNotEmpty() }

    // ---- The public site ----

    /** Starts the work with the site: now, and then every hour, while this runs (the vault is open and unlocked). */
    fun startSync() {
        synchronized(lock) {
            syncJob?.cancel()
            syncJob = scope.launch {
                while (isActive) {
                    try {
                        syncOnce()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: VaultFailure) {
                        // Locked, held or unreachable: the next hour (or the next unlock) tries again; the screens say what waits.
                    }
                    delay(syncEvery.toMillis())
                }
            }
        }
    }

    fun stopSync() {
        synchronized(lock) {
            syncJob?.cancel()
            syncJob = null
        }
    }

    /**
     * One run: the service list, the proposals, and the results that are due, as **separate stages** (VAULT-MESSAGING 0.81.5
     * §10.19.8): a refusal in one does not stop the next, so that a bad or hostile site item cannot hold back a member's results
     * check. The first failure (other than a list refused as too large, which leaves the vault its held list) is thrown once the
     * run is over. Not while another run is going.
     */
    suspend fun syncOnce() {
        if (!site.configured) return
        syncs.withLock {
            read(fromQueue = false)
            if (availabilityFlow.value != VotesAvailability.AVAILABLE) return@withLock
            var first: VaultFailure? = null
            suspend fun stage(block: suspend () -> Unit) {
                try {
                    block()
                } catch (e: VaultFailure) {
                    if (first == null && e.code != CODE_TOO_LARGE) first = e
                }
            }
            stage { syncLists() }
            stage { syncProposals(only = null) }
            stage { submitDue() }
            first?.let { throw it }
        }
    }

    override suspend fun siteCheck(connectionId: String, proposalId: String) {
        if (!site.configured) return
        syncs.withLock { syncProposals(only = proposalId) }
        refreshQuietly()
    }

    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private suspend fun syncLists() {
        val doc = (site.get("services.json", VoteSite.LIST_MAX) as? SiteAnswer.Found)?.bytes ?: return
        val sig = (site.get("services.json.sig", SIG_MAX) as? SiteAnswer.Found)?.bytes ?: return
        val version = VoteSync.versionOf(doc) ?: 0
        val current = VoteSync.ServedList(doc, sig, version)
        val held = listVersion
        val between = ArrayList<VoteSync.ServedList>()
        if (held != null && version > held + 1) {
            // The missing versions in order, once the site serves them; a copy not there yet (404) is for a later run.
            // At most MAX_LISTS a run: `version` is the unverified document's own word, so a huge one cannot loop or fill memory;
            // what was fetched is sent and the rest goes on at the next run.
            val last = minOf(version, held + 1 + VoteSync.MAX_LISTS)
            for (v in (held + 1) until last) {
                val d = (site.get("services/$v.json", VoteSite.LIST_MAX) as? SiteAnswer.Found)?.bytes ?: break
                val s = (site.get("services/$v.json.sig", SIG_MAX) as? SiteAnswer.Found)?.bytes ?: break
                between += VoteSync.ServedList(d, s, v)
            }
        }
        val bodies = when (val plan = VoteSync.planLists(current, held, listSha256, between)) {
            VoteSync.ListPlan.Nothing -> emptyList()
            is VoteSync.ListPlan.Confirm -> listOf(VoteSync.confirmCall(plan.version, plan.sha256))
            is VoteSync.ListPlan.Send -> VoteSync.listCalls(plan.lists)
        }
        for (b in bodies) send(b) ?: break
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private suspend fun syncProposals(only: String?) {
        val held = votesFlow.value.map { it.proposalId }.distinct()
        val index = (site.get("index.json", INDEX_MAX) as? SiteAnswer.Found)?.let { VoteSync.indexOf(it.bytes) }.orEmpty()
        val byId = index.mapNotNull { e -> VoteSync.proposalIdOf(e)?.let { it to e } }.toMap()
        val ids = if (only != null) {
            listOf(only)
        } else {
            // The held proposals, then the newest the vault lacks (it learns what to ask the service for from them).
            held + byId.keys.filter { it !in held }.takeLast(VoteSync.MAX_NEW)
        }
        val entries = ids.map { id -> VoteSync.entryJson(id, byId[id], readDocument(id, byId[id])) }
        // Each call on its own: one the vault refuses does not keep the others from it. The first refusal is thrown after the last.
        var first: VaultFailure? = null
        for (call in VoteSync.entryCalls(entries)) {
            try {
                send(call)
            } catch (e: VaultFailure) {
                if (first == null) first = e
            }
        }
        first?.let { throw it }
    }

    /** One proposal's documents from the site: `proposal.json` with its signature and bundle, and each extension in order. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private suspend fun readDocument(id: String, index: JsonObject?): VoteSync.Document {
        val p = when (val a = site.get("p/$id/proposal.json")) {
            SiteAnswer.Absent -> return VoteSync.Document.Absent
            SiteAnswer.Unreachable, SiteAnswer.TooLarge -> return VoteSync.Document.Unreachable
            is SiteAnswer.Found -> a.bytes
        }
        val sig = when (val a = site.get("p/$id/proposal.json.sig", SIG_MAX)) {
            SiteAnswer.Unreachable, SiteAnswer.TooLarge -> return VoteSync.Document.Unreachable
            SiteAnswer.Absent -> null
            is SiteAnswer.Found -> a.bytes
        }
        val bundle = when (val a = site.get("p/$id/proposal.json.sigstore.json", BUNDLE_MAX)) {
            SiteAnswer.Unreachable, SiteAnswer.TooLarge -> return VoteSync.Document.Unreachable
            SiteAnswer.Absent -> null
            is SiteAnswer.Found -> VoteSync.jsonObject(a.bytes)
        }
        val extensions = ArrayList<VoteSync.Extension>()
        val count = VoteSync.extendedOf(index)?.coerceIn(0, VoteSync.MAX_EXTENSIONS) ?: VoteSync.MAX_EXTENSIONS
        for (n in 1..count) {
            val x = when (val a = site.get("p/$id/extension-$n.json")) {
                SiteAnswer.Absent -> break
                SiteAnswer.Unreachable, SiteAnswer.TooLarge -> return VoteSync.Document.Unreachable
                is SiteAnswer.Found -> a.bytes
            }
            val xs = when (val a = site.get("p/$id/extension-$n.json.sig", SIG_MAX)) {
                SiteAnswer.Unreachable, SiteAnswer.TooLarge -> return VoteSync.Document.Unreachable
                SiteAnswer.Absent -> null
                is SiteAnswer.Found -> a.bytes
            }
            val xb = when (val a = site.get("p/$id/extension-$n.json.sigstore.json", BUNDLE_MAX)) {
                SiteAnswer.Unreachable, SiteAnswer.TooLarge -> return VoteSync.Document.Unreachable
                SiteAnswer.Absent -> null
                is SiteAnswer.Found -> VoteSync.jsonObject(a.bytes)
            }
            extensions += VoteSync.Extension(x, xs, xb)
        }
        return VoteSync.Document.Found(p, sig, bundle, extensions)
    }

    /**
     * Sends one `vote.site.sync` body within the limits (§10.19.8): inline at or under 245,760 bytes, otherwise as a
     * claim-check blob to the app's own vault, and never over 8,257,532 bytes. Null when it was not sent.
     */
    @Suppress("ReturnCount")
    private suspend fun send(body: JsonObject): JsonObject? {
        val bytes = VoteSync.encoded(body)
        if (bytes.size > VoteSync.BODY_MAX) return null
        if (VoteSync.fitsInline(bytes)) return vaultGuard { ops().sync(body) }
        val sealed = ClaimBlob.seal(bytes)
        try {
            val id = uploadWithRetry(sealed.blob) ?: return null
            return vaultGuard {
                ops().syncBlob(id, Base64s.encodeStd(sealed.key), Base64s.encodeStd(sealed.sha256), sealed.size)
            }
        } finally {
            sealed.destroy()
        }
    }

    /** The mailbox's single blob slot is shared with photo uploads: once more after a short wait when it is full. */
    private suspend fun uploadWithRetry(blob: ByteArray): String? {
        repeat(UPLOAD_TRIES) { attempt ->
            try {
                return vaultGuard { ops().uploadBlob(blob) }
            } catch (e: VaultFailure) {
                if (e.kind != FailureKind.LIMIT || attempt == UPLOAD_TRIES - 1) throw e
                delay(UPLOAD_WAIT_MS)
            }
        }
        return null
    }

    // ---- The results ----

    /** The checks that are due: closed, `pending`, and not before the vault's `due_at`. */
    private suspend fun submitDue() {
        val at = now()
        for (v in votesFlow.value) {
            val c = v.check ?: continue
            val due = v.state == VoteState.CLOSED && c.state == CheckState.PENDING && !(c.dueAt != null && c.dueAt.isAfter(at))
            if (due) runCatching { checkResults(v.connectionId, v.proposalId) }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    override suspend fun checkResults(connectionId: String, proposalId: String): ResultsOutcome = results.withLock {
        val key = VoteKeys.of(connectionId, proposalId)
        if (!site.configured) return@withLock ResultsOutcome.SiteUnreachable
        val detail = get(connectionId, proposalId)
        val tag = detail.recordTag?.let { runCatching { Base64s.decodeStd(it, TAG_LEN) }.getOrNull() }
            ?: return@withLock fail(key, ResultsOutcome.Problem)
        var siteDown = false
        var tooLarge = false
        suspend fun fetch(name: String, max: Long): ByteArray? = when (val a = site.get("p/$proposalId/$name", max)) {
            is SiteAnswer.Found -> a.bytes
            SiteAnswer.Absent -> null
            // Over the vault's own limit: results the vault could never take, a problem with them and not a site that is down.
            SiteAnswer.TooLarge -> {
                tooLarge = true
                null
            }
            SiteAnswer.Unreachable -> {
                siteDown = true
                null
            }
        }
        val doc = fetch("results.json", RESULTS_MAX)
        if (tooLarge) return@withLock fail(key, ResultsOutcome.Problem, tooLarge = true)
        if (doc == null) return@withLock unavailable(key, siteDown)
        val sig = fetch("results.json.sig", SIG_MAX)
        val bundleBytes = fetch("results.json.sigstore.json", BUNDLE_MAX)
        val rows = fetch("rows.json", ROWS_MAX)
        if (tooLarge) return@withLock fail(key, ResultsOutcome.Problem, tooLarge = true)
        if (sig == null || bundleBytes == null || rows == null) {
            return@withLock unavailable(key, siteDown)
        }
        // (0.81.5) Results the vault would refuse as a whole are never submitted: the existing problem hint says so.
        if (!VoteSync.submittable(doc, sig)) return@withLock fail(key, ResultsOutcome.Problem)
        val bundle = VoteSync.jsonObject(bundleBytes) ?: return@withLock fail(key, ResultsOutcome.Problem)
        val own = try {
            VoteRows.ownRow(doc, rows, tag)
        } catch (_: ResultsProblem) {
            return@withLock fail(key, ResultsOutcome.Problem)
        }
        val ballot = own.ballot
        val body = buildJsonObject {
            put("connection_id", connectionId)
            put("proposal_id", proposalId)
            put("results", Base64s.encodeStd(doc))
            put("results_sig", Base64s.encodeStd(sig))
            put("bundle", bundle)
            put(
                "row",
                buildJsonObject {
                    if (ballot == null) {
                        put("ballot", JsonNull)
                    } else {
                        put(
                            "ballot",
                            buildJsonObject {
                                put("vk", ballot.string("vk"))
                                put("choice_id", ballot.string("choice_id"))
                                put("ballot_sig", ballot.string("ballot_sig"))
                            },
                        )
                    }
                },
            )
            put(
                "row_proof",
                buildJsonObject {
                    put("index", own.index)
                    put("tree_size", own.treeSize)
                    put("path", JsonArray(own.path.map { JsonPrimitive(Base64s.encodeStd(it)) }))
                },
            )
        }
        // Over the request limit: the vault could never take it.
        if (!VoteSync.fitsInline(VoteSync.encoded(body))) return@withLock fail(key, ResultsOutcome.Problem, tooLarge = true)
        val answer = vaultGuard { ops().submit(body) }
        val check = answer["check"] as? JsonObject
        val state = CheckState.of(check?.let { VaultJson.str(it, "state") })
        // The vault alone judges; a submission it leaves pending (reason input) is the problem the screens say.
        val input = state == CheckState.PENDING && check?.let { c -> VaultJson.str(c, "reason") } == "input"
        hint(key) { it.copy(gaveUp = null, resultsProblem = input, resultsTooLarge = false) }
        refreshQuietly()
        ResultsOutcome.Submitted(state)
    }

    /** The site could not be reached, or had no results (yet): which of the two is what a `gave_up` check will say. */
    private fun unavailable(key: String, siteDown: Boolean): ResultsOutcome =
        if (siteDown) {
            fail(key, ResultsOutcome.SiteUnreachable, GaveUpWhy.SITE)
        } else {
            fail(key, ResultsOutcome.NotPublished, GaveUpWhy.UNPUBLISHED)
        }

    private fun fail(key: String, outcome: ResultsOutcome, why: GaveUpWhy? = null, tooLarge: Boolean = false): ResultsOutcome {
        hint(key) {
            it.copy(gaveUp = why ?: it.gaveUp, resultsProblem = outcome == ResultsOutcome.Problem, resultsTooLarge = tooLarge)
        }
        return outcome
    }

    override suspend fun diagnoseGaveUp(connectionId: String, proposalId: String) {
        if (!site.configured) return
        val key = VoteKeys.of(connectionId, proposalId)
        if (hintsFlow.value[key]?.gaveUp != null) return
        when (site.get("p/$proposalId/results.json", RESULTS_MAX)) {
            SiteAnswer.Absent -> hint(key) { it.copy(gaveUp = GaveUpWhy.UNPUBLISHED) }
            SiteAnswer.Unreachable -> hint(key) { it.copy(gaveUp = GaveUpWhy.SITE) }
            SiteAnswer.TooLarge -> hint(key) { it.copy(resultsProblem = true, resultsTooLarge = true) }
            is SiteAnswer.Found -> Unit // published: **Check again** hands them to the vault
        }
    }

    private companion object {
        val SYNC_EVERY: Duration = Duration.ofHours(1)
        const val MAX_PAGES = 5
        const val TAG_LEN = 32
        /** A failure that says the vault did not open the window (it refused); any other may have followed the opening. */
        val DEFINITE_REFUSALS = setOf(
            FailureKind.BAD_PASSWORD, FailureKind.BACKOFF, FailureKind.CREDENTIAL_FROZEN, FailureKind.ROTATION_REQUIRED,
            FailureKind.OWNER_CHECK_REQUIRED, FailureKind.CREDENTIAL_UNSETTLED,
        )
        const val CODE_TOO_LARGE = "payload_too_large"
        const val SIG_MAX = 256L
        const val BUNDLE_MAX = 64L * 1024
        /** The vault's `MaxResultsDoc`; rows.json is bounded by the rows the app reads (about 256 bytes a row). */
        const val RESULTS_MAX = 196_608L
        const val ROWS_MAX = VoteRows.MAX_ROWS * 256L
        const val INDEX_MAX = 8L shl 20
        const val UPLOAD_TRIES = 2
        const val UPLOAD_WAIT_MS = 5_000L
    }
}
