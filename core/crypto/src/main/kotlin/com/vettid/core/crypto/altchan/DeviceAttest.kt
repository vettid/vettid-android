package com.vettid.core.crypto.altchan

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.JsonObject
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.json.asString

/**
 * `device_attest` (enrollment §11.3, app pairing §6.7, transfer §6.7.1):
 * Android sends its attestation certificate chain, leaf first, 1–10 DER
 * certificates. The enclave verifies it; the app only builds it.
 */
class DeviceAttest private constructor(val platform: String, private val chain: List<ByteArray>) {
    fun chain(): List<ByteArray> = chain.map { it.copyOf() }

    fun marshal(): String {
        val b = JsonBuilder().string("platform", platform)
        b.raw("chain", JsonBuilder.array(chain.map { JsonBuilder.quote(Base64s.encodeStd(it)) }))
        return b.build()
    }

    companion object {
        const val PLATFORM_ANDROID = "android"
        const val MAX_CHAIN = 10
        internal const val MAX_BLOB = 16 * 1024

        fun android(chain: List<ByteArray>): DeviceAttest {
            if (chain.isEmpty() || chain.size > MAX_CHAIN || chain.any { it.isEmpty() || it.size > MAX_BLOB }) {
                throw CryptoException.Format("device_attest chain")
            }
            return DeviceAttest(PLATFORM_ANDROID, chain.map { it.copyOf() })
        }

        /** Parses an Android `device_attest` strictly (§11.7). */
        fun parse(o: JsonObject): DeviceAttest {
            if (o.string("platform") != PLATFORM_ANDROID) throw CryptoException.Format("device_attest platform")
            val arr = o.array("chain")
            return android(arr.map { Base64s.decodeStd(it.asString()) })
        }

        fun parse(raw: ByteArray): DeviceAttest = parse(StrictJson.parseObject(raw))
    }
}

/**
 * `device_assertion` of `vault.unlock` (§11.4) and the approval of a release
 * update (§11.10.3): a DER ECDSA P-256 signature by the device attestation key.
 */
class DeviceAssertion private constructor(val platform: String, private val sig: ByteArray) {
    fun sig(): ByteArray = sig.copyOf()

    fun marshal(): String = JsonBuilder().string("platform", platform).base64("sig", sig).build()

    companion object {
        fun android(sig: ByteArray): DeviceAssertion {
            if (sig.isEmpty() || sig.size > DeviceAttest.MAX_BLOB) throw CryptoException.Format("device_assertion sig")
            return DeviceAssertion(DeviceAttest.PLATFORM_ANDROID, sig.copyOf())
        }

        fun parse(o: JsonObject): DeviceAssertion {
            if (o.string("platform") != DeviceAttest.PLATFORM_ANDROID) throw CryptoException.Format("device_assertion platform")
            return android(o.base64("sig"))
        }
    }
}
