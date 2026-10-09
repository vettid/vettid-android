package com.vettid.feature.items

import android.content.Context
import android.telephony.TelephonyManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.vettid.core.data.items.FieldKinds
import com.vettid.core.data.items.ItemChecks
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale

/**
 * What a field's input lets through while the member types or pastes (VAULT-MESSAGING §10.7 field kinds; owner
 * request 2026-10-08: formats are enforced during entry). Characters a kind can never hold are dropped, a paste is
 * normalised (spaces out of a setup key, a decimal comma to a point), and what is still incomplete is left to the
 * checks of [ItemChecks], whose message shows under the field. Dates are typed as digits with the dashes drawn in
 * ([DateInput]).
 */
internal object FieldInput {
    /** §10.7: `-?[0-9]{1,32}(\.[0-9]{1,32})?`. */
    const val MAX_NUMBER_DIGITS = 32
    const val MAX_PHONE = 32
    const val MAX_EMAIL = 254
    const val MAX_URL = 2048

    /** A base32 secret: 16–256 characters and up to 6 `=` of padding (§10.7). */
    const val MAX_OTP_SECRET = 256 + 6
    private const val OTPAUTH = "otpauth:"
    private const val PHONE_SIGNS = " +-()."

    /** Kinds whose value has a format the input enforces (and whose rule shows while it is incomplete). */
    val FORMATTED = setOf(FieldKinds.NUMBER, FieldKinds.DATE, FieldKinds.EMAIL, FieldKinds.PHONE, FieldKinds.URL, FieldKinds.OTP)

    /** What an input of [kind] keeps of [typed] (the whole new text, typed or pasted). Dates go through [DateInput]. */
    fun accept(kind: String, typed: String): String = when (kind) {
        FieldKinds.NUMBER -> number(typed)
        FieldKinds.PHONE -> phone(typed)
        FieldKinds.EMAIL -> utf8Take(typed.filterNot { it.isWhitespace() }, MAX_EMAIL)
        FieldKinds.URL -> utf8Take(typed.filterNot { it.isWhitespace() }, MAX_URL)
        FieldKinds.OTP -> otp(typed)
        FieldKinds.MULTILINE, FieldKinds.PASSWORD -> typed
        else -> typed.replace("\r", "").replace("\n", "")
    }

    /**
     * A decimal (§10.7): digits, one leading minus and one point. A comma counts as the decimal point unless the
     * text also has a point (then commas group thousands and go); spaces, underscores and apostrophes go; a point
     * before any digit gets a 0 (".5" → "0.5").
     */
    @Suppress("CyclomaticComplexMethod")
    fun number(typed: String): String {
        val s = typed.replace('−', '-').filterNot { it.isWhitespace() || it == '_' || it == '\'' }
        val unified = if ('.' in s && ',' in s) s.replace(",", "") else s.replace(',', '.')
        val out = StringBuilder()
        var point = false
        var whole = 0
        var fraction = 0
        for (c in unified) {
            when {
                c == '-' && out.isEmpty() -> out.append(c)
                c in '0'..'9' && !point && whole < MAX_NUMBER_DIGITS -> {
                    out.append(c)
                    whole++
                }
                c in '0'..'9' && point && fraction < MAX_NUMBER_DIGITS -> {
                    out.append(c)
                    fraction++
                }
                c == '.' && !point -> {
                    if (whole == 0) {
                        out.append('0')
                        whole = 1
                    }
                    out.append('.')
                    point = true
                }
            }
        }
        return out.toString()
    }

    /** At most 32 digits, spaces and `+-().` (§10.7). */
    fun phone(typed: String): String = typed.filter { it in '0'..'9' || it in PHONE_SIGNS }.take(MAX_PHONE)

    /**
     * A TOTP seed (§10.7): an `otpauth://` URI (the scheme in lower case, no white space), or a base32 secret in
     * upper case with only its alphabet (A–Z, 2–7) and trailing `=`: the spaces and dashes a site shows a key with
     * go. While the text could still become `otpauth:` it is kept as typed, in lower case.
     */
    @Suppress("ReturnCount")
    fun otp(typed: String): String {
        val t = typed.filterNot { it.isWhitespace() }
        val lower = t.lowercase()
        if (lower.startsWith(OTPAUTH)) return utf8Take(OTPAUTH + t.substring(OTPAUTH.length), MAX_URL)
        if (t.isNotEmpty() && OTPAUTH.startsWith(lower)) return lower
        val secret = t.uppercase().filter { it in 'A'..'Z' || it in '2'..'7' || it == '=' }
        // Padding only at the end: an `=` followed by more of the secret goes.
        val body = secret.trimEnd('=').replace("=", "")
        return (body + "=".repeat(secret.length - secret.trimEnd('=').length)).take(MAX_OTP_SECRET)
    }

    /** An address's country (§10.7): two letters, upper case. */
    fun country(typed: String): String = typed.filter { it in 'a'..'z' || it in 'A'..'Z' }.uppercase().take(2)

    /** The longest prefix of [s] of at most [max] UTF-8 bytes. */
    fun utf8Take(s: String, max: Int): String {
        var bytes = 0
        var end = 0
        while (end < s.length) {
            val cp = s.codePointAt(end)
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + n > max) break
            bytes += n
            end += Character.charCount(cp)
        }
        return s.substring(0, end)
    }

    /** Whether [text] is not (yet) a value of [kind] (§10.7): its rule then shows under the field while typing. */
    fun incomplete(kind: String, text: String): Boolean = kind in FORMATTED && text.isNotEmpty() &&
        com.vettid.core.data.items.DraftProblem.VALUE_INVALID in
        ItemChecks.valueProblems(kind, com.vettid.core.data.items.FieldValue.Text(text))
}

/**
 * A `date` value (§10.7: `YYYY-MM-DD` or `YYYY-MM`, a valid calendar date), typed as digits: the input holds the
 * digits and draws the dashes ([Mask]), and the stored value is always the spec's format. A digit no date can
 * continue with (month 13, day 32, 30 February) is refused; a month-and-year field (a card's expiry) stops at the
 * month.
 */
internal object DateInput {
    private const val YEAR = 4
    private const val MONTH = 6
    private const val DAY = 8
    private const val MAX_MONTH = 12
    private const val MAX_DAY_TENS = 3

    /** The digits of a stored value, as the input holds them. */
    fun digits(stored: String): String = stored.filter { it in '0'..'9' }

    /** Digits in the spec's format: `YYYY`, `YYYY-M`, `YYYY-MM`, `YYYY-MM-D`, `YYYY-MM-DD`. */
    fun format(d: String): String = buildString {
        d.forEachIndexed { i, c ->
            if (i == YEAR || i == MONTH) append('-')
            append(c)
        }
    }

    /**
     * The stored value for what the input now holds ([typed]: its digits count, so a pasted `2031-04-30` or
     * `2031/04/30` works), or null when no date can begin so and the change is refused. A deletion (fewer digits
     * than [old] held) always goes through, so a digit in the middle can be taken out and typed again.
     */
    fun accept(old: String, typed: String, monthOnly: Boolean): String? {
        val d = digits(typed).take(if (monthOnly) MONTH else DAY)
        return if (possible(d) || d.length < digits(old).length) format(d) else null
    }

    /** Whether some valid date begins with the digits [d]. */
    @Suppress("ReturnCount", "MagicNumber")
    fun possible(d: String): Boolean {
        if (d.length > YEAR && d[YEAR] > '1') return false
        if (d.length >= MONTH && d.substring(YEAR, MONTH).toInt() !in 1..MAX_MONTH) return false
        if (d.length > MONTH && d[MONTH] - '0' > MAX_DAY_TENS) return false
        if (d.length >= DAY) {
            return try {
                LocalDate.of(d.substring(0, YEAR).toInt(), d.substring(YEAR, MONTH).toInt(), d.substring(MONTH, DAY).toInt())
                true
            } catch (_: DateTimeException) {
                false
            }
        }
        return true
    }

    /** A day the date picker returned (UTC midnight, Material3) as the spec's `YYYY-MM-DD`. */
    fun fromPicker(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

    /** The day to open the picker on: the stored date (a month opens on its first day), or null. */
    fun toPicker(stored: String): Long? = (day(stored) ?: yearMonth(stored)?.atDay(1))
        ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()

    fun day(stored: String): LocalDate? = runCatching { LocalDate.parse(stored) }.getOrNull()?.takeIf { it.toString() == stored }

    fun yearMonth(stored: String): YearMonth? = runCatching { YearMonth.parse(stored) }.getOrNull()?.takeIf { it.toString() == stored }

    /** A month and year as the spec's `YYYY-MM`. */
    fun month(ym: YearMonth): String = ym.toString()

    /** Whether a stored value is a month and year (`YYYY-MM`). */
    fun isMonth(stored: String): Boolean = yearMonth(stored) != null

    /** Draws the dashes of [format] over the digits the input holds, the cursor moving over them. */
    object Mask : VisualTransformation {
        override fun filter(text: AnnotatedString): TransformedText {
            val out = format(text.text)
            return TransformedText(
                AnnotatedString(out),
                object : OffsetMapping {
                    override fun originalToTransformed(offset: Int): Int =
                        (offset + (if (offset > YEAR) 1 else 0) + (if (offset > MONTH) 1 else 0)).coerceAtMost(out.length)

                    override fun transformedToOriginal(offset: Int): Int =
                        (offset - (if (offset > YEAR) 1 else 0) - (if (offset > MONTH + 1) 1 else 0)).coerceIn(0, text.length)
                },
            )
        }
    }
}

/** The region phone numbers are typed and shown in ([PhoneInput.region]); tests and the screen catalog set it. */
val LocalPhoneRegion = staticCompositionLocalOf<String?> { null }

@Composable
internal fun phoneRegion(): String {
    val context = LocalContext.current
    return LocalPhoneRegion.current ?: remember(context) { PhoneInput.region(context) }
}

/**
 * A `phone` value (owner request 2026-10-09; §10.7 allows at most 32 digits, spaces and `+-().`): the input holds the
 * dialable characters (digits, a leading `+`) and draws libphonenumber's as-you-type format over them ([mask]), in
 * the phone's region until a `+` and country code switch it. Saved, a number that parses is stored in international
 * format ([stored], e.g. `+44 20 7946 0958`); one that does not keeps what was typed and only gets a "Check this
 * number" hint ([doubtful]), never a block. Shown, a stored number is formatted for the viewer's region ([display]).
 */
internal object PhoneInput {
    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    /** The region numbers without `+` are read in: the network's, then the SIM's country, then the locale's. */
    fun region(context: Context): String {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val iso = listOfNotNull(
            runCatching { tm?.networkCountryIso }.getOrNull(),
            runCatching { tm?.simCountryIso }.getOrNull(),
            Locale.getDefault().country,
        ).firstOrNull { it.length == 2 }
        return iso?.uppercase(Locale.ROOT) ?: UNKNOWN
    }

    /** The dialable characters of [typed] (typed, pasted or stored): its digits, after a `+` if it starts with one. */
    fun raw(typed: String): String {
        val digits = typed.filter { it in '0'..'9' }
        val plus = typed.trimStart().startsWith("+")
        return (if (plus) "+" else "") + digits.take(MAX_DIGITS)
    }

    /** [raw] formatted as typed in [region] (a `+` and country code switch the region); [raw] when no format fits. */
    fun asYouType(raw: String, region: String): String {
        if (raw.isEmpty()) return raw
        val f = util.getAsYouTypeFormatter(region)
        var out = ""
        for (c in raw) out = f.inputDigit(c)
        return out.takeIf { significant(it) == raw && FieldInput.phone(it) == it } ?: raw
    }

    /**
     * What is saved for [text] in [region]: the international format when it parses as a possible number without an
     * extension (it always fits §10.7), else the as-you-type text (what the member saw), within §10.7's characters.
     */
    fun stored(text: String, region: String): String {
        val raw = raw(text)
        if (raw.isEmpty()) return text
        val n = parse(raw, region)
        val intl = n?.takeIf { !it.hasExtension() && util.isPossibleNumber(it) }
            ?.let { util.format(it, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL) }
        return intl?.takeIf { FieldInput.phone(it) == it } ?: FieldInput.phone(asYouType(raw, region))
    }

    /** Whether to show "Check this number" under [text]: there are digits, and it is not a valid number. */
    fun doubtful(text: String, region: String): Boolean {
        val raw = raw(text)
        return raw.any { it in '0'..'9' } && parse(raw, region)?.let { util.isValidNumber(it) } != true
    }

    /** A stored number as shown in [region]: national format there, international elsewhere; as stored if unparsed. */
    fun display(stored: String, region: String): String {
        val n = parse(raw(stored), region)?.takeIf { util.isValidNumber(it) } ?: return stored
        val same = util.getRegionCodeForNumber(n) == region
        return util.format(n, if (same) PhoneNumberUtil.PhoneNumberFormat.NATIONAL else PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
    }

    private fun parse(raw: String, region: String): Phonenumber.PhoneNumber? = try {
        util.parse(raw, region)
    } catch (_: NumberParseException) {
        null
    }

    private fun significant(s: String): String = (if (s.trimStart().startsWith("+")) "+" else "") + s.filter { it in '0'..'9' }

    /** Draws [asYouType] over the dialable characters the input holds, the cursor moving over them. */
    class Mask(private val region: String) : VisualTransformation {
        override fun filter(text: AnnotatedString): TransformedText {
            val raw = text.text
            val out = asYouType(raw, region)
            // Where each held character is in the drawn text (all of them are in it, in order).
            val at = IntArray(raw.length)
            var j = 0
            for (i in raw.indices) {
                while (j < out.length && out[j] != raw[i]) j++
                at[i] = j
                j++
            }
            return TransformedText(
                AnnotatedString(out),
                object : OffsetMapping {
                    override fun originalToTransformed(offset: Int): Int = when {
                        offset <= 0 -> 0
                        offset >= raw.length -> out.length
                        else -> (at[offset - 1] + 1).coerceAtMost(out.length)
                    }

                    override fun transformedToOriginal(offset: Int): Int =
                        at.count { it < offset }.coerceIn(0, raw.length)
                },
            )
        }

        override fun equals(other: Any?): Boolean = other is Mask && other.region == region

        override fun hashCode(): Int = region.hashCode()
    }

    /** E.164 holds at most 15 digits; §10.7 allows 32 characters. */
    private const val MAX_DIGITS = 15
    private const val UNKNOWN = "ZZ"
}
