package com.vettid.app.features.feed

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-device read-state for the built-in guide catalog. Backs the
 * VettID system connection's guide unread badges / notification
 * rows.
 *
 * Persisted in SharedPreferences (device-local, survives app
 * restarts). Intentionally not synced to the vault — guide reads are
 * minor UX state, not worth a vault round-trip per tap. A fresh
 * install / re-enroll resets them.
 */
@Singleton
class GuideReadTracker @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _readIds = MutableStateFlow(loadReadIds())
    val readIds: StateFlow<Set<String>> = _readIds

    /** Returns the list of guide ids the user hasn't opened yet. */
    fun unreadGuideIds(): List<String> {
        val read = _readIds.value
        return GuideContentProvider.allGuideIds().filter { it !in read }
    }

    fun isRead(guideId: String): Boolean = guideId in _readIds.value

    /** Mark a guide as read. Idempotent; noop if already marked. */
    fun markRead(guideId: String) {
        val current = _readIds.value
        if (guideId in current) return
        val updated = current + guideId
        prefs.edit().putStringSet(KEY_READ_IDS, updated).apply()
        _readIds.value = updated
    }

    /** Reset — exposed for debug/reset flows. */
    fun reset() {
        prefs.edit().remove(KEY_READ_IDS).apply()
        _readIds.value = emptySet()
    }

    private fun loadReadIds(): Set<String> =
        prefs.getStringSet(KEY_READ_IDS, emptySet())?.toSet() ?: emptySet()

    companion object {
        private const val PREFS_NAME = "vettid_guide_reads"
        private const val KEY_READ_IDS = "read_guide_ids"
    }
}
