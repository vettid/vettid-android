package com.vettid.app.features.enrollmentwizard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vettid.app.features.enrollmentwizard.phases.*

/**
 * Main enrollment wizard screen.
 *
 * Combines all enrollment phases into a single flow with a persistent step indicator.
 * Uses AnimatedContent for smooth transitions between phases.
 */
@Composable
fun EnrollmentWizardScreen(
    onWizardComplete: () -> Unit,
    onCancel: () -> Unit,
    startWithManualEntry: Boolean = false,
    initialCode: String? = null,
    viewModel: EnrollmentWizardViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    // Initialize with parameters
    LaunchedEffect(startWithManualEntry, initialCode) {
        viewModel.initialize(startWithManualEntry, initialCode)
    }

    // Track if we've already handled navigation to prevent double-navigation
    var hasNavigated by remember { mutableStateOf(false) }

    // Handle side effects - use collectLatest to ensure we always process the latest effect
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is WizardEffect.NavigateToMain -> {
                    if (!hasNavigated) {
                        hasNavigated = true
                        onWizardComplete()
                    }
                }
                is WizardEffect.CloseWizard -> onCancel()
                is WizardEffect.ShowToast -> {
                    // Toast can be handled by snackbar if needed
                }
            }
        }
    }

    // Backup navigation trigger: if state is Complete with shouldNavigate, trigger navigation
    // This handles cases where the effect might be missed due to recomposition timing
    LaunchedEffect(state) {
        if (state is WizardState.Complete && (state as WizardState.Complete).shouldNavigate && !hasNavigated) {
            hasNavigated = true
            onWizardComplete()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Step indicator (hidden during initial loading and error states)
            if (state !is WizardState.Error) {
                WizardStepIndicator(
                    currentStep = state.phase.stepIndex,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            // Main content with animations
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    val initialPhase = initialState.phase.stepIndex
                    val targetPhase = targetState.phase.stepIndex

                    if (targetPhase > initialPhase) {
                        // Moving forward
                        (slideInHorizontally { width -> width } + fadeIn())
                            .togetherWith(slideOutHorizontally { width -> -width } + fadeOut())
                    } else if (targetPhase < initialPhase) {
                        // Moving backward
                        (slideInHorizontally { width -> -width } + fadeIn())
                            .togetherWith(slideOutHorizontally { width -> width } + fadeOut())
                    } else {
                        // Same phase, different state
                        fadeIn() togetherWith fadeOut()
                    }
                },
                contentKey = { targetState ->
                    // Group related states to avoid unnecessary animations
                    when (targetState) {
                        is WizardState.Loading -> "loading"
                        is WizardState.ScanningQR, is WizardState.ManualEntry -> "start"
                        is WizardState.ProcessingInvite,
                        is WizardState.ConnectingToNats,
                        is WizardState.RequestingAttestation,
                        is WizardState.AttestationVerified -> "attestation"
                        is WizardState.ConfirmIdentity -> "confirm_identity"
                        is WizardState.SettingPin -> "pin"
                        is WizardState.SettingPassword, is WizardState.CreatingCredential -> "password"
                        is WizardState.VerifyingPassword,
                        is WizardState.Authenticating,
                        is WizardState.VerificationSuccess -> "verify"
                        is WizardState.PersonalData -> "personal"
                        is WizardState.SetupPublicProfile -> "public_profile"
                        is WizardState.Complete -> "complete"
                        is WizardState.Error -> "error_${targetState.previousPhase}"
                    }
                },
                label = "wizard_content"
            ) { currentState ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    when (currentState) {
                        // Loading State (initial)
                        is WizardState.Loading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        // Start Phase
                        is WizardState.ScanningQR -> {
                            StartPhaseContent(
                                mode = StartPhaseMode.SCANNING,
                                error = currentState.error,
                                onCodeScanned = { viewModel.onEvent(WizardEvent.QRCodeScanned(it)) },
                                onSwitchToManual = { viewModel.onEvent(WizardEvent.SwitchToManualEntry) },
                                onSwitchToScanning = { viewModel.onEvent(WizardEvent.SwitchToScanning) },
                                onCancel = { viewModel.onEvent(WizardEvent.Cancel) }
                            )
                        }

                        is WizardState.ManualEntry -> {
                            StartPhaseContent(
                                mode = StartPhaseMode.MANUAL_ENTRY,
                                inviteCode = currentState.inviteCode,
                                error = currentState.error,
                                onInviteCodeChanged = { viewModel.onEvent(WizardEvent.InviteCodeChanged(it)) },
                                onSubmitCode = { viewModel.onEvent(WizardEvent.SubmitInviteCode) },
                                onSwitchToManual = { viewModel.onEvent(WizardEvent.SwitchToManualEntry) },
                                onSwitchToScanning = { viewModel.onEvent(WizardEvent.SwitchToScanning) },
                                onCancel = { viewModel.onEvent(WizardEvent.Cancel) }
                            )
                        }

                        // Attestation Phase
                        is WizardState.ProcessingInvite -> {
                            AttestationPhaseContent(
                                mode = AttestationPhaseMode.PROCESSING,
                                message = currentState.message,
                                onCancel = { viewModel.onEvent(WizardEvent.Cancel) }
                            )
                        }

                        is WizardState.ConnectingToNats -> {
                            AttestationPhaseContent(
                                mode = AttestationPhaseMode.CONNECTING,
                                message = currentState.message,
                                onCancel = { viewModel.onEvent(WizardEvent.Cancel) }
                            )
                        }

                        is WizardState.RequestingAttestation -> {
                            AttestationPhaseContent(
                                mode = AttestationPhaseMode.VERIFYING,
                                message = currentState.message,
                                progress = currentState.progress,
                                onCancel = { viewModel.onEvent(WizardEvent.Cancel) }
                            )
                        }

                        is WizardState.AttestationVerified -> {
                            AttestationPhaseContent(
                                mode = AttestationPhaseMode.VERIFIED,
                                attestationInfo = currentState.attestationInfo
                            )
                        }

                        // Confirm Identity Phase
                        is WizardState.ConfirmIdentity -> {
                            ConfirmIdentityPhaseContent(
                                firstName = currentState.firstName,
                                lastName = currentState.lastName,
                                email = currentState.email,
                                attestationInfo = currentState.attestationInfo,
                                onConfirm = { viewModel.onEvent(WizardEvent.ConfirmIdentity) },
                                onCancel = { viewModel.onEvent(WizardEvent.Cancel) }
                            )
                        }

                        // PIN Phase
                        is WizardState.SettingPin -> {
                            PinSetupPhaseContent(
                                pin = currentState.pin,
                                confirmPin = currentState.confirmPin,
                                isSubmitting = currentState.isSubmitting,
                                error = currentState.error,
                                attestationInfo = currentState.attestationInfo,
                                onPinChange = { viewModel.onEvent(WizardEvent.PinChanged(it)) },
                                onConfirmPinChange = { viewModel.onEvent(WizardEvent.ConfirmPinChanged(it)) },
                                onSubmit = { viewModel.onEvent(WizardEvent.SubmitPin) },
                                onCancel = { viewModel.onEvent(WizardEvent.Cancel) }
                            )
                        }

                        // Password Phase
                        is WizardState.SettingPassword -> {
                            PasswordSetupPhaseContent(
                                password = currentState.password,
                                confirmPassword = currentState.confirmPassword,
                                strength = currentState.strength,
                                isSubmitting = currentState.isSubmitting,
                                error = currentState.error,
                                onPasswordChange = { viewModel.onEvent(WizardEvent.PasswordChanged(it)) },
                                onConfirmPasswordChange = { viewModel.onEvent(WizardEvent.ConfirmPasswordChanged(it)) },
                                onSubmit = { viewModel.onEvent(WizardEvent.SubmitPassword) },
                                onCancel = { viewModel.onEvent(WizardEvent.Cancel) }
                            )
                        }

                        is WizardState.CreatingCredential -> {
                            PasswordSetupPhaseContent(
                                isCreating = true,
                                creatingMessage = currentState.message,
                                creatingProgress = currentState.progress
                            )
                        }

                        // Verify Phase
                        is WizardState.VerifyingPassword -> {
                            VerifyPhaseContent(
                                mode = VerifyPhaseMode.PASSWORD_ENTRY,
                                password = currentState.password,
                                isPasswordVisible = currentState.isPasswordVisible,
                                isSubmitting = currentState.isSubmitting,
                                error = currentState.error,
                                onPasswordChange = { viewModel.onEvent(WizardEvent.VerifyPasswordChanged(it)) },
                                onToggleVisibility = { viewModel.onEvent(WizardEvent.TogglePasswordVisibility) },
                                onSubmit = { viewModel.onEvent(WizardEvent.SubmitVerifyPassword) },
                                onSkip = { viewModel.onEvent(WizardEvent.Skip) }
                            )
                        }

                        is WizardState.Authenticating -> {
                            VerifyPhaseContent(
                                mode = VerifyPhaseMode.AUTHENTICATING,
                                progress = currentState.progress,
                                statusMessage = currentState.statusMessage
                            )
                        }

                        is WizardState.VerificationSuccess -> {
                            VerifyPhaseContent(
                                mode = VerifyPhaseMode.SUCCESS,
                                successMessage = currentState.message,
                                onContinue = { viewModel.onEvent(WizardEvent.ContinueAfterVerification) }
                            )
                        }

                        // Personal Data Phase
                        is WizardState.PersonalData -> {
                            PersonalDataPhaseContent(
                                isLoading = currentState.isLoading,
                                isSyncing = currentState.isSyncing,
                                systemFields = currentState.systemFields,
                                optionalFields = currentState.optionalFields,
                                customFields = currentState.customFields,
                                hasPendingSync = currentState.hasPendingSync,
                                error = currentState.error,
                                showAddFieldDialog = currentState.showAddFieldDialog,
                                editingField = currentState.editingField,
                                onUpdateOptionalField = { field, value ->
                                    viewModel.onEvent(WizardEvent.UpdateOptionalField(field, value))
                                },
                                onAddCustomField = { name, value, category, fieldType ->
                                    viewModel.onEvent(WizardEvent.AddCustomField(name, value, category, fieldType))
                                },
                                onUpdateCustomField = { viewModel.onEvent(WizardEvent.UpdateCustomField(it)) },
                                onRemoveCustomField = { viewModel.onEvent(WizardEvent.RemoveCustomField(it)) },
                                onSyncNow = { viewModel.onEvent(WizardEvent.SyncPersonalData) },
                                onShowAddDialog = { viewModel.onEvent(WizardEvent.ShowAddFieldDialog) },
                                onHideAddDialog = { viewModel.onEvent(WizardEvent.HideAddFieldDialog) },
                                onShowEditDialog = { viewModel.onEvent(WizardEvent.ShowEditFieldDialog(it)) },
                                onHideEditDialog = { viewModel.onEvent(WizardEvent.HideEditFieldDialog) },
                                onDismissError = { viewModel.onEvent(WizardEvent.DismissError) },
                                onSkip = { viewModel.onEvent(WizardEvent.Skip) },
                                onContinue = { viewModel.onEvent(WizardEvent.NextStep) }
                            )
                        }

                        // Public Profile Phase
                        is WizardState.SetupPublicProfile -> {
                            PublicProfilePhaseContent(
                                isLoading = currentState.isLoading,
                                isPublishing = currentState.isPublishing,
                                systemFields = currentState.systemFields,
                                availableFields = currentState.availableFields,
                                selectedFields = currentState.selectedFields,
                                error = currentState.error,
                                onToggleField = { viewModel.onEvent(WizardEvent.TogglePublicProfileField(it)) },
                                onSelectAll = { viewModel.onEvent(WizardEvent.SelectAllPublicFields) },
                                onSelectNone = { viewModel.onEvent(WizardEvent.SelectNoPublicFields) },
                                onDismissError = { viewModel.onEvent(WizardEvent.DismissError) },
                                onSkip = { viewModel.onEvent(WizardEvent.SkipPublicProfile) },
                                onPublish = { viewModel.onEvent(WizardEvent.PublishProfile) }
                            )
                        }

                        // Complete Phase
                        is WizardState.Complete -> {
                            CompletePhaseContent(
                                userGuid = currentState.userGuid,
                                onContinue = { viewModel.onEvent(WizardEvent.NavigateToMain) }
                            )
                        }

                        // Error State
                        is WizardState.Error -> {
                            ErrorPhaseContent(
                                message = currentState.message,
                                canRetry = currentState.canRetry,
                                onRetry = { viewModel.onEvent(WizardEvent.Retry) },
                                onCancel = { viewModel.onEvent(WizardEvent.Cancel) }
                            )
                        }
                    }
                }
            }
        }
    }
}
