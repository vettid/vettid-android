package com.vettid.core.wallet

import com.vettid.core.chain.Network
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

// Everything here that carries wallet data prints its type name only: none of it may reach a log through a string template.

/** One account of a wallet as the vault gives it (`<wallet>.accounts[]`, VAULT-MESSAGING §10.18). */
data class VaultAccount(
    /** `p2tr` or `p2wpkh`. */
    val type: String,
    val path: String,
    val xpub: String,
    val receiveDescriptor: String,
    val changeDescriptor: String,
    val nextReceive: Int,
    val nextChange: Int,
) {
    override fun toString(): String = "VaultAccount"
}

/** A wallet as the vault gives it (`<wallet>`); memory only. */
data class VaultWallet(
    /** The wallet's item's `item_id`. */
    val id: String,
    val version: Long,
    val name: String,
    /** As the vault names it: `mainnet`, `testnet` (testnet3, or a wallet from before 0.37.0), `testnet4`, `signet`, `regtest`. */
    val networkName: String,
    val fingerprint: String,
    /** The receiving account's type. */
    val addressType: String,
    val accounts: List<VaultAccount>,
    val tags: List<String>,
    /** The wallet has a spending limit (`caps` with a per-payment or per-period value): showing its phrase needs the PIN (W1d). */
    val capped: Boolean = false,
    /** `caps.per_tx_sats` (0.56.0): the most one payment may take out; null for no limit, or a vault without caps. */
    val perTxSats: Long? = null,
    /** `caps.per_period_sats`: the most in the rolling period (24 hours; an hour on staging); null for no limit. */
    val perPeriodSats: Long? = null,
) {
    /** The chain, when the name alone says which; null for `testnet` (testnet3 or testnet4: ask, ANDROID-PLAN question 16). */
    val network: Network? get() = if (networkName == Network.TESTNET.vaultName) null else Network.fromVaultName(networkName)

    fun account(type: String): VaultAccount? = accounts.firstOrNull { it.type == type }

    override fun toString(): String = "VaultWallet"
}

/** An issued address (`<address>`). */
data class WalletAddress(
    val address: String,
    val type: String,
    val index: Int,
    val change: Boolean,
    val used: Boolean,
) {
    override fun toString(): String = "WalletAddress"
}

/** A page of `wallet.address.list`. */
data class AddressPage(val addresses: List<WalletAddress>, val next: Int?) {
    override fun toString(): String = "AddressPage"
}

/** The vault's answer was not what VAULT-MESSAGING §10.18 says. Its message names the field only, never a value. */
class WalletWireException(field: String) : IllegalStateException("wallet answer: $field")

/** Reads the §10.18 answers. Strict: a missing or mistyped field is a [WalletWireException]. */
internal object WalletWire {
    /** A wallet's spending limits (0.56.0): either one set makes it capped; `null` is no limit. */
    private val CAP_KEYS = listOf("per_tx_sats", "per_period_sats")

    private fun JsonObject.isNumber(k: String): Boolean = (this[k] as? JsonPrimitive)?.let { !it.isString && it.content != "null" } == true

    private fun JsonObject.number(k: String): Long? = if (isNumber(k)) (this[k] as JsonPrimitive).longOrNull else null

    fun wallets(o: JsonObject): List<VaultWallet> = (o["wallets"] as? JsonArray ?: throw WalletWireException("wallets")).map { wallet(it) }

    @Suppress("ThrowsCount") // one per field: the message names which
    fun wallet(e: JsonElement): VaultWallet {
        val o = e as? JsonObject ?: throw WalletWireException("wallet")
        return VaultWallet(
            id = o.str("wallet_id"),
            version = o.long("version"),
            name = o.str("name"),
            networkName = o.str("network"),
            fingerprint = o.str("fingerprint"),
            addressType = o.str("address_type"),
            accounts = (o["accounts"] as? JsonArray ?: throw WalletWireException("accounts")).map { account(it) },
            tags = (o["tags"] as? JsonArray)?.map { t ->
                (t as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw WalletWireException("tags")
            } ?: emptyList(),
            capped = (o["caps"] as? JsonObject)?.let { c -> CAP_KEYS.any { k -> c.isNumber(k) } } == true,
            perTxSats = (o["caps"] as? JsonObject)?.number("per_tx_sats"),
            perPeriodSats = (o["caps"] as? JsonObject)?.number("per_period_sats"),
        )
    }

    private fun account(e: JsonElement): VaultAccount {
        val o = e as? JsonObject ?: throw WalletWireException("account")
        val d = o["descriptors"] as? JsonObject ?: throw WalletWireException("descriptors")
        return VaultAccount(
            type = o.str("type"),
            path = o.str("path"),
            xpub = o.str("xpub"),
            receiveDescriptor = d.str("receive"),
            changeDescriptor = d.str("change"),
            nextReceive = o.index("next_receive"),
            nextChange = o.index("next_change"),
        )
    }

    fun version(o: JsonObject): Long = o.long("version")

    fun address(e: JsonElement): WalletAddress {
        val o = e as? JsonObject ?: throw WalletWireException("address")
        return WalletAddress(
            address = o.str("address"),
            type = o.str("type"),
            index = o.index("index"),
            change = o.bool("change"),
            used = (o["used"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    fun page(o: JsonObject): AddressPage = AddressPage(
        addresses = (o["addresses"] as? JsonArray ?: throw WalletWireException("addresses")).map { address(it) },
        next = o["next"]?.takeUnless { it is JsonNull }?.let { o.index("next") },
    )

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: throw WalletWireException(key)

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 } ?: throw WalletWireException(key)

    private fun JsonObject.index(key: String): Int = long(key).takeIf { it <= Int.MAX_VALUE }?.toInt() ?: throw WalletWireException(key)

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: throw WalletWireException(key)
}
