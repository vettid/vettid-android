package com.vettid.core.data.vault

import android.content.Context
import com.vettid.core.altchan.Slot
import com.vettid.core.altchan.AltChannelFlow
import com.vettid.core.altchan.AltTrust
import com.vettid.core.altchan.Approval
import com.vettid.core.altchan.Claimed
import com.vettid.core.altchan.MemberApiClient
import com.vettid.core.altchan.MemberApiException
import com.vettid.core.altchan.RecoveryCode
import com.vettid.core.altchan.TypedRecoveryCode
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
import com.vettid.core.data.items.ItemsManager
import com.vettid.core.data.items.VaultItemsOps
import com.vettid.core.data.items.SharingManager
import com.vettid.core.data.items.VaultSharingOps
import com.vettid.core.data.env.AppEnvironment
import com.vettid.core.data.feed.FeedManager
import com.vettid.core.data.feed.VaultFeedOps
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
import com.vettid.core.vault.callEnd
import com.vettid.core.vault.VaultOpException
import com.vettid.core.vault.VaultStateException
import com.vettid.core.vault.ChangeMarker
import com.vettid.core.vault.ChangeUnansweredException
import com.vettid.core.vault.KeptChange
import com.vettid.core.vault.changeResend
import com.vettid.core.vault.changeSettle
import com.vettid.core.vault.credentialChangePassword
import com.vettid.core.vault.credentialTopUpUtks
import com.vettid.core.vault.credentialVersionAt
import com.vettid.core.vault.setCredentialGate
import com.vettid.core.data.items.ListLoad
import com.vettid.core.vault.pinChange
import com.vettid.core.vault.pushConfirm
import com.vettid.core.vault.secretRenew
import com.vettid.core.vault.transferApprove
import com.vettid.core.vault.pushRegister
import com.vettid.core.vault.pushUnregister
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
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
import kotlinx.serialization.json.JsonObject
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
import java.util.concurrent.atomic.AtomicInteger

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
) : AccountRepository, VaultRepository, CredentialRepository, MoveRepository, BackgroundVault, PushVault {
    private val app = context.applicationContext
    private val http = env.http(baseHttp)
    private val gateway: MemberGateway = env.memberGateway(app, http) { session?.device?.vaultId ?: local.vaultId.ifEmpty { null } }
    private val accountFile = KeystoreFileStore(File(app.noBackupFilesDir, "account.bin"), "account")
    private val mutex = Mutex()
    private var session: Session? = null
    private var observer: Job? = null
    /** The account record could not be read or understood: nothing is assumed about it (A6 audit P4; [evaluate]). */
    @Volatile
    private var localUnreadable = false

    /** The account record, written only through updates under its lock, against a wipe on another thread ([AccountRecord]). */
    private val record = AccountRecord(
        loadLocal(),
        readable = !localUnreadable, // read just above, by loadLocal
        persist = { a ->
            accountFile.save(json.encodeToString(LocalAccount.serializer(), a).toByteArray())
            accountFlow.value = a.toInfo()
        },
        // A wipe's marker left over at the start (the start's resume could not finish it): shut until a wipe completes.
        eraseFailed = wiper.pending,
    )

    /**
     * The lock's record, in a file of its own ([LockRecordFile]): durable whatever the state of the account record (one
     * that cannot be read, or shut after a wipe that did not finish). Before it, the record was kept in the account record.
     */
    private val lockFile = LockRecordFile(
        File(app.noBackupFilesDir, LOCK_FILE),
        legacy = { LockRecord(local.lockAskedAtMs, local.relayLockUntilMs) },
        migrated = { local.lockFileInUse },
        onFirstSave = { record.markLockFileInUse() },
        recordUnreadable = { localUnreadable },
    )

    private val local: LocalAccount get() = record.value

    // --- a lost answer to a PIN or password change (VAULT-MESSAGING 0.44.0 §11.14.9; ANDROID-PLAN 0.1.45) ---

    /** The change request as it was sent (the same inner id and body), in memory only, until the change is decided. */
    @Volatile
    private var keptChange: KeptChange? = null

    /** Decides a change whose answer was lost; the gate of every other credential operation ([VaultApi.setCredentialGate]). */
    private val changes = ChangeResolver(
        scope,
        object : ChangeResolver.Ops {
            override fun marker(): UnresolvedChange? = local.unresolvedChange

            override suspend fun resend(m: UnresolvedChange): ChangeResolver.Answer? {
                val k = keptChange?.takeIf { it.innerId == m.innerId } ?: return null
                val api = session().openApi
                return api.changeResend(k)?.let { r ->
                    try {
                        api.changeSettle(k, r)
                        ChangeResolver.Answer.Ok
                    } catch (e: VaultOpException) {
                        ChangeResolver.Answer.Refused(opFailure(e.code), stale = e.code == CODE_STALE)
                    }
                }
            }

            override suspend fun version(): ChangeResolver.Version {
                val (info, ts) = session().openApi.credentialVersionAt()
                // A clone alarm follows its own path (§3.5.9): the Credential screen shows it.
                info.alarm?.let { a ->
                    VaultJson.str(a, "alarm_id")?.let { alarmFlow.value = CredentialAlarm(it, VaultJson.str(a, "state")
                        ?: CredentialAlarm.STATE_FROZEN, VaultJson.str(a, "at")) }
                }
                return ChangeResolver.Version(info.exists, info.version, info.alarm != null, ts)
            }

            override suspend fun fetch() {
                val api = session().openApi
                api.credentialFetch()
                api.credentialTopUpUtks()
            }

            override fun confirm(m: UnresolvedChange): Boolean = confirmChange(m)

            override fun clear(m: UnresolvedChange) = clearChange(m)
        },
    )

    private val pinChangeUnresolvedFlow = MutableStateFlow(false)

    /**
     * (§11.14.9 step 5) A PIN change is unresolved and the phone's current record is still the one it was sent from: the
     * unlock asks for the previous PIN first, and for the new one only after `device_secret`.
     */
    private fun pinChangeUndecided(a: LocalAccount = local): Boolean {
        val m = a.unresolvedChange ?: return false
        return m.kind == ChangeKind.PIN && a.phoneEpoch == m.fromEpoch
    }

    private fun refreshChangeFlows() {
        pinChangeUnresolvedFlow.value = pinChangeUndecided()
    }

    /**
     * The previous PIN got `device_secret` while a PIN change is unresolved: the next unlock tries the PIN typed (the new
     * one) with the candidate only. Kept until an answer to that try, and shown by the unlock screen ([newPinAsked]).
     */
    private val newPinAskedFlow = MutableStateFlow(false)
    private var newPinStep: Boolean
        get() = newPinAskedFlow.value
        set(v) {
            newPinAskedFlow.value = v
        }

    /**
     * §11.14.9 step 1: the unresolved change, stored before the send. A write that is dropped or fails stops the
     * operation before anything is sent ([FailureKind.LOCAL_STATE]); nothing of it stays in memory.
     */
    private fun storeChange(since: Int, m: ChangeMarker, fromEpoch: Long) {
        val u = UnresolvedChange(m.type, m.innerId, m.sentVersion, m.exp.toEpochMilli(), fromEpoch)
        val before = record.value
        val ok = try {
            record.update(since) { it.copy(unresolvedChange = u) }
        } catch (_: KeystoreException) {
            false
        } catch (_: IOException) {
            false
        }
        if (!ok) {
            if (record.value.unresolvedChange == u) record.reload(before, readable = true)
            throw VaultFailure(FailureKind.LOCAL_STATE, CODE_NOT_STORED)
        }
        refreshChangeFlows()
    }

    /**
     * The change applied (its answer, or the check): a PIN change's candidate becomes current (the next epoch, when the
     * current record is still the one it was sent from) and the unresolved change goes, as one write. False when the write
     * failed: the change is still shown as done, and the next check or unlock confirms it.
     */
    private fun confirmChange(m: UnresolvedChange): Boolean {
        keptChange?.takeIf { it.innerId == m.innerId }?.let {
            it.forget()
            keptChange = null
        }
        val ok = try {
            record.update(RecordStore.UNBOUND) { cur ->
                // UNBOUND: the change belongs to whatever record holds its marker; a reset or wipe leaves none.
                if (cur.unresolvedChange?.innerId != m.innerId) return@update null
                val epoch = if (m.kind == ChangeKind.PIN && cur.phoneEpoch == m.fromEpoch) m.fromEpoch + 1 else cur.phoneEpoch
                cur.copy(phoneEpoch = epoch, unresolvedChange = null)
            }
        } catch (_: KeystoreException) {
            false
        } catch (_: IOException) {
            false
        }
        // ANDROID-PLAN "Transfer": after a PIN or password change the app offers to rotate the vault's keys.
        if (ok) record.setRotateOffer(true, record.recordGeneration)
        newPinStep = false
        refreshChangeFlows()
        return ok
    }

    /** The change is decided as not applied, refused or ended: its marker goes; the candidate stays (§11.14.1 rules 3–5). */
    private fun clearChange(m: UnresolvedChange) {
        keptChange?.takeIf { it.innerId == m.innerId }?.let {
            it.forget()
            keptChange = null
        }
        try {
            record.update(RecordStore.UNBOUND) { cur ->
                if (cur.unresolvedChange?.innerId == m.innerId) cur.copy(unresolvedChange = null) else null
            }
        } catch (_: KeystoreException) {
            // Kept on the phone: the next check decides it again.
        } catch (_: IOException) {
            // As above.
        }
        newPinStep = false
        refreshChangeFlows()
    }

    /**
     * Sends a PIN or password change through [send], which stores the marker first ([storeChange]). The answer decides
     * it (success, or the refusal thrown as it is, the marker cleared). No answer is not a failure: the request is kept
     * in memory, the check runs, and [FailureKind.CHANGE_CHECKING] tells the screen to keep the entries and follow
     * [changeCheck]. A `stale_credential` is not retried: the change did not apply.
     */
    @Suppress("ThrowsCount")
    private suspend fun sendChange(since: Int, kind: ChangeKind, fromEpoch: Long, send: suspend (suspend (ChangeMarker) -> Unit) -> Unit) {
        var marker: UnresolvedChange? = null
        try {
            send { m ->
                storeChange(since, m, fromEpoch)
                marker = local.unresolvedChange
            }
        } catch (e: ChangeUnansweredException) {
            keptChange = e.kept
            changes.publish(ChangeCheck.Checking(kind))
            changes.kick()
            throw VaultFailure(FailureKind.CHANGE_CHECKING, CODE_CHANGE_CHECKING, cause = e)
        } catch (e: VaultOpException) {
            marker?.let { clearChange(it) }
            if (e.code == CODE_STALE) {
                changes.publish(ChangeCheck.NotApplied(kind))
                throw VaultFailure(FailureKind.CHANGE_CHECKING, CODE_CHANGE_CHECKING, cause = e)
            }
            throw e
        }
        marker?.let { confirmChange(it) }
        changes.publish(null)
    }

    /** Bumped by every wipe, reset and lock ([RecordStore.generation]): a phase computed before it is not published after it. */
    private val generation: Int get() = record.generation

    /** The old vault's device, detached by a wipe ([WipeRun]) and kept until it is forgotten (a retried wipe too). */
    @Volatile
    private var wipeDevice: VaultDevice? = null

    /** How a vault comes to this phone, each after an unfinished wipe is finished or refused while it is not ([Enrolments]). */
    private val enrolments = Enrolments(
        pending = { record.eraseFailed || wiper.pending },
        finish = { holder.wipeNow() },
        // The enrolments themselves, here and nowhere else: reachable only through [enrolments], so none goes round its check.
        ops = object : Enrolments.Ops<TypedRecoveryCode> {
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
                io { resetLocal(LocalAccount(userGuid = r.userGuid, vaultId = r.vaultId, emailHint = r.emailHint)) }
                phaseFlow.value = AppPhase.Setup(SetupStage.NEW_VAULT)
                return r.emailHint
            }

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
                val rec = record.recordGeneration
                if (!local.transferIn) io { updateLocal(rec) { it.copy(transferIn = true) } }
                // Release R (§11.14.10): this phone's key and its secret of epoch 1 go in the hs.init. The vault id is not
                // known yet: the key and the secret are made for "" (as vettid-vault's reference client does).
                // Stored before the hs.init is sent; a retry reuses the key. §11.10.9: only when the bundle lists `recovery_r`.
                d.startTransfer(link, env.attester()) { caps ->
                    val r = choosePath(caps, makesPhoneKey = true, rec) == ReleasePath.Path.R
                    if (r) phoneSecrets.secret(keepPhoneKey("", rec)) else null
                }
                transferStartedAt = Instant.now()
                try {
                    // §6.7.1 step 2 (0.10.6): a dropped hs.init is never answered; stop after 60 s and say why it may be.
                    withTimeout(MoveRepository.HS_RESP_WAIT_MS) { d.pairingSas.filterNotNull().first() }
                } catch (e: TimeoutCancellationException) {
                    d.abandonTransfer()
                    throw VaultFailure(FailureKind.NO_RESPONSE, MoveRepository.CODE_HS_UNANSWERED, cause = e)
                }
            }

            override suspend fun registerRecovery(code: RecoveryCode): RecoveryRegistration = guard {
                if (!session().device.recovering) freshDeviceIfUnpaired()
                // §11.11.7 (0.15.0): the claim gives this phone's key the right to register, and names the member.
                val claimed = try {
                    gateway.claimRecovery(code.vaultId, code.recoveryId)
                } catch (e: MemberApiException) {
                    return@guard claimRefusal(e)
                }
                registerClaimed(code, claimed)
            }

            override suspend fun registerTypedRecovery(typed: TypedRecoveryCode): RecoveryRegistration = guard {
                if (!session().device.recovering) freshDeviceIfUnpaired()
                val claimed = try {
                    gateway.claimRecoveryByLocator(typed.locator)
                } catch (e: MemberApiException) {
                    return@guard claimRefusal(e)
                }
                val code = typed.recoveryCode(claimed, apiOrigin)
                when (val r = registerClaimed(code, claimed)) {
                    is RecoveryRegistration.Refused -> r.copy(recoveryId = code.recoveryId)
                    is RecoveryRegistration.Registered -> r
                }
            }

            /**
             * After the claim, however the code was read: the sealed `vault.recovery.register` (§11.11.3). Here, in the
             * enrolments' object, so that the reset to the claimed vault's record is reached only through [enrolments]' check.
             */
            @Suppress("ReturnCount")
            private suspend fun registerClaimed(code: RecoveryCode, claimed: Claimed): RecoveryRegistration {
                val s = session()
                gateway.memberChanged(claimed.userGuid)
                io { resetLocal(LocalAccount(userGuid = claimed.userGuid, vaultId = code.vaultId, emailHint = claimed.emailHint)) }
                // The claim's own record, just made: what the registration writes belongs to it.
                val rec = record.recordGeneration
                val r = try {
                    s.alt.recoveryRegister(
                        code.vaultId,
                        build = { e -> buildRegister(s, code, e, rec) },
                        open = { raw, rid -> s.device.openRecoveryResult(raw, rid) },
                    )
                } catch (e: VaultFailure) {
                    if (e.code != CODE_NOT_OFFERED) throw e
                    return RecoveryRegistration.Refused(CODE_NOT_OFFERED)
                } catch (e: MemberApiException) {
                    // 2.1.0: a recovery the enclave refused (no backup copy) is `unavailable`: the register is refused too.
                    if (e.code == MemberApiException.RECOVERY_UNAVAILABLE) {
                        s.device.dropRecoveryRegistration()
                        return RecoveryRegistration.Refused(CODE_NO_BACKUP)
                    }
                    if (e.code != MemberApiException.RECOVERY_NOT_AVAILABLE) throw e
                    null
                }
                return when {
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
        },
    )

    /** Proofs that this phone was replaced, and the wipe they start. */
    val holder = HolderWatch(scope) { wipeThisPhone() }

    private val phaseFlow = MutableStateFlow<AppPhase>(AppPhase.Starting)

    /** (Release R, §11.14.10) When this old phone approved a transfer it still keeps the keys for. */
    private val transferKeptFlow = MutableStateFlow<Instant?>(null)

    /** (Release R) The erase at the end of a kept transfer's 7 days, while the process lives that long. */
    @Volatile
    private var keptTransferTimer: Job? = null

    /** (Release R, §11.14.10) This new phone has a move to finish. */
    private val finishMoveFlow = MutableStateFlow(local.finishMovePending)

    /**
     * "Finish the move" (§11.14.10 step 4), run in this manager's scope ([FinishMoveRunner]): the lock is the member's
     * lock as everywhere (pending, durably, until confirmed or superseded by the unlock that follows); the screen is held
     * ([finishingMove]) for a bounded time; the unlock completes whatever becomes of the screen that asked, and its
     * failure is kept for the unlock screen ([finishMoveFailure]). Declared before the init blocks that start work on
     * [scope].
     */
    private val finishMoveRunner = FinishMoveRunner(
        scope,
        FINISH_MOVE_HOLD_MS,
        lock = { lock() },
        unlock = { pin -> unlock(pin, null, false) },
        generation = { generation },
        signedOut = { phaseFlow.value == AppPhase.SignedOut },
        // ANDROID-PLAN "Transfer": after a transfer the app offers to rotate the vault's keys. Not after a wipe that
        // overtook the unlock: the offer would land in the fresh record, for a vault enrolled here later.
        onOpened = { gen -> offerRotationAt(gen) },
    )

    /** (Release R, §11.14.10) This old phone took the vault back after a transfer that did not finish. */
    private val movedBackFlow = MutableStateFlow(false)

    /** (Release R) At the end of the 7 days the vault confirmed this phone is still its app: the move did not happen. */
    private val moveNotDoneFlow = MutableStateFlow(false)

    /** (Release R) This old phone removed its keys at the end of a kept transfer: said once on the welcome screen. */
    private val movedAwayFlow = MutableStateFlow(wiper.movedAway)

    /** The next wipe is that of the end of a kept transfer (the new phone finished the move, or 7 days passed). */
    @Volatile
    private var wipeMovedAway = false

    /** One end-of-keep check at a time (a start, a return to the foreground and the timer may meet). */
    private val keptEndLock = Mutex()
    private val accountFlow = MutableStateFlow(local.toInfo())
    private val alarmFlow = MutableStateFlow<CredentialAlarm?>(null)
    private val windowFlow = MutableStateFlow<Instant?>(null)
    private val pausedFlow = MutableStateFlow(false)
    private val deletionFlow = MutableStateFlow<DeletionView?>(null)
    private val lockedElsewhereFlow = MutableStateFlow(false)

    /** The member locked the vault on this phone (until the next unlock): no "Your vault is locked" notification. */
    @Volatile
    private var lockedHere = false

    /** The relay connection is closed by the notification service while the vault is locked ([pauseRelay]). */
    @Volatile
    private var relayPaused = false

    /** [pauseRelay] was asked for while a lock was pending: done when the lock is confirmed. */
    @Volatile
    private var pauseWanted = false

    /**
     * The vault locked without the member locking it on this phone (`vault.locking` from another device or by
     * itself, an unsignalled lock found by a status read): ANDROID-PLAN 0.1.23, "Your vault is locked".
     */
    val lockedElsewhere: StateFlow<Boolean> = lockedElsewhereFlow.asStateFlow()
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

    /** `vault.locking` notices from before the vault last opened are dropped ([LockNotices]). */
    private val lockNotices = LockNotices(opened = local.lastOpenedAtMs?.let { Instant.ofEpochMilli(it) })

    /** `feed.event`s collected before the phase is known (a process started by a push wake), until it is ([HeldBackEvents]). */
    private val heldBack = HeldBackEvents()

    /** Held-back events being handed to the feed (a push wake waits for them). */
    private val replaying = AtomicInteger(0)

    /** Push wakes being handled ([collectForWake]). */
    private val waking = AtomicInteger(0)

    private val tokenNeededFlow =
        MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * This device's session with the vault. Its typed client is reachable in two ways only, and both ask the lock's
     * rules ([LockGuard.admits]): [openApi] for every operation on the open vault, refused while a lock is pending;
     * [lockTimeApi] for the two things that are sent to a vault that counts as locked ([LockTimeUse]).
     */
    private class Session(
        val device: VaultDevice,
        private val client: VaultApi,
        val member: MemberApiClient,
        val alt: AltChannelFlow,
        private val guard: () -> LockGuard,
    ) {
        /** The vault client for an operation on the open vault. Throws `lock_pending` while a lock is pending. */
        val openApi: VaultApi
            get() = if (guard().admits()) client else throw VaultFailure(FailureKind.OTHER, CODE_LOCK_PENDING)

        /** The vault client for the lock itself or a pending `call.end`: also while a lock is pending. */
        fun lockTimeApi(use: LockTimeUse): VaultApi =
            if (guard().admits(use)) client else throw VaultFailure(FailureKind.OTHER, CODE_LOCK_PENDING)
    }

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
        api = { openApi() },
        credential = this,
        store = KeystoreFileStore(File(app.noBackupFilesDir, SOCIAL_FILE), "social"),
        itemNames = {
            items.items.value.associate { i ->
                i.itemId to com.vettid.core.data.social.ShareItem(i.itemId, i.name, i.category, i.sensitivity.wire)
            }
        },
        feedRead = { kinds, ref, connectionId -> feed.markReadWhere(kinds, ref, connectionId) },
    )

    /**
     * Photo attachments (VAULT-MESSAGING 0.32.0 §10.5.1, ANDROID-PLAN 0.1.39): thumbnails, photos and pending uploads
     * in memory only, cleared whenever the vault is not open, held or due, and at a wipe.
     */
    val attachments = com.vettid.core.data.social.AttachmentManager(
        com.vettid.core.data.social.VaultAttachmentOps { openApi() },
        onSent = { social.addSent(it) },
    )

    /**
     * The vault's feed (ANDROID-PLAN 0.1.23, VAULT-MESSAGING §10.9): the Notifications screen and the bell. Read
     * while the vault is open and not held; in memory only.
     */
    val feed = FeedManager(
        scope,
        ops = { VaultFeedOps(openApi()) },
        gated = { ownerCheck.ownerCheck.value?.gated(Instant.now()) == true },
    )

    /**
     * The member's shared profile and the account's names (VAULT-MESSAGING 0.18.0 §10.8): the display name, and the
     * name change, which counts as an owner check for its PIN and password (§3.6.4).
     */
    val profile = ProfileManager(
        ops = { VaultProfileOps(openApi()) },
        mark = { record.recordGeneration },
        onRequest = { r, since -> io { updateLocal(since) { it.copy(nameRequest = r.toWire()) } } },
        checksLeft = {
            ownerCheck.refreshOwnerCheck()
            ownerCheck.ownerCheck.value?.checksLeft
        },
    )

    /** The member's audit log (VAULT-MESSAGING §10.9), read-only: the History screen. */
    val history = HistoryManager(
        ops = { VaultAuditOps(openApi()) },
        // The rows' and the entry page's device names (ANDROID-PLAN 0.1.27), as the export resolves them.
        devices = { VaultAuditExportOps.devicesOf(session().openApi.deviceList()) },
        self = { session?.device?.deviceId },
    )

    /** The History export (VAULT-MESSAGING 0.22.0 §10.9): `audit.export`, then `audit.list` below its `upto_seq`. */
    val historyExport = HistoryExportManager(ops = { VaultAuditExportOps(openApi()) }, audit = { VaultAuditOps(openApi()) })

    /** The member's items (VAULT-MESSAGING §10.7): the Vault screens. The list is read while the vault is open. */
    val items = ItemsManager(scope, ops = { VaultItemsOps(openApi()) })

    /** Tags, share rules and grants (VAULT-MESSAGING §10.8, §10.12): a tag change re-reads the items' tags. */
    val sharing = SharingManager(ops = { VaultSharingOps(openApi()) }, onItemsChanged = { items.refresh() })

    /** The daily owner check (VAULT-MESSAGING 0.13.0 §3.6): its state, the check, the interval and the hold. */
    val ownerCheck: OwnerCheckManager = KeystoreFileStore(File(app.noBackupFilesDir, OWNER_CHECK_FILE), "owner-check").let { f ->
        OwnerCheckManager(
            scope,
            ops = { VaultOwnerCheckOps(openApi()) },
            load = { runCatching { f.load() }.getOrNull() },
            persist = { f.save(it) },
            // §3.6.3: nothing held back is replayed as events; the lists are read again (the feed: below, in init).
            onPassed = { social.refreshAllQuietly() },
            readNotices = { ids -> ids.forEach { feed.setStatus(it, FeedManager.STATUS_READ) } },
            mark = { record.recordGeneration },
            onRecoveryStatus = { r, since -> noteRecoveryStatus(r, since) },
        )
    }

    /** The release update the shell offers, and the one-step update (ANDROID-PLAN 0.1.19). */
    val releaseUpdate = ReleaseUpdateManager(scope, UpdateOps())

    init {
        scope.launch { runCatching { canary.load() } }
        // Before MEMBER-API 2.0.0 the app kept the account site's session cookies here; it never signs in now.
        scope.launch { io { runCatching { File(app.noBackupFilesDir, LEGACY_SESSION_FILE).delete() } } }
        scope.launch {
            // Lists are re-read whenever the vault opens (unlock, end of onboarding, app start while unlocked).
            var previous: AppPhase = AppPhase.Starting
            phaseFlow.collect {
                // Open, then locked, and not by the member here: "Your vault is locked" (not at a cold start).
                if (it == AppPhase.Locked && previous == AppPhase.Unlocked && !lockedHere) lockedElsewhereFlow.value = true
                previous = it
                replayHeldBack(heldBack.release(it))
                if (it == AppPhase.Unlocked) {
                    lockedHere = false
                    lockedElsewhereFlow.value = false
                    resumeRelay() // after an unlock the notification service's connection comes back
                    social.refreshAllQuietly()
                    launch { attachments.probe() } // §10.5.1 "Compatibility": once after each unlock
                    items.refreshQuietly()
                    feed.open()
                    launch { refreshAccount() }
                    launch { runCatching { profile.refreshProfile() } }
                    // The proactive release notice (owner decision 2026-10-09): the manifest, read after every unlock.
                    launch { releaseUpdate.refreshOffer() }
                } else if (it != AppPhase.Starting) {
                    // Locked or signed out: no item metadata and no feed item is kept outside an open vault, and
                    // no connection or message in memory either.
                    social.forgetOpenVault()
                    attachments.clear()
                    items.clear()
                    sharing.clear()
                    feed.clear()
                }
            }
        }
        // The feed's owner-check items are the shell's notices (one source, ANDROID-PLAN 0.1.23, 6).
        scope.launch { feed.items.collect { ownerCheck.onFeed(it) } }
        // Held or due (§3.6.3): no cached feed content is shown; after the check (here or on another device), a full read.
        scope.launch {
            var held = false
            ownerCheck.ownerCheck.collect { v ->
                val now = v != null && v.state != OwnerCheckState.OK
                if (now && !held) {
                    feed.clear()
                    attachments.clear() // held or due: no photo or thumbnail kept (ANDROID-PLAN 0.1.39)
                }
                if (!now && held && phaseFlow.value == AppPhase.Unlocked) feed.open()
                held = now
            }
        }
    }

    /**
     * The vault is open: the `feed.event`s kept while that was not known arrive now, after the names their
     * notifications show are read (the connections and the asks), as the on-phone service has them.
     */
    private fun replayHeldBack(events: List<VaultMessage>) {
        if (events.isEmpty()) return
        replaying.incrementAndGet()
        scope.launch {
            try {
                runCatching { social.refresh() }
                runCatching { social.refreshApprovals() }
                events.forEach { e -> runCatching { feed.replay(e) } }
            } finally {
                replaying.decrementAndGet()
            }
        }
    }

    // --- local account record (encrypted under a Keystore key) ---

    /**
     * The account record. One that cannot be decrypted or parsed is not taken for "no account" (it names the vault
     * this phone's keys belong to): [localUnreadable] makes [evaluate] say so instead of showing the welcome screen.
     */
    private fun loadLocal(): LocalAccount = try {
        val a = accountFile.load()?.let { json.decodeFromString(LocalAccount.serializer(), String(it)) } ?: LocalAccount()
        localUnreadable = false
        a
    } catch (_: KeystoreException) {
        localUnreadable = true
        LocalAccount()
    } catch (_: IllegalArgumentException) {
        localUnreadable = true
        LocalAccount()
    }

    /**
     * A read-modify-write of the account record under its lock ([AccountRecord.update]): dropped after a wipe or a reset
     * since [since] ([AccountRecord.recordGeneration], read before the operation the write belongs to) was read.
     */
    private fun updateLocal(since: Int, f: (LocalAccount) -> LocalAccount?) {
        record.update(since, f)
    }

    /** A record of its own ([a]: none, or a new setup's): the writes still on their way for the one before are dropped. */
    private fun resetLocal(a: LocalAccount) {
        record.reset(a)
    }

    // --- session ---

    /**
     * The typed vault client of this device, for a module that is not part of every build and so cannot have its
     * operations here (today: the non-production calls modules). The same client the repositories use; an
     * operation on a locked vault fails as theirs do, and while a lock is pending every one is refused.
     */
    suspend fun vaultApi(): VaultApi = openApi()

    /**
     * Sends one `call.end`: the hang-up of a call that the member's lock ends. It is sent after the lock was asked
     * for, when [vaultApi] already refuses (the phone is locked from that moment), and before the vault is told to
     * lock, so that the other member is not left listening to silence. Nothing else can be sent this way: the
     * client itself is not handed out.
     */
    suspend fun sendCallEnd(callId: String, reason: String) = session().lockTimeApi(LockTimeUse.CALL_END).callEnd(callId, reason)

    /** What a module may hear of the vault's device, without a way to send anything through it. */
    class VaultEvents(val events: Flow<VaultMessage>, val droppedForTime: Flow<String>)

    private val eventsFlow = MutableStateFlow<VaultEvents?>(null)

    /**
     * The events of the vault device in use, from the moment its session exists (before the vault is open) until it
     * is dropped: a module that listens to the vault subscribes here, so that nothing collected right at an unlock
     * is missed (the device's events are not replayed). Nothing arrives here while a lock is pending.
     */
    val vaultEvents: StateFlow<VaultEvents?> = eventsFlow.asStateFlow()

    /** The member's lock, which always completes once asked for ([LockSequence]). */
    private val locking = LockSequence(scope)

    /**
     * The member's lock, failing secure (owner decision 2026-10-12): pending, durably, until the vault has
     * acknowledged it or its status says it is locked; tried again until then; never after a newer unlock. The
     * rules are [LockGuard]'s; here is only how the vault is reached.
     */
    private val lockGuard: LockGuard = LockGuard(
        ports = object : LockPorts {
            override val relayValidFor: Duration = VaultApi.LOCK_VALID

            override suspend fun relayLock(): Boolean {
                val s = lockSession() ?: return false
                return runCatching { io { s.lockTimeApi(LockTimeUse.LOCK).lock() } }.isSuccess
            }

            @Suppress("ReturnCount") // one return per answer
            override suspend fun memberLock(): MemberLock {
                val s = lockSession() ?: return MemberLock.NoAnswer
                val vaultId = s.device.vaultId ?: return MemberLock.NoAnswer
                return try {
                    val slot = io { s.alt.lock(vaultId, MEMBER_LOCK_WAIT) }
                    if (slot.status == Slot.QUEUED) MemberLock.NoAnswer else MemberLock.Answered(slot.status == Slot.DONE)
                } catch (e: MemberApiException) {
                    if (e.status != HTTP_TOO_MANY) return MemberLock.NoAnswer
                    MemberLock.Limited(Duration.ofSeconds(e.retryAfterSeconds.toLong()))
                } catch (_: IOException) {
                    MemberLock.NoAnswer
                }
            }

            override suspend fun unlocked(): Boolean? {
                val s = lockSession() ?: return null
                return runCatching { guard { vaultStatus(s) } }.map { it?.state == STATE_UNLOCKED }.getOrNull()
            }
        },
        newKeeper = { attempt ->
            LockKeeper(
                scope,
                store = lockFile,
                onSaveFailed = { e ->
                    android.util.Log.w("VaultManager", "the lock's record could not be saved: ${e.javaClass.simpleName}")
                },
                attempt = attempt,
            )
        },
    )
    private val lockKeeper: LockKeeper get() = lockGuard.keeper

    /** The phone keys of release R (§11.14.1): the Keystore's, one per vault this phone holds. */
    private val phoneSecrets = PhoneSecrets { com.vettid.core.keystore.PhoneKey(it) }

    init {
        // A lock that was pending when the process ended: locked here from the start, and tried again at once
        // (whatever started the process: the app, the on-phone service, a push wake, the boot receiver).
        lockKeeper.resume()
        if (lockKeeper.pending) lockedHere = true
        scope.launch {
            lockKeeper.notice.collect {
                if (it !is LockNotice.Unconfirmed && pauseWanted) {
                    pauseWanted = false
                    pauseRelay()
                }
            }
        }
    }

    override val lockNotice: StateFlow<LockNotice> = lockKeeper.notice

    override fun retryLock() {
        lockKeeper.retryNow()
    }

    override fun dismissLockNotice() = lockKeeper.dismissConfirmed()

    /** The session a lock is sent through: only for a vault this phone is set up with (an erased phone opens none). */
    private suspend fun lockSession(): Session? = if (enrolledOnThisPhone) runCatching { session() }.getOrNull() else null

    /**
     * The vault client for an operation on the open vault. While a lock is pending the vault counts as locked,
     * though it may be open: every operation is refused ([Session.openApi]).
     */
    private suspend fun openApi(): VaultApi = session().openApi

    /**
     * Something to finish while the vault still collects, before the member's lock: for example ending a call, so
     * that its hang-up reaches the vault. A hook must be quick and must not call [lock]; all hooks together get
     * [LockSequence.HOOK_BUDGET_MS], after which they are cancelled and the lock proceeds. No hook, no wait.
     */
    fun onBeforeLock(hook: suspend () -> Unit) = locking.addHook(hook)

    private suspend fun session(): Session = mutex.withLock {
        // Fail secure: while a wipe runs, and after one that did not finish, nothing of the old vault is opened again.
        // The only place a session is made: through the gate, by construction.
        SessionGate.open(record, session) {
            io {
                val trust: AltTrust = env.trust(http)
                val keys = deviceKeys()
                for (slot in KeySlot.entries) if (!keys.has(slot)) keys.generate(slot)
                val secrets = DeviceSecrets(keys.ed25519(KeySlot.IDENTITY), keys.kem(), keys.ed25519(KeySlot.RELAY), gateway.appKey())
                val store = KeystoreFileStore(File(app.noBackupFilesDir, DEVICE_FILE), "vault-device")
                val cfg = DeviceConfig(
                    name = deviceName, relayUrl = env.endpoints.relayUrl, http = http, store = store, trust = trust,
                    elapsedMs = { android.os.SystemClock.elapsedRealtime() },
                )
                val device = VaultDevice.load(cfg, secrets) ?: VaultDevice.create(cfg, secrets)
                val member = gateway.member()
                // §11.14.7: passwords are normalized to NFC for a vault of release R only (one with a phone secret); a vault
                // before it compares the bytes as typed.
                val api = VaultApi(device) { local.phone.inUse }
                // §11.14.9 step 2: no credential operation while a PIN or password change is undecided.
                api.setCredentialGate { changes.gate() }
                val s = Session(device, api, member, AltChannelFlow(member, trust, canary = canary)) { lockGuard }
                device.start(scope)
                observe(s)
                s
            }.also {
                session = it
                // What the vault sends while a lock is pending is not handed on: on this phone it is locked.
                eventsFlow.value = VaultEvents(it.device.events.filter { lockGuard.takesEvents() }, it.device.droppedForTime)
            }
        }
    }

    private fun deviceKeys() = DeviceKeys(SeedWrapKey.wrapper(), SharedPreferencesWrappedKeyStore(app))

    /** Drops the session after the vault forgot this device: fresh device keys for the next enrollment. */
    private suspend fun dropSession(forget: Boolean) = mutex.withLock {
        if (forget) lockKeeper.abandon() // the vault is no longer this phone's: nothing to lock from here
        if (forget) finishMoveRunner.clearFailure() // a deleted or forgotten vault: nothing of a move is left to say
        observer?.cancel()
        observer = null
        session?.device?.let { d -> if (forget) d.forget() else d.stop() }
        session = null
        eventsFlow.value = null
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
            attachments.reset()
            items.clear()
            feed.clear()
            ownerCheck.clear()
            phoneSecrets.delete(local.phone)
            // UNBOUND: clears the vault's part of whatever record is current, which the forgotten device leaves nothing
            // to belong to. A forget that ends the account resets the record next; a fresh device before an enrolment
            // ([freshDeviceIfUnpaired]: a redeem, a transfer to this phone, a recovery) keeps its member's part.
            updateLocal(RecordStore.UNBOUND) {
                it.copy(
                    setupComplete = false, snapshot = null, phoneKeyVault = "", phoneEpoch = 0, transferApprovedAtMs = null,
                    finishMovePending = false, movedIn = false, recoveryRSeen = false, capabilities = emptyList(),
                )
            }
            finishMoveFlow.value = false
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun observe(s: Session) {
        observer?.cancel()
        observer = scope.launch {
            // A collection that ended (a terminal relay error) is started again, with backoff: without it nothing
            // from the vault arrives until the next cold start.
            CollectorSupervisor(this, restart = {
                android.util.Log.i("VaultManager", "restarting the mailbox collector after ${s.device.collectionEnded.value}")
                s.device.start(scope)
                // A relay reconnect: what changed in the feed meanwhile (ANDROID-PLAN 0.1.23, 2).
                if (phaseFlow.value == AppPhase.Unlocked) feed.catchUp()
                // §11.14.9: and an unresolved change is checked again.
                if (phaseFlow.value == AppPhase.Unlocked && local.unresolvedChange != null) changes.kick()
            }).watch(s.device.collectionEnded)
            launch { s.device.vaultRefusals.collect { n -> refusals.onRefusals(n, phaseFlow.value) } }
            launch { s.device.ownerCheckRequired.collect { ownerCheck.onRequired() } }
            s.device.events.collect { m ->
                if (lockNotices.stale(m)) {
                    // The lock before this phone's last unlock (an update's first step, or the lock an unlock of a
                    // running vault makes), relayed late: the vault is open.
                    android.util.Log.i("VaultManager", "dropped a vault.locking from before the last unlock")
                    return@collect
                }
                onEvent(m)
                // A lock is pending: the vault may be open and still sending, but on this phone it is locked.
                // Nothing of what it sends is taken into the app's lists (an unlock reads them again).
                if (!lockGuard.takesEvents()) return@collect
                try {
                    items.onEvent(m)
                    sharing.onEvent(m)
                    social.onEvent(m)
                    attachments.onEvent(m.type, m.body)
                    // Before the phase is known a feed item cannot notify: kept until the vault is known to be open.
                    if (!heldBack.hold(m, phaseFlow.value)) feed.onEvent(m)
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
                // The vault says it is locking, after this phone's last unlock: a lock of this phone that was in
                // doubt no longer is. (A notice from before that unlock was dropped; one whose place in time is
                // not known, because no unlock time is kept, confirms nothing: the status read will.)
                if (lockNotices.sinceLastOpened(m)) lockKeeper.confirmed()
                val p = phaseFlow.value
                if (p is AppPhase.Unlocked || p is AppPhase.Setup) {
                    phaseFlow.value = AppPhase.Locked
                } else if (waking.get() > 0 && !lockedHere && p !is AppPhase.SignedOut) {
                    // A process started by the lock's own wake (§14.2 trigger 3) never saw the vault open: the
                    // notice is live (ephemeral, 60 s) and not this phone's lock, which sends no wake.
                    lockedElsewhereFlow.value = true
                }
            }
            // §14.2: the gateway reported the token gone, the vault erased the registration; a fresh one is asked for.
            PushWire.TOKEN_NEEDED -> tokenNeededFlow.tryEmit(PushWire.reason(m))
            "credential.alarm" -> {
                val b = m.body
                val id = VaultJson.str(b, "alarm_id") ?: return
                windowFlow.value = null
                alarmFlow.value = CredentialAlarm(id, VaultJson.str(b, "state") ?: CredentialAlarm.STATE_FROZEN, VaultJson.str(b, "at"),
                    VaultJson.str(b, "presenter"))
            }
            // 0.19.0: a change of the name request alone repeats the snapshot's version; never skip one.
            "sync.event" -> if (VaultJson.str(m.body, "kind") == SYNC_ACCOUNT_CHANGED) {
                scope.launch { refreshAccount() }
            } else if (VaultJson.str(m.body, "kind") == SYNC_PROFILE_CHANGED) {
                scope.launch { runCatching { profile.refreshProfile() } }
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
            HolderPolicy.DEVICE_UNLINKED -> {
                // Release R (§11.14.10): `transferred` on an old phone that kept its keys is the end of the kept transfer.
                val movedAway = local.transferApprovedAtMs != null && VaultJson.str(m.body, "reason") == HolderPolicy.REASON_TRANSFERRED
                if (movedAway) wipeMovedAway = true
                if (!holder.onVaultEvent(m)) {
                    wipeMovedAway = false
                    scope.launch { refresh() }
                }
            }
        }
    }

    // --- AccountRepository ---

    /**
     * Back in the foreground (after the phone froze the app in the background, say): the mailbox collection runs
     * again if it ended, the outbox is flushed, and a vault past its owner-check deadline is re-read, so that the
     * check screen shows what is waiting (`vault.status`'s `owner_check`, §3.6.5).
     */
    override fun onForeground() {
        // Release R (§11.14.10): the 7 days of a kept transfer are also checked on return, not only at a start.
        if (local.transferApprovedAtMs != null) scope.launch { runCatching { checkKeptTransfer() } }
        val d = session?.device ?: return
        if (d.ensureCollecting()) android.util.Log.i("VaultManager", "mailbox collector restarted on return to the foreground")
        // Not while a lock is pending: nothing but the lock is sent to a vault that counts as locked.
        if (!lockKeeper.pending) scope.launch { runCatching { d.flushOutbox() } }
        val t = Instant.now()
        val due = ownerCheck.ownerCheck.value?.let { it.gated(t) || it.warning(t) } == true
        if (due && phaseFlow.value is AppPhase.Unlocked) ownerCheck.onOpened()
        if (phaseFlow.value is AppPhase.Unlocked) {
            scope.launch { releaseUpdate.refreshOffer() }
            feed.catchUp()
        }
    }

    override suspend fun refresh() {
        val gen = generation
        val p = try {
            evaluate()
        } catch (e: VaultFailure) {
            // What the phone stores for the vault could not be read (the device state, a Keystore key): said as
            // that, with the way out, rather than as "cannot connect" (A6 audit P4).
            val kind = if (e.code == CODE_KEYSTORE) FailureKind.LOCAL_STATE else e.kind
            // Nothing has reached the vault service for a long time: erase-or-recover beside "Try again".
            val last = listOfNotNull(local.lastContactAtMs, local.lastOpenedAtMs).maxOrNull()
            AppPhase.Unreachable(kind, StuckScreens.longUnreachable(kind, last, System.currentTimeMillis()))
        }
        if (gen == generation) phaseFlow.value = p // a wipe meanwhile already said SignedOut
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one phase per state of the device
    private suspend fun evaluate(): AppPhase = guard {
        if (record.eraseFailed) {
            // "Try again" after a wipe that did not finish (or a marker left over at the start): it runs again, and says how it ended.
            holder.wipeNow()
            return@guard phaseFlow.value
        }
        if (localUnreadable) {
            // Read again (a Keystore that was not ready at start): never taken for an empty record.
            val a = io { loadLocal() }
            record.reload(a, readable = !localUnreadable)
            if (localUnreadable) throw VaultFailure(FailureKind.LOCAL_STATE, CODE_KEYSTORE)
            accountFlow.value = local.toInfo()
        }
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
        val rec = record.recordGeneration
        val st = try {
            vaultStatus(s).also { io { record.contacted(rec, System.currentTimeMillis()) } }
        } catch (e: VaultFailure) {
            // A lock is pending and the vault cannot be reached: the unlock screen, with its notice (never the
            // open app, and not "cannot connect" either: the member has to see that the lock is not confirmed).
            if (lockGuard.lockedWhatever(unlocked = null)) return@guard AppPhase.Locked
            // The API no longer accepts this phone's app key (another phone took the vault over, or the vault went):
            // the unlock screen says so; only the enclave's own answer erases anything.
            if (e.kind == FailureKind.UNAUTHORIZED) return@guard AppPhase.Locked
            throw e
        }
        // A lock is pending: locked on this phone whatever the status says, and confirmed if it says so.
        if (lockGuard.lockedWhatever(unlocked = st?.state == STATE_UNLOCKED)) return@guard AppPhase.Locked
        // The API says, explicitly, that the account has no vault (deleted, or the account was cancelled and
        // restored). The status is advisory and not authenticated by the vault, and the API answers the same to
        // every member while no release can start (MEMBER-API "Dark launch"), so this phone's keys are not dropped
        // for it: the member is told and decides ([forgetMissingVault]; A6 audit N6). An empty or malformed answer
        // never gets here: it fails as a malformed answer (MemberApiClient.vaultStatusAnswer).
        if (st == null) return@guard AppPhase.Unreachable(FailureKind.NO_VAULT)
        if (d.recovering) return@guard AppPhase.Setup(SetupStage.RECOVERING) // paired, the credential not recovered yet
        if (st.state != STATE_UNLOCKED) AppPhase.Locked else afterUnlocked(s)
    }

    @Suppress("ReturnCount")
    private suspend fun afterUnlocked(s: Session): AppPhase {
        if (s.device.recovering) return AppPhase.Setup(SetupStage.RECOVERING)
        if (s.device.credentialVersion == null) return AppPhase.Setup(SetupStage.NEEDS_CREDENTIAL)
        scope.launch { runCatching { refreshAlarm(s) } }
        // §11.11.9: a start-over requested on the portal shows after every unlock.
        scope.launch { runCatching { vaultStatus(s) } }
        // §11.7.1 (0.27.0): once the vault shows this app's signer, unlocks stop carrying the key's chain.
        scope.launch { runCatching { ownSigner(s) } }
        // §11.14.9: an unresolved PIN or password change is decided whenever the vault runs (a start, an unlock).
        if (local.unresolvedChange != null) changes.kick()
        ownerCheck.onOpened()
        return if (local.setupComplete) AppPhase.Unlocked else AppPhase.Setup(SetupStage.FINISHING)
    }

    /**
     * This app's signer from its own `device.list` entry (§10.3, §11.7.1), and with it the stop condition of the
     * unlock's chain: the device keeps "the vault has shown a signer" ([com.vettid.core.vault.VaultDevice.markSignerSeen]).
     * An answer without this app's entry changes nothing.
     */
    private suspend fun ownSigner(s: Session): SignerView? =
        when (val own = SignerWire.own(s.openApi.deviceList(), s.device.deviceId)) {
            is SignerWire.Own.Bound -> own.signer.also { s.device.markSignerSeen(true) }
            SignerWire.Own.BoundUnknown -> null.also { s.device.markSignerSeen(true) }
            SignerWire.Own.Unbound -> null.also { s.device.markSignerSeen(false) }
            SignerWire.Own.NoEntry -> null
        }

    private suspend fun refreshAlarm(s: Session) {
        val info = s.openApi.credentialVersion()
        alarmFlow.value = info.alarm?.let { a ->
            val id = VaultJson.str(a, "alarm_id") ?: return@let null
            CredentialAlarm(id, VaultJson.str(a, "state") ?: CredentialAlarm.STATE_FROZEN, VaultJson.str(a, "at"))
        }
    }

    override suspend fun redeemSetupCode(code: SetupCodeInput): String = enrolments.redeemSetupCode(code)

    override suspend fun forgetSetupCode() {
        val paired = session?.device?.deviceId != null
        if (paired) return // a vault on this phone is never dropped this way
        if (session != null) dropSession(forget = true)
        lockKeeper.abandon() // no vault on this phone: nothing to lock
        io { resetLocal(LocalAccount()) }
        phaseFlow.value = AppPhase.SignedOut
    }

    override suspend fun forgetMissingVault() {
        val d = guard { session().device }
        // Only a phone that holds a vault which the API, asked again now, explicitly says is gone.
        val gone = d.deviceId != null && !d.recovering && guard { vaultStatus(session()) } == null
        if (!gone) {
            refresh()
            return
        }
        dropSession(forget = true)
        io { resetLocal(LocalAccount()) }
        phaseFlow.value = AppPhase.SignedOut
    }

    /** Re-reads the account snapshot (`account.get`, §11.13); kept only when newer (`as_of`). Quiet on failure. */
    override suspend fun refreshAccount() {
        try {
            // The record this answer is for: after a wipe or a reset meanwhile, nothing of it is kept.
            val rec = record.recordGeneration
            val v = guard { session().openApi.accountGet() }
            val stored = local.snapshot
            val u = accountUpdate(stored, local.nameRequest, v) ?: return
            io { record.refreshed(rec, u) }
            // An applied name change brings new names to the shared profile's core too.
            if (u.snapshot?.firstName != stored?.firstName || u.snapshot?.lastName != stored?.lastName) {
                runCatching { profile.refreshProfile() }
            }
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
        val rec = record.recordGeneration
        val s = session()
        onStep(EnrollStep.ENROLL)
        // §11.10.9: the instance's capabilities choose the shape. Release R (§11.14.1): a phone key and its secret of
        // epoch 1 in the request; the state is stored before the request is sent, and a retry of this enrollment (a lost
        // answer, a crash) reuses the key: the vault may already hold its secret. Without `recovery_r`: as before.
        val out = s.alt.enroll(s.device, local.vaultId, local.userGuid, pin, env.attester()) { caps ->
            val r = choosePath(caps, makesPhoneKey = true, rec) == ReleasePath.Path.R
            if (r) phoneSecrets.secret(keepPhoneKey(local.vaultId, rec)) else null
        }
        if (!out.ok) throw VaultFailure(enrollFailure(out.code), out.code)
        if (out.vaultId.isNotEmpty() && out.vaultId != local.vaultId) io { updateLocal(rec) { it.copy(vaultId = out.vaultId) } }
        onStep(EnrollStep.WAIT_FOR_VAULT)
        s.device.awaitEnrolled()
        onStep(EnrollStep.HANDSHAKE)
        s.device.completeEnrollment()
    }

    override suspend fun createCredential(password: String, backup: Boolean, onStep: (EnrollStep) -> Unit) =
        createCredential(password, backup, onStep, null)

    /** (Release R, §11.14.9) `credential.create` carries the PIN, which the vault verifies and with which it arms recovery. */
    override suspend fun createCredential(password: String, backup: Boolean, onStep: (EnrollStep) -> Unit, pin: String?) = guard {
        val s = session()
        if (s.device.deviceId == null) {
            onStep(EnrollStep.HANDSHAKE)
            s.device.completeEnrollment()
        }
        onStep(EnrollStep.CREATE_CREDENTIAL)
        if (s.device.credentialVersion == null) createOrFetch(s, password, pin)
        onStep(EnrollStep.BACKUP)
        setSetting(s, KEY_BACKUP, JsonPrimitive(backup))
        onStep(EnrollStep.CONFIRM)
        s.openApi.enrollConfirm()
        phaseFlow.value = AppPhase.Setup(SetupStage.FINISHING)
    }

    /**
     * `credential.create`. (Release R) A vault that answers `exists` already holds this phone's credential (a create
     * whose answer was lost, a recovered vault): it is fetched instead of a new one being asked for.
     */
    private suspend fun createOrFetch(s: Session, password: String, pin: String?) {
        val r = local.phone.inUse
        try {
            s.openApi.credentialCreate(password, pin?.takeIf { r })
        } catch (e: VaultOpException) {
            if (!CredentialCreate.fetchInstead(r, e.code)) throw e
            s.openApi.credentialFetch()
        }
    }

    /**
     * Leaves onboarding for the open app: only from [SetupStage.FINISHING], and never while a lock is pending or "Finish
     * the move" is locking and unlocking the vault (the phase that follows decides then; the shell never shows over a
     * vault that is locked).
     */
    override suspend fun finishSetup() {
        val rec = record.recordGeneration
        if (!FinishSetupGate.allows(phaseFlow.value, lockPending = lockKeeper.pending, finishingMove = finishingMove.value)) return
        io { updateLocal(rec) { it.copy(setupComplete = true, movedIn = false) } }
        phaseFlow.value = AppPhase.Unlocked
    }

    override suspend fun preflight(): PreflightInfo = guard {
        val s = session()
        val p = s.alt.preflight(s.device.altState)
        PreflightInfo(view(p.routed), p.lastNumber, p.softwareUpdated, p.rollback, p.offer?.let { view(it) }, p.between.map { view(it) })
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
     * (Release R, ANDROID-PLAN "Other screens") An unlock with `cancel_recovery`, then at once the owner check with the
     * PIN and the password, which records that the member is present (§11.14.5).
     */
    override suspend fun unlockCancellingRecovery(pin: String, password: String, approve: ReleaseView?): UnlockAttempt =
        unlockOnce(pin, approve, cancelRecovery = true, holderApp = true) { ownerCheck.afterCancelledRecovery(pin, password) }

    /**
     * [holderApp]: this phone unlocks as the vault's app (not a recovering one): an enclave's sealed
     * `unknown_device` then means a transfer (§6.7.1) or a recovery (§11.11.5) replaced it, and it is wiped.
     * A failure before the sealed result (network, HTTP status, timeout, unreadable result) never wipes.
     */
    private suspend fun unlockOnce(
        pin: String,
        approve: ReleaseView?,
        cancelRecovery: Boolean,
        holderApp: Boolean = false,
        beforeOpen: suspend () -> Unit = {},
    ): UnlockAttempt = unlockStep(
        pin,
        UnlockOptions(approve = approve?.let { Approval(it.pcr0, it.number) }, cancelRecovery = cancelRecovery),
        followMove = true,
        holderApp = holderApp,
        beforeOpen = beforeOpen,
    ).attempt.also {
        // Every unlock that opened the vault (with the owner check, cancelling a recovery, finishing the move) ends what a
        // failed "Finish the move" left to say.
        if (it == UnlockAttempt.Success) finishMoveRunner.clearFailure()
    }

    /** One unlock's outcome; [moved]: the vault moved and locked itself (only without following the move). */
    private class StepOutcome(val attempt: UnlockAttempt, val moved: Boolean = false, val abandoned: Boolean = false)

    /**
     * One unlock (§11.4). [followMove]: after `update: moved` unlock again at once (§11.10.6), as the unlock screen
     * does; without it the move is reported and the vault stays locked. [release] routes to a given release (only
     * to abandon an unconfirmed move, §11.10.5). [onStarting] hears `503 release_starting`.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongParameterList") // one answer per §11.4 result code
    private suspend fun unlockStep(
        pin: String,
        opts: UnlockOptions,
        followMove: Boolean,
        holderApp: Boolean = false,
        release: String? = null,
        onStarting: ((Long) -> Unit)? = null,
        beforeOpen: suspend () -> Unit = {},
    ): StepOutcome {
        // §6.7.1 (0.17.0): a phone a transfer set up from an older vault release has no user_guid (§11.4 needs it).
        if (local.userGuid.isEmpty()) return StepOutcome(UnlockAttempt.Failed(FailureKind.NOT_SUPPORTED, UnlockAttempt.CODE_NO_USER_GUID))
        // Read before the unlock: a lock asked for while it is on its way bumps it, and this unlock then publishes no phase.
        val gen = generation
        // The record this unlock writes to: after a wipe meanwhile, nothing of it is (RecordStore).
        val rec = record.recordGeneration
        var moved = false
        val outcome = try {
            // Never beside a lock attempt; and once the vault has opened, no older lock is ever sent (LockKeeper).
            // On the IO dispatcher: an unlock that supersedes a pending lock removes its marker from the phone's store.
            io {
              lockKeeper.unlocking(opened = { it.result.ok }) {
                guard {
                    val s = session()
                    var out: UnlockOutcome = unlockWithSecret(s, pin, opts, release, onStarting)
                    if (out.ok && out.result.update?.result == UPDATE_MOVED) {
                        moved = true
                        // §11.10.6: after a move the vault is locked under the new release; unlock again, which reaches it.
                        if (followMove) out = unlockWithSecret(s, pin, UnlockOptions(), null, onStarting)
                    }
                    // A sealed result: the enclave knows this phone; earlier relay refusals no longer count (RefusalWatch).
                    s.device.clearVaultRefusals()
                    refusals.clear()
                    // Before the keeper takes a superseded lock back: a pause of the relay that was put off until
                    // that lock's confirmation must not happen now that the vault opens.
                    if (out.ok) pauseWanted = false
                    out
                }
              }
            }
        } catch (e: VaultFailure) {
            holder.onFailure(e) // never proof (a 503, paused or not, included)
            return StepOutcome(UnlockAttempt.Failed(e.kind, e.code, e.retryAfterSeconds))
        }
        val r = outcome.result
        if (!r.ok) {
            if (holderApp && holder.onSealedUnlockResult(r)) return StepOutcome(UnlockAttempt.Failed(FailureKind.OTHER, r.code))
            return StepOutcome(
                when (r.code) {
                    "bad_pin" -> UnlockAttempt.BadPin(r.retryAfterSeconds)
                    "backoff" -> UnlockAttempt.Backoff(r.retryAfterSeconds)
                    "recovery_pending" -> UnlockAttempt.RecoveryPending
                    // §11.7.1 (0.27.0): sealed, decided before the PIN; not a PIN failure and no backoff.
                    SignerWire.CODE_SIGNER_REFUSED -> UnlockAttempt.SignerRefused
                    "state_rollback" -> UnlockAttempt.StateRollback
                    "attestation" -> UnlockAttempt.Failed(FailureKind.ATTESTATION, r.code)
                    "manifest" -> UnlockAttempt.Failed(FailureKind.MANIFEST, r.code)
                    // Release R (§11.4, §11.14): decided before the PIN, not PIN failures. (0.44.0, §11.14.9 step 5) With
                    // a PIN change unresolved, the previous PIN's `device_secret` asks for the new PIN.
                    "device_secret" -> if (newPinStep && pinChangeUndecided()) {
                        UnlockAttempt.NewPinNeeded
                    } else {
                        UnlockAttempt.Failed(FailureKind.DEVICE_SECRET, r.code)
                    }
                    "transfer_incomplete" -> UnlockAttempt.Failed(FailureKind.TRANSFER_INCOMPLETE, r.code)
                    "retry" -> UnlockAttempt.Failed(FailureKind.RETRY, r.code)
                    else -> UnlockAttempt.Failed(FailureKind.OTHER, r.code)
                },
            )
        }
        // The vault moved and locked itself (§11.10.4 step 8); the caller unlocks again.
        if (moved && !followMove) return StepOutcome(UnlockAttempt.Success, moved = true)
        // The vault is open as of the result's ts: a vault.locking from before it is stale (LockNotices).
        lockNotices.opened(r.at)
        // Kept on the phone too: after a process start a notice from before this unlock is still stale.
        r.at?.let { at -> io { record.opened(rec, at.toEpochMilli()) } }
        val refused = r.update?.takeIf { it.result == "refused" }
        val s = session()
        // A lock asked for while this unlock was on its way is the newer wish (LockKeeper): still pending, or
        // already confirmed by its attempt, which followed the unlock at once. This phone stays locked, nothing is
        // opened or flushed.
        val lockedSince = lockGuard.lockedWhatever(unlocked = true)
        val overtaken = lockedSince || gen != generation
        scope.launch {
            // Never a lock from before this unlock (LockKeeper): one left in the outbox by an earlier version of the
            // app, which sent locks through it, is dropped before anything is flushed.
            runCatching { s.device.dropFromOutbox(TYPE_VAULT_LOCK) }
            // Nothing but the lock while one is pending: what the member wrote earlier waits for the next unlock.
            if (!overtaken && lockGuard.admits()) runCatching { s.device.flushOutbox() }
        }
        if (overtaken) {
            // (Not after a wipe, which also bumps the generation and has said SignedOut.)
            if (lockedSince) phaseFlow.value = AppPhase.Locked
        } else {
            if (!s.device.recovering) beforeOpen()
            val p = afterUnlocked(s)
            // Both checks: the generation (a lock or a wipe since this unlock began) and the guard (a lock pending now).
            if (gen == generation && !lockGuard.lockedWhatever(unlocked = true)) phaseFlow.value = p
        }
        // "The move didn't finish" (release R, §11.14.10): this phone opened the vault with its own keys after it
        // approved a transfer, so it is the vault's phone again.
        if (!overtaken) clearKeptTransfer(rec)
        // "Finish the move" (release R, §11.14.10): this new phone opened the vault from storage with its own secret.
        if (local.finishMovePending && FinishMoveProof.provedBy(r.ok, overtaken)) {
            io { updateLocal(rec) { it.copy(finishMovePending = false) } }
            finishMoveFlow.value = false
        }
        val attempt = if (refused != null) UnlockAttempt.UpdateRefused(refused.code ?: "refused") else UnlockAttempt.Success
        return StepOutcome(attempt, abandoned = r.update?.result == UPDATE_ABANDONED)
    }

    /**
     * One unlock with this phone's unlock secret when the vault has one (release R, §11.14.1). An answer
     * `device_secret` is tried once more with the next epoch (the answer to a change of secret may have been lost);
     * when that opens the vault, the phone moves to that epoch. A missing phone key is [FailureKind.DEVICE_SECRET].
     */
    @Suppress("ReturnCount")
    private suspend fun unlockWithSecret(
        s: Session,
        pin: String,
        opts: UnlockOptions,
        release: String?,
        onStarting: ((Long) -> Unit)?,
    ): UnlockOutcome {
        val phone = local.phone
        // §11.10.9: the routed instance's capabilities choose the shape; an instance without `recovery_r` for a vault
        // known to have it is refused before anything is sent.
        var r = false
        // The record this unlock writes to: after a wipe meanwhile, nothing of it is (RecordStore).
        val rec = record.recordGeneration
        // (0.44.0, §11.14.9 step 5) A PIN change is unresolved: the PIN typed after `device_secret` is the NEW one, and it
        // is tried with the candidate (the next epoch) only; the previous PIN is never reused for it.
        val twoStep = pinChangeUndecided()
        if (UnlockEpoch.nextOnly(twoStep, newPinStep)) {
            val next = s.alt.unlock(s.device, local.userGuid, pin, env.attester(), opts, release, onStarting) { caps ->
                choosePath(caps, makesPhoneKey = false, rec)
                phoneSecrets.secret(phone, ahead = 1)
            }
            // Answered (a failure to reach the vault keeps the step): the candidate was tried once.
            newPinStep = false
            if (UnlockEpoch.confirmsNext(next.ok, next.result.code)) {
                // The vault holds the candidate: the change applied. The check after the open fetches the blob and clears it.
                io { updateLocal(rec) { it.copy(phoneEpoch = phone.epoch + 1) } }
                refreshChangeFlows()
            }
            return next
        }
        val out = s.alt.unlock(s.device, local.userGuid, pin, env.attester(), opts, release, onStarting) { caps ->
            // An unlock never sets the mark on a phone without a phone secret (it then fails without one, unmarked).
            r = choosePath(caps, makesPhoneKey = false, rec) == ReleasePath.Path.R
            if (r) phoneSecrets.secret(phone) else null
        }
        when (UnlockEpoch.afterCurrent(r, out.ok, out.result.code, twoStep)) {
            UnlockEpoch.Next.DONE -> return out
            UnlockEpoch.Next.ASK_NEW_PIN -> {
                // `device_secret` with the current record, decided before any PIN is tested: the vault holds the
                // candidate. The member is asked for the new PIN ([UnlockAttempt.NewPinNeeded]); nothing more is sent.
                newPinStep = true
                return out
            }
            UnlockEpoch.Next.TRY_NEXT -> Unit
        }
        val again = s.alt.unlock(s.device, local.userGuid, pin, env.attester(), opts, release, onStarting) { caps ->
            choosePath(caps, makesPhoneKey = false, rec)
            phoneSecrets.secret(phone, ahead = 1)
        }
        if (UnlockEpoch.confirmsNext(again.ok, again.result.code)) io { updateLocal(rec) { it.copy(phoneEpoch = phone.epoch + 1) } }
        return again
    }

    /**
     * §11.10.9: the request's shape from the capabilities of the instance just verified ([ReleasePath.choose]); the
     * mark and the last capabilities are stored, into the record of [since]. A vault known to have release R and an
     * instance without it: refused.
     */
    private fun choosePath(capabilities: List<String>, makesPhoneKey: Boolean, since: Int): ReleasePath.Path {
        val path = ReleasePath.choose(capabilities, local.releaseRKnown)
        val seen = ReleasePath.marks(local.recoveryRSeen, path, makesPhoneKey = makesPhoneKey, holdsPhoneSecret = local.phone.inUse)
        if (seen != local.recoveryRSeen || capabilities != local.capabilities) {
            updateLocal(since) { it.copy(recoveryRSeen = seen, capabilities = capabilities) }
        }
        return path
    }

    /**
     * (Release R) The phone key for [keyVault] at epoch 1, stored before any request carries its secret: the one
     * this phone already holds for it when the earlier try never got that far ([PhoneSecrets.reuseOrCreate]), a new
     * one otherwise.
     */
    private fun keepPhoneKey(keyVault: String, since: Int): PhoneSecretState {
        val phone = phoneSecrets.reuseOrCreate(local.phone, keyVault)
        if (phone != local.phone) updateLocal(since) { it.copy(phoneKeyVault = phone.keyVault, phoneEpoch = phone.epoch) }
        return phone
    }

    // --- the proactive release update (owner decision 2026-10-09) ---

    private inner class UpdateOps : ReleaseUpdateOps {
        override suspend fun offer(): ReleaseUpdateOffer? = guard {
            val s = session()
            val st = s.device.altState
            if (st.releaseNumber == 0L) return@guard null
            val m = s.alt.manifest(st.manifestSerial)
            val sealed = (st.release.takeIf { it.isNotEmpty() }?.let { m.byPcr0(it) } ?: m.byNumber(st.releaseNumber))
                ?.takeIf { it.number == st.releaseNumber }
            val newest = m.newest()
            val between = if (sealed != null && newest != null) {
                m.releases.filter { it.number > sealed.number && it.number < newest.number }.map { view(it) }
            } else {
                emptyList()
            }
            ReleaseUpdateOffer.of(sealed?.let { view(it) }, newest?.let { view(it) }, between)
        }

        override fun ownerCheckDue(): Boolean =
            ownerCheck.ownerCheck.value?.gated(Instant.now()) == true || ownerCheck.lockedByOwnerCheck.value

        override suspend fun lock() {
            this@VaultManager.lock()
            // An update moves on only from a vault that is known to be locked.
            if (lockKeeper.pending) throw VaultFailure(FailureKind.NO_RESPONSE, CODE_LOCK_PENDING)
            // The next unlock goes to the instance that holds the vault: wait (bounded) until its lease is gone.
            withTimeoutOrNull(LOCKED_WAIT_MS) {
                while (runCatching { guard { vaultStatus(session()) } }.getOrNull()?.state == "unlocked") {
                    kotlinx.coroutines.delay(LOCKED_POLL_MS)
                }
            }
        }

        override suspend fun unlock(pin: String, approve: ReleaseView?, abandon: Boolean, onStarting: (Long) -> Unit): UpdateStepResult {
            val previous = session?.device?.altState?.previousRelease?.takeIf { it.isNotEmpty() }
            val opts = UnlockOptions(approve = approve?.let { Approval(it.pcr0, it.number) }, abandon = abandon)
            val release = if (abandon) {
                previous ?: return UpdateStepResult.Failed(FailureKind.NOT_SUPPORTED, "no_previous_release")
            } else {
                null
            }
            var o = unlockStep(pin, opts, followMove = false, holderApp = true, release = release, onStarting = onStarting)
            val a = o.attempt
            if (abandon && a is UnlockAttempt.Failed && a.code == MemberApiException.VAULT_BUSY) {
                // §11.10.5: an instance of the new release holds the vault; lock it first, then return.
                runCatching { this@VaultManager.lock() }
                o = unlockStep(pin, opts, followMove = false, holderApp = true, release = release, onStarting = onStarting)
            }
            return o.toStepResult()
        }

        override fun canAbandon(): Boolean = session?.device?.altState?.previousRelease?.isNotEmpty() == true
    }

    private fun StepOutcome.toStepResult(): UpdateStepResult = when {
        moved -> UpdateStepResult.Moved
        abandoned -> UpdateStepResult.Abandoned
        else -> when (val a = attempt) {
            UnlockAttempt.Success -> UpdateStepResult.Opened
            is UnlockAttempt.UpdateRefused -> UpdateStepResult.Refused(a.code)
            is UnlockAttempt.BadPin -> UpdateStepResult.BadPin(a.retryAfterSeconds)
            is UnlockAttempt.Backoff -> UpdateStepResult.Backoff(a.retryAfterSeconds)
            is UnlockAttempt.Failed -> UpdateStepResult.Failed(a.kind, a.code, a.retryAfterSeconds)
            UnlockAttempt.RecoveryPending -> UpdateStepResult.Failed(FailureKind.OTHER, "recovery_pending")
            UnlockAttempt.SignerRefused -> UpdateStepResult.Failed(FailureKind.SIGNER_REFUSED, SignerWire.CODE_SIGNER_REFUSED)
            UnlockAttempt.StateRollback -> UpdateStepResult.Failed(FailureKind.ROLLBACK, "state_rollback")
            // (0.44.0, §11.14.9 step 5) The previous PIN showed that a PIN change went through: the next try is the new PIN.
            UnlockAttempt.NewPinNeeded -> UpdateStepResult.Failed(FailureKind.CHANGE_UNRESOLVED, CODE_NEW_PIN_NEEDED)
        }
    }

    /**
     * Locks the vault. **A lock once asked for completes**: it runs in the manager's own scope, so a caller whose
     * scope ends (the screen went away) only stops waiting ([LockSequence]). This phone is locked from the first
     * line, in every outcome. Whether the vault is locked is another matter: known only when it acknowledged or its
     * status says so ([lockNotice] says which). An unconfirmed lock is not an error of the caller's: it is pending,
     * durably, and tried again.
     */
    override suspend fun lock() {
        lockedHere = true
        // A phase being computed now (a refresh, the end of an unlock) is not published after this.
        record.bumpGeneration()
        windowFlow.value = null
        phaseFlow.value = AppPhase.Locked
        // Pending, durably, BEFORE anything else is done for the lock (the hooks below take up to two seconds): a
        // process that dies from here on starts locked and goes on trying, and from here on nothing but the lock
        // and a call's hang-up is sent to the vault and nothing it sends is taken in.
        val mine = withContext(Dispatchers.IO + NonCancellable) { lockKeeper.begin() }
        locking.run(lock = { lockKeeper.ask(mine) }, after = {})
    }

    override suspend fun overview(): VaultOverview = guard {
        val rec = record.recordGeneration
        val s = session()
        val st = vaultStatus(s)
        val open = phaseFlow.value is AppPhase.Unlocked
        val vs = if (open) {
            try {
                s.openApi.status()
            } catch (_: IOException) {
                null
            }
        } else {
            null
        }
        // "Installed from" (ANDROID-PLAN §7): from the app's own device.list entry; no line when it cannot be read.
        val signer = if (open) {
            try {
                ownSigner(s)
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
            signer = signer,
            recovery = vs?.recovery?.let { r ->
                val unavailable = RecoveryUnknownClock.unavailable(r.state, r.counter)
                RecoveryStatusView(
                    r.state, r.attempts?.used, r.counter == COUNTER_UNAVAILABLE, unknownForADay = unknownForADay(unavailable, rec),
                )
            },
            recoveryOffered = ReleasePath.recoveryOffered(local.capabilities),
        )
    }

    override suspend fun changePin(pin: String, newPin: String) = guard {
        session().openApi.pinChange(pin, newPin)
    }

    /**
     * §11.14.5, §11.14.11: whether the recovery counter has been unavailable at every read for 24 hours. A read that
     * finds it available forgets the start; the first that does not records it (kept on the phone across restarts).
     */
    private suspend fun unknownForADay(unavailable: Boolean, since: Int): Boolean {
        val now = System.currentTimeMillis()
        // A read-modify-write under the account record's lock: every status read comes here, beside the other writers.
        val since = io {
            var next: Long? = null
            updateLocal(since) { cur ->
                val n = RecoveryUnknownClock.next(cur.recoveryUnknownSinceMs, unavailable, now)
                next = n
                if (n != cur.recoveryUnknownSinceMs) cur.copy(recoveryUnknownSinceMs = n) else null
            }
            next
        }
        return RecoveryUnknownClock.urgent(since, now)
    }

    /** Every `vault.status` read that carries `recovery` (the owner check's too) moves the clock of [unknownForADay]. */
    private suspend fun noteRecoveryStatus(r: com.vettid.core.vault.RecoveryStatus?, since: Int) {
        if (r == null) return
        unknownForADay(RecoveryUnknownClock.unavailable(r.state, r.counter), since)
    }

    override suspend fun deleteVault(pin: String, password: String) {
        guard { session().openApi.deleteVault(pin, password) }
        afterVaultDeleted()
    }

    /** A new vault needs a new setup code (its redeem makes the pending key, MEMBER-API 2.0.0): the welcome screen. */
    private suspend fun afterVaultDeleted() {
        dropSession(forget = true)
        io { resetLocal(LocalAccount()) }
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
        val info = s.openApi.credentialVersion()
        val alarm = info.alarm?.let { a ->
            VaultJson.str(a, "alarm_id")?.let { CredentialAlarm(it, VaultJson.str(a, "state")
                ?: CredentialAlarm.STATE_FROZEN, VaultJson.str(a, "at")) }
        }
        alarmFlow.value = alarm
        val settings = s.openApi.settingsGet().settings
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
            val page = s.openApi.itemList(sensitivity = "critical", after = after, limit = LIST_PAGE)
            n += page.items.size
            after = page.next ?: return n
        }
        return n
    }

    override suspend fun openUnlockWindow(password: String) = guard {
        val exp = session().openApi.credentialUnlock(password)
        windowFlow.value = try {
            Instant.parse(exp)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    override suspend fun closeUnlockWindow() = guard {
        session().openApi.credentialLock()
        windowFlow.value = null
    }

    override suspend fun changePassword(password: String, newPassword: String) = changePassword(password, newPassword, null)

    /** (Release R, §11.14.9) With the PIN, which the vault verifies; the change closes the recovery generation. */
    override suspend fun changePassword(password: String, newPassword: String, pin: String?) = guard {
        val rec = record.recordGeneration
        // §11.14.9 step 2: an earlier change is decided first.
        changes.gate()
        val s = session()
        sendChange(rec, ChangeKind.PASSWORD, local.phone.epoch) { store ->
            s.openApi.credentialChangePassword(password, newPassword, pin?.takeIf { local.phone.inUse }, store)
        }
        // ANDROID-PLAN "Transfer" (release R): after a password change the app offers to rotate the vault's keys.
        if (local.phone.inUse) offerRotation(true, rec)
    }

    /**
     * `pin.change` (release R, §11.14.9): the current PIN, the new one, the password and the next epoch's secret. The
     * phone moves to the next epoch only with the answer, or when a lost answer is decided as applied (0.44.0: the
     * change is decided, not repeated; [sendChange]).
     */
    override suspend fun changePin(pin: String, newPin: String, password: String) = guard {
        val rec = record.recordGeneration
        val phone = local.phone
        if (!phone.inUse) throw VaultFailure(FailureKind.NOT_SUPPORTED, "no_phone_secret")
        // §11.14.9 step 2: an earlier change is decided first.
        changes.gate()
        val s = session()
        val next = phoneSecrets.secret(phone, ahead = 1)
        try {
            sendChange(rec, ChangeKind.PIN, phone.epoch) { store -> s.openApi.pinChange(pin, newPin, password, next, store) }
        } finally {
            next.fill(0)
        }
        // ANDROID-PLAN "Transfer": after a PIN change the app offers to rotate the vault's keys.
        offerRotation(true, rec)
    }

    override val changeCheck: StateFlow<ChangeCheck?> get() = changes.check

    override fun acknowledgeChangeCheck() = changes.acknowledge()

    override val pinChangeUnresolved: StateFlow<Boolean> = pinChangeUnresolvedFlow.asStateFlow()

    override val newPinAsked: StateFlow<Boolean> = newPinAskedFlow.asStateFlow()

    /** `vault.secret.renew` (release R, §11.14.9): a new phone secret, the PIN unchanged. */
    override suspend fun renewPhoneSecret(pin: String, password: String) = guard {
        val rec = record.recordGeneration
        val phone = local.phone
        if (!phone.inUse) throw VaultFailure(FailureKind.NOT_SUPPORTED, "no_phone_secret")
        // §11.14.9 step 2 (also gated as a credential operation): an unresolved change is decided first.
        changes.gate()
        val next = phoneSecrets.secret(phone, ahead = 1)
        try {
            session().openApi.secretRenew(pin, password, next)
        } finally {
            next.fill(0)
        }
        io { updateLocal(rec) { it.copy(phoneEpoch = phone.epoch + 1) } }
    }

    override val usesPhoneSecret: Boolean get() = local.phone.inUse

    override val movedIn: Boolean get() = local.movedIn

    override val recoveryOffered: Boolean get() = ReleasePath.recoveryOffered(local.capabilities)

    override suspend fun rotate(password: String) = guard {
        val rec = record.recordGeneration
        val s = session()
        s.openApi.credentialRotate(password)
        offerRotation(false, rec)
        windowFlow.value = null
        refreshAlarm(s)
    }

    override suspend fun newCredential(pin: String, password: String, newPassword: String) = guard {
        val s = session()
        s.openApi.credentialResetHolder(pin, password, newPassword)
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
        val state = session().openApi.credentialAlarmConfirm(a.alarmId, mine) ?: CredentialAlarm.STATE_ROTATION_REQUIRED
        alarmFlow.value = a.copy(state = state)
    }

    private suspend fun setSetting(s: Session, key: String, value: JsonPrimitive) {
        val current = s.openApi.settingsGet()
        if (current.settings[key] == value) return
        s.openApi.settingsSet(current.version, mapOf(key to value))
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
            // Release R (§11.14.6): a phone the vault answered `ok` finishes on the open's screen, paired or not.
            d.deviceId == null || local.phone.inUse -> RecoveryStage.PIN
            vaultStatus(s)?.state == "unlocked" -> RecoveryStage.PASSWORD
            else -> RecoveryStage.PIN
        }
    }

    override suspend fun registerRecovery(code: RecoveryCode): RecoveryRegistration = enrolments.registerRecovery(code)

    /**
     * §11.11.10 (0.25.0): the claim by locator names the vault and the recovery; with them and the typed code the
     * app holds what a scan gives, and the register below is the scanned one.
     */
    override suspend fun registerTypedRecovery(typed: TypedRecoveryCode): RecoveryRegistration = enrolments.registerTypedRecovery(typed)

    /** The claim's refusals that have their own text (`409`, `404`); any other error is thrown. */
    private fun claimRefusal(e: MemberApiException): RecoveryRegistration = when (e.code) {
        MemberApiException.RECOVERY_NOT_AVAILABLE -> RecoveryRegistration.Refused(CODE_NOT_AVAILABLE)
        MemberApiException.RECOVERY_UNAVAILABLE -> RecoveryRegistration.Refused(CODE_NO_BACKUP)
        MemberApiException.NOT_FOUND -> RecoveryRegistration.Refused(CODE_NO_RECOVERY)
        else -> throw e
    }

    /**
     * The register's request. §11.10.9: release R's recovery only when the instance lists `recovery_r` (the phone key is
     * made before the register); with it and without `recovery_offered` nothing is sent. Otherwise the release's earlier
     * recovery.
     */
    private suspend fun buildRegister(
        s: Session,
        code: RecoveryCode,
        e: com.vettid.core.attestation.VerifiedEnclave,
        since: Int,
    ): com.vettid.core.altchan.SealedRequest {
        val caps = e.descriptor.capabilities
        if (choosePath(caps, makesPhoneKey = true, since) == ReleasePath.Path.R) {
            if (!ReleasePath.recoveryOffered(caps)) throw VaultFailure(FailureKind.NOT_SUPPORTED, CODE_NOT_OFFERED)
            val phone = phoneSecrets.create(code.vaultId)
            updateLocal(since) { it.copy(phoneKeyVault = phone.keyVault, phoneEpoch = phone.epoch) }
        }
        return s.device.prepareRecoveryRegister(local.userGuid, code, e, env.attester())
    }

    override suspend fun recoveryPreflight(): PreflightInfo = preflight()

    /**
     * `vault.recovery.open` (§11.14.6). Once the vault answered `ok` it has made this phone its app and removed the
     * recovery record: a phone in that state ([recoveryOpened]) never sends another open (which could only be answered
     * `no_recovery`) but finishes the one that succeeded ([finishOpened]). An open the member API took that got no
     * answer is remembered across a restart ([LocalAccount.recoveryOpenUnanswered]) and reported with the next answer.
     */
    override suspend fun recoveryOpen(pin: String, password: String): RecoveryOpenOutcome = guard {
        val rec = record.recordGeneration
        val s = session()
        val phone = local.phone
        if (!phone.inUse) throw VaultFailure(FailureKind.NOT_SUPPORTED, "no_phone_secret")
        if (s.device.recoveryOpenedNotFinished) return@guard finishOpened(s, pin, local.recoveryAttemptsUsed, resuming = true)
        val afterUnanswered = local.recoveryOpenUnanswered
        val secret = phoneSecrets.secret(phone)
        val r = try {
            s.alt.recoveryOpen(
                local.vaultId,
                build = { e, m ->
                    // §11.10.9: this phone registered with release R; an instance without it is refused, nothing sent.
                    choosePath(e.descriptor.capabilities, makesPhoneKey = false, rec)
                    s.device.prepareRecoveryOpen(local.userGuid, pin, password, secret, e, m, env.attester())
                },
                open = { raw, rid -> s.device.openRecoveryOpenResult(raw, rid) },
                onSent = { if (!local.recoveryOpenUnanswered) io { updateLocal(rec) { it.copy(recoveryOpenUnanswered = true) } } },
            )
        } finally {
            secret.fill(0)
        }
        val used = if (r.counted) r.used else null
        io { updateLocal(rec) { it.copy(recoveryOpenUnanswered = false, recoveryAttemptsUsed = used ?: it.recoveryAttemptsUsed) } }
        if (!r.ok) {
            return@guard RecoveryOpenOutcome(r.code, used, r.retryAt, afterUnanswered = afterUnanswered, attemptUsed = r.attemptUsed)
        }
        finishOpened(s, pin, used, resuming = false).copy(attemptUsed = r.attemptUsed)
    }

    /**
     * A lost `ok` (VAULT-MESSAGING 0.34.0 §11.14.6): the open's answer never arrived or could not be read, and the vault
     * may have completed the recovery and removed its record. An ordinary `vault.unlock` with this phone's secret and
     * the PIN: its result carries `vault_bundle` and a token only for the phone the completed recovery made the app,
     * before its first handshake; the bundle pins the vault, and the handshake is sent again (the unlock dropped any that
     * was under way), then `credential.get`. No attempt is used. Without a bundle the recovery did not complete here.
     */
    override suspend fun resumeLostOk(pin: String): RecoveryOpenOutcome = guard {
        val s = session()
        val d = s.device
        if (!local.phone.inUse || !d.recovering) throw VaultFailure(FailureKind.NOT_SUPPORTED, "no_recovery")
        if (d.recoveryOpenedNotFinished) return@guard finishOpened(s, pin, local.recoveryAttemptsUsed, resuming = true)
        val u = unlockWithSecret(s, pin, UnlockOptions(), null, null)
        when {
            !u.ok -> RecoveryOpenOutcome(u.result.code ?: "retry", local.recoveryAttemptsUsed, opened = true, attemptUsed = false)
            // A result without `vault_bundle`: this phone is not the one a completed recovery made the app.
            !d.recoveryOpenedNotFinished ->
                RecoveryOpenOutcome(CODE_NO_BUNDLE, local.recoveryAttemptsUsed, opened = true, attemptUsed = false)
            else -> finishOpened(s, pin, local.recoveryAttemptsUsed, resuming = false).copy(attemptUsed = false)
        }
    }

    /**
     * After `ok` (§11.14.6): `hs.init` with `ctx` = `recovery_id`, then `credential.get` as the holder (no
     * `credential.recover`). The app is opened by [finishRecoveryOpen], after the member has read the result. When the
     * handshake is not answered (the vault locked meanwhile, or the relay lost it), the phone unlocks the vault with
     * its own key, which the vault made its app's, and runs the handshake again.
     */
    private suspend fun finishOpened(s: Session, pin: String, used: Int?, resuming: Boolean): RecoveryOpenOutcome {
        val d = s.device
        // The handshake only when it has not completed yet: a phone paired earlier fetches the credential straight away.
        var refused = if (d.deviceId == null) pairRecovered(s, pin, resuming) else null
        if (refused == null) refused = fetchRecovered(s, pin, unlockFirst = resuming)
        if (refused != null) return RecoveryOpenOutcome(refused, used, opened = true)
        // Only now, with the credential on this phone, is the recovery over (until then the phone resumes it).
        d.finishRecoveryOpen()
        return RecoveryOpenOutcome("ok", used)
    }

    /** The recovered phone's handshake; unanswered, an unlock with its own key and the handshake again. The unlock's refusal code. */
    private suspend fun pairRecovered(s: Session, pin: String, resuming: Boolean): String? = try {
        s.device.completeRecoveryOpen(if (resuming) RESUME_HANDSHAKE_WAIT else Duration.ofSeconds(OPENED_HANDSHAKE_WAIT_S))
        null
    } catch (e: TimeoutCancellationException) {
        android.util.Log.i("VaultManager", "the recovered phone's handshake was not answered: unlocking with its own key")
        unlockOwn(s, pin, e) ?: run {
            s.device.completeRecoveryOpen()
            null
        }
    }

    /**
     * `credential.get` as the holder; when resuming ([unlockFirst]) and it is not answered (the vault locked since the
     * pairing), an unlock with this phone's own key and `credential.get` again. The unlock's refusal code.
     */
    private suspend fun fetchRecovered(s: Session, pin: String, unlockFirst: Boolean): String? = try {
        s.openApi.credentialFetch()
        null
    } catch (e: VaultStateException) {
        if (!unlockFirst) throw e
        android.util.Log.i("VaultManager", "the recovered phone's credential.get was not answered: unlocking with its own key")
        unlockOwn(s, pin, e) ?: run {
            s.openApi.credentialFetch()
            null
        }
    }

    /** An unlock with this phone's own key (the vault made it its app): null when it opened, else the vault's code. */
    private suspend fun unlockOwn(s: Session, pin: String, cause: Throwable): String? {
        val u = unlockWithSecret(s, pin, UnlockOptions(), null, null)
        return if (u.ok) null else u.result.code ?: throw VaultFailure(FailureKind.NO_RESPONSE, "recovery_unlock", cause = cause)
    }

    override suspend fun recoveryOpened(): Boolean = guard { local.phone.inUse && session().device.recoveryOpenedNotFinished }

    override val recoveryAttemptsUsed: Int? get() = local.recoveryAttemptsUsed

    override suspend fun finishRecoveryOpen() = guard {
        val s = session()
        val p = afterUnlocked(s)
        phaseFlow.value = p
    }

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

    /**
     * Forgets this phone's recovery registration: the device keys and the app key are made anew, the claimed account
     * is dropped, and the phone is back at the welcome screen (also at the next start: before, the registration was
     * dropped but the claimed account stayed, A6 audit E1). Nothing is sent: a recovering app cannot cancel the
     * recovery (§11.11.4). Only ever a recovering device: a phone that holds the vault is not touched.
     */
    override suspend fun abandonRecovery() {
        val d = guard { session().device }
        if (!d.recovering) return
        // Release R (§11.14.6): after `ok` the vault's only unlock key is this phone's; it is never deleted here.
        if (local.phone.inUse && d.recoveryOpenedNotFinished) throw VaultFailure(FailureKind.OTHER, MoveRepository.CODE_RECOVERY_OPENED)
        dropSession(forget = true)
        io { resetLocal(LocalAccount()) }
        phaseFlow.value = AppPhase.SignedOut
    }

    override suspend fun recoverCredential(password: String): RecoverOutcome = guard {
        val s = session()
        val outcome = try {
            s.openApi.credentialRecover(password)
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

    override suspend fun transferIn(code: String): String = enrolments.transferIn(code)

    override suspend fun awaitTransferIn(): Boolean = guard {
        val rec = record.recordGeneration
        val s = session()
        val started = transferStartedAt ?: Instant.now()
        val left = Duration.between(Instant.now(), started.plus(PAIRING_WINDOW)).coerceAtLeast(Duration.ofSeconds(1))
        val guid = try {
            s.device.awaitTransfer(left)
        } catch (e: TimeoutCancellationException) {
            s.device.abandonTransfer()
            throw e
        }
        transferStartedAt = null
        // §6.7.1 (0.17.0): the member's user_guid from device.paired, kept as an enrolled phone keeps the redeem's, so
        // that this phone unlocks later (§11.4). A vault release before 0.17.0 sends none: the transfer is still
        // complete, and the unlock screen says why this phone cannot unlock.
        if (guid != null) {
            gateway.memberChanged(guid)
            io { updateLocal(rec) { it.copy(userGuid = guid, vaultId = s.device.vaultId ?: it.vaultId) } }
        }
        // Release R (§11.14.10): "Finish the move" is asked until an unlock from storage with this phone's secret.
        if (local.phone.inUse) {
            io { updateLocal(rec) { it.copy(finishMovePending = true, movedIn = true) } }
            finishMoveFlow.value = true
        } else {
            io { updateLocal(rec) { it.copy(movedIn = true) } }
        }
        // §6.7.1 step 5: the blob the vault kept, confirmed, and a UTK pool.
        s.openApi.credentialTakeOver()
        phaseFlow.value = AppPhase.Setup(SetupStage.FINISHING)
        guid != null
    }

    override suspend fun abandonTransferIn() = guard {
        transferStartedAt = null
        session().device.abandonTransfer()
    }

    override suspend fun transferCreate(): TransferOfferView = guard {
        val api = session().openApi
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
            val s = session()
            val p = s.openApi.awaitTransferPending(transferId, left)
            // §11.7.1: the confirmation names both signers; this app's own is read only when it is needed.
            val current = if (p.signerChanged) runCatching { ownSigner(s) }.getOrNull()?.signerClass else null
            TransferPendingView(p.transferId, p.name, p.sas, p.signer, p.signerChanged, current)
        } catch (_: TimeoutCancellationException) {
            null
        }
    }

    /**
     * §6.7.1 step 3: the approval. Its `{}` is not yet proof that the transfer completed (vettid-vault completes
     * it right after the response, `afterRespond`, and only then removes this app); the proof is the
     * `device.unlinked{transferred}` that follows (step 4), which wipes this phone. Waits for it a little.
     */
    override suspend fun transferApprove(transferId: String, pin: String, password: String, signer: String?): Boolean {
        var keep = false
        val rec = record.recordGeneration
        val r = local.phone.inUse
        // Release R (§11.14.10, owner decision 2026-10-15): this phone keeps its keys until the vault's
        // `device.unlinked{transferred}` (after the new phone's first unlock from storage) or for 7 days. The time is
        // stored before the approval is sent, so that an approval whose answer is lost is bounded too; a refusal, or an
        // answer that does not keep the keys, removes it again.
        val before = local.transferApprovedAtMs
        if (r) io { updateLocal(rec) { it.copy(transferApprovedAtMs = System.currentTimeMillis()) } }
        val moved = try {
            holder.approveTransfer(if (r) 0 else UNLINK_WAIT_MS) {
                keep = guard { session().openApi.transferApprove(transferId, pin, password, signer) }.keepUntilNewUnlock
                openTransferId = null
            }
        } catch (e: VaultFailure) {
            if (r && !KeptTransfer.mayHaveReached(e.kind)) io { updateLocal(rec) { it.copy(transferApprovedAtMs = before) } }
            throw e
        }
        if (keep) {
            checkKeptTransfer()
        } else if (r) {
            io { updateLocal(rec) { it.copy(transferApprovedAtMs = before) } }
        }
        return moved
    }

    override val finishMovePending: StateFlow<Boolean> = finishMoveFlow.asStateFlow()

    override val finishingMove: StateFlow<Boolean> get() = finishMoveRunner.finishing

    override val finishMoveFailure: StateFlow<FailureKind?> get() = finishMoveRunner.failure

    override fun acknowledgeFinishMoveFailure() = finishMoveRunner.clearFailure()

    override suspend fun finishMove(pin: String): UnlockAttempt = finishMoveRunner.run(pin)

    /** (Release R) When this phone approved a transfer it still keeps the keys for; null otherwise. */
    override val transferKeptSince: StateFlow<Instant?> = transferKeptFlow.asStateFlow()

    /**
     * At every start, on every return to the foreground, and when the 7 days run out while the process lives: a
     * transfer approved 7 days ago or more ends here (the old phone's keys go, §11.14.10); before that,
     * [transferKeptSince] tells the shell to say "Finish the move on your new phone", and a timer runs to the end.
     */
    private suspend fun checkKeptTransfer() {
        val rec = record.recordGeneration
        val stored = local.transferApprovedAtMs ?: return
        val now = System.currentTimeMillis()
        // A clock behind the approval: the 7 days run from now, stored, so that later checks do not extend them again.
        val at = KeptTransfer.start(stored, now)
        if (at != stored) io { updateLocal(rec) { it.copy(transferApprovedAtMs = at) } }
        when (val k = KeptTransfer.decide(at, now)) {
            KeptTransfer.Decision.Erase -> {
                keptTransferTimer?.cancel()
                endKeptTransfer()
            }
            is KeptTransfer.Decision.Keep -> {
                transferKeptFlow.value = Instant.ofEpochMilli(at)
                keptTransferTimer?.cancel()
                keptTransferTimer = scope.launch {
                    delay(k.remainingMs)
                    keptTransferTimer = null // not cancelled by the check it runs
                    runCatching { checkKeptTransfer() }
                }
            }
        }
    }

    /** "The move didn't finish" succeeded: this phone is the vault's phone again. */
    private suspend fun clearKeptTransfer(since: Int) {
        if (local.transferApprovedAtMs == null) return
        keptTransferTimer?.cancel()
        io { updateLocal(since) { it.copy(transferApprovedAtMs = null) } }
        transferKeptFlow.value = null
        movedBackFlow.value = true
    }

    override val movedBack: StateFlow<Boolean> = movedBackFlow.asStateFlow()

    override fun acknowledgeMovedBack() {
        movedBackFlow.value = false
    }

    override val moveNotDone: StateFlow<Boolean> = moveNotDoneFlow.asStateFlow()

    override val rotateOffered: StateFlow<Boolean> = record.rotateOffer

    override fun dismissRotateOffer() {
        // UNBOUND: putting the offer away is right for whatever record is current.
        scope.launch { offerRotation(false, RecordStore.UNBOUND) }
    }

    /** The rotate offer, kept on the phone (a restart does not lose it), for the record of [since]. */
    private suspend fun offerRotation(on: Boolean, since: Int) {
        io { record.setRotateOffer(on, since) }
    }

    /** The offer after "Finish the move" opened the vault ([AccountRecord.offerAfterMove]): not after a lock, reset or wipe. */
    private suspend fun offerRotationAt(gen: Int) {
        io { record.offerAfterMove(gen) }
    }

    override val movedAway: StateFlow<Boolean> = movedAwayFlow.asStateFlow()

    override fun acknowledgeMovedAway() {
        runCatching { wiper.acknowledgeMovedAway() }
        movedAwayFlow.value = false
    }

    override fun acknowledgeMoveNotDone() {
        moveNotDoneFlow.value = false
    }

    /**
     * The 7 days are over (owner decision 2026-10-16): before erasing, ONE check with the vault over this phone's own
     * session ([stillTheVaultsApp]). Confirmed: the transfer never took effect, this phone keeps its keys, the kept
     * state goes and the member is told. Anything else (the vault no longer lists this phone, a refusal, no answer within
     * [KEPT_END_CHECK_MS], any failure): this phone's keys are removed, as before. Never retried: the bound stays 7 days.
     */
    private suspend fun endKeptTransfer() {
        if (!keptEndLock.tryLock()) return
        try {
            val rec = record.recordGeneration
            if (local.transferApprovedAtMs == null) return
            if (stillTheVaultsApp()) {
                android.util.Log.i("VaultManager", "7 days after the approval the vault still has this phone as its app: kept")
                keptTransferTimer?.cancel()
                io { updateLocal(rec) { it.copy(transferApprovedAtMs = null) } }
                transferKeptFlow.value = null
                moveNotDoneFlow.value = true
            } else {
                android.util.Log.i("VaultManager", "a transfer approved 7 days ago: this phone's keys are removed")
                wipeMovedAway = true
                eraseThisPhone()
            }
        } finally {
            keptEndLock.unlock()
        }
    }

    /**
     * `device.list` sent over this phone's end-to-end session with the vault, answered within [KEPT_END_CHECK_MS]: true
     * only when the answer lists this phone as the vault's app ([KeptTransfer.stillTheApp]). The answer must be sealed
     * under the session's keys (held only by the enclave and this phone) and carry `re` = this request's fresh id; the
     * vault removes the old app and its session at a completed approval, so it cannot answer it any more.
     */
    @Suppress("TooGenericExceptionCaught") // any failure is "not confirmed"
    private suspend fun stillTheVaultsApp(): Boolean = try {
        withTimeout(KEPT_END_CHECK_MS) {
            val s = session()
            KeptTransfer.stillTheApp(guard { s.openApi.deviceList() }, s.device.deviceId)
        }
    } catch (_: TimeoutCancellationException) {
        false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.i("VaultManager", "the end-of-keep check failed: ${e.javaClass.simpleName}")
        false
    }

    override suspend fun transferReject(transferId: String) = guard {
        session().openApi.transferReject(transferId)
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
        // Release R (§11.14.10): an old phone that removes its keys at the end of a kept transfer (the new phone finished
        // the move, or the 7 days are over) says so on the welcome screen; the reason is in the wipe's marker, so that a
        // wipe a crash interrupted still leaves the notice when it is finished at the next start.
        // Kept until a wipe completes: a retried wipe still says why it ran.
        val movedAway = wipeMovedAway
        WipeRun(
            record,
            // First, again at the end, whatever happens between: no lock retry, and nothing of the old vault's session
            // (its observer, its relay connection) outlives a wipe attempt, finished or not.
            abandonLock = { lockKeeper.abandon() }, // nothing of this phone's is left to lock a vault with
            detach = {
                mutex.withLock {
                    observer?.cancel()
                    observer = null
                    session?.device?.let { d ->
                        wipeDevice = d
                        runCatching { d.stop() }
                    }
                    session = null
                    eventsFlow.value = null
                }
            },
            // crash-safe: a process that dies from here on finishes the wipe at its next start
            mark = { wiper.begin(if (movedAway) LocalWipe.REASON_MOVED_AWAY else null) },
            erase = { eraseAfterMark() },
            onEnd = { phase ->
                if (phase == AppPhase.SignedOut) wipeMovedAway = false
                finishMoveFlow.value = false
                movedAwayFlow.value = runCatching { wiper.movedAway }.getOrDefault(false)
                finishMoveRunner.clearFailure()
                accountFlow.value = null
                phaseFlow.value = phase
            },
        ).run()
    }

    /** The wipe after its marker ([WipeRun]): everything of this phone's goes; true when nothing is left. */
    private suspend fun eraseAfterMark(): Boolean {
        wipeDevice?.let { d ->
            // Best effort, bounded: the relay forgets the mailbox; the wipe does not depend on it.
            withTimeoutOrNull(BEST_EFFORT_MS) { runCatching { io { d.deleteMailbox() } } }
            runCatching { d.forget() }
            wipeDevice = null
        }
        social.clear()
        attachments.reset()
        ownerCheck.clear()
        profile.clear()
        history.clear()
        items.clear()
        sharing.clear()
        feed.clear()
        canary.removeCanaryManifest()
        transferStartedAt = null
        openTransferId = null
        alarmFlow.value = null
        windowFlow.value = null
        relayPaused = false
        lockedElsewhereFlow.value = false
        heldBack.release(AppPhase.SignedOut)
        wiper.resetMemory()
        return io { wiper.erase().also { if (it) wiper.finish() } }
    }

    // --- BackgroundVault: the notification service (ANDROID-PLAN 0.1.23) ---

    /** This phone holds a vault (its setup finished): the notification service may start after a reboot. */
    val enrolledOnThisPhone: Boolean get() = local.setupComplete && local.replaced == null

    override fun held(): Boolean = ownerCheck.ownerCheck.value?.let { it.state != OwnerCheckState.OK } == true

    override fun pauseRelay() {
        if (phaseFlow.value != AppPhase.Locked || relayPaused) return
        // A lock is on its way: the vault's answer to it, and its `vault.locking`, are collected over this
        // connection. It is paused when the lock is confirmed (below, in init).
        pauseWanted = lockKeeper.pending
        val d = session?.device?.takeUnless { pauseWanted } ?: return
        relayPaused = true
        d.stop()
    }

    override fun resumeRelay() {
        pauseWanted = false
        if (!relayPaused) return
        relayPaused = false
        session?.device?.start(scope)
    }

    override fun reconnectNow() {
        val d = session?.device ?: return
        if (relayPaused || phaseFlow.value != AppPhase.Unlocked) return
        d.stop()
        d.start(scope)
        if (local.unresolvedChange != null) changes.kick()
    }

    // --- PushVault: Google push (ANDROID-PLAN 0.1.30, VAULT-MESSAGING §14) ---

    override val pushTokenNeeded: SharedFlow<String> = tokenNeededFlow.asSharedFlow()

    override suspend fun pushRegister(platform: String, token: String, appId: String): PushRegisterOutcome {
        // A locked vault answers nothing (the registration is DEK state, §12.1); held is fine (§3.6.3).
        if (phaseFlow.value != AppPhase.Unlocked) return PushRegisterOutcome.NoAnswer
        return try {
            val o = guard { session().openApi.pushRegister(platform, token, appId) }
            PushWire.state(o["push"] as? JsonObject)?.let { PushRegisterOutcome.Registered(it) } ?: PushRegisterOutcome.NoAnswer
        } catch (e: VaultFailure) {
            PushWire.outcome(e)
        }
    }

    override suspend fun pushConfirm(code: String): PushConfirmOutcome {
        // Like push.register: DEK state, so only an open (or held) vault answers.
        if (phaseFlow.value != AppPhase.Unlocked) return PushConfirmOutcome.NoAnswer
        return try {
            val o = guard { session().openApi.pushConfirm(code) }
            PushWire.state(o["push"] as? JsonObject)?.let { PushConfirmOutcome.Confirmed(it) } ?: PushConfirmOutcome.NoAnswer
        } catch (e: VaultFailure) {
            PushWire.confirmOutcome(e)
        }
    }

    override suspend fun pushUnregister(): Boolean {
        if (phaseFlow.value != AppPhase.Unlocked) return false
        return try {
            guard { session().openApi.pushUnregister() }
            true
        } catch (_: VaultFailure) {
            false
        }
    }

    override suspend fun pushState(): PushStateView? = guard {
        val s = session()
        PushWire.own(s.openApi.deviceList(), s.device.deviceId)
    }

    /**
     * PUSH-GATEWAY §11.1 step 2: a wake means only "collect". The phase is read if this process does not know it (a
     * process the wake started), the mailbox collection runs as when the app is open (decrypt, persist, ack), the
     * feed catches up, and the notifications follow from the feed and the vault's state as in the on-phone service
     * mode. Nothing is collected by a second path: the one collector of the process does it.
     */
    @Suppress("ReturnCount") // one early end per state in which there is nothing to collect
    override suspend fun collectForWake(): Boolean {
        waking.incrementAndGet()
        try {
            if (HeldBackEvents.unknown(phaseFlow.value)) refresh()
            val p = phaseFlow.value
            if (HeldBackEvents.unknown(p)) return false // not reachable now: the caller continues when there is a network
            if (p != AppPhase.Unlocked && p != AppPhase.Locked) return true // no vault on this phone
            val d = session?.device ?: return true
            if (relayPaused) return true // the fallback service keeps the relay closed while the vault is locked
            d.ensureCollecting()
            if (p == AppPhase.Unlocked) feed.catchUp()
            val began = System.nanoTime()
            val settle = WakeSettle()
            while (!settle.settled(System.nanoTime(), began, d.collectRequestSince, wakeBusy())) delay(WAKE_CHECK_MS)
            return true
        } finally {
            waking.decrementAndGet()
        }
    }

    /** A read that a wake's events started is still out (the held-back events, the feed's read). */
    private fun wakeBusy(): Boolean {
        val p = phaseFlow.value
        return replaying.get() > 0 || heldBack.waiting && HeldBackEvents.unknown(p) ||
            p == AppPhase.Unlocked && !held() && feed.load.value == ListLoad.LOADING
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

    // Last in the class, after every property is initialized: what starts work on the scope at once, which runs on
    // another thread and may read any property.
    init {
        // Release R (§11.14.10): a transfer this phone approved ends here 7 days after the approval.
        scope.launch { runCatching { checkKeptTransfer() } }
        // (0.44.0, §11.14.9) An unresolved change from before this start: the unlock's two steps, then the check.
        refreshChangeFlows()
    }

    companion object {
        private const val DEVICE_FILE = "vault-device.bin"
        private const val LOCK_FILE = "lock-record"
        private const val SOCIAL_FILE = "social.bin"
        private const val CANARY_FILE = "canary-manifest.bin"
        private const val OWNER_CHECK_FILE = "owner-check.bin"
        private const val HTTP_OK = 200
        private const val LEGACY_SESSION_FILE = "member-session.bin"
        private const val SYNC_ACCOUNT_CHANGED = "account.changed"
        private const val SYNC_PROFILE_CHANGED = "profile.changed"
        private const val CODE_NO_RECOVERY = "no_recovery"
        private const val UPDATE_MOVED = "moved"
        private const val UPDATE_ABANDONED = "abandoned"
        private const val COUNTER_UNAVAILABLE = "unavailable"
        private const val LOCKED_WAIT_MS = 15_000L
        private const val FINISH_MOVE_HOLD_MS = 90_000L
        private const val LOCKED_POLL_MS = 1_000L
        private const val KEY_BACKUP = "credential.backup"
        private const val KEY_TTL = "credential.unlock_ttl_seconds"
        private const val DEFAULT_TTL = 300
        const val MIN_TTL = 30
        const val MAX_TTL = 3600
        private const val STATE_UNLOCKED = "unlocked"
        private const val HTTP_TOO_MANY = 429

        /** How long one lock attempt waits for the member API's slot before it reads the status instead. */
        private val MEMBER_LOCK_WAIT: Duration = Duration.ofSeconds(12)
        private const val TYPE_VAULT_LOCK = "vault.lock"

        /** The code of a vault operation refused on this phone because a lock is pending ([openApi]). */
        const val CODE_LOCK_PENDING = ReleaseUpdateManager.CODE_LOCK_PENDING
        private const val LIST_PAGE = 500
        private const val MAX_PAGES = 4
        private const val FINGERPRINT_HEX = 16
        private const val CODE_NO_BACKUP = "no_backup"

        /** (§11.14.9) The unresolved change could not be stored before the send: nothing was sent. */
        private const val CODE_NOT_STORED = "change_not_stored"
        private const val CODE_CHANGE_CHECKING = "change_checking"
        private const val CODE_STALE = "stale_credential"
        private const val CODE_NEW_PIN_NEEDED = "new_pin_needed"

        /** [vaultGuard]'s code for a Keystore failure: this phone's stored state for the vault could not be read. */
        private const val CODE_KEYSTORE = "keystore"
        private const val CODE_NOT_AVAILABLE = "not_available"
        private const val CODE_NOT_TRANSFER = "not_transfer"
        private const val CODE_EXISTS = "exists"

        /** (0.34.0) The resume of a lost `ok`: the unlock opened the vault but carried no `vault_bundle` for this phone. */
        const val CODE_NO_BUNDLE = "no_bundle"

        /** (0.34.0) A release with `recovery_r` and without `recovery_offered` (§11.10.9). */
        const val CODE_NOT_OFFERED = "not_offered"

        /** The pairing window: 10 minutes after `hs.init` (§6.7, 0.10.4). */
        private val PAIRING_WINDOW: Duration = Duration.ofMinutes(10)

        /** How long the old phone's approval waits for `device.unlinked{transferred}` (§6.7.1 step 4). */
        private const val UNLINK_WAIT_MS = 30_000L

        /** The one check with the vault at the end of a kept transfer's 7 days. */
        private const val KEPT_END_CHECK_MS = 60_000L

        /** The recovered phone's handshake right after `ok` (§11.14.6). */
        private const val OPENED_HANDSHAKE_WAIT_S = 90L

        /** The handshake of a recovery whose `ok` came earlier (a restart, a lost answer): then an unlock. */
        private val RESUME_HANDSHAKE_WAIT: Duration = Duration.ofSeconds(30)

        /** The bound of the best-effort network calls of a wipe (relay mailbox, member sign-out). */
        private const val BEST_EFFORT_MS = 5_000L
        private const val WAKE_CHECK_MS = 250L
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
            MemberApiException.CLOCK_SKEW -> FailureKind.CLOCK
            MemberApiException.INVALID_CODE -> FailureKind.SETUP_CODE_INVALID
            MemberApiException.TERMS_REQUIRED -> FailureKind.TERMS_REQUIRED
            MemberApiException.RATE_LIMITED -> FailureKind.RATE_LIMITED
            MemberApiException.VAULT_UNAVAILABLE -> if (e.servicePaused) FailureKind.SERVICE_PAUSED else FailureKind.VAULT_UNAVAILABLE
            MemberApiException.RELEASE_STARTING -> FailureKind.VAULT_UNAVAILABLE
            MemberApiException.RELEASE_UNAVAILABLE -> FailureKind.RELEASE_ENDED
            MemberApiException.NOT_FOUND -> FailureKind.NOT_FOUND
            MemberApiException.MANIFEST_UNAVAILABLE -> FailureKind.MANIFEST_UNAVAILABLE
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
            "in_use" -> FailureKind.IN_USE
            "ttl_not_allowed" -> FailureKind.NOT_SUPPORTED
            "stale_credential", "utk_invalid" -> FailureKind.NO_RESPONSE
            "owner_check_required" -> FailureKind.OWNER_CHECK_REQUIRED
            // Release R (§11.14).
            "busy" -> FailureKind.BUSY
            "retry" -> FailureKind.RETRY
            "counter_unavailable" -> FailureKind.COUNTER_UNAVAILABLE
            "stored_mismatch" -> FailureKind.STORED_MISMATCH
            "weak_password" -> FailureKind.WEAK_PASSWORD
            "device_secret" -> FailureKind.DEVICE_SECRET
            "transfer_incomplete" -> FailureKind.TRANSFER_INCOMPLETE
            "response_too_large" -> FailureKind.RESPONSE_TOO_LARGE
            else -> FailureKind.OTHER
        }
    }
}

/** What [VaultManager.refreshAccount] keeps from an `account.get`. */
internal data class AccountUpdate(val snapshot: com.vettid.core.vault.AccountSnapshot?, val nameRequest: com.vettid.core.vault.NameRequest?)

/**
 * The account record after `account.get` [v] (§10.2, §11.13): the snapshot only when it is newer (`as_of`), and the
 * name request whenever it changed, whatever the snapshot's version (0.19.0: a change of the request alone repeats
 * the version, so an `account.changed` is never skipped). Null when nothing changed.
 */
internal fun accountUpdate(
    stored: com.vettid.core.vault.AccountSnapshot?,
    storedRequest: com.vettid.core.vault.NameRequest?,
    v: com.vettid.core.vault.AccountView,
): AccountUpdate? {
    val snap = v.account
    val newSnapshot = snap != null && (stored == null || newer(snap.asOf, stored.asOf))
    val request = v.nameRequest ?: storedRequest
    if (!newSnapshot && request == storedRequest) return null
    return AccountUpdate(if (newSnapshot) snap else stored, request)
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
internal fun com.vettid.core.vault.AccountSnapshot.toInfo(fallbackHint: String): AccountInfo {
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
        firstName = firstName?.takeIf { com.vettid.core.data.account.AccountNames.isValidCore(it) },
        lastName = lastName?.takeIf { com.vettid.core.data.account.AccountNames.isValidCore(it) },
        nameAllowedAfter = t(nameChange?.allowedAfter),
        email = email?.takeIf { validEmail(it) },
    )
}

/** §11.13 (0.20.0): 3–1,016 bytes of UTF-8 with an `@` and no control characters. */
internal fun validEmail(e: String): Boolean =
    e.toByteArray(Charsets.UTF_8).size in EMAIL_MIN_BYTES..EMAIL_MAX_BYTES && '@' in e && e.none { it.isISOControl() }

private const val EMAIL_MIN_BYTES = 3
private const val EMAIL_MAX_BYTES = 1_016

/** A name request as the account record keeps it. */
private fun NameRequestView.toWire() = com.vettid.core.vault.NameRequest(
    seq = seq,
    firstName = firstName,
    lastName = lastName,
    requestedAt = requestedAt?.toString(),
    state = state.name.lowercase(),
    reason = reason,
)
