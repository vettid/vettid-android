// Debug-only sample data for screenshots: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength", "MagicNumber", "TooManyFunctions")

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
import com.vettid.core.data.social.SafetyCodeRecord
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
import com.vettid.feature.settings.AttestationContent
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
    override fun setEmail(v: String) = Unit
    override fun submitEmail() = Unit
    override fun resendLink() = Unit
    override fun setLinkInput(v: String) = Unit
    override fun submitLink() = Unit
    override fun confirmSignIn() = Unit
    override fun back(): Boolean = false
    override fun setAccountPin(v: String) = Unit
    override fun submitAccountPin() = Unit
    override fun checkAgain() = Unit
    override fun useAnotherAccount() = Unit
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

private object NoUnlock : UnlockActions {
    override fun retryPreflight() = Unit
    override fun acknowledgeUpdate() = Unit
    override fun setApproveOffer(approve: Boolean) = Unit
    override fun setPin(v: String) = Unit
    override fun submit() = Unit
    override fun cancelRecoveryAndUnlock() = Unit
    override fun signOut() = Unit
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

    private val onboarding = OnboardingUiState(email = EMAIL)
    private val chrome = ShellChrome(accountName = "Sam Rivera", onMenuClick = {}, onAvatarClick = {})
    private val credential = CredentialStatus(true, 7, "Jk4m2Qx9TzA1", "2026-10-04T14:12:00Z", null, true, 300, 3)
    private val alarm = CredentialAlarm("01JABCDEF0123456789ABCDEFG", CredentialAlarm.STATE_FROZEN, "2026-10-04T14:20:00Z", "other")


    // --- A4 sample data (made up) ---
    private val t0: Instant = Instant.parse("2026-10-04T09:00:00Z")
    private val sam = ConnectionInfo("c1", "Sam Rivera", ConnectionState.ACTIVE, favorite = true, profile = listOf("name" to "Sam Rivera", "city" to "Lisbon"),
        keyFingerprint = "4e8e e8f7 1c2d 3e4f", createdAt = t0.minusSeconds(86_400 * 30), lastActiveAt = t0.plusSeconds(600))
    private val alex = ConnectionInfo("c2", "Alexandra Okafor", ConnectionState.ACTIVE, alias = "Alex (work)", createdAt = t0.minusSeconds(86_400 * 3), lastActiveAt = t0.minusSeconds(7200))
    private val jo = ConnectionInfo("c3", "Jo Lindqvist", ConnectionState.STALE, createdAt = t0.minusSeconds(86_400 * 90))
    private val connections = listOf(sam, alex, jo)
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
    private val request = Approval.ConnectionRequest("p1", invite.inviteId, "042817", false, "Morgan Lee", null, t0, t0.plusSeconds(604_800))
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
    private fun Ob(state: OnboardingUiState) = OnboardingContent(state, NoOnboarding) {}

    val screens: Map<String, @Composable () -> Unit> = linkedMapOf(
        "onboarding.welcome" to { Ob(onboarding) },
        "onboarding.email" to { Ob(onboarding.copy(step = OnboardingStep.EMAIL)) },
        "onboarding.check_email" to { Ob(onboarding.copy(step = OnboardingStep.CHECK_EMAIL)) },
        "onboarding.confirm" to { Ob(onboarding.copy(step = OnboardingStep.CONFIRM_SIGN_IN)) },
        "onboarding.account_pin" to { Ob(onboarding.copy(step = OnboardingStep.ACCOUNT_PIN, accountPin = "12")) },
        "onboarding.terms" to { Ob(onboarding.copy(step = OnboardingStep.TERMS)) },
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
        "unlock.recovery" to {
            UnlockContent(UnlockUiState(loading = false, email = EMAIL, preflight = PreflightInfo(release(3), 3, false, false, null), recoveryPending = true), NoUnlock)
        },
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
                    account = AccountInfo(EMAIL, "Sam", "Rivera"),
                    preferences = AppPreferences(ThemePreference.SYSTEM, true, AppLockTimeout.FIVE_MINUTES), appLockOn = true,
                ),
                SettingsActions(),
            )
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
                RecoveryUiState(loading = false, recovery = RecoveryView("01JABCDEF0123456789ABCDEFG", "pending", "2026-10-05T14:00:00Z", "2026-10-06T14:00:00Z")),
                {}, {}, {},
            )
        },
        "settings.attestation" to {
            AttestationContent(LoadState(false, AttestationInfo("devStack", true, "STRONG_BOX", true, 400, "SelfSigned", true, "4e8e e8f7 1c2d 3e4f", 3, "0303 0303 0303 0303")), {})
        },
        "settings.delete" to { DeleteVaultContent(DeleteVaultUiState(phrase = "delete my vault", pin = "975310", password = "pw", acknowledged = true), DeleteVaultActions()) },
        "settings.delete_confirm" to {
            DeleteVaultContent(DeleteVaultUiState(phrase = "delete my vault", pin = "975310", password = "pw", acknowledged = true, confirming = true), DeleteVaultActions())
        },
        "messages" to { MessagesScreen(MessagesUiState(loading = false, conversations = conversations, noConnections = false), chrome) },
        "messages.empty" to { MessagesScreen(MessagesUiState(loading = false), chrome) },
        "messages.unread" to { MessagesScreen(MessagesUiState(loading = false, conversations = conversations.filter { it.unread > 0 }, unreadOnly = true, noConnections = false), chrome) },
        "messages.conversation" to { ConversationScreen(ConversationUiState("c1", sam, thread, loading = false, draft = "See you there"), ConversationActions()) },
        "messages.conversation_empty" to { ConversationScreen(ConversationUiState("c2", alex, emptyList(), loading = false), ConversationActions()) },
        "messages.conversation_stale" to { ConversationScreen(ConversationUiState("c3", jo, emptyList(), loading = false), ConversationActions()) },
        "messages.delete" to { ConversationScreen(ConversationUiState("c1", sam, thread, loading = false, deleting = thread[1]), ConversationActions()) },
        "messages.new" to { NewMessageScreen(NewMessageUiState(false, listOf(sam, alex)), {}, {}, {}) },
        "connections" to {
            ConnectionsScreen(
                ConnectionsUiState(loading = false, connections = connections, invites = listOf(OutstandingInvite("i1", t0.plusSeconds(3600), remote = true))),
                chrome, ConnectionsActions(),
            )
        },
        "connections.empty" to { ConnectionsScreen(ConnectionsUiState(loading = false), chrome, ConnectionsActions()) },
        "connections.add" to { ConnectionsScreen(ConnectionsUiState(loading = false, connections = connections, addSheet = true), chrome, ConnectionsActions()) },
        "connections.detail" to {
            ConnectionDetailScreen(
                ConnectionDetailUiState("c1", sam, AuthenticationState("c1", t0, "authenticated", t0), SafetyCodeRecord("042817", t0.minusSeconds(86_400 * 30)), loading = false),
                DetailActions(),
            )
        },
        "connections.detail_alias" to { ConnectionDetailScreen(ConnectionDetailUiState("c2", alex, null, null, loading = false), DetailActions()) },
        "connections.detail_edit" to {
            ConnectionDetailScreen(ConnectionDetailUiState("c2", alex, loading = false, editing = true, aliasInput = "Alex (work)", noteInput = "Met at the 2026 conference"), DetailActions())
        },
        "connections.detail_block" to { ConnectionDetailScreen(ConnectionDetailUiState("c1", sam, loading = false, confirm = DetailConfirm.BLOCK), DetailActions()) },
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
        "account_sheet" to { AccountSheet(name = "Sam Rivera", detail = EMAIL, onDismiss = {}, onLockVault = {}, onSignOut = {}) },
    )
}

private const val CRITICAL_PAYLOAD = "SGVsbG8sIFZldHRJRCE="
