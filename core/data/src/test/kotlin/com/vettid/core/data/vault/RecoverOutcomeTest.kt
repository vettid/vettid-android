package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How a refusal of `credential.recover` reads (VAULT-MESSAGING §11.11.5): only the two codes it has; any other fails. */
class RecoverOutcomeTest {
    @Test
    fun theTwoRefusalsHaveTheirOutcomes() {
        assertEquals(RecoverOutcome.NO_BACKUP, RecoverOutcome.ofRefusal("no_backup"))
        assertEquals(RecoverOutcome.CREDENTIAL_REQUIRED, RecoverOutcome.ofRefusal("credential_required"))
    }

    @Test
    fun everyOtherCodeIsTheVaultsError() {
        // `credential_lost` was removed in 0.16.0: no vault the app opens sends it, so it is unknown like any other.
        val unknown = listOf(
            "credential_lost", "forbidden", "bad_password", "internal", "No_Backup", "no_backup ", "", "something_new", null,
        )
        for (code in unknown) assertNull(code, RecoverOutcome.ofRefusal(code))
    }
}
