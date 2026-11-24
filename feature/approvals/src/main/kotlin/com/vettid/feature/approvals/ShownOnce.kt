package com.vettid.feature.approvals

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.vettid.core.ui.components.LocalCheckComing

/**
 * A result shown once: leaving its screen by any way (OK, back, the drawer, a notification) counts as read, so a restored
 * back-stack entry closes at once instead of showing it again. A rotation or a fold change is not leaving. The app lock
 * and the release gate replace the shell's content rather than cover it, so they count as leaving too: the member had the
 * result on screen (one that arrives while the app is stopped is first composed after the unlock). The daily check is
 * drawn over the shell, so the screen stays composed under it: its cover coming up counts as leaving too, or the result
 * would be on screen again when the check passes, possibly hours later and with a time already past (R33). Only a cover
 * that comes up after the result was on screen: one composed under the cover (it arrived while the check was up) waits to
 * be seen.
 */
@Composable
internal fun FinishWhenLeft(onFinish: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    val finish by rememberUpdatedState(onFinish)
    DisposableEffect(Unit) { onDispose { if (activity?.isChangingConfigurations != true) finish() } }
    val covered = LocalCheckComing.current
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(covered) { if (!covered) seen = true else if (seen) finish() }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
