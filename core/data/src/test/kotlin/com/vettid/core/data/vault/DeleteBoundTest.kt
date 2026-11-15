package com.vettid.core.data.vault

import kotlinx.coroutines.Job
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

    /** The answer's word with a 410 standing, handed to its own job: the request done meanwhile keeps the bound until it ends. */
    @Test
    fun aHeldWordKeepsTheBoundUntilItsJobEnds() {
        bound.begin(600_000L)
        val word = Job()
        bound.hold(word) { deleting }
        bound.end { deleting } // the request's own end
        bound.clearUnlessOut() // a clear while the word is being decided
        assertEquals(1_600_000L, bound.expMs)
        word.complete()
        assertNull(bound.expMs)
        assertEquals(0, bound.requestsOut)
    }

    /** 410, listed, 410, listed in the deciding window: the word handed on again is held too. */
    @Test
    fun aWordHandedOnAgainIsHeldToo() {
        bound.begin(600_000L)
        val first = Job()
        bound.hold(first) { deleting }
        bound.end { deleting }
        bound.clearUnlessOut()
        val second = Job()
        bound.hold(second) { deleting } // the first, deciding after a clear, met a new 410 and handed the word on
        first.complete()
        bound.clearUnlessOut()
        assertEquals(1_600_000L, bound.expMs)
        deleting = true // the second starts "being deleted" with this phone's bound
        second.complete()
        assertEquals(1_600_000L, bound.expMs)
    }

    /** A job cancelled before it decided is done with as well; one already ended when held counts once. */
    @Test
    fun aCancelledOrEndedJobIsDoneWith() {
        bound.begin(600_000L)
        val cancelled = Job()
        bound.hold(cancelled) { deleting }
        cancelled.cancel()
        val ended = Job().apply { complete() }
        bound.hold(ended) { deleting }
        assertEquals(1, bound.requestsOut)
        bound.end { deleting }
        assertNull(bound.expMs)
        assertEquals(0, bound.requestsOut)
    }
}
