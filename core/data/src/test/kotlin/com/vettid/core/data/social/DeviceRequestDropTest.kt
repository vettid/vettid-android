// Event bodies stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.InMemoryDeviceStateStore
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * ANDROID-PLAN 0.1.53 AA1: ending a device's access, or removing it, takes its waiting requests out of Approvals at
 * once (the vault declined them, VAULT-MESSAGING §6.8). Only that device's; every other entry stays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceRequestDropTest {
    private val now = Instant.parse("2026-10-21T12:00:00Z")

    private val credential = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CredentialRepository::class.java)) { _, m, _ ->
        error("unexpected credential call ${m.name}")
    } as CredentialRepository

    private fun event(type: String, body: String) = VaultMessage(Inner(id = Ulid.new(), type = type, ts = now, body = body.toByteArray()))

    @Test
    fun onlyTheNamedDevicesRequestsLeave() = runTest {
        val m = SocialManager(
            backgroundScope,
            api = { throw VaultFailure(FailureKind.OTHER) },
            credential = credential,
            store = InMemoryDeviceStateStore(),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        m.onEvent(event("device.session.pending", """{"request_id":"s1","device_id":"A","role":"agent","name":"Bot","seconds":3600,"exp":"2026-10-21T12:10:00.000Z"}"""))
        m.onEvent(event("approval.pending", """{"approval_id":"h1","device_id":"A","role":"agent","name":"Bot","type":"agent.request","body":{},"exp":"2026-10-21T12:05:00.000Z"}"""))
        m.onEvent(event("device.session.pending", """{"request_id":"s2","device_id":"B","role":"desktop","name":"Laptop","seconds":900,"exp":"2026-10-21T12:10:00.000Z"}"""))
        runCurrent()
        assertEquals(3, m.approvals.value.size)

        m.dropDeviceRequests("A")
        runCurrent()
        assertEquals(listOf("device:device.session.pending:s2"), m.approvals.value.map { it.key })

        // A device with nothing waiting changes nothing.
        m.dropDeviceRequests("Z")
        runCurrent()
        assertEquals(1, m.approvals.value.size)
    }
}
