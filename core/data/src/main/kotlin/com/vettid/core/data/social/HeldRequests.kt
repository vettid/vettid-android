package com.vettid.core.data.social

import com.vettid.core.vault.VaultJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * What a request held for approval asks (`approval.pending{type, body}`, VAULT-MESSAGING §6.8, §10.11), as far as this app
 * can show it (ANDROID-PLAN 0.1.116 §4 "Approvals for devices and agents (AA5)"). Anything the app cannot show in full is
 * [Unshown] or [Later]: those can only be denied, never approved unseen (decision 10).
 *
 * None of these prints what it holds (`toString`): an agent chooses `data`, `fields` and the rest.
 */
sealed interface HeldRequest {
    /** `agent.request{op: "item.use"}`: HMAC-SHA-256 keyed by the field's value over [data] (1-16,384 bytes). */
    class ItemUse(val itemId: String, val fieldId: String, val data: ByteArray) : HeldRequest {
        override fun toString(): String = "ItemUse"
    }

    /** `agent.request{op: "item.get"}`: a reveal. [fieldIds] null: all fields and the notes. */
    class ItemGet(val itemId: String, val fieldIds: List<String>?) : HeldRequest {
        override fun toString(): String = "ItemGet"
    }

    /** `agent.request{op: "catalog"}`: the names and field labels of the items the agent may use. */
    data object Catalog : HeldRequest

    /** `item.run` and `item.proxy` (AA8): shown by a later version of the app, so deny-only until then (decision 15). */
    class Later(val itemId: String?) : HeldRequest {
        override fun toString(): String = "Later"
    }

    /** A desktop's step-up request (decision 9): deny-only. */
    data object DesktopStepUp : HeldRequest

    /** Any other type or op, or a body that does not read: deny-only. */
    data object Unshown : HeldRequest

    /** Whether the request can be approved here: only what the app shows in full. */
    val approvable: Boolean get() = this is ItemUse || this is ItemGet || this is Catalog
}

/** Reads a held request from the event's `role`, `type` and `body` (§6.8: `body` is the held request's body). */
/** Default_Ignorable_Code_Point: soft hyphen, CGJ, the Hangul fillers, variation selectors, zero width and bidi controls, tags. */
@Suppress("MagicNumber")
private val IGNORABLE: List<IntRange> = listOf(
    0x00AD..0x00AD, 0x034F..0x034F, 0x061C..0x061C, 0x115F..0x1160, 0x17B4..0x17B5, 0x180B..0x180F, 0x200B..0x200F,
    0x202A..0x202E, 0x2060..0x206F, 0x3164..0x3164, 0xFE00..0xFE0F, 0xFEFF..0xFEFF, 0xFFA0..0xFFA0, 0xFFF0..0xFFF8,
    0x1BCA0..0x1BCA3, 0x1D173..0x1D17A, 0xE0000..0xE0FFF,
)

object HeldRequests {
    /** `item.use`'s data bound (§10.11). */
    const val MAX_DATA = 16_384

    /** The most field ids an `item.get` is shown with. */
    const val MAX_FIELDS = 64

    @Suppress("ReturnCount")
    fun of(role: String?, type: String?, body: JsonObject?): HeldRequest {
        if (role == "desktop") return HeldRequest.DesktopStepUp
        if (role != "agent" || type != "agent.request" || body == null) return HeldRequest.Unshown
        return when (VaultJson.str(body, "op")) {
            "item.use" -> itemUse(body)
            "item.get" -> itemGet(body)
            "catalog" -> HeldRequest.Catalog
            "item.run", "item.proxy" -> HeldRequest.Later(VaultJson.str(body, "item_id")?.takeIf { it.isNotEmpty() })
            else -> HeldRequest.Unshown
        }
    }

    /**
     * Whether the item cache can name everything [h] is about: the item for an `item.use` or `item.get`, the field of an
     * `item.use`, every field of an `item.get`. Anything else is not approved (nothing is shown unseen).
     */
    fun nameable(h: HeldRequest?, items: List<com.vettid.core.data.items.ItemSummary>): Boolean {
        fun fields(id: String) = items.firstOrNull { it.itemId == id }?.fieldLabels?.map { it.fieldId }?.toSet()
        return when (h) {
            is HeldRequest.ItemUse -> fields(h.itemId)?.contains(h.fieldId) == true
            is HeldRequest.ItemGet -> fields(h.itemId)?.let { have -> h.fieldIds?.all { it in have } ?: true } == true
            HeldRequest.Catalog -> true
            else -> false
        }
    }

    private fun itemUse(b: JsonObject): HeldRequest {
        val item = VaultJson.str(b, "item_id")?.takeIf { it.isNotEmpty() }
        val field = VaultJson.str(b, "field_id")?.takeIf { it.isNotEmpty() }
        val action = VaultJson.str(b, "action")
        val data = VaultJson.str(b, "data")?.let(::decode)
        // A request that does not read in full is never offered for approval (it is not shown unseen).
        return when {
            item == null || field == null || data == null -> HeldRequest.Unshown
            action != "hmac-sha256" -> HeldRequest.Unshown
            else -> HeldRequest.ItemUse(item, field, data)
        }
    }

    private fun fieldsShown(f: List<String>): Boolean = f.isNotEmpty() && f.size <= MAX_FIELDS && f.toSet().size == f.size

    @Suppress("ReturnCount")
    private fun itemGet(b: JsonObject): HeldRequest {
        val item = VaultJson.str(b, "item_id")?.takeIf { it.isNotEmpty() } ?: return HeldRequest.Unshown
        val raw = b["fields"]
        val fields = when (raw) {
            null -> null
            is JsonArray -> raw.map { e -> (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return HeldRequest.Unshown }
            else -> return HeldRequest.Unshown
        }
        // An empty list (a vault may read it as "all fields"), a repeated id or more than 64 are not shown unseen.
        if (fields != null && !fieldsShown(fields)) return HeldRequest.Unshown
        return HeldRequest.ItemGet(item, fields)
    }

    /** Standard base64, 1-16,384 bytes; null when it is not. */
    @Suppress("SwallowedException")
    private fun decode(s: String): ByteArray? = try {
        java.util.Base64.getDecoder().decode(s).takeIf { it.size in 1..MAX_DATA }
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * `item.use`'s data as the member sees it (decision 18): the text when the bytes are printable UTF-8, else hex.
 * The data is the agent's own choice, not a secret of the member's.
 */
sealed interface DataView {
    data class Text(val text: String) : DataView

    data class Hex(val hex: String) : DataView

    companion object {
        /**
         * Printable: strictly valid UTF-8, and nothing that could hide, reorder or fake what the member reads. Hex when it has:
         * a control character other than a line feed or tab; a format character (the bidirectional overrides and isolates,
         * zero width characters); a Default_Ignorable code point (soft hyphen, the Hangul fillers U+115F, U+1160, U+3164,
         * U+FFA0, variation selectors, the combining grapheme joiner U+034F, tag characters); an unassigned, private-use or
         * surrogate code point; a line or paragraph separator; a run of more than [MAX_MARKS] combining marks on one base;
         * more than [MAX_LINES] lines (a text with many line feeds could fill the screen with the agent's own lines).
         * Up to [MAX_LINES] lines of text are kept.
         */
        fun of(data: ByteArray): DataView {
            val text = strict(data)
            return if (text != null && printable(text)) Text(text) else Hex(hex(data))
        }

        /** The most combining marks in a row. */
        const val MAX_MARKS = 2

        /** The most lines shown as text. */
        const val MAX_LINES = 8

        private fun strict(data: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

        @Suppress("ReturnCount")
        private fun printable(s: String): Boolean {
            var i = 0
            var lines = 1
            var marks = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                i += Character.charCount(cp)
                if (cp == '\n'.code && ++lines > MAX_LINES) return false
                marks = if (isMark(cp)) marks + 1 else 0
                if (marks > MAX_MARKS || !shown(cp)) return false
            }
            return true
        }

        private fun isMark(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
            else -> false
        }

        @Suppress("ReturnCount")
        private fun shown(cp: Int): Boolean {
            if (cp == '\n'.code || cp == '\t'.code) return true
            if (ignorable(cp)) return false
            return when (Character.getType(cp).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.UNASSIGNED, Character.PRIVATE_USE, Character.SURROGATE,
                Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                -> false
                else -> true
            }
        }

        /** The Unicode Default_Ignorable_Code_Point property (and nothing less): invisible in most renderings. */
        private fun ignorable(cp: Int): Boolean = IGNORABLE.any { cp in it }

        /** Lower-case hex, two digits a byte, a space between bytes. */
        fun hex(data: ByteArray): String = data.joinToString(" ") { "%02x".format(it) }
    }
}
