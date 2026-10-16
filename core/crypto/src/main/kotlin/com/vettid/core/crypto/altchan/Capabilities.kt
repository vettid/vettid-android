package com.vettid.core.crypto.altchan

import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.JsonObject
import com.vettid.core.crypto.json.asString

/**
 * A release's capabilities (VAULT-MESSAGING 0.34.0 §11.10.9): a constant of the vault's image, carried in its attested
 * ETK descriptor (§11.2) and in a transfer's bundle (§6.4). Lowercase names of 1 to 32 characters `[a-z0-9_]`, unique,
 * sorted, at most 32. A missing member is an empty list (a release before R); a malformed one is refused like any
 * malformed descriptor or bundle. Names the app does not know are kept and ignored.
 */
object Capabilities {
    /** §11.14: the phone-held unlock secret, the recovery open, none of the recovery of §11.11.5. */
    const val RECOVERY_R = "recovery_r"

    /** The release offers recovery (listed only then). */
    const val RECOVERY_OFFERED = "recovery_offered"

    const val MEMBER = "capabilities"
    private const val MAX_NAMES = 32
    private const val MAX_NAME = 32

    fun isValidName(s: String): Boolean = s.length in 1..MAX_NAME && s.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }

    /** `capabilities` of [o]; empty when absent. */
    fun parse(o: JsonObject): List<String> {
        val items = o.optArray(MEMBER) ?: return emptyList()
        val names = items.map { it.asString() }
        if (names.size > MAX_NAMES || names.any { !isValidName(it) } || names != names.distinct().sorted()) {
            throw CryptoException.Format("capabilities")
        }
        return names
    }

    /** Writes `capabilities` (tests and tooling). */
    fun write(b: JsonBuilder, names: List<String>): JsonBuilder =
        b.raw(MEMBER, JsonBuilder.array(names.map { JsonBuilder.quote(it) }))
}
