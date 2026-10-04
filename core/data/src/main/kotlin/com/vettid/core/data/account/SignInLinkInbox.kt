package com.vettid.core.data.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A sign-in link the app was opened with (App Link on account.vettid.org
 * `/auth/`), handed from the activity to the onboarding flow. The link is
 * never sent anywhere until the member confirms the sign-in (MEMBER-API: a
 * link scanner must not burn the token, and opening a link must not silently
 * switch accounts).
 */
class SignInLinkInbox {
    private val flow = MutableStateFlow<String?>(null)
    val link: StateFlow<String?> = flow.asStateFlow()

    fun offer(raw: String) {
        flow.value = raw
    }

    fun consume() {
        flow.value = null
    }
}
