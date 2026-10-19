package com.vettid.core.data.vault

import com.vettid.core.vault.ChangeMarker
import com.vettid.core.vault.ChangeUnansweredException
import com.vettid.core.vault.CredentialUnsettledException
import com.vettid.core.vault.KeptChange
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

/**
 * Sends a PIN or password change (VAULT-MESSAGING 0.44.0 §11.14.9) in the vault manager's own scope: a screen that goes
 * away while the change is on its way does not stop it, and its outcome is kept and said ([ChangeResolver.check]).
 *
 * - The marker is stored first ([Ops.store]); a refused store sends nothing.
 * - An answer decides the change: success confirms it, a refusal clears the marker and is thrown as it is, and
 *   `stale_credential` (answered before any secret was tested) means this request did not apply.
 * - No answer, or anything else that goes wrong after the marker, is not a failure: the request is kept in memory, the
 *   check starts, and [FailureKind.CHANGE_CHECKING] tells the screen to keep the entries.
 * - An earlier credential operation that may still reach the vault refuses the change before anything is stored
 *   ([FailureKind.CREDENTIAL_UNSETTLED]).
 */
internal class ChangeSender(
    private val scope: CoroutineScope,
    private val resolver: ChangeResolver,
    private val ops: Ops,
    private val failureOf: (String) -> FailureKind,
) {
    interface Ops {
        /** Stores the marker for [m] (throws [VaultFailure] when it cannot be stored, or another change is unresolved). */
        fun store(since: Int, m: ChangeMarker, fromEpoch: Long): UnresolvedChange

        /** The change applied: the candidate made current and the marker cleared. False: the write failed. */
        fun confirm(m: UnresolvedChange): Boolean

        /** The change did not apply or was refused: the marker cleared. */
        fun clear(m: UnresolvedChange)

        /** The request as it was sent, kept in memory for the check's resends. */
        fun keep(k: KeptChange)
    }

    /** [send] sends the change, calling its argument with the marker before anything goes out. */
    suspend fun send(since: Int, kind: ChangeKind, fromEpoch: Long, send: suspend (suspend (ChangeMarker) -> Unit) -> Unit) {
        scope.async { run(since, kind, fromEpoch, send) }.await()
    }

    @Suppress("ThrowsCount")
    private suspend fun run(since: Int, kind: ChangeKind, fromEpoch: Long, send: suspend (suspend (ChangeMarker) -> Unit) -> Unit) {
        var marker: UnresolvedChange? = null
        try {
            send { m -> marker = ops.store(since, m, fromEpoch) }
        } catch (e: CredentialUnsettledException) {
            throw VaultFailure(FailureKind.CREDENTIAL_UNSETTLED, CODE_UNSETTLED, cause = e)
        } catch (e: ChangeUnansweredException) {
            ops.keep(e.kept)
            resolver.publish(ChangeCheck.Checking(kind))
            resolver.kick()
            throw VaultFailure(FailureKind.CHANGE_CHECKING, CODE_CHECKING, cause = e)
        } catch (e: VaultOpException) {
            // Before the marker (a UTK request refused): nothing of the change was sent.
            val m = marker ?: throw e
            ops.clear(m)
            if (e.code == CODE_STALE) {
                resolver.publish(ChangeCheck.NotApplied(kind))
                throw VaultFailure(FailureKind.CHANGE_CHECKING, CODE_CHECKING, cause = e)
            }
            resolver.publish(ChangeCheck.Refused(kind, failureOf(e.code)))
            throw e
        }
        marker?.let { ops.confirm(it) }
        resolver.publish(ChangeCheck.Applied(kind))
    }

    companion object {
        const val CODE_CHECKING = "change_checking"
        const val CODE_UNSETTLED = "credential_unsettled"
        private const val CODE_STALE = "stale_credential"
    }
}
