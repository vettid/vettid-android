package com.vettid.app.core.storage

import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight in-memory key-value store that mirrors enough of
 * `SharedPreferences` for drop-in replacement at call sites that used
 * EncryptedSharedPreferences purely as a cache. Survives only as long
 * as the process; the vault is the authoritative store.
 *
 * This exists because the project's invariant is that no user data is
 * written to disk — the vault holds truth, and on-device caches must
 * be in-memory only.
 */
class InMemoryPrefs {
    private val data = ConcurrentHashMap<String, Any>()

    fun getString(key: String, default: String?): String? = (data[key] as? String) ?: default
    fun getBoolean(key: String, default: Boolean): Boolean = (data[key] as? Boolean) ?: default
    fun getLong(key: String, default: Long): Long = (data[key] as? Long) ?: default
    fun getInt(key: String, default: Int): Int = (data[key] as? Int) ?: default
    fun getFloat(key: String, default: Float): Float = (data[key] as? Float) ?: default
    fun contains(key: String): Boolean = data.containsKey(key)

    @Suppress("UNCHECKED_CAST")
    fun getStringSet(key: String, default: Set<String>?): Set<String>? =
        (data[key] as? Set<String>) ?: default

    fun edit(): Editor = Editor(data)

    class Editor(private val data: ConcurrentHashMap<String, Any>) {
        fun putString(key: String, value: String?): Editor {
            if (value == null) data.remove(key) else data[key] = value
            return this
        }
        fun putBoolean(key: String, value: Boolean): Editor { data[key] = value; return this }
        fun putLong(key: String, value: Long): Editor { data[key] = value; return this }
        fun putInt(key: String, value: Int): Editor { data[key] = value; return this }
        fun putFloat(key: String, value: Float): Editor { data[key] = value; return this }
        fun putStringSet(key: String, value: Set<String>?): Editor {
            if (value == null) data.remove(key) else data[key] = value
            return this
        }
        fun remove(key: String): Editor { data.remove(key); return this }
        fun clear(): Editor { data.clear(); return this }
        fun apply() { /* in-memory; no flush */ }
        fun commit(): Boolean = true
    }
}
