package com.vettid.core.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `vault.status`'s `relay` (VAULT-MESSAGING 0.84.0 §10.2): only `"refused"` counts; anything else is absent. */
class RelayStateTest {
    private fun read(body: String) = VaultJson.decode(VaultStatusInfo.serializer(), VaultJson.parseObject(body.toByteArray()))

    @Test
    fun refusedIsTheOnlyValueThatCounts() {
        assertTrue(read("""{"vault_id":"x","relay":"refused"}""").relayRefused)
    }

    @Test
    fun absentAndUnknownValuesCountAsAbsent() {
        assertFalse(read("""{"vault_id":"x"}""").relayRefused)
        assertFalse(read("""{"vault_id":"x","relay":null}""").relayRefused)
        assertFalse(read("""{"vault_id":"x","relay":"degraded"}""").relayRefused)
        assertFalse(read("""{"vault_id":"x","relay":"REFUSED"}""").relayRefused)
        assertFalse(read("""{"vault_id":"x","relay":""}""").relayRefused)
        assertFalse(read("""{"vault_id":"x","relay":7}""").relayRefused)
        assertFalse(read("""{"vault_id":"x","relay":true}""").relayRefused)
        assertFalse(read("""{"vault_id":"x","relay":{"state":"refused"}}""").relayRefused)
        assertFalse(read("""{"vault_id":"x","relay":["refused"]}""").relayRefused)
    }
}
