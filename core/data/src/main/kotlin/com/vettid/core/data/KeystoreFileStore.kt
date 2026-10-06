package com.vettid.core.data

import com.vettid.core.crypto.Bytes
import com.vettid.core.keystore.KeystoreException
import com.vettid.core.keystore.SeedWrapKey
import com.vettid.core.vault.DeviceStateStore
import java.io.File
import java.io.FileOutputStream
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A file encrypted under a Keystore AES-256-GCM key (no user
 * authentication: the collector must read it while the phone is locked).
 * Format: `0x01 || iv (12) || AES-GCM(key, iv, aad = "vettid/file/1" || 0x00 || [purpose], data)`;
 * writes go to a temporary file that is synced and renamed over the old one,
 * so a crash leaves either the old or the new state (ack after persist, §8.3).
 * Excluded from backups (`allowBackup=false`).
 */
class KeystoreFileStore(
    private val file: File,
    private val purpose: String,
    private val key: () -> SecretKey = { SeedWrapKey.getOrCreate(ALIAS) },
) : DeviceStateStore {
    @Synchronized
    override fun load(): ByteArray? {
        if (!file.exists()) return null
        val blob = file.readBytes()
        if (blob.size < 1 + IV + TAG || blob[0] != FORMAT) throw KeystoreException("malformed ${file.name}")
        return try {
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 1, IV))
            c.updateAAD(aad())
            c.doFinal(blob, 1 + IV, blob.size - 1 - IV)
        } catch (e: GeneralSecurityException) {
            throw KeystoreException("cannot decrypt ${file.name}", e)
        }
    }

    @Synchronized
    override fun save(state: ByteArray) {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(aad())
        val blob = Bytes.concat(byteArrayOf(FORMAT), c.iv, c.doFinal(state))
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(blob)
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) throw KeystoreException("cannot replace ${file.name}")
    }

    @Synchronized
    override fun clear() {
        file.delete()
    }

    private fun aad() = Bytes.concat(LABEL.toByteArray(), byteArrayOf(0), purpose.toByteArray())

    companion object {
        /** The Keystore key of the app's encrypted files. */
        const val ALIAS = "vettid.local_files.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT: Byte = 0x01
        private const val IV = 12
        private const val TAG = 16
        private const val TAG_BITS = 128
        private const val LABEL = "vettid/file/1"
    }
}
