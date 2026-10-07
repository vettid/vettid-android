package com.vettid.core.data.vault

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.IkFingerprint
import com.vettid.core.vault.NameRequest
import com.vettid.core.vault.Profile
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The member's own profile and the name change (VAULT-MESSAGING 0.18.0 §10.8). */
class ProfileManagerTest {
    private val ik = ByteArray(32) { 0x04 }

    private class Ops(val ik: ByteArray) : ProfileOps {
        var profile = Profile(4, "", null, "Ada", "Lovelace", Base64s.encodeStd(ik))
        val sets = mutableListOf<Pair<Long, String>>()
        val nameSets = mutableListOf<List<String>>()
        var error: String? = null
        var errorBody: JsonObject? = null

        override suspend fun profileGet() = profile

        override suspend fun profileSet(version: Long, name: String): Long {
            sets += version to name
            profile = profile.copy(version = version + 1, name = name)
            return version + 1
        }

        override suspend fun accountNameSet(pin: String, password: String, firstName: String, lastName: String): NameRequest {
            nameSets += listOf(pin, password, firstName, lastName)
            error?.let { throw VaultOpException("account.name.set", it, body = errorBody) }
            return NameRequest(7, firstName, lastName, "2026-10-07T12:00:00.000Z", "pending")
        }
    }

    private val ops = Ops(ik)
    private val requests = mutableListOf<NameRequestView>()
    private var checksLeftCalls = 0
    private val m = ProfileManager(
        ops = { ops },
        onRequest = { requests += it },
        checksLeft = {
            checksLeftCalls++
            8
        },
    )

    private fun o(s: String) = VaultJson.json.parseToJsonElement(s).jsonObject

    @Test
    fun readsTheCoreReadOnlyAndTheFingerprint() = runTest {
        m.refreshProfile()
        val p = m.profile.value!!
        assertEquals("Ada Lovelace", p.fullName)
        assertEquals("", p.displayName)
        assertEquals(IkFingerprint.format(ik), p.fingerprint)
    }

    @Test
    fun aVaultWithoutTheCoreGivesNoNames() = runTest {
        ops.profile = Profile(1, "Ada")
        m.refreshProfile()
        assertNull(m.profile.value!!.fullName)
        assertNull(m.profile.value!!.fingerprint)
    }

    @Test
    fun theDisplayNameIsSetWithTheVersionAndOnlyIt() = runTest {
        m.refreshProfile()
        m.setDisplayName("  Countess ")
        assertEquals(listOf(4L to "Countess"), ops.sets)
        assertEquals("Countess", m.profile.value!!.displayName)
        m.setDisplayName("")
        assertEquals(5L to "", ops.sets.last())
    }

    @Test
    fun aNameChangeSendsTheNormalisedNamesAndKeepsTheRequest() = runTest {
        val r = m.changeName("246810", "pw", " Ada ", "King")
        assertEquals(listOf("246810", "pw", "Ada", "King"), ops.nameSets.single())
        r as NameChangeOutcome.Requested
        assertEquals(NameRequestState.PENDING, r.request.state)
        assertEquals(7, r.request.seq)
        assertEquals(Instant.parse("2026-10-07T12:00:00Z"), r.request.requestedAt)
        assertEquals(listOf(r.request), requests)
    }

    @Test
    fun namesThatBreakTheRuleAreNotSent() = runTest {
        assertEquals(NameChangeOutcome.Invalid, m.changeName("246810", "pw", "Ada1", "King"))
        assertEquals(NameChangeOutcome.Invalid, m.changeName("246810", "pw", "Ada", ""))
        assertTrue(ops.nameSets.isEmpty())
    }

    @Test
    fun tooSoonCarriesTheDate() = runTest {
        ops.error = "too_soon"
        ops.errorBody = o("""{"allowed_after":"2026-11-01T00:00:00Z"}""")
        assertEquals(NameChangeOutcome.TooSoon(Instant.parse("2026-11-01T00:00:00Z")), m.changeName("246810", "pw", "Ada", "King"))
        ops.errorBody = null
        assertEquals(NameChangeOutcome.TooSoon(null), m.changeName("246810", "pw", "Ada", "King"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun theOwnerChecksAnswers() = runTest {
        ops.error = "bad_pin"
        assertEquals(NameChangeOutcome.BadPin(8), m.changeName("246810", "pw", "Ada", "King"))
        ops.error = "bad_password"
        assertEquals(NameChangeOutcome.BadPassword(8), m.changeName("246810", "pw", "Ada", "King"))
        assertEquals(2, checksLeftCalls)
        ops.error = "backoff"
        ops.errorBody = o("""{"retry_after":30}""")
        assertEquals(NameChangeOutcome.Backoff(30), m.changeName("246810", "pw", "Ada", "King"))
        ops.error = "bad_request"
        assertEquals(NameChangeOutcome.Invalid, m.changeName("246810", "pw", "Ada", "King"))
        ops.error = "owner_check_required"
        assertEquals(
            NameChangeOutcome.Failed(FailureKind.OWNER_CHECK_REQUIRED, "owner_check_required"),
            m.changeName("246810", "pw", "Ada", "King"),
        )
        ops.error = "credential_frozen"
        assertEquals(
            NameChangeOutcome.Failed(FailureKind.CREDENTIAL_FROZEN, "credential_frozen"),
            m.changeName("246810", "pw", "Ada", "King"),
        )
    }

    @Test
    fun requestStates() {
        assertEquals(
            NameRequestView(3, "A", "B", null, NameRequestState.REFUSED, "too_soon"),
            NameRequestView.of(NameRequest(3, "A", "B", null, "refused", "too_soon")),
        )
        // reason only with refused (0.19.0).
        assertNull(NameRequestView.of(NameRequest(3, "A", "B", null, "applied", "too_soon"))!!.reason)
        assertNull(NameRequestView.of(NameRequest(3, "A", "B", null, "lost")))
    }
}

/** `account.get` after `sync.event{account.changed}` (VAULT-MESSAGING 0.19.0 §10.1, §10.2). */
class AccountUpdateTest {
    private val snap = com.vettid.core.vault.AccountSnapshot(asOf = "2026-10-07T10:00:00Z", firstName = "Ada", lastName = "Lovelace")
    private val pending = NameRequest(3, "Ada", "King", "2026-10-07T11:00:00.000Z", "pending")

    @Test
    fun aNameRequestChangeAloneIsKeptThoughTheVersionRepeats() {
        val applied = pending.copy(state = "applied")
        // Same snapshot (same as_of, same version 4), only the request changed.
        val u = accountUpdate(snap, pending, com.vettid.core.vault.AccountView(snap, 4, nameRequest = applied))!!
        assertEquals(snap, u.snapshot)
        assertEquals(applied, u.nameRequest)
    }

    @Test
    fun nothingNewIsNothing() {
        assertNull(accountUpdate(snap, pending, com.vettid.core.vault.AccountView(snap, 4, nameRequest = pending)))
        // An older snapshot is not taken.
        val older = snap.copy(asOf = "2026-10-06T10:00:00Z", firstName = "Old")
        assertNull(accountUpdate(snap, pending, com.vettid.core.vault.AccountView(older, 3, nameRequest = pending)))
    }

    @Test
    fun aNewerSnapshotBringsTheNewNames() {
        val newer = snap.copy(asOf = "2026-10-08T10:00:00Z", lastName = "King")
        val applied = pending.copy(state = "applied")
        val u = accountUpdate(snap, pending, com.vettid.core.vault.AccountView(newer, 5, nameRequest = applied))!!
        assertEquals("King", u.snapshot!!.lastName)
        assertEquals(applied, u.nameRequest)
    }
}
