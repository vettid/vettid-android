package com.vettid.core.vault

import com.vettid.core.crypto.envelope.Inner
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

// VAULT-MESSAGING 0.70.0 §10.4.2: the open asks that otherwise arrive only as events (vault release 24 and later).

/** One page of `pending.list`: the entries `{kind, body}`, the cursor for `after`, and the response's `ts`. */
class PendingPage(val asks: List<JsonObject>, val next: String?, val ts: Instant)

/**
 * `pending.list{after?, limit?}` (`limit` 1-100): `{asks: [{kind, body}], next?}` with the response's `ts`, which the app
 * judges events against. An older release answers `unsupported_type`; a held vault `owner_check_required`.
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
    val asks = (r.body["asks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    return PendingPage(asks, VaultJson.str(r.body, "next"), r.ts)
}
