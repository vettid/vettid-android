package com.vettid.core.data.votes

import com.vettid.core.data.KeystoreFileStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What the app keeps of its last attempt at one vote's results (VAULT-MESSAGING 0.86.4 §10.19.6, "A `gave_up` check is not
 * final"; ANDROID-PLAN 0.1.121 "A `gave_up` check run again"): enough to run a `gave_up` check again at most once a day per
 * record, across restarts, and not to download an unchanged `rows.json` again. **Never a choice, a row or a proof**: only
 * the time, the SHA-256 of the public `results.json` the attempt read, and three facts. A re-check that skips the
 * download sends nothing (the spec's "or sends nothing": the same results, row and proof get the same answer), so no row
 * needs to be kept. Forgotten when the check is final or the vote leaves the vault's list, and with the app's data at a wipe.
 */
@Serializable
data class ResultsAttempt(
    /** [VoteKeys.of] the pair. */
    val key: String,
    /** When the attempt ran (the phone's clock, epoch milliseconds). */
    val atMs: Long,
    /** Hex SHA-256 of the `results.json` it read; null when it was absent or could not be fetched. */
    val resultsSha256: String? = null,
    /** `rows.json` was had on that attempt (read, or found over its cap): false when it was absent or could not be fetched. */
    val rowsFetched: Boolean = false,
    /** The vault left the check for a reason of its own side (`trust_root`, `service_list`, `clock`). */
    val vaultSide: Boolean = false,
    /**
     * The attempt was submitted and the vault answered it (review 1, M2). Only then does "the same results get the same
     * answer" hold: an attempt that sent nothing (no `vote_rows` then, a row the app could not use) is never skipped.
     */
    val answered: Boolean = false,
    /**
     * That submission carried the member's real row, not `rows_unavailable` (the lead's review of #312, M2): a rows.json
     * that was broken (over its cap, a row that is not a leaf, no row for the member, not reaching `rows_root`) may be
     * corrected under the same results.json, so after such an attempt rows.json is always fetched again.
     */
    val sentRow: Boolean = false,
    /**
     * Hex SHA-256 of the `results.json.sig` and `results.json.sigstore.json` it read (review 1, M4: public, small); null when
     * absent or not fetched. A fixed bundle or signature beside the same results.json is a change: fetched and sent again.
     */
    val sigSha256: String? = null,
    val bundleSha256: String? = null,
    /**
     * When this record's `gave_up` check was first run again (epoch ms; #314 item 1, owner decision a, thread
     * 01M6RS33MQVX7KZMSJE0VBFYBR): for 7 days from it rows.json is downloaded by the daily rule on any network; after them
     * on an unmetered one, and at most weekly on a metered one. Null before the first re-check.
     */
    val firstRecheckMs: Long? = null,
    /** When rows.json was last downloaded for this record (epoch ms; the weekly cap on a metered network); null for never. */
    val lastRowsMs: Long? = null,
) {
    /**
     * Whether a `gave_up` re-check that read results.json, its signature and its bundle of these hashes skips rows.json and
     * sends nothing (0.84.1 §10.19.6; review 1, M4): the same three as this attempt, which had rows.json, was answered by the
     * vault and did not end on the vault's side.
     */
    fun skipsRows(results: String?, sig: String?, bundle: String?): Boolean =
        answered && sentRow && rowsFetched && !vaultSide && results != null && sig != null && bundle != null &&
            results == resultsSha256 && sig == sigSha256 && bundle == bundleSha256

    override fun toString() = "ResultsAttempt"
}

/** Where [ResultsAttempt]s are kept. */
interface ResultsAttemptStore {
    fun load(): List<ResultsAttempt>

    fun save(attempts: List<ResultsAttempt>)
}

/** In memory only (tests, and builds without a file). */
class MemoryResultsAttemptStore : ResultsAttemptStore {
    @Volatile
    private var held: List<ResultsAttempt> = emptyList()

    override fun load(): List<ResultsAttempt> = held

    override fun save(attempts: List<ResultsAttempt>) {
        held = attempts
    }
}

/** In a Keystore-encrypted file in the no-backup directory (erased with it at a wipe); an unreadable file reads as empty. */
class KeystoreResultsAttemptStore(private val file: KeystoreFileStore) : ResultsAttemptStore {
    override fun load(): List<ResultsAttempt> = try {
        file.load()?.let { json.decodeFromString(ListSerializer(ResultsAttempt.serializer()), it.decodeToString()) }.orEmpty()
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
        emptyList()
    }

    override fun save(attempts: List<ResultsAttempt>) {
        if (attempts.isEmpty()) {
            file.clear()
        } else {
            file.save(json.encodeToString(ListSerializer(ResultsAttempt.serializer()), attempts).encodeToByteArray())
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * When the recheck on open last downloaded rows.json for a vote (#319 item 2; the lead's review of #321, M1): a key and a time,
 * no choice and no verdict. Its own file, since the attempts of a final check are dropped and the recheck is for verified and
 * unconfirmed votes.
 */
@Serializable
data class RecheckStamp(val key: String, val atMs: Long) {
    override fun toString() = "RecheckStamp"
}

/** Where [RecheckStamp]s are kept. */
interface RecheckStampStore {
    fun load(): List<RecheckStamp>

    fun save(stamps: List<RecheckStamp>)
}

/** In memory only (tests, and builds without a file). */
class MemoryRecheckStampStore : RecheckStampStore {
    @Volatile
    private var held: List<RecheckStamp> = emptyList()

    override fun load(): List<RecheckStamp> = held

    override fun save(stamps: List<RecheckStamp>) {
        held = stamps
    }
}

/** In a Keystore-encrypted file in the no-backup directory (erased with it at a wipe); an unreadable file reads as empty. */
class KeystoreRecheckStampStore(private val file: KeystoreFileStore) : RecheckStampStore {
    override fun load(): List<RecheckStamp> = try {
        file.load()?.let { json.decodeFromString(ListSerializer(RecheckStamp.serializer()), it.decodeToString()) }.orEmpty()
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
        emptyList()
    }

    override fun save(stamps: List<RecheckStamp>) {
        if (stamps.isEmpty()) {
            file.clear()
        } else {
            file.save(json.encodeToString(ListSerializer(RecheckStamp.serializer()), stamps).encodeToByteArray())
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
