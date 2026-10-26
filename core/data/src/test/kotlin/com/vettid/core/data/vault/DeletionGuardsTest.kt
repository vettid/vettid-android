package com.vettid.core.data.vault

import com.vettid.core.altchan.MemberApiException
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.vault.VaultMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** The 410-only state's decisions, driven through the classes [VaultManager] only constructs (ANDROID-PLAN 0.1.78). */
class DeletionGuardsTest {
    private fun msg(type: String, reason: String?) = VaultMessage(
        Inner(
            id = "01JB2Z6V9K3M4N5P6Q7R8S9T60", type = type, ts = Instant.parse("2026-10-24T10:00:00Z"),
            body = (if (reason == null) "{}" else """{"reason":"$reason"}""").toByteArray(),
        ),
    )

    private fun gone(code: String = "vault_deleted", status: Int = 410) =
        VaultFailure(FailureKind.VAULT_DELETED, code, cause = MemberApiException(status, code))

    // --- the observer ---

    private class Events(standing: Boolean) {
        var erased = 0
        val holder = mutableListOf<String>()
        val watch = DeletionEvents({ standing }, { erased++ }, { holder += it.type })
    }

    @Test
    fun whileA410StandsOnlyTheVaultsOwnWordErases() {
        val e = Events(true)
        assertTrue(e.watch.take(msg("device.unlinked", "vault_deleted")))
        assertEquals(1, e.erased)
        val others = listOf(
            msg("vault.locking", null), msg("sync.event", null), msg("device.unlinked", "other"), msg("device.paired", "vault_deleted"),
        )
        for (m in others) assertTrue(e.watch.take(m)) // taken (dropped) here
        assertEquals(1, e.erased)
        assertTrue(e.holder.isEmpty())
    }

    @Test
    fun replacementProofsGoToTheHolderAndNeverErase() {
        val e = Events(true)
        e.watch.take(msg("device.unlinked", "transferred"))
        e.watch.take(msg("device.unlinked", "replaced"))
        assertEquals(listOf("device.unlinked", "device.unlinked"), e.holder)
        assertEquals(0, e.erased)
    }

    @Test
    fun withoutAStanding410NothingIsTakenHere() {
        val e = Events(false)
        assertFalse(e.watch.take(msg("device.unlinked", "vault_deleted")))
        assertFalse(e.watch.take(msg("device.unlinked", "transferred")))
        assertEquals(0, e.erased)
        assertTrue(e.holder.isEmpty())
    }

    // --- the read ---

    private fun read(standing: Boolean, check: DeletionCheck, onListed: () -> Unit = {}) =
        ResolvedRead({ standing }, { check }, onListed)

    @Test
    fun aGoneErasesOnlyWithTheVaultsWord() = runTest {
        assertEquals(DeletionCheck.API_ONLY, read(standing = true, check = DeletionCheck.GONE).read()) // a 410 alone
        assertEquals(DeletionCheck.GONE, read(standing = false, check = DeletionCheck.GONE).read()) // with the word
    }

    @Test
    fun aListedStatusClearsTheMarkerOnlyWhileA410Stands() = runTest {
        var cleared = 0
        assertEquals(DeletionCheck.LISTED, read(true, DeletionCheck.LISTED) { cleared++ }.read())
        assertEquals(1, cleared)
        read(false, DeletionCheck.LISTED) { cleared++ }.read()
        read(true, DeletionCheck.UNREACHABLE) { cleared++ }.read()
        assertEquals(1, cleared)
    }

    // --- the start ---

    @Test
    fun onlyADefinite410OnAPhoneWithAVaultStartsTheStateAndLocksAnOpenOne() {
        val p = ApiDeletionStart.plan(gone(), AppPhase.Unlocked, deleting = false)!!
        assertEquals("vault_deleted", p.code)
        assertTrue(p.lock)
        assertEquals("account_deleted", ApiDeletionStart.plan(gone("account_deleted"), AppPhase.Locked, false)!!.code)
        assertFalse(ApiDeletionStart.plan(gone(), AppPhase.Locked, false)!!.lock)
        assertTrue(ApiDeletionStart.plan(gone(), AppPhase.Setup(SetupStage.FINISHING), false)!!.lock)
        // Nothing else starts it.
        assertNull(ApiDeletionStart.plan(VaultFailure(FailureKind.UNAUTHORIZED, "unauthorized"), AppPhase.Unlocked, false))
        assertNull(ApiDeletionStart.plan(gone("release_unavailable"), AppPhase.Unlocked, false))
        assertNull(ApiDeletionStart.plan(gone(status = 404), AppPhase.Unlocked, false))
        assertNull(ApiDeletionStart.plan(gone(), AppPhase.Unlocked, deleting = true))
        assertNull(ApiDeletionStart.plan(gone(), AppPhase.SignedOut, false))
        assertNull(ApiDeletionStart.plan(gone(), AppPhase.Setup(SetupStage.NEW_VAULT), false))
    }

    // --- the sessions ---

    @Test
    fun aSessionWhileDeletingIsOnlyTheMailboxOfAStanding410() {
        fun refused(deleting: Boolean, mailbox: Boolean, standing: Boolean): Boolean = try {
            SessionGuard({ deleting }, { standing }).check(mailbox)
            false
        } catch (e: VaultFailure) {
            assertEquals(VaultManager.CODE_DELETING, e.code)
            true
        }
        assertFalse(refused(deleting = false, mailbox = false, standing = false))
        assertTrue(refused(deleting = true, mailbox = false, standing = true))
        assertTrue(refused(deleting = true, mailbox = true, standing = false))
        assertFalse(refused(deleting = true, mailbox = true, standing = true))
        // The lock's session is the mailbox one exactly while a 410 stands.
        assertTrue(SessionGuard({ true }, { true }).lockMailboxOnly())
        assertFalse(SessionGuard({ true }, { false }).lockMailboxOnly())
        if (false) fail()
    }
}
