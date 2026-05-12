package com.vettid.app.di

import com.vettid.app.core.nats.OwnerSpaceClient
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point that lets non-composable / pre-injection sites
 * reach the OwnerSpaceClient. VettIDApp uses this to subscribe to
 * grantEvents from the navigation host without involving a ViewModel.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface GrantsEntryPoint {
    fun ownerSpaceClient(): OwnerSpaceClient
}
