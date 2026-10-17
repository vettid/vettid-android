package com.vettid.core.data.vault

/**
 * The account record's writes against a wipe ([VaultManager]'s, on another thread). Two guards, both under [lock], the
 * account record's lock:
 *
 * - **While a wipe runs** ([beginWipe] to [endWipe]) no write reaches the record: one that lands between the erase and
 *   the fresh record in memory would put the erased account back on the phone.
 * - **After a wipe**, a write computed for the record before it ([write] with `since`) is dropped: it would land in the
 *   fresh record (or bring the old one back, when it was a copy made before the wipe).
 *
 * A dropped write is only lost: nothing that says the vault is open depends on it.
 */
internal class RecordGuard(private val lock: Any) {
    /** Bumped by every wipe; read before an operation whose writes belong to the record it started with. */
    @Volatile
    var generation: Int = 0
        private set

    /** Read and written under [lock] only. */
    private var wipingNow = false

    /** True between [beginWipe] and [endWipe]. */
    val wiping: Boolean get() = synchronized(lock) { wipingNow }

    /** The wipe starts: from here no write reaches the record until [endWipe]. [also] runs in the same step. */
    fun beginWipe(also: () -> Unit = {}) = synchronized(lock) {
        generation++
        wipingNow = true
        also()
    }

    /**
     * [reset] (the fresh record in memory, and what shows it) and the end of the wipe, as one step. A wipe that ends
     * early (an exception) leaves the guard shut: nothing is written until the next start finishes the wipe.
     */
    fun endWipe(reset: () -> Unit) = synchronized(lock) {
        reset()
        wipingNow = false
    }

    /**
     * Runs [write] under [lock] unless a wipe is under way or, with [since] (a [generation] read earlier), one happened
     * since; null when dropped.
     */
    fun <T> write(since: Int? = null, write: () -> T): T? = synchronized(lock) {
        if (wipingNow || (since != null && since != generation)) null else write()
    }
}
