package com.vettid.core.data.vault

import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** How a refusal of `credential.recover` reads (VAULT-MESSAGING §11.11.5): only the two codes it has; any other fails. */
class RecoverOutcomeTest {
    private val unknown = listOf(
        "credential_lost", "unsupported_type", "forbidden", "bad_password", "internal", "No_Backup", "no_backup ", "", "something_new",
    )

    @Test
    fun theTwoRefusalsHaveTheirOutcomes() {
        assertEquals(RecoverOutcome.NO_BACKUP, RecoverOutcome.ofRefusal("no_backup"))
        assertEquals(RecoverOutcome.CREDENTIAL_REQUIRED, RecoverOutcome.ofRefusal("credential_required"))
    }

    @Test
    fun everyOtherCodeIsTheVaultsError() {
        // `credential_lost` was removed in 0.16.0: no vault the app opens sends it, so it is unknown like any other.
        for (code in unknown + listOf(null)) assertNull(code, RecoverOutcome.ofRefusal(code))
    }

    /** As `VaultManager.recoverCredential` runs it: the send, then the vault guard. */
    private suspend fun recover(send: suspend () -> Unit): RecoverOutcome = vaultGuard { RecoverOutcome.answering(send) }

    @Test
    fun aSendThatReturnedRecovered() = runTest {
        assertEquals(RecoverOutcome.RECOVERED, recover {})
        assertEquals(RecoverOutcome.NO_BACKUP, recover { throw VaultOpException("credential.recover", "no_backup") })
        assertEquals(RecoverOutcome.CREDENTIAL_REQUIRED, recover { throw VaultOpException("credential.recover", "credential_required") })
    }

    /** An unknown refusal is a failure, never "no backup" (which would drop the recovery's registration). */
    @Test
    fun anUnknownRefusalIsAFailure() = runTest {
        for (code in unknown) {
            try {
                val got = recover { throw VaultOpException("credential.recover", code) }
                fail("$code read as $got")
            } catch (f: VaultFailure) {
                assertEquals(code, code, f.code)
            }
        }
    }
}
