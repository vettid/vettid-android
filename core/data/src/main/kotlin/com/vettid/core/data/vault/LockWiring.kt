package com.vettid.core.data.vault

import kotlinx.coroutines.CoroutineScope

/** How the manager's lock keeper is put together, in one place so that the wiring can be tested. */
internal object LockWiring {
    /**
     * The keeper for [attempt]. (Release L) When a lock ends as "opened again" the opening it was bound to is dead:
     * [forgetUnlockId] makes the phone forget that name, so that a later plain lock names none (unconditional).
     */
    fun keeper(
        scope: CoroutineScope,
        store: LockRecordStore,
        attempt: LockAttempt,
        onSaveFailed: (Exception) -> Unit,
        forgetUnlockId: (dead: String?) -> Unit,
    ): LockKeeper = LockKeeper(scope, store = store, attempt = attempt, onSaveFailed = onSaveFailed, onOpenedAgain = forgetUnlockId)
}
