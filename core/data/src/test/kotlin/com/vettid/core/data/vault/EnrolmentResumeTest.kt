package com.vettid.core.data.vault

import com.vettid.core.altchan.EnrollAnswer
import com.vettid.core.altchan.PendingEnroll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * (A6 audit N3) Enrollment resumes instead of posting twice. [World] is what outlives a process: the stored pending
 * request, the member API's slots and the enclave's vaults, and the relay's `vault.enrolled`. Each test "kills" the
 * process at one step (a fresh [EnrolmentResume] over the same world, often without the PIN) and checks that no second
 * enrollment is posted over a request that may have made the vault.
 */
class EnrolmentResumeTest {
    private class World {
        var now = 1_000_000_000L

        /** The stored pending request (with its nonce), as the device keeps it. */
        var pending: PendingEnroll? = null

        /** Applied `vault.enrolled`. */
        var pinned = false

        /** The slots the member API holds: request id to answer. A missing id is a `404`. */
        val slots = mutableMapOf<String, EnrollAnswer>()

        /** `vault.enrolled` waiting on the relay for the request that made the vault. */
        var enrolledOnRelay: String? = null

        /** Every request posted, and the PIN each carried. */
        val posted = mutableListOf<Pair<String, String>>()

        /** The PIN the vault holds (the request that made it). */
        var vaultPin: String? = null

        /** The next step to fail with a transport error ("post", "poll"); the next post to stop after storing ("kill"). */
        var failNext: String? = null
        var nextId = 0

        /** How the enclave answers the next request: ok, a refusal code, or "lost" (made, answer never seen). */
        var enclave: String = "ok"
    }

    private class Killed : RuntimeException("process killed")

    private fun ops(w: World) = object : EnrolmentResume.Ops {
        override fun vaultPinned() = w.pinned
        override fun pending() = w.pending
        override suspend fun answer(requestId: String): EnrollAnswer {
            if (w.failNext == "poll") {
                w.failNext = null
                throw IOException("poll dropped")
            }
            return w.slots[requestId] ?: EnrollAnswer.Gone
        }

        override suspend fun markAnswered() {
            w.pending = w.pending?.copy(answered = true)
        }

        override suspend fun drop() {
            w.pending = null
        }

        override suspend fun awaitEnrolled(): Boolean {
            val id = w.enrolledOnRelay ?: return false
            if (w.pending?.requestId != id) return false // its nonce is gone: dropped as unverifiable
            w.pinned = true
            w.pending = null
            return true
        }

        override suspend fun post(pin: String): EnrolmentResume.Posted {
            val id = "r${++w.nextId}"
            w.pending = PendingEnroll(id, w.now, answered = false) // stored before the request goes out
            if (w.failNext == "kill-before-post") {
                w.failNext = null
                throw Killed()
            }
            w.posted += id to pin
            val made = w.enclave == "ok" || w.enclave == "lost"
            if (made && w.vaultPin != null) {
                w.slots[id] = EnrollAnswer.Answered(false, "vault_exists")
            } else if (made) {
                w.vaultPin = pin
                w.slots[id] = EnrollAnswer.Answered(true, null)
                w.enrolledOnRelay = id
            } else {
                w.slots[id] = EnrollAnswer.Answered(false, w.enclave)
            }
            if (w.failNext == "kill-after-post") {
                w.failNext = null
                throw Killed()
            }
            if (w.enclave == "lost" || w.failNext == "poll") {
                w.failNext = null
                throw IOException("answer lost")
            }
            val a = w.slots.getValue(id) as EnrollAnswer.Answered
            return EnrolmentResume.Posted(a.ok, a.code)
        }
    }

    private fun run(w: World, pin: String?) = runBlocking { EnrolmentResume(ops(w)) { w.now }.run(pin) }

    @Test
    fun aFirstEnrollmentPostsOnceAndPins() {
        val w = World()
        assertEquals(EnrolmentResume.Result.Pinned, run(w, "246802"))
        assertEquals(listOf("r1" to "246802"), w.posted)
        assertNull(w.pending)
    }

    /** The answer is lost (a dropped poll): "Try again" with the same PIN reads the slot, and posts nothing. */
    @Test
    fun aLostAnswerThenTryAgainDoesNotPostASecondEnrollment() {
        val w = World()
        w.enclave = "lost"
        assertThrows(IOException::class.java) { run(w, "246802") }
        w.enclave = "ok"
        assertEquals(EnrolmentResume.Result.Pinned, run(w, "246802"))
        assertEquals(1, w.posted.size)
    }

    /** Killed after the request was stored and before it was posted: the slot is gone while young, so it is posted once. */
    @Test
    fun killedBeforeThePostPostsOnceAfterTheRestart() {
        val w = World()
        w.failNext = "kill-before-post"
        assertThrows(Killed::class.java) { run(w, "246802") }
        assertTrue(w.posted.isEmpty())
        // The restart has no PIN: nothing outstanding can make a vault, so the member is asked for one.
        assertEquals(EnrolmentResume.Result.NeedsPin, run(w, null))
        assertNull(w.pending)
        assertEquals(EnrolmentResume.Result.Pinned, run(w, "135790"))
        assertEquals(listOf("r2" to "135790"), w.posted)
    }

    /** Killed after the post, before its answer was read: the restart (no PIN) reads the slot and pins the vault. */
    @Test
    fun killedAfterThePostResumesFromTheSlotWithoutAPin() {
        val w = World()
        w.failNext = "kill-after-post"
        assertThrows(Killed::class.java) { run(w, "246802") }
        assertEquals(EnrolmentResume.Result.Pinned, run(w, null))
        assertEquals(1, w.posted.size)
        assertEquals("246802", w.vaultPin)
    }

    /** Killed after an `ok` answer, before `vault.enrolled` was applied: only it is awaited. */
    @Test
    fun killedAfterTheOkAwaitsVaultEnrolledOnly() {
        val w = World()
        val relay = "r1"
        // The `ok` answer was stored; vault.enrolled has not reached the relay yet (a cold host).
        w.pending = PendingEnroll(relay, w.now, answered = true)
        assertThrows(EnrolmentResume.Waiting::class.java) { run(w, null) }
        assertEquals("still outstanding", relay, w.pending?.requestId)
        w.enrolledOnRelay = relay
        assertEquals(EnrolmentResume.Result.Pinned, run(w, null))
        assertTrue("nothing posted", w.posted.isEmpty())
    }

    /** `vault.enrolled` was applied before the kill: nothing is read or posted. */
    @Test
    fun killedAfterVaultEnrolledGoesStraightToTheHandshake() {
        val w = World()
        w.pinned = true
        w.failNext = "poll"
        assertEquals(EnrolmentResume.Result.Pinned, run(w, "000000"))
        assertTrue(w.posted.isEmpty())
    }

    /** A transport failure while the slot is read again keeps the request outstanding; nothing is posted. */
    @Test
    fun aFailedReadOfTheSlotKeepsTheRequest() {
        val w = World()
        w.failNext = "kill-after-post"
        assertThrows(Killed::class.java) { run(w, "246802") }
        w.failNext = "poll"
        assertThrows(IOException::class.java) { run(w, "999999") }
        assertEquals("r1", w.pending?.requestId)
        assertEquals(EnrolmentResume.Result.Pinned, run(w, "999999"))
        assertEquals("the vault keeps the PIN of the request that made it", "246802", w.vaultPin)
        assertEquals(1, w.posted.size)
    }

    /** A request that left the queue unprocessed (`expired`, `etk_unknown`) made nothing: a new one is posted. */
    @Test
    fun anUnprocessedRequestIsPostedAgain() {
        val w = World()
        w.pending = PendingEnroll("r0", w.now, answered = false)
        w.slots["r0"] = EnrollAnswer.NotProcessed
        assertEquals(EnrolmentResume.Result.Pinned, run(w, "246802"))
        assertEquals(listOf("r1" to "246802"), w.posted)
    }

    /** The slot is gone long after the post: its vault.enrolled is awaited first; it comes, so nothing is posted. */
    @Test
    fun anOldGoneSlotWaitsForVaultEnrolledFirst() {
        val w = World()
        w.pending = PendingEnroll("r0", w.now, answered = false)
        w.vaultPin = "246802"
        w.enrolledOnRelay = "r0"
        w.now += 20 * 60 * 1000
        assertEquals(EnrolmentResume.Result.Pinned, run(w, null))
        assertTrue(w.posted.isEmpty())
    }

    /** The slot is gone long after the post and no vault.enrolled comes: nothing was made, a new request is posted. */
    @Test
    fun anOldGoneSlotWithoutVaultEnrolledPostsAgain() {
        val w = World()
        w.pending = PendingEnroll("r0", w.now, answered = false)
        w.now += 20 * 60 * 1000
        assertEquals(EnrolmentResume.Result.NeedsPin, run(w, null))
        assertEquals(EnrolmentResume.Result.Pinned, run(w, "246802"))
        assertEquals(1, w.posted.size)
    }

    /** A refusal is the member's answer: `vault_exists` for a vault this phone never made. The request goes. */
    @Test
    fun aRefusalEndsTheRequest() {
        val w = World()
        w.vaultPin = "111111" // another phone's vault
        val e = assertThrows(EnrolmentResume.Refused::class.java) { run(w, "246802") }
        assertEquals("vault_exists", e.code)
        assertNull(w.pending)
    }

    /** A refusal read from the slot after a restart is the same answer. */
    @Test
    fun aRefusalReadFromTheSlotEndsTheRequest() {
        val w = World()
        w.enclave = "attestation"
        w.failNext = "kill-after-post"
        assertThrows(Killed::class.java) { run(w, "246802") }
        val e = assertThrows(EnrolmentResume.Refused::class.java) { run(w, null) }
        assertEquals("attestation", e.code)
        assertNull(w.pending)
    }
}
