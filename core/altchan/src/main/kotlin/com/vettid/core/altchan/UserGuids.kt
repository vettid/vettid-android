package com.vettid.core.altchan

/**
 * The member's `user_guid` (VAULT-MESSAGING §6.7.1, §11.4, §11.11.7, §11.12.1): 1–128 printable ASCII characters
 * (0x21–0x7E), so that it fits the signing strings of the unlock, the enrollment and the recovery. The one check for
 * every `user_guid` that enters the app (the setup code's redeem, the recovery claim, a transfer's `device.paired`) and
 * for every stored value read back: any other value is no `user_guid` (fail closed).
 */
object UserGuids {
    const val MAX_LENGTH = 128
    private const val PRINTABLE_MIN = 0x21
    private const val PRINTABLE_MAX = 0x7e

    fun valid(g: String): Boolean = g.length in 1..MAX_LENGTH && g.all { c -> c.code in PRINTABLE_MIN..PRINTABLE_MAX }
}
