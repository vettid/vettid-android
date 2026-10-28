package com.vettid.core.data.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * (ANDROID-PLAN 0.1.93) The open of an unlock that waits: the vault is open, but the daily check that follows the unlock did
 * not pass on the password, so the app stays closed until another try passes. Holds what the open needs ([T]) with the
 * generation of the unlock; a lock or a wipe since (a newer generation), or any other unlock, ends the wait.
 */
internal class OpenHold<T> {
    class Held<T>(val item: T, val generation: Int)

    private var held: Held<T>? = null
    private val flow = MutableStateFlow(false)

    /** Whether an open waits. */
    val holding: StateFlow<Boolean> = flow.asStateFlow()

    @Synchronized
    fun hold(item: T, generation: Int) {
        held = Held(item, generation)
        flow.value = true
    }

    @Synchronized
    fun clear() {
        held = null
        flow.value = false
    }

    /** The waiting open if it is still for [generation]; otherwise none, and the wait ends. */
    @Synchronized
    fun claim(generation: Int): Held<T>? {
        val h = held
        if (h == null || h.generation != generation) {
            clear()
            return null
        }
        return h
    }
}
