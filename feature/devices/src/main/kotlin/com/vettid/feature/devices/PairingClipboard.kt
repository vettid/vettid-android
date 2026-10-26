package com.vettid.feature.devices

import android.content.ClipboardManager
import android.content.Context
import com.vettid.core.ui.components.CLIP_EXTRA_IS_SENSITIVE
import com.vettid.core.ui.components.textClip
import java.security.MessageDigest
import java.time.Instant

/**
 * The pairing code on the clipboard (ANDROID-PLAN 0.1.53 AA2, decision 1). The code goes there marked sensitive (the
 * system hides it in its clipboard preview), and the app clears the clipboard, if the clip is still the code, when the
 * code expires or the pairing ends while it is in the foreground, and otherwise when it next comes to the foreground:
 * since Android 10 an app can read the clipboard only while it has the focus, so it cannot clear it from the
 * background. It holds only a hash of the code, never the code, so nothing of it stays in memory after the pairing.
 */
object PairingClipboard {
    private var armed: Armed? = null

    private class Armed(val hash: ByteArray, val exp: Instant, val label: String, var ended: Boolean)

    /** Puts [code] on the clipboard, marked sensitive, and remembers to clear it at [exp] or when the pairing ends. */
    fun copy(context: Context, code: String, exp: Instant, label: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(textClip(label, code, sensitive = true))
        synchronized(this) { armed = Armed(hash(code), exp, label, ended = false) }
    }

    /** The pairing ended (cancelled, rejected, paired, expired): the code is worthless; clear it as soon as it can be. */
    fun ended(context: Context) {
        synchronized(this) { armed?.ended = true }
        wipe(context, Instant.now())
    }

    /** The app has the focus again: clears the code if it ended or expired meanwhile. */
    fun onFocus(context: Context, now: Instant = Instant.now()) = wipe(context, now)

    @Suppress("ReturnCount")
    private fun wipe(context: Context, now: Instant) {
        val a = synchronized(this) { armed?.takeIf { it.ended || !it.exp.isAfter(now) } } ?: return
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        // Without the focus the system hands back nothing: keep waiting, never guess. The clip's description comes first:
        // only a clip with this label and the sensitive mark is read at all (anything else is not ours).
        val description = try {
            cm.primaryClipDescription
        } catch (_: SecurityException) {
            null
        } ?: return
        val ours = description.label?.toString() == a.label && description.extras?.getBoolean(CLIP_EXTRA_IS_SENSITIVE) == true
        if (!ours) {
            synchronized(this) { if (armed === a) armed = null }
            return
        }
        val clip = try {
            cm.primaryClip
        } catch (_: SecurityException) {
            null
        } ?: return
        val text = if (clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString() else ""
        if (MessageDigest.isEqual(hash(text), a.hash)) cm.clearPrimaryClip()
        // Cleared, or something else is there now (the member copied other text): nothing more of ours to clear.
        synchronized(this) { if (armed === a) armed = null }
    }

    private fun hash(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
}
