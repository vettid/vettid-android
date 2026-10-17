package com.vettid.feature.onboarding

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * "Contact support" on a screen that cannot open the vault: an email to [ADDRESS] with a fixed subject and nothing else.
 * No vault id, account data, logs or device details go into it; the member writes what they choose.
 */
object SupportMail {
    const val ADDRESS = "support@vettid.org"
    const val SUBJECT = "VettID: help with my vault"

    /** The `mailto:` intent: the address and the fixed subject only. */
    fun intent(): Intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$ADDRESS?subject=" + Uri.encode(SUBJECT)))

    /** Opens the member's mail app; false when none handles it (the screen then shows the address to copy). */
    fun open(context: Context): Boolean = try {
        context.startActivity(intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
