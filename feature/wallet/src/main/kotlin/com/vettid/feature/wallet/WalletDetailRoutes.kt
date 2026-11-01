package com.vettid.feature.wallet

import kotlinx.serialization.Serializable

/** Receive: the wallet's address as a QR code and as text. */
@Serializable
data class ReceiveRoute(val walletId: String)

/** The chain server setting of one network ([network] is the vault's name for it: `signet`, `testnet4`). */
@Serializable
data class ChainServerRoute(val network: String)

/** A wallet's details: fingerprint, network, accounts and descriptors. */
@Serializable
data class WalletDetailsRoute(val walletId: String)
