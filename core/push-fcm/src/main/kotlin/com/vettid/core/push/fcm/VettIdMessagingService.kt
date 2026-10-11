package com.vettid.core.push.fcm

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking

/** The app's one [FcmPushProvider], for the components Android creates. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FcmEntryPoint {
    fun fcmPushProvider(): FcmPushProvider
}

private fun provider(context: Context): FcmPushProvider =
    EntryPointAccessors.fromApplication(context.applicationContext, FcmEntryPoint::class.java).fcmPushProvider()

/**
 * Receives Google push wakes (PUSH-GATEWAY 0.4.1 §6, §11.1). The gateway's message is fixed (`{"w": "1"}`, high
 * priority, no `notification` block) and means only "collect": nothing of it is read here, so nothing a push could
 * carry is ever shown or trusted. Notifications come from what the app then collects from its own relay mailbox and
 * decrypts on the phone, through the same channels, Mapping and Content rules as the on-phone service's.
 */
class VettIdMessagingService : FirebaseMessagingService() {
    /** On Firebase's worker thread, which holds the wake lock for the message's 20 s. */
    override fun onMessageReceived(message: RemoteMessage) {
        runBlocking { provider(this@VettIdMessagingService).wake() }
    }

    /** Google dropped pending messages (the phone was offline for long): a collect, as for a wake. */
    override fun onDeletedMessages() {
        runBlocking { provider(this@VettIdMessagingService).wake() }
    }

    /** The token changed: the new one goes to the vault (`push.register`), read from Firebase when it is sent. */
    override fun onNewToken(token: String) {
        provider(this).tokenRotated()
    }
}

/** A wake's collect, continued: runs once there is a network, for a few minutes at most. */
internal class WakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        provider(applicationContext).continueWake(WakeWork.WORK_BUDGET_MS)
        // Never retried: correctness does not depend on a wake (the app collects at every start and foreground).
        return Result.success()
    }
}

/**
 * [WakeContinuation] on WorkManager (ANDROID-PLAN 0.1.30, Notification modes 4: "a longer collect runs as expedited
 * WorkManager work"). WorkManager's own start-up initialiser is removed from the manifest: it is initialised on
 * first use from the application's `Configuration.Provider`, so a build without Google push, which never comes
 * here, starts nothing of WorkManager.
 */
internal class WakeWork(private val context: Context) : WakeContinuation {
    override fun enqueue() {
        val work = OneTimeWorkRequestBuilder<WakeWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, work)
    }

    companion object {
        const val NAME = "vettid.push.wake"
        const val WORK_BUDGET_MS = 180_000L
    }
}
