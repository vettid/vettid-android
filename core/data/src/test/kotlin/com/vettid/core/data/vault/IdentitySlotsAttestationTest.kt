package com.vettid.core.data.vault

import com.vettid.core.keystore.AndroidKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * (ANDROID-PLAN §4 step 5) A slot's attestation keys go with the slot ([IdentitySlots.clear], the full forget), and the key
 * of the builds before slots belongs to the slot in use before it changes. Under Robolectric: the slots' SharedPreferences.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IdentitySlotsAttestationTest {
    private val ks = mutableSetOf<String>()
    private val keys = object : AttestationKeys.Keys {
        override fun exists(alias: String) = alias in ks

        override fun delete(alias: String) {
            ks.remove(alias)
        }
    }
    private val store = object : AttestationKeys.Store {
        val m = mutableMapOf<Int, String>()

        override fun read(slot: Int) = m[slot]

        override fun write(slot: Int, value: String?) {
            if (value == null) m.remove(slot) else m[slot] = value
        }
    }

    private fun slots(): IdentitySlots {
        val app = RuntimeEnvironment.getApplication()
        lateinit var s: IdentitySlots
        s = IdentitySlots(app, File(app.noBackupFilesDir, "t").apply { mkdirs() }, AttestationKeys(store, keys) { s.active })
        return s
    }

    private fun make(s: IdentitySlots, slot: Int): String {
        var made = ""
        s.attestation.attest(slot, "R$slot") { alias ->
            ks += alias
            made = alias
        }
        return made
    }

    @Test
    fun clearingASlotDeletesItsAttestationKeysOnly() {
        val s = slots()
        ks += AndroidKeys.ALIAS_DEVICE_ATTESTATION
        // E in slot 0 (the key of a build before slots), N in slot 1: switching to N first gives E that key.
        s.active = 1
        assertEquals(AndroidKeys.ALIAS_DEVICE_ATTESTATION, s.attestation.current(0)?.alias)
        val n = make(s, 1)
        s.active = 0
        s.clear(1)
        assertFalse(n in ks)
        assertTrue(s.attestation.candidates(1).isEmpty())
        assertEquals(AndroidKeys.ALIAS_DEVICE_ATTESTATION, s.attestation.current(0)?.alias)
    }

    @Test
    fun aFullForgetDeletesEverySlotsAttestationKeys() {
        val s = slots()
        ks += AndroidKeys.ALIAS_DEVICE_ATTESTATION
        make(s, 0)
        s.active = 1
        make(s, 1)
        s.resetToSingle()
        assertEquals(0, s.active)
        assertTrue(ks.isEmpty())
        assertTrue(store.m.isEmpty())
    }
}
