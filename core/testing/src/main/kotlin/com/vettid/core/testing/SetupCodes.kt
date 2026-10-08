package com.vettid.core.testing

import com.vettid.core.altchan.AppKeySigner
import com.vettid.core.vault.VaultJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * TEST ONLY. The portal's half of MEMBER-API 2.0.0 against the dev stack's member API stand-in: a setup code for
 * [guid] (`POST /api/vault/enroll-code`, a portal session: `Authorization: Bearer <user_guid>`). Null from a stand-in
 * before setup codes (vettid-vault before 0.15.0), which takes the guid itself.
 */
object SetupCodes {
    fun issue(stack: DevStack, guid: String): String? {
        val r = Request.Builder().url(stack.apiBase.trimEnd('/') + "/api/vault/enroll-code")
            .header("Authorization", "Bearer $guid").post(ByteArray(0).toRequestBody(null)).build()
        stack.http.newCall(r).execute().use { resp ->
            if (resp.code == HTTP_NOT_FOUND) return null
            check(resp.isSuccessful) { "enroll-code: ${resp.code}" }
            val o = VaultJson.json.parseToJsonElement(resp.body.string()).jsonObject
            return (o["secret"] as? JsonPrimitive)?.content ?: error("enroll-code without secret")
        }
    }

    private const val HTTP_NOT_FOUND = 404
}

/** TEST ONLY. An app key in software (ECDSA P-256), where the app uses the Keystore's (`AppApiKey`). */
class SoftwareAppKey : AppKeySigner {
    private val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    override fun spki(): ByteArray = pair.public.encoded

    override fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private)
        update(message)
        sign()
    }
}
