package com.vettid.core.data.vault

/**
 * The account record ([VaultManager]'s), written only through updates applied under its lock, against a wipe or a
 * reset on another thread:
 *
 * - **While a wipe runs** ([beginWipe] to [endWipe]) no write reaches the record: one that lands between the erase and
 *   the fresh record in memory would put the erased account back on the phone.
 * - **After a wipe or a [reset]**, a write meant for the record before it ([update] with `since`, a [recordGeneration]
 *   read earlier) is dropped: it would land in the fresh record.
 * - An update is a function of the record as it is under the lock, never a copy made before: a copy of the old record
 *   that straddles a wipe cannot be written back.
 *
 * [generation] is bumped by every lock ([bumpGeneration]) and every wipe: a phase or an offer computed before it is not
 * published after it. Both counters move at the start and at the end of a wipe, so a value read during one never
 * matches again after it.
 *
 * A dropped write is only lost: nothing that says the vault is open depends on it.
 */
internal class RecordStore<T : Any>(initial: T, private val empty: () -> T, private val persist: (T) -> Unit) {
    private val lock = Any()

    @Volatile
    var value: T = initial
        private set

    /** Bumped by every lock and every wipe. */
    @Volatile
    var generation: Int = 0
        private set

    /** Bumped by every wipe and every [reset]: the record a write was meant for. */
    @Volatile
    var recordGeneration: Int = 0
        private set

    /** Read and written under [lock] only. */
    private var wiping = false

    fun bumpGeneration() {
        synchronized(lock) { generation++ }
    }

    /**
     * Writes [f] of the record as it is now, under the lock; [f] returns null for nothing to write. Dropped while a wipe
     * runs, and when [since] (a [recordGeneration]) or [generation] (a [RecordStore.generation]) was read before a change
     * of it. True when written.
     */
    fun update(since: Int? = null, generation: Int? = null, f: (T) -> T?): Boolean = synchronized(lock) {
        val stale = (since != null && since != recordGeneration) || (generation != null && generation != this.generation)
        if (wiping || stale) return false
        val next = f(value) ?: return false
        write(next)
        true
    }

    /** A record of its own ([f] of the one now): the writes still on their way for the one before are dropped. */
    fun reset(f: (T) -> T): Boolean = synchronized(lock) {
        if (wiping) return false
        recordGeneration++
        write(f(value))
        true
    }

    /** The record read again from the phone (it could not be read before); nothing is written. */
    fun reload(a: T) {
        synchronized(lock) { if (!wiping) value = a }
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
            wiping = false
            also()
        }
    }

    private fun write(a: T) {
        value = a
        persist(a)
    }
}
