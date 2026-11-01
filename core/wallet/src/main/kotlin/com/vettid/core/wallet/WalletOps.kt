package com.vettid.core.wallet

import com.vettid.core.vault.ChangeUnansweredException
import com.vettid.core.vault.KeptChange
import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.WalletImport
import com.vettid.core.vault.walletCapsRaise
import com.vettid.core.vault.walletCapsSet
import com.vettid.core.vault.walletAddressList
import com.vettid.core.vault.walletAddressNew
import com.vettid.core.vault.walletAddressUsed
import com.vettid.core.vault.walletCreate
import com.vettid.core.vault.walletGet
import com.vettid.core.vault.walletHistory
import com.vettid.core.vault.walletPsbtInspect
import com.vettid.core.vault.walletSignsOutstanding
import com.vettid.core.vault.walletSign
import com.vettid.core.vault.walletSignResend
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
@Suppress("TooManyFunctions")
interface WalletOps {
    suspend fun list(): List<VaultWallet>

    suspend fun get(walletId: String): VaultWallet

    /**
     * `wallet.create`, a credential operation: a new 24-word phrase on [network] (a vault name: `signet`, `testnet4`), or, with
     * [item], the phrase imported. No limits are sent with it (they follow with [capsSet]).
     */
    suspend fun create(name: String, network: String, password: String, item: WalletImport? = null): VaultWallet

    /**
     * `item.reveal` of the wallet's item, a credential operation: its "Recovery phrase" field. [pin] with the password for the item
     * of a wallet that has a spending limit (`pin_required` otherwise).
     */
    suspend fun revealPhrase(walletId: String, password: String, pin: String? = null): RevealedPhrase

    /**
     * `wallet.caps.set` at [version]: the wallet's new version. Lowers only (sets where there was none); a null member is absent
     * (unchanged). `conflict` if the wallet changed meanwhile. Never raises or removes a limit.
     */
    suspend fun capsSet(walletId: String, version: Long, perTx: Long?, perPeriod: Long?): Long

    /**
     * `wallet.caps.raise`, one credential operation with [pin] and [password]: the limits the wallet will have afterwards (null: no
     * limit) at [version]; the wallet's new version. Any error leaves the limits as they were.
     */
    suspend fun capsRaise(walletId: String, version: Long, perTx: Long?, perPeriod: Long?, pin: String, password: String): Long

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

    /** `wallet.psbt.inspect`: the vault's summary of [psbtBase64] and its `cap`. `invalid_psbt` is a [VaultOpException]. */
    suspend fun inspect(walletId: String, psbtBase64: String): PsbtInspection

    /**
     * `wallet.sign`, a credential operation with [password] over [psbtBase64] (no `broadcast`: the app broadcasts). A lost or late
     * answer is [SignAnswer.Unanswered], whose `resend` sends the same request; never a second `sign` for the same payment.
     */
    suspend fun sign(walletId: String, psbtBase64: String, password: String): SignAnswer

    /** The wallets with a `wallet.sign` that may still apply (also after a lock or a restart): no new send is made for them. */
    suspend fun signsOutstanding(): Set<String>

    /** `wallet.history`: what this vault signed, newest first ([limit] 1–200). */
    suspend fun history(walletId: String, limit: Int?): List<WalletTx>
}

/** [WalletOps] over the open vault's client. */
@Suppress("TooManyFunctions")
class VaultWalletOps(private val api: suspend () -> VaultApi) : WalletOps {
    override suspend fun list(): List<VaultWallet> = WalletWire.wallets(api().walletList())

    override suspend fun get(walletId: String): VaultWallet = WalletWire.wallet(api().walletGet(walletId))

    override suspend fun create(name: String, network: String, password: String, item: WalletImport?): VaultWallet =
        WalletWire.wallet(api().walletCreate(name, network, password, item))

    override suspend fun capsSet(walletId: String, version: Long, perTx: Long?, perPeriod: Long?): Long =
        WalletWire.version(api().walletCapsSet(walletId, version, perTx, perPeriod))

    override suspend fun capsRaise(walletId: String, version: Long, perTx: Long?, perPeriod: Long?, pin: String, password: String): Long =
        WalletWire.version(api().walletCapsRaise(walletId, version, perTx, perPeriod, pin, password))

    override suspend fun revealPhrase(walletId: String, password: String, pin: String?): RevealedPhrase {
        val a = api()
        // The field ids of the wallet's item (`wallet.btc`: two password fields, "Recovery phrase" and "Passphrase").
        val fields = a.itemGet(walletId).fields
        val field = fields.firstOrNull { it.label == PHRASE_LABEL }?.fieldId ?: throw WalletWireException("phrase field")
        val passField = fields.firstOrNull { it.label == PASSPHRASE_LABEL }?.fieldId
        val revealed = a.itemRevealCritical(password, walletId, pin)
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

    override suspend fun inspect(walletId: String, psbtBase64: String): PsbtInspection =
        WalletWire.inspection(api().walletPsbtInspect(walletId, psbtBase64))

    override suspend fun sign(walletId: String, psbtBase64: String, password: String): SignAnswer {
        val a = api()
        var sent = false
        return try {
            SignAnswer.Signed(WalletWire.signed(a.walletSign(walletId, psbtBase64, password) { sent = true }))
        } catch (e: ChangeUnansweredException) {
            unanswered(a, e.kept)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Before the request went out nothing reached the vault: said to the manager, which then gives the payment back.
            if (!sent) e.addSuppressed(SignNotSent())
            throw e
        }
    }

    /** No answer: sending [kept] again is the only way on; it asks for nothing (no password, no UTK) and builds nothing. */
    private fun unanswered(a: VaultApi, kept: KeptChange): SignAnswer.Unanswered = SignAnswer.Unanswered({ kept.forget() }) {
        val o = a.walletSignResend(kept)
        if (o == null) unanswered(a, kept) else SignAnswer.Signed(WalletWire.signed(o))
    }

    override suspend fun signsOutstanding(): Set<String> = api().walletSignsOutstanding()

    override suspend fun history(walletId: String, limit: Int?): List<WalletTx> = WalletWire.history(api().walletHistory(walletId, limit))

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
