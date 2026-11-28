package com.vettid.core.data.vault

/**
 * (#19721 b′) When the vault-gone probe runs ([watching]) and when the lock it finds is applied ([mayLock]): the vault open
 * on this phone, an activity of the app started (never a process a push or the service started), and no owner-check screen
 * running a probe of its own (it knows the lock's cause; this one would mark it unknown). The lock also needs no unlock under
 * way and none answered since the probe began ([begin]): an unlock's result is newer than the member API's word, which may
 * be the lock that unlock began with (VAULT-MESSAGING §11.4 `ts`).
 */
internal class VaultGoneGate(
    private val phase: () -> AppPhase,
    private val activityStarted: () -> Boolean,
    private val unlockInFlight: () -> Boolean,
    /** The unlock results taken so far ([LockNotices.openings]). */
    private val openings: () -> Long,
    private val checkScreenProbing: () -> Boolean,
) {
    fun watching(): Boolean = phase() == AppPhase.Unlocked && activityStarted() && !checkScreenProbing()

    /** The probe begins: the mark [mayLock] compares. */
    fun begin(): Long = openings()

    fun mayLock(mark: Long): Boolean =
        phase() == AppPhase.Unlocked && !unlockInFlight() && openings() == mark && !checkScreenProbing()
}
