package com.vettid.core.crypto

import com.vettid.core.crypto.grant.GrantSeal
import com.vettid.core.crypto.hpke.KemPrivateKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** A granted item's content sealed to the fetching device (§10.12): bound to the grant and the fetch. */
class GrantSealTest {
    private val content = """{"item_id":"01J","version":2,"name":"Allergies","category":"medical","fields":[]}""".toByteArray()

    @Test
    fun opensWithTheReplyKeyForTheSameGrantAndFetch() {
        val k = KemPrivateKey.generate()
        val sealed = GrantSeal.sealValue(k.publicKey, "01GRANT", "01FETCH", content)
        assertArrayEquals(content, GrantSeal.openValue(k, "01GRANT", "01FETCH", sealed))
    }

    @Test
    fun anotherFetchOrGrantDoesNotOpen() {
        val k = KemPrivateKey.generate()
        val sealed = GrantSeal.sealValue(k.publicKey, "01GRANT", "01FETCH", content)
        assertThrows(CryptoException::class.java) { GrantSeal.openValue(k, "01GRANT", "01OTHER", sealed) }
        assertThrows(CryptoException::class.java) { GrantSeal.openValue(k, "01OTHER", "01FETCH", sealed) }
        assertThrows(CryptoException::class.java) { GrantSeal.openValue(KemPrivateKey.generate(), "01GRANT", "01FETCH", sealed) }
        assertThrows(CryptoException::class.java) { GrantSeal.openValue(k, "01GRANT", "01FETCH", sealed.copyOf(100)) }
    }
}
