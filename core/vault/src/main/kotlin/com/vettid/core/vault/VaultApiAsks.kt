package com.vettid.core.vault

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Mutes or unmutes a connection's asks (VAULT-MESSAGING 0.23.0 §10.4.1 `connection.asks.mute`): `{}`, or `not_found`. */
suspend fun VaultApi.connectionAsksMute(connectionId: String, muted: Boolean) {
    device.op(
        "connection.asks.mute",
        buildJsonObject {
            put("connection_id", connectionId)
            put("muted", muted)
        },
    )
}

/** Ends a pause of a connection's asks and clears its declines and cooldowns (§10.4.1 `connection.asks.resume`). */
suspend fun VaultApi.connectionAsksResume(connectionId: String) {
    device.op("connection.asks.resume", buildJsonObject { put("connection_id", connectionId) })
}
