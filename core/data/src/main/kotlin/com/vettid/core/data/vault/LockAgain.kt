package com.vettid.core.data.vault

import com.vettid.core.altchan.UnlockIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/** (Release L, owner decision 2026-10-24) "Lock it now": which opening the fresh lock is bound to. */
internal object LockAgain {
    /**
     * The `unlock_id` the vault's status names now ([read]); null when it names none, one not of the form, or cannot be
     * read: the lock is then unconditional (VAULT-LOCK Q3), never bound to a value that may be stale.
     */
    @Suppress("TooGenericExceptionCaught") // whatever stopped the read, the lock goes out unconditional
    suspend fun unlockId(read: suspend () -> String?): String? = try {
        UnlockIds.of(read())
    } catch (_: TimeoutCancellationException) {
        null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
