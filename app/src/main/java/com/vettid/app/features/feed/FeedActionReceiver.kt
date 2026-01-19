package com.vettid.app.features.feed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.vettid.app.core.nats.FeedClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Broadcast receiver for handling notification action buttons.
 *
 * Handles Accept/Decline actions from feed notifications without
 * requiring the user to open the app.
 */
@AndroidEntryPoint
class FeedActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var feedClient: FeedClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "FeedActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra(FeedNotificationService.EXTRA_EVENT_ID)
        val action = intent.getStringExtra("action")

        if (eventId == null || action == null) {
            Log.w(TAG, "Received action without eventId or action")
            return
        }

        Log.d(TAG, "Received action: $action for event: $eventId")

        // Execute action asynchronously
        scope.launch {
            try {
                feedClient.executeAction(eventId, action)
                    .onSuccess {
                        Log.i(TAG, "Action $action executed successfully for $eventId")
                    }
                    .onFailure { error ->
                        Log.e(TAG, "Failed to execute action $action for $eventId", error)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing action", e)
            }

            // Cancel the notification regardless of result
            NotificationManagerCompat.from(context).cancel(eventId.hashCode())
        }
    }
}
