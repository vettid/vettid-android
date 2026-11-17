package com.vettid.core.crypto.json

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.Ed25519
import com.vettid.core.crypto.VectorDoc
import com.vettid.core.crypto.assertBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The approval document vector (`connauth_document.json`, VAULT-MESSAGING 0.89.0 §10.4 "Approval documents", §16): its
 * document is its own canonical form, its hash is `d`, its challenge's `context` ends with the reference, and the vault's
 * signature is the document form over it (`m_d = len(purpose) || purpose || d || m` under
 * `"vettid/vms/2/conn-auth-document"`), verified with the vector's credential key.
 */
class ApprovalDocumentVectorTest {
    private val v = VectorDoc.load("connauth_document.json")
    private val document = v.str("document").toByteArray(Charsets.UTF_8)
    private val d = Bytes.sha256(document)

    @Test
    fun theDocumentIsItsOwnCanonicalForm() {
        assertBytes("canonical", document, Jcs.canonical(StrictJson.parseObject(document, 16)))
        // The challenge as the service sent it carries exactly these bytes.
        assertBytes("challenge document", document, StrictJson.parseObject(v.str("challenge_body")).base64("document"))
    }

    @Test
    fun itsHashAndReference() {
        assertBytes("d", v.hex("document_sha256_hex"), d)
        assertEquals(v.str("document_sha256_b64"), Base64s.encodeStd(d))
        assertTrue(v.str("context").endsWith(Bytes.hex(d.copyOf(8))))
        assertEquals(v.str("context"), StrictJson.parseObject(v.str("challenge_body")).string("context"))
    }

    @Test
    fun theDocumentFormSignature() {
        val mD = v.hex("message_document_hex")
        val purpose = "approval".toByteArray(Charsets.US_ASCII)
        assertBytes("m_d prefix", Bytes.concat(byteArrayOf(purpose.size.toByte()), purpose, d), mD.copyOf(1 + purpose.size + 32))
        val label = v.str("label_document")
        assertEquals("vettid/vms/2/conn-auth-document", label)
        val response = StrictJson.parseObject(v.str("response_body"))
        val key = response.base64("key", 32)
        assertEquals(v.str("document_sha256_b64"), response.string("document_sha256"))
        assertEquals("approval", response.string("purpose"))
        assertEquals(v.str("sig_b64"), response.string("sig"))
        assertTrue("document form", Ed25519.verify(key, label, mD, v.b64("sig_b64")))
        // The purpose form of the same challenge (a vault before 0.89.0) signs m without d.
        val m = mD.copyOfRange(1 + purpose.size + 32, mD.size)
        val purposeForm = Bytes.concat(byteArrayOf(purpose.size.toByte()), purpose, m)
        assertTrue("purpose form", Ed25519.verify(key, "vettid/vms/2/conn-auth-purpose", purposeForm, v.b64("purpose_sig_b64")))
    }
}
