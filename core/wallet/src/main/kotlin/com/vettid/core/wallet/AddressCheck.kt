package com.vettid.core.wallet

import com.vettid.core.chain.Account
import com.vettid.core.chain.AccountType
import com.vettid.core.chain.DescriptorException
import com.vettid.core.chain.Network
import com.vettid.core.chain.Wallet

/** One account's chain: its type (`p2tr`, `p2wpkh`) and whether it is the change chain. */
data class AddressChain(val type: String, val change: Boolean)

/** The outcome of [AddressCheck.check]. */
sealed interface AddressCheckResult {
    /** The app derives the addresses the vault issued: the app's own derivation can be used for look-ups. */
    data class Match(val wallet: Wallet) : AddressCheckResult {
        override fun toString(): String = "Match"
    }

    /** Any difference ("This wallet's addresses don't match your vault's. Nothing was sent."): nothing is looked up. */
    data object Mismatch : AddressCheckResult
}

/**
 * Before the app uses its own derivation (ANDROID-PLAN §4 "The chain source", "Which addresses"): both accounts of the vault's
 * `<wallet>` must parse as their descriptors say, under the wallet's fingerprint and the network's account paths, and the first
 * addresses the vault issued on each chain (`wallet.address.list`) must be the ones the app derives at the same indices. A chain the
 * vault has issued nothing on yet has nothing to compare (the receiving chain always has one by the first look-up: the app asks for
 * it, as Receive does).
 */
object AddressCheck {
    /** How many issued addresses of each chain are compared. */
    const val FIRST = 5

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // every difference is its own way out: a mismatch
    fun check(vault: VaultWallet, network: Network, issued: Map<AddressChain, List<WalletAddress>>): AddressCheckResult {
        val accounts = try {
            AccountType.entries.map { t ->
                val a = vault.account(t.vaultName) ?: return AddressCheckResult.Mismatch
                Account.fromDescriptors(a.receiveDescriptor, a.changeDescriptor, network).also { acc ->
                    // The account's own path and key as the vault states them beside the descriptors.
                    if (acc.path(false, 0).substringBeforeLast("/0/0") != a.path) return AddressCheckResult.Mismatch
                }
            }
        } catch (_: DescriptorException) {
            return AddressCheckResult.Mismatch
        }
        val wallet = try {
            Wallet(network, accounts)
        } catch (_: IllegalArgumentException) {
            return AddressCheckResult.Mismatch
        }
        if (wallet.fingerprintHex != vault.fingerprint.lowercase()) return AddressCheckResult.Mismatch
        for ((chain, addresses) in issued) {
            val type = AccountType.fromVaultName(chain.type) ?: return AddressCheckResult.Mismatch
            val account = wallet.account(type)
            for (a in addresses.sortedBy { it.index }.take(FIRST)) {
                val sameChain = a.type == chain.type && a.change == chain.change
                if (!sameChain || a.index !in 0 until Account.MAX_INDEX) return AddressCheckResult.Mismatch
                if (account.address(a.change, a.index) != a.address) return AddressCheckResult.Mismatch
            }
        }
        return AddressCheckResult.Match(wallet)
    }
}
