package com.vettid.app.di

import com.vettid.core.data.wipe.WipeHook
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/** The modules' wipe hooks ([WipeHook]): declared here so that a build in which no module adds one (production) has an empty set. */
@Module
@InstallIn(SingletonComponent::class)
interface WipeHookBindings {
    @Multibinds
    fun wipeHooks(): Set<WipeHook>
}
