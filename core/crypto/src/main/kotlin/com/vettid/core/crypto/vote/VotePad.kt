package com.vettid.core.crypto.vote

/**
 * `pad` (VAULT-MESSAGING 0.83.6 §10.19.5 "Sizes that do not depend on the choice"): every JSON object a vote message
 * carries with a `choice_id` in it also carries `pad`, ASCII `0` characters to the number of 32 minus the byte length of
 * that `choice_id` (its UTF-8 bytes, not its escaped JSON form), and empty for a `choice_id` longer than 32 bytes. Senders
 * write it exactly so; a receiver never checks it or requires it (an exception to §5.3), so nothing here reads one.
 */
object VotePad {
    /** The longest `choice_id` padded to (BALLOT-PROTOCOL §8.5: 1 to 32 bytes). */
    const val WIDTH = 32

    /** The `pad` for [choiceId]. */
    fun of(choiceId: String): String = of(choiceId.toByteArray(Charsets.UTF_8))

    /** The `pad` for a `choice_id` of these UTF-8 bytes. */
    fun of(choiceId: ByteArray): String = "0".repeat((WIDTH - choiceId.size).coerceAtLeast(0))
}
