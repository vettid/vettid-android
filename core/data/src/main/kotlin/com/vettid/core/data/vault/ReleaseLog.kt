package com.vettid.core.data.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** How urgent updating is, as the release log says (RELEASE-UPDATES §5). Information only. */
enum class ReleaseSecurity(val wire: String) {
    NONE("none"),
    RECOMMENDED("recommended"),
    URGENT("urgent"),
    ;

    companion object {
        fun of(wire: String): ReleaseSecurity? = entries.firstOrNull { it.wire == wire }
    }
}

/** One release log entry as the app shows it: plain text, already cut to the RELEASE-UPDATES §5 limits. */
data class ReleaseLogEntry(
    val release: Long,
    val summary: String,
    val changes: List<String>,
    val security: ReleaseSecurity,
    val securityText: String?,
)

/** A release between the vault's and the offered one: "Also in earlier releases" (one line each). */
data class EarlierRelease(val release: Long, val summary: String)

/** What's new in a release (ANDROID-PLAN 0.1.31): the log entry, or why there is none. */
sealed interface ReleaseNotes {
    /** The entry of the offered release from [host]'s release log, and the summaries of the releases in between. */
    data class Available(val host: String, val entry: ReleaseLogEntry, val earlier: List<EarlierRelease>) : ReleaseNotes

    /**
     * "Release notes unavailable": `notes` is not the channel's log URL, the fetch failed, or the log has no entry
     * for the release with the manifest's PCR0. Updating stays possible.
     */
    data object Unavailable : ReleaseNotes
}

/** What's new for the update screen, the unlock offer and the "Vault updates" notification. */
interface ReleaseNotesRepository {
    /**
     * What's new in [target] (a release of the trusted manifest), with the summaries of [between] (the manifest's
     * releases between the vault's and [target]). Never throws (but for cancellation); never decides anything.
     */
    suspend fun whatsNew(target: ReleaseView, between: List<ReleaseView>): ReleaseNotes
}

/**
 * The rules of What's new (ANDROID-PLAN 0.1.31 §4, RELEASE-UPDATES 0.3.0 §5), pure: which URL may be fetched, and
 * what of `index.json` (vettid.org `lib/vault/release-log.ts` `LogIndex`) is used.
 */
object ReleaseLog {
    /** At most 1 MiB of `index.json` is read. */
    const val MAX_BYTES = 1 shl 20
    const val TIMEOUT_S = 10L
    const val MAX_SUMMARY = 160
    const val MAX_CHANGES = 20
    const val MAX_CHANGE = 280
    const val MAX_SECURITY_TEXT = 1000
    private const val ELLIPSIS = "…"
    private const val LOG_PATH = "/security/releases/"
    const val INDEX = "index.json"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The origin of the build's pinned manifest URL (`https://vettid.org`; staging `https://staging.vettid.org`;
     * the dev stack's), or null for a URL without one.
     */
    fun origin(manifestUrl: String): String? {
        val u = manifestUrl.toHttpUrlOrNull() ?: return null
        val defaultPort = if (u.scheme == "https") HTTPS_PORT else HTTP_PORT
        return "${u.scheme}://${u.host}" + if (u.port == defaultPort) "" else ":${u.port}"
    }

    /** The host shown in "From <host>". */
    fun host(manifestUrl: String): String? = manifestUrl.toHttpUrlOrNull()?.host

    /**
     * The only URL the app fetches for [target]: `<origin>/security/releases/index.json`, and only when the trusted
     * manifest's `notes` for the release is exactly `<origin>/security/releases/<N>/`, where `<origin>` is the
     * pinned manifest's; null otherwise (any other `notes`: no fetch).
     */
    fun indexUrl(manifestUrl: String, target: ReleaseView): String? {
        val o = origin(manifestUrl)
        return if (o != null && target.number > 0 && target.notes == "$o$LOG_PATH${target.number}/") "$o$LOG_PATH$INDEX" else null
    }

    /** `notes` opened by "Full release notes": an `https` URL only (the manifest parser already requires it). */
    fun openable(notes: String): Boolean = notes.toHttpUrlOrNull()?.scheme == "https"

    /**
     * What's new from `index.json` [body]: the entry whose `release` is [target]'s number AND whose `pcr0` is the
     * manifest's PCR0 for it; null when there is none (or the document is not a log). [between] entries are kept
     * the same way, each matched on number and PCR0, newest first.
     */
    fun parse(body: String, target: ReleaseView, between: List<ReleaseView>): Pair<ReleaseLogEntry, List<EarlierRelease>>? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
        val objects = (root?.get("releases") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        fun find(r: ReleaseView) = objects.firstNotNullOfOrNull { o -> if (matches(o, r)) entry(o, r.number) else null }
        val entry = find(target) ?: return null
        val earlier = between.filter { it.number in 1 until target.number }.sortedByDescending { it.number }.mapNotNull { r ->
            find(r)?.let { EarlierRelease(r.number, it.summary) }
        }
        return entry to earlier
    }

    private fun matches(o: JsonObject, r: ReleaseView): Boolean =
        (o["release"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull == r.number && string(o, "pcr0") == r.pcr0

    private fun entry(o: JsonObject, number: Long): ReleaseLogEntry? {
        val summary = string(o, "summary")?.let { plain(it, MAX_SUMMARY) }?.takeIf { it.isNotEmpty() }
        val raw = (o["changes"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        val changes = raw?.takeIf { null !in it }?.filterNotNull()
            ?.map { plain(it, MAX_CHANGE) }?.filter { it.isNotEmpty() }?.take(MAX_CHANGES)
        val security = string(o, "security")?.let { ReleaseSecurity.of(it) }
        if (summary == null || changes == null || security == null) return null
        val securityText = string(o, "security_text")?.let { plain(it, MAX_SECURITY_TEXT) }?.takeIf { it.isNotEmpty() }
        return ReleaseLogEntry(number, summary, changes, security, securityText?.takeIf { security != ReleaseSecurity.NONE })
    }

    private fun string(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * Plain text: control and format characters (bidi overrides and the like) become spaces, runs of white space
     * collapse to one, and text past [max] characters is cut with "…" (never inside a surrogate pair).
     */
    fun plain(s: String, max: Int): String {
        val b = StringBuilder(minOf(s.length, max + 1))
        var space = false
        for (c in s) {
            if (b.length > max) break
            val t = Character.getType(c)
            if (c.isWhitespace() || t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt()) {
                space = b.isNotEmpty()
            } else {
                if (space) b.append(' ')
                space = false
                b.append(c)
            }
        }
        if (b.length <= max) return b.toString()
        var end = max - ELLIPSIS.length
        if (end > 0 && Character.isHighSurrogate(b[end - 1])) end--
        return b.substring(0, end).trimEnd() + ELLIPSIS
    }

    private const val HTTPS_PORT = 443
    private const val HTTP_PORT = 80
}

/**
 * Fetches What's new from the channel's release log (ANDROID-PLAN 0.1.31): only `index.json` on the pinned manifest's
 * host, only when [ReleaseLog.indexUrl] allows it; GET without cookies or credentials, redirects refused, a
 * [ReleaseLog.TIMEOUT_S] second timeout, at most [ReleaseLog.MAX_BYTES], JSON only. A found entry is kept in memory
 * per host, release and PCR0 until the process ends; nothing is stored. Failures are not kept: the next look retries.
 */
class ReleaseNotesClient(base: OkHttpClient, private val manifestUrl: String) : ReleaseNotesRepository {
    private val http = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .callTimeout(ReleaseLog.TIMEOUT_S, TimeUnit.SECONDS)
        .build()
    private val found = ConcurrentHashMap<Triple<String, Long, String>, ReleaseNotes.Available>()

    override suspend fun whatsNew(target: ReleaseView, between: List<ReleaseView>): ReleaseNotes {
        val url = ReleaseLog.indexUrl(manifestUrl, target)
        val host = ReleaseLog.host(manifestUrl)
        if (url == null || host == null) return ReleaseNotes.Unavailable
        val key = Triple(host, target.number, target.pcr0)
        val notes = found[key] ?: fetchOrNull(url)?.let { ReleaseLog.parse(it, target, between) }?.let { (entry, earlier) ->
            ReleaseNotes.Available(host, entry, earlier).also { found[key] = it }
        }
        return notes ?: ReleaseNotes.Unavailable
    }

    /** Network failures, timeouts and refusals alike: null. */
    private suspend fun fetchOrNull(url: String): String? = try {
        withContext(Dispatchers.IO) { fetch(url) }
    } catch (_: IOException) {
        null
    }

    private fun fetch(url: String): String? {
        val req = Request.Builder().url(url).get().header("Accept", "application/json").build()
        return http.newCall(req).execute().use { r ->
            val type = r.body.contentType()
            val json = type != null && type.type == "application" && (type.subtype == "json" || type.subtype.endsWith("+json"))
            if (r.code != HTTP_OK || !json || r.body.contentLength() > ReleaseLog.MAX_BYTES) return@use null
            val src = r.body.source()
            src.request(ReleaseLog.MAX_BYTES.toLong() + 1)
            if (src.buffer.size > ReleaseLog.MAX_BYTES) return@use null
            src.buffer.readUtf8()
        }
    }

    private companion object {
        const val HTTP_OK = 200
    }
}
