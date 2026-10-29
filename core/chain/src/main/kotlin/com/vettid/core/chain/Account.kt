package com.vettid.core.chain

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.Descriptor
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.XonlyPublicKey

enum class AccountType(val purpose: Long, val descriptorFunction: String) {
    /** BIP84, native segwit v0 (`wpkh`). */
    P2WPKH(84, "wpkh"),

    /** BIP86, taproot key path only (`tr` with no script tree). */
    P2TR(86, "tr"),
}

class DescriptorException(message: String) : IllegalArgumentException(message)

/**
 * One account of a wallet, from what the vault gives in `wallet.get` (`accounts[].descriptors.receive` and `.change`):
 * `wpkh([73c5da0a/84'/1'/0']tpub.../0/<star>)` and the same with `tr` and 86 (<star> is the wildcard). Public keys only: nothing here can sign.
 */
class Account private constructor(
    val type: AccountType,
    val network: Network,
    /** Master key fingerprint (the wallet's), as in the PSBT derivation entries. */
    val fingerprint: Long,
    /** m/84'/c'/0' or m/86'/c'/0'. */
    val origin: KeyPath,
    private val xpub: DeterministicWallet.ExtendedPublicKey,
) {
    // Child keys of the account key: branch (0 receive, 1 change) then index. Public derivation only (BIP32 non-hardened).
    private val branches = arrayOf(xpub.derivePublicKey(0L), xpub.derivePublicKey(1L))

    fun publicKey(change: Boolean, index: Int): PublicKey {
        require(index >= 0) { "index out of range" }
        return branches[if (change) 1 else 0].derivePublicKey(index.toLong()).publicKey
    }

    /** The BIP86 internal key (x-only); for a BIP84 account there is none. */
    fun internalKey(change: Boolean, index: Int): XonlyPublicKey = publicKey(change, index).xOnly()

    /** The full derivation path m/purpose'/coin'/0'/branch/index that the vault re-derives and checks. */
    fun path(change: Boolean, index: Int): KeyPath = origin.append(if (change) 1L else 0L).append(index.toLong())

    fun script(change: Boolean, index: Int): ByteVector {
        val pub = publicKey(change, index)
        return ByteVector(
            Script.write(
                when (type) {
                    AccountType.P2WPKH -> Script.pay2wpkh(pub)
                    AccountType.P2TR -> Script.pay2tr(pub.xOnly(), Crypto.TaprootTweak.KeyPathTweak)
                },
            ),
        )
    }

    fun address(change: Boolean, index: Int): String {
        val pub = publicKey(change, index)
        return when (type) {
            AccountType.P2WPKH -> Bitcoin.computeBIP84Address(pub, network.chainHash)
            AccountType.P2TR -> Bitcoin.computeBIP86Address(pub, network.chainHash)
        }
    }

    companion object {
        private val DESCRIPTOR = Regex("""^(wpkh|tr)\(\[([0-9a-fA-F]{8})((?:/[0-9]+['h])*)]([1-9A-HJ-NP-Za-km-z]+)/([01])/\*\)(?:#([a-z0-9]{8}))?$""")

        private class Parsed(val type: AccountType, val fingerprint: Long, val origin: String, val xpub: String, val branch: Int)

        private fun parse(text: String): Parsed {
            val m = DESCRIPTOR.matchEntire(text.trim()) ?: throw DescriptorException("not a descriptor of the two shapes the vault gives")
            val (fn, fp, path, key, branch, checksum) = m.destructured
            if (checksum.isNotEmpty() && Descriptor.checksum(text.trim().substringBefore('#')) != checksum) throw DescriptorException("bad descriptor checksum")
            val type = AccountType.entries.first { it.descriptorFunction == fn }
            return Parsed(type, fp.toLong(16), path.replace('h', '\''), key, branch.toInt())
        }

        /** The account from its receive and change descriptors; both must name the same key and origin. */
        fun fromDescriptors(receive: String, change: String, network: Network): Account {
            val r = parse(receive)
            val c = parse(change)
            if (r.branch != 0 || c.branch != 1) throw DescriptorException("receive must end /0/* and change /1/*")
            if (r.type != c.type || r.fingerprint != c.fingerprint || r.origin != c.origin || r.xpub != c.xpub) throw DescriptorException("receive and change differ")
            val origin = KeyPath("m" + r.origin)
            // The vault only signs m/84'/c'/0' and m/86'/c'/0' (§10.18, derivation_path): refuse anything else early.
            val expected = listOf(DeterministicWallet.hardened(r.type.purpose), DeterministicWallet.hardened(network.coinType), DeterministicWallet.hardened(0))
            if (origin.path != expected) throw DescriptorException("origin ${origin.asString('\'')} is not m/${r.type.purpose}'/${network.coinType}'/0'")
            val (prefix, key) = DeterministicWallet.ExtendedPublicKey.decode(r.xpub, KeyPath(expected.dropLast(1)))
            val wantPrefix = if (network.testKeys) DeterministicWallet.tpub else DeterministicWallet.xpub
            if (prefix != wantPrefix) throw DescriptorException("key version does not match the network")
            if (key.depth != 3) throw DescriptorException("not an account key (depth ${key.depth})")
            return Account(r.type, network, r.fingerprint, origin, key)
        }
    }
}
