package com.vettid.core.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Secret values on the clipboard (A6 audit, 2026-10-27; ANDROID-PLAN; iOS has the same rule: IOS-PLAN §6.5
 * SecurePasteboard, 60 s). A sensitive copy goes there marked sensitive (Android's own flag: hidden in the clipboard
 * preview and keyboards' suggestions) and with a mark of its own, the time it is to be cleared at. The app clears the
 * clipboard when that time comes, and whenever the vault is no longer open (locked, held, wiped, a new process with no
 * open vault), but **only if the clipboard still holds this app's clip**: the mark is part of the clip's description,
 * so something the member copied since carries no mark and is never touched. The mark is in the clip, not in memory,
 * so it also works after the process died.
 *
 * Since Android 10 an app reads the clipboard only while it has the focus, so it cannot check, and so never clears,
 * from the background: what is due then is cleared when the app next has the focus ([onFocus]). Never guessed.
 */
object SecureClipboard {
    /**
     * How long a secret stays on the clipboard: 60 s while the app is in front; from the background it is cleared when the
     * member next returns to VettID (Android lets an app read the clipboard only with the focus), not before.
     */
    const val CLEAR_AFTER_MS = 60_000L

    /** The clip description's mark: the time (epoch ms) at which this app clears the clip. */
    const val EXTRA_CLEAR_AT = "com.vettid.app.extra.CLEAR_AT_MS"

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var scheduled: Runnable? = null
    private var closedPending = false

    /**
     * Puts [text] on the clipboard. A [sensitive] one is marked and cleared after [CLEAR_AFTER_MS]; any other copy is
     * an ordinary copy that stays.
     */
    @Synchronized
    fun copy(context: Context, label: String, text: String, sensitive: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = textClip(label, text, sensitive)
        if (sensitive) {
            val extras = clip.description.extras ?: PersistableBundle()
            extras.putLong(EXTRA_CLEAR_AT, nowMs + CLEAR_AFTER_MS)
            clip.description.extras = extras
        }
        cm.setPrimaryClip(clip)
        closedPending = false // a copy is made while the vault is open
        scheduled?.let { handler.removeCallbacks(it) }
        scheduled = null
        if (sensitive) {
            val app = context.applicationContext
            val r = Runnable { onFocus(app) }
            scheduled = r
            handler.postDelayed(r, CLEAR_AFTER_MS)
        }
    }

    /**
     * Puts [text] on the clipboard marked sensitive (hidden in the clipboard preview and keyboards' suggestions) but without
     * this app's clear mark, so it is never cleared: for what is private but not a secret, such as a receive address the member
     * is about to paste elsewhere (the owner's decision 2a, 2026-11-01). Any timer for an earlier secret goes, as with [copy]:
     * that secret is no longer on the clipboard.
     */
    @Synchronized
    fun copyHidden(context: Context, label: String, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(textClip(label, text, sensitive = true))
        closedPending = false
        scheduled?.let { handler.removeCallbacks(it) }
        scheduled = null
    }

    /**
     * The vault is no longer open (locked, held, wiped) or this process started without one: this app's clip leaves
     * the clipboard now, or as soon as the app has the focus.
     */
    @Synchronized
    fun vaultClosed(context: Context) {
        closedPending = true
        sweep(context, System.currentTimeMillis())
    }

    /** The app has the focus (or a timer ran): clears this app's clip if it is due or the vault closed meanwhile. */
    @Synchronized
    fun onFocus(context: Context, nowMs: Long = System.currentTimeMillis()) = sweep(context, nowMs)

    @Suppress("ReturnCount")
    private fun sweep(context: Context, nowMs: Long) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        // Without the focus the system hands back nothing: keep waiting, never guess.
        val description = try {
            cm.primaryClipDescription
        } catch (_: SecurityException) {
            null
        } ?: return
        val clearAt = description.extras?.takeIf { it.containsKey(EXTRA_CLEAR_AT) }?.getLong(EXTRA_CLEAR_AT)
        // Not this app's clip (nothing, or something the member copied since): nothing of ours to clear.
        if (clearAt == null) {
            closedPending = false
            return
        }
        if (closedPending || nowMs >= clearAt) {
            cm.clearPrimaryClip()
            closedPending = false
            scheduled?.let { handler.removeCallbacks(it) }
            scheduled = null
        }
    }
}
