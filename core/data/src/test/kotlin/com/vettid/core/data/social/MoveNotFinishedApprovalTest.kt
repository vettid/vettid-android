// Event bodies stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.social

import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.data.vault.CredentialRepository
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.data.vault.assertMoveNotFinished
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * VAULT-MESSAGING 0.83.11 section 11.14.10: an approval answered `transfer_incomplete` shows the move-not-finished text
 * and the challenge stays open, so it can be approved again after the move.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MoveNotFinishedApprovalTest {
    private val at = Instant.parse("2026-11-04T12:00:00Z")
    private val clock = Clock.fixed(at, ZoneOffset.UTC)

    private val credential = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CredentialRepository::class.java)) { _, m, _ ->
        if (m.name == "openUnlockWindow") Unit else error("unexpected credential call ${m.name}")
    } as CredentialRepository

    @Test
    fun theChallengeIsKeptAndTheNewTextIsShown() = runTest {
        val m = SocialManager(
            backgroundScope,
            api = { throw VaultOpException("connection.authenticate.approve", "transfer_incomplete") },
            credential = credential,
            clock = clock,
        )
        val body = """{"connection_id":"c1","request_id":"r1","purpose":"signin","exp":"2026-11-04T13:00:00.000Z"}"""
        m.onEvent(VaultMessage(Inner(id = Ulid.new(), type = "connection.authenticate.pending", ts = at, body = body.toByteArray())))
        runCurrent()
        assertEquals(listOf("auth:r1"), m.approvals.value.map { it.key })
        try {
            m.approveAuthentication("r1", "pw-fake-1")
            fail("no failure")
        } catch (e: VaultFailure) {
            assertMoveNotFinished(e.kind)
        }
        assertEquals(listOf("auth:r1"), m.approvals.value.map { it.key })
        // Approved again after the move: still the same open challenge, and a second answer is sent.
        try {
            m.approveAuthentication("r1", "pw-fake-1")
            fail("no failure")
        } catch (e: VaultFailure) {
            assertMoveNotFinished(e.kind)
        }
    }
}
