package com.vettid.core.data.location

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import com.vettid.core.data.R

/** The words for [SendIssue] on the notification and the connection's card (one place, so that both say the same). */
@StringRes
fun SendIssue.textRes(): Int? = when (this) {
    SendIssue.NONE -> null
    SendIssue.STOP_PENDING -> R.string.data_location_issue_stop_pending
    SendIssue.NO_PERMISSION -> R.string.data_location_issue_no_permission
    SendIssue.LOCATION_OFF -> R.string.data_location_issue_off
    SendIssue.SERVICE -> R.string.data_location_issue_service
}

/** "Stopping…": the share's stop has not reached the vault yet. */
@StringRes
fun stoppingTextRes(): Int = R.string.data_location_stopping

/**
 * Where the member can put it right: the app's page in Android's settings (the location permission), or the phone's location
 * settings. Null where there is nothing to open.
 */
fun SendIssue.settingsIntent(context: Context): Intent? = when (this) {
    SendIssue.NO_PERMISSION -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    SendIssue.LOCATION_OFF -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    else -> null
}?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
