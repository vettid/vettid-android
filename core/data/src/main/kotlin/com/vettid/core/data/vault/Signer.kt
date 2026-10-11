package com.vettid.core.data.vault

import com.vettid.core.vault.VaultJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The class of an Android signer (VAULT-MESSAGING 0.27.0 §11.7.1): who holds the key that signed an installation.
 * An identifier from the spec's table; the vault never supplies member-facing wording, the app does
 * (ANDROID-PLAN §7).
 */
enum class SignerClass(val wire: String) {
    /** Signed with a key only VettID holds: the F-Droid and direct builds. */
    VETTID("vettid"),

    /** Signed with the Play app-signing key, which Google holds. */
    GOOGLE_PLAY("google_play"),

    /** A development or automation key (never pinned by a production release). */
    DEVELOPMENT("development"),
    ;

    companion object {
        /** The class the vault named, or null for a name this app does not know (or none). */
        fun of(wire: String?): SignerClass? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The vault's signer binding as the app's own `device.list` entry shows it (§10.3): [signerClass] for the
 * "Installed from" line, [digest] (64 lowercase hex digits of the signing certificate's SHA-256, public) for a
 * details view and for support.
 */
data class SignerView(val signerClass: SignerClass, val digest: String)

/** The wire forms of the signer binding (pure, for tests). */
object SignerWire {
    const val CODE_SIGNER_REFUSED = "signer_refused"
    const val CODE_SIGNER_UNCONFIRMED = "signer_unconfirmed"

    /** What this app's own `device.list` entry says about its signer. */
    sealed interface Own {
        /** The entry carries `signer` with a class this app knows. */
        data class Bound(val signer: SignerView) : Own

        /**
         * The entry carries a `signer` this app cannot name (a class added after this version, or a malformed
         * member): the vault has recorded a signer, so the chain is no longer sent, and no line is shown.
         */
        data object BoundUnknown : Own

        /** The entry has no `signer`: the vault has recorded none (it was enrolled before 0.27.0, or runs one). */
        data object Unbound : Own

        /** The answer has no entry for this app: nothing is learnt from it. */
        data object NoEntry : Own
    }

    /**
     * This app's signer in a `device.list` answer: `signer` on the entry of [deviceId], or, when the id is not
     * known, on the one `app` entry (the vault has one app, §6.7; desktops and agents never carry a signer).
     */
    @Suppress("ReturnCount") // one per form the entry can have
    fun own(list: JsonObject, deviceId: String?): Own {
        val devices = (list["devices"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return Own.NoEntry
        val entry = if (deviceId != null) {
            devices.firstOrNull { VaultJson.str(it, "id") == deviceId }
        } else {
            devices.singleOrNull { VaultJson.str(it, "kind") == KIND_APP }
        } ?: return Own.NoEntry
        if (!entry.containsKey("signer")) return Own.Unbound
        val s = entry["signer"] as? JsonObject ?: return Own.BoundUnknown
        val cls = SignerClass.of(VaultJson.str(s, "class")) ?: return Own.BoundUnknown
        val digest = VaultJson.str(s, "digest")?.takeIf { d -> d.length == DIGEST_HEX && d.all { it in '0'..'9' || it in 'a'..'f' } }
            ?: return Own.BoundUnknown
        return Own.Bound(SignerView(cls, digest))
    }

    private const val KIND_APP = "app"
    private const val DIGEST_HEX = 64
}
