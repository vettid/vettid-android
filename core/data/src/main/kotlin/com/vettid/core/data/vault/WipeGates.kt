package com.vettid.core.data.vault

import com.vettid.core.altchan.RecoveryCode

/**
 * The vault session of this phone, opened only while no wipe runs and none is unfinished ([AccountRecord.closed]): a
 * session opened during a wipe (a lock retry, the owner check, a refresh, the notification service) would start the old
 * device's relay and observer again and outlive the wipe.
 */
internal object SessionGate {
    /** The error of a session asked for while a wipe runs. */
    const val CODE_WIPING = "wiping"

    /** [current], or one made by [create]; refused while the record is closed, whatever is current. */
    suspend fun <S : Any> open(record: AccountRecord, current: S?, create: suspend () -> S): S {
        refuseWhileClosed(record)
        return current ?: create()
    }

    fun refuseWhileClosed(record: AccountRecord) {
        if (record.eraseFailed) throw VaultFailure(FailureKind.ERASE_FAILED, WipeRun.CODE_WIPE_PENDING)
        if (record.closed) throw VaultFailure(FailureKind.BUSY, CODE_WIPING)
    }
}

/**
 * The ways a vault comes to this phone (a setup code's redeem, a transfer to it, a recovery by QR or by typed code), each
 * after an unfinished wipe is finished ([WipeRun.beforeEnrolment]) and refused while it stays unfinished, so that a later
 * start never erases a new vault's keys. [T] is the typed recovery code.
 */
internal class Enrolments<T>(private val pending: () -> Boolean, private val finish: suspend () -> Unit, private val ops: Ops<T>) {
    /** The enrolments themselves (the vault manager's). */
    interface Ops<T> {
        suspend fun redeemSetupCode(code: SetupCodeInput): String

        suspend fun transferIn(code: String): String

        suspend fun registerRecovery(code: RecoveryCode): RecoveryRegistration

        suspend fun registerTypedRecovery(typed: T): RecoveryRegistration
    }

    suspend fun redeemSetupCode(code: SetupCodeInput): String {
        before()
        return ops.redeemSetupCode(code)
    }

    suspend fun transferIn(code: String): String {
        before()
        return ops.transferIn(code)
    }

    suspend fun registerRecovery(code: RecoveryCode): RecoveryRegistration {
        before()
        return ops.registerRecovery(code)
    }

    suspend fun registerTypedRecovery(typed: T): RecoveryRegistration {
        before()
        return ops.registerTypedRecovery(typed)
    }

    private suspend fun before() = WipeRun.beforeEnrolment(pending, finish)
}
