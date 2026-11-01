package com.vettid.core.data.wipe

/**
 * A step of [LocalWipe.resetMemory] that a module outside `:core:data` adds (a Hilt `@IntoSet` binding; the set may be empty): its
 * in-memory state forgotten, or a generation moved so that a write still in flight cannot bring an erased file back. It runs before
 * anything is erased from disk. A hook that throws does not stop the wipe.
 */
fun interface WipeHook {
    suspend fun run()
}
