package com.vettid.core.data.items

import java.net.URLDecoder

/**
 * The canonical stored form of each value kind (VAULT-MESSAGING 0.55.3 §10.7 "Canonical forms", number rule 0.55.2; ANDROID-PLAN
 * 0.1.68 §4 "Items: phone values and the tag preview"): the one place a value is canonicalised, so the same function (and
 * the same tests) serve every writer. Applied on save, only to a field the member edited or created; an untouched
 * field is sent exactly as stored, and an older value is stored in this form only when its field is next edited and
 * saved. `phone` is the one kind not here: it needs libphonenumber and the phone's region (`PhoneInput.stored`).
 *
 * White space is the Unicode `White_Space` property ([isWhiteSpace]); case mapping is ASCII only ([lower], [upper]).
 * A text that does not have the shape of its kind is returned as it is, so that the check against the §10.7 table
 * ([ItemChecks]) refuses it with its existing message; nothing here rejects.
 */
@Suppress("TooManyFunctions")
object CanonicalForms {
    private const val OTPAUTH = "otpauth://"
    private const val HEX = "0123456789ABCDEF"
    private const val NIBBLE = 4
    private const val LOW_NIBBLE = 0x0F
    private const val BYTE = 0xFF
    private const val MIN_SECRET = 16
    private const val MAX_SECRET = 256
    private val schemeRe = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val opaqueRe = Regex("^[A-Za-z][A-Za-z0-9+-]*$")
    private val plainSchemes = listOf("mailto", "tel", "sms")

    /** Unicode `White_Space` (not Kotlin's `isWhitespace`, which differs at U+001C–U+001F, U+180E and the no-break spaces). */
    @Suppress("MagicNumber")
    fun isWhiteSpace(c: Char): Boolean = when (c.code) {
        in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }

    /** ASCII `A`–`Z` to `a`–`z`; every other character as it is. */
    fun lower(s: String): String = s.map { if (it in 'A'..'Z') it + ('a' - 'A') else it }.joinToString("")

    /** ASCII `a`–`z` to `A`–`Z`; every other character as it is. */
    fun upper(s: String): String = s.map { if (it in 'a'..'z') it - ('a' - 'A') else it }.joinToString("")

    /** [s] without its leading and trailing White_Space. */
    fun trim(s: String): String = s.trim(::isWhiteSpace)

    /** [s] trimmed, with every inner run of White_Space one space. */
    fun collapse(s: String): String {
        val out = StringBuilder()
        var gap = false
        for (c in trim(s)) {
            if (isWhiteSpace(c)) {
                gap = true
            } else {
                if (gap) out.append(' ')
                gap = false
                out.append(c)
            }
        }
        return out.toString()
    }

    /**
     * The canonical value of a field of [kind] typed as [text], for a field labelled [label] (the `otp` label); null
     * for a kind this does not canonicalise (`password`, `multiline`, `phone`, `address`, `file`, an unknown kind):
     * those are stored as typed. [monthYear]: the date is entered as a month and year.
     */
    fun text(kind: String, text: String, label: String, monthYear: Boolean = false): String? = when (kind) {
        FieldKinds.NUMBER -> number(text)
        FieldKinds.DATE -> date(text, monthYear)
        FieldKinds.EMAIL -> email(text)
        FieldKinds.URL -> url(text)
        FieldKinds.OTP -> otp(text, label)
        FieldKinds.TEXT -> trim(text)
        else -> null
    }

    /** [f] with its value in canonical form (the label is canonicalised by the caller: [trim]). */
    fun field(f: DraftField): DraftField = when {
        f.kept -> f
        f.kind == FieldKinds.ADDRESS -> f.copy(address = address(f.address))
        else -> text(f.kind, f.text, trim(f.label), f.monthYear)?.let { f.copy(text = it) } ?: f
    }

    /**
     * `number` (VAULT-MESSAGING 0.55.2, owner decision of 2026-10-22): as typed, except that leading and trailing
     * White_Space is trimmed and a single leading `+` is dropped. Leading zeros (`007`), `-0`, trailing fractional
     * zeros (`1.0`) and a trailing point are kept as typed (the last is refused by the grammar, like `.5`).
     */
    fun number(s: String): String = trim(s).let { if (it.startsWith('+')) it.substring(1) else it }

    /**
     * `date`: [monthYear] says how the field is entered (the editor's mode: the template's `"format": "month"` hint
     * on an empty field, or a stored value of the form `YYYY-MM`). The value is kept as typed (a new day field `YYYY-MM-DD`, a month field
     * `YYYY-MM`); a day is never invented and never cut: a full date in a month field is refused by [ItemChecks] (VAULT-MESSAGING 0.55.3).
     */
    @Suppress("UnusedParameter")
    fun date(s: String, monthYear: Boolean): String = s

    /** `email`: trimmed, the domain (after the last `@`) lower-cased, the local part as typed. */
    fun email(s: String): String {
        val t = trim(s)
        val at = t.lastIndexOf('@')
        return if (at < 0) t else t.substring(0, at + 1) + lower(t.substring(at + 1))
    }

    /**
     * `url`: trimmed. `scheme://` keeps its scheme and its host lower-cased. A text with no `/` before its first
     * `:`, whose part before it is a scheme without a `.` and that is not `host:port` (digits up to `/`, `?`, `#` or
     * the end), keeps its scheme lower-cased and the rest as typed (`urn:isbn:1`, `bitcoin:bc1qxyz`, `geo:1,2`,
     * `foo:`, `C:\path`, `MAILTO:a@b.c`, `tel:` and `sms:`, VAULT-MESSAGING 0.55.3); the rules apply in this
     * order and the first match wins. Anything else gets `https://` in front and then its
     * host lower-cased (`localhost:8080/x`, `Example.com:443/A`). Path, query and fragment as typed.
     */
    fun url(s: String): String {
        val t = trim(s)
        val scheme = schemeRe.find(t)?.value
        val opaque = if (scheme == null) opaqueScheme(t) else null
        return when {
            t.isEmpty() -> t
            scheme != null -> lower(scheme) + hostLowered(t.substring(scheme.length))
            opaque != null -> lower(opaque) + t.substring(opaque.length)
            else -> url("https://$t")
        }
    }

    /** The `scheme:` of [t] when it keeps it (see [url]), else null. */
    private fun opaqueScheme(t: String): String? {
        val colon = t.indexOf(':')
        val slash = t.indexOf('/')
        val scheme = t.substring(0, maxOf(colon, 0))
        val port = t.substring(colon + 1).takeWhile { it != '/' && it != '?' && it != '#' }
        val hostPort = port.isNotEmpty() && port.all { it in '0'..'9' } && lower(scheme) !in plainSchemes
        val keeps = colon > 0 && (slash < 0 || slash > colon) && opaqueRe.matches(scheme) && !hostPort
        return t.substring(0, colon + 1).takeIf { keeps }
    }

    /** [rest] (what follows `scheme://`) with its host lower-cased: the part after `userinfo@`, up to `:port`. */
    private fun hostLowered(rest: String): String {
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        val userinfo = authority.substring(0, authority.lastIndexOf('@') + 1)
        val hostPort = authority.substring(userinfo.length)
        // A bracketed IPv6 host runs to its `]`; any other host to the `:` of the port.
        val end = if (hostPort.startsWith("[")) hostPort.indexOf(']') + 1 else hostPort.indexOf(':')
        val hostEnd = if (end > 0 || (end == 0 && hostPort.startsWith(":"))) end else hostPort.length
        return userinfo + lower(hostPort.substring(0, hostEnd)) + hostPort.substring(hostEnd) + rest.substring(authorityEnd)
    }

    /**
     * `otp`: an `otpauth://` URI keeps its parameters, its `secret` in canonical form; a bare base32 secret (upper
     * case, no spaces, no `=` padding) becomes `otpauth://totp/<label>?secret=…&algorithm=SHA1&digits=6&period=30`.
     * A secret that is not base32 of 16–256 characters is left as typed, for the check to refuse.
     */
    @Suppress("ReturnCount")
    fun otp(s: String, label: String): String {
        if (s.startsWith(OTPAUTH, ignoreCase = true)) return otpUri(s)
        val secret = secret(s) ?: return s
        return "${OTPAUTH}totp/${encodeLabel(trim(label))}?secret=$secret&algorithm=SHA1&digits=6&period=30"
    }

    private fun otpUri(uri: String): String {
        val q = uri.indexOf('?')
        if (q < 0) return uri
        val end = uri.indexOf('#', q).let { if (it < 0) uri.length else it }
        val query = uri.substring(q + 1, end).split('&').joinToString("&") { p ->
            val eq = p.indexOf('=')
            if (eq > 0 && p.substring(0, eq).equals("secret", ignoreCase = true)) {
                val decoded = runCatching { URLDecoder.decode(p.substring(eq + 1), "UTF-8") }.getOrNull()
                p.substring(0, eq + 1) + (decoded?.let(::secret) ?: p.substring(eq + 1))
            } else {
                p
            }
        }
        return uri.substring(0, q + 1) + query + uri.substring(end)
    }

    /** A base32 secret in canonical form (upper case, no white space, no padding), or null if it is not one. */
    private fun secret(s: String): String? {
        val t = upper(s.filterNot(::isWhiteSpace)).trimEnd('=')
        return t.takeIf { it.length in MIN_SECRET..MAX_SECRET && it.all { c -> c in 'A'..'Z' || c in '2'..'7' } }
    }

    /** [label] as UTF-8 with every byte but the RFC 3986 unreserved characters `A-Za-z0-9-._~` as `%XX` (upper-case hex). */
    fun encodeLabel(label: String): String = buildString {
        for (b in label.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and BYTE).toChar()
            if (unreserved(c)) {
                append(c)
            } else {
                append('%').append(HEX[b.toInt() and BYTE shr NIBBLE]).append(HEX[b.toInt() and LOW_NIBBLE])
            }
        }
    }

    private fun unreserved(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~"

    /**
     * `address`: `street`, `street2`, `city`, `region` and `postal_code` trimmed with inner white space collapsed to
     * single spaces, `postal_code` also upper-cased; `country` trimmed and upper-cased. An empty member is left out
     * of what is sent ([ItemChecks.valueJson]).
     */
    fun address(a: AddressValue): AddressValue = AddressValue(
        street = collapse(a.street),
        street2 = collapse(a.street2),
        city = collapse(a.city),
        region = collapse(a.region),
        postalCode = upper(collapse(a.postalCode)),
        country = upper(trim(a.country)),
    )
}
