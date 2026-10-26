package com.vettid.core.data.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * (ANDROID-PLAN 0.1.57 (1), 0.1.58 (3)) The vault answered `credential_required` (with `{"reason": "no_holder"}` or
 * without a reason): it does not count this phone as its holder and refused before testing any PIN or password. The app
 * shows the full screen "This phone can't use your vault" from whatever screen sent the request, with **Try again** and
 * **Lock vault**. A vault answer, not a proof for an erase: nothing is erased, the keys and local state stay, the
 * session is kept. An unresolved change being decided keeps its own rules ([ChangeResolver] does not raise this).
 */
object CredentialRequired {
    /** One refusal: [retry] sends an idempotent read that failed again; null for every other request. */
    class Refusal(retry: (suspend () -> Unit)? = null) {
        internal val retry: (suspend () -> Unit)? = retry

        /** Whether **Try again** sends the request itself again (an idempotent read without secrets). */
        val resends: Boolean get() = retry != null
    }

    private val flow = MutableStateFlow<Refusal?>(null)

    /** The refusal the screen shows, or null. */
    val state: StateFlow<Refusal?> = flow.asStateFlow()

    internal fun raise(retry: (suspend () -> Unit)?) {
        flow.value = Refusal(retry)
    }

    /** **Lock vault**, a lock or a wipe: the screen goes. */
    fun clear() {
        flow.value = null
    }

    /**
     * **Try again**: the screen goes, back to the screen that sent the request. An idempotent read is sent again
     * (nothing was counted); a new `credential_required` shows the screen again, and any other failure is left to that
     * screen. Any other request (a write, or one that carried a PIN or a password) is not kept: the member sends it
     * again from its screen, whose secret fields are empty.
     */
    suspend fun tryAgain() {
        val r = flow.value ?: return
        if (!flow.compareAndSet(r, null)) return
        try {
            r.retry?.invoke()
        } catch (e: CancellationException) {
            throw e
        } catch (_: VaultFailure) {
            // Shown by the screen again (credential_required) or left to the screen that sent the request.
        }
    }
}
