package com.vettid.core.data.social

import com.vettid.core.data.account.AccountNames

/**
 * Why a connection challenges the member (VAULT-MESSAGING 0.65.0 §10.4 "Purpose"): a sign-in to something it runs, an
 * approval of what the challenge's `context` names, or neither. A challenge without a purpose, or with a value this
 * app does not know, is [NONE] and is shown as before 0.65.0. Read only from the vault's `purpose`, never from `context`.
 */
enum class AuthPurpose {
    NONE,
    SIGNIN,
    APPROVAL,
    ;

    companion object {
        /** The wire value's purpose; any other value (or none) is [NONE]. */
        fun of(value: String?): AuthPurpose = when (value) {
            "signin" -> SIGNIN
            "approval" -> APPROVAL
            else -> NONE
        }
    }
}

/**
 * A challenge's `context` (and any note, memo or reason) is another party's own words: cleaned by AccountNames
 * (VAULT-MESSAGING 0.76.0 §10.8 "Showing received text") and shown as plain text that cannot reorder or hide anything.
 */
object AuthContext {
    /** [s] with every break one LF, Cc and Cf removed (but ZWJ and ZWNJ), trimmed per line; "" when nothing shows. */
    fun plain(s: String): String = AccountNames.cleanedLines(s).orEmpty()

    /** [plain] with each line in its own isolate (FSI ... PDI), for next to other text; null when nothing shows. */
    fun isolated(s: String): String? = AccountNames.cleanedLines(s)?.let { AccountNames.isolateLines(it) }

    /** A received name for a fixed sentence: cleaned as one line, then isolated; null when empty. */
    fun isolatedName(s: String): String? = AccountNames.isolateOrNull(s)

    /** The first line that shows something, isolated, for a list row's second line; null when none does. */
    fun firstLineIsolated(s: String): String? = AccountNames.firstLineIsolated(s)

    /** A message's full text for its bubble: breaks as LF, LRM/RLM/ALM kept, each line isolated; "" when empty. */
    fun bubble(s: String): String = AccountNames.cleanedMessage(s).let { if (it.isEmpty()) it else AccountNames.isolateLines(it) }
}
