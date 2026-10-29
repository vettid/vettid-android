package com.vettid.core.chain

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.Descriptor
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Script

enum class AccountType(val purpose: Long, val descriptorFunction: String, val vaultName: String) {
    /** BIP84, native segwit v0 (`wpkh`). */
    P2WPKH(84, "wpkh", "p2wpkh"),

    /** BIP86, taproot key path only (`tr` with no script tree). */
    P2TR(86, "tr", "p2tr"),
    ;

    companion object {
        fun fromVaultName(name: String): AccountType? = entries.firstOrNull { it.vaultName == name }
    }
}

class DescriptorException(message: String) : IllegalArgumentException(message)

/**
 * One account of a wallet, from what the vault gives in `wallet.get` (`accounts[].descriptors.receive` and `.change`):
 * `wpkh([73c5da0a/84'/1'/0']tpub.../0/<star>)` and the same with `tr` and 86 (<star> is the wildcard). Public keys only: nothing here
 * can sign. The public surface has no bitcoin-kmp type.
 */
class Account private constructor(
    val type: AccountType,
    val network: Network,
    /** The master key's fingerprint, 4 bytes, as it stands in a PSBT's derivation entries. */
    internal val fingerprint: ByteArray,
    private val xpub: DeterministicWallet.ExtendedPublicKey,
) {
    // Child keys of the account key: branch (0 receive, 1 change) then index. Public derivation only (BIP32 non-hardened).
    private val branches = arrayOf(xpub.derivePublicKey(0L), xpub.derivePublicKey(1L))

    val fingerprintHex: String get() = fingerprint.toHex()

    /** m/84'/c'/0' or m/86'/c'/0' as the vault re-derives it, hardened values (high bit set). */
    internal val originPath: LongArray = longArrayOf(hardened(type.purpose), hardened(network.coinType), hardened(0))

    internal fun publicKey(change: Boolean, index: Int): PublicKey {
        require(index in 0 until MAX_INDEX) { "index out of range" }
        return branches[if (change) 1 else 0].derivePublicKey(index.toLong()).publicKey
    }

    /** The key a PSBT derivation names: compressed (33 bytes) for P2WPKH, the x-only internal key (32 bytes) for P2TR. */
    fun derivationKey(change: Boolean, index: Int): ByteArray {
        val pub = publicKey(change, index)
        return if (type == AccountType.P2TR) pub.xOnly().value.toByteArray() else pub.value.toByteArray()
    }

    /** The output script (scriptPubKey) of the address at that place. */
    fun scriptPubKey(change: Boolean, index: Int): ByteArray {
        val pub = publicKey(change, index)
        return Script.write(
            when (type) {
                AccountType.P2WPKH -> Script.pay2wpkh(pub)
                AccountType.P2TR -> Script.pay2tr(pub.xOnly(), Crypto.TaprootTweak.KeyPathTweak)
            },
        )
    }

    fun address(change: Boolean, index: Int): String {
        val pub = publicKey(change, index)
        return when (type) {
            AccountType.P2WPKH -> Bitcoin.computeBIP84Address(pub, network.chainHash)
            AccountType.P2TR -> Bitcoin.computeBIP86Address(pub, network.chainHash)
        }
    }

    /** `m/84'/1'/0'/1/4`, the form `wallet.psbt.inspect` and `wallet.address.*` use. */
    fun path(change: Boolean, index: Int): String = originString(type, network) + "/" + (if (change) 1 else 0) + "/" + index

    internal fun fullPath(change: Boolean, index: Int): KeyPath = KeyPath(originPath.toList()).append(if (change) 1L else 0L).append(index.toLong())

    companion object {
        /** The vault issues and accepts indices below this (vettid-vault `vms/btc/account.go`, `MaxIndex`). */
        const val MAX_INDEX: Int = 1 shl 20

        private val DESCRIPTOR = Regex("""^(wpkh|tr)\(\[([0-9a-fA-F]{8})((?:/[0-9]+['h])*)]([1-9A-HJ-NP-Za-km-z]+)/([01])/\*\)(?:#([a-z0-9]{8}))?$""")

        internal fun hardened(i: Long): Long = i + 0x80000000L

        internal fun originString(type: AccountType, network: Network): String = "m/${type.purpose}'/${network.coinType}'/0'"

        private class Parsed(val type: AccountType, val fingerprint: ByteArray, val origin: String, val xpub: String, val branch: Int)

        private fun parse(text: String): Parsed {
            val trimmed = text.trim()
            val m = DESCRIPTOR.matchEntire(trimmed) ?: throw DescriptorException("not a descriptor of the two shapes the vault gives")
            val g = m.groupValues
            val (fn, fp, path) = listOf(g[1], g[2], g[3])
            val (key, branch, checksum) = listOf(g[4], g[5], g[6])
            if (checksum.isNotEmpty() && Descriptor.checksum(trimmed.substringBefore('#')) != checksum) throw DescriptorException("bad descriptor checksum")
            val type = AccountType.entries.first { it.descriptorFunction == fn }
            return Parsed(type, fp.lowercase().hexToBytes(), path.replace('h', '\''), key, branch.toInt())
        }

        /** The account from its receive and change descriptors; both must name the same key and origin. */
        fun fromDescriptors(receive: String, change: String, network: Network): Account {
            val r = parse(receive)
            val c = parse(change)
            if (r.branch != 0 || c.branch != 1) throw DescriptorException("receive must end /0/* and change /1/*")
            if (r.type != c.type || !r.fingerprint.contentEquals(c.fingerprint) || r.origin != c.origin || r.xpub != c.xpub) {
                throw DescriptorException("receive and change differ")
            }
            // The vault only signs m/84'/c'/0' and m/86'/c'/0' (§10.18, derivation_path): refuse anything else early.
            if (r.origin != "/${r.type.purpose}'/${network.coinType}'/0'") {
                throw DescriptorException("origin ${r.origin} is not ${originString(r.type, network)}")
            }
            val parentPath = KeyPath(listOf(hardened(r.type.purpose), hardened(network.coinType)))
            val (prefix, key) = try {
                DeterministicWallet.ExtendedPublicKey.decode(r.xpub, parentPath)
            } catch (e: Exception) {
                throw DescriptorException("not an extended public key")
            }
            val wantPrefix = if (network.testKeys) DeterministicWallet.tpub else DeterministicWallet.xpub
            if (prefix != wantPrefix) throw DescriptorException("key version does not match the network")
            if (key.depth != 3) throw DescriptorException("not an account key (depth ${key.depth})")
            if (key.path.lastChildNumber != hardened(0)) throw DescriptorException("not account 0")
            return Account(r.type, network, r.fingerprint, key)
        }
    }
}

/**
 * A wallet as the app holds it: its network and its accounts (the vault creates both, BIP86 and BIP84, from one phrase, so the
 * fingerprint is the same). [account] finds one by type.
 */
class Wallet(val network: Network, val accounts: List<Account>) {
    init {
        require(accounts.isNotEmpty()) { "a wallet has at least one account" }
        require(accounts.map { it.type }.toSet().size == accounts.size) { "one account per type" }
        require(accounts.all { it.network == network }) { "accounts of another network" }
        require(accounts.all { it.fingerprint.contentEquals(accounts[0].fingerprint) }) { "accounts of different keys" }
    }

    val fingerprintHex: String get() = accounts[0].fingerprintHex
    internal val fingerprint: ByteArray get() = accounts[0].fingerprint

    fun account(type: AccountType): Account = accounts.firstOrNull { it.type == type } ?: throw IllegalArgumentException("no ${type.vaultName} account")
}
