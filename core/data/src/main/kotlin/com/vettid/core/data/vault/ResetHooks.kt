package com.vettid.core.data.vault

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
