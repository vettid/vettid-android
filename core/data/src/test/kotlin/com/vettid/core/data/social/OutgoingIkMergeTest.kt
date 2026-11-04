// Event bodies stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.IkFingerprint
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.InMemoryDeviceStateStore
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** VAULT-MESSAGING 0.83.7 §10.4: how successive `connection.request.outgoing` events merge a service's `ik` fingerprint. */
@OptIn(ExperimentalCoroutinesApi::class)
class OutgoingIkMergeTest {
    private val now = Instant.parse("2026-11-04T12:00:00Z")
    private val credential = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CredentialRepository::class.java)) { _, m, _ ->
        error("unexpected credential call ${m.name}")
    } as CredentialRepository

    private fun TestScope.manager() = SocialManager(
        backgroundScope,
        api = { throw VaultFailure(FailureKind.OTHER) },
        credential = credential,
        obsoleteStore = InMemoryDeviceStateStore(),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun ik(seed: Int) = ByteArray(32) { (it + seed).toByte() }
    private fun b64(seed: Int) = Base64s.encodeStd(ik(seed))
    private fun fp(seed: Int) = IkFingerprint.format(ik(seed))

    private suspend fun SocialManager.emit(kind: String?, ikValue: String?) {
        val extra = (kind?.let { ""","peer_kind":"$it"""" } ?: "") + (ikValue?.let { ""","ik":"$it"""" } ?: "")
        val body = """{"connection_id":"c1","sas":"123456","state":"pending","name":"Acme"$extra}"""
        onEvent(VaultMessage(Inner(id = Ulid.new(), type = "connection.request.outgoing", ts = now, body = body.toByteArray())))
    }

    private fun SocialManager.shown() = approvals.value.single() as Approval.OutgoingRequest

    @Test
    fun aFreshIkReplacesTheOldOneAnAbsentOneKeepsItAndAnInvalidOneClearsIt() = runTest {
        val m = manager()
        m.emit("service", b64(1))
        runCurrent()
        assertEquals(fp(1), m.shown().peerFingerprint)
        // Absent: the earlier one stays, and so does the service flag when the event lacks `peer_kind`.
        m.emit(null, null)
        runCurrent()
        assertEquals(fp(1), m.shown().peerFingerprint)
        assertTrue(m.shown().service)
        // An unmarked event with another valid `ik` still replaces the stale one.
        m.emit(null, b64(2))
        runCurrent()
        assertEquals(fp(2), m.shown().peerFingerprint)
        // Present but invalid: cleared, not the old value.
        m.emit("service", "not base64!")
        runCurrent()
        assertNull(m.shown().peerFingerprint)
    }
}
