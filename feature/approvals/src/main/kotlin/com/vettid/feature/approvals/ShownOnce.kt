package com.vettid.feature.approvals

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * A result shown once: leaving its screen by any way (OK, back, the drawer, a notification) counts as read, so a restored
 * back-stack entry closes at once instead of showing it again. A rotation or a fold change is not leaving. The app lock
 * and the release gate replace the shell's content rather than cover it, so they count as leaving too: the member had the
 * result on screen (one that arrives while the app is stopped is first composed after the unlock).
 */
@Composable
internal fun FinishWhenLeft(onFinish: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    val finish by rememberUpdatedState(onFinish)
    DisposableEffect(Unit) { onDispose { if (activity?.isChangingConfigurations != true) finish() } }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
