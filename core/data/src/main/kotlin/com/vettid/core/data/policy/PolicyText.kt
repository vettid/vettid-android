package com.vettid.core.data.policy

import androidx.annotation.StringRes
import com.vettid.core.data.R

/** The message for a PIN that breaks [PinPolicy] (some take [PinPolicy.MIN_LENGTH] / [PinPolicy.MAX_LENGTH] as argument). */
@StringRes
fun PinPolicy.Problem.messageRes(): Int = when (this) {
    PinPolicy.Problem.NOT_DIGITS -> R.string.data_pin_problem_digits
    PinPolicy.Problem.TOO_SHORT -> R.string.data_pin_problem_short
    PinPolicy.Problem.TOO_LONG -> R.string.data_pin_problem_long
    PinPolicy.Problem.REPEATED -> R.string.data_pin_problem_repeated
    PinPolicy.Problem.SEQUENCE -> R.string.data_pin_problem_sequence
    PinPolicy.Problem.PATTERN -> R.string.data_pin_problem_pattern
    PinPolicy.Problem.COMMON -> R.string.data_pin_problem_common
}

/** The format argument of [messageRes], if any. */
fun PinPolicy.Problem.messageArg(): Int? = when (this) {
    PinPolicy.Problem.TOO_SHORT -> PinPolicy.MIN_LENGTH
    PinPolicy.Problem.TOO_LONG -> PinPolicy.MAX_LENGTH
    else -> null
}

@StringRes
fun PasswordPolicy.Problem.messageRes(): Int = when (this) {
    PasswordPolicy.Problem.TOO_SHORT -> R.string.data_password_problem_short
    PasswordPolicy.Problem.TOO_LONG -> R.string.data_password_problem_long
    PasswordPolicy.Problem.TOO_WEAK -> R.string.data_password_problem_weak
    PasswordPolicy.Problem.SAME_AS_PIN -> R.string.data_password_problem_pin
    PasswordPolicy.Problem.CONTROL_CHARACTERS -> R.string.data_password_problem_control
}

@StringRes
fun PasswordPolicy.Strength.labelRes(): Int = when (this) {
    PasswordPolicy.Strength.TOO_SHORT -> R.string.data_strength_too_short
    PasswordPolicy.Strength.WEAK -> R.string.data_strength_weak
    PasswordPolicy.Strength.FAIR -> R.string.data_strength_fair
    PasswordPolicy.Strength.GOOD -> R.string.data_strength_good
    PasswordPolicy.Strength.STRONG -> R.string.data_strength_strong
}

/** The line under a new password for [this] rule (ANDROID-PLAN "Passwords", verbatim). */
@StringRes
fun PasswordRule.messageRes(): Int = when (this) {
    PasswordRule.SHORT -> R.string.data_password_rule_short
    PasswordRule.REPEATED -> R.string.data_password_rule_repeated
    PasswordRule.COMMON -> R.string.data_password_rule_common
    PasswordRule.SAME_AS_PIN -> R.string.data_password_rule_pin
    PasswordRule.GUESSABLE -> R.string.data_password_rule_guessable
    PasswordRule.TOO_LONG -> R.string.data_password_problem_long
    PasswordRule.CONTROL_CHARACTERS -> R.string.data_password_problem_control
    PasswordRule.VAULT_REFUSED -> R.string.data_failure_weak_password
    PasswordRule.OK -> R.string.data_password_rule_ok
}

/** The estimator's short hint after [PasswordRule.GUESSABLE], else null. */
@StringRes
fun PasswordRule.hintRes(): Int? = if (this == PasswordRule.GUESSABLE) R.string.data_password_rule_guessable_hint else null
