package com.vettid.core.data.account

import com.vettid.core.altchan.AppKeySigner
import com.vettid.core.altchan.Claimed
import com.vettid.core.altchan.MemberApiClient
import com.vettid.core.altchan.MemberAuth
import com.vettid.core.altchan.Redeemed
import okhttp3.OkHttpClient

/**
 * The member API as the app uses it since MEMBER-API 2.0.0 (VAULT-MESSAGING §11.12): no sign-in. The app redeems
 * the portal's setup code (or claims a recovery) with its app key, and every request is signed by that key.
 */
interface MemberGateway {
    /** The member API client for the vault routes, signed by the app key for the current vault. */
    fun member(): MemberApiClient

    /** The app key's SPKI DER (`app_key`, `app.api_key`, `api_key`). */
    fun appKey(): ByteArray

    /** A new app key for a new vault (the old one is deleted). */
    fun newAppKey()

    /** Redeems a scanned setup QR's (or App Link's) secret. */
    suspend fun redeemSecret(secret: String): Redeemed

    /** Redeems a typed setup code with the member's email. */
    suspend fun redeemTyped(email: String, code: String): Redeemed

    /** Claims the recovery named by a scanned recovery QR. */
    suspend fun claimRecovery(vaultId: String, recoveryId: String): Claimed

    /** The member on this phone changed (a redeem or a claim): requests of the dev stack name them. */
    fun memberChanged(userGuid: String) {}

    /** A development hint shown on the setup screens (devStack builds only). */
    val devHint: String? get() = null
}

/** Production and staging: every request signed by the app key ([signer]), for the vault [vaultId] names. */
class AppKeyMemberGateway(
    apiBase: String,
    manifestUrl: String,
    http: OkHttpClient,
    private val signer: AppKeySigner,
    private val regenerate: () -> Unit,
    vaultId: () -> String?,
) : MemberGateway {
    private val client = MemberApiClient(apiBase, manifestUrl, http, MemberAuth.AppKey(signer, vaultId))

    override fun member(): MemberApiClient = client

    override fun appKey(): ByteArray = signer.spki()

    override fun newAppKey() = regenerate()

    override suspend fun redeemSecret(secret: String): Redeemed = client.redeemSecret(secret, signer.spki())

    override suspend fun redeemTyped(email: String, code: String): Redeemed = client.redeemTyped(email, code, signer.spki())

    override suspend fun claimRecovery(vaultId: String, recoveryId: String): Claimed =
        client.recoveryClaim(vaultId, recoveryId, signer.spki())
}
