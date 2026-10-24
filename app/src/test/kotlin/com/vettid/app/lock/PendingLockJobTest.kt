package com.vettid.app.lock

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import com.vettid.app.notify.PendingLockNotifier
import com.vettid.core.data.vault.LockNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/**
 * Release L's Q4 (owner decision 2026-10-23): while a lock is pending, a framework job that survives the process and
 * a restart of the phone; and what the notifier does for each state of the lock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PendingLockJobTest {
    private val context: Application = org.robolectric.RuntimeEnvironment.getApplication()
    private val jobs: JobScheduler get() = context.getSystemService(JobScheduler::class.java)

    @Test
    fun theJobNeedsANetworkSurvivesARestartAndIsTheAppsOnly() {
        assertFalse(PendingLockJob.scheduled(context))
        PendingLockJob.schedule(context)
        assertTrue(PendingLockJob.scheduled(context))
        val info = jobs.getPendingJob(PendingLockJob.JOB_ID)!!
        assertTrue("kept across a restart of the phone", info.isPersisted)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, info.networkType)
        assertEquals(PendingLockJob.FIRST_AFTER.toMillis(), info.minLatencyMillis)
        assertEquals(PendingLockJob::class.java.name, info.service.className)
        // Scheduled again: replaced, not added.
        PendingLockJob.schedule(context, PendingLockJob.AGAIN_AFTER)
        assertEquals(1, jobs.allPendingJobs.size)
        assertEquals(PendingLockJob.AGAIN_AFTER.toMillis(), jobs.getPendingJob(PendingLockJob.JOB_ID)!!.minLatencyMillis)
        PendingLockJob.cancel(context)
        assertFalse(PendingLockJob.scheduled(context))
    }

    /** Battery (second review of #190): a run waits for one attempt at most a minute; runs slow down after hours. */
    @Test
    fun theRunsAreShortAndSlowDownAfterSomeHours() {
        assertEquals(Duration.ofMinutes(1), PendingLockJob.BUDGET)
        assertEquals(Duration.ofMinutes(15), PendingLockJob.againAfter(Duration.ofMinutes(5)))
        assertEquals(Duration.ofMinutes(15), PendingLockJob.againAfter(Duration.ofHours(2)))
        assertEquals(Duration.ofHours(1), PendingLockJob.againAfter(Duration.ofHours(3)))
        assertEquals(Duration.ofHours(1), PendingLockJob.againAfter(Duration.ofDays(2)))
    }

    /** A notice is posted once per lock, also across processes: a lock in doubt by its time; "opened again" by its lock. */
    @Test
    fun aNoticeIsKeyedByItsLock() {
        val t = Instant.parse("2026-10-24T10:00:00Z")
        assertEquals(null, PendingLockNotifier.keyOf(LockNotice.Unconfirmed(t, 0), null))
        assertEquals("u:${t.toEpochMilli()}", PendingLockNotifier.keyOf(LockNotice.Unconfirmed(t, 3), null))
        // Second recheck of #190, D4: the "opened again" of a second lock is not the first's.
        assertEquals("o:1", PendingLockNotifier.keyOf(LockNotice.OpenedAgain, 1))
        assertEquals("o:2", PendingLockNotifier.keyOf(LockNotice.OpenedAgain, 2))
        assertEquals(null, PendingLockNotifier.keyOf(LockNotice.ConfirmedLate, 1))
    }

    @Test
    fun theRunsKeepWithinTheMemberApisLockLimit() {
        // 30 locks in 15 minutes per member: a run makes at most 9 (1, 2, 5, 10 s, then 30 s apart in 5 minutes).
        assertTrue(PendingLockJob.BUDGET <= Duration.ofMinutes(5))
        assertTrue(PendingLockJob.AGAIN_AFTER >= Duration.ofMinutes(15))
    }

    @Test
    fun theJobIsScheduledWhileALockIsPendingAndTheNoticeOnlyWhileItIsInDoubt() {
        val t = Instant.parse("2026-10-23T10:00:00Z")
        assertEquals(PendingLockNotifier.State.PENDING, PendingLockNotifier.state(LockNotice.Unconfirmed(t, 0)))
        assertEquals(PendingLockNotifier.State.IN_DOUBT, PendingLockNotifier.state(LockNotice.Unconfirmed(t, 1)))
        // Opened again: the job goes (nothing to retry) but the notice does not just disappear (review of #190, D3).
        assertEquals(PendingLockNotifier.State.OPENED_AGAIN, PendingLockNotifier.state(LockNotice.OpenedAgain))
        for (over in listOf(LockNotice.None, LockNotice.ConfirmedLate)) {
            assertEquals(over.toString(), PendingLockNotifier.State.NONE, PendingLockNotifier.state(over))
        }
    }
}
