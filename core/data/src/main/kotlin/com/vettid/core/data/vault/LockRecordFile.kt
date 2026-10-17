package com.vettid.core.data.vault

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The lock's record ([LockRecord], [LockKeeper]) in a file of its own, beside the account record and not in it, written
 * whole and synced before [save] returns. A lock the member asked for is kept across a process death in every state of
 * the account record: one that cannot be read (its Keystore key), one shut after a wipe that did not finish, one being
 * written. Plain text: two times and nothing else (when a lock was asked for, and until when an unanswered relay lock
 * may still be acted on), readable without the Keystore.
 *
 * Before this file, the record was kept in the account record: [legacy] reads it there while no file exists. A file that
 * cannot be read or understood says a lock is pending (fail secure: the phone stays locked and the lock is sent again).
 */
internal class LockRecordFile(
    private val file: File,
    private val legacy: () -> LockRecord,
    private val now: () -> Long = System::currentTimeMillis,
) : LockRecordStore {
    private val lock = Any()

    override fun load(): LockRecord = synchronized(lock) {
        if (!file.exists()) return legacy()
        try {
            parse(file.readText())
        } catch (_: IOException) {
            null
        } ?: LockRecord(pendingSinceMs = now())
    }

    override fun save(record: LockRecord) {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use {
                it.write("$VERSION ${record.pendingSinceMs ?: NONE} ${record.relayLockUntilMs ?: NONE}\n".toByteArray())
                it.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException("lock record not saved")
        }
    }

    private fun parse(text: String): LockRecord? {
        val parts = text.trim().split(' ')
        if (parts.size != FIELDS || parts[0] != VERSION) return null
        fun time(s: String): Long? = if (s == NONE) null else s.toLongOrNull() ?: throw NumberFormatException(s)
        return try {
            LockRecord(time(parts[1]), time(parts[2]))
        } catch (_: NumberFormatException) {
            null
        }
    }

    private companion object {
        const val VERSION = "1"
        const val NONE = "-"
        const val FIELDS = 3
    }
}
