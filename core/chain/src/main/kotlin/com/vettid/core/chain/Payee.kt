package com.vettid.core.chain

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.utils.Either

/**
 * The address encodings: mainnet (`bc1`, `1`, `3`), the test networks (`tb1`, `m`, `n`, `2`; testnet3, testnet4 and signet share them)
 * and regtest (`bcrt1`).
 */
enum class AddressFamily { MAINNET, TEST, REGTEST }

/** What [Payee.check] found. */
sealed interface PayeeCheck {
    /** A standard address of the wallet's network; [scriptSize] is its output script's length in bytes (for the fee). */
    data class Ok(val scriptSize: Int) : PayeeCheck

    /** A valid address of another encoding than the wallet's network: "This is a <network> address. This wallet is on <network>." */
    data class WrongNetwork(val found: AddressFamily) : PayeeCheck

    /** Not an address the vault would pay (not an address at all, or not a standard script). */
    data object Invalid : PayeeCheck
}

/** The payee's address, checked before anything is built (ANDROID-PLAN §4 "Sending", step 1). */
object Payee {
    private val FAMILIES = listOf(
        AddressFamily.MAINNET to Network.MAINNET,
        AddressFamily.TEST to Network.TESTNET,
        AddressFamily.REGTEST to Network.REGTEST,
    )

    fun familyOf(n: Network): AddressFamily = when (n) {
        Network.MAINNET -> AddressFamily.MAINNET
        Network.REGTEST -> AddressFamily.REGTEST
        else -> AddressFamily.TEST
    }

    fun check(network: Network, address: String): PayeeCheck {
        scriptOf(network, address)?.let { return if (PsbtInspector.isStandard(it)) PayeeCheck.Ok(it.size) else PayeeCheck.Invalid }
        val other = FAMILIES.filter { (family, _) -> family != familyOf(network) }
            .firstNotNullOfOrNull { (family, n) -> scriptOf(n, address)?.let { family to it } }
            ?: return PayeeCheck.Invalid
        return if (PsbtInspector.isStandard(other.second)) PayeeCheck.WrongNetwork(other.first) else PayeeCheck.Invalid
    }

    private fun scriptOf(network: Network, address: String): ByteArray? =
        when (val r = Bitcoin.addressToPublicKeyScript(network.chainHash, address)) {
            is Either.Right -> fr.acinq.bitcoin.Script.write(r.value)
            is Either.Left -> null
        }
}
