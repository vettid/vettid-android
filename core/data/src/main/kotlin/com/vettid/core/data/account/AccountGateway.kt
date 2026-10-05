package com.vettid.core.data.account

import com.vettid.core.altchan.CookiePersistence
import com.vettid.core.altchan.Me
import com.vettid.core.altchan.MemberApiClient
import com.vettid.core.altchan.MemberAuth
import com.vettid.core.altchan.SessionCookieJar
import com.vettid.core.altchan.SignInStatus
import okhttp3.OkHttpClient

/**
 * The member API's account side as the app uses it (MEMBER-API "Auth",
 * "Account"): sign-in by magic link with an optional account PIN, `Me`,
 * sign-out, and the member API client for the vault routes, authenticated
 * as the signed-in member.
 */
interface AccountGateway {
    /** Whether a member session is held on this device. */
    fun hasSession(): Boolean

    /** Asks for a sign-in link by email (the API always answers ok). */
    suspend fun start(email: String)

    /** Exchanges the link's token for a session, or a PIN step. */
    suspend fun verify(email: String, token: String): SignInStatus

    /** The account PIN step (not the vault PIN). */
    suspend fun pin(pin: String): SignInStatus

    suspend fun me(): Me

    /** Ends the member session on this device (the vault stays paired). */
    suspend fun signOut()

    /** Forgets the member session on this device without asking the API (the wipe of a replaced phone). */
    fun forgetLocal()

    /** The member API client for the vault routes. */
    fun member(): MemberApiClient

    /** A development hint shown on the sign-in screens (devStack builds only). */
    val devHint: String? get() = null
}

/**
 * Production: the httpOnly session cookies of account.vettid.org, kept
 * encrypted under a Keystore key ([cookies]).
 */
class SessionAccountGateway(
    apiBase: String,
    manifestUrl: String,
    http: OkHttpClient,
    cookies: CookiePersistence,
) : AccountGateway {
    private val jar = SessionCookieJar(cookies)
    private val client = MemberApiClient(apiBase, manifestUrl, http, MemberAuth.Session(jar))

    override fun hasSession(): Boolean = jar.hasSession()

    override suspend fun start(email: String) = client.authStart(email)

    override suspend fun verify(email: String, token: String): SignInStatus = client.authVerify(email, token)

    override suspend fun pin(pin: String): SignInStatus = client.authPin(pin)

    override suspend fun me(): Me = client.me()

    override suspend fun signOut() = client.signOut()

    override fun forgetLocal() = jar.clear()

    override fun member(): MemberApiClient = client
}
