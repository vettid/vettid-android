package com.vettid.core.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * Makes a text that appears by itself (a failure, an outcome, a result after a tap) a polite live region, so a screen reader
 * speaks it when it appears (A6 audit A1, A16, A29). Never on a text that changes every second (a countdown): that would be
 * read out each time. For a [NoticeCard] use its `announce` parameter.
 */
fun Modifier.announced(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }
