package com.vettid.core.vault

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The wallet requests the app uses in W1c (VAULT-MESSAGING §10.18; ANDROID-PLAN §4 "Wallet"), beside [VaultApi] rather than in
 * it, as the calls are. Not in production builds: nothing that ships in release may reach these (`walletMarkers` in
 * app/build.gradle.kts fails a release APK that carries their type names); `:core:wallet`, linked only where the wallet is, is
 * their one caller. A vault without wallets answers `unsupported_type`; a held vault `owner_check_required`. Requests and answers
 * carry addresses and account keys: nothing here logs.
 */

/** `wallet.list{}`: `{wallets: [<wallet>]}`. */
suspend fun VaultApi.walletList(): JsonObject = device.op("wallet.list", buildJsonObject { })

/** `wallet.get{wallet_id}`: `<wallet>`. */
suspend fun VaultApi.walletGet(walletId: String): JsonObject = device.op("wallet.get", buildJsonObject { put("wallet_id", walletId) })

/**
 * `wallet.create{name, network, credential, utk_id, sealed{password}}`, a credential operation (§3.5.3) that makes a new phrase
 * (24 words) in the vault: `<wallet>` and the new credential, which [CredentialOps] keeps and acknowledges. No `item` (an import is
 * W1d), no `address_type` (P2TR by default) and no `caps` (spending limits are set after the backup, W1d). `limit` at 16 wallets.
 */
suspend fun VaultApi.walletCreate(name: String, network: String, password: String): JsonObject =
    credentialOps.credOp(
        "wallet.create",
        { put("password", password) },
        extra = {
            put("name", name)
            put("network", network)
        },
    ).first

/**
 * `wallet.address.new{wallet_id, type?, change?}`: the next address of an account's chain, `<address>`; without `type` the
 * receiving account's. `limit` at 2,000 addresses per wallet.
 */
suspend fun VaultApi.walletAddressNew(walletId: String, type: String? = null, change: Boolean = false): JsonObject =
    device.op(
        "wallet.address.new",
        buildJsonObject {
            put("wallet_id", walletId)
            type?.let { put("type", it) }
            if (change) put("change", true)
        },
    )

/**
 * `wallet.address.list{wallet_id, type?, change?, after?, limit?}`: `{addresses: [<address>], next?}`, one account's chain in
 * index order (`type` defaults to the receiving account); `limit` 1–500.
 */
suspend fun VaultApi.walletAddressList(
    walletId: String,
    type: String,
    change: Boolean,
    after: Int? = null,
    limit: Int? = null,
): JsonObject {
    require(limit == null || limit in 1..ADDRESS_LIST_MAX) { "limit" }
    return device.op(
        "wallet.address.list",
        buildJsonObject {
            put("wallet_id", walletId)
            put("type", type)
            put("change", change)
            after?.let { put("after", it) }
            limit?.let { put("limit", it) }
        },
    )
}

/**
 * `wallet.address.used{wallet_id, addresses}` (1–256 address strings): `{marked, version}`. The vault marks only addresses it
 * issued and ignores the others; an address not in the wallet's network's encoding is `bad_request`.
 */
suspend fun VaultApi.walletAddressUsed(walletId: String, addresses: List<String>): JsonObject {
    require(addresses.size in 1..ADDRESS_USED_MAX) { "1 to $ADDRESS_USED_MAX addresses" }
    return device.op(
        "wallet.address.used",
        buildJsonObject {
            put("wallet_id", walletId)
            put("addresses", JsonArray(addresses.map(::JsonPrimitive)))
        },
    )
}

/** The most addresses one `wallet.address.used` takes. */
const val ADDRESS_USED_MAX = 256

/** The most addresses one `wallet.address.list` page holds. */
const val ADDRESS_LIST_MAX = 500
