// Debug-only sample data for screenshots: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength", "MagicNumber", "TooManyFunctions", "LargeClass")

package com.vettid.app.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vettid.app.ui.AccountSheet
import com.vettid.core.data.vault.AuditCategory
import com.vettid.core.data.vault.AuditFilter
import com.vettid.core.data.vault.AuditRecord
import com.vettid.core.data.vault.ExportAnswer
import com.vettid.core.data.vault.ExportFormat
import com.vettid.feature.history.DatePreset
import com.vettid.feature.history.ExportFilterWords
import com.vettid.feature.history.ExportRefusal
import com.vettid.feature.history.ExportStep
import com.vettid.feature.history.HistoryExportActions
import com.vettid.feature.history.HistoryExportContent
import com.vettid.feature.history.HistoryActions
import com.vettid.feature.history.HistoryEntryScreen
import com.vettid.feature.history.HistoryEntryUiState
import com.vettid.feature.history.HistoryScreen
import com.vettid.feature.history.HistoryUiState
import com.vettid.app.ui.OwnerCheckBanners
import com.vettid.app.ui.PendingDeletionBanner
import com.vettid.core.data.vault.DeletionView
import com.vettid.app.ui.OwnerCheckGateState
import com.vettid.core.data.vault.OwnerCheckNotice
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.WaitingCounts
import com.vettid.feature.credential.NewCredentialActions
import com.vettid.feature.credential.NewCredentialContent
import com.vettid.feature.credential.NewCredentialUiState
import com.vettid.feature.onboarding.HoldOffChoice
import com.vettid.feature.onboarding.OwnerCheckActions
import com.vettid.feature.onboarding.OwnerCheckContent
import com.vettid.feature.onboarding.OwnerCheckMessage
import com.vettid.feature.onboarding.OwnerCheckMode
import com.vettid.feature.onboarding.OwnerCheckUiState
import com.vettid.feature.settings.OwnerCheckSettingsUiState
import com.vettid.app.ui.LocalThemeController
import com.vettid.core.data.policy.PasswordPolicy
import com.vettid.core.data.prefs.AppLockTimeout
import com.vettid.core.data.prefs.AppPreferences
import com.vettid.core.data.prefs.ThemePreference
import com.vettid.core.data.vault.AccountInfo
import com.vettid.core.data.vault.AttestationInfo
import com.vettid.core.data.vault.CredentialAlarm
import com.vettid.core.data.vault.CredentialStatus
import com.vettid.core.data.vault.EnrollStep
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.PreflightInfo
import com.vettid.core.data.vault.RecoveryView
import com.vettid.core.data.vault.ReleaseInfoView
import com.vettid.core.data.vault.ReleaseView
import com.vettid.core.data.vault.VaultOverview
import com.vettid.core.ui.components.ShellChrome
import com.vettid.core.ui.components.StepState
import com.vettid.core.ui.components.UrgentBanner
import com.vettid.core.data.social.Approval
import com.vettid.core.data.social.ApprovalParser
import com.vettid.core.data.social.RequestEnd
import com.vettid.core.data.social.RequestState
import com.vettid.core.data.social.AuthenticationState
import com.vettid.core.data.social.ConnectionInfo
import com.vettid.core.data.social.ConnectionState
import com.vettid.core.data.social.ConversationSummary
import com.vettid.core.data.social.GrantEntry
import com.vettid.core.data.social.InviteInfo
import com.vettid.core.data.social.InviteTtl
import com.vettid.core.data.social.MessageInfo
import com.vettid.core.data.social.OutstandingInvite
import com.vettid.core.data.social.PeerDecline
import com.vettid.core.data.social.ShareItem
import com.vettid.feature.approvals.ApprovalDetailScreen
import com.vettid.feature.approvals.ApprovalDetailUiState
import com.vettid.feature.approvals.ApprovalsScreen
import com.vettid.feature.approvals.ApprovalsUiState
import com.vettid.feature.approvals.DecisionActions
import com.vettid.feature.connections.AcceptActions
import com.vettid.feature.connections.AcceptScreen
import com.vettid.feature.connections.AcceptStep
import com.vettid.feature.connections.AcceptUiState
import com.vettid.feature.connections.ConnectionDetailScreen
import com.vettid.feature.connections.ConnectionDetailUiState
import com.vettid.feature.connections.ConnectionsActions
import com.vettid.feature.connections.ConnectionsScreen
import com.vettid.feature.connections.ConnectionsUiState
import com.vettid.feature.connections.DetailActions
import com.vettid.feature.connections.DetailConfirm
import com.vettid.feature.connections.DetailSharing
import com.vettid.feature.connections.InviteActions
import com.vettid.feature.connections.InviteScreen
import com.vettid.feature.connections.InviteStep
import com.vettid.feature.connections.InviteUiState
import com.vettid.feature.connections.ScanScreen
import com.vettid.feature.connections.ScanUiState
import com.vettid.feature.messages.ConversationActions
import com.vettid.feature.messages.ConversationScreen
import com.vettid.feature.messages.ConversationUiState
import com.vettid.feature.messages.MessagesScreen
import com.vettid.feature.messages.MessagesUiState
import com.vettid.feature.messages.NewMessageScreen
import com.vettid.feature.messages.NewMessageUiState
import com.vettid.feature.credential.AlarmActions
import com.vettid.feature.credential.AlarmContent
import com.vettid.feature.credential.AlarmStep
import com.vettid.feature.credential.AlarmUiState
import com.vettid.feature.credential.BackupOffDialog
import com.vettid.feature.credential.ChangePasswordContent
import com.vettid.feature.credential.ChangePasswordUiState
import com.vettid.feature.credential.CredentialActions
import com.vettid.feature.credential.CredentialContent
import com.vettid.feature.credential.CredentialUiState
import com.vettid.feature.credential.RotateContent
import com.vettid.feature.credential.RotateUiState
import com.vettid.feature.onboarding.AppLockScreen
import com.vettid.feature.onboarding.OnboardingActions
import com.vettid.feature.onboarding.OnboardingContent
import com.vettid.feature.onboarding.OnboardingStep
import com.vettid.feature.onboarding.OnboardingUiState
import com.vettid.feature.onboarding.UnlockActions
import com.vettid.feature.onboarding.UnlockContent
import com.vettid.feature.onboarding.UnlockMessage
import com.vettid.feature.onboarding.UnlockUiState
import com.vettid.feature.onboarding.UnlockViewModel
import com.vettid.feature.onboarding.CodeRefusal
import com.vettid.feature.onboarding.RecoverActions
import com.vettid.feature.onboarding.RecoverContent
import com.vettid.feature.onboarding.RecoverStep
import com.vettid.feature.onboarding.RecoverUiState
import com.vettid.feature.onboarding.TransferInActions
import com.vettid.feature.onboarding.TransferInContent
import com.vettid.feature.onboarding.TransferInStep
import com.vettid.feature.onboarding.TransferInUiState
import com.vettid.feature.settings.TransferOutActions
import com.vettid.feature.settings.TransferOutContent
import com.vettid.feature.settings.TransferOutStep
import com.vettid.feature.settings.TransferOutUiState
import com.vettid.core.data.vault.SubscriptionInfo
import com.vettid.feature.onboarding.ScanRefusal
import com.vettid.core.data.vault.TransferOfferView
import com.vettid.core.data.vault.TransferPendingView
import com.vettid.feature.settings.AttestationContent
import com.vettid.core.data.vault.CanaryManifestRepository
import com.vettid.core.data.vault.CanaryManifestView
import com.vettid.feature.settings.ChangeNameActions
import com.vettid.feature.settings.ChangeNameContent
import com.vettid.feature.settings.ChangeNameMessage
import com.vettid.feature.settings.ChangeNameStep
import com.vettid.feature.settings.ChangeNameUiState
import com.vettid.feature.settings.SharedProfileActions
import com.vettid.feature.settings.SharedProfileContent
import com.vettid.feature.settings.SharedProfileUiState
import com.vettid.core.data.vault.NameRequestState
import com.vettid.core.data.vault.NameRequestView
import com.vettid.core.data.vault.OwnProfile
import com.vettid.core.data.social.SharedProfileItem
import com.vettid.feature.settings.ChangePinContent
import com.vettid.feature.settings.ChangePinUiState
import com.vettid.feature.settings.DeleteVaultActions
import com.vettid.feature.settings.DeleteVaultContent
import com.vettid.feature.settings.DeleteVaultUiState
import com.vettid.feature.settings.LoadState
import com.vettid.feature.settings.RecoveryContent
import com.vettid.feature.settings.RecoveryUiState
import com.vettid.feature.settings.SettingsActions
import com.vettid.feature.settings.SettingsContent
import com.vettid.feature.settings.SettingsUiState
import com.vettid.feature.settings.VaultStatusContent
import java.time.Instant

/** No-op actions for the catalog. */
private object NoOnboarding : OnboardingActions {
    override fun start() = Unit
    override fun startRecovery() = Unit
    override fun startTransfer() = Unit
    override fun recover() = Unit
    override fun transfer() = Unit
    override fun leaveMove() = Unit
    override fun newVaultAfterMove() = Unit
    override fun typeCode() = Unit
    override fun scanned(text: String) = Unit
    override fun setEmail(v: String) = Unit
    override fun setCode(v: String) = Unit
    override fun submitCode() = Unit
    override fun confirmAccount() = Unit
    override fun back(): Boolean = false
    override fun useAnotherCode() = Unit
    override fun enrollAnyway() = Unit
    override fun setPin(v: String) = Unit
    override fun submitPin() = Unit
    override fun setPinConfirm(v: String) = Unit
    override fun submitPinConfirm() = Unit
    override fun setPassword(v: String) = Unit
    override fun setPasswordConfirm(v: String) = Unit
    override fun submitPassword() = Unit
    override fun setBackup(on: Boolean) = Unit
    override fun acknowledgeBackupOff(ack: Boolean) = Unit
    override fun submitBackup() = Unit
    override fun run() = Unit
    override fun editAfterFailure() = Unit
    override fun finish() = Unit
}

private object NoRecover : RecoverActions {
    override fun scan() = Unit
    override fun back(): Boolean = false
    override fun scanned(text: String) = Unit
    override fun retryPreflight() = Unit
    override fun setApproveOffer(approve: Boolean) = Unit
    override fun setPin(v: String) = Unit
    override fun submitPin() = Unit
    override fun setPassword(v: String) = Unit
    override fun submitPassword() = Unit
    override fun finish() = Unit
}

private object NoTransferIn : TransferInActions {
    override fun scan() = Unit
    override fun paste() = Unit
    override fun back(): Boolean = false
    override fun scanned(text: String) = Unit
    override fun setInput(v: String) = Unit
    override fun submitInput() = Unit
    override fun cancel() = Unit
    override fun again() = Unit
    override fun finish() = Unit
}

private object NoTransferOut : TransferOutActions {
    override fun create() = Unit
    override fun codesMatch() = Unit
    override fun askReject(show: Boolean) = Unit
    override fun reject() = Unit
    override fun setPin(v: String) = Unit
    override fun setPassword(v: String) = Unit
    override fun approve() = Unit
    override fun askApprove(show: Boolean) = Unit
    override fun leave() = Unit
}

private object NoOwnerCheck : OwnerCheckActions {
    override fun setPin(v: String) = Unit
    override fun setPassword(v: String) = Unit
    override fun setHoldOff(choice: HoldOffChoice) = Unit
    override fun submit() = Unit
    override fun lockVault() = Unit
}

private object NoUnlock : UnlockActions {
    override fun setPassword(v: String) = Unit
    override fun retryPreflight() = Unit
    override fun acknowledgeUpdate() = Unit
    override fun setApproveOffer(approve: Boolean) = Unit
    override fun setPin(v: String) = Unit
    override fun submit() = Unit
    override fun cancelRecoveryAndUnlock() = Unit
    override fun askErase() = Unit
    override fun dismissErase() = Unit
    override fun confirmErase() = Unit
}

/**
 * DEBUG ONLY. Every A3 and A4 screen with sample state, for screenshots without a
 * vault: `--es vettid.start screen:<name>` (names below). Sample values are
 * made up; nothing here talks to a vault.
 */
object ScreenCatalog {
    private const val EMAIL = "sam@example.org"
    private fun release(n: Long, status: String = "active") =
        ReleaseView(n, "%02d".format(n).repeat(48), status, null, "https://vettid.org/security/releases/$n")

    private val canaryView = CanaryManifestView(2, "4353463f85c4012f", "ab".repeat(32), listOf(release(1, "deprecated"), release(2)))

    private val onboarding = OnboardingUiState(email = EMAIL)
    private val sampleAccount = AccountInfo(
        emailHint = "s***@example.org", state = "member", accountStatus = "active", firstName = "Sam", lastName = "Rivera",
        subscription = SubscriptionInfo("Annual", SubscriptionInfo.STATUS_ACTIVE, true, Instant.parse("2027-10-01T00:00:00Z")),
        email = "sam.rivera@example.org",
    )

    /** History samples (ANDROID-PLAN 0.1.11). */
    private val historyNames = mapOf("c1" to "Alice Moreau", "c2" to "Bob Okafor")
    private val historyEntries = listOf(
        AuditRecord("e10", 10, Instant.now().minusSeconds(60), "audit.exported", deviceId = "app-1", ref = "format=csv;count=40;seqs=1-9;filters=none"),
        AuditRecord("e9", 9, Instant.now().minusSeconds(120), "message.received", connectionId = "c1", ref = "m-01J9", direction = "in"),
        AuditRecord("e8", 8, Instant.now().minusSeconds(900), "vault.unlocked"),
        AuditRecord("e7", 7, Instant.now().minusSeconds(3_600), "connection.added", connectionId = "c2"),
        AuditRecord("e6", 6, Instant.now().minusSeconds(86_400), "credential.password_changed"),
        AuditRecord("e5", 5, Instant.now().minusSeconds(90_000), "item.revealed", ref = "it-01J8"),
        AuditRecord("e4", 4, Instant.now().minusSeconds(200_000), "owner_check.passed"),
        AuditRecord("e3", 3, Instant.now().minusSeconds(300_000), "drop.rate_limited", connectionId = "c3"),
        AuditRecord("e2", 2, Instant.now().minusSeconds(400_000), "leash.item.read.summary", deviceId = "agent-1", ref = "12"),
        AuditRecord("e1", 1, Instant.now().minusSeconds(500_000), "zebra.future_kind"),
    )
    private val exportPreview = ExportAnswer(
        count = 40, more = false, uptoSeq = 790, uptoHash = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", oldestSeq = 700, newestSeq = 790,
        oldestAt = Instant.parse("2026-10-01T09:12:00Z"), newestAt = Instant.parse("2026-10-08T17:40:00Z"),
    )
    private val exportWords = ExportFilterWords(
        AuditFilter(category = AuditCategory.MESSAGES, connectionId = "c1", query = "lisbon", since = Instant.parse("2026-10-01T04:00:00Z")),
        connectionName = "Alice Moreau",
        dates = "Last 7 days",
    )
    private val namePending = NameRequestView(4, "Sam", "King", Instant.parse("2026-10-07T12:00:00Z"), NameRequestState.PENDING)
    private val nameRefused = namePending.copy(state = NameRequestState.REFUSED, reason = NameRequestView.REASON_TOO_SOON)
    private val chrome = ShellChrome(accountName = "Sam Rivera", onMenuClick = {}, onAvatarClick = {})
    private val credential = CredentialStatus(true, 7, "Jk4m2Qx9TzA1", "2026-10-04T14:12:00Z", null, true, 300, 3)
    private val alarm = CredentialAlarm("01JABCDEF0123456789ABCDEFG", CredentialAlarm.STATE_FROZEN, "2026-10-04T14:20:00Z", "other")


    // --- A4 sample data (made up) ---
    private val t0: Instant = Instant.parse("2026-10-04T09:00:00Z")
    // VAULT-MESSAGING 0.18.0 §10.8: titled from the names on the peer's account; the display name is secondary.
    private val sam = ConnectionInfo("c1", "Sam", ConnectionState.ACTIVE, favorite = true, firstName = "Samira", lastName = "Rivera",
        sharedItems = listOf(SharedProfileItem("i1", "Where I live", listOf("City" to "Lisbon"))),
        keyFingerprint = "9a1f bb7d 873e eafb 494b ef94 f072 7b25", createdAt = t0.minusSeconds(86_400 * 30), lastActiveAt = t0.plusSeconds(600))
    private val alex = ConnectionInfo("c2", "Alex", ConnectionState.ACTIVE, firstName = "Alexandra", lastName = "Okafor",
        keyFingerprint = "1c2d 3e4f 4e8e e8f7 0a0b 0c0d 0e0f 1011", createdAt = t0.minusSeconds(86_400 * 3), lastActiveAt = t0.minusSeconds(7200))
    private val jo = ConnectionInfo("c3", "", ConnectionState.STALE, firstName = "Jo", lastName = "Lindqvist", createdAt = t0.minusSeconds(86_400 * 90))
    /** Between activation and the first profile.update on the accepter's side: no names yet (§10.8). */
    /** Sharing with Samira both ways: a rule and two items out, one item in, one request waiting. */
    private val bothWays = DetailSharing(
        rules = listOf(
            com.vettid.core.data.items.ShareRule(
                "r1", 1, "c1", tags = listOf("medical"), mode = com.vettid.core.data.items.ShareMode.ASK,
                included = listOf("i1"), pending = listOf("i3"),
            ),
            com.vettid.core.data.items.ShareRule(
                "r2", 1, "c1", tags = listOf("travel"), mode = com.vettid.core.data.items.ShareMode.AUTO, uses = 5,
                expiresAt = Instant.parse("2027-04-30T00:00:00Z"), included = listOf("i2"),
            ),
        ),
        given = listOf(
            com.vettid.core.data.items.GrantView("g1", "c1", com.vettid.core.data.items.GrantDirection.GIVEN, "i1", "Allergies", "medical", ruleId = "r1"),
            com.vettid.core.data.items.GrantView("g2", "c1", com.vettid.core.data.items.GrantDirection.GIVEN, "i2", "Passport", "identity_document", uses = 3, used = 1),
        ),
        received = listOf(
            com.vettid.core.data.items.GrantView("g3", "c1", com.vettid.core.data.items.GrantDirection.RECEIVED, "x1", "Home address", "contact"),
        ),
        asked = listOf(com.vettid.core.data.items.GrantAsk("q1", "c1", listOf(com.vettid.core.data.items.GrantAskEntry("category", "insurance")), "pending")),
        loaded = true,
    )
    private val riley = ConnectionInfo("c4", "Riley", ConnectionState.ACTIVE, createdAt = t0, lastActiveAt = t0)
    private val connections = listOf(sam, alex, jo, riley)
    private fun msg(id: String, conn: String, text: String, out: Boolean, min: Long, read: Boolean = true) =
        MessageInfo(conn, id, out, text, t0.plusSeconds(min * 60), delivered = true, read = read)
    private val thread = listOf(
        msg("m1", "c1", "Are we still on for Saturday?", false, 0),
        msg("m2", "c1", "Yes, 10am at the market. I will bring the bikes.", true, 2),
        msg("m3", "c1", "Perfect. Sending the address through here so it stays between us.", false, 5),
        msg("m4", "c1", "Got it, thanks!", true, 6, read = false),
    )
    private val conversations = listOf(
        ConversationSummary(sam, thread.last(), 0),
        ConversationSummary(alex, msg("m9", "c2", "Can you review the contract tonight?", false, -90, read = false), 2),
        ConversationSummary(jo, null, 0),
    )
    private val invite = InviteInfo(
        "01JINVITE0000000000000000A",
        "eyJ2IjoyLCJ0IjoiYyIsInIiOiJodHRwczovL3JlbGF5LnZldHRpZC50ZXN0IiwiYyI6ImFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6IiwiaCI6IngiLCJrIjoieSIsImUiOjE3OTExMDAwMDB9",
        """{"v":2,"t":"c","r":"https://relay.vettid.test","c":"abcdefghijklmnopqrstuvwxyz","h":"x","k":"y","e":1791100000}""",
        Instant.now().plusSeconds(540),
        remote = false,
    )
    private val request = Approval.ConnectionRequest("p1", invite.inviteId, "042817", false, "Morgan Lee", null, t0, t0.plusSeconds(604_800), displayName = "Mo")
    private val outgoing = Approval.OutgoingRequest(
        "c9", "315904", remote = true, name = "Jordan Park", state = RequestState.PENDING, peerApproved = false,
        introducedBy = null, receivedAt = t0, exp = t0.plusSeconds(691_200),
    )
    private val outgoingWaiting = outgoing.copy(connectionId = "c10", sas = null, state = RequestState.WAITING, name = "Riley Chen")
    private val approvals: List<Approval> = listOf(
        request,
        Approval.Authentication("a1", "c1", "Confirm before I send the keys", t0, t0.plusSeconds(600), "Sam Rivera"),
        Approval.GrantRequest("g1", "c2", listOf(GrantEntry("item", "01JITEM", "Passport", true), GrantEntry("category", "insurance", "Your insurance card", false)), 1, 604_800, "Booking the trip", t0, t0.plusSeconds(86_400), "Alex (work)"),
        Approval.CriticalUse("u1", "c1", "Signing key", "Private key", "sign", CRITICAL_PAYLOAD, ApprovalParser.payloadSha256(CRITICAL_PAYLOAD)!!, "Sign the lease agreement", t0, t0.plusSeconds(86_400), "Sam Rivera"),
        Approval.ShareDecision("r1", "c1", null, listOf(ShareItem("i1", "Allergy list", "health", "data")), "tagged", t0, null, "Sam Rivera"),
        Approval.DeviceRequest("device.session.pending", "s1", "Office laptop", "desktop", null, t0, t0.plusSeconds(300)),
        outgoing,
        outgoingWaiting,
        request.copy(pendingId = "p2", name = "Casey Novak", sas = "770312", state = RequestState.APPROVED),
    )

    @Composable
    private fun Ob(state: OnboardingUiState) =
        OnboardingContent(state, NoOnboarding, {}) { m -> Box(m.background(MaterialTheme.colorScheme.surfaceContainerHigh)) }

    // --- recovery and transfer sample data (made up) ---
    private val recState = RecoverUiState(step = RecoverStep.INTRO)
    private val pinState = recState.copy(
        step = RecoverStep.PIN, preflight = PreflightInfo(release(3), 0, false, false, null), pin = "975310", emailHint = "s***@example.org",
    )

    @Composable
    private fun Rec(state: RecoverUiState) = RecoverContent(state, NoRecover, {}, {}, {}) { m -> Box(m.background(MaterialTheme.colorScheme.surfaceContainerHigh)) }

    @Composable
    private fun TIn(state: TransferInUiState) = TransferInContent(state, NoTransferIn, {}) { m -> Box(m.background(MaterialTheme.colorScheme.surfaceContainerHigh)) }

    private val transferOffer = TransferOfferView(
        "01JTRANSFER000000000000000",
        """{"v":2,"t":"p","r":"https://relay.vettid.test","c":"abcdefghijklmnopqrstuvwxyz","h":"x","k":"y","e":1791100000}""",
        "eyJ2IjoyLCJ0IjoicCIsInIiOiJodHRwczovL3JlbGF5LnZldHRpZC50ZXN0IiwiYyI6ImFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6In0",
        Instant.now().plusSeconds(540),
    )
    private val transferPending = TransferPendingView(transferOffer.transferId, "Pixel 10 Pro", "042817")
    private val out = TransferOutUiState(step = TransferOutStep.SHOWING, offer = transferOffer, secondsLeft = 540)

    @Composable
    private fun TOut(state: TransferOutUiState) = TransferOutContent(state, NoTransferOut, {}, {})

    private val okView = OwnerCheckView(OwnerCheckState.OK, Instant.now().plusSeconds(20_000), 86_400, 0, hold = true, holdOffUntil = null)
    private val heldView = okView.copy(state = OwnerCheckState.HELD, deadline = Instant.now().minusSeconds(4_000), waiting = WaitingCounts(3, 1, 0, 2))

    val screens: Map<String, @Composable () -> Unit> = linkedMapOf(
        "onboarding.welcome" to { Ob(onboarding) },
        "onboarding.setup_scan" to { Ob(onboarding.copy(step = OnboardingStep.SETUP_SCAN)) },
        "onboarding.setup_other_env" to {
            Ob(onboarding.copy(step = OnboardingStep.SETUP_SCAN, scanRefusal = ScanRefusal.OTHER_ENVIRONMENT, otherApi = "https://account.staging.vettid.org"))
        },
        "onboarding.setup_type" to { Ob(onboarding.copy(step = OnboardingStep.SETUP_TYPE, codeInput = "K7QM-4XRP")) },
        "onboarding.setup_type_invalid" to {
            Ob(onboarding.copy(step = OnboardingStep.SETUP_TYPE, codeInput = "K7QM-4XR0", codeInvalid = true))
        },
        "onboarding.setup_refused" to {
            Ob(onboarding.copy(step = OnboardingStep.SETUP_TYPE, codeInput = "K7QM-4XRP", error = FailureKind.SETUP_CODE_INVALID))
        },
        "onboarding.confirm_account" to { Ob(onboarding.copy(step = OnboardingStep.CONFIRM_ACCOUNT, emailHint = "s***@example.org")) },
        "onboarding.elsewhere" to { Ob(onboarding.copy(step = OnboardingStep.VAULT_ELSEWHERE)) },
        "onboarding.pin" to { Ob(onboarding.copy(step = OnboardingStep.PIN_CREATE, pin = "1234")) },
        "onboarding.pin_problem" to {
            Ob(onboarding.copy(step = OnboardingStep.PIN_CREATE, pin = "123456", pinProblem = com.vettid.core.data.policy.PinPolicy.Problem.SEQUENCE))
        },
        "onboarding.pin_confirm" to { Ob(onboarding.copy(step = OnboardingStep.PIN_CONFIRM, pinConfirm = "97531", pinMismatch = true)) },
        "onboarding.password" to {
            Ob(
                onboarding.copy(
                    step = OnboardingStep.PASSWORD, password = "correct horse battery", passwordConfirm = "correct horse battery",
                    passwordStrength = PasswordPolicy.Strength.GOOD,
                ),
            )
        },
        "onboarding.backup" to { Ob(onboarding.copy(step = OnboardingStep.BACKUP)) },
        "onboarding.backup_off" to { Ob(onboarding.copy(step = OnboardingStep.BACKUP, backup = false)) },
        "onboarding.progress" to {
            Ob(
                onboarding.copy(
                    step = OnboardingStep.PROGRESS, busy = true,
                    progress = EnrollStep.entries.mapIndexed { i, s -> s to if (i < 3) StepState.DONE else if (i == 3) StepState.ACTIVE else StepState.PENDING },
                ),
            )
        },
        "onboarding.progress_failed" to {
            Ob(
                onboarding.copy(
                    step = OnboardingStep.PROGRESS, error = FailureKind.ATTESTATION,
                    progress = EnrollStep.entries.mapIndexed { i, s -> s to if (i == 0) StepState.FAILED else StepState.PENDING },
                ),
            )
        },
        "onboarding.done" to { Ob(onboarding.copy(step = OnboardingStep.DONE)) },
        "recover" to { Rec(recState) },
        "recover.scan" to { Rec(recState.copy(step = RecoverStep.SCAN, refusal = CodeRefusal.NOT_A_CODE)) },
        "recover.other_env" to {
            Rec(recState.copy(step = RecoverStep.SCAN, refusal = CodeRefusal.OTHER_ENVIRONMENT, otherApi = "https://account.staging.vettid.org"))
        },
        "recover.not_available" to { Rec(recState.copy(step = RecoverStep.SCAN, refusal = CodeRefusal.NOT_AVAILABLE)) },
        "recover.bad_code" to { Rec(recState.copy(step = RecoverStep.SCAN, refusal = CodeRefusal.BAD_CODE, wrongCodes = 2)) },
        "recover.voided" to { Rec(recState.copy(step = RecoverStep.SCAN, refusal = CodeRefusal.VOIDED, wrongCodes = 5)) },
        "recover.too_early" to { Rec(recState.copy(step = RecoverStep.SCAN, refusal = CodeRefusal.TOO_EARLY)) },
        "recover.code_expired" to { Rec(recState.copy(step = RecoverStep.SCAN, refusal = CodeRefusal.EXPIRED)) },
        "recover.registering" to { Rec(recState.copy(step = RecoverStep.REGISTERING)) },
        "recover.pin" to { Rec(pinState) },
        "recover.pin_offer" to { Rec(pinState.copy(preflight = PreflightInfo(release(3, "deprecated"), 0, false, false, release(4)), approveOffer = true)) },
        "recover.pin_backoff" to { Rec(pinState.copy(pin = "", pinWrong = true, waitSeconds = 75)) },
        "recover.pin_ended" to { Rec(pinState.copy(preflight = null, preflightError = FailureKind.RELEASE_ENDED)) },
        "recover.pin_cancelled" to { Rec(pinState.copy(pin = "", error = FailureKind.OTHER, errorCode = "unknown_device")) },
        "recover.password" to { Rec(recState.copy(step = RecoverStep.PASSWORD, password = "correct horse battery")) },
        "recover.password_wrong" to { Rec(recState.copy(step = RecoverStep.PASSWORD, error = FailureKind.BAD_PASSWORD)) },
        "recover.no_backup" to { Rec(recState.copy(step = RecoverStep.NO_BACKUP)) },
        "recover.done" to { Rec(recState.copy(step = RecoverStep.DONE)) },
        "transfer_in" to { TIn(TransferInUiState()) },
        "transfer_in.scan" to { TIn(TransferInUiState(step = TransferInStep.SCAN)) },
        "transfer_in.paste" to { TIn(TransferInUiState(step = TransferInStep.PASTE, input = "not a code", inputProblem = FailureKind.INVITE_INVALID)) },
        "transfer_in.connecting" to { TIn(TransferInUiState(step = TransferInStep.CONNECTING)) },
        "transfer_in.compare" to { TIn(TransferInUiState(step = TransferInStep.COMPARE, sas = "042817", secondsLeft = 563)) },
        "transfer_in.done" to { TIn(TransferInUiState(step = TransferInStep.DONE)) },
        "transfer_in.done_no_guid" to { TIn(TransferInUiState(step = TransferInStep.DONE, canUnlockLater = false)) },
        "transfer_in.rejected" to { TIn(TransferInUiState(step = TransferInStep.REJECTED)) },
        "transfer_in.timed_out" to { TIn(TransferInUiState(step = TransferInStep.TIMED_OUT)) },
        "transfer_in.no_answer" to { TIn(TransferInUiState(step = TransferInStep.NOT_ANSWERED)) },
        "unlock" to { UnlockContent(UnlockUiState(loading = false, email = EMAIL, preflight = PreflightInfo(release(3), 3, false, false, null), pin = "1234"), NoUnlock) },
        "unlock.updated" to {
            UnlockContent(UnlockUiState(loading = false, email = EMAIL, preflight = PreflightInfo(release(4, "deprecated"), 3, true, false, release(5))), NoUnlock)
        },
        "unlock.rollback" to { UnlockContent(UnlockUiState(loading = false, email = EMAIL, preflight = PreflightInfo(release(2), 3, false, true, null)), NoUnlock) },
        "unlock.backoff" to {
            UnlockContent(
                UnlockUiState(loading = false, email = EMAIL, preflight = PreflightInfo(release(3), 3, false, false, null), message = UnlockMessage.BadPin, waitSeconds = 75),
                NoUnlock,
            )
        },
        "unlock.paused" to {
            UnlockContent(
                UnlockUiState(
                    loading = false, email = EMAIL, preflight = PreflightInfo(release(3), 3, false, false, null),
                    message = UnlockMessage.Failed(FailureKind.SERVICE_PAUSED, "vault_unavailable"), serviceWaitSeconds = 300,
                ),
                NoUnlock,
            )
        },
        "unlock.paused_preflight" to {
            UnlockContent(UnlockUiState(loading = false, email = EMAIL, preflightError = FailureKind.SERVICE_PAUSED, serviceWaitSeconds = 300), NoUnlock)
        },
        "unlock.recovery" to {
            UnlockContent(UnlockUiState(loading = false, email = EMAIL, preflight = PreflightInfo(release(3), 3, false, false, null), recoveryPending = true), NoUnlock)
        },
        "unlock.refused" to {
            UnlockContent(UnlockUiState(loading = false, email = EMAIL, preflight = PreflightInfo(release(3), 3, false, false, null), refused = true), NoUnlock)
        },
        "unlock.not_recognised" to {
            UnlockContent(
                UnlockUiState(loading = false, email = EMAIL, preflight = PreflightInfo(release(3), 3, false, false, null),
                    message = UnlockMessage.Failed(FailureKind.OTHER, UnlockViewModel.CODE_UNREADABLE), notRecognised = true),
                NoUnlock,
            )
        },
        "unlock.erase_confirm" to {
            UnlockContent(
                UnlockUiState(loading = false, email = EMAIL, preflight = PreflightInfo(release(3), 3, false, false, null),
                    message = UnlockMessage.Failed(FailureKind.OTHER, UnlockViewModel.CODE_UNREADABLE), notRecognised = true,
                    eraseConfirm = true),
                NoUnlock,
            )
        },
        "unlock.erasing" to {
            UnlockContent(
                UnlockUiState(loading = false, email = EMAIL, preflight = PreflightInfo(release(3), 3, false, false, null),
                    notRecognised = true, erasing = true),
                NoUnlock,
            )
        },
        // The daily owner check (VAULT-MESSAGING §3.6.5): a locked vault past its deadline, and after ten failed checks.
        "unlock.owner_check" to {
            UnlockContent(
                UnlockUiState(loading = false, email = EMAIL, preflight = PreflightInfo(release(3), 3, false, false, null), checkDue = true, pin = "975310"),
                NoUnlock,
            )
        },
        "unlock.owner_check_locked" to {
            UnlockContent(
                UnlockUiState(loading = false, email = EMAIL, preflight = PreflightInfo(release(3), 3, false, false, null), checkDue = true,
                    lockedByOwnerCheck = true),
                NoUnlock,
            )
        },
        "credential.new" to { NewCredentialContent(NewCredentialUiState(pin = "975310", current = "pw", acknowledged = true), NewCredentialActions()) },
        "credential.new_confirm" to {
            NewCredentialContent(NewCredentialUiState(pin = "975310", current = "pw", acknowledged = true, confirming = true), NewCredentialActions())
        },
        "owner_check.held" to { OwnerCheckContent(OwnerCheckUiState(OwnerCheckMode.GATED, heldView), NoOwnerCheck) {} },
        // Past the deadline by the phone's clock, before the vault said what is waiting: never shown as zero.
        "owner_check.unknown" to {
            OwnerCheckContent(OwnerCheckUiState(OwnerCheckMode.GATED, okView.copy(deadline = Instant.now().minusSeconds(60))), NoOwnerCheck) {}
        },
        "owner_check.due" to {
            OwnerCheckContent(OwnerCheckUiState(OwnerCheckMode.GATED, heldView.copy(state = OwnerCheckState.DUE, hold = false, waiting = WaitingCounts())), NoOwnerCheck) {}
        },
        "owner_check.bad_password" to {
            OwnerCheckContent(
                OwnerCheckUiState(OwnerCheckMode.GATED, heldView.copy(failures = 2), message = OwnerCheckMessage.BadPassword(8)),
                NoOwnerCheck,
            ) {}
        },
        "owner_check.backoff" to {
            OwnerCheckContent(
                OwnerCheckUiState(OwnerCheckMode.GATED, heldView.copy(failures = 4), message = OwnerCheckMessage.BadPin(6), waitSeconds = 290),
                NoOwnerCheck,
            ) {}
        },
        "owner_check.voluntary" to { OwnerCheckContent(OwnerCheckUiState(OwnerCheckMode.VOLUNTARY, okView), NoOwnerCheck) {} },
        "owner_check.hold_off" to { OwnerCheckContent(OwnerCheckUiState(OwnerCheckMode.HOLD_OFF, okView), NoOwnerCheck) {} },
        "unlock.ended" to { UnlockContent(UnlockUiState(loading = false, email = EMAIL, preflightError = FailureKind.RELEASE_ENDED), NoUnlock) },
        "app_lock" to { AppLockScreen(onUnlock = {}) },
        "credential" to { CredentialContent(CredentialUiState(loading = false, status = credential, windowUntil = Instant.now().plusSeconds(240)), chrome, CredentialActions()) },
        "credential.alarm_banner" to {
            Column(Modifier.fillMaxSize()) {
                UrgentBanner(stringResource(com.vettid.feature.credential.R.string.credential_alarm_banner), stringResource(com.vettid.feature.credential.R.string.credential_alarm_review), {})
                CredentialContent(CredentialUiState(loading = false, status = credential, alarm = alarm), chrome, CredentialActions())
            }
        },
        "credential.window_dialog" to {
            CredentialContent(CredentialUiState(loading = false, status = credential, windowDialog = true, windowPassword = "secret"), chrome, CredentialActions())
        },
        "credential.backup_off" to {
            CredentialContent(CredentialUiState(loading = false, status = credential), chrome, CredentialActions())
            BackupOffDialog({}, {})
        },
        "credential.password" to {
            ChangePasswordContent(ChangePasswordUiState(current = "old password!", password = "a new long passphrase", strength = PasswordPolicy.Strength.STRONG), {}, {}, {}, {}, {})
        },
        "credential.rotate" to { RotateContent(RotateUiState(), {}, {}, {}) },
        "credential.alarm" to { AlarmContent(AlarmUiState(alarm = alarm), AlarmActions()) },
        "credential.alarm_rotate" to {
            AlarmContent(AlarmUiState(alarm = alarm.copy(state = CredentialAlarm.STATE_ROTATION_REQUIRED), step = AlarmStep.ROTATE, notMine = true), AlarmActions())
        },
        "settings" to {
            SettingsContent(
                SettingsUiState(
                    account = sampleAccount,
                    preferences = AppPreferences(ThemePreference.SYSTEM, true, AppLockTimeout.FIVE_MINUTES), appLockOn = true,
                ),
                SettingsActions(),
            )
        },
        "settings.owner_check_hold_off" to {
            SettingsContent(
                SettingsUiState(account = sampleAccount),
                SettingsActions(),
                OwnerCheckSettingsUiState(okView.copy(intervalSeconds = 43_200, hold = false, holdOffUntil = Instant.now().plusSeconds(3 * 86_400L))),
            )
        },
        "settings.owner_check_offer" to {
            SettingsContent(SettingsUiState(account = sampleAccount), SettingsActions(), OwnerCheckSettingsUiState(okView, offerCheck = true))
        },
        "shell.deletion" to {
            Column {
                PendingDeletionBanner(DeletionView(Instant.now().plusSeconds(20 * 3_600L), false, "01JDELETION0000000000000000"), true, {}, {})
                PendingDeletionBanner(DeletionView(Instant.now().plusSeconds(3 * 3_600L), false, null), false, {}, {})
            }
        },
        "shell.owner_check_banners" to {
            Column {
                OwnerCheckBanners(
                    OwnerCheckGateState(
                        warning = true, deadline = Instant.now().plusSeconds(1_800), holdOff = true, holdOffUntil = Instant.now().plusSeconds(86_400),
                        notices = listOf(OwnerCheckNotice("01J0000000000000000000000A", "owner_check.failed", "password", Instant.now().minusSeconds(600), false)),
                    ),
                    first = true, onCheckNow = {}, onHoldOn = {}, onDismissNotices = {},
                )
            }
        },
        "settings.status" to {
            VaultStatusContent(
                LoadState(
                    false,
                    VaultOverview(
                        "3f9c2a7be41d4c0e9b8a6f5d2c1e0a9b", "unlocked", ReleaseInfoView(3, "deprecated", "2027-01-15T00:00:00Z", 4, "update_available"),
                        3, null, false, 1, 4,
                    ),
                ),
                {}, {},
            )
        },
        "settings.pin" to { ChangePinContent(ChangePinUiState(current = "975310", pin = "1111"), {}, {}, {}, {}, {}) },
        "settings.recovery" to {
            RecoveryContent(
                RecoveryUiState(loading = false, recovery = RecoveryView("pending", "2026-10-05T14:00:00Z")),
                {}, {},
            )
        },
        "settings.transfer" to { TOut(TransferOutUiState()) },
        "settings.transfer_show" to { TOut(out) },
        "settings.transfer_compare" to { TOut(out.copy(step = TransferOutStep.COMPARE, pending = transferPending, secondsLeft = 571)) },
        "settings.transfer_approve" to { TOut(out.copy(step = TransferOutStep.APPROVE, pending = transferPending, pin = "975310", password = "pw", secondsLeft = 512)) },
        "settings.transfer_approve_confirm" to {
            TOut(out.copy(step = TransferOutStep.APPROVE, pending = transferPending, pin = "975310", password = "pw", confirmApprove = true))
        },
        "settings.transfer_bad_pin" to { TOut(out.copy(step = TransferOutStep.APPROVE, pending = transferPending, error = FailureKind.BAD_PIN, waitSeconds = 30)) },
        "settings.transfer_reject_confirm" to { TOut(out.copy(step = TransferOutStep.COMPARE, pending = transferPending, confirmReject = true)) },
        "settings.transfer_expired" to { TOut(TransferOutUiState(step = TransferOutStep.EXPIRED)) },
        "settings.transfer_rejected" to { TOut(TransferOutUiState(step = TransferOutStep.REJECTED)) },
        "settings.transfer_moved" to { TOut(TransferOutUiState(step = TransferOutStep.MOVED, pending = transferPending)) },
        "settings.transfer_exists" to { TOut(TransferOutUiState(error = FailureKind.OTHER, errorCode = "exists")) },
        "settings.attestation" to {
            AttestationContent(LoadState(false, AttestationInfo("devStack", true, "STRONG_BOX", true, 400, "SelfSigned", true, "4e8e e8f7 1c2d 3e4f", 3, "0303 0303 0303 0303")), {})
        },
        "settings.attestation_canary" to {
            AttestationContent(
                LoadState(false, AttestationInfo("production", true, "STRONG_BOX", true, 400, "Verified", true, "4e8e e8f7 1c2d 3e4f", 1, "0101 0101 0101 0101")),
                {},
                canary = canaryView,
            )
        },
        "canary.confirm" to { com.vettid.app.ui.CanaryManifestDialog(com.vettid.app.ui.CanaryManifestPrompt.Confirm(canaryView), {}, {}) },
        "canary.refused" to {
            com.vettid.app.ui.CanaryManifestDialog(com.vettid.app.ui.CanaryManifestPrompt.Refused(CanaryManifestRepository.CODE_SIGNATURE), {}, {})
        },
        "settings.delete" to { DeleteVaultContent(DeleteVaultUiState(phrase = "delete my vault", pin = "975310", password = "pw", acknowledged = true), DeleteVaultActions()) },
        "settings.delete_confirm" to {
            DeleteVaultContent(DeleteVaultUiState(phrase = "delete my vault", pin = "975310", password = "pw", acknowledged = true, confirming = true), DeleteVaultActions())
        },
        "messages" to { MessagesScreen(MessagesUiState(loading = false, conversations = conversations, noConnections = false, searchable = true), chrome) },
        "messages.search" to {
            MessagesScreen(MessagesUiState(loading = false, conversations = conversations.take(1), noConnections = false, query = "sam", searchable = true), chrome)
        },
        "messages.search_none" to { MessagesScreen(MessagesUiState(loading = false, noConnections = false, query = "zz", searchable = true), chrome) },
        "messages.empty" to { MessagesScreen(MessagesUiState(loading = false), chrome) },
        "messages.unread" to { MessagesScreen(MessagesUiState(loading = false, conversations = conversations.filter { it.unread > 0 }, unreadOnly = true, noConnections = false, searchable = true), chrome) },
        "messages.conversation" to { ConversationScreen(ConversationUiState("c1", sam, thread, loading = false, draft = "See you there"), ConversationActions()) },
        "messages.conversation_empty" to { ConversationScreen(ConversationUiState("c2", alex, emptyList(), loading = false), ConversationActions()) },
        "messages.conversation_stale" to { ConversationScreen(ConversationUiState("c3", jo, emptyList(), loading = false), ConversationActions()) },
        "messages.delete" to { ConversationScreen(ConversationUiState("c1", sam, thread, loading = false, deleting = thread[1]), ConversationActions()) },
        "messages.new" to { NewMessageScreen(NewMessageUiState(false, listOf(sam, alex)), {}, {}, {}) },
        "connections" to {
            ConnectionsScreen(
                ConnectionsUiState(loading = false, connections = connections, invites = listOf(OutstandingInvite("i1", t0.plusSeconds(3600), remote = true)), searchable = true),
                chrome, ConnectionsActions(),
            )
        },
        "connections.search" to {
            ConnectionsScreen(ConnectionsUiState(loading = false, connections = connections.take(1), query = "sam", searchable = true), chrome, ConnectionsActions())
        },
        "connections.search_none" to { ConnectionsScreen(ConnectionsUiState(loading = false, query = "zz", searchable = true), chrome, ConnectionsActions()) },
        "connections.empty" to { ConnectionsScreen(ConnectionsUiState(loading = false), chrome, ConnectionsActions()) },
        "connections.add" to { ConnectionsScreen(ConnectionsUiState(loading = false, connections = connections, addSheet = true), chrome, ConnectionsActions()) },
        "connections.detail" to {
            ConnectionDetailScreen(
                ConnectionDetailUiState("c1", sam, AuthenticationState("c1", t0, "authenticated", t0), loading = false, sharing = bothWays),
                DetailActions(),
            )
        },
        // The two sharing cards with nothing either way (owner request 2026-10-08).
        "connections.detail_sharing_empty" to {
            ConnectionDetailScreen(ConnectionDetailUiState("c1", sam, null, loading = false, sharing = DetailSharing(loaded = true)), DetailActions())
        },
        "connections.detail_not_shared" to {
            ConnectionDetailScreen(ConnectionDetailUiState("c4", riley, null, loading = false, sharing = DetailSharing(loaded = true)), DetailActions())
        },
        "connections.detail_remove" to { ConnectionDetailScreen(ConnectionDetailUiState("c1", sam, loading = false, confirm = DetailConfirm.REMOVE), DetailActions()) },
        "invite.choose" to { InviteScreen(InviteUiState(ttls = InviteTtl.entries.toList()), InviteActions()) },
        "invite.choose_remote" to { InviteScreen(InviteUiState(ttls = InviteTtl.entries.toList(), ttl = InviteTtl.ONE_DAY), InviteActions()) },
        "invite.show" to { InviteScreen(InviteUiState(step = InviteStep.SHOWING, invite = invite), InviteActions()) },
        "invite.request" to { InviteScreen(InviteUiState(step = InviteStep.REQUEST, invite = invite, request = request), InviteActions()) },
        "invite.waiting_peer" to {
            InviteScreen(InviteUiState(step = InviteStep.CONNECTING, invite = invite, request = request.copy(state = RequestState.APPROVED)), InviteActions())
        },
        "invite.connected" to { InviteScreen(InviteUiState(step = InviteStep.CONNECTED, connectionId = "c4", connectionName = "Morgan Lee"), InviteActions()) },
        "invite.expired" to { InviteScreen(InviteUiState(step = InviteStep.EXPIRED), InviteActions()) },
        "accept" to { AcceptScreen(AcceptUiState(input = invite.link), AcceptActions()) },
        "accept.error" to { AcceptScreen(AcceptUiState(input = "not a link", error = FailureKind.INVITE_INVALID), AcceptActions()) },
        "accept.opened" to { AcceptScreen(AcceptUiState(input = "https://relay.vettid.org/connect#${invite.link}", fromLink = true), AcceptActions()) },
        "accept.waiting" to { AcceptScreen(AcceptUiState(step = AcceptStep.WAITING, connectionId = "c4", name = "Morgan Lee"), AcceptActions()) },
        "accept.compare" to { AcceptScreen(AcceptUiState(step = AcceptStep.COMPARE, connectionId = "c4", name = "Morgan Lee", sas = "042817"), AcceptActions()) },
        "accept.approved" to { AcceptScreen(AcceptUiState(step = AcceptStep.APPROVED, connectionId = "c4", name = "Morgan Lee", sas = "042817"), AcceptActions()) },
        "accept.exists" to { AcceptScreen(AcceptUiState(step = AcceptStep.EXISTS, connectionId = "c1", connectionName = "Sam Rivera"), AcceptActions()) },
        "accept.peer_declined" to {
            AcceptScreen(AcceptUiState(step = AcceptStep.ENDED, connectionId = "c4", name = "Morgan Lee", end = RequestEnd.PEER_DECLINED), AcceptActions())
        },
        "invite.peer_declined" to { InviteScreen(InviteUiState(step = InviteStep.DECLINED, request = request, declinedName = "Morgan Lee"), InviteActions()) },
        "accept.ended" to { AcceptScreen(AcceptUiState(step = AcceptStep.ENDED, connectionId = "c4", end = RequestEnd.FAILED), AcceptActions()) },
        "scan" to {
            ScanScreen(ScanUiState(problem = FailureKind.INVITE_NOT_CONNECTION), {}, {}, {}, {}) { m -> Box(m.background(MaterialTheme.colorScheme.surfaceContainerHigh)) }
        },
        "approvals" to { ApprovalsScreen(ApprovalsUiState(loading = false, approvals = approvals), chrome) },
        "approvals.peer_declined" to {
            ApprovalsScreen(
                ApprovalsUiState(
                    loading = false,
                    peerDeclines = listOf(PeerDecline("c4", "Morgan Lee", outgoing = true, at = t0), PeerDecline("p2", "Alex", outgoing = false, at = t0)),
                ),
                chrome,
            )
        },
        "approvals.empty" to { ApprovalsScreen(ApprovalsUiState(loading = false), chrome) },
        "approvals.connection" to { ApprovalDetailScreen(ApprovalDetailUiState(request.key, request), DecisionActions()) },
        "approvals.authentication" to { ApprovalDetailScreen(ApprovalDetailUiState(approvals[1].key, approvals[1], password = "pw"), DecisionActions()) },
        "approvals.grant" to { ApprovalDetailScreen(ApprovalDetailUiState(approvals[2].key, approvals[2]), DecisionActions()) },
        "approvals.critical" to { ApprovalDetailScreen(ApprovalDetailUiState(approvals[3].key, approvals[3]), DecisionActions()) },
        "approvals.share" to { ApprovalDetailScreen(ApprovalDetailUiState(approvals[4].key, approvals[4]), DecisionActions()) },
        "approvals.critical_mismatch" to {
            val bad = (approvals[3] as Approval.CriticalUse).copy(payloadSha256 = "AAAA")
            ApprovalDetailScreen(ApprovalDetailUiState(bad.key, bad, password = "pw"), DecisionActions())
        },
        "approvals.outgoing" to { ApprovalDetailScreen(ApprovalDetailUiState(outgoing.key, outgoing), DecisionActions()) },
        "approvals.outgoing_waiting" to { ApprovalDetailScreen(ApprovalDetailUiState(outgoingWaiting.key, outgoingWaiting), DecisionActions()) },
        "approvals.connection_waiting_peer" to {
            val a = request.copy(state = RequestState.APPROVED)
            ApprovalDetailScreen(ApprovalDetailUiState(a.key, a), DecisionActions())
        },
        "approvals.device" to { ApprovalDetailScreen(ApprovalDetailUiState(approvals[5].key, approvals[5]), DecisionActions()) },
        "gallery" to { GalleryScreen(themeMode = LocalThemeController.current.mode, onThemeModeChange = {}, onBack = {}) },
        "account_sheet" to { AccountSheet(sampleAccount, "https://account.vettid.org", onDismiss = {}, onLockVault = {}) },
        "account_sheet.trial_expired" to {
            AccountSheet(
                sampleAccount.copy(
                    termsNeedAcceptance = true,
                    subscription = SubscriptionInfo("Trial", SubscriptionInfo.STATUS_TRIAL, false, Instant.parse("2026-09-01T00:00:00Z")),
                ),
                "https://account.vettid.org",
                onDismiss = {},
                onLockVault = {},
            )
        },
        "account_sheet.name_pending" to {
            AccountSheet(sampleAccount.copy(nameRequest = namePending), "https://account.vettid.org", onDismiss = {}, onLockVault = {})
        },
        "account_sheet.name_too_soon" to {
            AccountSheet(
                sampleAccount.copy(nameRequest = nameRefused, nameAllowedAfter = Instant.now().plusSeconds(86_400 * 20)),
                "https://account.vettid.org",
                onDismiss = {},
                onLockVault = {},
            )
        },
        // The shared profile and the name change (ANDROID-PLAN 0.1.10).
        "settings.shared_profile" to {
            SharedProfileContent(
                SharedProfileUiState(sampleAccount, OwnProfile(3, "Sam", "Sam", "Rivera", "9a1f bb7d 873e eafb 494b ef94 f072 7b25"), displayName = "Sam"),
                SharedProfileActions(),
            )
        },
        "settings.shared_profile_pending" to {
            SharedProfileContent(
                SharedProfileUiState(
                    sampleAccount.copy(nameRequest = namePending), OwnProfile(3, "", "Sam", "Rivera", "9a1f bb7d 873e eafb 494b ef94 f072 7b25"),
                ),
                SharedProfileActions(),
            )
        },
        "settings.change_name" to { ChangeNameContent(ChangeNameUiState(account = sampleAccount, first = "Sam", last = "King"), ChangeNameActions()) },
        "settings.change_name_invalid" to {
            ChangeNameContent(ChangeNameUiState(account = sampleAccount, first = "Sam1", last = "", checked = true), ChangeNameActions())
        },
        "settings.change_name_too_soon" to {
            ChangeNameContent(
                ChangeNameUiState(
                    account = sampleAccount.copy(nameAllowedAfter = Instant.now().plusSeconds(86_400 * 12)), first = "Sam", last = "Rivera",
                ),
                ChangeNameActions(),
            )
        },
        "settings.change_name_confirm" to {
            ChangeNameContent(ChangeNameUiState(ChangeNameStep.CONFIRM, sampleAccount, "Sam", "King", pin = "246810"), ChangeNameActions())
        },
        "settings.change_name_bad_pin" to {
            ChangeNameContent(
                ChangeNameUiState(ChangeNameStep.CONFIRM, sampleAccount, "Sam", "King", message = ChangeNameMessage.BadPin(7)),
                ChangeNameActions(),
            )
        },
        "settings.change_name_pending" to {
            ChangeNameContent(ChangeNameUiState(ChangeNameStep.SENT, sampleAccount, "Sam", "King", request = namePending), ChangeNameActions())
        },
        "settings.change_name_applied" to {
            ChangeNameContent(
                ChangeNameUiState(ChangeNameStep.SENT, sampleAccount, "Sam", "King", request = namePending.copy(state = NameRequestState.APPLIED)),
                ChangeNameActions(),
            )
        },
        "settings.change_name_refused" to {
            ChangeNameContent(
                ChangeNameUiState(
                    ChangeNameStep.SENT, sampleAccount.copy(nameAllowedAfter = Instant.now().plusSeconds(86_400 * 12)), "Sam", "King",
                    request = nameRefused,
                ),
                ChangeNameActions(),
            )
        },
        // History (ANDROID-PLAN 0.1.11).
        "history" to {
            HistoryScreen(HistoryUiState(historyEntries, connectionNames = historyNames, loading = false, end = true), chrome, HistoryActions())
        },
        "history.filtered" to {
            HistoryScreen(
                HistoryUiState(
                    historyEntries.filter { it.connectionId == "c1" },
                    filter = AuditFilter(category = AuditCategory.MESSAGES, connectionId = "c1", query = "alice"),
                    datePreset = DatePreset.WEEK, connectionNames = historyNames, loading = false, loadingMore = true, partial = true,
                ),
                chrome,
                HistoryActions(),
            )
        },
        "history.search_local" to {
            HistoryScreen(
                HistoryUiState(
                    historyEntries.take(2), filter = AuditFilter(query = "unlocked"), connectionNames = historyNames, loading = false,
                    end = true, localSearch = true,
                ),
                chrome,
                HistoryActions(),
            )
        },
        "history.empty" to { HistoryScreen(HistoryUiState(loading = false, end = true), chrome, HistoryActions()) },
        "history.no_match" to {
            HistoryScreen(HistoryUiState(filter = AuditFilter(query = "zzz"), loading = false, end = true), chrome, HistoryActions())
        },
        "history.chain_broken" to {
            HistoryScreen(HistoryUiState(historyEntries, connectionNames = historyNames, loading = false, chainBroken = true), chrome, HistoryActions())
        },
        "history.entry" to {
            HistoryEntryScreen(
                HistoryEntryUiState(
                    historyEntries.first().copy(hash = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="),
                    connectionName = "Alice Moreau", connectionExists = true, loading = false,
                ),
                onBack = {},
            )
        },
        "history.entry_unknown" to {
            HistoryEntryScreen(HistoryEntryUiState(historyEntries.last(), loading = false), onBack = {})
        },
        // History export (ANDROID-PLAN 0.1.17, VAULT-MESSAGING 0.22.0 §10.9): each step of History ⋯ → "Export…".
        "history.export_preparing" to { HistoryExportContent(ExportStep.Previewing, exportWords, HistoryExportActions()) },
        "history.export_confirm" to { HistoryExportContent(ExportStep.Confirm(exportPreview), exportWords, HistoryExportActions()) },
        "history.export_confirm_json" to {
            HistoryExportContent(ExportStep.Confirm(exportPreview, ExportFormat.JSON), ExportFilterWords(), HistoryExportActions())
        },
        "history.export_confirm_more" to {
            HistoryExportContent(
                ExportStep.Confirm(exportPreview.copy(count = 10_000, more = true), ExportFormat.CSV),
                ExportFilterWords(),
                HistoryExportActions(),
            )
        },
        "history.export_nothing" to { HistoryExportContent(ExportStep.NothingToExport, exportWords, HistoryExportActions()) },
        "history.export_alarm" to { HistoryExportContent(ExportStep.Refused(ExportRefusal.ALARM), exportWords, HistoryExportActions()) },
        "history.export_old_vault" to { HistoryExportContent(ExportStep.Refused(ExportRefusal.OLD_VAULT), exportWords, HistoryExportActions()) },
        "history.export_pin" to { HistoryExportContent(ExportStep.Pin(exportPreview, ExportFormat.CSV), exportWords, HistoryExportActions()) },
        "history.export_pin_wrong" to {
            HistoryExportContent(ExportStep.Pin(exportPreview, ExportFormat.CSV, error = FailureKind.BAD_PIN), exportWords, HistoryExportActions())
        },
        "history.export_pin_backoff" to {
            HistoryExportContent(
                ExportStep.Pin(exportPreview, ExportFormat.CSV, error = FailureKind.BACKOFF, retryUntil = Instant.now().plusSeconds(90)),
                exportWords,
                HistoryExportActions(),
            )
        },
        "history.export_reading" to { HistoryExportContent(ExportStep.Reading(25, 40), exportWords, HistoryExportActions()) },
        "history.export_save" to {
            HistoryExportContent(ExportStep.Save("vettid-history-20261008-140312.csv", ExportFormat.CSV, requested = true), exportWords, HistoryExportActions())
        },
        "history.export_writing" to { HistoryExportContent(ExportStep.Writing, exportWords, HistoryExportActions()) },
        "history.export_done" to { HistoryExportContent(ExportStep.Done(40, 40), exportWords, HistoryExportActions()) },
        "history.export_done_partial" to { HistoryExportContent(ExportStep.Done(38, 40), exportWords, HistoryExportActions()) },
        "history.export_failed" to { HistoryExportContent(ExportStep.Failed(FailureKind.NETWORK), exportWords, HistoryExportActions()) },
        "history.export_save_failed" to { HistoryExportContent(ExportStep.Failed(null, saveFailed = true), exportWords, HistoryExportActions()) },
        "history.export_owner_check" to {
            HistoryExportContent(ExportStep.Failed(FailureKind.OWNER_CHECK_REQUIRED), exportWords, HistoryExportActions())
        },
        // The profile photo (owner request 2026-10-07).
        "settings.shared_profile_photo_preview" to {
            SharedProfileContent(
                SharedProfileUiState(
                    sampleAccount, OwnProfile(3, "Sam", "Sam", "Rivera", "9a1f bb7d 873e eafb 494b ef94 f072 7b25"), displayName = "Sam",
                    pendingPhoto = SAMPLE_PHOTO,
                ),
                SharedProfileActions(),
            )
        },
        "settings.shared_profile_photo" to {
            SharedProfileContent(
                SharedProfileUiState(
                    sampleAccount, OwnProfile(3, "Sam", "Sam", "Rivera", "9a1f bb7d 873e eafb 494b ef94 f072 7b25", photo = SAMPLE_PHOTO),
                    displayName = "Sam",
                ),
                SharedProfileActions(),
            )
        },
        "account_sheet.waiting" to { AccountSheet(AccountInfo("s***@example.org"), "https://account.vettid.org", onDismiss = {}, onLockVault = {}) },
    )
}

private const val CRITICAL_PAYLOAD = "SGVsbG8sIFZldHRJRCE="

/** An 8×8 PNG in four colour blocks, drawn as a photo tile. */
internal const val SAMPLE_PHOTO = "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAH0lEQVR4nGP4f1AVjrz9TsARAxUl5Of1wdF/JEBFCQCiLXjhwCs13wAAAABJRU5ErkJggg=="
