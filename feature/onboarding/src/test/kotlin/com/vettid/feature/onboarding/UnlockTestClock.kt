package com.vettid.feature.onboarding

import com.vettid.core.data.vault.ReleaseNotesRepository
import com.vettid.core.testing.FakeReleaseNotes
import com.vettid.core.testing.FakeVault
import kotlinx.coroutines.test.TestScope
import java.time.Instant

/**
 * (#19722) An [UnlockViewModel] whose wall clock (and [vault]'s, for a passed check's deadline) moves with the test's virtual
 * time from [start] (by default the real time now, so that times a test counts from [Instant.now] keep their meaning). On the
 * real clock a deadline ahead is never reached in virtual time, and a screen that judged again then would never end its test.
 * The clock is set before the first judgement only because the Main test dispatcher is a StandardTestDispatcher
 * ([MainDispatcherRule]); with an unconfined one the view model would judge once on the real clock while being made.
 */
internal fun TestScope.unlockOnTestClock(
    vault: FakeVault,
    notes: ReleaseNotesRepository = FakeReleaseNotes(),
    start: Instant = Instant.now(),
): UnlockViewModel {
    val clock = { start.plusMillis(testScheduler.currentTime) }
    vault.clock = clock
    return UnlockViewModel(vault, vault, vault, notes).also { it.clock = clock }
}
