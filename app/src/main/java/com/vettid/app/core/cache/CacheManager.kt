package com.vettid.app.core.cache

import android.content.Context
import android.util.Log
import coil.Coil
import coil.imageLoader
import com.vettid.app.features.feed.FeedRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wipes all regenerable local caches and lets the next vault round-trip
 * repopulate them. Identity essentials — encrypted credential blob,
 * UTK pool, password salt, LAT, the user's PIN-derived DEK in memory —
 * are intentionally NOT touched, so the user stays logged in across
 * the reset. Use this when the user suspects local-state corruption
 * (stale connection list, missing feed events, broken image previews)
 * and wants a clean slate without re-enrolling.
 *
 * What gets cleared:
 *   - FeedRepository's encrypted prefs (events, sync sequence,
 *     last-sync timestamp, unread count) so the next foreground
 *     fetch performs a full server pull.
 *   - Coil's memory + disk image caches (profile photos, QR images).
 *
 * What stays:
 *   - CredentialStore (encrypted blob, UTKs, salt, LAT) — needed to
 *     unlock the vault on next session.
 *   - VaultState (in-memory DEK + identity keys) — currently active
 *     unlock session; clearing would force a re-PIN with no benefit.
 *   - User-level app preferences (theme, session TTL, etc.) — the
 *     user explicitly set these; a cache reset shouldn't undo them.
 *   - Vault-side state — the vault is the source of truth; nothing
 *     to clear there. A round-trip on the next list refresh
 *     re-fetches anything that mattered.
 */
@Singleton
class CacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val feedRepository: FeedRepository,
) {
    suspend fun resetCache() = withContext(Dispatchers.IO) {
        try {
            feedRepository.clearCache()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear feed cache", e)
        }
        try {
            val loader = context.imageLoader
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear image cache", e)
        }
        try {
            // Coil's singleton ImageLoader can hold cached state too
            // even when ApplicationContext.imageLoader is configured.
            // Best-effort.
            Coil.imageLoader(context).memoryCache?.clear()
            Coil.imageLoader(context).diskCache?.clear()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear Coil singleton image cache", e)
        }
        Log.i(TAG, "Cache reset complete")
    }

    companion object {
        private const val TAG = "CacheManager"
    }
}
