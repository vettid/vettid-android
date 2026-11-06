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
) {
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
