package com.vettid.core.ui.components

import android.content.ClipData
import android.os.PersistableBundle

/** `ClipDescription.EXTRA_IS_SENSITIVE` (API 33), by name so that API 31–32 phones carry it too. */
const val CLIP_EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

/**
 * A plain-text clip for the clipboard. A [sensitive] one carries Android's sensitive flag, so that the system does
 * not show its text in the clipboard preview or in keyboards' clipboard suggestions (A6 audit S4).
 */
fun textClip(label: String, text: String, sensitive: Boolean): ClipData {
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(CLIP_EXTRA_IS_SENSITIVE, true) }
    return clip
}
