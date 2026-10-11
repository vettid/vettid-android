package com.vettid.core.crypto

import com.vettid.core.crypto.altchan.DeviceAssertion
import com.vettid.core.crypto.json.StrictJson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** `device_assertion` with the optional `chain` of VAULT-MESSAGING 0.27.0 §11.7 / §11.7.1. */
class DeviceAssertionTest {
    private val sig = ByteArray(71) { (it + 1).toByte() }
    private val leaf = ByteArray(600) { 0x11 }
    private val root = ByteArray(500) { 0x22 }

    @Test
    fun withoutAChainTheWireFormIsAsBefore() {
        val a = DeviceAssertion.android(sig)
        assertEquals("""{"platform":"android","sig":"${Base64s.encodeStd(sig)}"}""", a.marshal())
        assertNull(a.chain())
        assertFalse(StrictJson.parseObject(a.marshal().toByteArray()).members.containsKey("chain"))
    }

    @Test
    fun theChainFollowsTheSignatureLeafFirst() {
        val a = DeviceAssertion.android(sig, listOf(leaf, root))
        val b64 = listOf(sig, leaf, root).map { Base64s.encodeStd(it) }
        assertEquals("""{"platform":"android","sig":"${b64[0]}","chain":["${b64[1]}","${b64[2]}"]}""", a.marshal())
        val back = DeviceAssertion.parse(StrictJson.parseObject(a.marshal().toByteArray()))
        assertArrayEquals(sig, back.sig())
        assertEquals(2, back.chain()!!.size)
        assertArrayEquals(leaf, back.chain()!![0])
        assertArrayEquals(root, back.chain()!![1])
        assertNull(a.withoutChain().chain())
        assertArrayEquals(sig, a.withoutChain().sig())
    }

    @Test
    fun aChainIsOneToTenCertificates() {
        assertThrows(CryptoException.Format::class.java) { DeviceAssertion.android(sig, emptyList()) }
        assertThrows(CryptoException.Format::class.java) { DeviceAssertion.android(sig, List(11) { leaf }) }
        assertThrows(CryptoException.Format::class.java) { DeviceAssertion.android(sig, listOf(leaf, ByteArray(0))) }
        assertEquals(10, DeviceAssertion.android(sig, List(10) { leaf }).chain()!!.size)
        val emptyChain = """{"platform":"android","sig":"${Base64s.encodeStd(sig)}","chain":[]}"""
        assertThrows(CryptoException.Format::class.java) { DeviceAssertion.parse(StrictJson.parseObject(emptyChain.toByteArray())) }
    }
}
