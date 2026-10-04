package com.vettid.app

import android.app.Application
import com.vettid.app.di.AppScope
import com.vettid.core.data.lock.AppLock
import com.vettid.core.data.vault.AccountRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class VettIdApplication : Application() {
    @Inject
    lateinit var appLock: AppLock

    @Inject
    lateinit var account: AccountRepository

    @Inject
    @AppScope
    lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Once per process: the app lock starts locked when it is on (D6); the vault's phase is read.
        scope.launch {
            appLock.start()
            account.refresh()
        }
    }
}
