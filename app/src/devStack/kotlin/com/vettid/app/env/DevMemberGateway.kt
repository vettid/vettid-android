package com.vettid.app.env

import android.content.Context
import com.vettid.core.altchan.Claimed
import com.vettid.core.altchan.MemberApiClient
import com.vettid.core.altchan.MemberApiException
import com.vettid.core.altchan.MemberAuth
import com.vettid.core.altchan.Redeemed
import com.vettid.core.crypto.Bytes
import com.vettid.core.data.account.MemberGateway
import com.vettid.core.keystore.AppApiKey
import okhttp3.OkHttpClient

/**
 * DEVELOPMENT ONLY (`devStack` builds). vettid-vault's member API stand-in
 * serves only the vault routes and names the member by
 * `Authorization: Bearer <user_guid>`; it has no setup codes (MEMBER-API 2.0.0)
 * yet. This gateway simulates the redeem so that the onboarding screens run end
 * to end against the stack:
 *
 * - the typed form: any email with the code [DEV_CODE]; the member's user_guid is
 *   derived from the address, so each test address is its own member with its own
 *   vault;
 * - the QR form and the recovery claim are not simulated (they need the stand-in's
 *   2.0.0 routes).
 *
 * The app key is still made in the Keystore (its public key goes into the sealed
 * enrollment); requests carry the stand-in's bearer instead of its signature.
 */
internal class DevMemberGateway(
    context: Context,
    private val apiBase: String,
    private val manifestUrl: String,
    private val http: OkHttpClient,
) : MemberGateway {
    private val prefs = context.getSharedPreferences("vettid_devstack_account", Context.MODE_PRIVATE)
    private val key = AppApiKey()
    private var client: Pair<String, MemberApiClient>? = null

    override val devHint: String = "The dev stack has no setup codes. Type any email with the code $DEV_CODE " +
        "(each address is its own member); scanning and recovery need the stand-in's MEMBER-API 2.0.0 routes."

    override fun member(): MemberApiClient {
        val guid = prefs.getString(KEY_GUID, null) ?: ""
        client?.let { (g, c) -> if (g == guid) return c }
        return MemberApiClient(apiBase, manifestUrl, http, MemberAuth.Bearer(guid)).also { client = guid to it }
    }

    override fun appKey(): ByteArray = key.spki()

    override fun newAppKey() {
        key.regenerate()
    }

    override suspend fun redeemSecret(secret: String): Redeemed =
        throw MemberApiException(NOT_FOUND, MemberApiException.INVALID_CODE, "not simulated")

    override suspend fun redeemTyped(email: String, code: String): Redeemed {
        if (code != DEV_CODE) throw MemberApiException(NOT_FOUND, MemberApiException.INVALID_CODE, "invalid code")
        val e = email.trim().lowercase()
        val guid = "android-" + Bytes.hex(Bytes.sha256(e.toByteArray())).take(GUID_HEX)
        prefs.edit().putString(KEY_GUID, guid).commit()
        val vid = member().vaultStatus()?.vaultId ?: ""
        return Redeemed(vid, guid, e.first() + "***@" + e.substringAfter('@'))
    }

    override suspend fun claimRecovery(vaultId: String, recoveryId: String): Claimed =
        throw MemberApiException(NOT_FOUND, MemberApiException.NOT_FOUND, "not simulated")

    override fun memberChanged(userGuid: String) {
        prefs.edit().putString(KEY_GUID, userGuid).commit()
    }

    companion object {
        /** TEST-ONLY setup code of the dev stack. */
        const val DEV_CODE = "DEVSTACK"
        private const val NOT_FOUND = 404
        private const val GUID_HEX = 24
        private const val KEY_GUID = "guid"
    }
}
