// Fixtures (spec vectors, JSON bodies of the fake relay, member API and vault) stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.vault

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

    private companion object {
        const val TIMEOUT_MS = 300_000L
    }
}
