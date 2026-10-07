package com.vettid.core.data.social

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.IkFingerprint
import com.vettid.core.data.account.AccountNames
import com.vettid.core.vault.VaultJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A peer's shared profile as the vault keeps it (VAULT-MESSAGING 0.18.0 §10.4, §10.8): the kept `profile.update`
 * `{version, first_name, last_name, ik, name?, photo?, items}` or, on the inviter's side before the first update and
 * in a connection request, the `hs.init` profile `{first_name, last_name, name?}` (§6.2).
 *
 * Parsed strictly: the core names must be 1–160 UTF-8 bytes without control characters, and `ik`, when present,
 * the base64 of 32 bytes; a profile that breaks the core rules yields no names (the title falls back to
 * "Name not shared yet"), never a partial one. The display name, photo and items are self-asserted extras.
 */
data class PeerProfile(
    val firstName: String?,
    val lastName: String?,
    val displayName: String?,
    val hasPhoto: Boolean,
    val items: List<SharedProfileItem>,
) {
    val accountName: String? get() = AccountNames.full(firstName, lastName)

    companion object {
        /** The display name's limit (§10.8: at most 128 bytes). */
        private const val MAX_DISPLAY_NAME_BYTES = 128

        /** Null for an absent profile. */
        fun parse(p: JsonObject?): PeerProfile? {
            if (p == null) return null
            val coreOk = coreValid(p)
            return PeerProfile(
                firstName = if (coreOk) VaultJson.str(p, "first_name") else null,
                lastName = if (coreOk) VaultJson.str(p, "last_name") else null,
                displayName = displayNameOf(p),
                hasPhoto = VaultJson.str(p, "photo")?.isNotEmpty() == true,
                items = itemsOf(p),
            )
        }

        /** "First Last" of [p] when its core is complete and valid. */
        fun accountName(p: JsonObject?): String? = parse(p)?.accountName

        /** The non-empty display name of [p]. */
        fun displayName(p: JsonObject?): String? = p?.let { displayNameOf(it) }

        /** Both names present and valid; `ik`, if present, the base64 of 32 bytes. */
        @Suppress("ReturnCount")
        private fun coreValid(p: JsonObject): Boolean {
            if (!AccountNames.isValidCore(VaultJson.str(p, "first_name"))) return false
            if (!AccountNames.isValidCore(VaultJson.str(p, "last_name"))) return false
            val ik = p["ik"] ?: return true
            val s = (ik as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
            return try {
                Base64s.decodeStd(s, IkFingerprint.IK_SIZE)
                true
            } catch (_: CryptoException) {
                false
            }
        }

        private fun displayNameOf(p: JsonObject): String? = VaultJson.str(p, "name")
            ?.takeIf { it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_DISPLAY_NAME_BYTES }

        private fun itemsOf(p: JsonObject): List<SharedProfileItem> =
            (p["items"] as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = VaultJson.str(o, "item_id") ?: return@mapNotNull null
                val fields = (o["fields"] as? JsonArray)?.mapNotNull { f ->
                    val fo = f as? JsonObject ?: return@mapNotNull null
                    val label = VaultJson.str(fo, "label") ?: return@mapNotNull null
                    // A string value; an address object is shown by its text members, joined.
                    val value = VaultJson.str(fo, "value") ?: (fo["value"] as? JsonObject)?.values
                        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { v -> v.isString }?.content?.takeIf { v -> v.isNotBlank() } }
                        ?.joinToString(", ")
                        ?.takeIf { it.isNotEmpty() }
                    value?.let { label to it }
                } ?: emptyList()
                SharedProfileItem(id, VaultJson.str(o, "name") ?: "", fields)
            } ?: emptyList()
    }
}
