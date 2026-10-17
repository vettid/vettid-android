package com.vettid.core.data.vault

import java.time.Duration

/**
 * Screens a member could not leave (owner decision: a vault problem must have a path forward). Where the vault cannot be
 * reached for a long time, the root screen offers to erase VettID from this phone or to recover the vault, besides
 * "Try again".
 */
object StuckScreens {
    /**
     * How long without any contact with the vault service before erase-or-recover is offered beside "Try again": 7
     * days, the app's other long bound (a kept transfer, the idle lock after an overdue owner check). Ordinary outages
     * (travel, a maintenance window, a dead network for a weekend) end well before it; past it, the phone has most
     * likely been replaced, or the member needs another way back.
     */
    val LONG_UNREACHABLE: Duration = Duration.ofDays(7)

    /** Failures that already have their own way out, or none to offer (the phone's clock is the member's to fix). */
    private val OWN_WAY = setOf(
        FailureKind.CLOCK,
        FailureKind.NO_VAULT,
        FailureKind.LOCAL_STATE,
        FailureKind.ERASE_FAILED,
        FailureKind.UNAUTHORIZED,
    )

    /**
     * Whether the root screen for [kind] also offers erase-or-recover because nothing has reached the vault service for
     * [LONG_UNREACHABLE]: [lastContactMs] is the last contact this phone knows of (null: none known, nothing offered).
     */
    fun longUnreachable(kind: FailureKind, lastContactMs: Long?, nowMs: Long): Boolean =
        kind !in OWN_WAY && lastContactMs != null && nowMs - lastContactMs >= LONG_UNREACHABLE.toMillis()

    /** A last contact is written at most this often (every start and return reads the status). */
    val CONTACT_WRITE_EVERY: Duration = Duration.ofHours(1)

    /** Whether a contact at [nowMs] is worth writing over [lastContactMs]. */
    fun writeContact(lastContactMs: Long?, nowMs: Long): Boolean =
        lastContactMs == null || nowMs - lastContactMs >= CONTACT_WRITE_EVERY.toMillis() || nowMs < lastContactMs
}
