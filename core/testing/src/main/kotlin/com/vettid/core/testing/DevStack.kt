package com.vettid.core.testing

import com.vettid.core.altchan.AltTrust
import com.vettid.core.attestation.manifest.ManifestKey
import com.vettid.core.crypto.Base64s
import com.vettid.core.relay.OriginMapInterceptor
import com.vettid.core.vault.VaultJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

/**
 * TEST ONLY. The local dev stack (devstack/README.md) as tests reach it:
 * on the host directly, on a phone through `adb reverse` of the same ports.
 * The relay URL that tokens name is `https://relay.vettid.test`; requests
 * for it go to the relay's plain-HTTP port. The control port serves the
 * stack's TEST-ONLY trust anchors and drives the vaultctl peer vault.
 */
class DevStack(
    val apiBase: String = "http://127.0.0.1:18081",
    relayTransport: String = "http://127.0.0.1:18080",
    private val ctl: String = "http://127.0.0.1:18082",
) {
    val relayUrl = "https://relay.vettid.test"
    val manifestUrl: String get() = "$apiBase/.well-known/vettid/pcr-manifest.json"

    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(OriginMapInterceptor(mapOf(relayUrl to relayTransport)))
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val ctlHttp = http.newBuilder().readTimeout(240, TimeUnit.SECONDS).build()

    /** Whether the stack answers (tests skip without it). */
    fun reachable(): Boolean = try {
        val quick = ctlHttp.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
        quick.newCall(Request.Builder().url("$ctl/dev/health").build()).execute().use { it.isSuccessful }
    } catch (_: java.io.IOException) {
        false
    }

    private suspend fun call(path: String, body: JsonObject?): Pair<Int, JsonObject> = withContext(Dispatchers.IO) {
        val rb = Request.Builder().url(ctl + path)
        if (body != null) rb.post(VaultJson.bytes(body).toRequestBody("application/json".toMediaType()))
        ctlHttp.newCall(rb.build()).execute().use { r -> r.code to VaultJson.parseObject(r.body.bytes()) }
    }

    /** The stack's TEST-ONLY trust: its test Nitro root and test manifest key. */
    suspend fun trust(): AltTrust {
        val (_, o) = call("/dev/trust", null)
        val root = CertificateFactory.getInstance("X.509")
            .generateCertificate(Base64s.decodeStd(o["nitro_root"]!!.jsonPrimitive.content).inputStream()) as X509Certificate
        val keys = o["manifest_keys"]!!.jsonArray.map { ManifestKey.fromBase64(it.jsonPrimitive.content) }
        return AltTrust(listOf(root), keys)
    }

    /** `vaultctl request TYPE BODY` on the peer: the response `{status, body}`. */
    suspend fun peerRequest(type: String, body: JsonObject = JsonObject(emptyMap())): JsonObject {
        val (code, o) = call(
            "/dev/peer/request",
            buildJsonObject {
                put("type", type)
                put("body", body)
            },
        )
        check(code == 200) { "peer $type: $code $o" }
        check((o["status"] as? JsonPrimitive)?.content == "ok") { "peer $type: $o" }
        return o["body"]?.jsonObject ?: JsonObject(emptyMap())
    }

    /** Waits for an event of [type] at the peer whose body has the [match] members; returns its body. */
    suspend fun peerEvent(type: String, match: Map<String, String> = emptyMap(), timeoutSeconds: Int = 120): JsonObject {
        val (code, o) = call(
            "/dev/peer/event",
            buildJsonObject {
                put("type", type)
                put("match", JsonObject(match.mapValues { JsonPrimitive(it.value) }))
                put("timeout_s", timeoutSeconds)
            },
        )
        check(code == 200) { "peer event $type: $code" }
        return o["body"]?.jsonObject ?: JsonObject(emptyMap())
    }
}
