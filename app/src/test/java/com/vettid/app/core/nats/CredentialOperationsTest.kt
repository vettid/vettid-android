package com.vettid.app.core.nats

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for credential.create and credential.unseal operations.
 */
class CredentialOperationsTest {

    // MARK: - CreateCredentialResponse Tests

    @Test
    fun `CreateCredentialResponse parses valid response`() {
        val json = JsonObject().apply {
            addProperty("credential", "base64-sealed-credential-blob")
            addProperty("algorithm", "nitro-kms-aes256-gcm")
        }

        val response = CreateCredentialResponse.fromJson(json)

        assertNotNull(response)
        assertEquals("base64-sealed-credential-blob", response!!.credential)
        assertEquals("nitro-kms-aes256-gcm", response.algorithm)
    }

    @Test
    fun `CreateCredentialResponse uses default algorithm when not provided`() {
        val json = JsonObject().apply {
            addProperty("credential", "base64-sealed-credential-blob")
        }

        val response = CreateCredentialResponse.fromJson(json)

        assertNotNull(response)
        assertEquals("nitro-kms-aes256-gcm", response!!.algorithm)
    }

    @Test
    fun `CreateCredentialResponse returns null for missing credential`() {
        val json = JsonObject().apply {
            addProperty("algorithm", "nitro-kms-aes256-gcm")
        }

        val response = CreateCredentialResponse.fromJson(json)

        assertNull(response)
    }

    @Test
    fun `CreateCredentialResponse returns null for empty JSON`() {
        val json = JsonObject()

        val response = CreateCredentialResponse.fromJson(json)

        assertNull(response)
    }

    // MARK: - UnsealCredentialResponse Tests

    @Test
    fun `UnsealCredentialResponse parses valid response`() {
        val json = JsonObject().apply {
            add("unseal_result", JsonObject().apply {
                addProperty("session_token", "session-token-abc123")
                addProperty("expires_at", 1704067200L)
            })
        }

        val response = UnsealCredentialResponse.fromJson(json)

        assertNotNull(response)
        assertEquals("session-token-abc123", response!!.sessionToken)
        assertEquals(1704067200L, response.expiresAt)
    }

    @Test
    fun `UnsealCredentialResponse returns null for missing unseal_result`() {
        val json = JsonObject().apply {
            addProperty("session_token", "session-token-abc123")
        }

        val response = UnsealCredentialResponse.fromJson(json)

        assertNull(response)
    }

    @Test
    fun `UnsealCredentialResponse returns null for missing session_token`() {
        val json = JsonObject().apply {
            add("unseal_result", JsonObject().apply {
                addProperty("expires_at", 1704067200L)
            })
        }

        val response = UnsealCredentialResponse.fromJson(json)

        assertNull(response)
    }

    @Test
    fun `UnsealCredentialResponse handles zero expires_at`() {
        val json = JsonObject().apply {
            add("unseal_result", JsonObject().apply {
                addProperty("session_token", "session-token-abc123")
                // Missing expires_at
            })
        }

        val response = UnsealCredentialResponse.fromJson(json)

        assertNotNull(response)
        assertEquals(0L, response!!.expiresAt)
    }

    // MARK: - SealedCredentialBlob Tests

    @Test
    fun `SealedCredentialBlob data class works correctly`() {
        val blob = SealedCredentialBlob(
            version = 1,
            algorithm = "nitro-kms-aes256-gcm",
            encryptedDek = "encrypted-dek-base64",
            nonce = "nonce-base64",
            ciphertext = "ciphertext-base64",
            pcrBound = true
        )

        assertEquals(1, blob.version)
        assertEquals("nitro-kms-aes256-gcm", blob.algorithm)
        assertEquals("encrypted-dek-base64", blob.encryptedDek)
        assertEquals("nonce-base64", blob.nonce)
        assertEquals("ciphertext-base64", blob.ciphertext)
        assertTrue(blob.pcrBound)
    }

    @Test
    fun `SealedCredentialBlob with dev algorithm`() {
        val blob = SealedCredentialBlob(
            version = 1,
            algorithm = "dev-aes256-gcm",
            encryptedDek = "dev-key",
            nonce = "dev-nonce",
            ciphertext = "dev-ciphertext",
            pcrBound = false
        )

        assertEquals("dev-aes256-gcm", blob.algorithm)
        assertFalse(blob.pcrBound)
    }

    // MARK: - Algorithm Constants

    @Test
    fun `nitro algorithm identifier is correct`() {
        val nitroAlgorithm = "nitro-kms-aes256-gcm"
        val devAlgorithm = "dev-aes256-gcm"

        assertNotEquals(nitroAlgorithm, devAlgorithm)
        assertTrue(nitroAlgorithm.contains("nitro"))
        assertTrue(devAlgorithm.contains("dev"))
    }
}
