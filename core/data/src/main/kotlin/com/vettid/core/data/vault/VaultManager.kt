package com.vettid.core.data.vault

import android.content.Context
import com.vettid.core.altchan.AltChannelFlow
import com.vettid.core.altchan.AltTrust
import com.vettid.core.altchan.Approval
import com.vettid.core.altchan.MemberApiClient
import com.vettid.core.altchan.MemberApiException
import com.vettid.core.altchan.RecoveryCode
import com.vettid.core.altchan.UnlockOptions
import com.vettid.core.altchan.UnlockOutcome
import com.vettid.core.attestation.AttestationException
import com.vettid.core.attestation.android.KeyDescription
import com.vettid.core.attestation.android.RootOfTrust
import com.vettid.core.attestation.manifest.ManifestVerifier
import com.vettid.core.attestation.manifest.Release
import com.vettid.core.attestation.manifest.ReleaseManifest
import com.vettid.core.crypto.Bytes
import com.vettid.core.data.KeystoreFileStore
import com.vettid.core.data.account.MemberGateway
import com.vettid.core.data.social.InviteLinks
import com.vettid.core.data.social.SocialManager
import com.vettid.core.data.env.AppEnvironment
import com.vettid.core.data.wipe.LocalWipe
import com.vettid.core.keystore.AndroidKeys
import com.vettid.core.keystore.DeviceAttestationKey
import com.vettid.core.keystore.DeviceKeys
import com.vettid.core.keystore.KeySlot
import com.vettid.core.keystore.KeystoreException
import com.vettid.core.keystore.SeedWrapKey
import com.vettid.core.keystore.SharedPreferencesWrappedKeyStore
import com.vettid.core.vault.DeviceConfig
import com.vettid.core.vault.DeviceSecrets
import com.vettid.core.vault.VaultApi
import com.vettid.core.vault.VaultDevice
import com.vettid.core.vault.VaultJson
import com.vettid.core.vault.VaultMessage
import com.vettid.core.vault.VaultOpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The app's vault on this device: the member API signed by the app key
 * ([MemberGateway]; no sign-in, VAULT-MESSAGING 0.15.0 §11.12), the
 * device keys from the Keystore, the device state in an encrypted file, the
 * vault client and the alternate channel. Implements the repositories the
 * features use; nothing in a feature touches transport or crypto.
 *
 * One instance per process (Hilt singleton). Its [scope] runs the mailbox
 * collector and the event observer while the app process lives.
 *
 * A phone that a direct transfer (§6.7.1) or a recovery (§11.11.5) replaced
 * erases itself through [wiper] once an authenticated signal proves it
 * ([HolderWatch], [HolderPolicy]); the app is then as freshly installed.
 */
@Suppress("TooManyFunctions", "LargeClass")
class VaultManager(
    context: Context,
    private val env: AppEnvironment,
    baseHttp: OkHttpClient,
    private val scope: CoroutineScope,
    private val deviceName: String,
    private val wiper: LocalWipe,
) : AccountRepository, VaultRepository, CredentialRepository, MoveRepository {
    private val app = context.applicationContext
    private val http = env.http(baseHttp)
    private val gateway: MemberGateway = env.memberGateway(app, http) { session?.device?.vaultId ?: local.vaultId.ifEmpty { null } }
    private val accountFile = KeystoreFileStore(File(app.noBackupFilesDir, "account.bin"), "account")
    private val mutex = Mutex()
    private var session: Session? = null
    private var observer: Job? = null
    private var local: LocalAccount = loadLocal()

    /** Bumped by every wipe: a phase computed before it is not published after it. */
    @Volatile
    private var generation = 0

    /** Proofs that this phone was replaced, and the wipe they start. */
    val holder = HolderWatch(scope) { wipeThisPhone() }

    private val phaseFlow = MutableStateFlow<AppPhase>(AppPhase.Starting)
    private val accountFlow = MutableStateFlow(local.toInfo())
    private val alarmFlow = MutableStateFlow<CredentialAlarm?>(null)
    private val windowFlow = MutableStateFlow<Instant?>(null)
    private val pausedFlow = MutableStateFlow(false)
    private val deletionFlow = MutableStateFlow<DeletionView?>(null)
    override val pendingDeletion: StateFlow<DeletionView?> = deletionFlow.asStateFlow()

    override val phase: StateFlow<AppPhase> = phaseFlow.asStateFlow()
    override val account: StateFlow<AccountInfo?> = accountFlow.asStateFlow()
    override val alarm: StateFlow<CredentialAlarm?> = alarmFlow.asStateFlow()
    override val unlockWindow: StateFlow<Instant?> = windowFlow.asStateFlow()
    override val servicePaused: StateFlow<Boolean> = pausedFlow.asStateFlow()
    override val devHint: String? get() = gateway.devHint
    override val apiOrigin: String = env.endpoints.apiBase.trimEnd('/')

    /** Refused deposits to the vault: the open app goes to the unlock screen (never a wipe, [RefusalWatch]). */
    private val refusals = RefusalWatch {
        windowFlow.value = null
        phaseFlow.value = AppPhase.Locked
    }
    override val refusedByVault: StateFlow<Boolean> = refusals.suspected

    private class Session(val device: VaultDevice, val api: VaultApi, val member: MemberApiClient, val alt: AltChannelFlow)

    /**
     * A canary manifest loaded out of band (VAULT-RELEASES §10.1 step 9, W10-READINESS P31/B5): verified under
     * this build's pinned manifest keys and used instead of the published manifest only while it is newer.
     */
    val canary = CanaryManifestStore(
        KeystoreFileStore(File(app.noBackupFilesDir, CANARY_FILE), "canary-manifest"),
        verifier = { ManifestVerifier(env.trust(http).manifestKeys) },
        seen = { session?.device?.altState?.manifestSerial ?: 0 },
        published = { fetchPublishedManifest() },
    )

    /** Connections, messages and approvals (A4): the repositories of those features. */
    val social = SocialManager(
        scope,
        api = { session().api },
        credential = this,
        store = KeystoreFileStore(File(app.noBackupFilesDir, SOCIAL_FILE), "social"),
    )

    /** The daily owner check (VAULT-MESSAGING 0.13.0 §3.6): its state, the check, the interval and the hold. */
    val ownerCheck: OwnerCheckManager = KeystoreFileStore(File(app.noBackupFilesDir, OWNER_CHECK_FILE), "owner-check").let { f ->
        OwnerCheckManager(
            scope,
            ops = { VaultOwnerCheckOps(session().api) },
            load = { runCatching { f.load() }.getOrNull() },
            persist = { f.save(it) },
            // §3.6.3: nothing held back is replayed as events; the lists are read again.
            onPassed = { social.refreshAllQuietly() },
        )
    }

    init {
        scope.launch { runCatching { canary.load() } }
        // Before MEMBER-API 2.0.0 the app kept the account site's session cookies here; it never signs in now.
        scope.launch { io { runCatching { File(app.noBackupFilesDir, LEGACY_SESSION_FILE).delete() } } }
        scope.launch {
            // Lists are re-read whenever the vault opens (unlock, end of onboarding, app start while unlocked).
            phaseFlow.collect {
                if (it == AppPhase.Unlocked) {
                    social.refreshAllQuietly()
                    launch { refreshAccount() }
                }
            }
        }
    }

    // --- local account record (encrypted under a Keystore key) ---

    /**
     * What this phone knows of its member without signing in: the `user_guid` and `vault_id` from the setup code's
     * redeem or the recovery's claim (§11.12.1, §11.11.7), the masked email to show, and the last account snapshot
     * the vault sent (§11.13). Fields of older builds (the signed-in member's email and names) are ignored.
     */
    @Serializable
    private data class LocalAccount(
        val userGuid: String = "",
        val vaultId: String = "",
        val emailHint: String = "",
        val snapshot: com.vettid.core.vault.AccountSnapshot? = null,
        /** A direct transfer to this phone started (§6.7.1): it has a vault to come without a redeem. */
        val transferIn: Boolean = false,
        val setupComplete: Boolean = false,
        /**
         * Written by the build before the wipe (0af6ba1) when this phone stopped holding the vault; that build had
         * already forgotten the device keys. Read only to finish such a phone with a wipe.
         */
        val replaced: String? = null,
    ) {
        fun toInfo(): AccountInfo? = when {
            snapshot != null -> snapshot.toInfo(emailHint)
            emailHint.isNotEmpty() -> AccountInfo(emailHint)
            else -> null
        }
    }

    private fun loadLocal(): LocalAccount = try {
        accountFile.load()?.let { json.decodeFromString(LocalAccount.serializer(), String(it)) } ?: LocalAccount()
    } catch (_: KeystoreException) {
        LocalAccount()
    } catch (_: IllegalArgumentException) {
        LocalAccount()
    }

    private fun saveLocal(a: LocalAccount) {
        local = a
        accountFile.save(json.encodeToString(LocalAccount.serializer(), a).toByteArray())
        accountFlow.value = a.toInfo()
    }

    // --- session ---

    private suspend fun session(): Session = mutex.withLock {
        session ?: openSession().also { session = it }
    }

    private suspend fun openSession(): Session = io {
        val trust: AltTrust = env.trust(http)
        val keys = deviceKeys()
        for (slot in KeySlot.entries) if (!keys.has(slot)) keys.generate(slot)
        val secrets = DeviceSecrets(keys.ed25519(KeySlot.IDENTITY), keys.kem(), keys.ed25519(KeySlot.RELAY), gateway.appKey())
        val store = KeystoreFileStore(File(app.noBackupFilesDir, DEVICE_FILE), "vault-device")
        val cfg = DeviceConfig(name = deviceName, relayUrl = env.endpoints.relayUrl, http = http, store = store, trust = trust)
        val device = VaultDevice.load(cfg, secrets) ?: VaultDevice.create(cfg, secrets)
        val member = gateway.member()
        val s = Session(device, VaultApi(device), member, AltChannelFlow(member, trust, canary = canary))
        device.start(scope)
        observe(s)
        s
    }

    private fun deviceKeys() = DeviceKeys(SeedWrapKey.wrapper(), SharedPreferencesWrappedKeyStore(app))

    /** Drops the session after the vault forgot this device: fresh device keys for the next enrollment. */
    private suspend fun dropSession(forget: Boolean) = mutex.withLock {
        observer?.cancel()
        observer = null
        session?.device?.let { d -> if (forget) d.forget() else d.stop() }
        session = null
        refusals.clear()
        if (forget) {
            io {
                val keys = deviceKeys()
                KeySlot.entries.forEach { keys.generate(it) }
                gateway.newAppKey() // the app key is per vault (§11.12.2)
                File(app.noBackupFilesDir, DEVICE_FILE).delete()
            }
            alarmFlow.value = null
            windowFlow.value = null
            social.clear()
            ownerCheck.clear()
            saveLocal(local.copy(setupComplete = false, snapshot = null))
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun observe(s: Session) {
        observer?.cancel()
        observer = scope.launch {
            launch { s.device.vaultRefusals.collect { n -> refusals.onRefusals(n, phaseFlow.value) } }
            launch { s.device.ownerCheckRequired.collect { ownerCheck.onRequired() } }
            s.device.events.collect { m ->
                onEvent(m)
                try {
                    social.onEvent(m)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    // one malformed event must not stop the observer
                    android.util.Log.w("VaultManager", "event ${m.type} not handled: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // one branch per event the app follows
    private suspend fun onEvent(m: VaultMessage) {
        ownerCheck.onEvent(m)
        when (m.type) {
            // §3.6.3: entering the hold ends the credential's unlock window.
            "vault.held" -> windowFlow.value = null
            "vault.locking" -> {
                windowFlow.value = null
                val p = phaseFlow.value
                if (p is AppPhase.Unlocked || p is AppPhase.Setup) phaseFlow.value = AppPhase.Locked
            }
            "credential.alarm" -> {
                val b = m.body
                val id = VaultJson.str(b, "alarm_id") ?: return
                windowFlow.value = null
                alarmFlow.value = CredentialAlarm(id, VaultJson.str(b, "state") ?: CredentialAlarm.STATE_FROZEN, VaultJson.str(b, "at"),
                    VaultJson.str(b, "presenter"))
            }
            "sync.event" -> if (VaultJson.str(m.body, "kind") == SYNC_ACCOUNT_CHANGED) {
                scope.launch { refreshAccount() }
            } else if (VaultJson.str(m.body, "kind") == "credential.alarm") {
                val state = VaultJson.str(m.body, "state")
                val id = VaultJson.str(m.body, "alarm_id")
                alarmFlow.value = when {
                    state == CredentialAlarm.STATE_RESOLVED -> null
                    state != null && id != null -> (alarmFlow.value?.takeIf { it.alarmId == id } ?: CredentialAlarm(id, state, null))
                        .copy(state = state)
                    else -> alarmFlow.value
                }
            }
            // §10.3: opened under the session, from the vault's relay key. `transferred` (§6.7.1 step 4) and
            // `replaced` (§11.11.5 step 3) wipe this phone (in another coroutine: the wipe cancels this observer);
            // another reason (`vault_deleted`) is re-read like any change of the vault.
            HolderPolicy.DEVICE_UNLINKED -> if (!holder.onVaultEvent(m)) scope.launch { refresh() }
        }
    }

    // --- AccountRepository ---

    override suspend fun refresh() {
        val gen = generation
        val p = try {
            evaluate()
        } catch (e: VaultFailure) {
            AppPhase.Unreachable(e.kind)
        }
        if (gen == generation) phaseFlow.value = p // a wipe meanwhile already said SignedOut
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one phase per state of the device
    private suspend fun evaluate(): AppPhase = guard {
        if (local.replaced != null) {
            // A phone the build before the wipe marked replaced (its device keys already forgotten): erase it now.
            holder.wipeNow()
            return@guard AppPhase.SignedOut
        }
        // Nothing redeemed or claimed on this phone (and nothing re-created after a wipe): the welcome screen.
        if (local.userGuid.isEmpty() && !local.transferIn) return@guard AppPhase.SignedOut
        val s = session()
        val d = s.device
        if (d.deviceId == null) {
            if (d.recovering) return@guard AppPhase.Setup(SetupStage.RECOVERING) // registered with a recovery code (§11.11.3)
            if (d.vaultId != null) return@guard AppPhase.Setup(SetupStage.NEEDS_CREDENTIAL) // enrolled, handshake not finished
            // A setup code was redeemed: enroll. The pending key may not read the status (MEMBER-API 2.0.0); the
            // enrollment itself answers vault_exists for a vault this phone does not hold. A transfer that did not
            // complete leaves nothing to resume: the welcome screen.
            return@guard if (local.userGuid.isEmpty()) AppPhase.SignedOut else AppPhase.Setup(SetupStage.NEW_VAULT)
        }
        val st = try {
            vaultStatus(s)
        } catch (e: VaultFailure) {
            // The API no longer accepts this phone's app key (another phone took the vault over, or the vault went):
            // the unlock screen says so; only the enclave's own answer erases anything.
            if (e.kind == FailureKind.UNAUTHORIZED) return@guard AppPhase.Locked
            throw e
        }
        if (st == null) {
            // The vault is gone (deleted, or the account was cancelled and restored): start over with a new code.
            dropSession(forget = true)
            saveLocal(LocalAccount())
            return@guard AppPhase.SignedOut
        }
        if (d.recovering) return@guard AppPhase.Setup(SetupStage.RECOVERING) // paired, the credential not recovered yet
        if (st.state != "unlocked") AppPhase.Locked else afterUnlocked(s)
    }

    @Suppress("ReturnCount")
    private suspend fun afterUnlocked(s: Session): AppPhase {
        if (s.device.recovering) return AppPhase.Setup(SetupStage.RECOVERING)
        if (s.device.credentialVersion == null) return AppPhase.Setup(SetupStage.NEEDS_CREDENTIAL)
        scope.launch { runCatching { refreshAlarm(s) } }
        // §11.11.9: a start-over requested on the portal shows after every unlock.
        scope.launch { runCatching { vaultStatus(s) } }
        ownerCheck.onOpened()
        return if (local.setupComplete) AppPhase.Unlocked else AppPhase.Setup(SetupStage.FINISHING)
    }

    private suspend fun refreshAlarm(s: Session) {
        val info = s.api.credentialVersion()
        alarmFlow.value = info.alarm?.let { a ->
            val id = VaultJson.str(a, "alarm_id") ?: return@let null
            CredentialAlarm(id, VaultJson.str(a, "state") ?: CredentialAlarm.STATE_FROZEN, VaultJson.str(a, "at"))
        }
    }

    override suspend fun redeemSetupCode(code: SetupCodeInput): String {
        // Any refusal of the code itself is `404 invalid_code` ([FailureKind.SETUP_CODE_INVALID], [memberFailure]).
        val r = guard {
            // A phone that holds nothing yet but carries a stale enrollment starts afresh (with a new app key).
            freshDeviceIfUnpaired()
            when (code) {
                is SetupCodeInput.Secret -> gateway.redeemSecret(code.secret)
                is SetupCodeInput.Typed -> gateway.redeemTyped(code.email, code.code)
            }
        }
        gateway.memberChanged(r.userGuid)
        io { saveLocal(LocalAccount(userGuid = r.userGuid, vaultId = r.vaultId, emailHint = r.emailHint)) }
        phaseFlow.value = AppPhase.Setup(SetupStage.NEW_VAULT)
        return r.emailHint
    }

    override suspend fun forgetSetupCode() {
        val paired = session?.device?.deviceId != null
        if (paired) return // a vault on this phone is never dropped this way
        if (session != null) dropSession(forget = true)
        io { saveLocal(LocalAccount()) }
        phaseFlow.value = AppPhase.SignedOut
    }

    /** Re-reads the account snapshot (`account.get`, §11.13); kept only when newer (`as_of`). Quiet on failure. */
    override suspend fun refreshAccount() {
        try {
            val v = guard { session().api.accountGet() }
            val snap = v.account ?: return
            val stored = local.snapshot
            if (stored != null && !newer(snap.asOf, stored.asOf)) return
            io { saveLocal(local.copy(snapshot = snap, emailHint = snap.emailHint ?: local.emailHint)) }
        } catch (_: VaultFailure) {
            // display only: the last snapshot stays
        }
    }

    /**
     * The member confirmed "Erase VettID from this phone": [wipeThisPhone] through [holder] (one wipe at a time,
     * none twice), in [scope] so that leaving the screen does not stop it half-way.
     */
    override suspend fun eraseThisPhone() {
        scope.launch { holder.wipeNow() }.join()
    }

    // --- VaultRepository ---

    override suspend fun enroll(pin: String, onStep: (EnrollStep) -> Unit) = guard {
        val s = session()
        onStep(EnrollStep.ENROLL)
        val out = s.alt.enroll(s.device, local.vaultId, local.userGuid, pin, env.attester())
        if (!out.ok) throw VaultFailure(enrollFailure(out.code), out.code)
        if (out.vaultId.isNotEmpty() && out.vaultId != local.vaultId) io { saveLocal(local.copy(vaultId = out.vaultId)) }
        onStep(EnrollStep.WAIT_FOR_VAULT)
        s.device.awaitEnrolled()
        onStep(EnrollStep.HANDSHAKE)
        s.device.completeEnrollment()
    }

    override suspend fun createCredential(password: String, backup: Boolean, onStep: (EnrollStep) -> Unit) = guard {
        val s = session()
        if (s.device.deviceId == null) {
            onStep(EnrollStep.HANDSHAKE)
            s.device.completeEnrollment()
        }
        onStep(EnrollStep.CREATE_CREDENTIAL)
        if (s.device.credentialVersion == null) s.api.credentialCreate(password)
        onStep(EnrollStep.BACKUP)
        setSetting(s, KEY_BACKUP, JsonPrimitive(backup))
        onStep(EnrollStep.CONFIRM)
        s.api.enrollConfirm()
        phaseFlow.value = AppPhase.Setup(SetupStage.FINISHING)
    }

    override suspend fun finishSetup() {
        io { saveLocal(local.copy(setupComplete = true)) }
        phaseFlow.value = AppPhase.Unlocked
    }

    override suspend fun preflight(): PreflightInfo = guard {
        val s = session()
        val p = s.alt.preflight(s.device.altState)
        PreflightInfo(view(p.routed), p.lastNumber, p.softwareUpdated, p.rollback, p.offer?.let { view(it) })
    }

    override suspend fun unlock(pin: String, approve: ReleaseView?, cancelRecovery: Boolean): UnlockAttempt =
        unlockOnce(pin, approve, cancelRecovery, holderApp = true)

    /**
     * A locked vault past its owner-check deadline (§3.6.5): unlocks with [pin], reads `vault.status` and sends
     * the check with [pin] and [password] before the app opens, so that the member types both once.
     */
    override suspend fun unlockWithCheck(pin: String, password: String, approve: ReleaseView?, cancelRecovery: Boolean): UnlockAttempt =
        unlockOnce(pin, approve, cancelRecovery, holderApp = true) { ownerCheck.afterUnlock(pin, password) }

    /**
     * [holderApp]: this phone unlocks as the vault's app (not a recovering one): an enclave's sealed
     * `unknown_device` then means a transfer (§6.7.1) or a recovery (§11.11.5) replaced it, and it is wiped.
     * A failure before the sealed result (network, HTTP status, timeout, unreadable result) never wipes.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one answer per §11.4 result code
    private suspend fun unlockOnce(
        pin: String,
        approve: ReleaseView?,
        cancelRecovery: Boolean,
        holderApp: Boolean = false,
        beforeOpen: suspend () -> Unit = {},
    ): UnlockAttempt {
        val outcome = try {
            guard {
                val s = session()
                val opts = UnlockOptions(approve = approve?.let { Approval(it.pcr0, it.number) }, cancelRecovery = cancelRecovery)
                var out: UnlockOutcome = s.alt.unlock(s.device, local.userGuid, pin, env.attester(), opts)
                if (out.ok && out.result.update?.result == UPDATE_MOVED) {
                    // §11.10.6: after a move the vault is locked under the new release; unlock again, which reaches it.
                    out = s.alt.unlock(s.device, local.userGuid, pin, env.attester())
                }
                // A sealed result: the enclave knows this phone; earlier relay refusals no longer count (RefusalWatch).
                s.device.clearVaultRefusals()
                refusals.clear()
                out
            }
        } catch (e: VaultFailure) {
            holder.onFailure(e) // never proof (a 503, paused or not, included)
            return UnlockAttempt.Failed(e.kind, e.code, e.retryAfterSeconds)
        }
        val r = outcome.result
        if (!r.ok) {
            if (holderApp && holder.onSealedUnlockResult(r)) return UnlockAttempt.Failed(FailureKind.OTHER, r.code)
            return when (r.code) {
                "bad_pin" -> UnlockAttempt.BadPin(r.retryAfterSeconds)
                "backoff" -> UnlockAttempt.Backoff(r.retryAfterSeconds)
                "recovery_pending" -> UnlockAttempt.RecoveryPending
                "state_rollback" -> UnlockAttempt.StateRollback
                "attestation" -> UnlockAttempt.Failed(FailureKind.ATTESTATION, r.code)
                "manifest" -> UnlockAttempt.Failed(FailureKind.MANIFEST, r.code)
                else -> UnlockAttempt.Failed(FailureKind.OTHER, r.code)
            }
        }
        val refused = r.update?.takeIf { it.result == "refused" }
        val gen = generation
        val s = session()
        scope.launch { runCatching { s.device.flushOutbox() } }
        if (!s.device.recovering) beforeOpen()
        val p = afterUnlocked(s)
        if (gen == generation) phaseFlow.value = p
        return if (refused != null) UnlockAttempt.UpdateRefused(refused.code ?: "refused") else UnlockAttempt.Success
    }

    override suspend fun lock() = guard {
        val s = session()
        val viaRelay = try {
            withTimeoutOrNull(LOCK_TIMEOUT_MS) { s.api.lock() } != null
        } catch (_: IOException) {
            false
        }
        if (!viaRelay) s.device.vaultId?.let { s.alt.lock(it) }
        windowFlow.value = null
        phaseFlow.value = AppPhase.Locked
    }

    override suspend fun overview(): VaultOverview = guard {
        val s = session()
        val st = vaultStatus(s)
        val vs = if (phaseFlow.value is AppPhase.Unlocked) {
            try {
                s.api.status()
            } catch (_: IOException) {
                null
            }
        } else {
            null
        }
        VaultOverview(
            vaultId = st?.vaultId ?: s.device.vaultId,
            state = st?.state,
            release = st?.release?.let { ReleaseInfoView(it.number, it.status, it.endsAt, it.newestActive, it.notice) },
            lastRelease = s.device.altState.releaseNumber,
            recoveryState = st?.recoveryState,
            provisional = vs?.provisional,
            devices = vs?.devices,
            connections = vs?.connections,
        )
    }

    override suspend fun changePin(pin: String, newPin: String) = guard {
        session().api.pinChange(pin, newPin)
    }

    override suspend fun deleteVault(pin: String, password: String) {
        guard { session().api.deleteVault(pin, password) }
        afterVaultDeleted()
    }

    /** A new vault needs a new setup code (its redeem makes the pending key, MEMBER-API 2.0.0): the welcome screen. */
    private suspend fun afterVaultDeleted() {
        dropSession(forget = true)
        io { saveLocal(LocalAccount()) }
        phaseFlow.value = AppPhase.SignedOut
    }

    override suspend fun recovery(): RecoveryView? = guard {
        val st = vaultStatus(session()) ?: return@guard null
        st.recoveryState?.let { RecoveryView(it, st.recoveryAvailableAt ?: "") }
    }

    override suspend fun attestationInfo(): AttestationInfo = io {
        val key = DeviceAttestationKey()
        val present = key.exists()
        val desc = if (present) {
            try {
                KeyDescription.parse(key.chain().first())
            } catch (_: AttestationException) {
                null
            } catch (_: KeystoreException) {
                null
            }
        } else {
            null
        }
        val level = if (present) runCatching { key.level().name }.getOrNull() else null
        val rot = desc?.rootOfTrust
        val alt = session?.device?.altState
        AttestationInfo(
            environment = env.name,
            keyPresent = present,
            keyLevel = level,
            strongBoxAvailable = AndroidKeys.hasStrongBox(app.packageManager),
            attestationVersion = desc?.attestationVersion,
            verifiedBoot = rot?.let { bootState(it.verifiedBootState) },
            deviceLocked = rot?.deviceLocked,
            bootKeyFingerprint = rot?.let { Bytes.hex(it.verifiedBootKey()).take(FINGERPRINT_HEX).chunked(GROUP).joinToString(" ") },
            lastRelease = alt?.releaseNumber ?: 0,
            lastReleaseFingerprint = alt?.release?.takeIf { it.isNotEmpty() }?.take(FINGERPRINT_HEX)?.chunked(GROUP)?.joinToString(" "),
        )
    }

    // --- CredentialRepository ---

    override suspend fun status(): CredentialStatus = guard {
        val s = session()
        val info = s.api.credentialVersion()
        val alarm = info.alarm?.let { a ->
            VaultJson.str(a, "alarm_id")?.let { CredentialAlarm(it, VaultJson.str(a, "state")
                ?: CredentialAlarm.STATE_FROZEN, VaultJson.str(a, "at")) }
        }
        alarmFlow.value = alarm
        val settings = s.api.settingsGet().settings
        val critical = try {
            countCritical(s)
        } catch (_: IOException) {
            null
        }
        CredentialStatus(
            exists = info.exists,
            version = info.version,
            keyFingerprint = info.key?.let { k -> k.take(KEY_FINGERPRINT_CHARS) },
            updatedAt = info.updatedAt,
            alarm = alarm,
            backup = (settings[KEY_BACKUP] as? JsonPrimitive)?.booleanOrNull ?: true,
            unlockTtlSeconds = (settings[KEY_TTL] as? JsonPrimitive)?.intOrNull ?: DEFAULT_TTL,
            criticalItems = critical,
        )
    }

    /** Counts the critical items, a page of at most [LIST_PAGE] at a time (§10.7: a vault holds at most 1,000). */
    private suspend fun countCritical(s: Session): Int {
        var n = 0
        var after: String? = null
        repeat(MAX_PAGES) {
            val page = s.api.itemList(sensitivity = "critical", after = after, limit = LIST_PAGE)
            n += page.items.size
            after = page.next ?: return n
        }
        return n
    }

    override suspend fun openUnlockWindow(password: String) = guard {
        val exp = session().api.credentialUnlock(password)
        windowFlow.value = try {
            Instant.parse(exp)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    override suspend fun closeUnlockWindow() = guard {
        session().api.credentialLock()
        windowFlow.value = null
    }

    override suspend fun changePassword(password: String, newPassword: String) = guard {
        session().api.credentialChangePassword(password, newPassword)
    }

    override suspend fun rotate(password: String) = guard {
        val s = session()
        s.api.credentialRotate(password)
        windowFlow.value = null
        refreshAlarm(s)
    }

    override suspend fun newCredential(pin: String, password: String, newPassword: String) = guard {
        val s = session()
        s.api.credentialResetHolder(pin, password, newPassword)
        windowFlow.value = null
        runCatching { refreshAlarm(s) }
        ownerCheck.onOpened() // a new credential starts the owner-check clock afresh (§3.6.1)
    }

    override suspend fun setBackup(on: Boolean) = guard {
        setSetting(session(), KEY_BACKUP, JsonPrimitive(on))
    }

    override suspend fun setUnlockTtl(seconds: Int) = guard {
        require(seconds in MIN_TTL..MAX_TTL)
        setSetting(session(), KEY_TTL, JsonPrimitive(seconds))
    }

    override suspend fun confirmAlarm(mine: Boolean) = guard {
        val a = alarmFlow.value ?: throw VaultFailure(FailureKind.NOT_FOUND)
        val state = session().api.credentialAlarmConfirm(a.alarmId, mine) ?: CredentialAlarm.STATE_ROTATION_REQUIRED
        alarmFlow.value = a.copy(state = state)
    }

    private suspend fun setSetting(s: Session, key: String, value: JsonPrimitive) {
        val current = s.api.settingsGet()
        if (current.settings[key] == value) return
        s.api.settingsSet(current.version, mapOf(key to value))
    }

    // --- MoveRepository: recovery on this (new) phone (§11.11) ---

    /** A device that holds nothing yet but carries a stale pairing, enrollment or refused registration starts afresh. */
    private suspend fun freshDeviceIfUnpaired() {
        val d = session().device
        if (d.deviceId == null && (d.hasVault || d.vaultId != null)) dropSession(forget = true)
    }

    override suspend fun recoveryStage(): RecoveryStage = guard {
        val s = session()
        val d = s.device
        when {
            !d.recovering -> RecoveryStage.CODE
            d.deviceId == null -> RecoveryStage.PIN
            vaultStatus(s)?.state == "unlocked" -> RecoveryStage.PASSWORD
            else -> RecoveryStage.PIN
        }
    }

    @Suppress("ReturnCount")
    override suspend fun registerRecovery(code: RecoveryCode): RecoveryRegistration = guard {
        if (!session().device.recovering) freshDeviceIfUnpaired()
        val s = session()
        // §11.11.7 (0.15.0): the claim gives this phone's key the right to register, and names the member.
        val claimed = try {
            gateway.claimRecovery(code.vaultId, code.recoveryId)
        } catch (e: MemberApiException) {
            return@guard when (e.code) {
                MemberApiException.RECOVERY_NOT_AVAILABLE -> RecoveryRegistration.Refused(CODE_NOT_AVAILABLE)
                MemberApiException.RECOVERY_UNAVAILABLE -> RecoveryRegistration.Refused(CODE_NO_BACKUP)
                MemberApiException.NOT_FOUND -> RecoveryRegistration.Refused(CODE_NO_RECOVERY)
                else -> throw e
            }
        }
        gateway.memberChanged(claimed.userGuid)
        io { saveLocal(LocalAccount(userGuid = claimed.userGuid, vaultId = code.vaultId, emailHint = claimed.emailHint)) }
        val r = try {
            s.alt.recoveryRegister(
                code.vaultId,
                build = { e -> s.device.prepareRecoveryRegister(local.userGuid, code, e, env.attester()) },
                open = { raw, rid -> s.device.openRecoveryResult(raw, rid) },
            )
        } catch (e: MemberApiException) {
            // 2.1.0: a recovery the enclave refused (no backup copy) is `unavailable`: the register is refused too.
            if (e.code == MemberApiException.RECOVERY_UNAVAILABLE) {
                s.device.dropRecoveryRegistration()
                return@guard RecoveryRegistration.Refused(CODE_NO_BACKUP)
            }
            if (e.code != MemberApiException.RECOVERY_NOT_AVAILABLE) throw e
            null
        }
        when {
            r == null -> {
                s.device.dropRecoveryRegistration()
                RecoveryRegistration.Refused(CODE_NOT_AVAILABLE)
            }
            r.ok -> {
                phaseFlow.value = AppPhase.Setup(SetupStage.RECOVERING)
                RecoveryRegistration.Registered(claimed.emailHint)
            }
            else -> {
                s.device.dropRecoveryRegistration()
                RecoveryRegistration.Refused(r.code ?: "retry")
            }
        }
    }

    override suspend fun recoveryPreflight(): PreflightInfo = preflight()

    override suspend fun recoveryUnlock(pin: String, approve: ReleaseView?): UnlockAttempt {
        val r = unlockOnce(pin, approve, cancelRecovery = false)
        if (r != UnlockAttempt.Success) return r
        return try {
            // §11.11.5 step 2: the first handshake (purpose app, ctx = recovery id), accepted without approval.
            guard {
                val d = session().device
                if (d.deviceId == null) d.completeEnrollment()
            }
            phaseFlow.value = AppPhase.Setup(SetupStage.RECOVERING)
            UnlockAttempt.Success
        } catch (e: VaultFailure) {
            UnlockAttempt.Failed(e.kind, e.code)
        }
    }

    override suspend fun recoveryCredentialBackup(): Boolean? = guard { session().device.recoveryCredentialBackup }

    override suspend fun abandonRecovery() {
        if (session?.device?.recovering == true) dropSession(forget = true)
    }

    override suspend fun recoverCredential(password: String): RecoverOutcome = guard {
        val s = session()
        val outcome = try {
            s.api.credentialRecover(password)
            RecoverOutcome.RECOVERED
        } catch (e: VaultOpException) {
            when (e.code) {
                // 0.16.0: a vault without a backup copy cannot be recovered; `credential_lost` is an older vault's word.
                "credential_lost", CODE_NO_BACKUP -> RecoverOutcome.NO_BACKUP
                "credential_required" -> RecoverOutcome.CREDENTIAL_REQUIRED
                else -> throw e
            }
        }
        if (outcome == RecoverOutcome.RECOVERED) phaseFlow.value = AppPhase.Setup(SetupStage.FINISHING)
        outcome
    }


    // --- MoveRepository: direct transfer (§6.7.1) ---

    /** When this phone sent its transfer hs.init: it waits for the approval until 10 minutes after it. */
    @Volatile
    private var transferStartedAt: Instant? = null

    /** The transfer this (old) phone opened last, to cancel it when a new one is asked for. */
    @Volatile
    private var openTransferId: String? = null

    override suspend fun transferIn(code: String): String = guard {
        val link = when (val p = InviteLinks.parseTransfer(code)) {
            is InviteLinks.TransferParsed.Ok -> p.link
            InviteLinks.TransferParsed.Expired -> throw VaultFailure(FailureKind.INVITE_EXPIRED, CODE_NOT_TRANSFER)
            InviteLinks.TransferParsed.NotATransfer, InviteLinks.TransferParsed.Invalid ->
                throw VaultFailure(FailureKind.INVITE_INVALID, CODE_NOT_TRANSFER)
        }
        freshDeviceIfUnpaired()
        val d = session().device
        if (d.deviceId != null) throw VaultFailure(FailureKind.VAULT_EXISTS)
        if (!local.transferIn) io { saveLocal(local.copy(transferIn = true)) }
        d.startTransfer(link, env.attester())
        transferStartedAt = Instant.now()
        try {
            // §6.7.1 step 2 (0.10.6): a dropped hs.init is never answered; stop after 60 s and say why it may be.
            withTimeout(MoveRepository.HS_RESP_WAIT_MS) { d.pairingSas.filterNotNull().first() }
        } catch (e: TimeoutCancellationException) {
            d.abandonTransfer()
            throw VaultFailure(FailureKind.NO_RESPONSE, MoveRepository.CODE_HS_UNANSWERED, cause = e)
        }
    }

    override suspend fun awaitTransferIn() = guard {
        val s = session()
        val started = transferStartedAt ?: Instant.now()
        val left = Duration.between(Instant.now(), started.plus(PAIRING_WINDOW)).coerceAtLeast(Duration.ofSeconds(1))
        try {
            s.device.awaitTransfer(left)
        } catch (e: TimeoutCancellationException) {
            s.device.abandonTransfer()
            throw e
        }
        transferStartedAt = null
        // §6.7.1 step 5: the blob the vault kept, confirmed, and a UTK pool.
        s.api.credentialTakeOver()
        phaseFlow.value = AppPhase.Setup(SetupStage.FINISHING)
    }

    override suspend fun abandonTransferIn() = guard {
        transferStartedAt = null
        session().device.abandonTransfer()
    }

    override suspend fun transferCreate(): TransferOfferView = guard {
        val api = session().api
        val offer = try {
            api.transferCreate()
        } catch (e: VaultOpException) {
            // One transfer at a time (`exists`): a code this phone showed before and left behind is cancelled first.
            val prev = openTransferId
            if (e.code != CODE_EXISTS || prev == null) throw e
            runCatching { api.transferReject(prev) }
            api.transferCreate()
        }
        openTransferId = offer.transferId
        val exp = offer.exp?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: InviteLinks.expiry(offer.link)
        TransferOfferView(offer.transferId, InviteLinks.qrPayload(offer.link), offer.link, exp)
    }

    override suspend fun awaitTransferPending(transferId: String, until: Instant): TransferPendingView? = guard {
        val left = Duration.between(Instant.now(), until)
        if (left.isNegative || left.isZero) return@guard null
        try {
            val p = session().api.awaitTransferPending(transferId, left)
            TransferPendingView(p.transferId, p.name, p.sas)
        } catch (_: TimeoutCancellationException) {
            null
        }
    }

    /**
     * §6.7.1 step 3: the approval. Its `{}` is not yet proof that the transfer completed (vettid-vault completes
     * it right after the response, `afterRespond`, and only then removes this app); the proof is the
     * `device.unlinked{transferred}` that follows (step 4), which wipes this phone. Waits for it a little.
     */
    override suspend fun transferApprove(transferId: String, pin: String, password: String): Boolean =
        holder.approveTransfer(UNLINK_WAIT_MS) {
            guard { session().api.transferApprove(transferId, pin, password) }
            openTransferId = null
        }

    override suspend fun transferReject(transferId: String) = guard {
        session().api.transferReject(transferId)
        if (openTransferId == transferId) openTransferId = null
    }

    // --- a replaced phone erases itself (owner decision, 2026-10-05) ---

    /**
     * Runs only from [holder]: after an authenticated proof ([HolderPolicy]), when the vault already made another
     * phone its app, so nothing here is the only copy of anything; or when the member confirmed
     * [eraseThisPhone] on an unlock the vault did not recognise (a phone that missed its `device.unlinked`
     * because it was offline longer than the relay keeps messages). Everything goes: the session and the device
     * state, the relay mailbox, the member session, the social state, the account record, the Keystore keys,
     * the files, the preferences and the notifications ([LocalWipe]); the app then shows the welcome screen.
     */
    private suspend fun wipeThisPhone() {
        generation++
        wiper.begin() // crash-safe: a process that dies from here on finishes the wipe at its next start
        mutex.withLock {
            observer?.cancel()
            observer = null
            val d = session?.device
            session = null
            if (d != null) {
                // Best effort, bounded: the relay forgets the mailbox; the wipe does not depend on it.
                withTimeoutOrNull(BEST_EFFORT_MS) { runCatching { io { d.deleteMailbox() } } }
                runCatching { d.forget() }
            }
        }
        social.clear()
        ownerCheck.clear()
        canary.removeCanaryManifest()
        transferStartedAt = null
        openTransferId = null
        alarmFlow.value = null
        windowFlow.value = null
        wiper.resetMemory()
        io { if (wiper.erase()) wiper.finish() }
        local = LocalAccount()
        accountFlow.value = null
        generation++
        phaseFlow.value = AppPhase.SignedOut
    }

    // --- helpers ---

    /** The published manifest's served document (for the canary's installation check), or null. */
    private suspend fun fetchPublishedManifest(): ByteArray? = io {
        http.newCall(Request.Builder().url(env.endpoints.manifestUrl).get().build()).execute().use { r ->
            if (r.code != HTTP_OK) return@use null
            r.body.source().use { src ->
                src.request(ReleaseManifest.MAX_SERVED.toLong() + 1)
                src.buffer.readByteArray(minOf(src.buffer.size, ReleaseManifest.MAX_SERVED.toLong() + 1))
            }
        }
    }

    private fun view(r: Release) = ReleaseView(r.number, r.pcr0, r.status.wire, r.endsAt, r.notes)

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    /** Runs [block] on the IO dispatcher and turns every failure into a [VaultFailure]. */
    private suspend fun <T> guard(block: suspend () -> T): T = try {
        vaultGuard(block)
    } catch (e: VaultFailure) {
        // MEMBER-API 1.2.0: a refusal while paused also shows the banner (status may not have been read yet).
        if (e.kind == FailureKind.SERVICE_PAUSED) pausedFlow.value = true
        throw e
    }

    /** `GET /api/vault/status`, following its top-level `service` (MEMBER-API 1.2.0) for the banner. */
    private suspend fun vaultStatus(s: Session): com.vettid.core.altchan.VaultStatus? {
        val a = s.member.vaultStatusAnswer()
        pausedFlow.value = a.servicePaused
        deletionFlow.value = a.vault?.deletion?.let { d ->
            runCatching { Instant.parse(d.deletesAt) }.getOrNull()?.let { DeletionView(it, d.state == "executing", d.deletionId) }
        }
        return a.vault
    }

    override suspend fun cancelDeletion(): Boolean = guard {
        val s = session()
        val id = deletionFlow.value?.deletionId ?: throw VaultFailure(FailureKind.NOT_SUPPORTED)
        val cancelled = s.member.deletionCancel(id)
        runCatching { vaultStatus(s) }
        cancelled
    }

    companion object {
        private const val DEVICE_FILE = "vault-device.bin"
        private const val SOCIAL_FILE = "social.bin"
        private const val CANARY_FILE = "canary-manifest.bin"
        private const val OWNER_CHECK_FILE = "owner-check.bin"
        private const val HTTP_OK = 200
        private const val LEGACY_SESSION_FILE = "member-session.bin"
        private const val SYNC_ACCOUNT_CHANGED = "account.changed"
        private const val CODE_NO_RECOVERY = "no_recovery"
        private const val UPDATE_MOVED = "moved"
        private const val KEY_BACKUP = "credential.backup"
        private const val KEY_TTL = "credential.unlock_ttl_seconds"
        private const val DEFAULT_TTL = 300
        const val MIN_TTL = 30
        const val MAX_TTL = 3600
        private const val LOCK_TIMEOUT_MS = 10_000L
        private const val LIST_PAGE = 500
        private const val MAX_PAGES = 4
        private const val FINGERPRINT_HEX = 16
        private const val CODE_NO_BACKUP = "no_backup"
        private const val CODE_NOT_AVAILABLE = "not_available"
        private const val CODE_NOT_TRANSFER = "not_transfer"
        private const val CODE_EXISTS = "exists"

        /** The pairing window: 10 minutes after `hs.init` (§6.7, 0.10.4). */
        private val PAIRING_WINDOW: Duration = Duration.ofMinutes(10)

        /** How long the old phone's approval waits for `device.unlinked{transferred}` (§6.7.1 step 4). */
        private const val UNLINK_WAIT_MS = 30_000L

        /** The bound of the best-effort network calls of a wipe (relay mailbox, member sign-out). */
        private const val BEST_EFFORT_MS = 5_000L
        private const val KEY_FINGERPRINT_CHARS = 12
        private const val GROUP = 4
        private val json = Json { ignoreUnknownKeys = true }

        private fun bootState(v: Int) = when (v) {
            RootOfTrust.VERIFIED -> "Verified"
            RootOfTrust.SELF_SIGNED -> "SelfSigned"
            RootOfTrust.UNVERIFIED -> "Unverified"
            else -> "Failed"
        }

        fun enrollFailure(code: String?): FailureKind = when (code) {
            "vault_exists" -> FailureKind.VAULT_EXISTS
            "attestation" -> FailureKind.ATTESTATION
            "manifest", "release_key" -> FailureKind.MANIFEST
            else -> FailureKind.OTHER
        }

        fun memberFailure(e: MemberApiException): FailureKind = when (e.code) {
            MemberApiException.UNAUTHORIZED -> FailureKind.UNAUTHORIZED
            MemberApiException.INVALID_CODE -> FailureKind.SETUP_CODE_INVALID
            MemberApiException.TERMS_REQUIRED -> FailureKind.TERMS_REQUIRED
            MemberApiException.RATE_LIMITED -> FailureKind.RATE_LIMITED
            MemberApiException.VAULT_UNAVAILABLE -> if (e.servicePaused) FailureKind.SERVICE_PAUSED else FailureKind.VAULT_UNAVAILABLE
            MemberApiException.RELEASE_STARTING -> FailureKind.VAULT_UNAVAILABLE
            MemberApiException.RELEASE_UNAVAILABLE -> FailureKind.RELEASE_ENDED
            MemberApiException.NOT_FOUND -> FailureKind.NOT_FOUND
            MemberApiException.MANIFEST_UNAVAILABLE -> FailureKind.MANIFEST
            else -> FailureKind.OTHER
        }

        @Suppress("CyclomaticComplexMethod") // one branch per error code
        fun opFailure(code: String): FailureKind = when (code) {
            "bad_pin" -> FailureKind.BAD_PIN
            "bad_password" -> FailureKind.BAD_PASSWORD
            "backoff" -> FailureKind.BACKOFF
            "credential_frozen" -> FailureKind.CREDENTIAL_FROZEN
            "rotation_required" -> FailureKind.ROTATION_REQUIRED
            "not_found" -> FailureKind.NOT_FOUND
            "unsupported_type" -> FailureKind.NOT_SUPPORTED
            "connection_unavailable" -> FailureKind.CONNECTION_UNAVAILABLE
            "claim_unavailable", "accept_failed" -> FailureKind.INVITE_UNAVAILABLE
            "blocked" -> FailureKind.BLOCKED
            "credential_locked" -> FailureKind.CREDENTIAL_LOCKED
            "conflict" -> FailureKind.CONFLICT
            "limit" -> FailureKind.LIMIT
            "ttl_not_allowed" -> FailureKind.NOT_SUPPORTED
            "stale_credential", "utk_invalid" -> FailureKind.NO_RESPONSE
            "owner_check_required" -> FailureKind.OWNER_CHECK_REQUIRED
            else -> FailureKind.OTHER
        }
    }
}

/** Whether RFC 3339 [a] is later than [b] (a snapshot without a parsable `as_of` replaces one only when none is stored). */
private fun newer(a: String?, b: String?): Boolean {
    val ia = a?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val ib = b?.let { runCatching { Instant.parse(it) }.getOrNull() }
    return when {
        ia == null -> b == null
        ib == null -> true
        else -> ia.isAfter(ib)
    }
}

/** The snapshot for display (§11.13). */
private fun com.vettid.core.vault.AccountSnapshot.toInfo(fallbackHint: String): AccountInfo {
    fun t(s: String?): Instant? = s?.let { runCatching { Instant.parse(it) }.getOrNull() }
    return AccountInfo(
        emailHint = emailHint ?: fallbackHint,
        state = state ?: "",
        accountStatus = accountStatus,
        deletesAt = t(deletesAt),
        termsNeedAcceptance = terms?.needsAcceptance ?: false,
        subscription = subscription?.let { SubscriptionInfo(it.typeName, it.status, it.paid, t(it.expiresAt)) },
        votingRights = votingRights,
        asOf = t(asOf),
    )
}
