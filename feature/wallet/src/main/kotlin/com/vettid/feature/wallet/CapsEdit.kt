package com.vettid.feature.wallet

import com.vettid.core.wallet.CapsRules

/**
 * What the member typed on "Lower a limit" or "Raise or remove a limit" (ANDROID-PLAN §4 "Spending limits", "The screen"): each
 * limit as typed (empty: keep it as it is), whether it is to be removed (the raise form only), and the unit it is typed in.
 * Prints its type name only.
 */
data class CapsEditForm(
    val perTx: String = "",
    val perPeriod: String = "",
    val removeTx: Boolean = false,
    val removePeriod: Boolean = false,
    val btc: Boolean = false,
) {
    /** The other unit, with what is typed carried over when it is a valid amount (else cleared). */
    fun toggled(): CapsEditForm =
        copy(btc = !btc, perTx = LimitsInput.convert(perTx, !btc), perPeriod = LimitsInput.convert(perPeriod, !btc))

    /** Something was typed or switched. */
    val touched: Boolean get() = perTx.isNotEmpty() || perPeriod.isNotEmpty() || removeTx || removePeriod

    override fun toString(): String = "CapsEditForm"
}

/** Which way a form moves one limit. */
enum class CapMove {
    SAME,

    /** A smaller number, or a limit where there was none. */
    LOWER,

    /** A larger number, or the limit removed. */
    RAISE,
}

/** What is wrong with one field. */
enum class CapFieldProblem {
    /** Not a number, under 1 sat or over 2,100,000,000,000,000 sats. */
    OUT_OF_RANGE,

    /** "Lower a limit" with a number that is not lower. */
    NOT_LOWER,
}

/** What is wrong with the form as a whole. */
enum class CapFormProblem {
    /** Nothing would change. */
    NOTHING,

    /** "Most per payment" above "Most in any 24 hours". */
    ABOVE_PERIOD,
}

/** Which request the form comes to. */
enum class CapsAction {
    NONE,

    /** Only lowers (or sets where there was none): `wallet.caps.set`, no password. */
    SET,

    /** Raises or removes something (and may lower another): `wallet.caps.raise`, PIN and password. */
    RAISE,
}

/**
 * What the form comes to, against the limits as they stand. [newPerTx] and [newPerPeriod] are the values the wallet will have
 * (null: no limit); `wallet.caps.set` sends only the members that lower ([setPerTx], [setPerPeriod]).
 */
data class CapsPlan(
    val action: CapsAction,
    val newPerTx: Long?,
    val newPerPeriod: Long?,
    val txMove: CapMove,
    val periodMove: CapMove,
    val txProblem: CapFieldProblem? = null,
    val periodProblem: CapFieldProblem? = null,
    val problem: CapFormProblem? = null,
) {
    /** The form may go on. */
    val ok: Boolean get() = action != CapsAction.NONE && txProblem == null && periodProblem == null && problem == null

    /** `wallet.caps.set`'s members: the ones that lower; the others are left out, so unchanged. */
    val setPerTx: Long? get() = newPerTx.takeIf { txMove == CapMove.LOWER }
    val setPerPeriod: Long? get() = newPerPeriod.takeIf { periodMove == CapMove.LOWER }

    override fun toString(): String = "CapsPlan"
}

/** The rules of the two forms, pure (the view model and the tests use them). */
object CapsEdit {
    private class Member(val wanted: Long?, val move: CapMove, val problem: CapFieldProblem?)

    /**
     * [raiseMode] false is "Lower a limit": only a lower number, or a number where there was none, is accepted. True is "Raise or
     * remove a limit": any change; what only lowers is still sent as `set`, what raises (or removes) as `raise`, and both as
     * `raise` with the lowered value in it.
     */
    fun plan(nowTx: Long?, nowPeriod: Long?, form: CapsEditForm, raiseMode: Boolean): CapsPlan {
        val tx = member(nowTx, form.perTx, form.removeTx && raiseMode, form.btc, raiseMode)
        val period = member(nowPeriod, form.perPeriod, form.removePeriod && raiseMode, form.btc, raiseMode)
        val moves = listOf(tx.move, period.move)
        val action = when {
            CapMove.RAISE in moves -> CapsAction.RAISE
            CapMove.LOWER in moves -> CapsAction.SET
            else -> CapsAction.NONE
        }
        val fieldsOk = tx.problem == null && period.problem == null
        val problem = when {
            !fieldsOk -> null
            CapsRules.problem(tx.wanted, period.wanted) != null -> CapFormProblem.ABOVE_PERIOD
            action == CapsAction.NONE -> CapFormProblem.NOTHING
            else -> null
        }
        return CapsPlan(action, tx.wanted, period.wanted, tx.move, period.move, tx.problem, period.problem, problem)
    }

    private fun member(now: Long?, text: String, remove: Boolean, btc: Boolean, raiseMode: Boolean): Member {
        val typed = LimitsInput.sats(text, btc)
        return when {
            remove -> Member(null, if (now == null) CapMove.SAME else CapMove.RAISE, null)
            typed == null -> Member(now, CapMove.SAME, null)
            typed !in 1..CapsRules.MAX_SATS -> Member(now, CapMove.SAME, CapFieldProblem.OUT_OF_RANGE)
            else -> {
                val move = when {
                    now == null || typed < now -> CapMove.LOWER
                    typed == now -> CapMove.SAME
                    else -> CapMove.RAISE
                }
                Member(typed, move, CapFieldProblem.NOT_LOWER.takeIf { !raiseMode && move == CapMove.RAISE })
            }
        }
    }
}
