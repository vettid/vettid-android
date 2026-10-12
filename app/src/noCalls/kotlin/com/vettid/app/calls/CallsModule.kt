package com.vettid.app.calls

import com.vettid.core.data.calls.CallAccess
import com.vettid.core.data.calls.NoCallAccess
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * A build without calls (every production build until calls are promoted, CALLS-PLAN 0.1.1 phase C1): no call
 * button anywhere, and nothing of calls is asked of the vault. The builds with calls have `src/calls` instead.
 */
@Module
@InstallIn(SingletonComponent::class)
object CallsModule {
    @Provides
    fun callAccess(): CallAccess = NoCallAccess
}
