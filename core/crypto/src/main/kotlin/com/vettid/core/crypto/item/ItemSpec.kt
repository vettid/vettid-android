package com.vettid.core.crypto.item

import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.JsonObject
import com.vettid.core.crypto.json.JsonValue
import com.vettid.core.crypto.json.StrictJson
import com.vettid.core.crypto.json.asObject
import com.vettid.core.crypto.json.asString
import java.net.URI
import java.net.URISyntaxException
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException

/**
 * The shape of items (VAULT-MESSAGING §10.7, §10.8; VAULT-ITEMS): field kinds
 * and value checks, tag normalisation and the canonical encodings, ported
 * from the Go reference's `features/itemspec` so that the app validates
 * before sending what the vault would refuse, and encodes byte for byte what
 * it would. The vault remains the authority.
 */
@Suppress("TooManyFunctions")
object ItemSpec {
    const val DATA = "data"
    const val SECRET = "secret"
    const val CRITICAL = "critical"

    const val KIND_TEXT = "text"
    const val KIND_MULTILINE = "multiline"
    const val KIND_NUMBER = "number"
    const val KIND_DATE = "date"
    const val KIND_EMAIL = "email"
    const val KIND_PHONE = "phone"
    const val KIND_URL = "url"
    const val KIND_PASSWORD = "password"
    const val KIND_OTP = "otp"
    const val KIND_ADDRESS = "address"

    /** Reserved (files later) and refused. */
    const val KIND_FILE = "file"

    const val MAX_NAME = 128
    const val MAX_NOTES = 16_384
    const val MAX_FIELDS = 64
    const val MAX_LABEL = 64
    const val MAX_VALUE = 16_384
    const val MAX_ITEM_BYTES = 65_536
    const val MAX_CRIT_BYTES = 12_288
    const val MAX_TAGS = 16
    const val MAX_TAG_LEN = 32
    const val MAX_ADDRESS_PART = 256
    const val MAX_URL = 2048
    const val MAX_EMAIL = 254
    const val MAX_PHONE = 32
    const val PROFILE_TAG = "@profile"

    val RECOMMENDED_CATEGORIES = listOf(
        "identity_document", "login", "payment_card", "bank_account", "medical",
        "insurance", "vehicle", "contact", "note", "crypto_wallet", "other",
    )

    val KINDS = setOf(
        KIND_TEXT, KIND_MULTILINE, KIND_NUMBER, KIND_DATE, KIND_EMAIL, KIND_PHONE,
        KIND_URL, KIND_PASSWORD, KIND_OTP, KIND_ADDRESS,
    )

    private val categoryRe = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val templateRe = Regex("^[a-z0-9_.-]{1,64}$")
    private val fieldIdRe = Regex("^[A-Za-z0-9_-]{1,32}$")
    private val tagRe = Regex("^[a-z0-9][a-z0-9 _-]{0,31}$")
    private val numberRe = Regex("^-?[0-9]{1,32}(\\.[0-9]{1,32})?$")
    private val countryRe = Regex("^[A-Z]{2}$")
    private val addressParts = listOf("street", "street2", "city", "region", "postal_code", "country")

    private fun len(s: String) = s.toByteArray(Charsets.UTF_8).size

    fun isValidCategory(s: String) = categoryRe.matches(s)

    fun isValidTemplate(s: String) = templateRe.matches(s)

    fun isValidSensitivity(s: String) = s == DATA || s == SECRET || s == CRITICAL

    fun isValidFieldId(s: String) = fieldIdRe.matches(s)

    fun isValidKind(s: String) = s in KINDS

    /** No control characters (U+0000–U+001F, U+007F), except LF and tab when [multi]. */
    fun isCleanText(s: String, multi: Boolean): Boolean =
        s.all { c -> !(c < ' ' || c == '\u007f') || (multi && (c == '\n' || c == '\t')) } &&
            s.indices.none { Character.isSurrogate(s[it]) && !validSurrogateAt(s, it) }

    private fun validSurrogateAt(s: String, i: Int): Boolean =
        if (Character.isHighSurrogate(s[i])) {
            i + 1 < s.length && Character.isLowSurrogate(s[i + 1])
        } else {
            i > 0 && Character.isHighSurrogate(s[i - 1])
        }

    fun isValidName(s: String) = s.isNotEmpty() && len(s) <= MAX_NAME && isCleanText(s, false)

    fun isValidNotes(s: String) = len(s) <= MAX_NOTES && isCleanText(s, true)

    fun isValidLabel(s: String) = s.isNotEmpty() && len(s) <= MAX_LABEL && isCleanText(s, false)

    /** Checks a string value of [kind] (§10.7); every kind accepts "". */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun isValidStringValue(kind: String, s: String): Boolean {
        if (len(s) > MAX_VALUE) return false
        if (kind == KIND_MULTILINE || kind == KIND_PASSWORD) return isCleanText(s, true)
        if (!isCleanText(s, false)) return false
        if (s.isEmpty()) return kind in KINDS
        return when (kind) {
            KIND_TEXT -> true
            KIND_NUMBER -> numberRe.matches(s)
            KIND_DATE -> isValidDate(s)
            KIND_EMAIL -> isValidEmail(s)
            KIND_PHONE -> isValidPhone(s)
            KIND_URL -> isValidUrl(s)
            KIND_OTP -> isValidOtp(s)
            else -> false
        }
    }

    private fun isValidDate(s: String): Boolean = try {
        when (s.length) {
            10 -> LocalDate.parse(s).toString() == s
            7 -> YearMonth.parse(s).toString() == s
            else -> false
        }
    } catch (_: DateTimeParseException) {
        false
    }

    private fun noSpace(s: String) = s.none { it == ' ' || it == '\t' || it == '\n' || it == '\r' }

    private fun isValidEmail(s: String): Boolean {
        if (len(s) > MAX_EMAIL || !noSpace(s) || s.count { it == '@' } != 1) return false
        val at = s.indexOf('@')
        return at > 0 && at < s.length - 1
    }

    private fun isValidPhone(s: String): Boolean =
        len(s) <= MAX_PHONE && s.all { it in '0'..'9' || it in " +-()." } && s.any { it in '0'..'9' }

    private fun isValidUrl(s: String): Boolean {
        if (len(s) > MAX_URL || !noSpace(s)) return false
        return try {
            val u = URI(s)
            !u.scheme.isNullOrEmpty() && u.isAbsolute
        } catch (_: URISyntaxException) {
            false
        }
    }

    private fun isValidOtp(s: String): Boolean {
        if (s.startsWith("otpauth://")) return isValidUrl(s)
        val t = s.trimEnd('=')
        if (s.length - t.length > 6 || t.length < 16 || t.length > 256) return false
        return t.all { it in 'A'..'Z' || it in 'a'..'z' || it in '2'..'7' }
    }

    /**
     * Checks a field value of [kind] and returns its canonical JSON: a string,
     * or an address object with its non-empty members in fixed order.
     */
    fun canonicalValue(kind: String, v: JsonValue): String {
        if (kind == KIND_ADDRESS) {
            val o = v.asObject()
            if (o.members.keys.any { it !in addressParts }) throw CryptoException.Format("address")
            val b = JsonBuilder()
            for (k in addressParts) {
                val s = o.optString(k) ?: continue
                if (len(s) > MAX_ADDRESS_PART || !isCleanText(s, false)) throw CryptoException.Format("address")
                if (k == "country" && s.isNotEmpty() && !countryRe.matches(s)) throw CryptoException.Format("address country")
                if (s.isNotEmpty()) b.string(k, s)
            }
            return b.build()
        }
        val s = v.asString()
        if (!isValidStringValue(kind, s)) throw CryptoException.Format("value")
        return JsonBuilder.quote(s)
    }

    /**
     * Normalises a tag (§10.8): spaces trimmed, A–Z lowered, runs of spaces
     * collapsed; the result matches `[a-z0-9][a-z0-9 _-]{0,31}`, or is the
     * reserved `@profile` when [reserved] allows it.
     */
    fun normalizeTag(s: String, reserved: Boolean = false): String {
        if (len(s) > 4 * MAX_TAG_LEN) throw CryptoException.Format("tag")
        val sb = StringBuilder()
        var space = false
        for (c in s.trim(' ')) {
            if (c == ' ') {
                if (!space) sb.append(c)
                space = true
                continue
            }
            space = false
            sb.append(if (c in 'A'..'Z') c + ('a' - 'A') else c)
        }
        val n = sb.toString()
        if (n == PROFILE_TAG) {
            if (!reserved) throw CryptoException.Format("tag")
            return n
        }
        if (!tagRe.matches(n)) throw CryptoException.Format("tag")
        return n
    }

    /** Normalises a tag list: at most 16, sorted, without duplicates. */
    fun normalizeTags(tags: List<String>, reserved: Boolean = false): List<String> {
        if (tags.size > MAX_TAGS) throw CryptoException.Format("tags")
        return tags.map { normalizeTag(it, reserved) }.distinct().sorted()
    }
}

/** One field of an item's content: an existing field ([id] set) or a new one. */
class ItemField(val id: String?, val label: String, val kind: String, val valueJson: String) {
    companion object {
        /** A string-valued field (every kind but address). */
        fun text(id: String?, label: String, kind: String, value: String): ItemField {
            if (!ItemSpec.isValidLabel(label) || !ItemSpec.isValidKind(kind) || kind == ItemSpec.KIND_ADDRESS ||
                !ItemSpec.isValidStringValue(kind, value)
            ) {
                throw CryptoException.Format("field")
            }
            return ItemField(id, label, kind, JsonBuilder.quote(value))
        }
    }
}

/**
 * An item's content as the app sends it in `item.put` or inside a critical
 * item's UTK payload: `{name, category?, template?, fields?, notes?}` (§10.7).
 */
class ItemContent(
    val name: String,
    val category: String = "other",
    val template: String? = null,
    val fields: List<ItemField>? = null,
    val notes: String? = null,
) {
    @Suppress("CyclomaticComplexMethod")
    fun validate() {
        if (!ItemSpec.isValidName(name) || !ItemSpec.isValidCategory(category)) throw CryptoException.Format("item")
        if (template != null && !ItemSpec.isValidTemplate(template)) throw CryptoException.Format("item template")
        if (notes != null && !ItemSpec.isValidNotes(notes)) throw CryptoException.Format("item notes")
        val fs = fields.orEmpty()
        if (fs.size > ItemSpec.MAX_FIELDS) throw CryptoException.Format("item fields")
        val ids = fs.mapNotNull { it.id }
        if (ids.size != ids.toSet().size || ids.any { !ItemSpec.isValidFieldId(it) }) throw CryptoException.Format("item field ids")
    }

    /** Members in the order name, category, template, fields, notes. */
    fun marshal(): String {
        validate()
        val b = JsonBuilder().string("name", name).string("category", category)
        template?.let { b.string("template", it) }
        fields?.let { fs ->
            b.raw(
                "fields",
                JsonBuilder.array(
                    fs.map { f ->
                        val fb = JsonBuilder()
                        f.id?.let { fb.string("field_id", it) }
                        fb.string("label", f.label).string("kind", f.kind).raw("value", f.valueJson).build()
                    },
                ),
            )
        }
        notes?.let { b.string("notes", it) }
        return b.build()
    }
}

/**
 * The plaintext of a critical item's `values_sealed` (§10.7, opened with the
 * request's reply key): `{"fields": [{"field_id", "value"}], "notes"?}`.
 */
class CriticalValues(val fields: List<Pair<String, String>>, val notes: String?) {
    companion object {
        fun parse(b: ByteArray): CriticalValues {
            val o: JsonObject = StrictJson.parseObject(b)
            val arr = o.array("fields")
            if (arr.size > ItemSpec.MAX_FIELDS) throw CryptoException.Format("values")
            val fields = arr.map {
                val f = it.asObject()
                val id = f.string("field_id")
                if (!ItemSpec.isValidFieldId(id)) throw CryptoException.Format("values")
                val v = f["value"] ?: throw CryptoException.Format("values")
                id to v.raw
            }
            val notes = o.optString("notes")
            if (notes != null && !ItemSpec.isValidNotes(notes)) throw CryptoException.Format("values")
            return CriticalValues(fields, notes)
        }
    }
}
