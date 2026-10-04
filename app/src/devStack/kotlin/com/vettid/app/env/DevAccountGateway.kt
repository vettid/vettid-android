package com.vettid.app.env

import android.content.Context
import com.vettid.core.altchan.Me
import com.vettid.core.altchan.MemberApiClient
import com.vettid.core.altchan.MemberApiException
import com.vettid.core.altchan.MemberAuth
import com.vettid.core.altchan.SignInStatus
import com.vettid.core.crypto.Bytes
import com.vettid.core.data.account.AccountGateway
import okhttp3.OkHttpClient

/**
 * DEVELOPMENT ONLY (`devStack` builds). vettid-vault's member API stand-in
 * serves only the vault routes and names the member by
 * `Authorization: Bearer <user_guid>`; it has no sign-in (auth) or
 * the account routes. This gateway simulates them so that the onboarding
 * screens run end to end against the stack:
 *
 * - no email is sent; the sign-in "link" is the token [DEV_TOKEN] (paste it, or
 *   open `http://127.0.0.1:18081/auth/#t=<token>&e=<email>`);
 * - an address with `+pin` asks for the account PIN [DEV_PIN];
 * - `+registered` is a registered (not yet member) account and `+terms` a member
 *   whose terms are out of date, each for the first check only (as if the
 *   member then accepted the terms on the account site);
 * - the member's user_guid is derived from the address, so each test address
 *   is its own member with its own vault.
 */
internal class DevAccountGateway(
    context: Context,
    private val apiBase: String,
    private val manifestUrl: String,
    private val http: OkHttpClient,
) : AccountGateway {
    private val prefs = context.getSharedPreferences("vettid_devstack_account", Context.MODE_PRIVATE)
    private var client: Pair<String, MemberApiClient>? = null

    override val devHint: String = "No email is sent by the dev stack. Paste the token $DEV_TOKEN. " +
        "Addresses with +pin ask for account PIN $DEV_PIN; +registered and +terms show the terms step once."

    override fun hasSession(): Boolean = prefs.getString(KEY_GUID, null) != null

    override suspend fun start(email: String) {
        prefs.edit().putString(KEY_PENDING, email).apply()
    }

    override suspend fun verify(email: String, token: String): SignInStatus {
        if (token != DEV_TOKEN) throw MemberApiException(UNAUTHORIZED, MemberApiException.UNAUTHORIZED, "invalid link")
        if (email.contains("+pin")) {
            prefs.edit().putString(KEY_PENDING, email).apply()
            return SignInStatus.PIN_REQUIRED
        }
        signIn(email)
        return SignInStatus.SIGNED_IN
    }

    override suspend fun pin(pin: String): SignInStatus {
        if (pin != DEV_PIN) throw MemberApiException(UNAUTHORIZED, MemberApiException.UNAUTHORIZED, "wrong PIN")
        signIn(prefs.getString(KEY_PENDING, null) ?: throw MemberApiException(UNAUTHORIZED, MemberApiException.UNAUTHORIZED))
        return SignInStatus.SIGNED_IN
    }

    private fun signIn(email: String) {
        val guid = "android-" + Bytes.hex(Bytes.sha256(email.trim().lowercase().toByteArray())).take(GUID_HEX)
        prefs.edit().putString(KEY_GUID, guid).putString(KEY_EMAIL, email.trim()).putInt(KEY_CHECKS, 0).remove(KEY_PENDING).apply()
    }

    override suspend fun me(): Me {
        val guid = prefs.getString(KEY_GUID, null) ?: throw MemberApiException(UNAUTHORIZED, MemberApiException.UNAUTHORIZED)
        val email = prefs.getString(KEY_EMAIL, "") ?: ""
        val checks = prefs.getInt(KEY_CHECKS, 0)
        prefs.edit().putInt(KEY_CHECKS, checks + 1).apply()
        val first = email.substringBefore('@').substringBefore('+').replaceFirstChar { it.uppercase() }
        return Me(
            userGuid = guid, email = email, firstName = first, lastName = "(dev)",
            state = if (email.contains("+registered") && checks == 0) "registered" else "member",
            termsNeedAcceptance = email.contains("+terms") && checks == 0,
            pinEnabled = email.contains("+pin"),
        )
    }

    override suspend fun signOut() {
        prefs.edit().remove(KEY_GUID).remove(KEY_EMAIL).apply()
        client = null
    }

    override fun member(): MemberApiClient {
        val guid = prefs.getString(KEY_GUID, null) ?: ""
        client?.let { (g, c) -> if (g == guid) return c }
        return MemberApiClient(apiBase, manifestUrl, http, MemberAuth.Bearer(guid)).also { client = guid to it }
    }

    companion object {
        /** TEST-ONLY sign-in token of the dev stack. */
        const val DEV_TOKEN = "devstack-sign-in-token"
        const val DEV_PIN = "1234"
        private const val UNAUTHORIZED = 401
        private const val GUID_HEX = 24
        private const val KEY_GUID = "guid"
        private const val KEY_EMAIL = "email"
        private const val KEY_PENDING = "pending"
        private const val KEY_CHECKS = "checks"
    }
}
