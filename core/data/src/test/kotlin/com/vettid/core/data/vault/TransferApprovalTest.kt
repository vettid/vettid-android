package com.vettid.core.data.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * §11.14.10 (owner decision 2026-10-23): the renewal that runs before each approval never blocks it. (That it is
 * deposited before the approval, and again before a retry, is VaultDeviceTest's.)
 */
class TransferApprovalTest {
    @Test
    fun theRenewalRunsEachTime() = runTest {
        var renewed = 0
        val r = TransferApproval.renewal(renew = { renewed++ }) { error("not failed") }
        r()
        r()
        assertEquals(2, renewed)
    }

    @Test
    fun aFailedRenewalIsLoggedAndDoesNotBlockTheApproval() = runTest {
        val failed = mutableListOf<String>()
        val r = TransferApproval.renewal(renew = { throw IOException("relay down") }) { failed += it.javaClass.simpleName }
        r() // does not throw: the approval goes out
        assertEquals(listOf("IOException"), failed)
    }

    @Test
    fun aCancelledRenewalStopsTheApproval() = runTest {
        val failed = mutableListOf<String>()
        val r = TransferApproval.renewal(renew = { throw CancellationException("gone") }) { failed += "failed" }
        assertTrue(runCatching { r() }.exceptionOrNull() is CancellationException)
        assertEquals(emptyList<String>(), failed)
    }
}
