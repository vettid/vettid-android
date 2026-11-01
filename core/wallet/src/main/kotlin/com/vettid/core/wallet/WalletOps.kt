package com.vettid.core.wallet

import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.walletAddressList
import com.vettid.core.vault.walletAddressNew
import com.vettid.core.vault.walletAddressUsed
import com.vettid.core.vault.walletCreate
import com.vettid.core.vault.walletGet
import com.vettid.core.vault.walletList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A wallet's recovery phrase as `item.reveal` gave it (ANDROID-PLAN §4 "Creating and backing up"): the words in a [CharArray] that
 * [wipe] overwrites. It takes [chars] as its own (no copy is made). Held only while the phrase screen and its check are shown; never
 * on disk, in saved state or in a log. The decrypted item's bytes are wiped once read, but the JSON parser's `String` copies of the
 * value cannot be: they are left to the garbage collector, as every `String` on the JVM is.
 */
class RevealedPhrase(private val chars: CharArray) {
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

    fun wipe() {
        wiped = true
        chars.fill(Char(0))
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

    /** `wallet.address.new` on the receiving account's receive chain. */
    suspend fun newAddress(walletId: String): WalletAddress

    suspend fun addresses(walletId: String, chain: AddressChain, after: Int?, limit: Int): AddressPage

    /** `wallet.address.used` (1–256 addresses). */
    suspend fun markUsed(walletId: String, addresses: List<String>)
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
        val field = a.itemGet(walletId).fields.firstOrNull { it.label == PHRASE_LABEL }?.fieldId
            ?: throw WalletWireException("phrase field")
        val revealed = a.itemRevealCritical(password, walletId)
        try {
            return phraseOf(revealed.json(), field)
        } finally {
            revealed.wipe()
        }
    }

    override suspend fun delete(walletId: String, password: String) = api().itemDelete(walletId, password)

    override suspend fun newAddress(walletId: String): WalletAddress = WalletWire.address(api().walletAddressNew(walletId))

    override suspend fun addresses(walletId: String, chain: AddressChain, after: Int?, limit: Int): AddressPage =
        WalletWire.page(api().walletAddressList(walletId, chain.type, chain.change, after, limit))

    override suspend fun markUsed(walletId: String, addresses: List<String>) {
        api().walletAddressUsed(walletId, addresses)
    }

    internal companion object {
        const val PHRASE_LABEL = "Recovery phrase"

        /** The phrase's words from a revealed item's plaintext (`{"fields": [{field_id, value}]}`), normalised to single spaces. */
        @Suppress("ThrowsCount")
        fun phraseOf(o: JsonObject, fieldId: String): RevealedPhrase {
            val fields = o["fields"] as? JsonArray ?: throw WalletWireException("fields")
            val value = fields.firstNotNullOfOrNull { e ->
                (e as? JsonObject)?.takeIf { VaultJson.str(it, "field_id") == fieldId }?.get("value") as? JsonPrimitive
            }?.takeIf { it.isString }?.content ?: throw WalletWireException("phrase")
            val words = value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.size !in PHRASE_WORDS) throw WalletWireException("phrase length")
            // The array is the phrase's own (RevealedPhrase keeps it and wipes it); no other copy of it is made here.
            return RevealedPhrase(words.joinToString(" ").toCharArray())
        }

        /** BIP39's lengths, as the vault accepts them. */
        val PHRASE_WORDS = setOf(12, 15, 18, 21, 24)
    }
}
