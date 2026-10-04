// Fixtures (spec vectors, JSON bodies of the fake relay, member API and vault) stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.vault

import com.vettid.core.relay.MailboxCollector
import com.vettid.core.testing.DevStack
import com.vettid.core.testing.ExitScenario
import com.vettid.core.testing.TestAndroidAttester
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The A2 exit scenario from the JVM against a running local dev stack
 * (devstack/devstack.sh up). Skipped when the stack is not running (CI).
 */
class DevStackJvmTest {
    @Test
    fun exitScenario() = runBlocking {
        val stack = DevStack()
        assumeTrue("dev stack not running", stack.reachable())
        withTimeout(TIMEOUT_MS) {
            ExitScenario(stack, TestAndroidAttester(), DeviceSecrets.generate(), InMemoryDeviceStateStore()) { println("exit: $it") }.run(this)
        }
    }

    /** The same scenario with the device collecting over WebSocket (RELAY-PROTOCOL §6.4). */
    @Test
    fun exitScenarioOverWebSocket() = runBlocking {
        val stack = DevStack()
        assumeTrue("dev stack not running", stack.reachable())
        withTimeout(TIMEOUT_MS) {
            ExitScenario(stack, TestAndroidAttester(), DeviceSecrets.generate(), InMemoryDeviceStateStore(), MailboxCollector.Mode.WEBSOCKET) {
                println("ws: $it")
            }.run(this)
        }
    }

    /** WebSocket collect against the real relay (no fallback involved): deposit to oneself, receive, ack. */
    @Test
    fun relayWebSocketCollect() = runBlocking {
        val stack = DevStack()
        assumeTrue("dev stack not running", stack.reachable())
        val key = com.vettid.core.crypto.Ed25519PrivateKey.generate()
        val c = com.vettid.core.relay.RelayClient(stack.relayUrl, key, stack.http)
        c.register()
        val s = c.openStream()
        try {
            val tok = c.mintToken(c.publicKeyB64)
            val id = c.deposit(c.mailboxId, tok, byteArrayOf(1, 2, 3))
            val m = withTimeout(10_000) { s.incoming.receive() }
            org.junit.Assert.assertEquals(id, m.msgId)
            org.junit.Assert.assertEquals(c.publicKeyB64, m.sender)
            org.junit.Assert.assertTrue(s.ack(m.msgId))
        } finally {
            s.close()
            c.deleteMailbox()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 300_000L
    }
}
