package com.vettid.app.core.security

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.vettid.app.core.storage.AppPreferencesStore
import com.vettid.app.core.storage.ContractStore
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.core.storage.PersonalDataStore
import com.vettid.app.core.storage.ProteanCredentialManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LocalDataWiper"

/**
 * Single chokepoint for "scrub everything user-identifying off this
 * device". Used by sign-out and decommission so neither path forgets a
 * store. Vault is the source of truth — none of this data is required
 * for the user to recover, but stale local copies after sign-out are a
 * bigger threat than a UX inconvenience on next enrollment.
 */
@Singleton
class LocalDataWiper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentialStore: CredentialStore,
    private val personalDataStore: PersonalDataStore,
    private val contractStore: ContractStore,
    private val proteanCredentialManager: ProteanCredentialManager,
    private val appPreferencesStore: AppPreferencesStore,
) {
    /**
     * Wipe every local store known to hold user-identifying state.
     * Catches per-store exceptions so a failure in one path doesn't
     * leave others un-wiped.
     */
    fun wipeAll() {
        runStep("CredentialStore") { credentialStore.clearAll() }
        runStep("PersonalDataStore") { personalDataStore.clearAll() }
        runStep("ContractStore") { contractStore.clearAll() }
        runStep("ProteanCredentialManager") { proteanCredentialManager.clearCredential() }
        runStep("AppPreferencesStore") { appPreferencesStore.clearAll() }
        runStep("WorkManager") {
            // Cancel pending backup / publish jobs so they can't fire
            // post-wipe with stale inputData (the inputData carries
            // user_guid and is stored in unencrypted androidx.work.workdb).
            WorkManager.getInstance(context).cancelAllWork()
        }
        runStep("CacheDir") { context.cacheDir.deleteRecursively() }
    }

    private inline fun runStep(label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "wipe step '$label' failed: ${t.message}", t)
        }
    }
}
