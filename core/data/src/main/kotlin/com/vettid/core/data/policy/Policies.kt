package com.vettid.core.data.policy

/**
 * The vault PIN rules the app enforces when a PIN is chosen (enrollment,
 * PIN change). VAULT-MESSAGING allows 4–32 ASCII digits (§6.7.1, §11.3); the
 * app asks for at least [MIN_LENGTH] and refuses PINs that are guessed first
 * (one repeated digit, straight runs, short repeated patterns, the most
 * common choices). The PIN is guessed only online, through the enclave and
 * its backoff (§11.8), so length beats complexity here.
 */
object PinPolicy {
    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 32
    private const val DIGITS = 10
    private const val MAX_BLOCK = 3

    enum class Problem { NOT_DIGITS, TOO_SHORT, TOO_LONG, REPEATED, SEQUENCE, PATTERN, COMMON }

    private val COMMON = setOf(
        "123123", "112233", "121212", "123321", "654321", "696969", "159753", "147258", "258369", "102030",
        "111222", "123654", "131313", "520520", "112358", "246810", "135790", "142536", "789456", "456123",
        "100200", "098765", "012345", "123456789", "1234567890", "0123456789", "11223344",
    )

    /** The first rule [pin] breaks, or null if it is acceptable. */
    fun check(pin: String): Problem? = when {
        pin.isEmpty() || !pin.all { it in '0'..'9' } -> Problem.NOT_DIGITS
        pin.length < MIN_LENGTH -> Problem.TOO_SHORT
        pin.length > MAX_LENGTH -> Problem.TOO_LONG
        pin.all { it == pin[0] } -> Problem.REPEATED
        isRun(pin, 1) || isRun(pin, -1) -> Problem.SEQUENCE
        pin in COMMON -> Problem.COMMON
        isRepeatedPattern(pin) -> Problem.PATTERN
        else -> null
    }

    /** Every digit is the previous one plus [step] (mod 10): 123456, 890123, 987654. */
    private fun isRun(pin: String, step: Int): Boolean =
        (1 until pin.length).all { i -> (pin[i] - '0') == Math.floorMod((pin[i - 1] - '0') + step, DIGITS) }

    /** A block of one to three digits repeated: 121212, 123123, 1212121. */
    private fun isRepeatedPattern(pin: String): Boolean =
        (1..MAX_BLOCK).any { n -> pin.length >= 2 * n && pin.indices.all { pin[it] == pin[it % n] } }
}

/**
 * The credential password (VAULT-MESSAGING §3.5.1: 8–1,024 UTF-8 bytes) and a
 * strength estimate for the meter. The password protects the credential and
 * the critical items when the vault state is ever decrypted (§3.5.8), where
 * it can be guessed offline against Argon2id, so the app asks for a strong
 * one: at least [Strength.FAIR] to continue.
 */
object PasswordPolicy {
    const val MIN_BYTES = 8
    const val MAX_BYTES = 1024
    private const val FIRST_PRINTABLE = 0x20
    private const val DEL = 0x7f

    enum class Strength { TOO_SHORT, WEAK, FAIR, GOOD, STRONG }

    enum class Problem { TOO_SHORT, TOO_LONG, TOO_WEAK, SAME_AS_PIN, CONTROL_CHARACTERS }

    private val COMMON = setOf(
        "password", "passw0rd", "12345678", "123456789", "qwertyui", "qwerty123", "iloveyou", "sunshine", "princess",
        "football", "baseball", "welcome1", "letmein1", "trustno1", "admin123", "abcd1234", "11111111", "00000000",
        "password1", "password123", "qwertyuiop", "1q2w3e4r", "zaq12wsx", "vettid123", "vettidvault",
    )

    /** An estimate in five steps from length, character variety and obvious patterns. */
    @Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod") // a scoring table
    fun strength(password: String): Strength {
        val bytes = password.toByteArray(Charsets.UTF_8).size
        if (bytes < MIN_BYTES) return Strength.TOO_SHORT
        val lower = password.lowercase()
        if (lower in COMMON || password.toSet().size <= 2) return Strength.WEAK
        val classes = listOf(
            password.any { it.isLowerCase() },
            password.any { it.isUpperCase() },
            password.any { it.isDigit() },
            password.any { !it.isLetterOrDigit() },
        ).count { it }
        val unique = password.toSet().size
        var score = when {
            password.length >= 20 -> 3
            password.length >= 14 -> 2
            password.length >= 10 -> 1
            else -> 0
        }
        score += when (classes) {
            4 -> 2
            3 -> 1
            else -> 0
        }
        if (password.count { it == ' ' } >= 2 && password.length >= 16) score += 1 // a passphrase
        if (unique < password.length / 3) score -= 1
        if (password.all { it.isDigit() }) score -= 1
        return when {
            score <= 0 -> Strength.WEAK
            score == 1 -> Strength.FAIR
            score == 2 || score == 3 -> Strength.GOOD
            else -> Strength.STRONG
        }
    }

    /** The first rule [password] breaks, or null. [pin] is the vault PIN, which must not be reused. */
    fun check(password: String, pin: String? = null): Problem? {
        val bytes = password.toByteArray(Charsets.UTF_8).size
        return when {
            bytes < MIN_BYTES -> Problem.TOO_SHORT
            bytes > MAX_BYTES -> Problem.TOO_LONG
            password.any { it.code < FIRST_PRINTABLE || it.code == DEL } -> Problem.CONTROL_CHARACTERS
            pin != null && password == pin -> Problem.SAME_AS_PIN
            strength(password) < Strength.FAIR -> Problem.TOO_WEAK
            else -> null
        }
    }
}

/** The phrase `vault.delete` requires (§10.2, §12.5). */
object DeletePhrase {
    const val PHRASE = "delete my vault"

    fun matches(input: String): Boolean = input.trim() == PHRASE
}
