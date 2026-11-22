// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.data.devices

import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultOpException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ANDROID-PLAN 0.1.137 AA7 "Saving": the password for a widening, the answers, a read-only page's save. */
class ItemWaysManagerTest {
    private val now = Instant.parse("2026-11-22T12:00:00Z")

    private class Ops : ItemWaysOps {
        val sets = mutableListOf<JsonObject>()
        var refuse: MutableList<String> = mutableListOf()
        var detail = ""
        var answer = """{"agent_id":"a1","item_id":"i1","version":2,"ways":["use","reveal"]}"""
        var listed = """{"items":[{"agent_id":"a1","item_id":"i1","version":1,"ways":["use"]}]}"""
        var listGate: CompletableDeferred<Unit>? = null
        var setGate: CompletableDeferred<Unit>? = null
        val setEntered = CompletableDeferred<Unit>()

        override suspend fun list(agentId: String, itemId: String?): ByteArray {
            listGate?.await()
            return listed.toByteArray()
        }

        override suspend fun set(body: JsonObject): ByteArray {
            sets += body
            setEntered.complete(Unit)
            setGate?.await()
            refuse.removeFirstOrNull()?.let { throw VaultOpException("leash.item.set", it, detail) }
            return answer.toByteArray()
        }
    }

    private class Window(var until: Instant? = null) {
        val calls = mutableListOf<String>()
        var unlockFails: VaultFailure? = null
        var lockFails = false
    }

    private fun manager(ops: Ops, w: Window) = ItemWaysManager(
        ops = { ops },
        windowUntil = { w.until },
        openWindow = { p ->
            w.calls += "unlock:$p"
            w.unlockFails?.let { throw it }
        },
        closeWindow = {
            w.calls += "lock"
            if (w.lockFails) throw VaultFailure(FailureKind.NETWORK)
        },
        unlockTtl = { 300 },
        now = { now },
    )

    private fun read(json: String) = ItemWaysRules.one(json.toByteArray())!!

    private val useOnly = read("""{"agent_id":"a1","item_id":"i1","version":1,"ways":["use"]}""")
    private val withReveal = useOnly.ways!!.copy(ways = setOf(ItemWay.USE, ItemWay.REVEAL))

    @Test
    fun aWideningAsksForThePasswordAndLocksTheWindowItOpened() = runTest {
        val ops = Ops()
        val w = Window()
        val m = manager(ops, w)
        assertEquals(ItemWaysSave.NeedsPassword, m.save(useOnly, withReveal, password = null))
        assertTrue(ops.sets.isEmpty() && w.calls.isEmpty())
        val saved = m.save(useOnly, withReveal, "pw")
        assertTrue(saved is ItemWaysSave.Saved)
        assertEquals(listOf("unlock:pw", "lock"), w.calls)
        assertEquals(VaultJson.bytes(withReveal.setBody()).decodeToString(), VaultJson.bytes(ops.sets.single()).decodeToString())
        assertEquals(2L, (saved as ItemWaysSave.Saved).read.ways!!.version)
    }

    @Test
    fun aWindowAlreadyOpenIsLeftOpenButThePasswordIsStillAsked() = runTest {
        val w = Window(until = now.plusSeconds(300))
        val m = manager(Ops(), w)
        assertEquals(ItemWaysSave.NeedsPassword, m.save(useOnly, withReveal, null))
        m.save(useOnly, withReveal, "pw")
        assertEquals(listOf("unlock:pw"), w.calls)
        // A window about to end counts as closed (the phone's clock may be off): locked after.
        val w2 = Window(until = now.plusSeconds(10))
        manager(Ops(), w2).save(useOnly, withReveal, "pw")
        assertEquals(listOf("unlock:pw", "lock"), w2.calls)
    }

    @Test
    fun aNarrowingIsSentWithoutThePassword() = runTest {
        val ops = Ops()
        val w = Window()
        val cur = read("""{"agent_id":"a1","item_id":"i1","version":1,"ways":["use","reveal"]}""")
        val saved = manager(ops, w).save(cur, cur.ways!!.copy(ways = setOf(ItemWay.USE)), null)
        assertTrue(saved is ItemWaysSave.Saved)
        assertTrue(w.calls.isEmpty())
        assertEquals(1, ops.sets.size)
    }

    @Test
    fun credentialLockedPromptsOnceThenSaysItLockedAgain() = runTest {
        val cur = read("""{"agent_id":"a1","item_id":"i1","version":1,"ways":["use","reveal"]}""")
        val next = cur.ways!!.copy(ways = setOf(ItemWay.USE))
        val ops = Ops().apply { refuse = mutableListOf("credential_locked", "credential_locked") }
        val m = manager(ops, Window())
        assertEquals(ItemWaysSave.CredentialLocked, m.save(cur, next, null))
        assertEquals(ItemWaysSave.LockedAgain, m.save(cur, next, "pw", resent = true))
        // The resend with the password opens the window and locks it after, as a widening does.
        val w = Window()
        val ops2 = Ops().apply { refuse = mutableListOf("credential_locked") }
        val m2 = manager(ops2, w)
        assertEquals(ItemWaysSave.CredentialLocked, m2.save(cur, next, null))
        assertTrue(m2.save(cur, next, "pw", resent = true) is ItemWaysSave.Saved)
        assertEquals(listOf("unlock:pw", "lock"), w.calls)
        assertEquals(2, ops2.sets.size)
    }

    @Test
    fun aCancelledSaveStillLocksTheWindow() = runTest {
        val ops = Ops().apply { setGate = CompletableDeferred() }
        val w = Window()
        val job = launch { manager(ops, w).save(useOnly, withReveal, "pw") }
        ops.setEntered.await() // the save is on the IO dispatcher, waiting for the vault
        assertEquals(listOf("unlock:pw"), w.calls)
        job.cancel()
        job.join()
        assertEquals(listOf("unlock:pw", "lock"), w.calls)
    }

    @Test
    fun aLockThatFailsIsTold() = runTest {
        val w = Window().apply { lockFails = true }
        val m = manager(Ops(), w)
        assertNull(m.windowNotClosed.value)
        assertTrue(m.save(useOnly, withReveal, "pw") is ItemWaysSave.Saved)
        assertEquals(300L, m.windowNotClosed.value!!.ttlSeconds)
        w.lockFails = false
        m.closeWindowNow()
        assertNull(m.windowNotClosed.value)
    }

    @Test
    fun settingsOfAnotherItemAskForThePassword() = runTest {
        val cur = read("""{"agent_id":"a1","item_id":"i1","version":1,"ways":["use","reveal"]}""")
        val other = cur.ways!!.copy(itemId = "i2", ways = setOf(ItemWay.USE))
        assertEquals(ItemWaysSave.NeedsPassword, manager(Ops(), Window()).save(cur, other, null))
    }

    @Test
    fun aReadAnsweredAfterAClearIsNotKept() = runTest {
        val ops = Ops().apply { listGate = CompletableDeferred() }
        val m = manager(ops, Window())
        var failure: VaultFailure? = null
        val job = launch { failure = runCatching { m.list("a1") }.exceptionOrNull() as? VaultFailure }
        advanceUntilIdle()
        m.clear()
        ops.listGate!!.complete(Unit)
        job.join()
        assertEquals("voided_by_lock", failure!!.code)
        assertTrue(m.items.value.isEmpty())
        // The same for one item's read.
        ops.listGate = CompletableDeferred()
        val job2 = launch { failure = runCatching { m.read("a1", "i1") }.exceptionOrNull() as? VaultFailure }
        advanceUntilIdle()
        m.clear()
        ops.listGate!!.complete(Unit)
        job2.join()
        assertEquals("voided_by_lock", failure!!.code)
    }

    @Test
    fun oneItemsReadUpdatesTheHeldList() = runTest {
        val ops = Ops()
        val m = manager(ops, Window())
        m.list("a1")
        ops.listed = """{"items":[{"agent_id":"a1","item_id":"i1","version":7,"ways":["use","reveal"]}]}"""
        assertEquals(7L, m.read("a1", "i1")!!.ways!!.version)
        assertEquals(7L, m.items.value["a1"]!!.single().ways!!.version)
        ops.listed = """{"items":[]}"""
        assertNull(m.read("a1", "i1"))
        assertTrue(m.items.value["a1"]!!.isEmpty())
        // An answer that is not a list is a failure, never "not included".
        ops.listed = "{}"
        assertEquals("unreadable_settings", (runCatching { m.read("a1", "i1") }.exceptionOrNull() as VaultFailure).code)
    }

    @Test
    fun turningOffAWayOnAnEditablePageDropsItsSettings() = runTest {
        val cur = read("""{"agent_id":"a1","item_id":"i1","version":1,"ways":["use","proxy"],"proxy":{"hosts":["a.example.com"],"header":"X"}}""")
        val ops = Ops()
        manager(ops, Window()).save(cur, cur.ways!!.copy(ways = setOf(ItemWay.USE), proxy = null), null)
        assertFalse(ops.sets.single().containsKey("proxy"))
    }

    @Test
    fun eachAnswer() = runTest {
        val cases = mapOf(
            "conflict" to ItemWaysSave.Conflict,
            "not_found" to ItemWaysSave.NotIncluded,
            "owner_check_required" to ItemWaysSave.OwnerCheck,
            "bad_request" to ItemWaysSave.BadRequest("program: refused name"),
        )
        for ((code, want) in cases) {
            val ops = Ops().apply { refuse = mutableListOf(code); detail = "program: refused name" }
            assertEquals(code, want, manager(ops, Window()).save(useOnly, withReveal, "pw"))
        }
        val unreadable = Ops().apply { answer = """{"agent_id":"a1","item_id":"i1","ways":["use"],"x":1}""" }
        val r = manager(unreadable, Window()).save(useOnly, withReveal, "pw")
        assertTrue("an answer that does not read strictly is still the vault's", r is ItemWaysSave.Saved && r.read.readOnly)
    }

    @Test
    fun theUnlocksRefusals() = runTest {
        val cases = mapOf(
            FailureKind.BAD_PASSWORD to ItemWaysSave.PasswordRefused::class,
            FailureKind.BACKOFF to ItemWaysSave.PasswordRefused::class,
            FailureKind.CREDENTIAL_FROZEN to ItemWaysSave.AlarmOpen::class,
            FailureKind.ROTATION_REQUIRED to ItemWaysSave.AlarmOpen::class,
            FailureKind.OWNER_CHECK_REQUIRED to ItemWaysSave.OwnerCheck::class,
        )
        for ((kind, want) in cases) {
            val ops = Ops()
            val w = Window().apply { unlockFails = VaultFailure(kind) }
            val r = manager(ops, w).save(useOnly, withReveal, "pw")
            assertEquals(kind.name, want, r::class)
            assertTrue("$kind sent the settings", ops.sets.isEmpty())
            assertEquals("$kind: a definite refusal opened nothing to lock", listOf("unlock:pw"), w.calls)
        }
        // A failure that may have come after the window opened: locked.
        val w = Window().apply { unlockFails = VaultFailure(FailureKind.NETWORK) }
        manager(Ops(), w).save(useOnly, withReveal, "pw")
        assertEquals(listOf("unlock:pw", "lock"), w.calls)
    }

    @Test
    fun aReadOnlyPageTurnsAWayOffWithoutThePrompt() = runTest {
        val cur = read("""{"agent_id":"a1","item_id":"i1","version":4,"ways":["use","reveal","run"],"run":{"programs":[],"each":true,"x":1}}""")
        assertTrue(cur.readOnly)
        val ops = Ops()
        val w = Window()
        val m = manager(ops, w)
        assertEquals("unreadable_settings", (m.save(cur, withReveal, "pw") as ItemWaysSave.Failed).failure.code)
        assertTrue(ops.sets.isEmpty() && w.calls.isEmpty())
        assertTrue(m.turnOff(cur, "run") is ItemWaysSave.Saved)
        assertFalse(ops.sets.single().containsKey("run"))
        val locked = Ops().apply { refuse = mutableListOf("credential_locked") }
        assertEquals(ItemWaysSave.ReadOnlyLocked, manager(locked, w).turnOff(cur, "reveal"))
        assertEquals(1, locked.sets.size)
        assertTrue(w.calls.isEmpty())
    }

    /** An answer that is not the settings sent (another item, or `{}`) is not taken as saved. */
    @Test
    fun anAnswerForAnotherItemIsNotSaved() = runTest {
        for (a in listOf("""{"agent_id":"a1","item_id":"i2","version":2,"ways":["use"]}""", "{}")) {
            val ops = Ops().apply { answer = a }
            val r = manager(ops, Window()).save(useOnly, withReveal, "pw")
            assertEquals(a, "unreadable_settings", (r as ItemWaysSave.Failed).failure.code)
        }
    }

    /** Settings of another version than the one read can't be judged: a widening. */
    @Test
    fun anotherVersionIsJudgedAWidening() = runTest {
        val cur = read("""{"agent_id":"a1","item_id":"i1","version":1,"ways":["use","reveal"]}""")
        val next = cur.ways!!.copy(version = 2, ways = setOf(ItemWay.USE))
        assertEquals(ItemWaysSave.NeedsPassword, manager(Ops(), Window()).save(cur, next, null))
    }

    @Test
    fun theVaultsTextIsNeverPrinted() {
        assertEquals("BadRequest", ItemWaysSave.BadRequest("secret detail").toString())
    }

    @Test
    fun theListIsHeldUntilCleared() = runTest {
        val m = manager(Ops(), Window())
        assertEquals(1, m.list("a1").size)
        assertEquals(1, m.items.value["a1"]!!.size)
        m.save(useOnly, withReveal, "pw")
        assertEquals(2L, m.items.value["a1"]!!.single().ways!!.version)
        m.clear()
        assertTrue(m.items.value.isEmpty())
    }
}
