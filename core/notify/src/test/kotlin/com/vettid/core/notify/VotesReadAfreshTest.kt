package com.vettid.core.notify

import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.testing.FakeVotes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The notifier's votes read (review 1, M3): true only for a read that was applied. A read a lock or hold voided while it was
 * out is false, as one that failed, so a `vote.result` keeps the generic title and never a give-up title from a list
 * that was not the vault's after the item.
 */
class VotesReadAfreshTest {
    @Test
    fun anAppliedReadIsTrue() = runTest {
        val votes = FakeVotes()
        assertTrue(votesReadAfresh(votes))
        assertEquals(listOf("refresh"), votes.calls)
    }

    @Test
    fun aReadALockVoidedIsFalse() = runTest {
        val votes = FakeVotes().apply { refreshApplied = false }
        assertFalse(votesReadAfresh(votes))
    }

    @Test
    fun aReadThatFailedIsFalse() = runTest {
        val votes = FakeVotes().apply { fail["refresh"] = VaultFailure(FailureKind.OWNER_CHECK_REQUIRED, "owner_check_required") }
        assertFalse(votesReadAfresh(votes))
    }

    /** The app's source answers with this rule (read from the source: it needs the whole vault manager to run). */
    @Test
    fun theVaultSourceUsesIt() {
        val c = File("src/main/kotlin/com/vettid/core/notify/VaultNotifySource.kt").readLines().map { it.substringBefore("//").trim() }
        assertTrue(c.contains("override suspend fun refreshVotes(): Boolean = votesReadAfresh(vault.votes)"))
    }
}
