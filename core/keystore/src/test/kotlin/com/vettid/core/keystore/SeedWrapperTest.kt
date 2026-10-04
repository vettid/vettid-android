package com.vettid.core.keystore

import com.vettid.core.crypto.Ed25519PrivateKey
import com.vettid.core.crypto.hpke.KemPrivateKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

/** The wrap format on the JVM, with a software AES key standing in for the Keystore key. */
class SeedWrapperTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val wrapper = SeedWrapper { key }

    private class MemoryStore : WrappedKeyStore {
        val map = HashMap<KeySlot, ByteArray>()

        override fun get(slot: KeySlot) = map[slot]

        override fun put(slot: KeySlot, blob: ByteArray) {
            map[slot] = blob
        }

        override fun remove(slot: KeySlot) {
            map.remove(slot)
        }
    }

    @Test
    fun roundTripAndBinding() {
        val seed = ByteArray(32) { it.toByte() }
        val blob = wrapper.wrap(KeySlot.IDENTITY, seed)
        assertEquals(1 + 12 + 32 + 16, blob.size)
        assertEquals(1, blob[0].toInt())
        assertArrayEquals(seed, wrapper.unwrap(KeySlot.IDENTITY, blob))
        // Randomized: wrapping twice gives different blobs.
        assertNotEquals(blob.toList(), wrapper.wrap(KeySlot.IDENTITY, seed).toList())
        // Bound to the slot: a relay blob does not unwrap as the identity.
        assertThrows(KeystoreException::class.java) { wrapper.unwrap(KeySlot.RELAY, blob) }
        // Tampering, truncation, another key.
        val tampered = blob.copyOf().also { it[20] = (it[20] + 1).toByte() }
        assertThrows(KeystoreException::class.java) { wrapper.unwrap(KeySlot.IDENTITY, tampered) }
        assertThrows(KeystoreException::class.java) { wrapper.unwrap(KeySlot.IDENTITY, blob.copyOf(20)) }
        val other = SeedWrapper { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        assertThrows(KeystoreException::class.java) { other.unwrap(KeySlot.IDENTITY, blob) }
    }

    @Test
    fun deviceKeys() {
        val store = MemoryStore()
        val keys = DeviceKeys(wrapper, store)
        KeySlot.entries.forEach { keys.generate(it) }
        assertTrue(KeySlot.entries.all { keys.has(it) })
        val relay = keys.ed25519(KeySlot.RELAY)
        val ik = keys.ed25519(KeySlot.IDENTITY)
        val kem: KemPrivateKey = keys.kem()
        assertNotEquals(relay.publicKey.toList(), ik.publicKey.toList())
        // Unwrapping again yields the same keys.
        assertArrayEquals(ik.publicKey, keys.ed25519(KeySlot.IDENTITY).publicKey)
        assertEquals(kem.publicKey, keys.kem().publicKey)
        // The stored blobs hold no seed in the clear.
        val seed = ik.seed()
        assertTrue(store.map.values.none { blob -> blob.toList().windowed(32).any { it == seed.toList() } })
        keys.clear()
        assertThrows(KeystoreException::class.java) { keys.ed25519(KeySlot.IDENTITY) }
        assertEquals(Ed25519PrivateKey::class, relay::class)
    }
}
