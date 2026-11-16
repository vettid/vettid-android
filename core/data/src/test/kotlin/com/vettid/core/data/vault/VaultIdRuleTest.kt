package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** This phone's vault id, as the deletion's checks, its markers and the member API take it. */
class VaultIdRuleTest {
    /** The vault the deletion's checks take: the session's device first (a transfer to this phone), an empty id is none. */
    @Test
    fun theHeldVaultIdIsTheDevicesFirst() {
        assertEquals("dev", VaultDeletion.heldVaultId("dev", ""))
        assertEquals("dev", VaultDeletion.heldVaultId("dev", "rec"))
        assertEquals("rec", VaultDeletion.heldVaultId(null, "rec"))
        assertEquals("rec", VaultDeletion.heldVaultId("", "rec"))
        assertEquals("", VaultDeletion.heldVaultId(null, ""))
        assertEquals("", VaultDeletion.heldVaultId("", ""))
    }

    /** The member API's vault id: the same rule, an empty result being none. */
    @Test
    fun theMemberApiVaultIdIsTheHeldOneOrNone() {
        assertEquals("dev", VaultDeletion.vaultIdOrNull("dev", "rec"))
        assertEquals("rec", VaultDeletion.vaultIdOrNull("", "rec"))
        assertEquals("rec", VaultDeletion.vaultIdOrNull(null, "rec"))
        assertNull(VaultDeletion.vaultIdOrNull("", ""))
        assertNull(VaultDeletion.vaultIdOrNull(null, ""))
    }
}
