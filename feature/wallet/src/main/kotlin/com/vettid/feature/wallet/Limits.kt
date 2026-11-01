package com.vettid.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.wallet.CapsRules
import com.vettid.core.wallet.WalletCaps
import java.text.DecimalFormatSymbols

/**
 * What the member typed on the creation step "Set a spending limit?" (ANDROID-PLAN §4 "Spending limits", 0.1.74): the choice
 * ([limit] false is **No limit**, the default), the two optional amounts as typed, and the unit they are typed in. Sats are whole
 * numbers (digits only; the field draws the thousands separators); bitcoin has up to 8 decimals. Prints its type name only.
 */
data class LimitsForm(
    val limit: Boolean = false,
    val perTx: String = "",
    val perPeriod: String = "",
    val btc: Boolean = false,
) {
    /** The other unit, with what is typed carried over when it is a valid amount (else cleared). */
    fun toggled(): LimitsForm =
        copy(btc = !btc, perTx = LimitsInput.convert(perTx, !btc), perPeriod = LimitsInput.convert(perPeriod, !btc))

    override fun toString(): String = "LimitsForm"
}

/** What [LimitsInput.parse] found: the limits to set (null: none), and what is wrong with each field. */
data class LimitsParse(
    val caps: WalletCaps?,
    val perTxProblem: LimitProblem? = null,
    val perPeriodProblem: LimitProblem? = null,
) {
    /** The step may go on: No limit, or limits the vault would take. */
    val ok: Boolean get() = perTxProblem == null && perPeriodProblem == null
}

enum class LimitProblem {
    /** Not a number, under 1 sat, or over 2,100,000,000,000,000 sats. */
    OUT_OF_RANGE,

    /** "Most per payment" above "Most in any 24 hours". */
    ABOVE_PERIOD,

    /** "Set a limit" chosen and both fields empty. */
    EMPTY,
}

/** The creation step's rules, pure (the view models and the tests use them). */
object LimitsInput {
    /** The characters a field takes in [btc] or sats: digits (and in bitcoin one decimal separator), at most 16 digits in sats. */
    fun filter(text: String, btc: Boolean): String =
        if (btc) BtcFormat.filter(text) else text.filter { it in '0'..'9' }.take(MAX_DIGITS)

    /** [text] as satoshis: null when empty, [INVALID] when it is not a whole number of sats (or bitcoin) in range. */
    fun sats(text: String, btc: Boolean): Long? = when {
        text.isBlank() -> null
        btc -> (BtcFormat.parse(text) as? BtcFormat.Amount.Valid)?.sats ?: INVALID
        else -> text.toLongOrNull() ?: INVALID
    }

    /** What the form says: No limit, or the two amounts checked as the vault checks them (1 to 2.1e15, per payment <= per period). */
    fun parse(form: LimitsForm): LimitsParse {
        if (!form.limit) return LimitsParse(null)
        val tx = sats(form.perTx, form.btc)
        val period = sats(form.perPeriod, form.btc)
        val txBad = tx != null && (tx == INVALID || tx !in 1..CapsRules.MAX_SATS)
        val periodBad = period != null && (period == INVALID || period !in 1..CapsRules.MAX_SATS)
        return when {
            tx == null && period == null -> LimitsParse(null, LimitProblem.EMPTY)
            txBad || periodBad -> LimitsParse(
                null,
                LimitProblem.OUT_OF_RANGE.takeIf { txBad },
                LimitProblem.OUT_OF_RANGE.takeIf { periodBad },
            )
            tx != null && period != null && tx > period -> LimitsParse(null, LimitProblem.ABOVE_PERIOD)
            else -> LimitsParse(WalletCaps(tx, period))
        }
    }

    /** The same amount in the other unit when the field holds a valid one; an invalid or empty field is cleared. */
    fun convert(text: String, toBtc: Boolean): String {
        val sats = sats(text, !toBtc)?.takeIf { it > 0 } ?: return ""
        return if (toBtc) BtcFormat.btc(sats) else sats.toString()
    }

    private const val MAX_DIGITS = 16
    private const val INVALID = -1L
}

/** Draws a whole number of sats with the locale's thousands separator ("2,000,000") without changing what is typed. */
internal object ThousandsTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val sep = DecimalFormatSymbols.getInstance().groupingSeparator
        val out = StringBuilder()
        for ((i, c) in raw.withIndex()) {
            if (i > 0 && (raw.length - i) % GROUP == 0) out.append(sep)
            out.append(c)
        }
        // Separators sit before the digits whose distance from the end is a multiple of three.
        fun before(offset: Int): Int = separatorsBefore(raw.length, offset)
        return TransformedText(
            AnnotatedString(out.toString()),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = offset + before(offset)

                override fun transformedToOriginal(offset: Int): Int {
                    var o = 0
                    while (o < raw.length && o + before(o) < offset) o++
                    return o
                }
            },
        )
    }

    /** The separators drawn before the first [offset] characters of a number [length] digits long. */
    private fun separatorsBefore(length: Int, offset: Int): Int = (0 until offset).count { i -> i > 0 && (length - i) % GROUP == 0 }

    private const val GROUP = 3
}

/**
 * The step "Set a spending limit?" (ANDROID-PLAN §4 "Spending limits", Texts): **No limit** (the default) or **Set a limit** with
 * the two optional fields, in sats with a toggle to bitcoin. Nothing is suggested.
 */
@Composable
internal fun LimitsFields(
    form: LimitsForm,
    parse: LimitsParse,
    actions: LimitsActions,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        FilterChip(
            selected = !form.limit,
            onClick = { actions.onChoice(false) },
            label = { Text(stringResource(R.string.wallet_limits_none)) },
            modifier = Modifier.testTag("limits_none"),
        )
        FilterChip(
            selected = form.limit,
            onClick = { actions.onChoice(true) },
            label = { Text(stringResource(R.string.wallet_limits_set)) },
            modifier = Modifier.testTag("limits_set"),
        )
    }
    if (!form.limit) return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        LimitField(form.perTx, form.btc, R.string.wallet_limit_per_tx, parse.perTxProblem, actions.onPerTx, "limit_per_tx")
        val period = form.perPeriod
        LimitField(period, form.btc, R.string.wallet_limit_per_period, parse.perPeriodProblem, actions.onPerPeriod, "limit_per_period")
        parse.perTxProblem.takeIf { it == LimitProblem.ABOVE_PERIOD || it == LimitProblem.EMPTY }?.let { p ->
            Text(
                stringResource(if (p == LimitProblem.EMPTY) R.string.wallet_limit_need_one else R.string.wallet_limit_order),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("limit_form_problem"),
            )
        }
        TextButton(onClick = actions.onToggleUnit, modifier = Modifier.testTag("limit_unit_toggle")) {
            Text(stringResource(if (form.btc) R.string.wallet_limits_show_sats else R.string.wallet_limits_show_btc))
        }
    }
}

/** What the limits step does; the creation flows pass their view model's functions. */
data class LimitsActions(
    val onChoice: (Boolean) -> Unit = {},
    val onPerTx: (String) -> Unit = {},
    val onPerPeriod: (String) -> Unit = {},
    val onToggleUnit: () -> Unit = {},
)

/** One limit's field. [message] is an error of the screen's own (shown in place of the amount in sats); [note] a line under it. */
@Composable
internal fun LimitField(
    value: String,
    btc: Boolean,
    label: Int,
    problem: LimitProblem?,
    onChange: (String) -> Unit,
    tag: String,
    message: String? = null,
    note: String? = null,
    enabled: Boolean = true,
) {
    val parsed = LimitsInput.sats(value, btc)?.takeIf { it > 0 }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        suffix = { Text(stringResource(if (btc) R.string.wallet_unit_btc else R.string.wallet_unit_sats)) },
        isError = problem == LimitProblem.OUT_OF_RANGE || message != null,
        enabled = enabled,
        supportingText = when {
            problem == LimitProblem.OUT_OF_RANGE -> {
                { Text(stringResource(R.string.wallet_limit_out_of_range, BtcFormat.sats(CapsRules.MAX_SATS))) }
            }
            message != null -> {
                { Text(message) }
            }
            // In bitcoin, the sats it comes to: sats everywhere in this screen, whole numbers with a thousands separator.
            btc && parsed != null -> {
                { Text(stringResource(R.string.wallet_balance_sats, BtcFormat.sats(parsed))) }
            }
            note != null -> {
                { Text(note) }
            }
            else -> null
        },
        singleLine = true,
        visualTransformation = if (btc) VisualTransformation.None else ThousandsTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = if (btc) KeyboardType.Decimal else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
