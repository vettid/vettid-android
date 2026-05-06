package com.vettid.app.core.security

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point + Composable accessor so screens that aren't
 * already in a ViewModel scope (most copy-on-tap UIs are pure
 * Composables) can pull the SecureClipboard singleton without a
 * dedicated ViewModel.
 *
 * Usage:
 *   val clipboard = rememberSecureClipboard()
 *   clipboard.copySensitiveText(walletAddress)
 *
 * SecureClipboard auto-clears the clip after 30s and tags it with
 * EXTRA_IS_SENSITIVE on Android 13+, so callers don't have to
 * remember to do either.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SecureClipboardEntryPoint {
    fun secureClipboard(): SecureClipboard
}

fun Context.secureClipboard(): SecureClipboard =
    EntryPointAccessors.fromApplication(
        applicationContext,
        SecureClipboardEntryPoint::class.java,
    ).secureClipboard()

@Composable
fun rememberSecureClipboard(): SecureClipboard {
    val context = LocalContext.current
    return remember(context) { context.secureClipboard() }
}
