package com.vettid.core.data.vault

import com.vettid.core.altchan.UnlockIds
import com.vettid.core.data.KeystoreFileStore
import com.vettid.core.keystore.KeystoreException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * The lock's record ([LockRecord], [LockKeeper]) in a file of its own, beside the account record and not in it, written
 * whole and synced before [save] returns. A lock the member asked for is kept across a process death in every state of
 * the account record: one that cannot be read (its Keystore key), one shut after a wipe that did not finish, one being
 * written. Two times (when a lock was asked for, and until when an unanswered relay lock may still be acted
 * on) and, from release L, the `unlock_id` a pending lock is bound to (a name, not a secret) and whether this phone is
 * held locked after a lock that ended because the vault was opened again. Sealed with the app's Keystore file key like
 * the other state files ([Sealer]; owner decision 2026-10-27, audit): a file in the earlier plain-text form is read once
 * and written again sealed. A record the Keystore cannot open is treated like a record not understood: a lock is
 * pending (the unlock id it was bound to is then lost, and the lock is the unbound form, which is fail secure). A record with
 * neither is written in the first version's form, which an older app reads; a record of a form this app does not know
 * says a lock is pending (an older app reading the second form starts locked, which is fail secure).
 *
 * Before this file, the record was kept in the account record: [legacy] reads it there until the file is in use
 * ([migrated], marked by [onFirstSave] after the first save, which also clears the legacy fields). A file that cannot be
 * read or understood, or one that is missing once in use or while the account record cannot be read ([recordUnreadable]:
 * whether the file was in use is not known then), says a lock is pending (fail secure: the phone stays locked and the
 * lock is sent again). A fresh install (no account record, no file) has no lock.
 */
internal class LockRecordFile(
    private val file: File,
    private val sealer: Sealer? = null,
    private val legacy: () -> LockRecord,
    private val migrated: () -> Boolean = { false },
    private val onFirstSave: () -> Unit = {},
    private val recordUnreadable: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) : LockRecordStore {
    private val lock = Any()

    /** Seals and opens the file's bytes (the Keystore in the app; none in JVM tests). */
    internal interface Sealer {
        fun seal(plain: ByteArray): ByteArray

        /** Throws [KeystoreException] for a blob it cannot open. */
        fun open(blob: ByteArray): ByteArray

        /** Whether [blob] is in the sealed form (else the earlier plain text). */
        fun isSealed(blob: ByteArray): Boolean = blob.isNotEmpty() && blob[0] == KeystoreFileStore.FORMAT
    }

    override fun load(): LockRecord = synchronized(lock) {
        if (!file.exists()) return if (migrated() || recordUnreadable()) LockRecord(pendingSinceMs = now()) else legacy()
        try {
            val bytes = file.readBytes()
            val s = sealer
            when {
                s == null -> parse(String(bytes))
                s.isSealed(bytes) -> parse(String(s.open(bytes)))
                // The earlier plain-text form: sealed now (best effort; the next save does it otherwise).
                else -> parse(String(bytes))?.also { r -> runCatching { write(line(r).toByteArray()) } }
            }
        } catch (_: IOException) {
            null
        } catch (_: KeystoreException) {
            null
        } catch (@Suppress("TooGenericExceptionCaught") _: RuntimeException) {
            null // the Keystore failing in another way (a ProviderException, say): a lock is pending, never a crash at start
        } ?: LockRecord(pendingSinceMs = now())
    }

    override fun save(record: LockRecord) {
        synchronized(lock) {
            write(line(record).toByteArray())
            if (!migrated()) onFirstSave()
        }
    }

    private fun sealed(plain: ByteArray): ByteArray = try {
        sealer?.seal(plain) ?: plain
    } catch (e: KeystoreException) {
        throw IOException("lock record not sealed", e)
    } catch (e: java.security.GeneralSecurityException) {
        throw IOException("lock record not sealed", e)
    } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
        throw IOException("lock record not sealed", e)
    }

    private fun write(plain: ByteArray) {
        val bytes = sealed(plain)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(bytes)
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("lock record not saved")
        syncDir()
    }

    /** The rename itself made durable: the directory's entry, synced (best effort where the file system refuses it). */
    private fun syncDir() {
        val dir = file.parentFile ?: return
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // a file system that cannot sync a directory: the file's own sync above stands
        }
    }

    private fun line(r: LockRecord): String {
        val times = "${r.pendingSinceMs ?: NONE} ${r.relayLockUntilMs ?: NONE}"
        val id = r.unlockId
        if (id == null && !r.held) return "$VERSION $times\n"
        val shown = if (id == null) NONE else UnlockIds.of(id) ?: throw IOException("unlock_id")
        val held = when {
            !r.held -> NOT_HELD
            r.heldConfirmed -> HELD_CONFIRMED
            else -> HELD
        }
        return "$VERSION_BOUND $times $shown $held\n"
    }

    @Suppress("ReturnCount") // one return per form
    private fun parse(text: String): LockRecord? {
        val parts = text.trim().split(' ')
        val fields = when (parts[0]) {
            VERSION -> FIELDS
            VERSION_BOUND -> FIELDS_BOUND
            else -> return null
        }
        if (parts.size != fields) return null
        val pending = time(parts[1]) ?: return null
        val relay = time(parts[2]) ?: return null
        if (fields == FIELDS) return LockRecord(pending.value, relay.value)
        val raw = parts[ID_FIELD]
        val id = if (raw == NONE) null else UnlockIds.of(raw) ?: return null
        val held = when (parts[HELD_FIELD]) {
            HELD -> true to false
            HELD_CONFIRMED -> true to true
            NOT_HELD -> false to false
            else -> return null
        }
        return LockRecord(pending.value, relay.value, id, held.first, held.second)
    }

    /** A time field: `-` is none ([Field] of null); anything but a number is not of the form (null). */
    private fun time(s: String): Field? = if (s == NONE) Field(null) else s.toLongOrNull()?.let { Field(it) }

    private class Field(val value: Long?)

    private companion object {
        const val VERSION = "1"

        /**
         * (Release L) With the `unlock_id` the pending lock is bound to (or `-`) and the held marker (`0`, `1`, or `2`:
         * held and since confirmed locked).
         */
        const val VERSION_BOUND = "2"
        const val NONE = "-"
        const val FIELDS = 3
        const val FIELDS_BOUND = 5
        const val ID_FIELD = 3
        const val HELD_FIELD = 4
        const val HELD = "1"
        const val NOT_HELD = "0"
        const val HELD_CONFIRMED = "2"
    }
}
