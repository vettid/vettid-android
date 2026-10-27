package com.vettid.app.calls

import androidx.compose.runtime.Composable

/** A build without calls: the open app, and nothing over it. (`src/calls` has the builds with calls.) */
@Composable
fun CallsHost(content: @Composable () -> Unit) = content()
