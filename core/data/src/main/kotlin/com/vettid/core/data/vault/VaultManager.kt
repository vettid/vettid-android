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
import com.vettid.core.crypto.altchan.ReleaseTraits
import com.vettid.core.data.KeystoreFileStore
import com.vettid.core.data.account.MemberGateway
import com.vettid.core.data.social.InviteLinks
import com.vettid.core.data.social.SocialManager
import com.vettid.core.data.items.ItemsManager
import com.vettid.core.data.items.VaultItemsOps
import com.vettid.core.data.items.SharingManager
import com.vettid.core.data.items.VaultSharingOps
import com.vettid.core.data.env.AppEnvironment
import com.vettid.core.data.devices.DevicesManager
import com.vettid.core.data.devices.PairingManager
import com.vettid.core.data.devices.VaultPairingOps
import com.vettid.core.data.devices.VaultDevicesOps
import com.vettid.core.data.votes.OkHttpVoteSite
import com.vettid.core.data.votes.VaultVoteOps
import com.vettid.core.data.votes.VotesManager
import com.vettid.core.data.feed.FeedManager
import com.vettid.core.data.feed.VaultFeedOps
import com.vettid.core.data.wipe.LocalWipe
import com.vettid.core.keystore.AndroidKeys
import com.vettid.core.keystore.DeviceAttestationKey
import com.vettid.core.keystore.KeySlot
import com.vettid.core.keystore.KeystoreException
import com.vettid.core.keystore.SeedWrapKey
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
import com.vettid.core.vault.KeptChange
import com.vettid.core.vault.credentialAwaitSettled
import com.vettid.core.vault.SentRequest
import com.vettid.core.vault.changeResend
import com.vettid.core.vault.changeSettle
import com.vettid.core.vault.credentialChangePassword
import com.vettid.core.vault.credentialGeneration
import com.vettid.core.vault.credentialTopUpUtks
import com.vettid.core.vault.credentialVersionAt
import com.vettid.core.vault.setCredentialGate
import com.vettid.core.data.items.ListLoad
import com.vettid.core.vault.pinChange
import com.vettid.core.vault.pushConfirm
import com.vettid.core.vault.secretRenew
import com.vettid.core.vault.TransferInState
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
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
) : AccountRepository, VaultRepository, CredentialRepository, MoveRepository, BackgroundVault, PushVault, TimeScaleSource {
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

    /**
     * The record read at the start still holds the account snapshot or the name request of an older build: it is
     * written again without them (once, at the start), so that the own profile is on the phone only while the vault is
     * open. Declared before [record], whose initializer reads the record ([loadLocal]).
     */
    @Volatile
    private var legacyOwnAccount = false

    /**
     * The own account of the open vault, memory only ([OwnAccount]; ANDROID-PLAN §4 "The member's own profile"). Declared
     * before [record], whose writes publish it.
     */
    private val ownAccount = OwnAccount()

    /** The account record, written only through updates under its lock, against a wipe on another thread ([AccountRecord]). */
    private val record = AccountRecord(
        loadLocal(),
        readable = !localUnreadable, // read just above, by loadLocal
        persist = { a ->
            accountFile.save(json.encodeToString(LocalAccount.serializer(), a).toByteArray())
            ownAccount.publish { snap, req -> accountFlow.value = a.toInfo(snap, req) }
            deletedNoticeFlow.value = a.deletedNotice
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
        sealer = lockSealer(File(app.noBackupFilesDir, LOCK_FILE)),
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
                        // (ANDROID-PLAN 0.1.55) Another phone became the holder: the resolver ends the change.
                        if (e.code == VaultCodes.FORBIDDEN) throw e
                        val f = VaultFailure(opFailure(e.code, e.type), e.code, cause = e)
                        ChangeResolver.Answer.Refused(f.kind, stale = e.code == CODE_STALE, shownCode = f.shownCode)
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

            override fun end(m: UnresolvedChange) {
                forgetKept(m)
                record.endChange(m)
                newPinStep = false
                refreshChangeFlows()
            }

            override fun pinUncertain(): Boolean = local.uncertainPinFromEpoch != null

            override fun savedDecision(): ChangeCheck? = local.changeDecision?.toCheck()

            override fun saveDecision(c: ChangeCheck?) {
                // (P4) Off the caller's thread (an acknowledgement comes from the main thread); always the newest wish.
                val d = SavedDecision.of(c)
                if (d == null || !d.sameAs(decisionToSave)) notifiedWanted = false
                decisionToSave = d
                writeDecision()
            }
        },
    )

    private val pinChangeUnresolvedFlow = MutableStateFlow(false)

    /** (P4) The decision to keep on the phone, written off the main thread, the newest each time. */
    @Volatile
    private var decisionToSave: SavedDecision? = record.value.changeDecision

    @Volatile
    private var notifiedWanted = record.value.changeDecision?.notified == true
    private val decisionWrite = Mutex()

    /**
     * (§11.14.9 step 5) A PIN change is unresolved and the phone's current record is still the one it was sent from: the
     * unlock asks for the previous PIN first, and for the new one only after `device_secret`.
     */
    private fun pinChangeUndecided(a: LocalAccount = local): Boolean {
        // (0.45.0 step 3) Or the PIN is uncertain after a check that ended without a decision.
        val uncertain = a.uncertainPinFromEpoch != null && a.uncertainPinFromEpoch == a.phoneEpoch
        val m = a.unresolvedChange
        return uncertain || m != null && m.kind == ChangeKind.PIN && a.phoneEpoch == m.fromEpoch
    }

    /**
     * (N6) Why a lock-and-unlock flow (the release update, "Finish the move") is held, in the gate's words: the change's
     * "still checking" (PIN or password) while the vault answers, "can't reach" while it cannot, or the uncertain PIN.
     */
    private val changeHoldFlow = MutableStateFlow<FailureKind?>(null)

    private fun changeHoldOf(a: LocalAccount, c: ChangeCheck?): FailureKind? {
        val m = a.unresolvedChange
        return when {
            m != null && c is ChangeCheck.Unreachable -> FailureKind.CHANGE_UNRESOLVED
            m != null && m.kind == ChangeKind.PIN -> FailureKind.CHANGE_UNDECIDED
            m != null -> FailureKind.CHANGE_UNDECIDED_PASSWORD
            a.uncertainPinFromEpoch != null -> FailureKind.PIN_UNCERTAIN
            else -> null
        }
    }

    private fun refreshChangeFlows() {
        changeHoldFlow.value = changeHoldOf(local, changes.check.value)
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
            // The unlock's PIN step changed: a PIN typed before it may no longer be the one asked for (pinGeneration).
            if (newPinAskedFlow.value != v) record.pinMoved()
            newPinAskedFlow.value = v
        }

    /**
     * §11.14.9 step 1: the unresolved change, stored before the send. A write that is dropped or fails stops the
     * operation before anything is sent ([FailureKind.LOCAL_STATE]); another change still unresolved, checked in the same
     * step as the write (L4), refuses it ([FailureKind.CHANGE_UNRESOLVED]).
     */
    private fun storeChange(since: Int, m: ChangeMarker, fromEpoch: Long): UnresolvedChange {
        val u = UnresolvedChange(m.type, m.innerId, m.sentVersion, m.exp.toEpochMilli(), fromEpoch)
        when (record.storeChange(since, u)) {
            AccountRecord.ChangeStored.STORED -> Unit
            AccountRecord.ChangeStored.CONFLICT -> throw VaultFailure(FailureKind.CHANGE_UNRESOLVED, ChangeResolver.CODE_UNRESOLVED)
            AccountRecord.ChangeStored.FAILED -> throw VaultFailure(FailureKind.LOCAL_STATE, CODE_NOT_STORED)
        }
        refreshChangeFlows()
        return u
    }

    private fun forgetKept(m: UnresolvedChange) {
        keptChange?.takeIf { it.innerId == m.innerId }?.let {
            it.forget()
            keptChange = null
        }
    }

    /** The change applied ([AccountRecord.confirmChange]); false when the write failed (still shown as done). */
    private fun confirmChange(m: UnresolvedChange): Boolean {
        forgetKept(m)
        val ok = record.confirmChange(m)
        newPinStep = false
        refreshChangeFlows()
        return ok
    }

    /** The change is decided as not applied, refused or ended: its marker goes; the epoch stays (§11.14.1 rules 3–5). */
    private fun clearChange(m: UnresolvedChange) {
        forgetKept(m)
        record.clearChange(m)
        newPinStep = false
        refreshChangeFlows()
    }

    /** Sends a PIN or password change in this manager's scope ([ChangeSender]). */
    private val changeSender = ChangeSender(
        scope,
        changes,
        object : ChangeSender.Ops {
            override fun store(since: Int, m: ChangeMarker, fromEpoch: Long): UnresolvedChange = storeChange(since, m, fromEpoch)

            override fun confirm(m: UnresolvedChange): Boolean = confirmChange(m)

            override fun clear(m: UnresolvedChange) = clearChange(m)

            override fun keep(k: KeptChange) {
                keptChange = k
            }
        },
        failureOf = { e -> VaultFailure(opFailure(e.code, e.type), e.code, cause = e) },
    )

    /** Bumped by every wipe, reset and lock ([RecordStore.generation]): a phase computed before it is not published after it. */
    private val generation: Int get() = record.generation

    /** The old vault's device, detached by a wipe ([WipeRun]) and kept until it is forgotten (a retried wipe too). */
    @Volatile
    private var wipeDevice: VaultDevice? = null

    /**
     * Every device this process made and has not forgotten, weakly held: one that nothing refers to any more can send
     * and save nothing, and drops out. Before anything is erased (a wipe, a deletion's end, a forget, a start-over, a new
     * redeem's fresh device), each is forgotten ([forgetDevices]): its lock taken, so that a send in progress ends first,
     * and from then on its saves refused and its sends failing ([VaultDevice.forget]). That includes a device whose
     * session was already dropped (one stopped for a deletion), so no request still on its way writes the device's state
     * file, or makes its file key again, after the erase.
     */
    private val liveDevices: MutableSet<VaultDevice> =
        java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap()))

    /** Forgets every device of [liveDevices] ([VaultDevice.forget]); before an erase. */
    private suspend fun forgetDevices() {
        for (d in synchronized(liveDevices) { liveDevices.toList() }) {
            runCatching { d.forget() }
            liveDevices.remove(d)
        }
    }

    /**
     * Before an enrolment on the device that is kept (the session's): every other device of [liveDevices] saves and sends
     * nothing more ([VaultDevice.dropKeysFromMemory]). Not [VaultDevice.forget]: the devices share one state file, which
     * the kept one goes on using. Under [mutex], so that the session's device cannot change meanwhile ([dropSession],
     * [session]).
     */
    private suspend fun retireOtherDevices() = mutex.withLock {
        val kept = session?.device
        for (d in synchronized(liveDevices) { liveDevices.toList() }) {
            if (d === kept) continue
            runCatching { d.dropKeysFromMemory() }
            liveDevices.remove(d)
        }
    }

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
                d.startTransfer(link, attester()) { traits ->
                    val r = choosePath(traits, makesPhoneKey = true, rec) == ReleasePath.Path.R
                    if (r) phoneSecrets.secret(keepPhoneKey("", rec)) else null
                }
                try {
                    // §6.7.1 step 2 (0.10.6): a dropped hs.init is never answered; stop after 60 s and say why it may be.
                    withTimeout(MoveRepository.HS_RESP_WAIT_MS) { d.pairingSas.filterNotNull().first() }
                } catch (e: TimeoutCancellationException) {
                    d.abandonTransfer()
                    throw VaultFailure(FailureKind.NO_RESPONSE, MoveRepository.CODE_HS_UNANSWERED, cause = e)
                }
            }

            override suspend fun registerRecovery(code: RecoveryCode): RecoveryRegistration = guard {
                // (0.1.78) Undecided: a new code only reuses K (the claim, the earlier open's slot, a new identity), or is refused.
                reuseGate()?.let {
                    return@guard reuseRegister(code.vaultId, claim = { gateway.claimRecovery(code.vaultId, code.recoveryId) }) { code }
                }
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
                reuseGate()?.let {
                    return@guard reuseRegister(null, claim = { gateway.claimRecoveryByLocator(typed.locator) }) {
                        typed.recoveryCode(it, apiOrigin)
                    }
                }
                if (!session().device.recovering) freshDeviceIfUnpaired()
                val claimed = try {
                    gateway.claimRecoveryByLocator(typed.locator)
                } catch (e: MemberApiException) {
                    return@guard claimRefusal(e)
                }
                val code = typed.recoveryCode(claimed, apiOrigin)
                when (val r = registerClaimed(code, claimed)) {
                    is RecoveryRegistration.Refused -> r.copy(recoveryId = code.recoveryId)
                    else -> r
                }
            }

            /**
             * After the claim, however the code was read: the sealed `vault.recovery.register` (§11.11.3). Here, in the
             * enrolments' object, so that the reset to the claimed vault's record is reached only through [enrolments]' check.
             */
            @Suppress("ReturnCount", "ThrowsCount") // one per answer of the register
            private suspend fun registerClaimed(code: RecoveryCode, claimed: Claimed): RecoveryRegistration {
                val s = session()
                gateway.memberChanged(claimed.userGuid)
                io { resetLocal(LocalAccount(userGuid = claimed.userGuid, vaultId = code.vaultId, emailHint = claimed.emailHint)) }
                // The claim's own record, just made: what the registration writes belongs to it.
                val rec = record.recordGeneration
                // Each POST's attestation key, kept until that POST is settled not registered (ANDROID-PLAN §4 step 5).
                val slot = slots.active
                val posts = RegisterKeys()
                val r = try {
                    s.alt.recoveryRegister(
                        code.vaultId,
                        build = { e -> buildRegister(s, code, e, rec, posts) },
                        open = { raw, rid -> s.device.openRecoveryResult(raw, rid) },
                        onPosted = { posts.accepted() },
                        // A connection that was never made: that POST never left (its key is settled below).
                        postFailed = { e -> if (RegisterKeys.neverSent(e)) posts.unsent() },
                    )
                } catch (e: VaultFailure) {
                    // Every attestation key of this identity is listed: nothing was made or sent (fail secure).
                    if (e.code == AttestationKeys.CODE_KEYS_FULL) return RecoveryRegistration.Refused(AttestationKeys.CODE_KEYS_FULL)
                    if (e.code != CODE_NOT_OFFERED) throw e
                    return RecoveryRegistration.Refused(CODE_NOT_OFFERED)
                } catch (e: MemberApiException) {
                    posts.answered(e.status, e.code)
                    // 2.1.0: a recovery the enclave refused (no backup copy) is `unavailable`: the register is refused too.
                    if (e.code == MemberApiException.RECOVERY_UNAVAILABLE) {
                        s.device.dropRecoveryRegistration()
                        forgetUnregistered(slot, posts, lastRefused = false)
                        return RecoveryRegistration.Refused(CODE_NO_BACKUP)
                    }
                    if (e.code != MemberApiException.RECOVERY_NOT_AVAILABLE) throw e
                    null
                } catch (e: IOException) {
                    // Only the keys of POSTs that never left go (postFailed); the failure is thrown on as before.
                    forgetUnregistered(slot, posts, lastRefused = false)
                    throw e
                }
                return when {
                    r == null -> {
                        s.device.dropRecoveryRegistration()
                        forgetUnregistered(slot, posts, lastRefused = false)
                        RecoveryRegistration.Refused(CODE_NOT_AVAILABLE)
                    }
                    r.ok -> {
                        phaseFlow.value = AppPhase.Setup(SetupStage.RECOVERING)
                        RecoveryRegistration.Registered(claimed.emailHint)
                    }
                    else -> {
                        s.device.dropRecoveryRegistration()
                        forgetUnregistered(slot, posts, lastRefused = true)
                        RecoveryRegistration.Refused(r.code ?: "retry")
                    }
                }
            }
        },
    )

    /** Proofs that this phone was replaced, and the wipe they start. */
    val holder = HolderWatch(scope) { wipeThisPhone() }

    /** The phase, with "being deleted" above every other ([PhaseHolder], ANDROID-PLAN 0.1.54). */
    private val phaseFlow = PhaseHolder(AppPhase.Starting)

    /** A lock not made here, and the phases published after vault calls (#242): [PhaseGate]. */
    private val phaseGate by lazy {
        PhaseGate(phaseFlow, { generation }, { record.bumpGeneration() }, { lockGuard.lockedWhatever(unlocked = true) })
    }

    /**
     * (ANDROID-PLAN 0.1.54 "A vault being deleted") The re-reads of the status after `device.unlinked{vault_deleted}`,
     * through the member API alone (the app key, kept until the vault is gone): no session is opened meanwhile.
     */
    private val deletionWatch = DeletionWatch(
        scope,
        nowMs = System::currentTimeMillis,
        read = { resolvedRead.read() },
        onGone = { finishDeleted() },
        onSlow = { if (phaseFlow.deleting) phaseFlow.deleting(AppPhase.Deleting(slow = true, apiOnly = deletedApiMarker != null)) },
    )

    private val resolvedRead = ResolvedRead(
        apiStanding = { deletedApiMarker != null },
        source = { readDeletion() },
        onListedWhileApi = { scope.launch { clearApiDeleted() } },
    )

    private val deletionEvents = DeletionEvents(
        apiStanding = { deletedApiMarker != null },
        onErase = { scope.launch { finishDeleted() } },
        onHolder = { holderWhile410(it) },
    )

    private val sessionGuard = SessionGuard(deleting = { phaseFlow.deleting }, apiStanding = { deletedApiMarker != null })

    /** One move to "no vault" after a deletion, whichever read saw the vault gone. */
    private val deletionEnd = Mutex()

    /**
     * (0.1.54) The deletion's marker as this process knows it: set with "being deleted", whether or not the record took it
     * ([closeForDeletion]), so that "Check again", a return to the foreground and a refresh still read the status.
     */
    @Volatile
    private var deletingMarker: DeletingMarker? = local.deleting

    /**
     * (ANDROID-PLAN 0.1.78) The marker of a `410 vault_deleted` / `account_deleted` without the vault's word, as this process
     * knows it (set before the record took it). While it is set the keys and the device session are kept, nothing but the
     * vault's `device.unlinked{vault_deleted}` is taken from the mailbox, and that event erases at once.
     */
    @Volatile
    private var deletedApiMarker: DeletedApiMarker? = local.deletedApi

    private val deletedApiLock = Any()

    /** (0.1.54) The device stopped for a deletion, kept until the vault is gone, when its stored state is forgotten. */
    @Volatile
    private var deletingDevice: com.vettid.core.vault.VaultDevice? = null

    /** (0.1.54) When this phone's own `vault.delete` expires (epoch ms), for the deletion's bound; in memory only. */
    @Volatile
    private var deleteExpMs: Long? = null

    /** (0.1.54) "Your vault was deleted", once on "no vault": follows the account record. */
    private val deletedNoticeFlow = MutableStateFlow(local.deletedNotice)

    init {
        // A process that finds the marker starts in "being deleted", before it reads anything (the watch starts with the
        // first read of the phase, [evaluate]).
        local.deleting?.let { m -> phaseFlow.deleting(AppPhase.Deleting(slow = VaultDeletion.pastBound(m))) }
            ?: local.deletedApi?.let { m ->
                phaseFlow.deleting(AppPhase.Deleting(slow = VaultDeletion.pastBound(m.watch()), apiOnly = true))
            }
    }

    /** (ANDROID-PLAN 0.1.54) The stored marker of an unconfirmed release move, as the device last kept it. */
    private val moveFlow = MutableStateFlow<MoveUnconfirmed?>(null)

    override val moveUnconfirmed: StateFlow<MoveUnconfirmed?> = moveFlow.asStateFlow()

    /** The marker as the device keeps it now ([moveFlow]); none without a session. */
    private fun syncMove() {
        moveFlow.value = session?.device?.altState?.let { MoveUnconfirmed.of(it) }
    }

    /** (Release R, §11.14.10) When this old phone approved a transfer it still keeps the keys for. */
    private val transferKeptFlow = MutableStateFlow<Instant?>(null)

    /** (Release R) The erase at the end of a kept transfer's 7 days, while the process lives that long. */
    @Volatile
    private var keptTransferTimer: Job? = null

    /** (Release R, §11.14.10) This new phone has a move to finish. */
    private val finishMoveFlow = MutableStateFlow(local.finishMovePending)

    /** (STAGING-TIME-SCALE §5.2) The vault's time scale, from the account record ([syncTimeScale]). */
    private val timeScaleFlow = MutableStateFlow(timeScaleOf(local))

    override val timeScale: StateFlow<TimeScaleView> = timeScaleFlow.asStateFlow()

    private fun timeScaleOf(a: LocalAccount) = TimeScaleView(
        scale = a.timeScale ?: com.vettid.core.crypto.altchan.TimeScale.REAL,
        known = a.timeScale != null,
        stagingBuild = env.name == ENV_STAGING,
    )

    /** The flow follows the record after a write, a reset, a reload or a wipe. */
    private fun syncTimeScale() {
        timeScaleFlow.value = timeScaleOf(local)
    }

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
        // Its own prompt, not the unlock screen's two steps (§11.14.9 step 2): refused while a change is unresolved.
        unlock = { pin, taken -> unlockOnce(pin, null, false, holderApp = true, pinTaken = taken) },
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

    /**
     * (Release R, §11.14.10; owner decision 2026-10-23) The check with the vault once a kept transfer's 7 days are over:
     * erases only on a definite answer; no answer means "check again" at the next start or return to the foreground.
     * The record (both counters and the approval's time) is noted before the up-to-[KEPT_END_CHECK_MS] wait, and every
     * verdict is acted on only after a write conditional on it: a lock, a reset or a wipe during the wait acts on nothing.
     */
    private val keptEnd = KeptTransferEnd.onRecord(
        record,
        check = { keptEndVerdict() },
        write = { w -> io { w() } },
        afterWrite = { syncTimeScale() },
        onKept = {
            android.util.Log.i("VaultManager", "7 days after the approval the vault still has this phone as its app: kept")
            keptTransferTimer?.cancel()
            transferKeptFlow.value = null
            moveNotDoneFlow.value = true
        },
        erase = {
            android.util.Log.i("VaultManager", "a transfer approved 7 days ago: the vault no longer lists this phone, its keys are removed")
            wipeMovedAway = true
            eraseThisPhone()
        },
    )
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
     * The window between an unlock's result and its phase, while relay requests go out before the app opens (the owner
     * check after a cancelled recovery, §3.6.5's check): the collection runs, a pause waits, the vault's events are held.
     */
    private val opening = OpeningWindow(
        object : OpeningWindow.Port {
            override val phase: AppPhase get() = phaseFlow.value
            override val relayPaused: Boolean get() = this@VaultManager.relayPaused

            override fun holdEvents() {
                session?.device?.holdEvents()
            }

            override suspend fun endHold(deliver: Boolean): List<VaultMessage> {
                val d = session?.device ?: return emptyList()
                val out = d.endHold(deliver)
                if (!d.holdingEvents && d.leftOnRelay > 0) {
                    android.util.Log.i("VaultManager", "${d.leftOnRelay} events left on the relay before the open")
                }
                return out
            }

            override suspend fun startCollecting() {
                val d = session?.device ?: return
                // As after every unlock: a lock left in the outbox by an earlier version of the app is dropped before the
                // collection's start flushes the outbox; and nothing is flushed while a lock is pending.
                runCatching { d.dropFromOutbox(TYPE_VAULT_LOCK) }
                if (lockGuard.admits()) d.start(scope)
            }

            override fun pauseAgain() {
                // Through pauseRelay, which keeps the connection while a lock is pending (its answer and `vault.locking`
                // come over it) and pauses it once that lock is confirmed; and which leaves a phase that is not Locked
                // (unreachable, starting) to the notification service, which pauses once the phase is known to be Locked.
                this@VaultManager.relayPaused = false
                pauseRelay()
            }

            override suspend fun takeState(events: List<VaultMessage>) {
                for (m in events) {
                    val take: suspend () -> Unit = { if (!lockNotices.stale(m)) runCatching { onEvent(m) } }
                    // (#244 review B3) A vault.locking during an unlock: judged after it.
                    if (!unlockNotices.hold(m, take)) take()
                }
            }
        },
    )

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
        phaseGate.refused()
    }
    override val refusedByVault: StateFlow<Boolean> = refusals.suspected

    /** `vault.locking` notices from before the vault last opened are dropped ([LockNotices]). */
    /** (#244 review B3) `vault.locking` received during an unlock, judged after it. */
    private val unlockNotices = UnlockNoticeHold()

    private val lockNotices = LockNotices(opened = local.lastOpenedAtMs?.let { Instant.ofEpochMilli(it) }) { session?.device?.unlockId }

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
        obsoleteStore = KeystoreFileStore(File(app.noBackupFilesDir, SOCIAL_FILE), "social"),
        obsoletePendingStore = KeystoreFileStore(File(app.noBackupFilesDir, PENDING_ASKS_FILE), "pending-asks"),
        onLeftoverFailed = {
            android.util.Log.w("VaultManager", "the old pending-asks file could not be deleted: ${it.javaClass.simpleName}")
        },
        itemNames = {
            items.items.value.associate { i ->
                i.itemId to com.vettid.core.data.social.ShareItem(i.itemId, i.name, i.category, i.sensitivity.wire)
            }
        },
        feedRead = { kinds, ref, connectionId -> feed.markReadWhere(kinds, ref, connectionId) },
        feedWithdrawn = { feed.askWithdrawn(it) },
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
        mark = { ownAccount.mark() },
        // Memory only, like the snapshot ([OwnAccount]): dropped when the vault closed while the request was on its way.
        onRequest = { r, since -> ownAccount.request(since, r.toWire(), ::ownProfileOpen) { publishAccount() } },
        open = { ownProfileOpen() },
        checksLeft = {
            ownerCheck.refreshOwnerCheck()
            ownerCheck.ownerCheck.value?.checksLeft
        },
    )

    /**
     * The member's devices and agents (VAULT-MESSAGING §10.3, ANDROID-PLAN 0.1.53 AA1): "Devices & agents". Memory only,
     * while the vault is open and not held. Ending a device's access or removing it takes its requests out of Approvals.
     */
    val devices = DevicesManager(
        scope,
        ops = { VaultDevicesOps(openApi()) },
        self = { session?.device?.deviceId },
        onDeviceGone = { social.dropDeviceRequests(it) },
        // The pairing in progress goes wherever the list does (lock, hold, wipe, deletion).
        onClear = { pairing.clear() },
    )

    /** Pairing an agent (ANDROID-PLAN 0.1.53 AA2): the code, the comparison, the approval. Memory only, cleared with [devices]. */
    val pairing: PairingManager = PairingManager(
        scope,
        ops = { VaultPairingOps(openApi()) },
        knownDeviceIds = { devices.devices.value.takeIf { devices.load.value == ListLoad.LOADED }?.map { it.id }?.toSet() },
        readDevices = {
            devices.refresh()
            devices.devices.value
        },
        onPaired = { devices.refreshQuietly() },
    )

    /**
     * The member's votes (VAULT-MESSAGING 0.81.3 §10.19, ANDROID-PLAN 0.1.108 "Votes"): the list, a vote, the cast, and the
     * app's hourly work with the public votes site. Memory only, while the vault is open and not held; cleared with
     * [devices].
     */
    val votes = VotesManager(
        scope,
        ops = { VaultVoteOps(openApi()) },
        site = OkHttpVoteSite(env.endpoints.votesBase, http),
        windowUntil = { windowFlow.value },
        openWindow = { openUnlockWindow(it) },
        closeWindow = { closeUnlockWindow() },
        unlockTtl = { status().unlockTtlSeconds },
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
            // A passed check also reads the approvals again here (§10.4.2), including one that came while the view was already OK.
            onPassed = { social.refreshAllQuietly() },
            readNotices = { ids -> ids.forEach { feed.setStatus(it, FeedManager.STATUS_READ) } },
            mark = { record.recordGeneration },
            onRecoveryStatus = { r, since -> noteRecoveryStatus(r, since) },
            onTimeScale = { t, since -> noteStatusTimeScale(t, since) },
            // (ANDROID-PLAN 0.1.95) "Probably locked, checking": the member API's word on the vault, and the lock it finds.
            memberState = { lockSession()?.let { runCatching { guard { vaultStatus(it) } }.getOrNull()?.state } },
            onFoundLocked = { lockedByNotice() },
        )
    }

    /** The release update the shell offers, and the one-step update (ANDROID-PLAN 0.1.19). */
    val releaseUpdate = ReleaseUpdateManager(scope, UpdateOps())

    init {
        scope.launch { runCatching { canary.load() } }
        // Before MEMBER-API 2.0.0 the app kept the account site's session cookies here; it never signs in now.
        scope.launch { io { runCatching { File(app.noBackupFilesDir, LEGACY_SESSION_FILE).delete() } } }
        // A leftover of the removed social.bin (its write went through a temporary file).
        scope.launch { io { runCatching { File(app.noBackupFilesDir, SOCIAL_FILE + ".tmp").delete() } } }
        // A leftover of the removed pending-asks.bin (its write went through a temporary file; the file itself is deleted by [social]).
        scope.launch { io { runCatching { File(app.noBackupFilesDir, PENDING_ASKS_FILE + ".tmp").delete() } } }
        // An older build kept the account snapshot and the name request in the record until a wipe: written again without.
        scope.launch { io { runCatching { if (legacyOwnAccount && record.rewrite()) legacyOwnAccount = false } } }
        scope.launch {
            // Lists are re-read whenever the vault opens (unlock, end of onboarding, app start while unlocked).
            var previous: AppPhase = AppPhase.Starting
            // Every step runs even if one throws (fail secure); a lock's clears that failed run once more (PhaseSteps).
            val phaseSteps = PhaseSteps.of(
                replay = { p -> replayHeldBack(heldBack.release(p)) },
                forgetOpenVault = { social.forgetOpenVault() },
                clearAttachments = { attachments.clear() },
                clearItems = { items.clear() },
                clearSharing = { sharing.clear() },
                clearFeed = { feed.clear() },
                clearDevices = { devices.clear() },
                clearVotes = { votes.clear() },
                // The names, the full email and the photo: memory only, while the vault is open (ANDROID-PLAN §4).
                ownProfile = { p ->
                    when (OwnProfileStep.onPhase(p)) {
                        OwnProfileStep.FORGET -> forgetOwnProfile()
                        OwnProfileStep.READ -> readOwnProfile()
                        OwnProfileStep.NONE -> Unit
                    }
                },
            )
            // The only place a lock clears these: one failure on one phase never ends this collector (surviveEvent).
            phaseFlow.asStateFlow().collectSurviving("phase") {
                // Open, then locked, and not by the member here: "Your vault is locked" (not at a cold start).
                if (it == AppPhase.Locked && previous == AppPhase.Unlocked && !lockedHere) lockedElsewhereFlow.value = true
                previous = it
                phaseSteps.onPhase(it) {
                    lockedHere = false
                    lockedElsewhereFlow.value = false
                    // First: events delivered from here on update the state it reopens (a throw below cannot skip it).
                    social.reopen() // the pending.list read below may merge again
                    resumeRelay() // after an unlock the notification service's connection comes back
                    social.refreshAllQuietly()
                    launch { attachments.probe() } // §10.5.1 "Compatibility": once after each unlock
                    items.refreshQuietly()
                    votes.startSync() // the list, the public site and the results that are due: now, then every hour
                    feed.open()
                    // The proactive release notice (owner decision 2026-10-09): the manifest, read after every unlock.
                    launch { releaseUpdate.refreshOffer() }
                }
            }
        }
        // The feed's owner-check items are the shell's notices (one source, ANDROID-PLAN 0.1.23, 6).
        scope.launch { feed.items.collectSurviving("feed items") { ownerCheck.onFeed(it) } }
        // Held or due (§3.6.3): no cached feed content is shown; after the check (here or on another device), a full read.
        scope.launch {
            var held = false
            ownerCheck.ownerCheck.collectSurviving("owner check") { v ->
                val now = OwnProfileStep.held(v)
                // Kept first: nothing that throws below leaves it wrong for the next value.
                val wasHeld = held
                held = now
                // Every clearing step runs even if one throws (fail secure); the first failure is rethrown after the last.
                val steps = ClearSteps()
                if (now && !wasHeld) {
                    steps.step { feed.clear() }
                    steps.step { devices.clear() }
                    steps.step { votes.clear() }
                    steps.step { social.forgetPending() } // pending approvals and requests: memory only, not while held
                    steps.step { attachments.clear() } // held or due: no photo or thumbnail kept (ANDROID-PLAN 0.1.39)
                }
                if (!now && wasHeld && phaseFlow.value == AppPhase.Unlocked) {
                    steps.step { feed.open() }
                    steps.step { votes.startSync() } // the votes the hold dropped, and the work with the public site
                    steps.step { social.refreshAllQuietly() } // the lists the hold dropped: approvals and requests
                }
                // Nor the member's own names, full email or photo; read again after the passed check.
                steps.step {
                    when (OwnProfileStep.onCheck(wasHeld = wasHeld, held = now, phase = phaseFlow.value)) {
                        OwnProfileStep.FORGET -> forgetOwnProfile()
                        OwnProfileStep.READ -> readOwnProfile()
                        OwnProfileStep.NONE -> Unit
                    }
                }
                steps.finish()
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

    /** What the app shows of the account now: the record's masked email and, while the vault is open, [ownAccount]. */
    private fun publishAccount() {
        ownAccount.publish { snap, req -> accountFlow.value = local.toInfo(snap, req) }
    }

    /**
     * The vault is open for the own profile: unlocked, and neither held nor due (§3.6.3). Otherwise the member's names,
     * full email and photo are not kept (ANDROID-PLAN §4 "The member's own profile").
     */
    private fun ownProfileOpen(): Boolean = isOwnProfileOpen(phaseFlow.value, ownerCheck.ownerCheck.value)

    /**
     * The vault is not open (a lock, the hold or the due state, a sign-out, a reset, a wipe, a deletion): the own account
     * and the own profile (the names, the full email, the photo) leave memory, and the shell shows the initial tile.
     */
    private fun forgetOwnProfile() {
        ownAccount.forget { accountFlow.value = local.toInfo() }
        profile.clear()
    }

    /** The vault opened again (an unlock, a passed check): the own account and profile are read from it. */
    private fun readOwnProfile() {
        scope.launch { refreshAccount() }
        scope.launch { runCatching { profile.refreshProfile() } }
    }

    /**
     * The account record. One that cannot be decrypted or parsed is not taken for "no account" (it names the vault
     * this phone's keys belong to): [localUnreadable] makes [evaluate] say so instead of showing the welcome screen.
     */
    private fun loadLocal(): LocalAccount = try {
        val raw = accountFile.load()?.let { String(it) }
        val a = raw?.let { json.decodeFromString(LocalAccount.serializer(), it) } ?: LocalAccount()
        localUnreadable = false
        legacyOwnAccount = raw != null && holdsLegacyOwnAccount(raw, json)
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
        try {
            record.update(since, f)
        } finally {
            syncTimeScale()
        }
    }

    /** A record of its own ([a]: none, or a new setup's): the writes still on their way for the one before are dropped. */
    private fun resetLocal(a: LocalAccount) {
        record.reset(a)
        syncTimeScale()
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

            override suspend fun relayLock(unlockId: String?): RelayLock {
                val s = lockSession() ?: return RelayLock.NO_ANSWER
                return try {
                    io { s.lockTimeApi(LockTimeUse.LOCK).lock(unlockId = unlockId) }
                    RelayLock.LOCKED
                } catch (_: TimeoutCancellationException) {
                    RelayLock.NO_ANSWER
                } catch (e: CancellationException) {
                    throw e
                } catch (e: VaultOpException) {
                    if (e.code == VaultApi.CODE_STALE_LOCK) RelayLock.STALE else RelayLock.NO_ANSWER
                } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                    RelayLock.NO_ANSWER
                }
            }

            @Suppress("ReturnCount") // one return per answer
            override suspend fun memberLock(unlockId: String?): MemberLock {
                val s = lockSession() ?: return MemberLock.NoAnswer
                val vaultId = s.device.vaultId ?: return MemberLock.NoAnswer
                return try {
                    val slot = io { s.alt.lock(vaultId, MEMBER_LOCK_WAIT, unlockId) }
                    when (slot.status) {
                        Slot.QUEUED -> MemberLock.NoAnswer
                        // A code is read only beside "done" (MEMBER-API 2.5.0 "Locks").
                        Slot.DONE -> MemberLock.Answered(true, slot.code)
                        else -> MemberLock.Answered(false)
                    }
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
            LockWiring.keeper(
                scope,
                store = lockFile,
                attempt = attempt,
                onSaveFailed = { e ->
                    android.util.Log.w("VaultManager", "the lock's record could not be saved: ${e.javaClass.simpleName}")
                },
                // (Release L) The opening the lock was for is dead: a later plain lock must not be bound to its name.
                forgetUnlockId = { dead -> session?.device?.forgetUnlockId(dead) },
            )
        },
    )
    private val lockKeeper: LockKeeper get() = lockGuard.keeper

    /** The phone keys of release R (§11.14.1): the Keystore's, one per vault this phone holds. */
    private val phoneSecrets = PhoneSecrets { com.vettid.core.keystore.PhoneKey(it) }

    init {
        // A lock that was pending when the process ended: locked here from the start, and tried again at once
        // (whatever started the process: the app, the on-phone service, a push wake, the boot receiver, the pending
        // lock's job), bound to the same unlock as before when it was bound (release L).
        lockKeeper.resume()
        if (lockKeeper.holdsPhone) lockedHere = true
        scope.launch {
            lockKeeper.notice.collectSurviving("lock notice") {
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
    private suspend fun lockSession(): Session? =
        if (enrolledOnThisPhone) runCatching { session(mailboxOnly = sessionGuard.lockMailboxOnly()) }.getOrNull() else null

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

    private suspend fun session(mailboxOnly: Boolean = false): Session = mutex.withLock {
        // ANDROID-PLAN 0.1.54: a vault being deleted gets nothing more from this phone, and nothing of it is read.
        // (0.1.78) The one exception: the mailbox of a phone that holds a 410 without the vault's word keeps being collected.
        sessionGuard.check(mailboxOnly)
        // Fail secure: while a wipe runs, and after one that did not finish, nothing of the old vault is opened again.
        // The only place a session is made: through the gate, by construction.
        SessionGate.open(record, session) {
            io {
                val trust: AltTrust = env.trust(http)
                val slot = slots.active
                val keys = slots.keys(slot)
                // Fail secure: while an open's answer is unknown, a slot with missing keys is never replaced by new ones.
                if (!Rules.mayGenerateKeys(undecidedNow()) && !slots.exists(slot)) {
                    throw VaultFailure(FailureKind.LOCAL_STATE, CODE_UNDECIDED)
                }
                for (k in KeySlot.entries) if (!keys.has(k)) keys.generate(k)
                val secrets = DeviceSecrets(keys.ed25519(KeySlot.IDENTITY), keys.kem(), keys.ed25519(KeySlot.RELAY), gateway.appKey())
                val store = KeystoreFileStore(slots.file(slot), "vault-device")
                val cfg = DeviceConfig(
                    name = deviceName, relayUrl = env.endpoints.relayUrl, http = http, store = store, trust = trust,
                    elapsedMs = { android.os.SystemClock.elapsedRealtime() },
                )
                val device = VaultDevice.load(cfg, secrets) ?: VaultDevice.create(cfg, secrets)
                liveDevices += device
                val member = gateway.member()
                // §11.14.7: passwords are normalized to NFC for a vault of release R only (one with a phone secret); a vault
                // before it compares the bytes as typed.
                val api = VaultApi(device) { local.phone.inUse }
                // §11.14.9 step 2: no credential operation while a PIN or password change is undecided.
                api.setCredentialGate { carriesPin -> changes.gate(carriesPin) }
                val s = Session(device, api, member, AltChannelFlow(member, trust, canary = canary)) { lockGuard }
                // (A6 audit N1) A transfer past its deadline: its collect runs first (evaluate), before the collection
                // can lease a waiting device.paired; the collection starts after it.
                if (TransferInResume.decide(device.transferInPending, device.deviceId, Instant.now()) != TransferInResume.Action.DEADLINE) {
                    device.start(scope)
                }
                observe(s)
                s
            }.also {
                session = it
                // What the vault sends while a lock is pending is not handed on: on this phone it is locked.
                eventsFlow.value = VaultEvents(it.device.events.filter { lockGuard.takesEvents() }, it.device.droppedForTime)
                syncMove()
            }
        }
    }

    /** (0.1.78) The phone's device identities: slot 0 normally, a second only while a reused-key recovery is undecided. */
    private val slots = IdentitySlots(app, app.noBackupFilesDir, recoveryIdOf = { migrationRecoveryId(it) })

    /**
     * The `recovery_id` the identity in [slot] is recovering for, as far as this process knows it (the migration's tag of the
     * key of the builds before slots): the reuse record's, or the device in use's while it is recovering; else null.
     */
    private fun migrationRecoveryId(slot: Int): String? {
        val r = local.reuse
        return when {
            r != null && slot == r.earlierSlot -> r.earlierRecoveryId
            r != null && slot == r.newSlot -> r.newRecoveryId
            else -> session?.device?.takeIf { slot == slots.active && it.recovering }?.recoveryId
        }
    }

    /**
     * The attester of identity [slot] (the one in use by default, [AttestationKeys]): an attestation is a new key of the
     * slot, registered for [recoveryId] when it is a register's; it signs with [signWith], else with the slot's current key.
     */
    private fun attester(slot: Int = slots.active, recoveryId: String? = null, signWith: String? = null): SlotAttester =
        SlotAttester(slots.attestation, slot, recoveryId, signWith) { env.attester(it) }

    /** The keys of [posts] that are settled not registered ([RegisterKeys]) are deleted from [slot]. */
    private suspend fun forgetUnregistered(slot: Int, posts: RegisterKeys, lastRefused: Boolean) {
        io { slots.attestation.remove(slot, posts.notRegistered(lastRefused)) }
    }

    /**
     * The recovered identity's handshake (VAULT-MESSAGING §11.14.6 "Pairing"): `hs.init` with each `recovery_id` the identity
     * in use registered for, newest first, each waiting [wait] (null: the device's default), and the last one's timeout
     * thrown. Once one completes, the vault's binding is the attested key of the registration it named: that registration's
     * keys stay and the identity's other keys go ([AttestationKeys.keepRegistration]).
     */
    private suspend fun pairWithEach(d: com.vettid.core.vault.VaultDevice, wait: Duration?) {
        val slot = slots.active
        val contexts = io { slots.attestation.pairingContexts(slot, d.recoveryId) }
        var i = 0
        while (true) {
            val ctx = contexts[i++]
            try {
                if (wait == null) d.completeRecoveryOpen(ctx = ctx) else d.completeRecoveryOpen(wait, ctx)
                break
            } catch (e: TimeoutCancellationException) {
                if (i == contexts.size) throw e
            }
        }
        io { slots.attestation.keepRegistration(slot, contexts[i - 1]) }
    }


    /** Drops the session after the vault forgot this device: fresh device keys for the next enrollment. */
    private suspend fun dropSession(forget: Boolean) = mutex.withLock {
        if (forget) lockKeeper.abandon() // the vault is no longer this phone's: nothing to lock from here
        if (forget) finishMoveRunner.clearFailure() // a deleted or forgotten vault: nothing of a move is left to say
        observer?.cancel()
        observer = null
        forgetPostedPin() // (review m1) nothing of a PIN outlives the session
        // Every step of a forget runs even if one throws (fail secure), the device's own forget and the other devices'
        // too (#179 review F1); the first failure is rethrown after the last.
        val steps = ClearSteps()
        session?.device?.let { d -> if (forget) steps.step { d.forget() } else d.stop() }
        // Every other device too (one stopped for a deletion, or detached), before the device file and keys go below.
        if (forget) steps.step { forgetDevices() }
        session = null
        eventsFlow.value = null
        moveFlow.value = null
        refusals.clear()
        if (forget) {
            steps.step {
                io {
                    // (0.1.78) Both identities go: slot 0 is made anew, a second one is removed.
                    slots.resetToSingle()
                    val keys = slots.keys(0)
                    KeySlot.entries.forEach { keys.generate(it) }
                    gateway.newAppKey() // the app key is per vault (§11.12.2)
                    slots.file(0).delete()
                }
            }
            alarmFlow.value = null
            windowFlow.value = null
            steps.step { social.clear() }
            steps.step { attachments.reset() }
            steps.step { items.clear() }
            steps.step { feed.clear() }
            steps.step { devices.clear() }
            steps.step { votes.clear() }
            steps.step { ownerCheck.clear() }
            steps.step { forgetOwnProfile() }
            steps.step { phoneSecrets.delete(local.phone) }
            // UNBOUND: clears the vault's part of whatever record is current, which the forgotten device leaves nothing
            // to belong to. A forget that ends the account resets the record next; a fresh device before an enrolment
            // ([freshDeviceIfUnpaired]: a redeem, a transfer to this phone, a recovery) keeps its member's part.
            steps.step {
                updateLocal(RecordStore.UNBOUND) {
                    it.copy(
                        setupComplete = false, phoneKeyVault = "", phoneEpoch = 0, transferApprovedAtMs = null,
                        transferKeepMs = null, finishMovePending = false, movedIn = false, recoveryRSeen = false,
                        capabilities = emptyList(), timeScale = null, unreachableAfterMs = null,
                    )
                }
            }
            finishMoveFlow.value = false
        }
        steps.finish()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun observe(s: Session) {
        observer?.cancel()
        observer = scope.launch {
            // A collection that ended (a terminal relay error) is started again, with backoff: without it nothing
            // from the vault arrives until the next cold start.
            // A child of this observer: a restart that failed would end the observer, its event collector too.
            CollectorSupervisor(this, restart = {
                surviveEvent("mailbox restart", ::logSurvived, { isActive }) {
                    android.util.Log.i("VaultManager", "restarting the mailbox collector after ${s.device.collectionEnded.value}")
                    s.device.start(scope)
                    ownerCheck.onRelayReconnected()
                    // A relay reconnect: what changed in the feed meanwhile (ANDROID-PLAN 0.1.23, 2).
                    if (phaseFlow.value == AppPhase.Unlocked) feed.catchUp()
                    // §11.14.9: and an unresolved change is checked again.
                    if (phaseFlow.value == AppPhase.Unlocked && local.unresolvedChange != null) changes.kick()
                }
            }).watch(s.device.collectionEnded)
            launch { s.device.vaultRefusals.collectSurviving("refusals") { n -> refusals.onRefusals(n, phaseFlow.value) } }
            launch { s.device.ownerCheckRequired.collectSurviving("owner check required") { ownerCheck.onRequired() } }
            // A vault.locking or vault.held arrives here (onEvent): one failure on one event never ends the observer.
            s.device.events.collectSurviving("event") { m ->
                // (0.1.78) A 410 without the vault's word: only the vault's own `vault_deleted` is taken, and it erases at once.
                if (deletionEvents.take(m)) return@collectSurviving
                // (#244 review B3) A vault.locking during an unlock: judged after it.
                if (unlockNotices.hold(m) { takeEvent(m) }) return@collectSurviving
                takeEvent(m)
            }
        }
    }

    /** One event from the vault, after the deletion check (and, for a `vault.locking` during an unlock, after it). */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun takeEvent(m: VaultMessage) {
        if (lockNotices.stale(m)) {
            // The lock before this phone's last unlock (an update's first step, or the lock an unlock of a
            // running vault makes), relayed late: the vault is open.
            android.util.Log.i("VaultManager", "dropped a vault.locking from before the last unlock")
            return
        }
        onEvent(m)
        // A lock is pending: the vault may be open and still sending, but on this phone it is locked.
        // Nothing of what it sends is taken into the app's lists (an unlock reads them again).
        if (!lockGuard.takesEvents()) return
        try {
            items.onEvent(m)
            sharing.onEvent(m)
            devices.onEvent(m)
            votes.onEvent(m)
            pairing.onEvent(m)
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

    /** A `vault.locking` (not a stale one): this phone is locked, before anything else of the event is taken. */
    /** A replacement proof while a 410 stands: the wipe as always (`transferred` ends a kept transfer). */
    private fun holderWhile410(m: VaultMessage) {
        val reason = VaultJson.str(m.body, "reason")
        if (local.transferApprovedAtMs != null && reason == HolderPolicy.REASON_TRANSFERRED) wipeMovedAway = true
        if (!holder.onVaultEvent(m)) wipeMovedAway = false
    }

    private fun lockedByNotice() {
        windowFlow.value = null
        if (phaseGate.lockedByVault()) return
        if (waking.get() > 0 && !lockedHere && phaseFlow.value !is AppPhase.SignedOut) {
            // A process started by the lock's own wake (§14.2 trigger 3) never saw the vault open: the notice is live
            // (ephemeral, 60 s) and not this phone's lock, which sends no wake.
            lockedElsewhereFlow.value = true
        }
    }

    @Suppress("CyclomaticComplexMethod") // one branch per event the app follows
    private suspend fun onEvent(m: VaultMessage) {
        // The lock first (fail secure): nothing before it that throws may skip it.
        if (m.type == "vault.locking") lockedByNotice()
        ownerCheck.onEvent(m)
        when (m.type) {
            // §3.6.3: entering the hold ends the credential's unlock window.
            "vault.held" -> windowFlow.value = null
            // The vault says it is locking, after this phone's last unlock: a lock of this phone that was in doubt no
            // longer is. (A notice from before that unlock was dropped; one whose place in time is not known, because
            // no unlock time is kept, confirms nothing: the status read will.)
            "vault.locking" -> {
                if (lockNotices.confirms(m, lockKeeper)) lockKeeper.confirmed()
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
            } else if (VaultJson.str(m.body, "kind") == SYNC_VAULT_RELEASE) {
                // ANDROID-PLAN 0.1.54: the vault first ran under N+1, which confirms the move; the way back is gone.
                val d = session?.device
                val named = VaultJson.str(m.body, "release") to VaultJson.long(m.body, "release_number")
                if (d != null && MoveMarkers.confirmedBy(moveFlow.value, named.first, named.second)) {
                    scope.launch {
                        runCatching { d.clearMove() }
                        syncMove()
                    }
                }
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
            // `vault_deleted` (§12.5 step 1) closes the vault at once (ANDROID-PLAN 0.1.54); no reason is re-read like any
            // change of the vault.
            HolderPolicy.DEVICE_UNLINKED -> {
                if (VaultJson.str(m.body, "reason") == REASON_VAULT_DELETED) {
                    startDeleting()
                    return
                }
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
        // ANDROID-PLAN 0.1.54: a vault being deleted is only read again (its status), nothing else runs.
        if (phaseFlow.deleting) {
            scope.launch { deletionWatch.check() }
            if (deletedApiMarker != null) session?.device?.ensureCollecting()
            return
        }
        // Release R (§11.14.10): the 7 days of a kept transfer are also checked on return, not only at a start.
        if (local.transferApprovedAtMs != null) scope.launch { runCatching { checkKeptTransfer() } }
        val d = session?.device ?: return
        if (d.ensureCollecting()) android.util.Log.i("VaultManager", "mailbox collector restarted on return to the foreground")
        // Not while a lock is pending: nothing but the lock is sent to a vault that counts as locked.
        // Not while this phone is held locked (a lock pending, or one the vault said was over: release L).
        if (lockGuard.admits()) scope.launch { runCatching { d.flushOutbox() } }
        val t = Instant.now()
        val due = ownerCheck.ownerCheck.value?.let { it.gated(t) || it.warning(t, timeScaleFlow.value.scale) } == true
        if (due && phaseFlow.value is AppPhase.Unlocked) ownerCheck.onOpened(afterUnlock = false)
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
            // STAGING-TIME-SCALE (S12): the bound as divided by the time scale when the last contact started it.
            val after = local.unreachableAfterMs ?: StuckScreens.LONG_UNREACHABLE.toMillis()
            AppPhase.Unreachable(kind, StuckScreens.longUnreachable(kind, last, System.currentTimeMillis(), after), after / MS_PER_SECOND)
        }
        phaseGate.publishAfter(gen, p) // a lock, a refusal or a wipe meanwhile: what it published stands
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
            syncTimeScale()
            if (localUnreadable) throw VaultFailure(FailureKind.LOCAL_STATE, CODE_KEYSTORE)
            publishAccount()
        }
        if (local.replaced != null) {
            // A phone the build before the wipe marked replaced (its device keys already forgotten): erase it now.
            holder.wipeNow()
            return@guard AppPhase.SignedOut
        }
        (local.deleting ?: deletingMarker)?.let { m ->
            // ANDROID-PLAN 0.1.54: "being deleted" before anything is read; the status is re-read at every start.
            if (!phaseFlow.deleting) phaseFlow.deleting(AppPhase.Deleting(slow = VaultDeletion.pastBound(m)))
            deletionWatch.start(m, readNow = true)
            return@guard phaseFlow.value
        }
        (local.deletedApi ?: deletedApiMarker)?.let { m ->
            // ANDROID-PLAN 0.1.78: "Your vault was deleted" before anything is read; the status is read at every start and the
            // mailbox keeps being collected (the vault's event can still arrive there).
            deletedApiMarker = m
            if (!phaseFlow.deleting) phaseFlow.deleting(AppPhase.Deleting(slow = VaultDeletion.pastBound(m.watch()), apiOnly = true))
            deletionWatch.start(m.watch(), readNow = true)
            scope.launch { runCatching { session(mailboxOnly = true).device.ensureCollecting() } }
            return@guard phaseFlow.value
        }
        // Nothing redeemed or claimed on this phone (and nothing re-created after a wipe): the welcome screen.
        if (local.userGuid.isEmpty() && !local.transferIn) return@guard AppPhase.SignedOut
        val s = session()
        val d = s.device
        // (A6 audit N1) A transfer to this phone that is not finished: completed if the vault paired it (also when the
        // credential steps failed or the process ended on the way), shown again while it waits for the approval. One
        // whose hs.init was never answered (no code shown, no hs.fin sent) cannot complete: dropped.
        val pendingIn = d.transferInPending
        when (TransferInResume.decide(pendingIn, d.deviceId, Instant.now())) {
            TransferInResume.Action.COMPLETE -> {
                completeTransferIn(s, record.recordGeneration, pendingIn?.userGuid)
                return@guard AppPhase.Setup(SetupStage.FINISHING)
            }
            // Not running at the deadline (stopped, killed, the phone off): the collect and the drop now, first.
            TransferInResume.Action.DEADLINE -> atTransferDeadline(s)?.let { t ->
                completeTransferIn(s, record.recordGeneration, t.userGuid)
                return@guard AppPhase.Setup(SetupStage.FINISHING)
            }
            TransferInResume.Action.WAIT -> return@guard AppPhase.Setup(SetupStage.TRANSFER_WAITING)
            TransferInResume.Action.DROP -> d.abandonTransfer()
            TransferInResume.Action.NONE -> Unit
        }
        if (d.deviceId == null) {
            if (d.recovering) return@guard AppPhase.Setup(SetupStage.RECOVERING) // registered with a recovery code (§11.11.3)
            if (d.vaultId != null) return@guard AppPhase.Setup(SetupStage.NEEDS_CREDENTIAL) // enrolled, handshake not finished
            // (A6 audit N3) An enrollment posted earlier is resumed, never posted over (and the PIN is not asked again).
            if (d.pendingEnroll != null && local.userGuid.isNotEmpty()) return@guard AppPhase.Setup(SetupStage.ENROLLING)
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
        if (!VaultDeletion.statusUnlocked(st.state)) AppPhase.Locked else afterUnlocked(s)
    }

    @Suppress("ReturnCount")
    private suspend fun afterUnlocked(s: Session): AppPhase {
        if (s.device.recovering) return AppPhase.Setup(SetupStage.RECOVERING)
        if (s.device.credentialVersion == null) {
            // (A6 audit N1) A transfer the vault paired takes the credential over; it never creates one (`exists`).
            s.device.transferInPending?.takeIf { it.paired }?.let { t ->
                completeTransferIn(s, record.recordGeneration, t.userGuid)
                return AppPhase.Setup(SetupStage.FINISHING)
            }
            return AppPhase.Setup(SetupStage.NEEDS_CREDENTIAL)
        }
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
        if (!Rules.mayForget(undecidedNow())) return // K is never forgotten while an open's answer is unknown
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
            // And the own account's: an answer on its way when the vault closes is not kept ([OwnAccount]).
            val mark = ownAccount.mark()
            val v = guard { session().openApi.accountGet() }
            // What is new is decided under the own account's lock (`as_of`): an older answer never replaces a newer one.
            val kept = ownAccount.keep(mark, v, ::ownProfileOpen) ?: return
            val u = kept.update
            val stored = kept.before
            io { record.refreshed(rec, u) }
            publishAccount()
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

    override suspend fun enroll(pin: String, onStep: (EnrollStep) -> Unit) {
        // (Review M1) A run without a PIN (a resume) joined here that made no vault: nothing was enrolled with [pin].
        if (!enrolment(pin, onStep)) throw VaultFailure(FailureKind.RETRY, CODE_ENROLL_NOT_MADE)
    }

    override suspend fun resumeEnroll(onStep: (EnrollStep) -> Unit): Boolean = enrolment(null, onStep)

    override val enrollPending: Boolean get() = session?.device?.pendingEnroll != null

    /** (A6 audit N3) The enrollment running now, joined by a second call instead of being run twice. */
    private var enrolmentRun: kotlinx.coroutines.Deferred<Boolean>? = null

    /**
     * (Review F1) The enrollment request this process posted last, with the SHA-256 of its PIN: memory only, so that
     * after a restart, and for any other PIN, the request is resumed without a PIN and never accepted for a new one.
     */
    @Volatile
    private var postedEnrol: Pair<String, ByteArray>? = null

    /** (Review m1) The posted PIN's digest goes, zeroed: the vault is pinned, the request dropped, or the session gone. */
    private fun forgetPostedPin() {
        postedEnrol?.second?.fill(0)
        postedEnrol = null
    }

    private fun pinDigest(pin: String): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
    private val enrolmentLock = Mutex()

    /**
     * (A6 audit N3) Enrollment through [EnrolmentResume], in the app's [scope] (leaving the screen does not stop it half
     * way; a second call while one runs joins it). True once the vault is pinned and the first handshake done; false when
     * nothing outstanding made a vault and no [pin] was given (the member chooses one).
     */
    private suspend fun enrolment(pin: String?, onStep: (EnrollStep) -> Unit): Boolean {
        val run = enrolmentLock.withLock {
            enrolmentRun?.takeIf { it.isActive } ?: scope.async { guard { enrolOnce(pin, onStep) } }.also { enrolmentRun = it }
        }
        try {
            return run.await()
        } catch (e: VaultFailure) {
            // (Review F1) A failure with a request still outstanding: every screen from now on (a new one after Back, or
            // after a restart) resumes it without a PIN, and never asks for a new PIN that the vault might not hold.
            if (session?.device?.pendingEnroll != null) {
                phaseFlow.setIf(AppPhase.Setup(SetupStage.ENROLLING)) { phaseFlow.value == AppPhase.Setup(SetupStage.NEW_VAULT) }
            }
            throw e
        }
    }

    @Suppress("ThrowsCount") // one failure per outcome of the resume
    private suspend fun enrolOnce(pin: String?, onStep: (EnrollStep) -> Unit): Boolean {
        val rec = record.recordGeneration
        val s = session()
        val d = s.device
        onStep(EnrollStep.ENROLL)
        val ops = object : EnrolmentResume.Ops {
            override fun vaultPinned() = d.hasVault && d.vaultId != null
            override fun pending() = d.pendingEnroll
            override suspend fun answer(requestId: String) = s.alt.enrollAnswer(d, requestId)
            override suspend fun markAnswered() = d.markEnrollAnswered()
            override suspend fun drop() = d.dropPendingEnroll()
            override fun postedHere(requestId: String, pin: String): Boolean =
                postedEnrol?.let { (id, h) ->
                    val typed = pinDigest(pin)
                    try {
                        id == requestId && java.security.MessageDigest.isEqual(h, typed)
                    } finally {
                        typed.fill(0)
                    }
                } == true
            override fun forgetPin() = forgetPostedPin()
            override fun relayMark(): Long = System.nanoTime()
            override fun relayReadSince(mark: Long): Boolean = d.relayReadSince(mark)
            @Suppress("SwallowedException") // no vault.enrolled in time is an answer: false
            override suspend fun awaitEnrolled(): Boolean {
                onStep(EnrollStep.WAIT_FOR_VAULT)
                return try {
                    d.awaitEnrolled()
                    true
                } catch (_: TimeoutCancellationException) {
                    false
                }
            }

            override suspend fun post(pin: String): EnrolmentResume.Posted {
                // §11.10.9: the instance's capabilities choose the shape. Release R (§11.14.1): a phone key and its secret
                // of epoch 1 in the request; the state is stored before the request is sent, and a retry of this
                // enrollment (a lost answer, a crash) reuses the key: the vault may already hold its secret. Without
                // `recovery_r`: as before.
                val out = try {
                    s.alt.enroll(d, local.vaultId, local.userGuid, pin, attester()) { traits ->
                        val r = choosePath(traits, makesPhoneKey = true, rec) == ReleasePath.Path.R
                        if (r) phoneSecrets.secret(keepPhoneKey(local.vaultId, rec)) else null
                    }
                } finally {
                    // (Review F1) The request stored for this post, with its PIN's digest, in memory only.
                    forgetPostedPin()
                    postedEnrol = d.pendingEnroll?.let { it.requestId to pinDigest(pin) }
                }
                if (out.ok && out.vaultId.isNotEmpty() && out.vaultId != local.vaultId) {
                    io { updateLocal(rec) { it.copy(vaultId = out.vaultId) } }
                }
                return EnrolmentResume.Posted(out.ok, out.code)
            }
        }
        val r = try {
            EnrolmentResume(ops).run(pin)
        } catch (e: EnrolmentResume.Refused) {
            throw VaultFailure(enrollFailure(e.code), e.code, cause = e)
        } catch (e: EnrolmentResume.Waiting) {
            throw VaultFailure(FailureKind.ENROLL_WAITING, cause = e)
        } catch (e: EnrolmentResume.NotThisPin) {
            phaseGate.setupStage(AppPhase.Setup(SetupStage.ENROLLING))
            throw VaultFailure(FailureKind.RETRY, VaultRepository.CODE_ENROLL_PENDING, cause = e)
        } catch (e: EnrolmentResume.Unreachable) {
            throw VaultFailure(FailureKind.NETWORK, CODE_ENROLL_RELAY, cause = e)
        }
        if (r == EnrolmentResume.Result.NeedsPin) {
            phaseGate.setupStage(AppPhase.Setup(SetupStage.NEW_VAULT))
            return false
        }
        if (d.deviceId == null) {
            onStep(EnrollStep.HANDSHAKE)
            d.completeEnrollment()
        }
        return true
    }

    override suspend fun createCredential(password: String, backup: Boolean, onStep: (EnrollStep) -> Unit) =
        createCredential(password, backup, onStep, null)

    /** (Release R, §11.14.9) `credential.create` carries the PIN, which the vault verifies and with which it arms recovery. */
    override suspend fun createCredential(
        password: String,
        backup: Boolean,
        onStep: (EnrollStep) -> Unit,
        pin: String?,
    ) = guard {
        // Read before the vault's calls: a lock, a refusal or a wipe from here on keeps the phase it published.
        val gen = generation
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
        publishFinishing(gen)
    }

    /**
     * FINISHING after setup steps that suspend (the end of enrolment, a recovered credential, a transfer to this phone):
     * not over what a lock, a refusal or a wipe since [gen] published, nor while a lock is pending (#242). FinishSetupGate
     * would otherwise open the app from it over a locked vault.
     */
    private fun publishFinishing(gen: Int) {
        phaseGate.publishFinishing(gen)
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
        finishSetupGated()
    }

    /**
     * [finishSetup]; false when its gate refused (nothing written, the phase left as it is), or when a lock or a wipe
     * came while the record was written (the record says setup is complete, but the phase that lock or wipe published
     * stands: never Unlocked over it).
     */
    @Suppress("ReturnCount") // the gate, then the checks after the write
    private suspend fun finishSetupGated(): Boolean {
        // Read before the gate: a lock asked for from here on bumps it (as at the end of an unlock).
        val gen = generation
        val rec = record.recordGeneration
        if (!FinishSetupGate.allows(phaseFlow.value, lockPending = lockKeeper.holdsPhone, finishingMove = finishingMove.value)) return false
        io { updateLocal(rec) { it.copy(setupComplete = true, movedIn = false) } }
        // Both checks after the write, which suspends: the generation (a lock or a wipe meanwhile) and the guard (a lock
        // pending now), with the publish as one step (#242).
        return phaseGate.publishAfter(gen, AppPhase.Unlocked)
    }

    override suspend fun preflight(): PreflightInfo {
        val s = guard { session() }
        val p = try {
            guard { s.alt.preflight(s.device.altState) }
        } catch (e: VaultFailure) {
            // ANDROID-PLAN 0.1.54 point 6: still routed to the release the vault moved from, after the retries.
            noteNewReleaseFailure(e.kind, e.code, sealed = false)
            throw e
        }
        // (0.1.54 point 4) The release an unconfirmed move left is no longer in the trusted manifest: no way back.
        if (!p.movedFromListed) {
            runCatching { s.device.clearMove() }
            syncMove()
        }
        return PreflightInfo(
            view(p.routed), p.lastNumber, p.softwareUpdated, p.rollback, p.offer?.let { view(it) }, p.between.map { view(it) },
        )
    }

    override suspend fun unlock(pin: String, approve: ReleaseView?, cancelRecovery: Boolean): UnlockAttempt =
        unlockOnce(pin, approve, cancelRecovery, holderApp = true, twoStepScreen = true)

    /**
     * A locked vault past its owner-check deadline (§3.6.5): unlocks with [pin], reads `vault.status` and sends
     * the check with [pin] and [password] before the app opens, so that the member types both once.
     */
    override suspend fun unlockWithCheck(pin: String, password: String, approve: ReleaseView?, cancelRecovery: Boolean): UnlockAttempt =
        // The foreground generation before the request: leaving and coming back while it runs keeps no PIN.
        ownerCheck.foregroundGeneration.let { gen ->
            unlockOnce(pin, approve, cancelRecovery, holderApp = true, twoStepScreen = true) { ownerCheck.afterUnlock(pin, password, gen) }
        }

    /**
     * (Release R, ANDROID-PLAN "Other screens") An unlock with `cancel_recovery`, then at once the owner check with the
     * PIN and the password, which records that the member is present (§11.14.5).
     */
    override suspend fun unlockCancellingRecovery(pin: String, password: String, approve: ReleaseView?): UnlockAttempt =
        unlockOnce(pin, approve, cancelRecovery = true, holderApp = true, twoStepScreen = true) {
            ownerCheck.afterCancelledRecovery(pin, password)
        }

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
        /** (0.45.0 §11.14.9 step 5) Only the unlock screen's entry points: it asks for the previous PIN, then the new one. */
        twoStepScreen: Boolean = false,
        /** A PIN typed on a flow's own screen ("Finish the move"), taken at this [pinGeneration] ([unlockStep]). */
        pinTaken: Int? = null,
        /** Relay requests before the app opens (the owner check); run in the [opening] window. */
        beforeOpen: (suspend () -> Unit)? = null,
    ): UnlockAttempt = unlockStep(
        pin,
        UnlockOptions(approve = approve?.let { Approval(it.pcr0, it.number) }, cancelRecovery = cancelRecovery),
        followMove = true,
        holderApp = holderApp,
        beforeOpen = beforeOpen,
        twoStepScreen = twoStepScreen,
        pinTaken = pinTaken,
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
    @Suppress("LongParameterList") // unlockStepHeld's
    private suspend fun unlockStep(
        pin: String,
        opts: UnlockOptions,
        followMove: Boolean,
        holderApp: Boolean = false,
        release: String? = null,
        onStarting: ((Long) -> Unit)? = null,
        beforeOpen: (suspend () -> Unit)? = null,
        twoStepScreen: Boolean = false,
        pinTaken: Int? = null,
    ): StepOutcome {
        // (#244 review B3) A vault.locking received from here on is judged after this unlock: its own (§11.4) is stale then.
        val ticket = unlockNotices.begin()
        try {
            return unlockStepHeld(pin, opts, followMove, holderApp, release, onStarting, beforeOpen, twoStepScreen, pinTaken, ticket)
        } finally {
            withContext(NonCancellable) { takeHeldNotices(ticket) }
        }
    }

    /** The notices [UnlockNoticeHold] kept for [ticket]'s unlock, taken as if they had just arrived (at most once). */
    private suspend fun takeHeldNotices(ticket: UnlockNoticeHold.Ticket) {
        for (take in ticket.end()) {
            try {
                take()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                android.util.Log.w("VaultManager", "held notice not handled: ${e.javaClass.simpleName}")
            }
        }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongParameterList", "LongMethod") // one answer per §11.4 result code
    private suspend fun unlockStepHeld(
        pin: String,
        opts: UnlockOptions,
        followMove: Boolean,
        holderApp: Boolean = false,
        release: String? = null,
        onStarting: ((Long) -> Unit)? = null,
        beforeOpen: (suspend () -> Unit)? = null,
        /** The unlock screen, which asks for the previous PIN and then the new one (§11.14.9 step 5). */
        twoStepScreen: Boolean = false,
        /**
         * (§11.14.9 step 2; chosen, not in the spec) A PIN typed on a flow's own screen (the release update, "Finish the
         * move"), taken at this [pinGeneration]: once that moved, nothing is sent, checked before the request is built
         * and again right before each one is posted (retries and candidate tries included). Null: the unlock screen.
         */
        pinTaken: Int? = null,
        ticket: UnlockNoticeHold.Ticket,
    ): StepOutcome {
        ownerCheck.beginUnlock() // a count kept for an earlier attempt is not carried into this one (ANDROID-PLAN 0.1.96)
        // §6.7.1 (0.17.0): a phone a transfer set up from an older vault release has no user_guid (§11.4 needs it).
        if (local.userGuid.isEmpty()) return StepOutcome(UnlockAttempt.Failed(FailureKind.NOT_SUPPORTED, UnlockAttempt.CODE_NO_USER_GUID))
        // (0.44.0, §11.14.9 step 5; H1) With a PIN change unresolved only the unlock screen's two steps know which PIN was
        // typed: any other unlock (the release update's) sends nothing.
        if (!UnlockEpoch.allowed(pinChangeUndecided(), twoStepScreen)) {
            return StepOutcome(UnlockAttempt.Failed(FailureKind.CHANGE_UNRESOLVED, ChangeResolver.CODE_UNRESOLVED))
        }
        pinTakenBefore(pinTaken)?.let { return StepOutcome(UnlockAttempt.Failed(it.kind, it.code)) }
        // Read before the unlock: a lock asked for while it is on its way bumps it, and this unlock then publishes no phase.
        val gen = generation
        // The record this unlock writes to: after a wipe meanwhile, nothing of it is (RecordStore).
        val rec = record.recordGeneration
        var moved = false
        val outcome = try {
            // Never beside a lock attempt; and once the vault has opened, no older lock is ever sent (LockKeeper).
            // On the IO dispatcher: an unlock that supersedes a pending lock removes its marker from the phone's store.
            io {
              // (Release L) A lock asked for during this unlock is bound to the opening it makes (none: as before L).
              lockKeeper.unlocking(
                  opened = { it.result.ok },
                  unlockIdOf = { o -> o.result.unlockId.takeIf { o.result.token != null } },
              ) {
                guard {
                    val s = session()
                    var out: UnlockOutcome = unlockWithSecret(s, pin, opts, release, onStarting, pinTaken)
                    if (out.ok && out.result.update?.result == UPDATE_MOVED) {
                        moved = true
                        // §11.10.6: after a move the vault is locked under the new release; unlock again, which reaches it.
                        if (followMove) out = unlockWithSecret(s, pin, UnlockOptions(), null, onStarting, pinTaken)
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
            // ANDROID-PLAN 0.1.54 point 2: after a move, N+1 failing for a reason not the member's offers the way back.
            if (release == null && !opts.abandon) noteNewReleaseFailure(e.kind, e.code, sealed = false) else syncMove()
            return StepOutcome(UnlockAttempt.Failed(e.kind, e.code, e.retryAfterSeconds))
        }
        val r = outcome.result
        // The result may have stored, cleared or kept the marker of an unconfirmed move (AltRequests.applyUnlock).
        syncMove()
        if (!r.ok) {
            if (release == null && !opts.abandon) noteNewReleaseFailure(FailureKind.OTHER, r.code, sealed = true)
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
        // Before the checks below: what came during the unlock, judged now (its own lock notice is stale; a later one
        // moves the generation, and this unlock is overtaken).
        takeHeldNotices(ticket)
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
        val opened = if (overtaken) {
            // (Not after a wipe, which also bumps the generation and has said SignedOut.)
            if (lockedSince) phaseFlow.value = AppPhase.Locked
            false
        } else {
            openAfterUnlock(s, gen, beforeOpen?.takeUnless { s.device.recovering })
        }
        // "The move didn't finish" (release R, §11.14.10): this phone opened the vault with its own keys after it
        // approved a transfer, so it is the vault's phone again.
        if (!overtaken) clearKeptTransfer(rec)
        // "Finish the move" (release R, §11.14.10): this new phone opened the vault from storage with its own secret.
        if (local.finishMovePending && FinishMoveProof.provedBy(r.ok, overtaken)) {
            io { updateLocal(rec) { it.copy(finishMovePending = false) } }
            finishMoveFlow.value = false
        }
        val attempt = when {
            // (#244 review B3) Never Success when nothing opened: a lock (pending, or the vault's own) or a wipe came first.
            !opened -> UnlockAttempt.Failed(
                FailureKind.OTHER,
                if (lockedSince) MoveRepository.CODE_LOCK_PENDING else MoveRepository.CODE_NOT_OPENED,
            )
            refused != null -> UnlockAttempt.UpdateRefused(refused.code ?: "refused")
            else -> UnlockAttempt.Success
        }
        return StepOutcome(attempt, abandoned = r.update?.result == UPDATE_ABANDONED)
    }

    /**
     * The open after an unlock that was not overtaken: [before] (relay requests before the app opens), then the phase.
     * [before]'s requests are answered while this phone is still locked ([OpeningWindow]): the collection runs, and what
     * else the vault sends is held back until the app opens.
     */
    private suspend fun openAfterUnlock(s: Session, gen: Int, before: (suspend () -> Unit)?): Boolean {
        var opened = false
        val openApp: suspend () -> Unit = {
            val p = afterUnlocked(s)
            // Both checks: the generation (a lock or a wipe since this unlock began) and the guard (a lock pending now),
            // with the publish as one step (#242).
            opened = phaseGate.publishAfter(gen, p)
        }
        try {
            if (before == null) {
                openApp()
            } else {
                opening.run {
                    before()
                    openApp()
                }
            }
        } finally {
            // Whatever ended the open, a count kept for it is not carried to the next one (ANDROID-PLAN 0.1.97).
            ownerCheck.beginUnlock()
        }
        return opened
    }

    /**
     * One unlock with this phone's unlock secret when the vault has one (release R, §11.14.1). An answer
     * `device_secret` is tried once more with the next epoch (the answer to a change of secret may have been lost);
     * when that opens the vault, the phone moves to that epoch. A missing phone key is [FailureKind.DEVICE_SECRET].
     */
    private suspend fun unlockWithSecret(
        s: Session,
        pin: String,
        opts: UnlockOptions,
        release: String?,
        onStarting: ((Long) -> Unit)?,
        pinTaken: Int? = null,
    ): UnlockOutcome {
        // (ANDROID-PLAN §4 step 5) Signed with the identity's newest key; on a readable `attestation` (decided before the PIN,
        // not counted) with its other keys, newest first, each once. Outside a recovery the key answered `ok` is the vault's
        // binding: the identity's other keys that served no registration go.
        val slot = slots.active
        return slots.attestation.tryKeys(
            slot,
            attempt = { alias -> unlockWithSecretOnce(s, pin, opts, release, onStarting, pinTaken, attester(slot, signWith = alias)) },
            code = { it.result.code },
            ok = { it.ok },
            onOk = { alias -> if (!s.device.recovering) io { slots.attestation.keepAfterUnlock(slot, alias) } },
        )
    }

    /** One [unlockWithSecret], signed by [signer]. */
    @Suppress("ReturnCount", "LongParameterList")
    private suspend fun unlockWithSecretOnce(
        s: Session,
        pin: String,
        opts: UnlockOptions,
        release: String?,
        onStarting: ((Long) -> Unit)?,
        pinTaken: Int?,
        signer: com.vettid.core.altchan.Attester,
    ): UnlockOutcome {
        val phone = local.phone
        // Right before each request is posted: a held-back unlock would otherwise still go out (and may mint a token).
        val beforePost: () -> Unit = { pinTakenBefore(pinTaken)?.let { throw it } }
        // §11.10.9: the routed instance's capabilities choose the shape; an instance without `recovery_r` for a vault
        // known to have it is refused before anything is sent.
        var r = false
        // The record this unlock writes to: after a wipe meanwhile, nothing of it is (RecordStore).
        val rec = record.recordGeneration
        // (0.44.0, §11.14.9 step 5) A PIN change is unresolved: the PIN typed after `device_secret` is the NEW one, and it
        // is tried with the candidate (the next epoch) only; the previous PIN is never reused for it ([UnlockEpoch.run]).
        val twoStep = pinChangeUndecided()
        return UnlockEpoch.run(
            pinChangeUnresolved = twoStep,
            newPinAsked = newPinStep,
            send = { ahead ->
                val o = s.alt.unlock(s.device, local.userGuid, pin, signer, opts, release, onStarting, beforePost) { traits ->
                    val path = choosePath(traits, makesPhoneKey = false, rec)
                    // An unlock never sets the mark on a phone without a phone secret (it then fails without one, unmarked).
                    if (ahead == 0) r = path == ReleasePath.Path.R
                    if (ahead > 0) phoneSecrets.secret(phone, ahead = ahead.toLong()) else if (r) phoneSecrets.secret(phone) else null
                }
                o to UnlockEpoch.Answer(o.ok, o.result.code, r)
            },
            setNewPinAsked = { newPinStep = it },
            confirmNext = { ahead ->
                // The vault holds that candidate: the phone moves to it, and an uncertain PIN or unconfirmed renew is
                // settled. A change still unresolved is decided by the check after the open (§11.14.9 step 5).
                io { record.settlePin(rec, phone.epoch + ahead) }
                refreshChangeFlows()
            },
            // (0.45.0 step 5) After `device_secret` with the previous PIN: an unanswered renew's candidate first, with it.
            previousPinCandidates = if (twoStep && local.renewUnconfirmed) listOf(1) else emptyList(),
            currentOk = {
                // `ok` with the current record: the vault holds the previous PIN (an uncertain PIN is settled); it does
                // not decide an unresolved change.
                io { record.settlePin(rec) }
                refreshChangeFlows()
            },
        )
    }

    /**
     * §11.10.9: the request's shape from the capabilities of the instance just verified ([ReleasePath.choose]); the
     * mark, the last capabilities and the time scale are stored, into the record of [since]. A vault known to have release R and an
     * instance without it: refused.
     */
    private fun choosePath(traits: ReleaseTraits, makesPhoneKey: Boolean, since: Int): ReleasePath.Path {
        // STAGING-TIME-SCALE §4 item 4: a time scale this build does not accept never gets here (the alternate channel
        // refuses such a descriptor, the vault client such a transfer bundle, before anything is sent).
        val capabilities = traits.capabilities
        val path = ReleasePath.choose(capabilities, local.releaseRKnown)
        val seen = ReleasePath.marks(local.recoveryRSeen, path, makesPhoneKey = makesPhoneKey, holdsPhoneSecret = local.phone.inUse)
        // §11.10.9: the time scale is stored per vault with the capabilities; the app's own waits start from it.
        if (seen != local.recoveryRSeen || capabilities != local.capabilities || traits.timeScale != local.timeScale) {
            updateLocal(since) { it.copy(recoveryRSeen = seen, capabilities = capabilities, timeScale = traits.timeScale) }
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
            // ANDROID-PLAN 0.1.54 point 4: the release an unconfirmed move left is no longer in the trusted manifest.
            val from = st.movedFrom
            if (from != null && m.byPcr0(from.pcr0) == null) {
                runCatching { s.device.clearMove() }
                syncMove()
            }
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
            ownerCheckDue(
                ownerCheck.ownerCheck.value, ownerCheck.lockedByOwnerCheck.value, ownerCheck.lockCauseUnknown.value, Instant.now(),
            )

        // (0.44.0, §11.14.9; H1) No lock and unlock while a PIN or password change is undecided.
        override fun changeUnresolved(): Boolean = changeHoldFlow.value != null

        override val changeHold: StateFlow<FailureKind?> get() = changeHoldFlow

        override suspend fun lock() {
            this@VaultManager.lock()
            // An update moves on only from a vault that is known to be locked: not after one opened again since either.
            if (lockKeeper.pending || lockKeeper.openedAgain) throw VaultFailure(FailureKind.NO_RESPONSE, CODE_LOCK_PENDING)
            // The next unlock goes to the instance that holds the vault: wait (bounded) until its lease is gone.
            withTimeoutOrNull(LOCKED_WAIT_MS) {
                while (runCatching { guard { vaultStatus(session()) } }.getOrNull()?.state == "unlocked") {
                    kotlinx.coroutines.delay(LOCKED_POLL_MS)
                }
            }
        }

        override fun pinGeneration(): Int = record.pinGeneration

        @Suppress("ReturnCount") // nothing sent: a PIN taken before a change, or no release to return to
        override suspend fun unlock(pin: TakenPin, approve: ReleaseView?, abandon: Boolean, onStarting: (Long) -> Unit): UpdateStepResult {
            // (§11.14.9 step 2; chosen) Nothing is sent with a PIN taken before something moved which PIN the vault expects.
            pinTakenBefore(pin.generation)?.let { return UpdateStepResult.Failed(it.kind, it.code) }
            val previous = session?.device?.altState?.previousRelease?.takeIf { it.isNotEmpty() }
            val opts = UnlockOptions(approve = approve?.let { Approval(it.pcr0, it.number) }, abandon = abandon)
            val release = if (abandon) {
                previous ?: return UpdateStepResult.Failed(FailureKind.NOT_SUPPORTED, "no_previous_release")
            } else {
                null
            }
            var o = unlockStep(
                pin.pin, opts, followMove = false, holderApp = true, release = release, onStarting = onStarting, pinTaken = pin.generation,
            )
            val a = o.attempt
            if (abandon && a is UnlockAttempt.Failed && a.code == MemberApiException.VAULT_BUSY) {
                // §11.10.5: an instance of the new release holds the vault; lock it first, then return. Not with a PIN
                // taken before a change: nothing is locked or sent then.
                pinTakenBefore(pin.generation)?.let { return UpdateStepResult.Failed(it.kind, it.code) }
                runCatching { this@VaultManager.lock() }
                o = unlockStep(
                    pin.pin, opts, followMove = false, holderApp = true, release = release, onStarting = onStarting,
                    pinTaken = pin.generation,
                )
            }
            val failed = o.attempt
            if (abandon && failed is UnlockAttempt.Failed && failed.kind == FailureKind.RELEASE_ENDED) {
                // ANDROID-PLAN 0.1.54 point 4: `410 release_unavailable` for N's PCR0: release N ended, no way back.
                session?.device?.let { d -> runCatching { d.clearMove() } }
                syncMove()
            }
            return o.toStepResult()
        }

        override fun canAbandon(): Boolean = session?.device?.altState?.previousRelease?.isNotEmpty() == true

        override fun moveMarker(): MoveUnconfirmed? = session?.device?.altState?.let { MoveUnconfirmed.of(it) }
    }

    override fun returnToPreviousRelease(pin: String): Boolean = releaseUpdate.returnToPrevious(pin)

    /**
     * (ANDROID-PLAN 0.1.54 point 2) An unlock after the move failed with [kind] and [code] ([sealed]: the enclave's own
     * answer): the marker's `failed` is set when the reason is not the member's ([MoveMarkers.setsFailed]).
     */
    private suspend fun noteNewReleaseFailure(kind: FailureKind, code: String?, sealed: Boolean) {
        val d = session?.device ?: return
        if (d.altState.movedFrom == null || !MoveMarkers.setsFailed(kind, code, sealed)) return
        runCatching { d.markMoveFailed() }
        syncMove()
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
            // Never reached from the update (it sends nothing while a change is unresolved); a backstop all the same: no PIN
            // typed outside the unlock screen's two steps is ever tried with the candidate.
            UnlockAttempt.NewPinNeeded -> {
                newPinStep = false
                UpdateStepResult.Failed(FailureKind.CHANGE_UNRESOLVED, CODE_NEW_PIN_NEEDED)
            }
        }
    }

    /**
     * Locks the vault. **A lock once asked for completes**: it runs in the manager's own scope, so a caller whose
     * scope ends (the screen went away) only stops waiting ([LockSequence]). This phone is locked from the first
     * line, in every outcome. Whether the vault is locked is another matter: known only when it acknowledged or its
     * status says so ([lockNotice] says which). An unconfirmed lock is not an error of the caller's: it is pending,
     * durably, and tried again.
     */
    override suspend fun lock() = lockWith(session?.device?.unlockId)

    /**
     * (Release L, owner decision 2026-10-24) "Lock it now" after "Your vault was opened again": the vault's status is
     * read for the opening running now, and the lock is bound to it; a status that cannot be read or names none gives
     * the unconditional lock (VAULT-LOCK Q3). The value the last lock was bound to is never reused: it was stale.
     */
    override suspend fun lockAgain() {
        // Pending, durably and unbound, before anything is read (second recheck of #190, D1): a tap is never lost to a
        // slow vault, a screen that goes away or a broadcast that ends. Returns here; the rest runs in the manager's scope.
        val mine = beginLock(null)
        // The mark is taken before the status is read (F1); the rest runs in the manager's scope.
        LockAgain.start(
            scope, lockKeeper, mine, LOCK_STATUS_WAIT,
            read = { lockSession()?.let { s -> io { s.lockTimeApi(LockTimeUse.LOCK_STATUS).status() }.unlockId } },
            then = { locking.run(lock = { lockKeeper.ask(mine) }, after = {}) },
        )
    }

    /** The member's lock, bound to [unlockId] (null: as before release L). */
    private suspend fun lockWith(unlockId: String?) {
        val mine = beginLock(unlockId)
        locking.run(lock = { lockKeeper.ask(mine) }, after = {})
    }

    /** This phone locked at once, and the lock pending durably ([LockKeeper.begin]); its number, for [LockKeeper.ask]. */
    private suspend fun beginLock(unlockId: String?): Long {
        lockedHere = true
        // (Release L) Not "locked itself" on a later unlock screen: the member locked it here.
        ownerCheck.onLockedHere()
        // (ANDROID-PLAN 0.1.57 (1)) "This phone can't use your vault" goes with the open vault.
        CredentialRequired.clear()
        // A phase being computed now (a refresh, the end of an unlock) is not published after this.
        record.bumpGeneration()
        windowFlow.value = null
        phaseFlow.value = AppPhase.Locked
        // Pending, durably, BEFORE anything else is done for the lock (the hooks below take up to two seconds): a
        // process that dies from here on starts locked and goes on trying, and from here on nothing but the lock
        // and a call's hang-up is sent to the vault and nothing it sends is taken in.
        // (Release L) Bound to the vault's current unlock as this phone knows it (memory only: from the unlock result
        // or the last vault.status); without one, as before L (VAULT-LOCK §3.6, Q3).
        return withContext(Dispatchers.IO + NonCancellable) { lockKeeper.begin(unlockId) }
    }

    override suspend fun overview(): VaultOverview = guard(resend = true) {
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
        noteStatusTimeScale(vs?.timeScale, rec)
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
                val clock = unknownForADay(unavailable, rec)
                RecoveryStatusView(
                    r.state, r.attempts?.used, r.counter == COUNTER_UNAVAILABLE,
                    unknownForADay = clock.urgent(System.currentTimeMillis()),
                    unknownForSeconds = clock.period / MS_PER_SECOND,
                )
            },
            recoveryOffered = ReleasePath.recoveryOffered(local.capabilities),
        )
    }

    override suspend fun changePin(pin: String, newPin: String) = guard {
        session().openApi.pinChange(pin, newPin)
    }

    /**
     * §11.14.5, §11.14.11: the clock of a recovery counter unavailable at every read, urgent after 24 hours. A read that
     * finds it available forgets the start; the first that does not records it (kept on the phone across restarts), with
     * the 24 hours divided by the vault's time scale then (STAGING-TIME-SCALE S8): a running clock keeps its period.
     */
    private suspend fun unknownForADay(unavailable: Boolean, since: Int): RecoveryUnknownClock.State {
        val now = System.currentTimeMillis()
        // A read-modify-write under the account record's lock: every status read comes here, beside the other writers.
        return io {
            var next = RecoveryUnknownClock.State()
            updateLocal(since) { cur ->
                val was = RecoveryUnknownClock.State(cur.recoveryUnknownSinceMs, cur.recoveryUnknownForMs)
                val n = RecoveryUnknownClock.advance(was, unavailable, now, cur.timeScale ?: com.vettid.core.crypto.altchan.TimeScale.REAL)
                next = n
                if (n != was) cur.copy(recoveryUnknownSinceMs = n.since, recoveryUnknownForMs = n.periodMs) else null
            }
            next
        }
    }

    /**
     * (VAULT-MESSAGING 0.43.0 §11.10.8, STAGING-TIME-SCALE §5.2) A `vault.status`'s `time_scale` is stored as the vault's
     * ([TimeScales.fromStatus]) when this build accepts it by the rule the descriptor is checked against: a phone updated
     * while its vault was open (which has not read a descriptor since) reckons the owner check's early warning (S2) and
     * the waits it starts from now on with the vault's scale from the first status read, not from the next unlock. Waits
     * already started keep the period stored with them. A value this build does not accept is not taken.
     */
    private suspend fun noteStatusTimeScale(reported: Long?, since: Int) {
        val accepts = session?.alt?.let { a -> a::acceptsTimeScale }
        if (reported == null || reported == local.timeScale || accepts == null) return
        if (TimeScales.fromStatus(local.timeScale, reported, accepts) == null) {
            android.util.Log.w("VaultManager", "vault.status time_scale $reported not taken by this build")
        } else {
            io { updateLocal(since) { cur -> TimeScales.fromStatus(cur.timeScale, reported, accepts)?.let { cur.copy(timeScale = it) } } }
        }
    }

    /** Every `vault.status` read that carries `recovery` (the owner check's too) moves the clock of [unknownForADay]. */
    private suspend fun noteRecoveryStatus(r: com.vettid.core.vault.RecoveryStatus?, since: Int) {
        if (r == null) return
        unknownForADay(RecoveryUnknownClock.unavailable(r.state, r.counter), since)
    }

    override suspend fun deleteVault(pin: String, password: String) {
        // ANDROID-PLAN 0.1.54: the deletion's bound is at least this request's `exp` plus 2 minutes (a credential
        // operation's exp is 10 minutes after it is sent, ChangeTypes.EXP; taken here on this phone's clock).
        deleteExpMs = System.currentTimeMillis() + com.vettid.core.vault.ChangeTypes.EXP.toMillis()
        try {
            guard { session().openApi.deleteVault(pin, password) }
        } catch (e: VaultFailure) {
            // Not answered or refused: no deletion of this phone's to bound (the vault's own event still starts one).
            if (!phaseFlow.deleting) deleteExpMs = null
            throw e
        }
        // The vault's `{}` follows its step 1 (§12.5), as `device.unlinked{vault_deleted}` does: "being deleted" until
        // the member API no longer lists the vault; then the welcome screen (a new vault needs a new setup code).
        startDeleting()
    }

    // --- a vault being deleted (ANDROID-PLAN 0.1.54) ---

    /**
     * `device.unlinked{vault_deleted}` (or this phone's own `vault.delete` answered): at once, over every screen, the open
     * vault closes and "Your vault is being deleted" shows ([PhaseHolder]); a release update or an operation in progress
     * stops. Then, in another coroutine (this may run in the event observer, which it ends): the marker, nothing more sent
     * to the vault or read from it, the vault's data and PIN dropped from memory, and the re-reads of the status.
     */
    private fun startDeleting() {
        // (0.1.78) The vault's own word with the member API's 410 standing: both signals, erase at once.
        if (deletedApiMarker != null) {
            scope.launch { finishDeleted() }
            return
        }
        if (phaseFlow.deleting) return
        val since = System.currentTimeMillis()
        val marker = DeletingMarker(session?.device?.vaultId ?: local.vaultId, since, VaultDeletion.untilMs(since, deleteExpMs))
        // What was on its way (a refresh, the end of an unlock) publishes nothing after this.
        record.bumpGeneration()
        deletingMarker = marker
        phaseFlow.deleting(AppPhase.Deleting())
        releaseUpdate.close()
        scope.launch { closeForDeletion(marker) }
    }

    private suspend fun closeForDeletion(marker: DeletingMarker) {
        // The marker first: a process that dies from here on starts in "being deleted". A write the record drops (a persist
        // that failed) is tried again; this process goes on from [deletingMarker] whatever becomes of it.
        val saved = io { VaultDeletion.saveMarker { record.update(record.recordGeneration) { it.copy(deleting = marker) } } }
        syncTimeScale()
        if (!saved) android.util.Log.w("VaultManager", "the deletion's marker could not be saved")
        // Nothing more is sent to the vault (no lock either) and nothing more is read from it; the app key and the device
        // keys stay until the vault is gone (the status is read with the app key). The session keys leave memory now.
        lockKeeper.abandon()
        val device = session?.device
        dropSession(forget = false)
        device?.let { d ->
            deletingDevice = d
            runCatching { d.dropKeysFromMemory() }
        }
        keptChange = null
        windowFlow.value = null
        alarmFlow.value = null
        lockedElsewhereFlow.value = false
        // Every step runs even if one throws (fail secure); the first failure is rethrown after the last.
        val steps = ClearSteps()
        steps.step { social.clear() }
        steps.step { attachments.reset() }
        steps.step { ownerCheck.clear() }
        steps.step { forgetOwnProfile() }
        steps.step { history.clear() }
        steps.step { items.clear() }
        steps.step { sharing.clear() }
        steps.step { feed.clear() }
        steps.step { devices.clear() }
        steps.step { votes.clear() }
        steps.step { deletionWatch.start(marker, readNow = false) }
        steps.finish()
    }


    /**
     * The member API said `410 vault_deleted` / `account_deleted` to a request this phone signed ([guard]); without the
     * vault's word. At once the vault's screens close ("Your vault was deleted"); the keys, the device session and the
     * relay mailbox stay. Nothing is erased here.
     */
    private fun apiDeleted(f: VaultFailure) {
        var plan: ApiDeletionStart.Plan? = null
        val marker = synchronized(deletedApiLock) {
            plan = ApiDeletionStart.plan(f, phaseFlow.value, phaseFlow.deleting) ?: return
            val since = System.currentTimeMillis()
            DeletedApiMarker(session?.device?.vaultId ?: local.vaultId, since, VaultDeletion.apiUntilMs(since), plan!!.code).also {
                record.bumpGeneration()
                deletedApiMarker = it
                phaseFlow.deleting(AppPhase.Deleting(apiOnly = true))
            }
        }
        releaseUpdate.close()
        scope.launch { closeForApiDeleted(marker, plan!!.lock) }
    }

    private suspend fun closeForApiDeleted(marker: DeletedApiMarker, lock: Boolean) {
        // Fail secure: a vault that was open stays open on the server until it is locked. The lock is pending durably and tried
        // again like any lock (unbound when this phone knows no current unlock); the 410 does not stop it.
        if (lock) {
            val mine = withContext(Dispatchers.IO + NonCancellable) { lockKeeper.begin(session?.device?.unlockId) }
            scope.launch { lockKeeper.ask(mine) }
        }
        val saved = io { VaultDeletion.saveMarker { record.update(record.recordGeneration) { it.copy(deletedApi = marker) } } }
        syncTimeScale()
        if (!saved) android.util.Log.w("VaultManager", "the deletion's marker could not be saved")
        forgetPostedPin()
        keptChange = null
        windowFlow.value = null
        alarmFlow.value = null
        lockedElsewhereFlow.value = false
        val steps = ClearSteps()
        steps.step { social.clear() }
        steps.step { attachments.reset() }
        steps.step { ownerCheck.clear() }
        steps.step { forgetOwnProfile() }
        steps.step { history.clear() }
        steps.step { items.clear() }
        steps.step { sharing.clear() }
        steps.step { feed.clear() }
        steps.step { devices.clear() }
        steps.step { votes.clear() }
        steps.step { deletionWatch.start(marker.watch(), readNow = false) }
        steps.step { session?.device?.ensureCollecting() }
        steps.finish()
    }

    /** A status that lists the vault again: the marker goes and the unlock screen returns. */
    private suspend fun clearApiDeleted(): Unit = deletionEnd.withLock {
        if (deletedApiMarker == null || !phaseFlow.deleting) return@withLock
        deletionWatch.stop()
        deletedApiMarker = null
        io { VaultDeletion.saveMarker { record.update(record.recordGeneration) { it.copy(deletedApi = null) } } }
        phaseFlow.endDeleting(AppPhase.Locked)
    }

    /** One read of `GET /api/vault/status` with the app key, without a session ([VaultDeletion.ofStatus]). */
    private suspend fun readDeletion(): DeletionCheck = VaultDeletion.readCheck(onPaused = { pausedFlow.value = it }) {
        val a = vaultGuard { gateway.member().vaultStatusAnswer() }
        VaultDeletion.StatusSeen(vaultListed = a.vault != null, servicePaused = a.servicePaused)
    }

    /**
     * The vault is gone: what the app does when a vault is gone (its keys and local data forgotten, a fresh app key), the
     * marker deleted with the record, "no vault", and the one-time notice.
     */
    private suspend fun finishDeleted(): Unit = deletionEnd.withLock {
        if (!phaseFlow.deleting) return@withLock
        deletionWatch.stop()
        // The device stopped for the deletion is forgotten (its keys and stored state); then what a gone vault leaves.
        deletingDevice?.let { d -> runCatching { d.forget() } }
        deletingDevice = null
        dropSession(forget = true)
        io { resetLocal(LocalAccount(deletedNotice = true)) }
        deleteExpMs = null
        deletingMarker = null
        deletedApiMarker = null
        phaseFlow.endDeleting(AppPhase.SignedOut)
    }

    override suspend fun checkDeletion(): DeletionCheck = if (phaseFlow.deleting) deletionWatch.check() else DeletionCheck.GONE

    override val deletedNotice: StateFlow<Boolean> = deletedNoticeFlow.asStateFlow()

    override fun acknowledgeDeletedNotice() {
        deletedNoticeFlow.value = false
        val rec = record.recordGeneration
        scope.launch { runCatching { io { updateLocal(rec) { if (it.deletedNotice) it.copy(deletedNotice = false) else null } } } }
    }

    override suspend fun recovery(): RecoveryView? = guard {
        val st = vaultStatus(session()) ?: return@guard null
        st.recoveryState?.let { RecoveryView(it, st.recoveryAvailableAt ?: "") }
    }

    override suspend fun attestationInfo(): AttestationInfo = io {
        // The key the identity in use signs with (ANDROID-PLAN §4 step 5).
        val key = slots.attestation.current(slots.active)?.let { DeviceAttestationKey(it.alias) }
        val present = key?.exists() == true
        val desc = if (key != null && present) {
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
        val level = if (key != null && present) runCatching { key.level().name }.getOrNull() else null
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
        changes.gate(carriesPin = pin != null && local.phone.inUse)
        val s = session()
        // §11.14.9 step 2: refused, nothing sent, if a change or a renew goes out before this one is under the lock.
        val gen = s.openApi.credentialGeneration
        val epoch = local.phone.epoch
        changeSender.send(rec, ChangeKind.PASSWORD, epoch) { store ->
            s.openApi.credentialChangePassword(password, newPassword, pin?.takeIf { local.phone.inUse }, since = gen, onMarker = store)
        }
    }

    /**
     * `pin.change` (release R, §11.14.9): the current PIN, the new one, the password and the next epoch's secret. The
     * phone moves to the next epoch only with the answer, or when a lost answer is decided as applied (0.44.0: the
     * change is decided, not repeated; [ChangeSender]).
     */
    override suspend fun changePin(pin: String, newPin: String, password: String) = guard {
        val rec = record.recordGeneration
        val phone = local.phone
        if (!phone.inUse) throw VaultFailure(FailureKind.NOT_SUPPORTED, "no_phone_secret")
        // §11.14.9 step 2: an earlier change is decided first.
        changes.gate(carriesPin = true)
        // Read before the renew's decision and the epoch below: if a change or a renew goes out after this, the change
        // is refused under the lock with nothing sent, so the epoch and the typed PIN it uses are still current.
        val gen = session().openApi.credentialGeneration
        // (N1) Never a PIN change while a renew may have applied: its candidate is the same secret as the change's.
        when (renewRunner.settle()) {
            RenewRunner.State.NONE -> Unit
            RenewRunner.State.UNSETTLED -> throw VaultFailure(FailureKind.CREDENTIAL_UNSETTLED, ChangeSender.CODE_UNSETTLED)
            RenewRunner.State.UNDECIDABLE -> throw VaultFailure(FailureKind.RENEW_UNCONFIRMED, CODE_RENEW_UNCONFIRMED)
            // (ANDROID-PLAN 0.1.55, 0.1.57 (3)) Another phone became the holder: the renew ended, and this phone can't
            // change the PIN either.
            RenewRunner.State.MOVED -> throw VaultFailure(FailureKind.NOT_HOLDER, VaultCodes.FORBIDDEN)
        }
        val s = session()
        // Read again: a renew decided just above may have moved the phone to its candidate.
        val now = local.phone
        // A PIN change begins: a PIN taken before it by the update's or the move's own screen is not sent (pinGeneration).
        record.pinMoved()
        changeSender.send(rec, ChangeKind.PIN, now.epoch) { store ->
            // Computed and wiped inside the send, which runs in this manager's scope and outlives a screen that goes away.
            val next = phoneSecrets.secret(now, ahead = 1)
            try {
                s.openApi.pinChange(pin, newPin, password, next, since = gen, onMarker = store)
            } finally {
                next.fill(0)
            }
        }
    }

    override val changeCheck: StateFlow<ChangeCheck?> get() = changes.check

    override fun acknowledgeChangeCheck() = changes.acknowledge()

    override fun changeDecisionNotified(): Boolean = local.changeDecision?.notified == true

    override fun markChangeDecisionNotified() {
        notifiedWanted = true
        writeDecision()
    }

    /** (P4) One writer: the decision as wanted now, then (in the same step) that it was notified, never the reverse. */
    private fun writeDecision() {
        scope.launch(Dispatchers.IO) {
            decisionWrite.withLock {
                runCatching {
                    record.saveDecision(decisionToSave)
                    if (notifiedWanted && decisionToSave != null) record.decisionNotified()
                }
            }
        }
    }

    override val pinChangeUnresolved: StateFlow<Boolean> = pinChangeUnresolvedFlow.asStateFlow()

    override val changeHold: StateFlow<FailureKind?> = changeHoldFlow.asStateFlow()

    override val newPinAsked: StateFlow<Boolean> = newPinAskedFlow.asStateFlow()

    /** `vault.secret.renew` (release R, §11.14.9): a new phone secret, the PIN unchanged. */
    override suspend fun renewPhoneSecret(pin: String, password: String): Unit = guard {
        val rec = record.recordGeneration
        if (!local.phone.inUse) throw VaultFailure(FailureKind.NOT_SUPPORTED, "no_phone_secret")
        // §11.14.9 step 2 (also gated as a credential operation): an unresolved change is decided first.
        changes.gate(carriesPin = true)
        val s = session()
        // Read before the epoch: refused under the lock, nothing sent, if a change or a renew went out since.
        val gen = s.openApi.credentialGeneration
        val phone = local.phone
        // A renew begins: a PIN taken before it by the update's or the move's own screen is not sent (pinGeneration).
        record.pinMoved()
        // In this manager's scope (N1): a screen that goes away does not stop it, and its outcome is kept.
        try {
            renewRunner.send { onSending, onAnswered, onRefused ->
                val next = phoneSecrets.secret(phone, ahead = 1)
                try {
                    s.openApi.secretRenew(pin, password, next, onSending = { req ->
                        // Stored before the send (§11.14.9 step 5, N1); the record of [rec].
                        renewEpoch = phone.epoch
                        renewSince = rec
                        onSending(req)
                    }, onAnswered = onAnswered, onRefused = onRefused, since = gen)
                } finally {
                    next.fill(0)
                }
            }
        } finally {
            refreshChangeFlows()
        }
    }

    /** The epoch and record a renew is sent from (read by [renewRunner]'s store). */
    @Volatile
    private var renewEpoch = 0L

    @Volatile
    private var renewSince = RecordStore.UNBOUND

    private val renewRunner = RenewRunner(
        scope,
        object : RenewRunner.Ops {
            override fun store(r: SentRequest): Boolean =
                record.storeRenew(renewSince, UnconfirmedRenew(r.innerId, r.sentVersion ?: 0, r.exp.toEpochMilli(), renewEpoch))

            override fun decide(innerId: String, applied: Boolean) {
                record.decideRenew(innerId, applied)
                refreshChangeFlows()
            }

            override fun unconfirmed(): UnconfirmedRenew? = local.unconfirmedRenew

            override suspend fun awaitSettled(): Boolean = session().openApi.credentialAwaitSettled()

            override suspend fun version(): ChangeResolver.Version {
                val (info, ts) = session().openApi.credentialVersionAt()
                return ChangeResolver.Version(info.exists, info.version, info.alarm != null, ts)
            }

            override suspend fun fetch() {
                val api = session().openApi
                api.credentialFetch()
                api.credentialTopUpUtks()
            }

            // A renew changes the phone's secret, which goes with the PIN: its notice is the PIN's.
            override fun moved() = changes.publish(ChangeCheck.Moved(ChangeKind.PIN))
        },
    )

    override val usesPhoneSecret: Boolean get() = local.phone.inUse

    override val movedIn: Boolean get() = local.movedIn

    override val recoveryOffered: Boolean get() = ReleasePath.recoveryOffered(local.capabilities)

    override suspend fun rotate(password: String) = guard {
        val s = session()
        // vettid.org #358: while a clone alarm is open, the held blob is checked (and refreshed) before the rotation.
        s.openApi.credentialRotate(password, alarmOpen = alarmFlow.value != null)
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
        // (0.1.78) K is never forgotten while an open's answer is unknown.
        if (!Rules.mayForget(undecidedNow())) throw VaultFailure(FailureKind.NOT_SUPPORTED, CODE_UNDECIDED)
        val d = session().device
        // (A6 audit N3) An outstanding enrollment belongs to the code redeemed before: a new code starts afresh too.
        val stale = d.hasVault || d.vaultId != null || d.pendingEnroll != null
        if (d.deviceId == null && stale) dropSession(forget = true) else retireOtherDevices()
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
        posts: RegisterKeys,
    ): com.vettid.core.altchan.SealedRequest {
        val caps = e.descriptor.capabilities
        if (choosePath(e.descriptor.traits, makesPhoneKey = true, since) == ReleasePath.Path.R) {
            if (!ReleasePath.recoveryOffered(caps)) throw VaultFailure(FailureKind.NOT_SUPPORTED, CODE_NOT_OFFERED)
            val phone = phoneSecrets.create(code.vaultId)
            updateLocal(since) { it.copy(phoneKeyVault = phone.keyVault, phoneEpoch = phone.epoch) }
        }
        return registerRequest(s, code, e, posts)
    }

    /** The register's request, attested with a new key of the identity in use, which [posts] records for this POST. */
    private suspend fun registerRequest(
        s: Session,
        code: RecoveryCode,
        e: com.vettid.core.attestation.VerifiedEnclave,
        posts: RegisterKeys,
    ): com.vettid.core.altchan.SealedRequest {
        val att = attester(recoveryId = code.recoveryId)
        val req = s.device.prepareRecoveryRegister(local.userGuid, code, e, att)
        posts.built(att.made)
        return req
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
        if (s.device.recoveryOpenedNotFinished) {
            val resumed = if (local.reuse != null) {
                finishReuse(pin, local.recoveryAttemptsUsed, resuming = true)
            } else {
                finishOpened(s, pin, local.recoveryAttemptsUsed, resuming = true)
            }
            completeOpens(resumed)
            return@guard resumed
        }
        val afterUnanswered = local.recoveryOpenUnanswered
        val secret = phoneSecrets.secret(phone)
        val r = try {
            // (ANDROID-PLAN §4 step 5) Signed with the identity's newest key; on a readable `attestation` (decided before the
            // PIN, not counted) the vault holds an earlier register's key: the identity's other keys, newest first, each once.
            slots.attestation.tryKeys(
                slots.active,
                attempt = { alias -> openOnce(s, rec, pin, password, secret, alias) },
                code = { it.code },
                ok = { it.ok },
                // The key the `ok` open was signed with is never deleted at the handshake.
                onOk = { alias -> io { slots.attestation.markOpened(slots.active, alias) } },
            )
        } finally {
            secret.fill(0)
        }
        val used = if (r.counted) r.used else null
        if (!r.ok) {
            val reuseNow = local.reuse
            return@guard RecoveryOpenOutcome(
                r.code, used, r.retryAt, afterUnanswered = afterUnanswered, attemptUsed = r.attemptUsed,
                // Recovery is ruled out until the owner's app arms a new generation; not terminal (a later recovery reuses N).
                contactCouldNotRecover = reuseNow != null && Rules.showCouldNotRecover(reuseNow.keyInUse, reuseNow.newRegistered, r.code),
            )
        }
        val out = if (local.reuse != null) finishReuse(pin, used, resuming = false) else finishOpened(s, pin, used, resuming = false)
        completeOpens(out)
        out.copy(attemptUsed = r.attemptUsed)
    }

    /**
     * One `vault.recovery.open` signed with the attestation key [alias] (null: the identity's current key). Its entry in the
     * per-open history is saved before it is posted, and its answer settles only that entry.
     */
    @Suppress("LongParameterList")
    private suspend fun openOnce(
        s: Session,
        rec: Int,
        pin: String,
        password: String,
        secret: ByteArray,
        alias: String?,
    ): com.vettid.core.crypto.altchan.RecoveryOpen.Result {
        var sentRid: String? = null
        val r = s.alt.recoveryOpen(
            local.vaultId,
            build = { e, m ->
                // §11.10.9: this phone registered with release R; an instance without it is refused, nothing sent.
                choosePath(e.descriptor.traits, makesPhoneKey = false, rec)
                s.device.prepareRecoveryOpen(local.userGuid, pin, password, secret, e, m, attester(signWith = alias))
            },
            open = { raw, rid -> s.device.openRecoveryOpenResult(raw, rid) },
            // (0.1.78) The open's entry is saved durably BEFORE it is posted; a save that fails posts nothing. Every open sent
            // is kept (sticky) until the vault answered `ok` and the pairing completed.
            beforePost = { rid ->
                sentRid = rid
                val recoveryIdNow = s.device.recoveryId ?: ""
                val ok = io {
                    record.update(rec) {
                        val l = Ledger.afterSent(it.recoveryOpenUnanswered, it.sentOpens, rid, recoveryIdNow, slots.active)
                        it.copy(recoveryOpenUnanswered = true, sentOpens = l)
                    }
                }
                if (!ok) throw VaultFailure(FailureKind.LOCAL_STATE, CODE_HISTORY_NOT_SAVED)
            },
        )
        val used = if (r.counted) r.used else null
        io {
            updateLocal(rec) {
                // Only this open is answered; an earlier one that got no answer stays unanswered.
                val left = Ledger.afterAnswer(it.recoveryOpenUnanswered, it.sentOpens, sentRid, r.code, r.ok)
                val attempts = used ?: it.recoveryAttemptsUsed
                it.copy(recoveryOpenUnanswered = left.isNotEmpty(), sentOpens = left, recoveryAttemptsUsed = attempts)
            }
        }
        return r
    }

    /** `ok`, the pairing and the credential completed ([finishOpened] answers `ok` only then): nothing is unanswered any more. */
    private suspend fun completeOpens(out: RecoveryOpenOutcome) {
        if (!Ledger.completes(out.ok, paired = out.ok, credential = out.ok)) return
        val rec = record.recordGeneration
        io {
            updateLocal(rec) {
                val none = it.sentOpens.isEmpty() && !it.recoveryOpenUnanswered
                if (none) null else it.copy(sentOpens = emptyList(), recoveryOpenUnanswered = false)
            }
        }
    }

    /**
     * A lost `ok` (VAULT-MESSAGING 0.34.0 §11.14.6): the open's answer never arrived or could not be read, and the vault
     * may have completed the recovery and removed its record. An ordinary `vault.unlock` with this phone's secret and
     * the PIN: its result carries `vault_bundle` and a token only for the phone the completed recovery made the app,
     * before its first handshake; the bundle pins the vault, and the handshake is sent again (the unlock dropped any that
     * was under way), then `credential.get`. No attempt is used. Without a bundle the recovery did not complete here.
     */
    override suspend fun resumeLostOk(pin: String): RecoveryOpenOutcome = guard {
        local.reuse?.let { return@guard reuseLostOk(pin, it) }
        val s = session()
        val d = s.device
        if (!local.phone.inUse || !d.recovering) throw VaultFailure(FailureKind.NOT_SUPPORTED, "no_recovery")
        if (d.recoveryOpenedNotFinished) {
            return@guard finishOpened(s, pin, local.recoveryAttemptsUsed, resuming = true).also { completeOpens(it) }
        }
        val u = unlockWithSecret(s, pin, UnlockOptions(), null, null)
        when {
            !u.ok -> RecoveryOpenOutcome(u.result.code ?: "retry", local.recoveryAttemptsUsed, opened = true, attemptUsed = false)
            // A result without `vault_bundle`: this phone is not the one a completed recovery made the app.
            !d.recoveryOpenedNotFinished ->
                RecoveryOpenOutcome(CODE_NO_BUNDLE, local.recoveryAttemptsUsed, opened = true, attemptUsed = false)
            else -> finishOpened(s, pin, local.recoveryAttemptsUsed, resuming = false).also { completeOpens(it) }.copy(attemptUsed = false)
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
        pairWithEach(s.device, if (resuming) RESUME_HANDSHAKE_WAIT else Duration.ofSeconds(OPENED_HANDSHAKE_WAIT_S))
        null
    } catch (e: TimeoutCancellationException) {
        android.util.Log.i("VaultManager", "the recovered phone's handshake was not answered: unlocking with its own key")
        unlockOwn(s, pin, e) ?: run {
            pairWithEach(s.device, null)
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

    // --- a phone that could not tell (ANDROID-PLAN 0.1.78; VAULT-MESSAGING 0.59.0 §11.14.6) ---

    /**
     * An open of this phone's has no readable answer, or a reused-key recovery is under way. A device that is no longer
     * recovering has nothing undecided.
     */
    private fun undecidedNow(): Boolean = Rules.undecided(local.sentOpens.isNotEmpty() || local.recoveryOpenUnanswered, local.reuse)

    override val recoveryUndecided: Boolean get() = undecidedNow()

    override fun lostOkDue(): Boolean = local.reuse?.lostOkDue == true

    override fun newCodeAllowed(): Boolean = local.reuse?.startNew == true

    /**
     * The record when the member may use a new code with K (two reads said ended). While undecided and not yet allowed, a
     * new code is refused: registering again would overwrite the earlier recovery ([CODE_UNDECIDED]).
     */
    private fun reuseGate(): ReuseRecord? {
        val r = local.reuse
        return when (Rules.gateNewCode(undecidedNow(), r?.startNew == true)) {
            Rules.Gate.REUSE -> r
            Rules.Gate.REFUSE -> throw VaultFailure(FailureKind.NOT_SUPPORTED, CODE_UNDECIDED)
            Rules.Gate.FRESH -> null
        }
    }

    /** One write of the reuse record; a write the record drops (stale, closed, unreadable) stops the step, fail secure. */
    private suspend fun persistReuse(next: ReuseRecord) {
        val rec = record.recordGeneration
        val ok = io { record.update(rec) { it.copy(reuse = next) } }
        if (!ok) throw VaultFailure(FailureKind.LOCAL_STATE, CODE_REUSE_NOT_SAVED)
    }

    private suspend fun markReuse(f: (ReuseRecord) -> ReuseRecord) {
        local.reuse?.let { persistReuse(f(it)) }
    }

    /** Status signed with K, as a [Rules.Read]. A `401` that is the phone's clock ([MemberApiException.CLOCK_SKEW]) concludes nothing. */
    @Suppress("ReturnCount", "SwallowedException") // a refusal or a fault is one of the two reads that conclude nothing or ended
    private suspend fun readStatusWithK(s: Session): Rules.Read {
        val a = try {
            s.member.vaultStatusAnswer()
        } catch (e: MemberApiException) {
            val ended = e.code == MemberApiException.UNAUTHORIZED ||
                (e.status == HTTP_GONE && e.code == MemberApiException.RECOVERY_ENDED)
            return if (ended) Rules.Read.Ended else Rules.Read.Unknown
        } catch (e: IOException) {
            return Rules.Read.Unknown
        }
        val v = a.vault
        if (v == null || a.servicePaused) return Rules.Read.Unknown
        return Rules.Read.Listed(
            registered = v.recoveryState == RECOVERY_REGISTERED,
            unregistered = v.recoveryState == RECOVERY_PENDING || v.recoveryState == RECOVERY_AVAILABLE,
        )
    }

    @Suppress("CyclomaticComplexMethod", "ComplexCondition") // the two reads, the clock, and one answer per decision
    override suspend fun couldNotTell(): CouldNotTell = guard {
        val none = CouldNotTell(CouldNotTell.Kind.NOT_APPLICABLE)
        if (!Rules.applies(local.recoveryOpenUnanswered, local.reuse)) return@guard none
        val s = session()
        val reuse = local.reuse ?: ReuseRecord(
            earlierSlot = slots.active,
            earlierRecoveryId = s.device.recoveryId ?: return@guard none,
        )
        // Both clocks: the wall clock and the monotonic one (a reboot resets it, a set clock disagrees with it).
        val now = Rules.Clock(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())
        val (decision, seen) = Rules.afterRead(
            readStatusWithK(s), Rules.Seen(reuse.endedSeen, reuse.listedSeen), now, secondIdentity = reuse.newRegistered,
        )
        val listed = decision == Rules.Decision.LostOk || decision == Rules.Decision.OpenAgain
        persistReuse(
            reuse.copy(
                endedSeen = seen.ended,
                listedSeen = seen.listed,
                startNew = decision == Rules.Decision.StartNew || decision == Rules.Decision.KeepBoth || (reuse.startNew && !listed),
                lostOkDue = decision == Rules.Decision.LostOk || decision == Rules.Decision.KeepBoth ||
                    (reuse.lostOkDue && decision != Rules.Decision.OpenAgain),
            ),
        )
        when (decision) {
            Rules.Decision.NotApplicable -> none
            Rules.Decision.LostOk, Rules.Decision.KeepBoth -> CouldNotTell(CouldNotTell.Kind.LOST_OK)
            Rules.Decision.OpenAgain -> CouldNotTell(CouldNotTell.Kind.OPEN_AGAIN)
            is Rules.Decision.Wait -> CouldNotTell(CouldNotTell.Kind.WAIT, decision.atMs)
            Rules.Decision.StartNew -> CouldNotTell(CouldNotTell.Kind.START_NEW)
            Rules.Decision.Unknown -> CouldNotTell(CouldNotTell.Kind.UNKNOWN)
        }
    }

    /** Switches the identity in use: the session is dropped (not forgotten) and the next one is made from [slot]'s keys and state. */
    private suspend fun switchIdentity(slot: Int) {
        if (slots.active == slot) return
        val d = session?.device
        dropSession(forget = false)
        d?.let {
            runCatching { it.dropKeysFromMemory() }
            liveDevices.remove(it)
        }
        io { slots.active = slot }
    }

    private fun slotOf(r: ReuseRecord, identity: Identity): Int? = if (identity == Identity.EARLIER) r.earlierSlot else r.newSlot

    /**
     * A new code with K (the claim, the earlier open's slot once, a new identity): [NewRecovery]. Nothing is deleted. The
     * phone secret record is kept: the new open carries the earlier open's `unlock_secret`.
     */
    private suspend fun reuseRegister(
        namedVault: String?,
        claim: suspend () -> Claimed,
        codeOf: (Claimed) -> RecoveryCode,
    ): RecoveryRegistration {
        if (!local.phone.inUse) throw VaultFailure(FailureKind.NOT_SUPPORTED, "no_phone_secret")
        // A scanned code for another vault is refused before anything is sent.
        if (!Rules.sameVault(local.vaultId, namedVault)) return RecoveryRegistration.Refused(CODE_OTHER_VAULT)
        var claimed: Claimed? = null
        var code: RecoveryCode? = null
        val result = NewRecovery(
            object : NewRecovery.Ports {
                override suspend fun claim(): ClaimAnswer = try {
                    val c = claim()
                    claimed = c
                    code = codeOf(c)
                    // The claim names the vault: another one than the earlier open's is refused, nothing registered.
                    val named = c.vaultId ?: code?.vaultId
                    if (Rules.sameVault(local.vaultId, named)) ClaimAnswer.Claimed else ClaimAnswer.Refused(CODE_OTHER_VAULT)
                } catch (e: MemberApiException) {
                    if (e.code == MemberApiException.KEY_IN_USE) {
                        ClaimAnswer.KeyInUse
                    } else {
                        ClaimAnswer.Refused((claimRefusal(e) as? RecoveryRegistration.Refused)?.code ?: CODE_RETRY)
                    }
                }

                override suspend fun pollEarlierOpen(): EarlierOpen = pollEarlier()

                override suspend fun registerNew(): RegisterAnswer = registerNewIdentity(code!!, claimed!!)

                override suspend fun lostOkDue() = markReuse { it.copy(lostOkDue = true, startNew = false, keyInUse = true) }
            },
        ).run()
        return when (result) {
            NewRecovery.Result.Registered -> RecoveryRegistration.Registered(claimed!!.emailHint)
            NewRecovery.Result.KeyInUse -> RecoveryRegistration.KeyInUse
            NewRecovery.Result.EarlierOk -> RecoveryRegistration.EarlierOpened
            NewRecovery.Result.RegisterUnauthorized -> RecoveryRegistration.Refused(CODE_RETRY, code?.recoveryId)
            is NewRecovery.Result.Refused -> RecoveryRegistration.Refused(result.code, code?.recoveryId)
        }
    }

    /**
     * Looks once at the slot of every open that got no readable answer (the ledger, not only the newest): "a readable answer
     * decides it". Each is opened with its own identity's keys (a temporary device for the slot not in use). Never twice.
     */
    @Suppress("ReturnCount")
    private suspend fun pollEarlier(): EarlierOpen {
        local.reuse ?: return EarlierOpen.Undecided
        for (o in Ledger.toPoll(local.sentOpens)) {
            val rec0 = record.recordGeneration
            io { updateLocal(rec0) { it.copy(sentOpens = Ledger.polled(it.sentOpens, o.rid)) } }
            val res = readOpenAnswer(o) ?: continue
            val rec = record.recordGeneration
            val used = if (res.counted) res.used else null
            io {
                updateLocal(rec) {
                    val left = Ledger.afterAnswer(it.recoveryOpenUnanswered, it.sentOpens, o.rid, res.code, res.ok)
                    val attempts = used ?: it.recoveryAttemptsUsed
                it.copy(recoveryOpenUnanswered = left.isNotEmpty(), sentOpens = left, recoveryAttemptsUsed = attempts)
                }
            }
            if (res.ok) {
                markReuse { it.copy(lostOkDue = false, startNew = false) }
                switchIdentity(o.slot)
                phaseFlow.value = AppPhase.Setup(SetupStage.RECOVERING)
                return EarlierOpen.Ok
            }
        }
        return EarlierOpen.Undecided
    }

    /** The readable answer in the slot of [o], or null (queued, expired, random bytes, a fault). */
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "SwallowedException") // anything unreadable leaves it undecided
    private suspend fun readOpenAnswer(o: SentOpen): com.vettid.core.crypto.altchan.RecoveryOpen.Result? {
        val slot = try {
            session().member.slot(o.rid)
        } catch (e: MemberApiException) {
            return null
        } catch (e: IOException) {
            return null
        }
        val env = slot.envelope()
        if (slot.status != Slot.DONE || env == null) return null
        return try {
            withDeviceOf(o.slot) { it.openRecoveryOpenResult(env, o.rid) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** [f] on the device of [slot]: the one in use, or a temporary one for the other slot (its keys leave memory after). */
    @Suppress("ReturnCount")
    private suspend fun <T> withDeviceOf(slot: Int, f: suspend (com.vettid.core.vault.VaultDevice) -> T): T? {
        if (slot == slots.active) return f(session().device)
        if (!io { slots.exists(slot) }) return null
        val d = io {
            val keys = slots.keys(slot)
            val secrets = DeviceSecrets(keys.ed25519(KeySlot.IDENTITY), keys.kem(), keys.ed25519(KeySlot.RELAY), gateway.appKey())
            val cfg = DeviceConfig(
                name = deviceName, relayUrl = env.endpoints.relayUrl, http = http,
                store = KeystoreFileStore(slots.file(slot), "vault-device"), trust = env.trust(http),
                elapsedMs = { android.os.SystemClock.elapsedRealtime() },
            )
            com.vettid.core.vault.VaultDevice.load(cfg, secrets)
        } ?: return null
        return try {
            f(d)
        } finally {
            runCatching { d.dropKeysFromMemory() }
        }
    }

    /** The new identity in the other slot (made once, kept across a failed register), registered with the same K. */
    @Suppress("ReturnCount", "ThrowsCount", "CyclomaticComplexMethod", "LongMethod") // one branch per answer of the register
    private suspend fun registerNewIdentity(code: RecoveryCode, claimed: Claimed): RegisterAnswer {
        var r = local.reuse ?: throw VaultFailure(FailureKind.NOT_SUPPORTED, CODE_UNDECIDED)
        val slot = r.newSlot ?: slots.other(r.earlierSlot)
        if (r.newSlot == null) {
            // The slot is named before it is made: a crash in between leaves a slot to make, never an unnamed identity.
            r = r.copy(newSlot = slot)
            persistReuse(r)
        }
        if (!io { slots.exists(slot) }) {
            // Fail secure: N's keys are never replaced once N was (or may have been) registered.
            if (!Rules.mayMakeNewKeysForN(r.newRegistered, r.newRecoveryId)) throw VaultFailure(FailureKind.LOCAL_STATE, CODE_UNDECIDED)
            io {
                slots.file(slot).delete()
                val k = slots.keys(slot)
                KeySlot.entries.forEach { k.generate(it) }
            }
        }
        switchIdentity(slot)
        val s = session()
        gateway.memberChanged(claimed.userGuid)
        // N may have registered once this is posted, whatever comes back: kept as unknown, never registered again, tried
        // by the lost-ok unlock. A definite refusal below takes it back.
        val before = local.reuse ?: r
        // Per POST (in [build]), see [Rules.registerFailure]: [postedEarlier] any earlier POST built in this flow, [accepted] this
        // POST got its `202`, [earlierAccepted] an earlier POST of this flow did (sticky: this POST's answer settles only itself).
        val triedBefore = before.registerTried == code.recoveryId
        var built = false
        var postedEarlier = false
        var accepted = false
        var earlierAccepted = false
        // Each POST's attestation key is N's, kept until that POST is settled not registered; E's keys are never touched here.
        val posts = RegisterKeys()
        markReuse { it.copy(newRegistered = true, newRecoveryId = code.recoveryId, registerTried = code.recoveryId) }
        val since = record.recordGeneration

        /** Applies [Rules.registerFailure] to a failed POST; the caller then throws, answers or confirms by the outcome. */
        suspend fun fail(status: Int?, failCode: String?): Rules.RegisterOutcome {
            val o = Rules.registerFailure(
                triedBefore, postedEarlier, accepted, earlierAccepted, before.newRegistered == true, status, failCode,
            )
            when (o) {
                Rules.RegisterOutcome.DROP_AND_RETRY, Rules.RegisterOutcome.DROP_REFUSED -> {
                    s.device.dropRecoveryRegistration()
                    backToEarlier(before)
                }
                Rules.RegisterOutcome.DROP_UNAUTHORIZED -> {
                    // N's registration is dropped with its identity and relay key: it no longer counts as one of the two.
                    s.device.dropRecoveryRegistration()
                    backToEarlier(before)
                    dropNewIdentity()
                }
                Rules.RegisterOutcome.MAYBE_REGISTERED, Rules.RegisterOutcome.KEEP_UNKNOWN -> Unit
            }
            return o
        }
        val res = try {
            s.alt.recoveryRegister(
                code.vaultId,
                build = { e ->
                    postedEarlier = built
                    built = true
                    earlierAccepted = earlierAccepted || accepted
                    accepted = false
                    buildReuseRegister(s, code, e, since, posts)
                },
                open = { raw, rid -> s.device.openRecoveryResult(raw, rid) },
                onPosted = {
                    accepted = true
                    posts.accepted()
                },
            )
        } catch (e: VaultFailure) {
            // Every attestation key of N is listed: nothing was made or sent (fail secure).
            if (e.code == AttestationKeys.CODE_KEYS_FULL) return RegisterAnswer.Refused(AttestationKeys.CODE_KEYS_FULL)
            if (e.code != CODE_NOT_OFFERED) throw e
            return RegisterAnswer.Refused(CODE_NOT_OFFERED)
        } catch (e: MemberApiException) {
            val o = fail(e.status, e.code)
            posts.answered(e.status, e.code)
            forgetUnregistered(slot, posts, lastRefused = false)
            return when {
                o == Rules.RegisterOutcome.MAYBE_REGISTERED -> maybeRegistered(s, code)
                o == Rules.RegisterOutcome.DROP_UNAUTHORIZED -> RegisterAnswer.Unauthorized
                e.code == MemberApiException.RECOVERY_UNAVAILABLE -> RegisterAnswer.Refused(CODE_NO_BACKUP)
                e.code == MemberApiException.RECOVERY_NOT_AVAILABLE -> RegisterAnswer.Refused(CODE_NOT_AVAILABLE)
                else -> throw e
            }
        } catch (e: IOException) {
            // A failure after the `202` (the poll, a slot that never answered) is not "before the vault".
            val o = fail(null, null)
            // No answer: this POST may have been queued, so its key stays.
            forgetUnregistered(slot, posts, lastRefused = false)
            if (o == Rules.RegisterOutcome.MAYBE_REGISTERED) return maybeRegistered(s, code)
            throw e
        }
        // A readable refusal settles its own POST: that POST's key was not recorded (the vault keeps an earlier one).
        if (res?.ok == false) forgetUnregistered(slot, posts, lastRefused = true)
        if (res?.ok == true) {
            s.device.confirmRecoveryRegistration()
            markReuse { it.copy(newRegistered = true, newRecoveryId = code.recoveryId, startNew = false, lostOkDue = false) }
            phaseFlow.value = AppPhase.Setup(SetupStage.RECOVERING)
            return RegisterAnswer.Registered
        }
        // A readable refusal settles its own POST only: after an earlier `202`, or a `used` to a repeat, N may be registered.
        val repeat = Rules.isRepeatPost(triedBefore, postedEarlier)
        // An earlier flow's POST for this code left N registered or maybe registered (memory-only [earlierAccepted] is gone).
        val priorRegistration = triedBefore && before.newRegistered && before.newRecoveryId == code.recoveryId
        if (Rules.registerRefusal(repeat, earlierAccepted || priorRegistration, res?.code) == Rules.RegisterOutcome.MAYBE_REGISTERED) {
            return maybeRegistered(s, code)
        }
        s.device.dropRecoveryRegistration()
        backToEarlier(before)
        return if (res == null) RegisterAnswer.Refused(CODE_NOT_AVAILABLE) else RegisterAnswer.Refused(res.code ?: CODE_RETRY)
    }

    /**
     * A repeat register for the same code was refused, but the first copy may have been recorded: the new registration is kept
     * (the prior one is not restored) and status signed with K decides, `registered` there meaning registered for this recovery.
     */
    private suspend fun maybeRegistered(s: Session, code: RecoveryCode): RegisterAnswer {
        val read = readStatusWithK(s) as? Rules.Read.Listed
        if (read?.registered != true) {
            // The recovery is active and not registered, and no open was ever sent for it: this identity's keys made for it
            // past any queue were not recorded (a later POST for it is a new key), so they no longer fill its list.
            if (read?.unregistered == true && local.sentOpens.none { it.recoveryId == code.recoveryId }) {
                val slot = slots.active
                io { slots.attestation.settleNotRegistered(slot, code.recoveryId, SETTLE_AFTER_MS) }
            }
            return RegisterAnswer.Refused(CODE_RETRY)
        }
        s.device.confirmRecoveryRegistration()
        markReuse { it.copy(newRegistered = true, newRecoveryId = code.recoveryId, startNew = false, lostOkDue = false) }
        phaseFlow.value = AppPhase.Setup(SetupStage.RECOVERING)
        return RegisterAnswer.Registered
    }

    /** N's keys and state are removed (after a `401` at its register) and the record forgets its slot: a later N is made anew. */
    private suspend fun dropNewIdentity() {
        val r = local.reuse
        val n = r?.newSlot
        if (r == null || n == null || n == slots.active) return
        io { slots.clear(n) }
        persistReuse(r.copy(newSlot = null, newRegistered = false, newRecoveryId = null))
    }

    /** A refused register leaves the new identity unregistered: the earlier identity is the one in use again. */
    private suspend fun backToEarlier(before: ReuseRecord? = null) {
        val r = local.reuse ?: return
        // N keeps what it had accepted before this attempt (the `recovery_id` it LAST registered for); a first N has none.
        persistReuse(r.copy(newRegistered = before?.newRegistered == true, newRecoveryId = before?.newRecoveryId))
        switchIdentity(Rules.slotAfterRefusedRegister(r.earlierSlot))
    }

    /** As [buildRegister], but the phone key and its record are the earlier open's, kept: nothing is made or replaced. */
    private suspend fun buildReuseRegister(
        s: Session,
        code: RecoveryCode,
        e: com.vettid.core.attestation.VerifiedEnclave,
        since: Int,
        posts: RegisterKeys,
    ): com.vettid.core.altchan.SealedRequest {
        if (choosePath(e.descriptor.traits, makesPhoneKey = false, since) != ReleasePath.Path.R ||
            !ReleasePath.recoveryOffered(e.descriptor.capabilities)
        ) {
            throw VaultFailure(FailureKind.NOT_SUPPORTED, CODE_NOT_OFFERED)
        }
        return registerRequest(s, code, e, posts)
    }

    /** The lost-ok unlock with [identity] (it becomes the identity in use): `ok` only with the vault's bundle. */
    @Suppress("ReturnCount")
    private suspend fun lostOkUnlock(identity: Identity, reuse: ReuseRecord, pin: String): LostOkAnswer {
        val slot = slotOf(reuse, identity) ?: return LostOkAnswer.Refused(CODE_NO_RECOVERY)
        switchIdentity(slot)
        val s = session()
        val d = s.device
        if (!local.phone.inUse || !d.recovering) return LostOkAnswer.Refused(CODE_NO_RECOVERY)
        if (d.recoveryOpenedNotFinished) return LostOkAnswer.Ok
        val u = unlockWithSecret(s, pin, UnlockOptions(), null, null)
        return when {
            !u.ok -> if (u.result.code == CODE_TRANSFER_INCOMPLETE) {
                LostOkAnswer.TransferIncomplete
            } else {
                LostOkAnswer.Refused(u.result.code ?: CODE_RETRY)
            }
            d.recoveryOpenedNotFinished -> LostOkAnswer.Ok
            else -> LostOkAnswer.Refused(CODE_NO_BUNDLE)
        }
    }

    /** [resumeLostOk] while a [ReuseRecord] stands: the newest identity first, then the earlier one ([LostOk]). */
    private suspend fun reuseLostOk(pin: String, reuse: ReuseRecord): RecoveryOpenOutcome {
        val start = slots.active
        val used = local.recoveryAttemptsUsed
        val result = LostOk(
            object : LostOk.Ports {
                override suspend fun unlock(identity: Identity) = lostOkUnlock(identity, reuse, pin)
            },
        ).run(reuse.newRegistered)
        return when (result) {
            is LostOk.Result.Ok -> {
                markReuse { it.copy(refusedSinceMs = null, lostOkDue = false) }
                // The new identity's pairing has the fall back to the earlier one; the cleanup is in finishReuse.
                finishReuse(pin, used, resuming = false).also { completeOpens(it) }.copy(attemptUsed = false)
            }
            is LostOk.Result.TransferIncomplete -> {
                // The state is still under DEK_0: only an open repeats the recovery, while the recovery shows `registered`.
                switchIdentity(Rules.slotForOpenAgain(reuse.newRegistered, reuse.newSlot, reuse.earlierSlot))
                val registered = (readStatusWithK(session()) as? Rules.Read.Listed)?.registered == true
                markReuse {
                    it.copy(lostOkDue = false, startNew = !registered, refusedSinceMs = null)
                }
                RecoveryOpenOutcome(if (registered) CODE_OPEN_AGAIN else CODE_START_NEW, used, opened = true, attemptUsed = false)
            }
            is LostOk.Result.NoneOk -> {
                switchIdentity(start)
                val now = System.currentTimeMillis()
                // A wrong PIN or a wait is the vault answering this phone; only other refusals count toward the day.
                val refusal = Rules.countsAsRefusal(result.code)
                val since = if (refusal) reuse.refusedSinceMs ?: now else reuse.refusedSinceMs
                if (since != reuse.refusedSinceMs) markReuse { it.copy(refusedSinceMs = since) }
                val scale = timeScaleFlow.value.scale
                RecoveryOpenOutcome(
                    result.code, used, opened = true, attemptUsed = false,
                    contactVettid = Rules.showContact(reuse.keyInUse, refusal, since, now, scale),
                )
            }
        }
    }

    /**
     * After the new identity's open answered `ok` ([Pairing]): `hs.init` with `ctx` = the new `recovery_id`; with no `hs.resp`
     * within the pairing timeout, the lost-ok unlock with the earlier identity and `hs.init` with the earlier `recovery_id`.
     * The earlier identity and its records go only after the handshake completed.
     */
    @Suppress("ReturnCount")
    private suspend fun finishReuse(pin: String, used: Int?, resuming: Boolean): RecoveryOpenOutcome {
        val reuse = local.reuse
        // The earlier identity itself opened (or the new one never existed): one identity, paired with its own recovery_id.
        if (reuse == null || !Rules.pairsWithFallback(reuse.newSlot, slots.active)) {
            val out = finishOpened(session(), pin, used, resuming)
            if (out.ok && reuse != null) reuseCleanup(Identity.EARLIER, openOk = true, pairingComplete = true)
            return out
        }
        val result = Pairing(
            object : Pairing.Ports {
                @Suppress("ReturnCount", "SwallowedException") // no hs.resp is the answer that leads to the earlier identity
                override suspend fun pair(identity: Identity): PairAnswer {
                    switchIdentity(slotOf(reuse, identity) ?: return PairAnswer.NO_RESPONSE)
                    val d = session().device
                    if (d.deviceId != null) return PairAnswer.PAIRED
                    return try {
                        pairWithEach(d, Duration.ofSeconds(OPENED_HANDSHAKE_WAIT_S))
                        PairAnswer.PAIRED
                    } catch (e: TimeoutCancellationException) {
                        android.util.Log.i("VaultManager", "no hs.resp for the new identity: trying the earlier one")
                        PairAnswer.NO_RESPONSE
                    }
                }

                override suspend fun unlockEarlier(): LostOkAnswer = lostOkUnlock(Identity.EARLIER, reuse, pin)
            },
        ).run()
        return when (result) {
            is Pairing.Result.Paired -> {
                val s = session()
                val refused = fetchRecovered(s, pin, unlockFirst = resuming)
                if (refused != null) return RecoveryOpenOutcome(refused, used, opened = true)
                s.device.finishRecoveryOpen()
                reuseCleanup(result.identity, openOk = true, pairingComplete = true)
                RecoveryOpenOutcome("ok", used)
            }
            is Pairing.Result.Failed -> {
                // Everything is kept; the identity whose open answered `ok` is the one in use again.
                reuse.newSlot?.let { switchIdentity(it) }
                RecoveryOpenOutcome(result.code, used, opened = true)
            }
        }
    }

    /** The paired identity stays; the other identity's keys and state go, and the reuse record ([Rules.mayDeleteEarlier]). */
    @Suppress("ReturnCount")
    private suspend fun reuseCleanup(paired: Identity, openOk: Boolean, pairingComplete: Boolean) {
        val r = local.reuse ?: return
        if (!Rules.mayDeleteEarlier(openOk, pairingComplete)) return
        val keep = slotOf(r, paired) ?: return
        val drop = slotOf(r, if (paired == Identity.NEW) Identity.EARLIER else Identity.NEW)
        switchIdentity(keep)
        if (drop != null && drop != keep) io { slots.clear(drop) }
        val rec = record.recordGeneration
        io { updateLocal(rec) { it.copy(reuse = null, sentOpens = emptyList(), recoveryOpenUnanswered = false) } }
    }

    override suspend fun recoveryOpened(): Boolean = guard { local.phone.inUse && session().device.recoveryOpenedNotFinished }

    override val recoveryAttemptsUsed: Int? get() = local.recoveryAttemptsUsed

    /**
     * (Release R) After the member has read the `ok` text: the vault opens on this phone. That text is the recovered
     * phone's last setup screen (a recovered phone is a new install, `setupComplete` false, so [afterUnlocked] says
     * [SetupStage.FINISHING]): setup is finished here as from the onboarding's last screen ([finishSetup], under its
     * gate: never over a pending lock). Without it the phase stayed FINISHING under the recovery screen, which ignores it.
     * The rules are [RecoveryOpenFinish]'s: as at the end of an unlock, a lock or a wipe since this began, or a lock
     * pending now, is never overwritten (Locked while a lock is pending), and an app that did not open is a failure.
     */
    override suspend fun finishRecoveryOpen() {
        // Read before anything: a lock asked for from here on bumps it, and this then publishes no open phase.
        val gen = generation
        RecoveryOpenFinish.run(
            object : RecoveryOpenFinish.Ports {
                override fun overtaken() = when {
                    lockGuard.lockedWhatever(unlocked = true) -> RecoveryOpenFinish.Overtaken.LOCK_PENDING
                    gen != generation -> RecoveryOpenFinish.Overtaken.OTHER
                    else -> RecoveryOpenFinish.Overtaken.NO
                }

                override suspend fun afterUnlocked(): AppPhase = guard { this@VaultManager.afterUnlocked(session()) }

                override fun publish(phase: AppPhase) {
                    phaseFlow.value = phase
                }

                override fun publishUnlessOvertaken(phase: AppPhase): Boolean =
                    phaseFlow.setIf(phase) { overtaken() == RecoveryOpenFinish.Overtaken.NO }

                // Under guard: the failures it knows (an IOException of the account record's write, among others)
                // become a VaultFailure; anything else (an IllegalStateException) is caught by the view model's openApp.
                override suspend fun finishSetup(): Boolean = guard { finishSetupGated() }
            },
        )
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
        // (0.1.78) K is never forgotten while an open's answer is unknown (also on `410 recovery_ended`).
        if (!Rules.mayForget(undecidedNow())) throw VaultFailure(FailureKind.NOT_SUPPORTED, CODE_UNDECIDED)
        // Release R (§11.14.6): after `ok` the vault's only unlock key is this phone's; it is never deleted here.
        if (local.phone.inUse && d.recoveryOpenedNotFinished) throw VaultFailure(FailureKind.OTHER, MoveRepository.CODE_RECOVERY_OPENED)
        dropSession(forget = true)
        io { resetLocal(LocalAccount()) }
        phaseFlow.value = AppPhase.SignedOut
    }

    override suspend fun recoverCredential(password: String): RecoverOutcome = guard {
        // Read before the vault's call: a lock, a refusal or a wipe from here on keeps the phase it published.
        val gen = generation
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
        if (outcome == RecoverOutcome.RECOVERED) publishFinishing(gen)
        outcome
    }

    // --- MoveRepository: direct transfer (§6.7.1) ---

    /** The transfer this (old) phone opened last, to cancel it when a new one is asked for. */
    @Volatile
    private var openTransferId: String? = null

    override suspend fun transferIn(code: String): String = enrolments.transferIn(code)

    override suspend fun awaitTransferIn(): Boolean = guard {
        // Read before the wait and the vault's calls: a lock, a refusal or a wipe from here on keeps the phase it published.
        val gen = generation
        val rec = record.recordGeneration
        val s = session()
        // (VAULT-MESSAGING 0.56.2 §11.14.10 rule 5) One deadline, from the start the device stored just before the hs.init
        // (kept across restarts; nothing the member does moves it); then one collect, and applied or dropped.
        val guid = TransferInResume.awaitApproval(
            pending = { s.device.transferInPending },
            now = Instant::now,
            await = { left -> s.device.awaitTransfer(left) },
            atDeadline = { atTransferDeadline(s) },
        ) ?: throw VaultFailure(FailureKind.NO_RESPONSE, MoveRepository.CODE_TRANSFER_DROPPED)
        completeTransferIn(s, rec, guid.userGuid).also { publishFinishing(gen) }
    }

    /**
     * (A6 audit N1; VAULT-MESSAGING 0.56.2 §11.14.10 rule 5, ANDROID-PLAN 0.1.76) The transfer to this phone at its
     * 11-minute deadline, or at the first start after it: one collect of the mailbox (`wait=0`, `max` at its cap, at most
     * [TransferInResume.DEADLINE_COLLECT_MS] in all), and the `device.paired{transfer}` it brings is applied by the
     * handler. Paired: the transfer (to be completed by the take-over). Otherwise, also when the collect fails or times
     * out: the handshake is dropped and null.
     */
    private suspend fun atTransferDeadline(s: Session): TransferInState? {
        val d = s.device
        return try {
            // (#187 recheck) One deadline step at a time (the screen's wait and a start may both reach it), decided again
            // inside: a second one never drops what the first one's collect is about to apply.
            val paired = transferDeadline.run(
                due = { TransferInResume.deadlineDue(d.transferInPending, d.deviceId, Instant.now()) },
                paired = { d.transferInPending?.paired == true && d.deviceId != null },
                collect = { d.deadlineCollect() },
                drop = { d.abandonTransfer() },
            )
            if (paired) d.transferInPending else null
        } finally {
            d.start(scope) // deferred at a start past the deadline (session()); running already otherwise
        }
    }

    /** (#187 recheck) Serializes [atTransferDeadline]. */
    private val transferDeadline = TransferInResume.Serial()

    override suspend fun transferInWaiting(): TransferInWaiting? = guard {
        val t = session().device.transferInPending ?: return@guard null
        // Also once paired: the screen's wait then returns at once and completes it.
        val sas = t.sas ?: return@guard null
        TransferInWaiting(sas, t.startedAt)
    }

    /** (A6 audit N1) One completion of a transfer to this phone at a time (the screen's wait and a start may meet). */
    private val transferInLock = Mutex()

    /**
     * (A6 audit N1) The steps after a transfer's `device.paired`, also after a restart: the member's `user_guid`, the
     * move's markers, and the credential the vault kept (§6.7.1 step 5). The transfer stays pending on the device until
     * they are done, so a failure or a kill on the way is resumed at the next start (never as a `credential.create`,
     * which the vault would answer `exists`).
     */
    private suspend fun completeTransferIn(s: Session, rec: Int, guid: String?): Boolean = transferInLock.withLock {
        if (s.device.transferInPending == null && s.device.credentialVersion != null) return@withLock guid != null
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
        s.device.finishTransferIn()
        // FINISHING is the caller's to publish, with the mark it took before its own vault calls (an unlock's end, a
        // refresh, the wait for the transfer): one taken here would be after them, and miss a lock during them.
        guid != null
    }

    override suspend fun abandonTransferIn() = guard {
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
        val keepBefore = local.transferKeepMs
        // STAGING-TIME-SCALE (S10): the keep period is divided by the vault's time scale now, and kept with the time.
        val keepMs = KeptTransfer.keepMs(timeScaleFlow.value.scale).takeIf { it != KeptTransfer.KEEP_MS }
        if (r) io { updateLocal(rec) { it.copy(transferApprovedAtMs = System.currentTimeMillis(), transferKeepMs = keepMs) } }
        val restore: (LocalAccount) -> LocalAccount = { it.copy(transferApprovedAtMs = before, transferKeepMs = keepBefore) }
        val moved = try {
            holder.approveTransfer(if (r) 0 else UNLINK_WAIT_MS) {
                keep = guard {
                    val s = session()
                    // §11.14.10 (owner decision 2026-10-23): the vault's token for this phone's mailbox is renewed (and
                    // accepted by the relay) right before each copy of the approval goes out.
                    val renew = TransferApproval.renewal(renew = { s.device.renewIssuedToken() }) { e ->
                        android.util.Log.i("VaultManager", "the token renewal before the approval failed: ${e.javaClass.simpleName}")
                    }
                    s.openApi.transferApprove(transferId, pin, password, signer, beforeSend = renew)
                }.keepUntilNewUnlock
                openTransferId = null
            }
        } catch (e: VaultFailure) {
            if (r && !KeptTransfer.mayHaveReached(e.kind)) io { updateLocal(rec, restore) }
            throw e
        }
        if (keep) {
            checkKeptTransfer()
        } else if (r) {
            io { updateLocal(rec, restore) }
        }
        return moved
    }

    override val finishMovePending: StateFlow<Boolean> = finishMoveFlow.asStateFlow()

    override val finishingMove: StateFlow<Boolean> get() = finishMoveRunner.finishing

    override val finishMoveFailure: StateFlow<FailureKind?> get() = finishMoveRunner.failure

    override fun acknowledgeFinishMoveFailure() = finishMoveRunner.clearFailure()

    @Suppress("ReturnCount") // nothing sent: held, or a PIN taken before a change
    override suspend fun finishMove(pin: String, taken: Int?): UnlockAttempt {
        // (0.45.0 §11.14.9 step 2; N2) No lock and unlock with a PIN typed outside the two-step unlock while a PIN or
        // password change is unresolved or the PIN uncertain: nothing is sent, and "still checking" is said.
        changeHoldFlow.value?.let { return UnlockAttempt.Failed(it, ChangeResolver.CODE_UNRESOLVED) }
        // (§11.14.9 step 2; chosen) The PIN as the screen's keypad took it; without a reading, taken now. A PIN taken
        // before a change neither locks the vault nor is sent (checked again inside the unlock, after the lock).
        pinTakenBefore(taken)?.let { return UnlockAttempt.Failed(it.kind, it.code) }
        return finishMoveRunner.run(pin, taken ?: record.pinGeneration)
    }

    override val pinGeneration: Int get() = record.pinGeneration

    /**
     * (§11.14.9 step 2; chosen, not in the spec) The refusal of an unlock whose PIN was typed on a flow's own screen and
     * taken at [taken], when [pinGeneration] moved since: the PIN is asked for again with the "earlier request" text, and
     * nothing is sent (no PIN attempt spent). Null when it may go out, or for the unlock screen (null [taken]).
     */
    private fun pinTakenBefore(taken: Int?): VaultFailure? =
        if (taken != null && taken != record.pinGeneration) VaultFailure(FailureKind.CREDENTIAL_UNSETTLED, CODE_PIN_TAKEN_BEFORE) else null

    /** (Release R) When this phone approved a transfer it still keeps the keys for; null otherwise. */
    override val transferKeptSince: StateFlow<Instant?> = transferKeptFlow.asStateFlow()

    override val transferKeepSeconds: Long get() = (local.transferKeepMs ?: KeptTransfer.KEEP_MS) / MS_PER_SECOND

    /**
     * At every start, on every return to the foreground, and when the 7 days run out while the process lives: for a
     * transfer approved 7 days ago or more the vault is asked ([keptEnd], §11.14.10): this phone's keys go only on a
     * definite answer that it is no longer the vault's app; without one they stay and the next start or return to the
     * foreground asks again ([transferCheckWaiting]). Before that, [transferKeptSince] tells the shell to say "Finish the
     * move on your new phone", and a timer runs to the end.
     */
    private suspend fun checkKeptTransfer() {
        val rec = record.recordGeneration
        val stored = local.transferApprovedAtMs ?: return
        val now = System.currentTimeMillis()
        // A clock behind the approval: the 7 days run from now, stored, so that later checks do not extend them again.
        val at = KeptTransfer.start(stored, now)
        if (at != stored) io { updateLocal(rec) { it.copy(transferApprovedAtMs = at) } }
        when (val k = KeptTransfer.decide(at, now, local.transferKeepMs ?: KeptTransfer.KEEP_MS)) {
            KeptTransfer.Decision.Erase -> {
                // Still kept while the vault is asked (and after an unclear answer): "The move didn't finish" stays offered.
                transferKeptFlow.value = Instant.ofEpochMilli(at)
                keptTransferTimer?.cancel()
                val o = keptEnd.run()
                if (o == KeptTransferEnd.Outcome.WAITING) {
                    android.util.Log.i("VaultManager", "the end-of-keep check got no definite answer: keys kept, checked again later")
                }
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
        keptEnd.clear()
        movedBackFlow.value = true
    }

    override val movedBack: StateFlow<Boolean> = movedBackFlow.asStateFlow()

    override fun acknowledgeMovedBack() {
        movedBackFlow.value = false
    }

    override val moveNotDone: StateFlow<Boolean> = moveNotDoneFlow.asStateFlow()

    override val movedAway: StateFlow<Boolean> = movedAwayFlow.asStateFlow()

    override fun acknowledgeMovedAway() {
        runCatching { wiper.acknowledgeMovedAway() }
        movedAwayFlow.value = false
    }

    override fun acknowledgeMoveNotDone() {
        moveNotDoneFlow.value = false
    }

    override val transferCheckWaiting: StateFlow<Boolean> get() = keptEnd.waiting

    /**
     * `device.list` sent over this phone's end-to-end session with the vault, answered within [KEPT_END_CHECK_MS]
     * ([KeptTransfer.verdict]). The answer must be sealed under the session's keys (held only by the enclave and this
     * phone) and carry `re` = this request's fresh id. No answer within the bound, a locked vault, a refusal or any other
     * failure is [KeptTransfer.Verdict.UNKNOWN] (owner decision 2026-10-23: never an erase); a cancelled check throws.
     */
    @Suppress("TooGenericExceptionCaught") // any failure is "unknown"
    private suspend fun keptEndVerdict(): KeptTransfer.Verdict = try {
        withTimeout(KEPT_END_CHECK_MS) {
            val s = session()
            KeptTransfer.verdict(guard { s.openApi.deviceList() }, s.device.deviceId)
        }
    } catch (_: TimeoutCancellationException) {
        KeptTransfer.Verdict.UNKNOWN
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.i("VaultManager", "the end-of-keep check failed: ${e.javaClass.simpleName}")
        KeptTransfer.Verdict.UNKNOWN
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
                // A wipe ends "being deleted" too (its marker went with the record).
                deletionWatch.stop()
                deletingMarker = null
                deletedApiMarker = null
                deletingDevice = null
                deletedNoticeFlow.value = false
                phaseFlow.endDeleting(phase)
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
        // Every other device this process made (one stopped for a deletion, whose session was already dropped), before
        // anything is erased: nothing still on its way saves after the erase.
        // Every step runs even if one throws (fail secure), the erase too; the first failure is rethrown after the last.
        val steps = ClearSteps()
        steps.step { forgetDevices() }
        steps.step { social.clear() }
        steps.step { attachments.reset() }
        steps.step { ownerCheck.clear() }
        steps.step { forgetOwnProfile() }
        steps.step { history.clear() }
        steps.step { items.clear() }
        steps.step { sharing.clear() }
        steps.step { feed.clear() }
        steps.step { devices.clear() }
        steps.step { votes.clear() }
        steps.step { canary.removeCanaryManifest() }
        openTransferId = null
        alarmFlow.value = null
        windowFlow.value = null
        relayPaused = false
        lockedElsewhereFlow.value = false
        steps.step { heldBack.release(AppPhase.SignedOut) }
        steps.step { wiper.resetMemory() }
        val erased = io { wiper.erase().also { if (it) wiper.finish() } }
        // A step that failed: the wipe is not reported complete (WipeRun offers "Try again").
        steps.finish()
        return erased
    }

    // --- BackgroundVault: the notification service (ANDROID-PLAN 0.1.23) ---

    /** This phone holds a vault (its setup finished): the notification service may start after a reboot. */
    val enrolledOnThisPhone: Boolean get() = local.setupComplete && local.replaced == null

    override fun held(): Boolean = ownerCheck.ownerCheck.value?.let { it.state != OwnerCheckState.OK } == true

    override val heldChanges: Flow<Boolean>
        get() = ownerCheck.ownerCheck.map { v -> v != null && v.state != OwnerCheckState.OK }.distinctUntilChanged()

    override fun pauseRelay() {
        // Requests before the app opens wait for their answers over this connection (OpeningWindow): paused, if the
        // phone is still locked, when they are done.
        if (phaseFlow.value != AppPhase.Locked || relayPaused || opening.deferPause()) return
        when (RelayPause.step(locked = true, paused = false, lockPending = lockKeeper.pending)) {
            RelayPause.Step.NONE -> Unit
            // A lock is on its way: the vault's answer to it, and its `vault.locking`, are collected over this
            // connection. It is paused when the lock is confirmed (below, in init).
            RelayPause.Step.AFTER_LOCK -> pauseWanted = true
            RelayPause.Step.STOP -> {
                pauseWanted = false
                val d = session?.device ?: return
                relayPaused = true
                d.stop()
            }
        }
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
        ownerCheck.onRelayReconnected()
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

    /**
     * Runs [block] on the IO dispatcher and turns every failure into a [VaultFailure]. [resend]: only for an idempotent
     * read without secrets ([vaultGuard]).
     */
    private suspend fun <T> guard(resend: Boolean = false, block: suspend () -> T): T = try {
        vaultGuard(resend, block)
    } catch (e: VaultFailure) {
        // MEMBER-API 1.2.0: a refusal while paused also shows the banner (status may not have been read yet).
        if (e.kind == FailureKind.SERVICE_PAUSED) pausedFlow.value = true
        // (ANDROID-PLAN 0.1.78) Signal (b): the member API's definite 410, from the error itself, never from the kind alone.
        apiDeleted(e)
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
        scope.launch { changes.check.collectSurviving("change check") { refreshChangeFlows() } }
    }

    companion object {
        /** The lock record's seal: the app's Keystore file key, purpose `lock-record`. */
        private fun lockSealer(file: File): LockRecordFile.Sealer {
            val store = KeystoreFileStore(file, "lock-record")
            return object : LockRecordFile.Sealer {
                override fun seal(plain: ByteArray) = store.seal(plain)

                override fun open(blob: ByteArray) = store.open(blob)
            }
        }

        private const val LOCK_FILE = "lock-record"
        private const val SOCIAL_FILE = "social.bin"
        private const val PENDING_ASKS_FILE = "pending-asks.bin"
        private const val CANARY_FILE = "canary-manifest.bin"
        private const val OWNER_CHECK_FILE = "owner-check.bin"
        private const val HTTP_OK = 200
        private const val LEGACY_SESSION_FILE = "member-session.bin"
        private const val SYNC_ACCOUNT_CHANGED = "account.changed"
        private const val SYNC_PROFILE_CHANGED = "profile.changed"

        /** (§10.1, §11.10.6) The vault first ran under a new release: `release`, `release_number` (ANDROID-PLAN 0.1.54). */
        private const val SYNC_VAULT_RELEASE = "vault.release"

        /** `device.unlinked`'s reason in a deletion's step 1 (§12.5). */
        private const val REASON_VAULT_DELETED = "vault_deleted"

        /** (ANDROID-PLAN 0.1.54) Refused: the vault is being deleted, and nothing more goes to it from this phone. */
        const val CODE_DELETING = "vault_deleting"
        private const val CODE_NO_RECOVERY = "no_recovery"
        private const val UPDATE_MOVED = "moved"
        private const val UPDATE_ABANDONED = "abandoned"
        private const val COUNTER_UNAVAILABLE = "unavailable"
        private const val MS_PER_SECOND = 1_000L
        private const val ENV_STAGING = "staging"
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

        /** "Lock it now": how long the status read that names the opening may take; then the lock goes unbound. */
        private val LOCK_STATUS_WAIT: Duration = Duration.ofSeconds(5)
        private const val TYPE_VAULT_LOCK = "vault.lock"

        /** The code of a vault operation refused on this phone because a lock is pending ([openApi]). */
        const val CODE_LOCK_PENDING = ReleaseUpdateManager.CODE_LOCK_PENDING

        /**
         * (§11.14.9 step 2; chosen, not in the spec) An unlock of the release update or of "Finish the move" refused with
         * nothing sent: which PIN the vault expects may have changed since the PIN was typed ([pinGeneration]).
         */
        const val CODE_PIN_TAKEN_BEFORE = ReleaseUpdateManager.CODE_PIN_TAKEN_BEFORE
        private const val LIST_PAGE = 500
        private const val MAX_PAGES = 4
        private const val FINGERPRINT_HEX = 16
        private const val CODE_NO_BACKUP = "no_backup"

        /** (§11.14.9) The unresolved change could not be stored before the send: nothing was sent. */
        private const val CODE_NOT_STORED = "change_not_stored"
        private const val CODE_STALE = "stale_credential"
        private const val CODE_RENEW_UNCONFIRMED = "renew_unconfirmed"
        private const val CODE_NEW_PIN_NEEDED = "new_pin_needed"

        /** [vaultGuard]'s code for a Keystore failure: this phone's stored state for the vault could not be read. */
        private const val CODE_KEYSTORE = "keystore"
        private const val CODE_NOT_AVAILABLE = "not_available"
        private const val CODE_NOT_TRANSFER = "not_transfer"
        private const val CODE_EXISTS = "exists"

        /** (0.34.0) The resume of a lost `ok`: the unlock opened the vault but carried no `vault_bundle` for this phone. */
        const val CODE_NO_BUNDLE = "no_bundle"

        /** (0.1.78) Refused: an open's answer is unknown; K is not forgotten and the phone does not start over. */
        const val CODE_UNDECIDED = "undecided"
        private const val CODE_REUSE_NOT_SAVED = "reuse_not_saved"
        private const val CODE_HISTORY_NOT_SAVED = "history_not_saved"
        private const val CODE_RETRY = "retry"

        /** (0.1.80) A recovery code or claim naming another vault than the earlier open's. */
        const val CODE_OTHER_VAULT = "other_vault"
        private const val CODE_TRANSFER_INCOMPLETE = "transfer_incomplete"
        private const val RECOVERY_REGISTERED = "registered"
        private const val RECOVERY_PENDING = "pending"
        private const val RECOVERY_AVAILABLE = "available"

        /** A register POST queued this long ago is past the member API's queue (5.5 minutes) with a wide margin. */
        private const val SETTLE_AFTER_MS = 30L * 60 * 1000

        /** (0.1.78) The lost-ok unlock's outcomes for [RecoveryOpenOutcome.code]: open again, or a new recovery with K. */
        const val CODE_OPEN_AGAIN = "open_again"
        const val CODE_START_NEW = "start_new"

        /** (0.34.0) A release with `recovery_r` and without `recovery_offered` (§11.10.9). */
        const val CODE_NOT_OFFERED = "not_offered"

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

        /** (A6 audit N3) A resume joined by [enroll] made no vault: the member enrolls again with the PIN. */
        const val CODE_ENROLL_NOT_MADE = "enroll_not_made"

        /** (N3) The relay could not be read while an earlier request's `vault.enrolled` was awaited: kept, try again. */
        const val CODE_ENROLL_RELAY = "enroll_relay"

        fun enrollFailure(code: String?): FailureKind = when (code) {
            "vault_exists" -> FailureKind.VAULT_EXISTS
            "attestation" -> FailureKind.ATTESTATION
            "manifest", "release_key" -> FailureKind.MANIFEST
            else -> FailureKind.OTHER
        }

        @Suppress("CyclomaticComplexMethod") // one branch per member API code
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
            // 410 only with the status: `release_unavailable` is also a 410 and is not this.
            VaultDeletion.CODE_VAULT_DELETED, VaultDeletion.CODE_ACCOUNT_DELETED ->
                if (e.status == HTTP_GONE) FailureKind.VAULT_DELETED else FailureKind.OTHER
            else -> FailureKind.OTHER
        }

        private const val HTTP_GONE = 410

        /**
         * [code] of an error answer to a request of [type] (ANDROID-PLAN 0.1.57): `forbidden` to a holder-only type is
         * [FailureKind.NOT_HOLDER]; to any other type, as every code [opFailure] does not map, [FailureKind.OTHER].
         */
        fun opFailure(code: String, type: String?): FailureKind =
            if (code == VaultCodes.FORBIDDEN && type in VaultCodes.HOLDER_ONLY) FailureKind.NOT_HOLDER else opFailure(code)

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
            // (ANDROID-PLAN 0.1.57 (1), 0.1.58 (3)) With or without `{"reason": "no_holder"}`.
            VaultCodes.CREDENTIAL_REQUIRED -> FailureKind.CREDENTIAL_REQUIRED
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
