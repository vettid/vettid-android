package com.vettid.core.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * (ANDROID-PLAN 0.1.54, 0.1.78) The bound from this phone's own `vault.delete`: kept while a request is out (or its answer is
 * still being decided), whatever a clear of a 410-only state does meanwhile; gone once the last one is done and no
 * deletion is up.
 */
class DeleteBoundTest {
    private val lock = Any()
    private var nowMs = 1_000_000L
    private val bound = DeleteBound(lock) { nowMs }
    private var deleting = false

    @Test
    fun aRequestSetsTheBoundFromItsExp() {
        bound.begin(600_000L)
        assertEquals(1_600_000L, bound.expMs)
        assertEquals(1, bound.requestsOut)
    }

    /** Refused, failed or cancelled with nothing up: no bound is kept. */
    @Test
    fun theLastRequestDoneWithNothingUpDropsTheBound() {
        bound.begin(600_000L)
        bound.end { deleting }
        assertNull(bound.expMs)
        assertEquals(0, bound.requestsOut)
    }

    /** Answered and "being deleted" started from it: the bound stays with the state. */
    @Test
    fun aRequestDoneWithADeletionUpKeepsTheBound() {
        bound.begin(600_000L)
        deleting = true
        bound.end { deleting }
        assertEquals(1_600_000L, bound.expMs)
    }

    /** Another request still out: the first one's end keeps the bound for it (the latest `exp`). */
    @Test
    fun anotherRequestOutKeepsTheBound() {
        bound.begin(600_000L)
        nowMs += 5_000L
        bound.begin(600_000L)
        bound.end { deleting }
        assertEquals(1_605_000L, bound.expMs)
        bound.end { deleting }
        assertNull(bound.expMs)
    }

    /**
     * The 410 route: the `{}` arrives with a 410 marker up and is decided in another coroutine. A clear of the 410-only
     * state meanwhile keeps the bound, since the request is still counted; the word then starts "being deleted" with the
     * request's bound, not since + 10 minutes. Only then is the request done.
     */
    @Test
    fun aClearWhileTheAnswerIsDecidedKeepsTheBound() {
        bound.begin(600_000L)
        // the answer's word is handed to its own coroutine: the request stays counted
        bound.clearUnlessOut() // a status listed the vault again: the 410-only state ends
        assertEquals(1_600_000L, bound.expMs)
        deleting = true // the word starts "being deleted" with this bound
        bound.end { deleting }
        assertEquals(1_600_000L, bound.expMs)
    }

    @Test
    fun aClearWithNoRequestOutDropsTheBound() {
        bound.begin(600_000L)
        deleting = true
        bound.end { deleting }
        bound.clearUnlessOut()
        assertNull(bound.expMs)
    }

    @Test
    fun forgetAlwaysDropsTheBound() {
        bound.begin(600_000L)
        bound.forget()
        assertNull(bound.expMs)
        assertEquals(1, bound.requestsOut)
    }

    @Test
    fun anEndWithoutABeginNeverCountsBelowZero() {
        bound.end { deleting }
        assertEquals(0, bound.requestsOut)
        bound.begin(600_000L)
        assertEquals(1, bound.requestsOut)
    }
}
