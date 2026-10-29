package com.vettid.core.chain

import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.Network
import org.bitcoindevkit.NetworkKind
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Wallet

/** W0 spike: a watch-only wallet from the vault's public account descriptors (VAULT-MESSAGING §10.18). No private key. */
object WatchOnly {
    fun open(receive: String, change: String, network: Network): Wallet {
        val kind = if (network == Network.BITCOIN) NetworkKind.MAIN else NetworkKind.TEST
        return Wallet(Descriptor(receive, kind), Descriptor(change, kind), network, Persister.newInMemory())
    }
}
