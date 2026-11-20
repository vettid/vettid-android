package com.vettid.core.data.vault

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phase collector as VaultManager runs it ([PhaseFollower], [follow]) over a real [PhaseHolder], collected on a test
 * dispatcher so that phases published between two runs are conflated as a slow collector would see them: a close the
 * collector was not told of on its own still runs the lock's clears, and the "Your vault is locked" notice still comes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhaseFollowTest {
    private val blip = AppPhase.Unreachable(FailureKind.NETWORK)

    private class Follow(scope: TestScope, initial: AppPhase) {
        val phase = PhaseHolder(initial)
        val ran = mutableListOf<String>()
        val notices = mutableListOf<Int>()
        private val follower = PhaseFollower()
        private val steps = PhaseSteps.of(
            replay = { ran += PhaseSteps.REPLAY },
            forgetOpenVault = { ran += PhaseSteps.SOCIAL },
            clearAttachments = { ran += PhaseSteps.ATTACHMENTS },
            clearItems = { ran += PhaseSteps.ITEMS },
            clearSharing = { ran += PhaseSteps.SHARING },
            clearFeed = { ran += PhaseSteps.FEED },
            clearDevices = { ran += PhaseSteps.DEVICES },
            clearVotes = { ran += PhaseSteps.VOTES },
            clearPresence = { ran += PhaseSteps.PRESENCE },
            clearLocation = { ran += PhaseSteps.LOCATION },
            clearReleaseCheck = { ran += PhaseSteps.RELEASE_CHECK },
            ownProfile = { p -> ran += "${PhaseSteps.OWN_PROFILE}:${p == AppPhase.Unlocked}" },
        )

        init {
            scope.backgroundScope.launch {
                phase.ticks.collect { t ->
                    val step = follower.next(t)
                    if (step.closedSinceOpen) notices += t.closes
                    steps.follow(t.phase, step) { missed -> ran += "opened:$missed" }
                }
            }
        }

        fun clears(name: String) = ran.count { it == name }
    }

    @Test
    fun aSecondLockConflatedBehindTheFirstStillClears() = runTest {
        val f = Follow(this, AppPhase.Unlocked)
        runCurrent()
        f.phase.value = AppPhase.Locked
        runCurrent()
        assertEquals(1, f.clears(PhaseSteps.ITEMS))
        // An unlock and a lock again before the collector runs: it sees Locked again, and clears again.
        f.phase.value = AppPhase.Unlocked
        f.phase.value = AppPhase.Locked
        runCurrent()
        assertEquals(2, f.clears(PhaseSteps.ITEMS))
        assertEquals(2, f.clears(PhaseSteps.LOCATION))
        assertEquals("both locks noticed", listOf(1, 2), f.notices)
    }

    @Test
    fun aLockAndAnUnlockConflatedClearThenOpenAgain() = runTest {
        val f = Follow(this, AppPhase.Unlocked)
        runCurrent()
        f.ran.clear()
        f.phase.value = AppPhase.Locked
        f.phase.value = AppPhase.Unlocked
        runCurrent()
        // Not the feed's or the release check's: the opening reset both for itself (and may be in a blip already).
        val clears = listOf(
            PhaseSteps.SOCIAL, PhaseSteps.ATTACHMENTS, PhaseSteps.ITEMS, PhaseSteps.SHARING, PhaseSteps.DEVICES,
            PhaseSteps.VOTES, PhaseSteps.PRESENCE, PhaseSteps.LOCATION,
        )
        // The lock's clears and the profile's forget, then the opening's steps, its reads due again; no replay for Locked.
        assertEquals(
            clears + listOf("${PhaseSteps.OWN_PROFILE}:false", PhaseSteps.REPLAY, "opened:true", "${PhaseSteps.OWN_PROFILE}:true"),
            f.ran,
        )
        assertEquals("open again: no notice", emptyList<Int>(), f.notices)
    }

    @Test
    fun aLockFollowedByABlipIsNoticed() = runTest {
        val f = Follow(this, AppPhase.Unlocked)
        runCurrent()
        f.phase.value = AppPhase.Locked
        f.phase.value = blip // a status read after the lock, all the collector sees
        runCurrent()
        assertEquals(listOf(1), f.notices)
    }

    /** Locked, then an unlock, a lock and an unlock again before the collector runs: a close of an open vault, missed. */
    @Test
    fun aCloseBetweenTwoOpeningsIsAMissedClose() = runTest {
        val f = Follow(this, AppPhase.Locked)
        runCurrent()
        f.ran.clear() // the first Locked's own clears
        f.phase.value = AppPhase.Unlocked
        f.phase.value = AppPhase.Locked
        f.phase.value = AppPhase.Unlocked
        runCurrent()
        assertEquals(1, f.clears(PhaseSteps.ITEMS))
        assertEquals(listOf("opened:true"), f.ran.filter { it.startsWith("opened") })
    }

    /** A blip whose return continues the same opening is no opening before a close: no missed close is run for it. */
    @Test
    fun aBlipsReturnIsNoOpeningBeforeTheClose() = runTest {
        val f = Follow(this, AppPhase.Locked)
        runCurrent()
        f.ran.clear() // the first Locked's own clears
        f.phase.value = blip // a status read while locked
        f.phase.value = AppPhase.Locked
        f.phase.value = AppPhase.Unlocked // the one opening
        f.phase.value = blip
        f.phase.value = AppPhase.Unlocked // the same opening continues
        runCurrent()
        assertEquals(0, f.clears(PhaseSteps.ITEMS))
        assertEquals(listOf("opened:false"), f.ran.filter { it.startsWith("opened") })
    }

    /** A lock found after a blip of the open vault: the vault was open before it, so the notice shows. */
    @Test
    fun aLockFoundAfterABlipIsNoticed() = runTest {
        val f = Follow(this, AppPhase.Unlocked)
        runCurrent()
        f.phase.value = blip
        runCurrent()
        f.phase.value = AppPhase.Locked
        runCurrent()
        assertEquals(listOf(1), f.notices)
    }

    /** The member's own lock is not "locked elsewhere"; a lock behind it, conflated with an unlock, is. */
    @Test
    fun theMembersOwnLockIsNotNoticedButOneBehindItIs() {
        val closed = PhaseFollower.Step(closedSinceOpen = true, missedClose = false)
        assertFalse(lockedElsewhere(closed, closes = 1, lockedHere = true, lockedHereClose = 1))
        assertTrue(lockedElsewhere(closed, closes = 2, lockedHere = true, lockedHereClose = 1))
        assertTrue(lockedElsewhere(closed, closes = 1, lockedHere = false, lockedHereClose = 1))
        assertFalse(lockedElsewhere(closed.copy(closedSinceOpen = false), closes = 2, lockedHere = false, lockedHereClose = 1))
    }

    @Test
    fun aBlipOfANewerOpeningIsNoNotice() = runTest {
        val f = Follow(this, AppPhase.Unlocked)
        runCurrent()
        f.phase.value = AppPhase.Locked
        f.phase.value = AppPhase.Unlocked
        f.phase.value = blip // a blip of the new opening, all the collector sees
        runCurrent()
        assertEquals(emptyList<Int>(), f.notices)
    }

    @Test
    fun aColdStartsLockAndUnlockTogetherClearNothing() = runTest {
        val f = Follow(this, AppPhase.Starting)
        runCurrent()
        f.phase.value = AppPhase.Locked
        f.phase.value = AppPhase.Unlocked
        runCurrent()
        assertEquals(0, f.clears(PhaseSteps.ITEMS))
        assertEquals(listOf("opened:false"), f.ran.filter { it.startsWith("opened") })
    }

    /** The missed close's clears failing twice end the process ([LockClearFailed]), even if the opening's steps fail too. */
    @Test
    fun aFailedMissedCloseIsNotHiddenByTheOpening() = runTest {
        val steps = PhaseSteps.of(
            replay = {}, forgetOpenVault = {}, clearAttachments = {}, clearItems = { error("items") }, clearSharing = {},
            clearFeed = {}, clearDevices = {}, clearVotes = {}, clearPresence = {}, clearLocation = {}, clearReleaseCheck = {},
            ownProfile = {},
        )
        val thrown = runCatching {
            steps.follow(AppPhase.Unlocked, PhaseFollower.Step(closedSinceOpen = false, missedClose = true)) {
                throw java.io.IOException("relay")
            }
        }.exceptionOrNull()
        assertTrue(thrown is LockClearFailed)
        assertTrue(thrown!!.suppressed.any { it is java.io.IOException })
    }

    @Test
    fun aBlipOfAnOpenVaultOrAColdStartIsNoNotice() = runTest {
        val f = Follow(this, AppPhase.Starting)
        runCurrent()
        f.phase.value = AppPhase.Locked // the cold start's
        runCurrent()
        f.phase.value = AppPhase.Unlocked
        runCurrent()
        f.phase.value = blip
        runCurrent()
        f.phase.value = AppPhase.Unlocked
        runCurrent()
        assertEquals(emptyList<Int>(), f.notices)
        assertEquals("a plain unlock reads once", listOf("opened:false", "opened:false"), f.ran.filter { it.startsWith("opened") })
    }
}
