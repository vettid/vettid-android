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
        for (over in listOf(LockNotice.None, LockNotice.ConfirmedLate, LockNotice.OpenedAgain)) {
            assertEquals(over.toString(), PendingLockNotifier.State.NONE, PendingLockNotifier.state(over))
        }
    }
}
