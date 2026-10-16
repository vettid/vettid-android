// Vector checks stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.crypto

import com.vettid.core.crypto.aead.XChaCha20Poly1305
import com.vettid.core.crypto.json.JsonObject
import com.vettid.core.crypto.json.StrictJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * VAULT-MESSAGING 0.32.0 §5.5 (the versioned, padded claim-check blob) and §10.5.1 (the vault's at-rest attachment
 * objects, which use the same framing) against vettid-vault's `blob.json` and `attachment.json`, byte for byte.
 */
class BlobVectorsTest {
    private val v = VectorDoc.load("blob.json")

    @Test
    fun sealReproducesTheVectorBlob() {
        val sealed = deterministic(v.hex("nonce_hex")) { ClaimBlob.seal(v.hex("content_hex"), v.hex("key_hex")) }
        assertBytes("blob", v.hex("blob_hex"), sealed.blob)
        assertBytes("sha256", v.hex("sha256_hex"), sealed.sha256)
        assertEquals(v.str("sha256_b64"), Base64s.encodeStd(sealed.sha256))
        assertEquals(v.num("size").toInt(), sealed.size)
        assertEquals(v.num("blob_len").toInt(), sealed.blob.size)
    }

    @Test
    fun framingAadAndPadmeMatch() {
        assertBytes("framed", v.hex("framed_hex"), ClaimBlob.frame(v.hex("content_hex")))
        assertBytes("aad", v.hex("aad_hex"), ClaimBlob.aad())
        assertEquals(v.num("padme"), ClaimBlob.padme(4 + v.num("size")))
        assertEquals(v.str("label"), ClaimBlob.LABEL)
        assertEquals(v.num("version").toInt(), ClaimBlob.VERSION)
        v.o.array("padme_samples").forEach {
            val s = it as JsonObject
            assertEquals("padme(${s.uint("l", 0, StrictJson.MAX_SAFE_INTEGER)})", s.uint("padme", 0, StrictJson.MAX_SAFE_INTEGER), ClaimBlob.padme(s.uint("l", 0, StrictJson.MAX_SAFE_INTEGER)))
        }
    }

    @Test
    fun theReferenceSizesFollowFromTheLayout() {
        val r = v.sub("reference")
        val maxBlob = r.num("max_blob_bytes")
        val maxContent = r.num("attachment_max_bytes")
        assertEquals(r.num("largest_blob_len"), ClaimBlob.blobLength(maxContent))
        assert(ClaimBlob.blobLength(maxContent) <= maxBlob)
        assert(ClaimBlob.blobLength(maxContent + 1) > maxBlob)
        // A thumbnail's stored object: the version, the nonce, the padded thumbnail and the tag.
        assertEquals(r.num("largest_thumb_object_len"), ClaimBlob.blobLength(r.num("thumb_max_bytes")))
    }

    @Test
    fun openReturnsTheContent() {
        val got = ClaimBlob.open(v.hex("blob_hex"), v.hex("key_hex"), v.hex("sha256_hex"), v.num("size"))
        assertBytes("content", v.hex("content_hex"), got)
    }

    @Test
    fun everyMustFailCaseIsRefusedForItsReason() {
        val cases = v.o.array("must_fail")
        assertEquals(6, cases.size)
        cases.forEach {
            val c = it as JsonObject
            val e = assertThrows(c.string("name"), ClaimBlob.Refused::class.java) {
                ClaimBlob.open(Bytes.unhex(c.string("blob_hex")), v.hex("key_hex"), Bytes.unhex(c.string("sha256_hex")), c.uint("size", 0, StrictJson.MAX_SAFE_INTEGER))
            }
            assertEquals(c.string("name"), c.string("error"), e.failure.code)
        }
    }

    @Test
    fun aFlippedByteIsRefusedBeforeDecryption() {
        // The hash is checked first: a wrong key would otherwise be a decryption failure.
        val blob = v.hex("blob_hex").also { it[40] = (it[40].toInt() xor 1).toByte() }
        val e = assertThrows(ClaimBlob.Refused::class.java) { ClaimBlob.open(blob, ByteArray(32), v.hex("sha256_hex"), v.num("size")) }
        assertEquals(ClaimBlob.Failure.SHA256, e.failure)
    }

    @Test
    fun aRoundTripWithAFreshKeyAndNonce() {
        val content = ByteArray(100_000) { (it * 31).toByte() }
        val s = ClaimBlob.seal(content)
        assertEquals(ClaimBlob.blobLength(content.size.toLong()), s.blob.size.toLong())
        assertBytes("content", content, ClaimBlob.open(s.blob, s.key, s.sha256, content.size.toLong()))
        val other = ClaimBlob.seal(content)
        assert(!other.key.contentEquals(s.key)) { "a blob key is used for one blob only" }
    }

    @Test
    fun theAtRestObjectsUseTheSameFraming() {
        val a = VectorDoc.load("attachment.json")
        val framed = ClaimBlob.frame(a.hex("content_hex"))
        assertBytes("framed", a.hex("framed_hex"), framed)
        a.o.array("objects").forEach {
            val o = VectorDoc(it as JsonObject)
            val aad = (a.str("label") + "\u0000" + a.str("vault_id") + "\u0000" + a.str("att_id") + "\u0000" + o.str("part") + "\u0000")
                .toByteArray(Charsets.US_ASCII) + byteArrayOf(1)
            assertBytes("aad ${o.str("part")}", o.hex("aad_hex"), aad)
            val ct = XChaCha20Poly1305.seal(a.hex("k_att_hex"), o.hex("nonce_hex"), aad, framed)
            assertBytes("object ${o.str("part")}", o.hex("object_hex"), byteArrayOf(1) + o.hex("nonce_hex") + ct)
            assertEquals("vaults/${a.str("vault_id")}/att/${a.str("att_id").lowercase()}" + if (o.str("part") == "t") ".t" else "", o.str("store_key"))
        }
    }
}
