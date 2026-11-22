package com.vettid.core.vault

import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.envelope.Inner
import com.vettid.core.crypto.json.JsonObject as StrictObject
import com.vettid.core.crypto.json.StrictJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

// VAULT-MESSAGING 0.70.0 §10.4.2: the open asks that otherwise arrive only as events (vault release 24 and later).

/**
 * One page of `pending.list`: the entries `{kind, body}`, the cursor for `after`, and the response's `ts`. [rawBodies] has
 * each entry's `body` as its exact source text, by index, when the answer reads strictly (no duplicate member anywhere);
 * else every one is null (ANDROID-PLAN 0.1.138 AA8 "Fail closed": an agent's run, proxy and reveal are then deny only).
 */
class PendingPage(val asks: List<JsonObject>, val next: String?, val ts: Instant, val rawBodies: List<String?> = asks.map { null })

/**
 * `pending.list{after?, limit?}` (`limit` 1-100): `{asks: [{kind, body}], next?}` with the response's `ts`, which the app
 * judges events against. A held vault answers `owner_check_required`.
 */
suspend fun VaultApi.pendingList(after: String? = null, limit: Int? = null): PendingPage {
    val r = device.request(
        "pending.list",
        buildJsonObject {
            after?.let { put("after", it) }
            limit?.let { put("limit", it) }
        },
    )
    if (r.inner.status != Inner.STATUS_OK) {
        val error = r.inner.error
        throw VaultOpException("pending.list", error?.code ?: "error", error?.message ?: "", r.body.takeIf { it.isNotEmpty() })
    }
    val listed = (r.body["asks"] as? JsonArray).orEmpty()
    val strict = strictAsks(r.inner.body)?.takeIf { it.size == listed.size }
    val kept = listed.indices.filter { listed[it] is JsonObject }
    return PendingPage(
        kept.map { listed[it] as JsonObject },
        VaultJson.str(r.body, "next"),
        r.ts,
        kept.map { i -> ((strict?.get(i) as? StrictObject)?.get("body") as? StrictObject)?.raw },
    )
}

/** The answer's `asks`, read strictly; null when it does not read so. */
@Suppress("SwallowedException")
private fun strictAsks(b: ByteArray): List<com.vettid.core.crypto.json.JsonValue>? = try {
    StrictJson.parseObject(b).optArray("asks")
} catch (_: CryptoException) {
    null
}
