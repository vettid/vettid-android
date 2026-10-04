package com.vettid.core.data.vault

import com.vettid.core.altchan.SignInStatus
import com.vettid.core.data.account.SignInLink
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/**
 * The member's account on this device: the phase the app is in, sign-in
 * (magic link, optional account PIN), the membership and terms checks, and
 * sign-out. Every failure is a [VaultFailure].
 */
interface AccountRepository {
    val phase: StateFlow<AppPhase>
    val account: StateFlow<AccountInfo?>

    /** The address a sign-in link was last requested for (survives restarts). */
    val pendingEmail: StateFlow<String?>

    /** A development hint for the sign-in screens (devStack builds only). */
    val devHint: String?

    /** Hosts whose `/auth/` links are accepted. */
    val signInHosts: Set<String>

    /** Re-reads the session, `Me` and the vault's state, and sets [phase]. */
    suspend fun refresh()

    suspend fun startSignIn(email: String)

    suspend fun verifySignIn(email: String, link: SignInLink): SignInStatus

    suspend fun signInPin(pin: String): SignInStatus

    /** Ends the member session on this device; the vault stays paired with it. */
    suspend fun signOut()
}

/** The vault on this device: enrollment, unlock and lock, status, PIN, deletion, recovery. */
@Suppress("TooManyFunctions")
interface VaultRepository {
    /** Enrolls a vault with [pin] (§11.3) and runs the first handshake. */
    suspend fun enroll(pin: String, onStep: (EnrollStep) -> Unit)

    /** Creates the Protean Credential (§3.5.5), sets the backup (§3.5.6) and confirms the vault. */
    suspend fun createCredential(password: String, backup: Boolean, onStep: (EnrollStep) -> Unit)

    /** Leaves onboarding for the app. */
    suspend fun finishSetup()

    /** The release check before the PIN (§11.10.6). */
    suspend fun preflight(): PreflightInfo

    /** Unlocks (§11.4), optionally approving [approve] (§11.10.3) or cancelling a recovery (§11.11.4). */
    suspend fun unlock(pin: String, approve: ReleaseView? = null, cancelRecovery: Boolean = false): UnlockAttempt

    suspend fun lock()

    suspend fun overview(): VaultOverview

    /** `pin.change` (§10.6). */
    suspend fun changePin(pin: String, newPin: String)

    /** `vault.delete` (§12.5): irreversible. */
    suspend fun deleteVault(pin: String, password: String)

    suspend fun recovery(): RecoveryView?

    suspend fun cancelRecovery(recoveryId: String)

    suspend fun attestationInfo(): AttestationInfo
}

/** The Protean Credential (§3.5): status, unlock window, password, rotation, backup, clone alarms. */
interface CredentialRepository {
    /** The open clone alarm, if any (§3.5.9). */
    val alarm: StateFlow<CredentialAlarm?>

    /** Until when the unlock window is open (§3.5.3), as this app opened it. */
    val unlockWindow: StateFlow<Instant?>

    suspend fun status(): CredentialStatus

    suspend fun openUnlockWindow(password: String)

    suspend fun closeUnlockWindow()

    suspend fun changePassword(password: String, newPassword: String)

    suspend fun rotate(password: String)

    suspend fun setBackup(on: Boolean)

    suspend fun setUnlockTtl(seconds: Int)

    /** Answers the alarm: [mine] = "that was me" (§3.5.9). Either way a rotation follows. */
    suspend fun confirmAlarm(mine: Boolean)
}
