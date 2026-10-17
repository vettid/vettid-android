package com.vettid.core.data.vault

/**
 * The account record ([AccountRecord]'s), written only through updates applied under its lock, against a wipe or a
 * reset on another thread:
 *
 * - **While a wipe runs** ([beginWipe] to [endWipe]) no write reaches the record: one that lands between the erase and
 *   the fresh record in memory would put the erased account back on the phone.
 * - **After a wipe or a [reset]**, a write meant for the record before it ([update]'s `since`, a [recordGeneration]
 *   read before the operation it belongs to) is dropped: it would land in the fresh record. `since` is required; a
 *   write that belongs to whatever record is current passes [UNBOUND], and says why.
 * - An update is a function of the record as it is under the lock, never a copy made before: a copy of the old record
 *   that straddles a wipe cannot be written back.
 * - **While the record could not be read** ([readable] false) nothing is written: a write would replace the record that
 *   names this phone's vault with an empty one. A [reload] that reads it, a [reset] or a wipe ends that.
 *
 * [generation] is bumped by every lock ([bumpGeneration]), every reset and every wipe: a phase or an offer computed
 * before it is not published after it. Both counters move at the start and at the end of a wipe, so a value read
 * during one never matches again after it.
 *
 * A dropped write is only lost: nothing that says the vault is open depends on it.
 */
internal class RecordStore<T : Any>(
    initial: T,
    readable: Boolean,
    private val empty: () -> T,
    private val persist: (T) -> Unit,
) {
    private val lock = Any()

    @Volatile
    var value: T = initial
        private set

    /** Bumped by every lock, every reset and every wipe. */
    @Volatile
    var generation: Int = 0
        private set

    /** Bumped by every wipe and every [reset]: the record a write was meant for. */
    @Volatile
    var recordGeneration: Int = 0
        private set

    /** The record on the phone was read (or replaced); false: it could not be, and nothing is written over it. */
    @Volatile
    var readable: Boolean = readable
        private set

    /** Read and written under [lock] only. */
    private var wiping = false

    fun bumpGeneration() {
        synchronized(lock) { generation++ }
    }

    /**
     * Writes [f] of the record as it is now, under the lock; [f] returns null for nothing to write. Dropped while a wipe
     * runs, while the record is not [readable], and when [since] (a [recordGeneration], or [UNBOUND]) or [generation]
     * (a [RecordStore.generation]) was read before a change of it. True when written.
     */
    fun update(since: Int, generation: Int? = null, f: (T) -> T?): Boolean = synchronized(lock) {
        val stale = (since != UNBOUND && since != recordGeneration) || (generation != null && generation != this.generation)
        if (wiping || !readable || stale) return false
        val next = f(value) ?: return false
        write(next)
        true
    }

    /** A record of its own ([f] of the one now): the writes still on their way for the one before are dropped. */
    fun reset(f: (T) -> T): Boolean = synchronized(lock) {
        if (wiping) return false
        generation++
        recordGeneration++
        readable = true
        write(f(value))
        true
    }

    /** The record read again from the phone; [readable]: it could be. Nothing is written. */
    fun reload(a: T, readable: Boolean) {
        synchronized(lock) {
            if (wiping) return
            value = a
            this.readable = readable
        }
    }

    /** The wipe starts: from here no write reaches the record until [endWipe]. */
    fun beginWipe() {
        synchronized(lock) {
            generation++
            recordGeneration++
            wiping = true
        }
    }

    /** The fresh record in memory (the phone's is erased), both counters moved, [also] and the end of the wipe: one step. */
    fun endWipe(also: () -> Unit = {}) {
        synchronized(lock) {
            generation++
            recordGeneration++
            value = empty()
            readable = true
            wiping = false
            also()
        }
    }

    private fun write(a: T) {
        value = a
        persist(a)
    }

    companion object {
        /** [update]'s `since` for a write that belongs to whatever record is current; every use says why. */
        const val UNBOUND = -1
    }
}
