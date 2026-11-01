package com.vettid.core.wallet

import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.walletAddressList
import com.vettid.core.vault.walletAddressNew
import com.vettid.core.vault.walletAddressUsed
import com.vettid.core.vault.walletCreate
import com.vettid.core.vault.walletGet
import com.vettid.core.vault.walletList
import com.vettid.core.vault.walletUpdate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A wallet's recovery phrase as `item.reveal` gave it (ANDROID-PLAN §4 "Creating and backing up"): the words in a [CharArray] that
 * [wipe] overwrites. It takes [chars] as its own (no copy is made). Held only while the phrase screen and its check are shown; never
 * on disk, in saved state or in a log. The decrypted item's bytes are wiped once read, but the JSON parser's `String` copies of the
 * value cannot be: they are left to the garbage collector, as every `String` on the JVM is.
 */
class RevealedPhrase(private val chars: CharArray, private val pass: CharArray? = null) {
    @Volatile
    private var wiped = false

    /** The words, in order (none once wiped). A copy: wipe it too. */
    fun words(): List<CharArray> {
        if (wiped) return emptyList()
        val out = ArrayList<CharArray>()
        var start = 0
        for (i in 0..chars.size) {
            if (i == chars.size || chars[i] == ' ') {
                if (i > start) out += chars.copyOfRange(start, i)
                start = i + 1
            }
        }
        return out
    }

    val wordCount: Int get() = words().also { w -> w.forEach { it.fill(Char(0)) } }.size

    /** The BIP39 passphrase the wallet was imported with, or null when it has none (none once wiped). A copy: wipe it too. */
    fun passphrase(): CharArray? = if (wiped) null else pass?.copyOf()

    fun wipe() {
        wiped = true
        chars.fill(Char(0))
        pass?.fill(Char(0))
    }

    override fun toString(): String = "RevealedPhrase"
}

/** The vault side of the wallet (VAULT-MESSAGING §10.18), behind an interface for the manager's tests. */
interface WalletOps {
    suspend fun list(): List<VaultWallet>

    suspend fun get(walletId: String): VaultWallet

    /** `wallet.create`, a credential operation: a new 24-word phrase on [network] (a vault name: `signet`, `testnet4`). */
    suspend fun create(name: String, network: String, password: String): VaultWallet

    /** `item.reveal` of the wallet's item, a credential operation: its "Recovery phrase" field. */
    suspend fun revealPhrase(walletId: String, password: String): RevealedPhrase

    /** `item.delete` of the wallet's item, a credential operation: the vault forgets the wallet and its phrase. */
    suspend fun delete(walletId: String, password: String)

    /** `wallet.update{address_type}` at [version]: the wallet's new version. `conflict` if the wallet changed meanwhile. */
    suspend fun setAddressType(walletId: String, version: Long, type: String): Long

    /** `wallet.address.new` on the receiving account's receive chain. */
    suspend fun newAddress(walletId: String): WalletAddress

    suspend fun addresses(walletId: String, chain: AddressChain, after: Int?, limit: Int): AddressPage

    /** `wallet.address.used` (1–256 addresses). */
    /** `wallet.address.used`: how many of [addresses] the vault marked now (those it issued and had not marked yet). */
    suspend fun markUsed(walletId: String, addresses: List<String>): Int
}

/** [WalletOps] over the open vault's client. */
class VaultWalletOps(private val api: suspend () -> VaultApi) : WalletOps {
    override suspend fun list(): List<VaultWallet> = WalletWire.wallets(api().walletList())

    override suspend fun get(walletId: String): VaultWallet = WalletWire.wallet(api().walletGet(walletId))

    override suspend fun create(name: String, network: String, password: String): VaultWallet =
        WalletWire.wallet(api().walletCreate(name, network, password))

    override suspend fun revealPhrase(walletId: String, password: String): RevealedPhrase {
        val a = api()
        // The field ids of the wallet's item (`wallet.btc`: two password fields, "Recovery phrase" and "Passphrase").
        val fields = a.itemGet(walletId).fields
        val field = fields.firstOrNull { it.label == PHRASE_LABEL }?.fieldId ?: throw WalletWireException("phrase field")
        val passField = fields.firstOrNull { it.label == PASSPHRASE_LABEL }?.fieldId
        val revealed = a.itemRevealCritical(password, walletId)
        try {
            return phraseOf(revealed.json(), field, passField)
        } finally {
            revealed.wipe()
        }
    }

    override suspend fun delete(walletId: String, password: String) = api().itemDelete(walletId, password)

    override suspend fun setAddressType(walletId: String, version: Long, type: String): Long =
        WalletWire.version(api().walletUpdate(walletId, version, type))

    override suspend fun newAddress(walletId: String): WalletAddress = WalletWire.address(api().walletAddressNew(walletId))

    override suspend fun addresses(walletId: String, chain: AddressChain, after: Int?, limit: Int): AddressPage =
        WalletWire.page(api().walletAddressList(walletId, chain.type, chain.change, after, limit))

    override suspend fun markUsed(walletId: String, addresses: List<String>): Int {
        val o = api().walletAddressUsed(walletId, addresses)
        // `{marked, version}`; an answer without the count is taken as "some were marked" (the wallet is read again).
        return (o["marked"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull() ?: addresses.size
    }

    internal companion object {
        const val PHRASE_LABEL = "Recovery phrase"
        const val PASSPHRASE_LABEL = "Passphrase"

        /**
         * The phrase's words from a revealed item's plaintext (`{"fields": [{field_id, value}]}`), normalised to single spaces, and
         * the passphrase from [passFieldId] (vettid-vault `features/wallet`: "Passphrase", empty for a wallet made without one),
         * kept exactly as stored: a passphrase's spaces and case are part of it.
         */
        @Suppress("ThrowsCount")
        fun phraseOf(o: JsonObject, fieldId: String, passFieldId: String? = null): RevealedPhrase {
            val fields = o["fields"] as? JsonArray ?: throw WalletWireException("fields")
            fun value(id: String): String? = fields.firstNotNullOfOrNull { e ->
                (e as? JsonObject)?.takeIf { VaultJson.str(it, "field_id") == id }?.get("value") as? JsonPrimitive
            }?.takeIf { it.isString }?.content
            val phrase = value(fieldId) ?: throw WalletWireException("phrase")
            val words = phrase.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.size !in PHRASE_WORDS) throw WalletWireException("phrase length")
            val pass = passFieldId?.let(::value)?.takeIf { it.isNotEmpty() }
            // The arrays are the phrase's own (RevealedPhrase keeps them and wipes them); no other copy of them is made here.
            return RevealedPhrase(words.joinToString(" ").toCharArray(), pass?.toCharArray())
        }

        /** BIP39's lengths, as the vault accepts them. */
        val PHRASE_WORDS = setOf(12, 15, 18, 21, 24)
    }
}
