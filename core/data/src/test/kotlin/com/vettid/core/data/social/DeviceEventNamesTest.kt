// Event bodies stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.vault.VaultJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** A device's own name in an event is cleaned and capped as `device.list`'s names are (ANDROID-PLAN 0.1.116 AA5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceEventNamesTest {
    private val at = Instant.parse("2026-11-02T12:00:00Z")

    private fun session(name: String) =
        ApprovalParser.parse("device.session.pending", VaultJson.parseObject("""{"request_id":"s1","device_id":"A","role":"agent","name":"$name","seconds":3600,"exp":"2026-11-02T12:10:00Z"}""".toByteArray()), at) as Approval.DeviceRequest

    private fun held(name: String) =
        ApprovalParser.parse("approval.pending", VaultJson.parseObject("""{"approval_id":"h1","device_id":"A","role":"agent","name":"$name","type":"agent.request","body":{"op":"catalog"},"exp":"2026-11-02T12:05:00Z"}""".toByteArray()), at) as Approval.DeviceRequest

    @Test
    fun aLongNameIsCapped() {
        val long = "x".repeat(500)
        assertEquals(64, session(long).deviceName!!.length)
        assertEquals(64, held(long).deviceName!!.length)
        assertEquals("Build bot", session("Build bot").deviceName)
    }

    @Test
    fun aHostileNameIsCleaned() {
        val n = session("\\u202Eevil\\u0007").deviceName!!
        assertFalse(n.contains('\u202E') || n.contains('\u0007'))
        assertTrue(n.contains("evil"))
        assertNull(held("\\u0007\\u202E").deviceName)
        assertNull(session("").deviceName)
    }
}
