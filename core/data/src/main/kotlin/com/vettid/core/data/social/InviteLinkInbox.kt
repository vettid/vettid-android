package com.vettid.core.data.social

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An invitation link the app was opened with (VAULT-MESSAGING §6.4, 0.10.2):
 * the App Link `https://relay.vettid.org/connect#<link>` or the relay page's
 * `vettid://connect#<link>`, handed from the activity to the connect flow once
 * the vault is unlocked. Nothing is sent until the member confirms on the
 * accept screen; the payload inside decides the relay, never the URL's host.
 */
class InviteLinkInbox {
    private val flow = MutableStateFlow<String?>(null)
    val link: StateFlow<String?> = flow.asStateFlow()

    fun offer(raw: String) {
        flow.value = raw
    }

    fun consume() {
        flow.value = null
    }
}
