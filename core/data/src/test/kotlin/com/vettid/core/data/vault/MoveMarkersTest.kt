package com.vettid.core.data.vault

import com.vettid.core.altchan.AltState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ANDROID-PLAN 0.1.54 points 2 and 4: what sets the marker's `failed`, and the `vault.release` that clears it. */
class MoveMarkersTest {
    private val n = "a3".repeat(48)
    private val n1 = "c4".repeat(48)

    @Test
    fun theNewReleaseFailingSetsFailed() {
        assertTrue(MoveMarkers.setsFailed(FailureKind.VAULT_UNAVAILABLE, "release_starting", sealed = false))
        assertTrue(MoveMarkers.setsFailed(FailureKind.RELEASE_ENDED, "release_unavailable", sealed = false))
        assertTrue(MoveMarkers.setsFailed(FailureKind.ENCLAVE, "enclave", sealed = false))
        assertTrue(MoveMarkers.setsFailed(FailureKind.NO_RESPONSE, null, sealed = false))
        assertTrue(MoveMarkers.setsFailed(FailureKind.ROUTE_PENDING, "route_pending", sealed = false))
        assertTrue(MoveMarkers.setsFailed(FailureKind.OTHER, "retry", sealed = true))
        assertTrue(MoveMarkers.setsFailed(FailureKind.OTHER, "internal", sealed = true))
    }

    @Test
    fun aWrongPinTheBackoffOrNoConnectionDoNot() {
        assertFalse(MoveMarkers.setsFailed(FailureKind.OTHER, "bad_pin", sealed = true))
        assertFalse(MoveMarkers.setsFailed(FailureKind.OTHER, "backoff", sealed = true))
        assertFalse(MoveMarkers.setsFailed(FailureKind.BAD_PIN, null, sealed = false))
        assertFalse(MoveMarkers.setsFailed(FailureKind.BACKOFF, null, sealed = false))
        assertFalse(MoveMarkers.setsFailed(FailureKind.NETWORK, null, sealed = false))
        // Nor what never reached the vault for another reason, or this phone's own answers.
        assertFalse(MoveMarkers.setsFailed(FailureKind.VAULT_UNAVAILABLE, "vault_unavailable", sealed = false))
        assertFalse(MoveMarkers.setsFailed(FailureKind.SERVICE_PAUSED, null, sealed = false))
        assertFalse(MoveMarkers.setsFailed(FailureKind.CHANGE_UNRESOLVED, null, sealed = false))
        assertFalse(MoveMarkers.setsFailed(FailureKind.OTHER, "unreadable_result", sealed = false))
        assertFalse(MoveMarkers.setsFailed(FailureKind.OTHER, "device_secret", sealed = true))
        assertFalse(MoveMarkers.setsFailed(FailureKind.OTHER, "signer_refused", sealed = true))
    }

    @Test
    fun theMarkerReadsFromTheStoredState() {
        assertNull(MoveUnconfirmed.of(AltState(release = n1, releaseNumber = 4)))
        val stored = AltState(release = n1, releaseNumber = 4, previousRelease = n, previousReleaseNumber = 3, moveFailed = true)
        val m = MoveUnconfirmed.of(stored)!!
        assertEquals(MoveUnconfirmed(3, n, 4, n1, failed = true), m)
        assertEquals(3L, m.from.number)
        assertEquals(n1, m.to.pcr0)
    }

    @Test
    fun vaultReleaseNamingN1ConfirmsTheMove() {
        val m = MoveUnconfirmed(3, n, 4, n1, failed = false)
        assertTrue(MoveMarkers.confirmedBy(m, n1, 4))
        assertFalse(MoveMarkers.confirmedBy(m, n, 3))
        assertFalse(MoveMarkers.confirmedBy(m, n1, 5))
        assertFalse(MoveMarkers.confirmedBy(null, n1, 4))
    }
}
