package com.vettid.core.vault

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Push (VAULT-MESSAGING 0.24.1 §14): contentless wakes through the VettID push gateway. The app's token goes to its
// own vault over the end-to-end session; the vault registers it with the gateway and keeps only the gateway's sealed
// wake blob, never the token. Only the holder app sends these.

/**
 * `push.register` (§14.1): registers this app's push token. The answer is `{push: <push state>}`; errors are
 * `bad_request`, `forbidden`, `unavailable`, `gateway_error` (body `{retry_after?}`) and `limit`
 * (`push_registrations`). `environment` is for `apns` only and is never sent for `fcm`.
 */
suspend fun VaultApi.pushRegister(platform: String, token: String, appId: String): JsonObject = device.op(
    "push.register",
    buildJsonObject {
        put("platform", platform)
        put("token", token)
        put("app_id", appId)
    },
)

/** `push.unregister` (§14.1): the body is `{}` and nothing else; answered `{}` without a registration too. */
suspend fun VaultApi.pushUnregister() {
    device.op("push.unregister")
}
