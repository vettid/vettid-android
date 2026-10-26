package com.vettid.core.data.vault

import com.vettid.core.altchan.UnlockIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration

/** (Release L, owner decision 2026-10-24) "Lock it now": which opening the fresh lock is bound to. */
internal object LockAgain {
    /**
     * "Lock it now" after the lock [mine] was begun unbound: the mark is taken here, before anything is read, then (in
     * [scope]) the status is read for at most [wait], the lock is bound to what it names unless an unlock succeeded
     * since the mark, and [then] makes the lock's attempts.
     */
    fun start(
        scope: CoroutineScope,
        keeper: LockKeeper,
        mine: Long,
        wait: Duration,
        read: suspend () -> String?,
        then: suspend () -> Unit,
    ): Job {
        val mark = keeper.openingMark()
        return scope.launch {
            val id = withTimeoutOrNull(wait.toMillis()) { unlockId(read) }
            keeper.rebind(mine, id, mark)
            then()
        }
    }

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
