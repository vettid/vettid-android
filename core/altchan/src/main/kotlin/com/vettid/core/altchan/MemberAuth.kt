package com.vettid.core.altchan

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * How requests to the member API are authenticated.
 *
 * - [Session]: the production model (MEMBER-API "Session model"): the
 *   httpOnly `vid_id` / `vid_rt` / `vid_pin` cookies live in [cookies]; every
 *   state-changing request (POST, DELETE) carries `X-VettID-CSRF: 1`; an
 *   expired `vid_id` is renewed by the server from `vid_rt`, and a 401 is
 *   retried once after `POST /api/auth/refresh`.
 * - [Bearer]: the local dev stack's member API stand-in (vettid-vault
 *   `internal/memberapitest`), which names the member by
 *   `Authorization: Bearer <user_guid>`. Development and tests only.
 */
sealed class MemberAuth {
    internal abstract fun apply(b: Request.Builder, method: String)

    class Session(val cookies: SessionCookieJar = SessionCookieJar()) : MemberAuth() {
        override fun apply(b: Request.Builder, method: String) {
            if (method == "POST" || method == "DELETE") b.header(CSRF_HEADER, "1")
        }
    }

    class Bearer(private val userGuid: String) : MemberAuth() {
        override fun apply(b: Request.Builder, method: String) {
            b.header("Authorization", "Bearer $userGuid")
        }
    }

    companion object {
        const val CSRF_HEADER = "X-VettID-CSRF"
    }
}

/** Where the session cookies are kept between app runs (A3: encrypted under a Keystore key). Holds secrets. */
interface CookiePersistence {
    fun load(): List<String>

    fun save(cookies: List<String>)
}

/**
 * The member session's cookies: host-only, path-scoped as the server sets
 * them, expired ones dropped. Optionally persisted (as Set-Cookie strings
 * with their origin) through [persistence].
 */
class SessionCookieJar(private val persistence: CookiePersistence? = null) : CookieJar {
    private val cookies = ArrayList<Pair<String, Cookie>>() // origin URL, cookie

    init {
        persistence?.load()?.forEach { line ->
            val (origin, set) = line.split(' ', limit = 2).let { if (it.size == 2) it[0] to it[1] else return@forEach }
            val url = origin.toHttpUrlOrNull() ?: return@forEach
            Cookie.parse(url, set)?.let { cookies.add(origin to it) }
        }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val origin = url.newBuilder().encodedPath("/").query(null).build().toString()
        for (c in cookies) {
            this.cookies.removeAll { (_, old) -> old.name == c.name && old.domain == c.domain && old.path == c.path }
            if (c.expiresAt > System.currentTimeMillis()) this.cookies.add(origin to c)
        }
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.second.expiresAt <= now }
        return cookies.map { it.second }.filter { it.matches(url) }
    }

    /** Whether a session (an ID or refresh cookie) is present. */
    @Synchronized
    fun hasSession(): Boolean = cookies.any { it.second.name == "vid_id" || it.second.name == "vid_rt" }

    /** Forgets the session (sign-out on this device). */
    @Synchronized
    fun clear() {
        cookies.clear()
        persist()
    }

    private fun persist() {
        persistence?.save(cookies.map { (origin, c) -> "$origin $c" })
    }
}
