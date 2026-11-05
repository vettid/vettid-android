package com.vettid.core.data.map

import com.vettid.core.data.prefs.AppPreferences
import java.net.URI
import java.net.URISyntaxException

/**
 * The tile server of the position screen's map (ANDROID-PLAN §4 "Location", L2; owner decision 2026-11-05: OpenFreeMap is the
 * default). The setting is a style URL and the host the privacy note was last answered for; both live in DataStore
 * ([AppPreferences.mapStyleUrl], [AppPreferences.mapNoteHost]) and are settings, not location data.
 *
 * Rules (the owner's and the lead's): **HTTPS only**, no user name or password, a host; a **changed server takes effect only after
 * the note has been shown again and answered for the new host** ([MapSetting.noteAnswered] compares hosts, so nothing is fetched
 * from a host the member has not been told about).
 */
object MapServer {
    /** OpenFreeMap's "liberty" style, the default server. */
    const val DEFAULT_STYLE = "https://tiles.openfreemap.org/styles/liberty"

    /** Longer than any style URL. */
    const val MAX_LENGTH = 2048

    private const val HTTPS_PORT = 443

    /** Why a typed address is refused, before anything is saved or fetched. */
    enum class Problem { NOT_A_URL, NOT_HTTPS, CREDENTIALS, NO_HOST }

    sealed interface Check {
        /**
         * [url] as stored (trimmed) and the server's lower-case [host] for "This map's server is …": `host`, or `host:port` when the
         * port is not 443 (an explicit other port is another server).
         */
        class Ok(val url: String, val host: String) : Check {
            override fun toString(): String = "MapServer.Ok($host)"
        }

        class Refused(val problem: Problem) : Check
    }

    fun parse(text: String): Check {
        val t = text.trim()
        val problem = problemOf(t)
        val host = if (problem == null) hostOf(t) else null
        return when {
            problem != null -> Check.Refused(problem)
            host.isNullOrEmpty() -> Check.Refused(Problem.NO_HOST)
            else -> Check.Ok(t, host)
        }
    }

    private fun schemeOf(t: String) = t.substringBefore("://", missingDelimiterValue = "").lowercase()

    // `https://@host` parses with an empty user name: any '@' before the path is credentials.
    private fun authorityOf(t: String) = t.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')

    private fun isText(t: String) = t.isNotEmpty() && t.length <= MAX_LENGTH && t.none { it.isWhitespace() || it.isISOControl() }

    private fun problemOf(t: String): Problem? = when {
        !isText(t) || schemeOf(t).isEmpty() -> Problem.NOT_A_URL
        schemeOf(t) != "https" -> Problem.NOT_HTTPS
        '@' in authorityOf(t) -> Problem.CREDENTIALS
        authorityOf(t).isEmpty() -> Problem.NO_HOST
        hostOf(t, parseOnly = true) == null -> Problem.NOT_A_URL
        else -> null
    }

    /** The lower-case host, or null when it does not parse; with [parseOnly] an address with no host reads as "".  */
    private fun hostOf(t: String, parseOnly: Boolean = false): String? = try {
        val uri = URI(t)
        val host = uri.host?.lowercase()
        val shown = if (host != null && uri.port != -1 && uri.port != HTTPS_PORT) "$host:${uri.port}" else host
        if (parseOnly) shown ?: "" else shown
    } catch (_: URISyntaxException) {
        null
    }

    /** The server in force: the stored address when it still passes the rules, else the default. */
    fun of(prefs: AppPreferences): MapSetting {
        val own = prefs.mapStyleUrl?.let { parse(it) as? Check.Ok }
        val server = own ?: (parse(DEFAULT_STYLE) as Check.Ok)
        return MapSetting(
            styleUrl = server.url,
            host = server.host,
            isDefault = own == null,
            noteAnswered = prefs.mapNoteHost.equals(server.host, ignoreCase = true),
        )
    }
}

/** The map server as the settings stand. [noteAnswered]: the privacy note was answered for [host] (and nothing is fetched before). */
class MapSetting(val styleUrl: String, val host: String, val isDefault: Boolean, val noteAnswered: Boolean) {
    override fun toString(): String = "MapSetting($host)"
}
