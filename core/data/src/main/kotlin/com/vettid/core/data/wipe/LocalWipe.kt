package com.vettid.core.data.wipe

import android.app.NotificationManager
import android.content.Context
import com.vettid.core.data.KeystoreFileStore
import com.vettid.core.keystore.AndroidKeys
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * What a wipe erases: directories, Keystore keys, SharedPreferences and
 * notifications. [AndroidWipeTargets] on a phone; temporary directories in
 * the JVM tests.
 */
interface WipeTargets {
    /** "Wipe pending": written (and synced) before anything is deleted, removed last; it survives process death. */
    val marker: File

    /** Every directory whose contents are erased ([marker] excepted). */
    val dirs: List<File>

    /** Every alias in the app's Keystore. */
    fun keyAliases(): List<String>

    fun deleteKey(alias: String)

    /** Every SharedPreferences file, in memory (live instances) and on disk. */
    fun clearSharedPreferences()

    fun cancelNotifications()
}

/**
 * Erases everything this app keeps on the phone, as if it had just been
 * installed (owner decision, 2026-10-05: once the vault has moved to another
 * phone and works there, the old phone shows no sign it was there). Used only
 * after an authenticated signal that this phone was replaced (`vault.HolderWatch`).
 *
 * Crash-safe: [begin] writes the [WipeTargets.marker] first; [erase] deletes
 * (idempotent, it goes on past a failure); [finish] removes the marker only
 * once everything is gone. A process that dies in between finds the marker at
 * the next start, and [resumeIfPending] finishes the wipe before anything else
 * reads local state (`VettIdApplication.onCreate`, before injection).
 *
 * [hooks] reset what lives in memory in this process (the app lock, the
 * DataStore preferences, pending links); they run in a live wipe only.
 */
class LocalWipe(private val targets: WipeTargets, private val hooks: List<suspend () -> Unit> = emptyList()) {
    /** Whether a wipe was started and not finished. */
    val pending: Boolean get() = targets.marker.exists()

    /** Step 1: the marker, synced, before anything is deleted. */
    fun begin() {
        val m = targets.marker
        m.parentFile?.mkdirs()
        FileOutputStream(m).use {
            it.write(MARKER_CONTENT)
            it.fd.sync()
        }
    }

    /** Runs the in-memory resets of this process. A failing hook does not stop the others or the wipe. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun resetMemory() {
        for (h in hooks) {
            try {
                h()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // the disk erase below removes what the hook would have reset
            }
        }
    }

    /**
     * Erases notifications, every Keystore key of the app, every
     * SharedPreferences file and the contents of every directory (the marker
     * excepted). Idempotent; goes on past a failure. True when nothing is left.
     */
    @Suppress("TooGenericExceptionCaught")
    fun erase(): Boolean {
        runCatching { targets.cancelNotifications() }
        // The known aliases first (device attestation, the seed wrap key of the device and relay keys,
        // the local files key, the biometric app-data key), then anything else in the app's Keystore.
        val aliases = (KNOWN_ALIASES + runCatching { targets.keyAliases() }.getOrDefault(emptyList())).distinct()
        for (a in aliases) {
            try {
                targets.deleteKey(a)
            } catch (_: Exception) {
                // checked below
            }
        }
        runCatching { targets.clearSharedPreferences() }
        for (d in targets.dirs) clearDir(d)
        return isErased()
    }

    /** Whether nothing is left: no Keystore key, every directory empty (but for the marker). */
    fun isErased(): Boolean {
        val noKeys = runCatching { targets.keyAliases() }.getOrNull()?.isEmpty() ?: false
        return noKeys && targets.dirs.all { d -> d.listFiles()?.all { it == targets.marker } ?: true }
    }

    /** The last step: the marker goes. */
    fun finish() {
        targets.marker.delete()
    }

    /**
     * At process start: finishes a wipe that a crash interrupted. Returns true
     * when one was pending. The marker stays if something could not be erased,
     * so that the next start tries again.
     */
    fun resumeIfPending(): Boolean {
        if (!pending) return false
        if (erase()) finish()
        return true
    }

    /** A whole live wipe: marker, memory, disk, marker removed. */
    suspend fun run(): Boolean {
        begin()
        resetMemory()
        val done = erase()
        if (done) finish()
        return done
    }

    private fun clearDir(dir: File) {
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (c == targets.marker) continue
            try {
                c.deleteRecursively() // what is left is found by isErased()
            } catch (_: SecurityException) {
                // likewise
            }
        }
    }

    companion object {
        /** The marker's file name, in the no-backup directory. */
        const val MARKER = "wipe-pending"
        private val MARKER_CONTENT = "1".toByteArray()

        /** Every Keystore alias the app creates. */
        val KNOWN_ALIASES: List<String> = listOf(
            AndroidKeys.ALIAS_DEVICE_ATTESTATION,
            AndroidKeys.ALIAS_SEED_WRAP,
            AndroidKeys.ALIAS_APP_DATA,
            KeystoreFileStore.ALIAS,
        )
    }
}

/**
 * [WipeTargets] of this app on the phone: the files, no-backup, cache and
 * databases directories, the SharedPreferences, the whole AndroidKeyStore of
 * the app's uid, and its notifications. DataStore lives under `files/`
 * (its live instance is cleared by a [LocalWipe] hook first).
 */
class AndroidWipeTargets(context: Context) : WipeTargets {
    private val app: Context = context.applicationContext ?: context

    override val marker: File = File(app.noBackupFilesDir, LocalWipe.MARKER)

    private val prefsDir: File = File(app.dataDir, "shared_prefs")

    override val dirs: List<File> = listOf(
        app.noBackupFilesDir,
        app.filesDir,
        app.cacheDir,
        File(app.dataDir, "databases"),
        prefsDir,
    )

    override fun keyAliases(): List<String> = try {
        AndroidKeys.aliases()
    } catch (e: GeneralSecurityException) {
        throw IOException("keystore", e)
    }

    override fun deleteKey(alias: String) {
        AndroidKeys.delete(alias)
    }

    override fun clearSharedPreferences() {
        val names = prefsDir.listFiles()?.mapNotNull { f -> f.name.takeIf { it.endsWith(XML) }?.removeSuffix(XML) } ?: emptyList()
        for (n in names) {
            // Live instances keep their contents in memory: clear them before the file goes.
            app.getSharedPreferences(n, Context.MODE_PRIVATE).edit().clear().commit()
            app.deleteSharedPreferences(n)
        }
    }

    override fun cancelNotifications() {
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancelAll()
    }

    private companion object {
        const val XML = ".xml"
    }
}
