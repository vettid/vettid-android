package com.vettid.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vettid.core.keystore.AndroidKeys
import com.vettid.core.keystore.KeystoreException
import com.vettid.core.keystore.SeedWrapKey
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class KeystoreFileStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "vettid.test.file_store"
    private val file = File(context.noBackupFilesDir, "store-test.bin")

    @After
    fun cleanup() {
        AndroidKeys.delete(alias)
        file.delete()
    }

    @Test
    fun roundTripTamperAndPurpose() {
        val s = KeystoreFileStore(file, "a", key = { SeedWrapKey.getOrCreate(alias) })
        assertNull(s.load())
        s.save("secret state".toByteArray())
        assertArrayEquals("secret state".toByteArray(), s.load())
        s.save("newer".toByteArray())
        assertArrayEquals("newer".toByteArray(), s.load())
        // Another purpose (AAD) cannot read it; a flipped byte fails.
        assertThrows(KeystoreException::class.java) { KeystoreFileStore(file, "b", key = { SeedWrapKey.getOrCreate(alias) }).load() }
        val b = file.readBytes()
        b[b.size - 1] = (b[b.size - 1].toInt() xor 1).toByte()
        file.writeBytes(b)
        assertThrows(KeystoreException::class.java) { s.load() }
        s.clear()
        assertNull(s.load())
    }
}
