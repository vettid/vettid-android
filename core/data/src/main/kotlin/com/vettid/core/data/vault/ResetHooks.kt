package com.vettid.core.data.vault

import kotlinx.coroutines.launch

/**
 * The hooks of a confirmed `credential.reset` ([VaultManager.onCredentialReset]): each [prepare][hooks] runs now, synchronously, and
 * returns the action to run afterwards. A prepare that throws is dropped and the others are kept: a hook must never keep the
 * reset from being confirmed (the marker cleared, the gate opened), which would leave the resolver checking for ever.
 */
@Suppress("TooGenericExceptionCaught")
internal fun prepareResetHooks(hooks: List<() -> (suspend () -> Unit)>): List<suspend () -> Unit> = hooks.mapNotNull { prepare ->
    try {
        prepare()
    } catch (_: Exception) {
        null
    }
}

/** What a confirmed reset does besides writing the record ([ResetConfirmation]). */
internal interface ResetEffects {
    /** The unlock window is closed. */
    fun closeWindow()

    /** The held lists of items (the critical ones are destroyed) are dropped. */
    fun clearItems()

    /** After the hooks: the alarm state read afresh, the owner-check clock restarted, the lists read again; needs a session. */
    suspend fun afterwards()
}

/**
 * Confirms a `credential.reset` (its answer, a resend or the check may each find it applied): once per reset, the hooks are
 * prepared **before** the record is written (the marker cleared, the gate opened), a hook that throws never stops the
 * confirmation, and the effects run afterwards, the hooks' actions first (local state, no request, no session needed). The
 * reset's id counts as handled only once the record was written.
 */
internal class ResetConfirmation(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val hooks: List<() -> (suspend () -> Unit)>,
    private val effects: ResetEffects,
) {
    private val handled: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** Runs [writeRecord] (which clears the marker) and returns its result; the reset's effects follow, once. */
    fun confirm(m: UnresolvedChange, writeRecord: () -> Boolean): Boolean {
        val actions = if (m.kind == ChangeKind.RESET && m.innerId !in handled) prepareResetHooks(hooks) else null
        val ok = writeRecord()
        if (actions != null && handled.add(m.innerId)) {
            effects.closeWindow()
            effects.clearItems()
            scope.launch {
                // First, before any request: what the hooks forget is local and the gate is already open.
                for (action in actions) runCatching { action() }
                effects.afterwards()
            }
        }
        return ok
    }
}
