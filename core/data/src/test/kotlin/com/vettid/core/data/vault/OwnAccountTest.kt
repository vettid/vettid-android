package com.vettid.core.data.vault

import com.vettid.core.vault.AccountSnapshot
import com.vettid.core.vault.AccountView
import com.vettid.core.vault.NameRequest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The member's own account is memory only and forgotten whenever the vault is not open (ANDROID-PLAN §4 "The member's
 * own profile"): [OwnAccount], the gate [isOwnProfileOpen], and the account record that no longer keeps it.
 */
class OwnAccountTest {
    private val snap = AccountSnapshot(
        asOf = "2026-10-21T00:00:00Z",
        emailHint = "a***@example.org",
        email = "ada@example.org",
        state = "member",
        firstName = "Ada",
        lastName = "Lovelace",
    )
    private val request = NameRequest(seq = 3, firstName = "Ada", lastName = "King", state = "pending")
    private val open = { true }

    private fun view(s: AccountSnapshot?, r: NameRequest?) = AccountView(s, 1, nameRequest = r)

    @Test
    fun anAnswerIsKeptWhileTheVaultIsOpen() {
        val own = OwnAccount()
        assertNotNull(own.keep(own.mark(), view(snap, request), open))
        assertEquals(snap, own.snapshot)
        assertEquals(request, own.nameRequest)
    }

    @Test
    fun aLockForgetsTheSnapshotAndTheNameRequest() {
        val own = OwnAccount()
        own.keep(own.mark(), view(snap, request), open)
        var published = false
        own.forget { published = true }
        assertNull(own.snapshot)
        assertNull(own.nameRequest)
        assertTrue(published)
    }

    @Test
    fun anAnswerOnItsWayWhenTheVaultLocksIsDropped() {
        val own = OwnAccount()
        val mark = own.mark()
        // `account.get` was sent; the vault locks (or the check falls due) before its answer arrives.
        own.forget()
        assertNull(own.keep(mark, view(snap, request), open))
        assertNull(own.snapshot)
        assertNull(own.nameRequest)
    }

    @Test
    fun anAnswerArrivingWhileTheVaultIsNotOpenIsDropped() {
        val own = OwnAccount()
        assertNull(own.keep(own.mark(), view(snap, request), open = { false }))
        assertNull(own.snapshot)
        assertFalse(own.request(own.mark(), request, open = { false }))
        assertNull(own.nameRequest)
    }

    @Test
    fun aNameRequestIsKeptInMemoryAndForgottenAtTheLock() {
        val own = OwnAccount()
        val mark = own.mark()
        assertTrue(own.request(mark, request, open))
        assertEquals(request, own.nameRequest)
        own.forget()
        assertNull(own.nameRequest)
        // A request answered after the lock: not kept.
        assertFalse(own.request(mark, request, open))
        assertNull(own.nameRequest)
    }

    @Test
    fun theAnswerAfterTheNextUnlockBringsTheNamesBack() {
        val own = OwnAccount()
        own.keep(own.mark(), view(snap, null), open)
        own.forget()
        assertNotNull(own.keep(own.mark(), view(snap, null), open))
        val info = own.publish { s, r -> LocalAccount(emailHint = "a***@example.org").toInfo(s, r) }!!
        assertEquals("Ada Lovelace", info.fullName)
        assertEquals("ada@example.org", info.displayEmail)
    }

    @Test
    fun whileTheVaultIsNotOpenOnlyTheMaskedEmailIsShown() {
        val own = OwnAccount()
        own.keep(own.mark(), view(snap, request), open)
        own.forget()
        val info = own.publish { s, r -> LocalAccount(emailHint = "a***@example.org").toInfo(s, r) }!!
        assertNull(info.fullName)
        assertNull(info.firstName)
        assertNull(info.email)
        assertNull(info.nameRequest)
        assertEquals("a***@example.org", info.displayEmail)
    }

    @Test
    fun theOwnProfileIsOpenOnlyWhenUnlockedAndNeitherHeldNorDue() {
        val deadline = Instant.parse("2026-10-22T00:00:00Z")
        fun check(s: OwnerCheckState) = OwnerCheckView(s, deadline, 86_400, 0, hold = true, holdOffUntil = null)
        assertTrue(isOwnProfileOpen(AppPhase.Unlocked, null))
        assertTrue(isOwnProfileOpen(AppPhase.Unlocked, check(OwnerCheckState.OK)))
        assertFalse(isOwnProfileOpen(AppPhase.Unlocked, check(OwnerCheckState.HELD)))
        assertFalse(isOwnProfileOpen(AppPhase.Unlocked, check(OwnerCheckState.DUE)))
        assertFalse(isOwnProfileOpen(AppPhase.Locked, null))
        assertFalse(isOwnProfileOpen(AppPhase.SignedOut, null))
        assertFalse(isOwnProfileOpen(AppPhase.Starting, null))
        assertFalse(isOwnProfileOpen(AppPhase.Deleting(), check(OwnerCheckState.OK)))
    }

    @Test
    fun theAccountRecordHasNoPlaceForTheOwnAccount() {
        val names = LocalAccount.serializer().descriptor.let { d -> (0 until d.elementsCount).map { d.getElementName(it) } }
        assertFalse("snapshot" in names)
        assertFalse("nameRequest" in names)
    }

    @Test
    fun aRecordOfAnOlderBuildIsReadWithoutTheOwnAccountAndWrittenAgainWithout() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = """{"userGuid":"u","vaultId":"v","emailHint":"a***@example.org",""" +
            """"snapshot":{"v":1,"email":"ada@example.org","first_name":"Ada","last_name":"Lovelace"},""" +
            """"nameRequest":{"seq":3,"first_name":"Ada","last_name":"King","state":"pending"},"setupComplete":true}"""
        val a = json.decodeFromString(LocalAccount.serializer(), legacy)
        assertEquals("u", a.userGuid)
        assertTrue(a.setupComplete)
        var disk: String? = legacy
        val record = AccountRecord(a, readable = true, persist = { disk = json.encodeToString(LocalAccount.serializer(), it) })
        assertTrue(record.rewrite())
        val written = disk!!
        assertFalse(written.contains("snapshot"))
        assertFalse(written.contains("nameRequest"))
        assertFalse(written.contains("Lovelace"))
        assertFalse(written.contains("ada@example.org"))
        assertTrue(written.contains("a***@example.org"))
    }

    @Test
    fun anAccountAnswerWritesOnlyTheMaskedEmail() {
        var disk: LocalAccount? = null
        val record = AccountRecord(LocalAccount(userGuid = "u", emailHint = "o***@example.org"), readable = true, persist = { disk = it })
        assertTrue(record.refreshed(record.recordGeneration, AccountUpdate(snap, request)))
        assertEquals(LocalAccount(userGuid = "u", emailHint = "a***@example.org"), disk)
        // The same hint again: nothing to write.
        disk = null
        assertFalse(record.refreshed(record.recordGeneration, AccountUpdate(snap, request)))
        assertNull(disk)
    }
    @Test
    fun anOlderAnswerNeverReplacesANewerOne() {
        val own = OwnAccount()
        // Two `account.get`s on their way; the newer answer lands first.
        val mark = own.mark()
        val newer = snap.copy(asOf = "2026-10-21T12:00:00Z", lastName = "King")
        assertNotNull(own.keep(mark, view(newer, null), open))
        assertNull(own.keep(mark, view(snap, null), open))
        assertEquals("King", own.snapshot!!.lastName)
        // What a kept answer replaced is reported (the shell re-reads the profile when the names changed).
        val newest = newer.copy(asOf = "2026-10-21T13:00:00Z", lastName = "Byron")
        val kept = own.keep(mark, view(newest, null), open)!!
        assertEquals(newer, kept.before)
        assertEquals(newest, kept.update.snapshot)
    }

    @Test
    fun aPhaseOtherThanUnlockedOrStartingForgetsAndAnUnlockReads() {
        assertEquals(OwnProfileStep.READ, OwnProfileStep.onPhase(AppPhase.Unlocked))
        assertEquals(OwnProfileStep.NONE, OwnProfileStep.onPhase(AppPhase.Starting))
        assertEquals(OwnProfileStep.FORGET, OwnProfileStep.onPhase(AppPhase.Locked))
        assertEquals(OwnProfileStep.FORGET, OwnProfileStep.onPhase(AppPhase.SignedOut))
        assertEquals(OwnProfileStep.FORGET, OwnProfileStep.onPhase(AppPhase.Deleting()))
    }

    @Test
    fun enteringTheHoldForgetsAndThePassedCheckReadsAgain() {
        val deadline = Instant.parse("2026-10-22T00:00:00Z")
        fun check(s: OwnerCheckState) = OwnerCheckView(s, deadline, 86_400, 0, hold = true, holdOffUntil = null)
        assertTrue(OwnProfileStep.held(check(OwnerCheckState.HELD)))
        assertTrue(OwnProfileStep.held(check(OwnerCheckState.DUE)))
        assertFalse(OwnProfileStep.held(check(OwnerCheckState.OK)))
        assertFalse(OwnProfileStep.held(null))
        assertEquals(OwnProfileStep.FORGET, OwnProfileStep.onCheck(wasHeld = false, held = true, phase = AppPhase.Unlocked))
        assertEquals(OwnProfileStep.READ, OwnProfileStep.onCheck(wasHeld = true, held = false, phase = AppPhase.Unlocked))
        // Passed while locked: the unlock reads it ([OwnProfileStep.onPhase]).
        assertEquals(OwnProfileStep.NONE, OwnProfileStep.onCheck(wasHeld = true, held = false, phase = AppPhase.Locked))
        assertEquals(OwnProfileStep.NONE, OwnProfileStep.onCheck(wasHeld = true, held = true, phase = AppPhase.Unlocked))
        assertEquals(OwnProfileStep.NONE, OwnProfileStep.onCheck(wasHeld = false, held = false, phase = AppPhase.Unlocked))
    }

    @Test
    fun theStartFindsTheOwnAccountOfAnOlderBuildInTheRecord() {
        val json = Json { ignoreUnknownKeys = true }
        assertTrue(holdsLegacyOwnAccount("""{"userGuid":"u","snapshot":{"v":1}}""", json))
        assertTrue(holdsLegacyOwnAccount("""{"userGuid":"u","nameRequest":{"seq":1}}""", json))
        assertFalse(holdsLegacyOwnAccount("""{"userGuid":"u","emailHint":"a***@example.org"}""", json))
        assertFalse(holdsLegacyOwnAccount("not json", json))
        assertFalse(holdsLegacyOwnAccount("[1]", json))
        // What the start writes back holds neither.
        val read = json.decodeFromString(LocalAccount.serializer(), """{"userGuid":"u","snapshot":{"v":1}}""")
        val rewritten = json.encodeToString(LocalAccount.serializer(), read)
        assertFalse(holdsLegacyOwnAccount(rewritten, json))
    }

    @Test
    fun theShellShowsTheAccountOnlyFromTheOpenVaultsSnapshot() {
        val own = OwnAccount()
        val record = LocalAccount(emailHint = "a***@example.org")
        // Locked (nothing in memory): the masked hint is there for the unlock screen, not for the shell.
        val locked = own.publish { s, r -> record.toInfo(s, r) }
        assertEquals("a***@example.org", locked!!.emailHint)
        assertNull(locked.shownInShell())
        assertNull((null as AccountInfo?).shownInShell())
        own.keep(own.mark(), view(snap, null), open)
        val opened = own.publish { s, r -> record.toInfo(s, r) }
        assertEquals("Ada Lovelace", opened.shownInShell()!!.fullName)
    }
}
