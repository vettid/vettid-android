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
object HeldRequests {
    /** `item.use`'s data bound (§10.11). */
    const val MAX_DATA = 16_384

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

    @Suppress("ReturnCount")
    private fun itemGet(b: JsonObject): HeldRequest {
        val item = VaultJson.str(b, "item_id")?.takeIf { it.isNotEmpty() } ?: return HeldRequest.Unshown
        val raw = b["fields"]
        val fields = when (raw) {
            null -> null
            is JsonArray -> raw.map { e -> (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return HeldRequest.Unshown }
            else -> return HeldRequest.Unshown
        }
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
         * Printable: strictly valid UTF-8, and no code point that could hide or reorder what the member reads: no control
         * character other than a line feed or tab, no format character (the bidirectional overrides and isolates, zero
         * width characters), no unassigned, private-use or surrogate code point, no line or paragraph separator.
         */
        fun of(data: ByteArray): DataView {
            val text = strict(data)
            return if (text != null && printable(text)) Text(text) else Hex(hex(data))
        }

        private fun strict(data: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

        private fun printable(s: String): Boolean {
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                i += Character.charCount(cp)
                if (cp == '\n'.code || cp == '\t'.code) continue
                val ok = when (Character.getType(cp).toByte()) {
                    Character.CONTROL, Character.FORMAT, Character.UNASSIGNED, Character.PRIVATE_USE, Character.SURROGATE,
                    Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                    -> false
                    else -> true
                }
                if (!ok) return false
            }
            return true
        }

        /** Lower-case hex, two digits a byte, a space between bytes. */
        fun hex(data: ByteArray): String = data.joinToString(" ") { "%02x".format(it) }
    }
}
