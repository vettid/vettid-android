package com.vettid.app.lock

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.vettid.app.di.AppScope
import com.vettid.core.data.vault.LockNotice
import com.vettid.core.data.vault.VaultRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * The member's lock survives the app being killed (owner decision 2026-10-23, release L's Q4; ANDROID-PLAN 0.1.75 §4
 * "Locking the vault" part 1): while a lock is pending, Android runs this job, with a network, whether or not the
 * app's process lives, and again after the phone restarts. Starting the job starts the process, and a process that
 * starts finds the durable pending-lock record and goes on trying (`LockKeeper.resume`, bound to the same unlock as
 * before). The job keeps the process for [BUDGET] at most while that happens, and is scheduled again while the lock
 * is still not confirmed. It does nothing else: the rules of the lock are `LockGuard`'s and `LockKeeper`'s.
 *
 * A framework job (JobScheduler), not WorkManager: the same in both distributions (the F-Droid build has no
 * WorkManager). A phone that the member force-stops runs no job until the app is opened again; the record is still
 * there then.
 */
@AndroidEntryPoint
class PendingLockJob : JobService() {
    @Inject
    lateinit var vault: VaultRepository

    @Inject
    @AppScope
    lateinit var scope: CoroutineScope

    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        // Injection made the vault manager, whose start resumed a pending lock and tried it at once. The run ends after
        // one completed attempt (the count of failed tries moves on, or the lock is over), or after BUDGET at most:
        // the process is not kept up for the in-process retries (battery).
        running = scope.launch {
            val start = (vault.lockNotice.value as? LockNotice.Unconfirmed)?.attempts
            withTimeoutOrNull(BUDGET.toMillis()) {
                vault.lockNotice.first { it !is LockNotice.Unconfirmed || start == null || it.attempts > start }
            }
            jobFinished(params, false)
            // Still pending: again later (scheduled after this run has finished: a schedule replaces a running job).
            val n = vault.lockNotice.value
            if (n is LockNotice.Unconfirmed) schedule(this@PendingLockJob, againAfter(Duration.between(n.askedAt, Instant.now())))
        }
        return true
    }

    /** Android ends the run early (no network any more, a constraint lost): asked to run it again. */
    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return vault.lockNotice.value is LockNotice.Unconfirmed
    }

    companion object {
        /** The job's id; the app's only job. */
        const val JOB_ID = 0x10C4

        /** The longest one run keeps the process, waiting for one attempt to complete (Android allows 10 minutes). */
        val BUDGET: Duration = Duration.ofMinutes(1)

        /**
         * The first run after a lock became pending: the process's own retries go on meanwhile (1, 2, 5, 10 s, then
         * every 30 and 60 s), and a run while the process lives only waits.
         */
        val FIRST_AFTER: Duration = Duration.ofMinutes(1)

        /**
         * The time between runs while the lock is still pending ([againAfter]). A run waits for one attempt, so it asks
         * the member API a few times at most; a process that lives on goes on at most 21 times in 15 minutes: under the
         * member API's 30 locks in 15 minutes. A `429` is honoured. Best effort: Android may defer a job for an app it
         * seldom sees (standby buckets); the record stays, and every start of the app resumes.
         */
        val AGAIN_AFTER: Duration = Duration.ofMinutes(15)

        /** After [SLOW_AFTER] of a lock that is still pending, the runs come [AGAIN_AFTER_SLOW] apart (battery). */
        val SLOW_AFTER: Duration = Duration.ofHours(3)
        val AGAIN_AFTER_SLOW: Duration = Duration.ofHours(1)

        /** The wait before the next run of a lock pending for [pendingFor]. */
        fun againAfter(pendingFor: Duration): Duration = if (pendingFor < SLOW_AFTER) AGAIN_AFTER else AGAIN_AFTER_SLOW

        /** The job, with a network, kept across a restart of the phone, after [after]. */
        fun info(context: Context, after: Duration): JobInfo =
            JobInfo.Builder(JOB_ID, ComponentName(context, PendingLockJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(after.toMillis())
                .setPersisted(true)
                .build()

        /** Schedules the job (replacing one already scheduled). */
        fun schedule(context: Context, after: Duration = FIRST_AFTER) {
            runCatching { context.getSystemService(JobScheduler::class.java)?.schedule(info(context, after)) }
        }

        /** Whether the job is scheduled now. */
        fun scheduled(context: Context): Boolean =
            runCatching { context.getSystemService(JobScheduler::class.java)?.getPendingJob(JOB_ID) != null }.getOrDefault(false)

        /** Cancels the job: no lock is pending. */
        fun cancel(context: Context) {
            runCatching { context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
        }
    }
}
