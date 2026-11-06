package com.vettid.core.data.votes

import androidx.annotation.VisibleForTesting
import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.ClaimBlob
import com.vettid.core.crypto.vote.OwnRow
import com.vettid.core.crypto.vote.PublishedResults
import com.vettid.core.crypto.vote.ResultsProblem
import com.vettid.core.crypto.vote.VotePad
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    /**
     * The app's own observer check of the last results it submitted failed outside the four inputs the vault checks
     * (VAULT-MESSAGING 0.86.4 §10.19.6 step 1, ANDROID-PLAN 0.1.116): the row was still submitted; with a `verified` or
     * `unconfirmed` check no number is shown and "don't add up" is said. A refused or lost submission keeps it; an attempt
     * that submits nothing clears it.
     */
    val observerFailed: Boolean = false,
    /**
     * The verdict of the app's observer check on the last results the vault answered for (review 1, finding 3; ANDROID-PLAN
     * 0.1.117: "the last verdict still decides whether numbers show"). Set only by a submit the vault answered; an attempt
     * that submits nothing leaves it. Null: no verdict in memory yet.
     */
    val verdict: ObserverVerdict? = null,
    /** When the app first saw the vote missing from the public site. */
    val notListedSince: Instant? = null,
    /**
     * The recheck on opening a `verified` or `unconfirmed` vote with no verdict in memory ([VotesRepository.recheck];
     * ANDROID-PLAN 0.1.121 "Recheck on open") could not reach the site: `vote_results_not_rechecked`, and no number.
     */
    val notRechecked: Boolean = false,
) {
    /** `vote_not_listed_late`: missing from the site for [LATE_AFTER] or more. */
    fun notListedLate(now: Instant): Boolean = notListedSince?.let { Duration.between(it, now) >= LATE_AFTER } == true

    companion object {
        val LATE_AFTER: Duration = Duration.ofMinutes(10)
    }
}

/** The app's observer check on results the vault answered for. */
enum class ObserverVerdict { PASSED, FAILED }

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

    /** The vault was locked (or cleared) during the check: nothing of it was kept or sent (recheck 1, R1-2). Shows nothing. */
    data object Voided : ResultsOutcome

    /**
     * A `gave_up` check run again whose `results.json` is the one of the last attempt, which had `rows.json` and did not end
     * on the vault's side (VAULT-MESSAGING 0.86.4 §10.19.6): `rows.json` is not downloaded again and nothing is sent, since
     * the same results, row and proof get the same answer. Shows nothing new.
     */
    data object Unchanged : ResultsOutcome

    /** The recheck on open ran the observer's check again (nothing submitted): [passed] is its verdict. */
    data class Rechecked(val passed: Boolean) : ResultsOutcome
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

    /**
     * The recheck on opening a `verified` or `unconfirmed` vote that has no verdict in memory (ANDROID-PLAN 0.1.121 "Recheck
     * on open", owner decision of 2026-11-02): the published results are fetched and the observer's check runs again. It
     * never submits and stores nothing on disk; a site that cannot be reached sets [VoteHint.notRechecked].
     */
    suspend fun recheck(connectionId: String, proposalId: String): ResultsOutcome

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
// LargeClass: the 0.83.6 results work (rows, attempts, recheck) joined the one manager; splitting it is for the rebase.
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
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
    /** Where the results are read, proved and observed: never the main thread (review 1, finding 1). */
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    /** Called at each step of that work with "<vote key>:<stage>" (tests watch the thread and cancel through it). */
    private val computeStep: (String) -> Unit = {},
    /**
     * The floor of the observer's time box per attempt (lead review M1): the check ([PublishedResults.observe]) gets
     * [observeLimitFor] of `counts.eligible`, at least this. Past it the check is an observer failure and the row is still
     * submitted, so results the observer cannot finish in time cannot stop the submit.
     */
    private val observeLimit: Duration = OBSERVE_LIMIT,
    /**
     * Whether the vault lists the release capability `vote_rows` (VAULT-MESSAGING 0.86.4 §11.10.9, §10.19.6 step 3): only
     * then are `other_rows` and `rows_unavailable` sent; without it the app keeps 0.83.2's behaviour (ANDROID-PLAN "Older vaults").
     */
    private val voteRows: () -> Boolean = { false },
    /** The last attempt at each vote's results (no choice, row or proof): the daily re-check of a `gave_up` one survives a restart. */
    private val attempts: ResultsAttemptStore = MemoryResultsAttemptStore(),
    /** Where [attempts] is read and written. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
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
    /**
     * One lock per vote (review 1, finding 4): a long check of one vote never holds up another's. The same vote is never
     * checked twice at once (the screen's Check again and the due checks). Nothing a check touches is shared between votes
     * but per-key hints, the site client and the vault's calls, which take concurrent use.
     */
    private val resultLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /** Bumped by [clear]: a read that started before it is not applied after it. */
    private var generation = 0

    /** The results checks in flight (under [lock]): [clear] cancels them (lead review L2). */
    private val checks = HashSet<Job>()

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
        val running = synchronized(lock) {
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
            checks.toList()
        }
        // The generation already voids what they would write or send; this stops their work too (lead review L2).
        running.forEach { it.cancel() }
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
            val epoch = storeEpochNow()
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
                // The kept attempts go with their record: a vote no longer listed, or whose check is final. Only from a whole list.
                if (after == null) forgetAttempts(epoch) { a -> shown.none { it.key == a.key && !finalCheck(it.check?.state) } }
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

    /** The [generation] now, as a check takes it at its start (tests: [hint]'s guard). */
    @VisibleForTesting
    internal fun generationNow(): Int = synchronized(lock) { generation }

    /** Changes [key]'s hint; with [gen], only while no lock or clear happened since it was taken (recheck 1, R1-2). */
    @VisibleForTesting
    internal fun hint(key: String, gen: Int? = null, change: (VoteHint) -> VoteHint) {
        synchronized(lock) {
            if (gen != null && gen != generation) return
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
            is SiteAnswer.Found -> VoteSync.jsonObject(a.bytes, VoteSync.BUNDLE_DEPTH)
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
                is SiteAnswer.Found -> VoteSync.jsonObject(a.bytes, VoteSync.EXTENSION_BUNDLE_DEPTH)
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
        when (VoteSync.inline(bytes)) {
            VoteSync.Inline.FITS -> return vaultGuard { ops().sync(body) }
            VoteSync.Inline.TOO_LARGE -> Unit // a claim-check blob, below
            // Not an inner message at all (the strict body check failed): the vault would refuse it either way. Not sent.
            VoteSync.Inline.OTHER -> return null
        }
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

    /**
     * The checks that are due: closed, `pending`, and not before the vault's `due_at`; and (VAULT-MESSAGING 0.86.4 §10.19.6,
     * owner decision a, thread 01M6HK8A1VMV41M8TTASXV4BSK) closed and `gave_up`, which is not final: run again whenever the app
     * opens, as a `pending` one, but at most once a day per record, keyed off the state (a `gave_up` check has no `due_at`).
     */
    private suspend fun submitDue() {
        val at = now()
        for (v in votesFlow.value) {
            val c = v.check ?: continue
            val pending = c.state == CheckState.PENDING && !(c.dueAt != null && c.dueAt.isAfter(at))
            val due = v.state == VoteState.CLOSED && (pending || c.state == CheckState.GAVE_UP && dailyDue(v.key, at))
            // One vote's failure does not stop the others; a cancellation does (it is not caught).
            if (due) {
                try {
                    checkDue(v.connectionId, v.proposalId)
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                    // Whatever it was (told by the vote's own hint where it can be), the other due votes are still checked.
                }
            }
        }
    }

    override suspend fun checkResults(connectionId: String, proposalId: String): ResultsOutcome =
        checkResults(connectionId, proposalId, due = false)

    /**
     * A check [submitDue] found due: a `gave_up` one is looked at again under the vote's lock (review 1, L2), so that two
     * passes that both found it due (a `check_due` event and the sync) do not run it back to back.
     */
    @VisibleForTesting
    internal suspend fun checkDue(connectionId: String, proposalId: String): ResultsOutcome =
        checkResults(connectionId, proposalId, due = true)

    private suspend fun checkResults(connectionId: String, proposalId: String, due: Boolean): ResultsOutcome {
        val key = VoteKeys.of(connectionId, proposalId)
        return resultLocks.computeIfAbsent(key) { Mutex() }.withLock {
            val gaveUp = votesFlow.value.firstOrNull { it.key == key }?.check?.state == CheckState.GAVE_UP
            if (due && gaveUp && !dailyDue(key, now())) return@withLock ResultsOutcome.Unchanged
            val gen = synchronized(lock) { generation }
            val epoch = storeEpochNow()
            val trace = Trace()
            var out: ResultsOutcome? = null
            var cancelled = false
            try {
                asCheck(gen) { checkResultsLocked(key, connectionId, proposalId, gen, trace) }.also { out = it }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                // Kept for the daily re-check, whatever the attempt came to (a failure too: then rows.json is fetched again
                // next time, since a lost submission may be the cause). One cancelled or voided by a lock after it downloaded
                // rows.json still counts toward the daily limit (review 1, L1): only its time and hashes, as rows not had.
                // Never after a wipe: the store's epoch drops the write (M1).
                val counted = !cancelled && out !is ResultsOutcome.Voided && synchronized(lock) { gen == generation }
                if (counted || trace.rowsDownloaded) {
                    withContext(NonCancellable) { recordAttempt(key, epoch, trace, if (counted) out else null, due) }
                }
            }
        }
    }

    /**
     * Runs [block] as its own job, so that a lock ([clear]) can cancel the check and not the caller (lead review L2). The
     * caller's own cancellation (leaving the screen, the sync stopped) goes on; a check that a lock or clear cancelled is
     * void ([ResultsOutcome.Voided]), as one that runs to its end after it (R1-2).
     */
    private suspend fun asCheck(gen: Int, block: suspend () -> ResultsOutcome): ResultsOutcome = coroutineScope {
        val check = async { block() }
        if (!synchronized(lock) { (gen == generation).also { if (it) checks += check } }) check.cancel()
        try {
            check.await()
        } catch (e: CancellationException) {
            if (!isActive || synchronized(lock) { gen == generation }) throw e
            ResultsOutcome.Voided
        } finally {
            synchronized(lock) { checks -= check }
        }
    }

    /** What one attempt saw, for [ResultsAttempt]. */
    private class Trace {
        var resultsSha256: String? = null
        var sigSha256: String? = null
        var bundleSha256: String? = null

        /** rows.json's bytes were downloaded (review 1, L1: such an attempt counts toward the daily limit, whatever follows). */
        var rowsDownloaded = false

        /** The submission carried the member's real row (not `rows_unavailable`). */
        var sentRow = false

        /** An `Unchanged` re-check keeps the last attempt's "answered" (it is the same submission). */
        var answered = false
        var rowsFetched = false
        var vaultSide = false
    }

    /** The rows a submission carries (§10.19.8): the member's row and up to four more under its tag, or `rows_unavailable`. */
    private class Examined(val own: OwnRow?, val others: List<OwnRow>, val observerFailed: Boolean) {
        val unavailable: Boolean get() = own == null

        companion object {
            /** The member's row cannot be had (§10.19.6 step 1): the observer's check cannot pass either. */
            val UNAVAILABLE = Examined(null, emptyList(), observerFailed = true)
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private suspend fun checkResultsLocked(
        key: String,
        connectionId: String,
        proposalId: String,
        gen: Int,
        trace: Trace,
    ): ResultsOutcome = run {
        // A lock (or any clear) during the check voids it: its hints are not written and nothing is submitted (R1-2), and
        // [clear] cancels it (L2).
        if (!site.configured) return@run ResultsOutcome.SiteUnreachable
        // `other_rows` and `rows_unavailable` only to a vault that lists `vote_rows` (§10.19.6 step 3, "Version gating").
        val rowsOk = voteRows()
        val detail = get(connectionId, proposalId)
        val tag = detail.recordTag?.let { runCatching { Base64s.decodeStd(it, TAG_LEN) }.getOrNull() }
            ?: return@run fail(key, ResultsOutcome.Problem, gen = gen)
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
        if (tooLarge) return@run fail(key, ResultsOutcome.Problem, tooLarge = true, gen = gen)
        if (doc == null) return@run unavailable(key, siteDown, gen)
        trace.resultsSha256 = Bytes.hex(Bytes.sha256(doc))
        // The signature and the bundle are always fetched (small): a fixed one beside the same results.json is a change (M4).
        val sig = fetch("results.json.sig", SIG_MAX)
        val bundleBytes = fetch("results.json.sigstore.json", BUNDLE_MAX)
        trace.sigSha256 = sig?.let { Bytes.hex(Bytes.sha256(it)) }
        trace.bundleSha256 = bundleBytes?.let { Bytes.hex(Bytes.sha256(it)) }
        // A `gave_up` check run again (0.84.1 §10.19.6): with the same results.json, signature and bundle as the last
        // attempt, which had rows.json, was answered and did not end on the vault's side, rows.json is not downloaded again
        // and nothing is sent.
        if (votesFlow.value.firstOrNull { it.key == key }?.check?.state == CheckState.GAVE_UP) {
            if (attemptOf(key)?.skipsRows(trace.resultsSha256, trace.sigSha256, trace.bundleSha256) == true) {
                trace.rowsFetched = true
                trace.answered = true
                trace.sentRow = true
                return@run ResultsOutcome.Unchanged
            }
        }
        if (tooLarge) return@run fail(key, ResultsOutcome.Problem, tooLarge = true, gen = gen)
        if (sig == null || bundleBytes == null) return@run unavailable(key, siteDown, gen)
        // (0.81.5) Results the vault would refuse as a whole are never submitted: the existing problem hint says so.
        if (!VoteSync.submittable(doc, sig)) return@run fail(key, ResultsOutcome.Problem, gen = gen)
        val bundle = VoteSync.jsonObject(bundleBytes, VoteSync.RESULTS_BUNDLE_DEPTH)
            ?: return@run fail(key, ResultsOutcome.Problem, gen = gen)
        // The download cap of rows.json follows counts.eligible (§10.19.6 step 1); results whose counts do not read are a
        // problem. An integer outside 1 to 100,000 cannot bound rows.json: with `vote_rows` the row cannot be had and the
        // vault judges the results themselves (a wrong counts.eligible is a contradiction, step 3).
        // Reading it parses results.json (up to 192 KiB), so it runs where the rest of the reading does (lead review L1).
        val (rowsCap, outOfRange) = withContext(compute) {
            computeStep("$key:cap")
            val cap = VoteRows.rowsCap(doc)
            cap to (cap == null && rowsOk && VoteRows.eligibleOutOfRange(doc))
        }
        if (rowsCap == null && !outOfRange) return@run fail(key, ResultsOutcome.Problem, gen = gen)
        val rows = rowsCap?.let { fetch("rows.json", it) }
        if (rows != null) trace.rowsDownloaded = true
        // rows.json over its cap stops the submit of a row (§10.19.6 step 1): with `vote_rows`, `rows_unavailable`.
        val rowsOver = tooLarge
        if (rowsOver && !rowsOk) return@run fail(key, ResultsOutcome.Problem, tooLarge = true, gen = gen)
        // A definite 404 for rows.json beside the signed results (the lead's call, 2026-11-06): with `vote_rows` the member's
        // row cannot be had. No answer, a timeout or a 5xx is tried again at the next attempt.
        val rowsMissing = rowsCap != null && rows == null && !rowsOver
        val rowsAbsent = rowsMissing && !siteDown
        if (rowsMissing && !(rowsAbsent && rowsOk)) return@run unavailable(key, siteDown, gen)
        // rows.json had (a 404 is fetched again at the next attempt: it may yet be published).
        trace.rowsFetched = !rowsAbsent
        // The heavy part (up to 51 MB: the hash, the reading, the trees, the proof, the signatures) in one block off the
        // main thread. The observer's verdict is only shown, never a reason not to submit (0.83.2).
        val examined = if (rows == null) {
            Examined.UNAVAILABLE
        } else {
            withContext(compute) {
                // Every 256 rows and tree nodes: a cancellation (leaving the screen ends its viewModelScope; a lock cancels the
                // sync job and every check in flight, Check again included) stops the work there. Whatever still returns after
                // a lock is voided by the generation check below.
                val ctx = coroutineContext
                fun step(stage: String) {
                    computeStep("$key:$stage")
                    ctx.ensureActive()
                }
                // What stops the submit of a row (§10.19.6 step 1): with `vote_rows` the row cannot be had and
                // `rows_unavailable` is sent; without it nothing is (0.83.2). Results that do not read are never sent.
                fun cannotHave(p: ResultsProblem): Examined? = if (rowsOk && p.reason != "results") Examined.UNAVAILABLE else null
                step("read")
                val published = try {
                    VoteRows.read(doc, rows, tag) { step("read") }
                } catch (p: ResultsProblem) {
                    return@withContext cannotHave(p)
                }
                step("row")
                val own = try {
                    published.ownRow(tag) { step("row") }
                } catch (p: ResultsProblem) {
                    return@withContext cannotHave(p)
                }
                // Another row under the member's tag (§10.19.6 step 1): the first four with their proofs, as `other_rows`.
                val others = if (!rowsOk) {
                    emptyList()
                } else {
                    try {
                        published.otherRows { step("row") }
                    } catch (p: ResultsProblem) {
                        return@withContext cannotHave(p)
                    }
                }
                step("observe")
                Examined(own, others, observerFailsInTime(published, proposalId, detail) { step("observe") })
            } ?: return@run fail(key, ResultsOutcome.Problem, gen = gen)
        }
        val ballot = examined.own?.ballot
        // (0.81.6) A choice id the vault would refuse (and with it the whole submission): with `vote_rows`, `rows_unavailable`;
        // without it the site's problem, not submitted.
        val chosen = if (ballot != null && !VoteSync.validChoiceId(ballot.choice)) {
            if (!rowsOk) return@run fail(key, ResultsOutcome.Problem, gen = gen)
            Examined.UNAVAILABLE
        } else {
            examined
        }
        var body = submitBody(connectionId, proposalId, doc, sig, bundle, chosen)
        var sent = chosen
        when (VoteSync.inline(VoteSync.encoded(body))) {
            VoteSync.Inline.FITS -> Unit
            // Over the request limit: the vault could never take it. Extra rows too large to send leave the row not had.
            VoteSync.Inline.TOO_LARGE -> {
                if (!rowsOk || sent.unavailable) return@run fail(key, ResultsOutcome.Problem, tooLarge = true, gen = gen)
                // Fewer extra rows first, the largest dropped first, down to one (review 1, L3): one verifying extra row is
                // enough for the vault's `row_duplicate`, a signed contradiction stronger than `rows_unavailable`.
                val fewer = fewerOthers(sent) { submitBody(connectionId, proposalId, doc, sig, bundle, it) }
                sent = fewer ?: Examined.UNAVAILABLE
                body = submitBody(connectionId, proposalId, doc, sig, bundle, sent)
                if (VoteSync.inline(VoteSync.encoded(body)) != VoteSync.Inline.FITS) {
                    return@run fail(key, ResultsOutcome.Problem, tooLarge = true, gen = gen)
                }
            }
            // Any other failure is a problem, not a size.
            VoteSync.Inline.OTHER -> return@run fail(key, ResultsOutcome.Problem, gen = gen)
        }
        if (synchronized(lock) { gen != generation }) return@run ResultsOutcome.Voided
        // Set before the submit: a refused or lost submission keeps it (ANDROID-PLAN 0.1.117).
        hint(key, gen) { it.copy(observerFailed = sent.observerFailed) }
        trace.sentRow = !sent.unavailable
        val answer = vaultGuard { ops().submit(body) }
        val check = answer["check"] as? JsonObject
        val state = CheckState.of(check?.let { VaultJson.str(it, "state") })
        val reason = check?.let { c -> VaultJson.str(c, "reason") }
        trace.vaultSide = reason in VAULT_SIDE
        // The vault alone judges; a submission it leaves pending (reason input) is the problem the screens say.
        val input = state == CheckState.PENDING && reason == "input"
        val verdict = if (sent.observerFailed) ObserverVerdict.FAILED else ObserverVerdict.PASSED
        hint(key, gen) { it.copy(gaveUp = null, resultsProblem = input, resultsTooLarge = false, verdict = verdict, notRechecked = false) }
        refreshQuietly()
        ResultsOutcome.Submitted(state)
    }

    /**
     * [rows] with its extra rows dropped one by one, the largest first, until [body] of it fits the request; at least one
     * extra row is kept (review 1, L3). Null when none fits (or there were none).
     */
    private fun fewerOthers(rows: Examined, body: (Examined) -> JsonObject): Examined? {
        var kept = rows.others
        while (kept.size > 1) {
            val largest = kept.maxBy { rowJson(it).toString().length }
            kept = kept.filterNot { it === largest }
            val fewer = Examined(rows.own, kept, rows.observerFailed)
            if (VoteSync.inline(VoteSync.encoded(body(fewer))) == VoteSync.Inline.FITS) return fewer
        }
        return null
    }

    /**
     * The body of `vote.results.submit` (VAULT-MESSAGING 0.86.4 §10.19.8): the results as served, and either the member's
     * row with its proof (and `other_rows` when there are more under its tag), or `rows_unavailable: true` and no row.
     * Every ballot carries `pad` (§10.19.5).
     */
    private fun submitBody(
        connectionId: String,
        proposalId: String,
        doc: ByteArray,
        sig: ByteArray,
        bundle: JsonObject,
        rows: Examined,
    ): JsonObject = buildJsonObject {
        put("connection_id", connectionId)
        put("proposal_id", proposalId)
        put("results", Base64s.encodeStd(doc))
        put("results_sig", Base64s.encodeStd(sig))
        put("bundle", bundle)
        val own = rows.own
        if (own == null) {
            put("rows_unavailable", true)
            return@buildJsonObject
        }
        put("row", rowJson(own))
        put("row_proof", proofJson(own))
        if (rows.others.isNotEmpty()) {
            put(
                "other_rows",
                JsonArray(
                    rows.others.map { r ->
                        buildJsonObject {
                            put("row", rowJson(r))
                            put("proof", proofJson(r))
                        }
                    },
                ),
            )
        }
    }

    private fun rowJson(r: OwnRow): JsonObject = buildJsonObject {
        val ballot = r.ballot
        if (ballot == null) {
            put("ballot", JsonNull)
        } else {
            put(
                "ballot",
                buildJsonObject {
                    // Canonical base64 decodes and encodes back to the same text: these are the site's bytes.
                    put("vk", Base64s.encodeStd(ballot.vk))
                    put("choice_id", ballot.choice)
                    put("ballot_sig", Base64s.encodeStd(ballot.ballotSig))
                    put("pad", VotePad.of(ballot.choiceId))
                },
            )
        }
    }

    private fun proofJson(r: OwnRow): JsonObject = buildJsonObject {
        put("index", r.index)
        put("tree_size", r.treeSize)
        put("path", JsonArray(r.path.map { JsonPrimitive(Base64s.encodeStd(it)) }))
    }

    // ---- The kept attempts (no choice, row or proof) ----

    /** The kept attempts as last read or written; [attemptsSync] guards it, [storeEpoch] and every read and write of [attempts]. */
    private var attemptsHeld: List<ResultsAttempt>? = null
    private val attemptsSync = Any()

    /**
     * Bumped by [forgetStored] (a wipe, a sign-out, a deletion: review 1, M1). A write taken under an older epoch is dropped,
     * so nothing of a wiped vault comes back to disk or into memory. A lock does not bump it: the kept attempts outlive a
     * lock and a restart on purpose (the daily limit, review 1 L1).
     */
    private var storeEpoch = 0

    /** The [storeEpoch] now, taken where an attempt or a list read starts. */
    private fun storeEpochNow(): Int = synchronized(attemptsSync) { storeEpoch }

    /**
     * Reads the kept attempts and, with [block]'s new list, writes them; with [epoch], only while no wipe happened since it
     * was taken (the test and the write are one step under [attemptsSync], so a write racing [forgetStored] is dropped).
     */
    private suspend fun <T> withAttempts(epoch: Int? = null, block: (List<ResultsAttempt>) -> Pair<List<ResultsAttempt>?, T>): T =
        withContext(io) {
            synchronized(attemptsSync) {
                val held = attemptsHeld ?: runCatching { attempts.load() }.getOrDefault(emptyList()).also { attemptsHeld = it }
                val (next, out) = block(held)
                val current = epoch == null || epoch == storeEpoch
                if (next != null && next != held && current) {
                    attemptsHeld = next
                    // A store that cannot be written keeps the memory copy: the daily limit then holds until a restart.
                    runCatching { attempts.save(next) }
                }
                out
            }
        }

    /**
     * The vault's data goes from this phone (a wipe, a sign-out, a deletion; review 1, M1): the kept attempts are forgotten
     * in memory and on disk, and a write still on its way is dropped. Called after [clear] on those paths, before the
     * files are erased. Not at a lock.
     */
    fun forgetStored() {
        synchronized(attemptsSync) {
            storeEpoch++
            attemptsHeld = emptyList()
            runCatching { attempts.save(emptyList()) }
        }
    }

    private suspend fun attemptOf(key: String): ResultsAttempt? = withAttempts { list -> null to list.firstOrNull { it.key == key } }

    /** A `gave_up` check is run again at most once a day per record (§10.19.6); a clock set back does not hold it up. */
    private suspend fun dailyDue(key: String, at: Instant): Boolean {
        val last = attemptOf(key) ?: return true
        val since = at.toEpochMilli() - last.atMs
        return since < 0 || since >= RECHECK_EVERY.toMillis()
    }

    private suspend fun recordAttempt(key: String, epoch: Int, trace: Trace, out: ResultsOutcome?, due: Boolean) {
        val final = out is ResultsOutcome.Submitted && finalCheck(out.state)
        // A Check again that ended Unchanged (nothing sent) keeps the last attempt's time, so it does not defer the daily
        // re-check (#314 item 3); the daily re-check's own Unchanged is its attempt of the day.
        val keepTime = !due && out == ResultsOutcome.Unchanged
        val at = now().toEpochMilli()
        // An attempt that ended in a failure (no answer from the vault) counts as not having had the rows.
        val entry = ResultsAttempt(
            key, at, trace.resultsSha256, trace.rowsFetched && out != null, trace.vaultSide,
            answered = out is ResultsOutcome.Submitted || trace.answered && out == ResultsOutcome.Unchanged,
            sentRow = trace.sentRow && (out is ResultsOutcome.Submitted || out == ResultsOutcome.Unchanged),
            sigSha256 = trace.sigSha256,
            bundleSha256 = trace.bundleSha256,
        )
        withAttempts(epoch) { list ->
            val kept = if (keepTime) list.firstOrNull { it.key == key }?.let { entry.copy(atMs = it.atMs) } ?: entry else entry
            (list.filterNot { it.key == key } + listOfNotNull(kept.takeUnless { final })) to Unit
        }
    }

    private suspend fun forgetAttempts(epoch: Int, gone: (ResultsAttempt) -> Boolean) {
        withAttempts(epoch) { list -> list.filterNot(gone) to Unit }
    }

    private fun finalCheck(state: CheckState?): Boolean =
        state == CheckState.VERIFIED || state == CheckState.UNCONFIRMED || state == CheckState.FAILED

    override suspend fun recheck(connectionId: String, proposalId: String): ResultsOutcome {
        val key = VoteKeys.of(connectionId, proposalId)
        return resultLocks.computeIfAbsent(key) { Mutex() }.withLock {
            // Queued behind a check that has since set a verdict (review 1, L4): nothing to download again.
            hintsFlow.value[key]?.verdict?.let { return@withLock ResultsOutcome.Rechecked(it == ObserverVerdict.PASSED) }
            // A child job as a results check is (lead review L2): a lock cancels it and it is void.
            val gen = synchronized(lock) { generation }
            asCheck(gen) { recheckLocked(key, connectionId, proposalId, gen) }
        }
    }

    /** The observer's check alone, on the published results as they are now; nothing is submitted or kept on disk. */
    @Suppress("ReturnCount")
    private suspend fun recheckLocked(key: String, connectionId: String, proposalId: String, gen: Int): ResultsOutcome = run {
        if (!site.configured) return@run notRechecked(key, gen)
        val detail = get(connectionId, proposalId)
        // Nothing checked (no tag to look for, results.json over the limit, a cap that does not read): not a failed observer
        // check but one that could not run, so `vote_results_not_rechecked` and no number, not "don't add up" (#314 item 2).
        val tag = detail.recordTag?.let { runCatching { Base64s.decodeStd(it, TAG_LEN) }.getOrNull() }
            ?: return@run notRechecked(key, gen)
        val doc = when (val a = site.get("p/$proposalId/results.json", RESULTS_MAX)) {
            is SiteAnswer.Found -> a.bytes
            SiteAnswer.TooLarge, SiteAnswer.Absent, SiteAnswer.Unreachable -> return@run notRechecked(key, gen)
        }
        val cap = withContext(compute) {
            computeStep("$key:cap")
            VoteRows.rowsCap(doc)
        } ?: return@run notRechecked(key, gen)
        val rows = when (val a = site.get("p/$proposalId/rows.json", cap)) {
            is SiteAnswer.Found -> a.bytes
            SiteAnswer.TooLarge -> return@run rechecked(key, passed = false, gen)
            SiteAnswer.Absent, SiteAnswer.Unreachable -> return@run notRechecked(key, gen)
        }
        val passed = withContext(compute) {
            val ctx = coroutineContext
            fun step(stage: String) {
                computeStep("$key:$stage")
                ctx.ensureActive()
            }
            try {
                val published = VoteRows.read(doc, rows, tag) { step("read") }
                published.ownRow(tag) { step("row") }
                step("observe")
                !observerFailsInTime(published, proposalId, detail) { step("observe") }
            } catch (_: ResultsProblem) {
                false
            }
        }
        rechecked(key, passed, gen)
    }

    private fun rechecked(key: String, passed: Boolean, gen: Int): ResultsOutcome {
        if (synchronized(lock) { gen != generation }) return ResultsOutcome.Voided
        val verdict = if (passed) ObserverVerdict.PASSED else ObserverVerdict.FAILED
        hint(key, gen) { it.copy(verdict = verdict, observerFailed = !passed, notRechecked = false) }
        return ResultsOutcome.Rechecked(passed)
    }

    private fun notRechecked(key: String, gen: Int): ResultsOutcome {
        hint(key, gen) { it.copy(notRechecked = true) }
        return ResultsOutcome.SiteUnreachable
    }

    /**
     * Whether the observer's check fails ([com.vettid.core.crypto.vote.PublishedResults.observe]), counting also what is only
     * reported: the documents naming another proposal or rows.json not exactly `{v, proposal_id, rows}`, and a `tally` or
     * `counts` other than the vault's own (`vote.get`, when it holds them).
     */
    private fun observerFails(published: PublishedResults, proposalId: String, detail: VoteDetail, checkpoint: () -> Unit): Boolean = try {
        published.observe(proposalId, checkpoint)
        val tallyDiffers = detail.tally != null && detail.tally != published.tally()
        val c = published.counts()
        val countsDiffer = detail.counts != null && detail.counts != VoteCounts(c.eligible, c.ballots, c.removed, c.unhandled)
        tallyDiffers || countsDiffer
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
        // Any failure of the observer's own work (a ResultsProblem, or anything thrown under it) is an observer failure,
        // never a reason not to submit (review 1, finding 7).
        true
    }


    /**
     * [observerFails] time-boxed (lead review M1): a check that runs out of time is an observer failure like any other and the
     * row is still submitted. Only the observer's own scope is cancelled at the limit; a cancellation of the check itself
     * passes through withTimeoutOrNull as before. The box scales with the rows (Opus recheck 3, M1-a): a fixed 60 s could time
     * out an honest 100,000-row vote on a slow phone and show "don't add up". counts.eligible also capped rows.json (rowsCap).
     */
    private suspend fun observerFailsInTime(
        published: PublishedResults,
        proposalId: String,
        detail: VoteDetail,
        checkpoint: () -> Unit,
    ): Boolean {
        val eligible = try {
            published.counts().eligible
        } catch (_: ResultsProblem) {
            0L
        }
        return withTimeoutOrNull(observeLimitFor(eligible, observeLimit).toMillis()) {
            val inner = coroutineContext
            observerFails(published, proposalId, detail) {
                checkpoint()
                inner.ensureActive()
            }
        } ?: true
    }

    /** The site could not be reached, or had no results (yet): which of the two is what a `gave_up` check will say. */
    private fun unavailable(key: String, siteDown: Boolean, gen: Int? = null): ResultsOutcome =
        if (siteDown) {
            fail(key, ResultsOutcome.SiteUnreachable, GaveUpWhy.SITE, gen = gen)
        } else {
            fail(key, ResultsOutcome.NotPublished, GaveUpWhy.UNPUBLISHED, gen = gen)
        }

    private fun fail(
        key: String,
        outcome: ResultsOutcome,
        why: GaveUpWhy? = null,
        tooLarge: Boolean = false,
        gen: Int? = null,
    ): ResultsOutcome {
        hint(key, gen) {
            // An attempt that submits nothing clears the observer's flag (ANDROID-PLAN 0.1.117).
            it.copy(
                gaveUp = why ?: it.gaveUp,
                resultsProblem = outcome == ResultsOutcome.Problem,
                resultsTooLarge = tooLarge,
                observerFailed = false,
            )
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

    internal companion object {
        val SYNC_EVERY: Duration = Duration.ofHours(1)
        /** The observer's check per attempt, at the least (lead review M1). */
        val OBSERVE_LIMIT: Duration = Duration.ofSeconds(60)

        /** The observer's time per row of `counts.eligible` (Opus recheck 3, M1-a): 300 s at 100,000 rows. */
        val OBSERVE_PER_ROW: Duration = Duration.ofMillis(3)

        /**
         * The observer's time box for a vote of [eligible] rows: `max(floor, eligible x 3 ms)`, with [eligible] taken as at
         * most [VoteRows.MAX_ROWS] (the rows read never exceed it).
         */
        fun observeLimitFor(eligible: Long, floor: Duration = OBSERVE_LIMIT): Duration {
            val scaled = OBSERVE_PER_ROW.multipliedBy(eligible.coerceIn(0L, VoteRows.MAX_ROWS.toLong()))
            return if (scaled > floor) scaled else floor
        }
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
        /** The vault's `MaxResultsDoc`; rows.json is capped by `counts.eligible` ([VoteRows.rowsCap]). */
        const val RESULTS_MAX = 196_608L
        const val INDEX_MAX = 8L shl 20
        const val UPLOAD_TRIES = 2
        const val UPLOAD_WAIT_MS = 5_000L

        /** A `gave_up` check runs again at most once a day per record (VAULT-MESSAGING 0.86.4 §10.19.6). */
        val RECHECK_EVERY: Duration = Duration.ofDays(1)

        /** The reasons of a check left on the vault's side: rows.json is fetched again after them (§10.19.6). */
        val VAULT_SIDE = setOf("trust_root", "service_list", "clock")
    }
}
