package com.vettid.core.data.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A setup link the app was opened with (the account portal's same-device App Link,
 * `https://<account host>/vault/enroll/#s=<secret>`, VAULT-MESSAGING §11.12.1), handed from the activity to the
 * onboarding flow, which redeems it only while this phone has no vault.
 */
class SetupLinkInbox {
    private val flow = MutableStateFlow<String?>(null)
    val link: StateFlow<String?> = flow.asStateFlow()

    fun offer(raw: String) {
        flow.value = raw
    }

    fun consume() {
        flow.value = null
    }
}
