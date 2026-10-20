// Fixtures stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.altchan

import com.vettid.core.attestation.manifest.Release
import com.vettid.core.attestation.manifest.ReleaseStatus
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.crypto.json.StrictJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** ANDROID-PLAN 0.1.54 "The way back to release N after leaving or a restart": the stored marker and the route's wait. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoveMarkerTest {
    private val n = TestSupport.pcr0
    private val n1 = "c4".repeat(48)
    private val older = "d5".repeat(48)

    private fun rel(pcr0: String, number: Long) =
        Release(number, pcr0, TestSupport.pcr1, TestSupport.pcr2, "k", ReleaseStatus.ACTIVE, Instant.EPOCH, null, "https://vettid.org/r/$number")

    private val onN = AltState(release = n, releaseNumber = 3, stateSeq = 2, manifestSerial = 7)
    private val toN1 = PendingUnlock(Ulid.new(), n, 3, 7, 4)
    private val movedState = AltRequests.applyUnlock(onN, toN1, UnlockResult(true, release = n, releaseNumber = 3, update = UnlockResult.Update(n1, "moved", null)))

    @Test
    fun movedStoresTheMarkerWithTheNewRelease() {
        assertEquals(n1, movedState.release)
        assertEquals(4L, movedState.releaseNumber)
        assertEquals(MovedFrom(n, 3), movedState.movedFrom)
        assertFalse(movedState.moveFailed)
        // Kept across a restart (the device state is this marshalled form).
        val back = AltState.parse(StrictJson.parseObject(movedState.copy(moveFailed = true).marshal()))
        assertEquals(MovedFrom(n, 3), back.movedFrom)
        assertTrue(back.moveFailed)
        // A state written before the marker had `failed` reads as not failed.
        assertFalse(AltState.parse(StrictJson.parseObject("""{"release":"$n1","release_number":4,"previous_release":"$n","previous_release_number":3}""")).moveFailed)
    }

    @Test
    fun anUnlockOnTheNewReleaseClearsIt() {
        val p = PendingUnlock(Ulid.new(), n1, 4, 7, 0)
        val s = AltRequests.applyUnlock(movedState.copy(moveFailed = true), p, UnlockResult(true, release = n1, releaseNumber = 4))
        assertNull(s.movedFrom)
        assertFalse(s.moveFailed)
        assertEquals(n1, s.release)
    }

    @Test
    fun aWrongPinOrBackoffOnTheNewReleaseKeepsIt() {
        val p = PendingUnlock(Ulid.new(), n1, 4, 7, 0)
        for (code in listOf("bad_pin", "backoff")) {
            val s = AltRequests.applyUnlock(movedState, p, UnlockResult(false, code = code))
            assertEquals(MovedFrom(n, 3), s.movedFrom)
            assertFalse(s.moveFailed)
        }
    }

    @Test
    fun theWayBackAbandonedOrRefusedAsADowngradeRecordsNAndClearsIt() {
        val back = PendingUnlock(Ulid.new(), n, 3, 7, 3)
        for (u in listOf(UnlockResult.Update(n, "abandoned", null), UnlockResult.Update(n, "refused", "downgrade"))) {
            val s = AltRequests.applyUnlock(movedState.copy(moveFailed = true), back, UnlockResult(true, release = n, releaseNumber = 3, update = u))
            assertNull(s.movedFrom)
            assertEquals(n, s.release)
            assertEquals(3L, s.releaseNumber)
        }
    }

    @Test
    fun theWayBackAnsweredWrongReleaseClearsItAndKeepsTheNewRelease() {
        val back = PendingUnlock(Ulid.new(), n, 3, 7, 3)
        val s = AltRequests.applyUnlock(movedState.copy(moveFailed = true), back, UnlockResult(false, code = "wrong_release"))
        assertNull(s.movedFrom)
        assertEquals(n1, s.release)
        // `wrong_release` from N+1 itself is not the way back: kept.
        val p = PendingUnlock(Ulid.new(), n1, 4, 7, 0)
        assertEquals(MovedFrom(n, 3), AltRequests.applyUnlock(movedState, p, UnlockResult(false, code = "wrong_release")).movedFrom)
    }

    @Test
    fun aRouteNamingNForTwoFetchesThenN1GoesOnWithN1() = runTest {
        val routes = ArrayDeque(listOf(rel(n, 3), rel(n1, 4)))
        var fetches = 0
        val r = RouteWait.awayFrom(MovedFrom(n, 3), rel(n, 3), { it }) {
            fetches++
            routes.removeFirst()
        }
        assertEquals(n1, r.pcr0)
        assertEquals(2, fetches)
        assertEquals(15_000L, currentTime) // 5 s, then 10 s
    }

    @Test
    fun aRouteNamingNForEveryFetchIsRoutePendingAfterAboutAMinute() = runTest {
        var fetches = 0
        val thrown = runCatching { RouteWait.awayFrom(MovedFrom(n, 3), rel(n, 3), { it }) { fetches++; rel(n, 3) } }.exceptionOrNull()
        assertTrue(thrown is AltRefusedException && thrown.reason == AltRefusedException.Reason.ROUTE_PENDING)
        assertEquals(5, fetches)
        assertEquals(60_000L, currentTime) // 5 + 10 + 15 + 15 + 15 s
    }

    @Test
    fun anotherOlderReleaseOrTheNewOneIsReturnedAtOnce() = runTest {
        var fetches = 0
        // Not the marker's `from` (another PCR0, or N's number with another PCR0): returned as it is, for the rollback check.
        assertEquals(older, RouteWait.awayFrom(MovedFrom(n, 3), rel(older, 2), { it }) { fetches++; rel(n1, 4) }.pcr0)
        assertEquals(older, RouteWait.awayFrom(MovedFrom(n, 3), rel(older, 3), { it }) { fetches++; rel(n1, 4) }.pcr0)
        assertEquals(n1, RouteWait.awayFrom(MovedFrom(n, 3), rel(n1, 4), { it }) { fetches++; rel(n1, 4) }.pcr0)
        // No marker: nothing waits.
        assertEquals(n, RouteWait.awayFrom(null, rel(n, 3), { it }) { fetches++; rel(n1, 4) }.pcr0)
        assertEquals(0, fetches)
        assertEquals(0L, currentTime)
    }
}
