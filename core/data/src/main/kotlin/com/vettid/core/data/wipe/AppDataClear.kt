package com.vettid.core.data.wipe

import android.app.ActivityManager
import android.content.Context
import com.vettid.core.data.vault.FailureKind

/**
 * "Erase VettID's data on this phone": the way out of a wipe that did not finish ([FailureKind.ERASE_FAILED]), offered
 * only there ([offeredFor]) and only after the member's confirmation. Local only, fail secure: no session is opened
 * and nothing is sent to the vault; the vault itself is not affected.
 *
 * [run] first deletes the app's Keystore entries one by one ([keyAliases], [deleteKey]), best effort, in case clearing
 * the app's data leaves them on some phone; then [clearAppData] (`ActivityManager.clearApplicationUserData`), which
 * also ends the process. False: the system refused the clear, the process goes on, and the member is told to clear
 * VettID's storage in Android Settings or to reinstall it.
 */
class AppDataClear(
    private val keyAliases: () -> List<String>,
    private val deleteKey: (String) -> Unit,
    private val clearAppData: () -> Boolean,
) {
    @Suppress("TooGenericExceptionCaught") // best effort: whatever the Keystore does, the clear follows
    fun run(): Boolean {
        val aliases = try {
            keyAliases()
        } catch (_: Exception) {
            emptyList()
        }
        for (a in aliases) {
            try {
                deleteKey(a)
            } catch (_: Exception) {
                // the next one, and the clear, all the same
            }
        }
        return try {
            clearAppData()
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /** The only state that offers it: a wipe of this phone that did not finish. */
        fun offeredFor(kind: FailureKind?): Boolean = kind == FailureKind.ERASE_FAILED

        /** On a phone: the app's Keystore aliases ([AndroidWipeTargets]) and the system's clear of the app's data. */
        fun android(context: Context): AppDataClear {
            val app = context.applicationContext ?: context
            val targets = AndroidWipeTargets(app)
            return AppDataClear(
                keyAliases = { targets.keyAliases() },
                deleteKey = { targets.deleteKey(it) },
                clearAppData = { (app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.clearApplicationUserData() == true },
            )
        }
    }
}
