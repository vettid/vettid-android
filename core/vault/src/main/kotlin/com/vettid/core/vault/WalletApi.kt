package com.vettid.core.vault

import com.vettid.core.crypto.Base64s
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

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
 * A phrase to import (`wallet.create`'s `item`, VAULT-MESSAGING §10.18): the BIP39 words in a [CharArray], separated by single
 * spaces, and the optional passphrase (printable ASCII, at most 256 bytes). It takes the arrays as its own and never copies them;
 * the caller wipes them. Prints its type name only.
 */
class WalletImport(val mnemonic: CharArray, val passphrase: CharArray? = null) {
    override fun toString(): String = "WalletImport"
}

/**
 * `wallet.create{name, network, credential, utk_id, sealed{password, item?}}`, a credential operation (§3.5.3) that makes a new
 * phrase (24 words) in the vault, or, with [item], imports one: `<wallet>` and the new credential, which [CredentialOps] keeps and
 * acknowledges. No `address_type` (P2TR by default) and no `caps` (the app sets spending limits after the backup, W1d, with
 * [walletCapsSet]). `limit` at 16 wallets; `exists` for an import whose account keys equal an existing wallet's.
 */
suspend fun VaultApi.walletCreate(name: String, network: String, password: String, item: WalletImport? = null): JsonObject =
    credentialOps.credOp(
        "wallet.create",
        { createSealed(password, item).forEach { (k, v) -> put(k, v) } },
        extra = {
            put("name", name)
            put("network", network)
        },
    ).first

/** The sealed payload of `wallet.create`: the password and, for an import, `item: {mnemonic, passphrase?}`. */
internal fun createSealed(password: String, item: WalletImport?): JsonObject = buildJsonObject {
    put("password", password)
    if (item != null) {
        put(
            "item",
            buildJsonObject {
                put("mnemonic", String(item.mnemonic))
                // An empty passphrase is no passphrase: the member's "Advanced" left blank.
                item.passphrase?.takeIf { it.isNotEmpty() }?.let { put("passphrase", String(it)) }
            },
        )
    }
}

/** The most a spending limit can be, in satoshis (VAULT-MESSAGING §10.18: 1 to 2,100,000,000,000,000). */
const val CAPS_MAX_SATS = 2_100_000_000_000_000L

/**
 * `wallet.caps.set{wallet_id, version, per_tx_sats?, per_period_sats?}` (0.56.0): `{version}`, the new one. A normal versioned
 * change that may only **lower** (set a limit where there was none, or a smaller one); a member that is null is absent, so
 * unchanged. A larger value or a removal is [walletCapsRaise]'s. `conflict` on a stale [version].
 */
suspend fun VaultApi.walletCapsSet(walletId: String, version: Long, perTxSats: Long?, perPeriodSats: Long?): JsonObject =
    device.op("wallet.caps.set", capsSetBody(walletId, version, perTxSats, perPeriodSats))

internal fun capsSetBody(walletId: String, version: Long, perTxSats: Long?, perPeriodSats: Long?): JsonObject {
    require(perTxSats == null || perTxSats in 1..CAPS_MAX_SATS) { "per_tx_sats" }
    require(perPeriodSats == null || perPeriodSats in 1..CAPS_MAX_SATS) { "per_period_sats" }
    return buildJsonObject {
        put("wallet_id", walletId)
        put("version", version)
        perTxSats?.let { put("per_tx_sats", it) }
        perPeriodSats?.let { put("per_period_sats", it) }
    }
}

/**
 * `wallet.caps.raise{wallet_id, version, per_tx_sats, per_period_sats, credential, utk_id, sealed{pin, password, item_id,
 * payload_sha256}}` (0.56.0), a credential operation of the holder: raises or removes a limit. [perTx] and [perPeriod] are the
 * values the wallet will have after the request (null: no limit), both always sent, and both bound by `payload_sha256`
 * ([WalletCapsBinding]); `{version, ...}` is the answer. The PIN and the password authorise these numbers on this wallet only.
 * A wrong one is `bad_pin` / `bad_password` and changes nothing.
 */
suspend fun VaultApi.walletCapsRaise(
    walletId: String,
    version: Long,
    perTx: Long?,
    perPeriod: Long?,
    pin: String,
    password: String,
): JsonObject =
    credentialOps.credOp(
        "wallet.caps.raise",
        { capsRaiseSealed(walletId, version, perTx, perPeriod, pin, password).forEach { (k, v) -> put(k, v) } },
        extra = { capsRaiseOuter(walletId, version, perTx, perPeriod).forEach { (k, v) -> put(k, v) } },
    ).first

/** The sealed payload of `wallet.caps.raise`: `{pin, password, item_id, payload_sha256}` (the hash in standard base64). */
internal fun capsRaiseSealed(walletId: String, version: Long, perTx: Long?, perPeriod: Long?, pin: String, password: String): JsonObject =
    buildJsonObject {
        put("pin", pin)
        put("password", password)
        put("item_id", walletId)
        put("payload_sha256", Base64s.encodeStd(WalletCapsBinding.payloadSha256(walletId, version, perTx, perPeriod)))
    }

/** The members of `wallet.caps.raise` outside the sealed payload; a limit removed is a JSON `null`. */
internal fun capsRaiseOuter(walletId: String, version: Long, perTx: Long?, perPeriod: Long?): JsonObject {
    require(perTx == null || perTx in 1..CAPS_MAX_SATS) { "per_tx_sats" }
    require(perPeriod == null || perPeriod in 1..CAPS_MAX_SATS) { "per_period_sats" }
    return buildJsonObject {
        put("wallet_id", walletId)
        put("version", version)
        put("per_tx_sats", perTx?.let(::JsonPrimitive) ?: JsonNull)
        put("per_period_sats", perPeriod?.let(::JsonPrimitive) ?: JsonNull)
    }
}

/**
 * What `wallet.caps.raise` binds the password and PIN to (VAULT-MESSAGING §10.18, the §16 vector): the SHA-256 of
 * `"vettid/wallet.caps/1" 0x00 wallet_id 0x00 version 0x00 per_tx 0x00 per_period`, each field in UTF-8, `0x00` between them and
 * none after the last; `version` in decimal ASCII; `per_tx` and `per_period` the values the wallet will have after the request,
 * decimal ASCII or the literal `none`.
 */
object WalletCapsBinding {
    private const val LABEL = "vettid/wallet.caps/1"
    private const val NONE = "none"

    fun payloadSha256(walletId: String, version: Long, perTx: Long?, perPeriod: Long?): ByteArray {
        val parts = listOf(LABEL, walletId, version.toString(), perTx?.toString() ?: NONE, perPeriod?.toString() ?: NONE)
        val preimage = parts.joinToString("\u0000").toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(preimage)
    }
}

/**
 * `wallet.update{wallet_id, version, address_type}`: `{version}`, the new one. [version] is the one the app last read (`conflict`
 * if the wallet changed meanwhile); [addressType] is `p2tr` or `p2wpkh`.
 */
suspend fun VaultApi.walletUpdate(walletId: String, version: Long, addressType: String): JsonObject =
    device.op(
        "wallet.update",
        buildJsonObject {
            put("wallet_id", walletId)
            put("version", version)
            put("address_type", addressType)
        },
    )

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

/** The most `wallet.history` returns in one answer (VAULT-MESSAGING §10.18: 1–200, default 50). */
const val HISTORY_LIMIT_MAX = 200

/**
 * `wallet.psbt.inspect{wallet_id, psbt}` (§10.18), without the credential: the vault's `<summary>` of the PSBT it would sign, and
 * (0.56.0) `cap: {ok, reason?, remaining_sats?, allowed_after?}` when it implements spending limits. [psbtBase64] is standard base64
 * of at most 65,536 bytes. `invalid_psbt` (the reason is the error's message) means the vault would not sign it; the app shows the
 * vault's summary, never its own figures, and compares the two before it asks for the password.
 */
suspend fun VaultApi.walletPsbtInspect(walletId: String, psbtBase64: String): JsonObject =
    device.op(
        "wallet.psbt.inspect",
        buildJsonObject {
            put("wallet_id", walletId)
            put("psbt", psbtBase64)
        },
    )

/**
 * `wallet.sign{wallet_id, psbt, credential, utk_id, sealed{password, item_id, payload_sha256}}` (§10.18), one credential operation:
 * `<summary>` and `{tx, credential, credential_version, utks}`, `tx` the signed transaction in hex. **No `broadcast` member**: the
 * vault never broadcasts, the app does (`POST /tx`).
 *
 * `item_id` is the wallet and `payload_sha256` the SHA-256 of the PSBT's **decoded bytes** ([WalletSignBinding]), so the password
 * authorises this PSBT of this wallet only. The request is sent as [CredentialOps.credOpKept] sends one: a lost or late answer is
 * [ChangeUnansweredException], carrying the request, and [walletSignResend] sends **the same request** (same inner id and body),
 * which the vault answers from its cache; it is never built again, as a new inner id would count a second time against the
 * wallet's spending limit. Errors: `credential_locked` (open the unlock window and sign again), `invalid_psbt`, `cap_exceeded`
 * (before the password is tried; the UTK is spent), `bad_password`, `backoff`.
 */
suspend fun VaultApi.walletSign(
    walletId: String,
    psbtBase64: String,
    password: String,
    onSending: (() -> Unit)? = null,
): JsonObject =
    credentialOps.credOpKept(
        "wallet.sign",
        { signSealed(walletId, psbtBase64, password).forEach { (k, v) -> put(k, v) } },
        extra = {
            put("wallet_id", walletId)
            put("psbt", psbtBase64)
        },
        walletId = walletId,
        onSending = onSending,
    )

/**
 * The same `wallet.sign` again after [ChangeUnansweredException] ([kept]): the answer, or null when there is still none. The vault's
 * cached answer (or its refusal) is taken as the first answer would have been.
 */
suspend fun VaultApi.walletSignResend(kept: KeptChange): JsonObject? = credentialOps.resendKept(kept)

/** The vault's clock as this phone knows it (the enclave's own time, not the phone's): for windows the vault's `at` is measured in. */
fun VaultApi.walletVaultNow(): java.time.Instant = device.vaultNow()

/**
 * Whether [kept] (an unanswered `wallet.sign`) can no longer apply: the vault's clock is past its `exp` plus the settle margin
 * (§11.14.9). Only then is it given up for good; before that it may have applied (also when `credential.version` has moved on)
 * and the vault answers a resend from its cache.
 */
fun VaultApi.walletSignExpired(kept: KeptChange): Boolean = !device.vaultNow().isBefore(kept.exp.plus(VaultDevice.SETTLE_MARGIN))

/** The sealed payload of `wallet.sign`: `{password, item_id, payload_sha256}` (the hash in standard base64). */
internal fun signSealed(walletId: String, psbtBase64: String, password: String): JsonObject = buildJsonObject {
    put("password", password)
    put("item_id", walletId)
    put("payload_sha256", Base64s.encodeStd(WalletSignBinding.payloadSha256(psbtBase64)))
}

/**
 * What `wallet.sign` binds the password to (§10.18 "Spending is a critical action"): `payload_sha256` is the SHA-256 of the PSBT's
 * **bytes**, that is, of the base64 text decoded, not of the text. The text must be standard base64, as the vault reads it.
 */
object WalletSignBinding {
    fun payloadSha256(psbtBase64: String): ByteArray {
        val bytes = try {
            java.util.Base64.getDecoder().decode(psbtBase64)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("psbt is not base64", e)
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

/**
 * `wallet.history{wallet_id, limit?}` (§10.18): `{transactions: [{txid, at, sending_sats, change_sats, fee_sats, payees: [{address,
 * amount_sats}], connection_id?, invocation_id?}]}`, newest first; what this vault signed (the latest 200). [limit] 1–200 (default 50).
 */
suspend fun VaultApi.walletHistory(walletId: String, limit: Int? = null): JsonObject {
    require(limit == null || limit in 1..HISTORY_LIMIT_MAX) { "limit" }
    return device.op(
        "wallet.history",
        buildJsonObject {
            put("wallet_id", walletId)
            limit?.let { put("limit", it) }
        },
    )
}

/**
 * The wallets with a `wallet.sign` that may still apply (VAULT-MESSAGING §3.5.3, §10.18): sent and not decided, also after a lock, an
 * unlock or a restart (the mark is durable). A new signing for such a wallet would build the payment again and count again against its
 * spending limit, so none is sent. Decided as `pin.change` is: by `credential.version`, which past the version the signing was sent at
 * means it applied (or never will), and by the request's `exp` on the vault's clock. A failed `credential.version` is thrown: the hold
 * stands.
 */
suspend fun VaultApi.walletSignsOutstanding(): Set<String> {
    if (device.unsettledSigns().isEmpty()) return emptySet()
    val (info, _) = credentialVersionAt()
    info.version?.let { device.settleSignsBefore(it) }
    return device.unsettledSigns()
}
