package com.vettid.app.features.unlock

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vettid.app.core.attestation.PcrConfigManager
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.NatsConnectionManager
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import com.vettid.app.core.storage.CredentialStore
import com.vettid.app.core.network.TransactionKeyInfo
import com.vettid.app.core.storage.PersonalDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

private const val TAG = "PinUnlockViewModel"

/**
 * PIN Unlock state
 */
sealed class PinUnlockState {
    data object Idle : PinUnlockState()
    data class EnteringPin(
        val pin: String = "",
        val error: String? = null
    ) : PinUnlockState()
    data object Connecting : PinUnlockState()
    data object Verifying : PinUnlockState()
    data class Success(val firstName: String? = null) : PinUnlockState()
    data class Error(val message: String) : PinUnlockState()
    // Transient state for "vault is in the middle of an update / just
    // came up and isn't answering yet" — auto-retries behind the scenes,
    // shows a calmer spinner-with-text instead of the red Error UI.
    data class WarmingUp(val message: String) : PinUnlockState()
    data class EnclaveUpdateRequired(
        val currentPcr0: String,
        val summary: String? = null,
        val detailsUrl: String? = null
    ) : PinUnlockState()
}

/**
 * PIN Unlock events
 */
sealed class PinUnlockEvent {
    data class PinChanged(val pin: String) : PinUnlockEvent()
    data object SubmitPin : PinUnlockEvent()
    data object Retry : PinUnlockEvent()
}

/**
 * PIN Unlock effects
 */
sealed class PinUnlockEffect {
    data object UnlockSuccess : PinUnlockEffect()
}

/**
 * ViewModel for PIN-based app unlock.
 *
 * Verifies the user's vault PIN against the enclave via NATS.
 */
@HiltViewModel
class PinUnlockViewModel @Inject constructor(
    private val credentialStore: CredentialStore,
    private val cryptoManager: CryptoManager,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val connectionManager: NatsConnectionManager,
    private val natsAutoConnector: NatsAutoConnector,
    private val personalDataStore: PersonalDataStore,
    private val pcrConfigManager: PcrConfigManager,
    private val migrationCompletionRecorder: com.vettid.app.features.migration.MigrationCompletionRecorder,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context
) : ViewModel() {

    companion object {
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 8
        private const val RESPONSE_TIMEOUT_MS = 30000L

        // (Removed 2026-05-11) MIGRATION_RETRY_MAX / MIGRATION_RETRY_DELAY_MS.
        // The single PIN-unlock now decides migration status definitively
        // because deploy.sh Phase 4.6 reclaims routing before publishing
        // the config — every consenting request lands on NEW. Any
        // pending_new_enclave response is surfaced as a user-actionable
        // error, not silently retried.
    }

    private val _state = MutableStateFlow<PinUnlockState>(PinUnlockState.Idle)
    val state: StateFlow<PinUnlockState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<PinUnlockEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<PinUnlockEffect> = _effects.asSharedFlow()

    private val gson = Gson()

    // Owner space for NATS communication (set during connection)
    private var ownerSpace: String? = null

    // Mutex to prevent concurrent PIN submission (e.g., double-tap)
    private val submitMutex = Mutex()

    // PCR0 pending user approval (set when enclave version changed)
    private var _pendingUntrustedPcr0: String? = null
    // PIN the user had already entered when we interrupted to show the
    // enclave-update prompt. On approval we reuse it so they don't re-type.
    // Held in memory only between prompt display and approval/skip; cleared
    // immediately after use or on skip.
    private var _pendingPin: String? = null
    // Skip PCR0 check for this session (user chose "Not Now")
    private var _skipPcr0Check = false
    // Set in approveEnclaveUpdate(); rides on the next submitPin() as
    // migrate_consent=true. Cleared on "completed". A wrong-PIN attempt
    // can't trigger an auto-migration because consent is only acted on
    // by the vault after PIN verification succeeds.
    private var _pendingMigrationApproval = false

    init {
        // Start in PIN entry mode
        _state.value = PinUnlockState.EnteringPin()
        // Log credential diagnostics on startup
        logCredentialState()
    }

    /**
     * Log credential diagnostics to help debug PIN unlock issues.
     */
    private fun logCredentialState() {
        Log.d(TAG, "Credential diagnostics:")
        Log.d(TAG, "  - Has stored credential: ${credentialStore.hasStoredCredential()}")
        Log.d(TAG, "  - Has enclave public key: ${credentialStore.getEnclavePublicKey() != null}")
        Log.d(TAG, "  - Has encrypted blob: ${credentialStore.getEncryptedBlob() != null}")
        Log.d(TAG, "  - UTK pool size: ${credentialStore.getUtkPool().size}")
        Log.d(TAG, "  - Has NATS connection: ${credentialStore.hasNatsConnection()}")
        Log.d(TAG, "  - NATS credentials valid: ${credentialStore.areNatsCredentialsValid()}")
        Log.d(TAG, "  - Has user GUID: ${credentialStore.getUserGuid() != null}")
    }

    /**
     * Check if user has stored credentials (is enrolled)
     */
    fun isEnrolled(): Boolean {
        return credentialStore.hasStoredCredential()
    }

    /**
     * Process UI events
     */
    fun onEvent(event: PinUnlockEvent) {
        viewModelScope.launch {
            when (event) {
                is PinUnlockEvent.PinChanged -> updatePin(event.pin)
                is PinUnlockEvent.SubmitPin -> submitPin()
                is PinUnlockEvent.Retry -> retry()
            }
        }
    }

    private fun updatePin(pin: String) {
        val current = _state.value
        if (current is PinUnlockState.EnteringPin) {
            // Only allow digits and max MAX_PIN_LENGTH
            val sanitizedPin = pin.filter { it.isDigit() }.take(MAX_PIN_LENGTH)
            _state.value = current.copy(pin = sanitizedPin, error = null)
        }
    }

    private suspend fun submitPin() {
        // Use tryLock to prevent double-tap / concurrent submission
        // If already submitting, just return without blocking
        if (!submitMutex.tryLock()) {
            Log.d(TAG, "PIN submission already in progress, ignoring")
            return
        }

        try {
            val current = _state.value
            if (current !is PinUnlockState.EnteringPin) {
                return
            }

            if (current.pin.length !in MIN_PIN_LENGTH..MAX_PIN_LENGTH) {
                _state.value = current.copy(error = "PIN must be $MIN_PIN_LENGTH-$MAX_PIN_LENGTH digits")
                return
            }

            val pin = current.pin

            // SECURITY: Verify the current enclave PCR0 is in the user's trusted set
            // before sending the PIN. This ensures the user has consented to this
            // enclave version. Without this check, an operator could deploy a rogue
            // enclave and the app would blindly send the PIN to it.
            val trustedSet = pcrConfigManager.getTrustedPcr0Set()
            val currentPcr0 = pcrConfigManager.getCurrentPcrs().pcr0
            val isValidPcr0 = currentPcr0 != "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"

            if (!_skipPcr0Check && trustedSet.isNotEmpty() && !pcrConfigManager.isPcr0Trusted(currentPcr0)) {
                Log.w(TAG, "SECURITY: Current enclave PCR0 not in user's trusted set")
                _pendingUntrustedPcr0 = currentPcr0
                // Stash the PIN so approveEnclaveUpdate() can auto-resubmit
                // instead of making the user re-type.
                _pendingPin = pin

                // Get description and changelog URL from PCR manifest (public, no vault needed)
                val (description, detailsUrl) = pcrConfigManager.getCurrentPcrDetails()
                val summary = description ?: "Enclave version: ${pcrConfigManager.getCurrentVersion()}"

                _state.value = PinUnlockState.EnclaveUpdateRequired(
                    currentPcr0 = currentPcr0,
                    summary = summary,
                    detailsUrl = detailsUrl
                )
                return
            } else if (trustedSet.isEmpty() && isValidPcr0) {
                // No trusted set yet (user enrolled before this feature existed).
                // Bootstrap: trust the current PCR0 on first use so existing users
                // aren't locked out. Future enclave changes will require consent.
                pcrConfigManager.addTrustedPcr0(currentPcr0)
                Log.i(TAG, "Bootstrapped trusted PCR0 set for existing user")
            }

            // Connect to NATS if not connected
            _state.value = PinUnlockState.Connecting

            if (!connectToNats()) {
                _state.value = PinUnlockState.Error("Failed to connect to vault")
                return
            }

            // Verify PIN with enclave using OwnerSpaceClient (event_id correlation)
            _state.value = PinUnlockState.Verifying

            // M1 (simplified 2026-05-11): pre-PIN consent rides on the
            // single PIN-unlock request. With deploy.sh Phase 4.6 eager
            // reclaim working (requires parent.p.pcr0 populated — see
            // vsock_client.GetExpectedPCR0Hex), every user's routing
            // claim is on NEW by the time the migration config publishes,
            // so this request lands on NEW and the vault re-seals inline.
            //
            // Anything other than "completed" when migrate_consent was
            // sent is a real error — surface it. The previous design's
            // silent retry loop + sticky _pendingMigrationApproval
            // produced a two-prompt UX (pre-PIN approve, then a
            // post-PIN VaultUpdateCard) because the retry kept consent
            // armed across unlocks and the card path saw it as unhandled.
            val migrateConsent = _pendingMigrationApproval

            // Bounded retry budget that covers both VaultNotReady (enclave
            // warming up) and pending_new_enclave (Phase 4.6 routing race
            // hasn't settled yet). Both paths are transient — surface a
            // calm "Updating vault…" screen instead of a red error, and
            // keep retrying with backoff. Total budget ~60s; per-attempt
            // delay grows 2s → 5s.
            var retryCount = 0
            val maxRetries = 15
            while (true) {
                val result = verifyPinWithEnclave(pin, migrateConsent = migrateConsent)

                when (result) {
                    is PinVerificationResult.Success -> {
                        Log.i(TAG, "PIN verification successful (migration_status=${result.migrationStatus.ifEmpty { "<none>" }})")

                        // M1 simplified: act on migration_status once,
                        // no retries. If migrate_consent was sent we
                        // expect "completed"; any other value is an
                        // error path the user should know about.
                        if (migrateConsent) {
                            when (result.migrationStatus) {
                                "completed" -> {
                                    Log.i(TAG, "Migration re-seal completed inline (version=${result.migrationVersion})")
                                    if (result.migrationVersion.isNotEmpty()) {
                                        migrationCompletionRecorder.recordCompletion(result.migrationVersion)
                                    }
                                    _pendingMigrationApproval = false
                                }
                                "pending_new_enclave" -> {
                                    // Phase 4.6 reclaim should prevent this, but if
                                    // the routing race hasn't settled the migration
                                    // request lands on OLD. Treat as transient —
                                    // show the calm WarmingUp state and retry.
                                    retryCount++
                                    if (retryCount >= maxRetries) {
                                        Log.e(TAG, "Migration still pending after $maxRetries retries")
                                        _state.value = PinUnlockState.Error(
                                            "Vault update is taking longer than usual. Please try unlocking again in a minute."
                                        )
                                        return
                                    }
                                    Log.i(TAG, "pending_new_enclave, retrying (attempt $retryCount/$maxRetries)")
                                    _state.value = PinUnlockState.WarmingUp(
                                        "Finishing vault update… this usually takes a few seconds."
                                    )
                                    kotlinx.coroutines.delay(retryBackoffMs(retryCount))
                                    continue
                                }
                                "failed" -> {
                                    Log.e(TAG, "Migration re-seal failed inline; surfacing error to user")
                                    _state.value = PinUnlockState.Error(
                                        "Vault update failed. Please try again or contact support."
                                    )
                                    return
                                }
                                "not_requested", "" -> {
                                    // Vault didn't see a migration to apply (config
                                    // missing or unverifiable). Treat as completed
                                    // since there's nothing to do — but log loudly
                                    // because the user only sees this path when the
                                    // pre-PIN prompt fired, which means the app
                                    // thought there WAS a migration.
                                    Log.w(TAG, "migrate_consent=true but vault returned ${result.migrationStatus.ifEmpty { "empty" }} — config drift?")
                                    _pendingMigrationApproval = false
                                }
                                else -> {
                                    Log.e(TAG, "Unknown migration_status: ${result.migrationStatus}")
                                    _state.value = PinUnlockState.Error(
                                        "Vault update returned an unexpected state: ${result.migrationStatus}"
                                    )
                                    return
                                }
                            }
                        }

                        // Reconnect with vault-issued credentials if available
                        // The vault issues full credentials after PIN verification;
                        // the initial connection may have used narrow bootstrap creds
                        if (credentialStore.areNatsCredentialsValid()) {
                            try {
                                natsAutoConnector.disconnect()
                                val reconnectResult = natsAutoConnector.autoConnect(autoStartVault = false)
                                if (reconnectResult is com.vettid.app.core.nats.NatsAutoConnector.ConnectionResult.Success) {
                                    Log.i(TAG, "Reconnected with vault-issued credentials")
                                } else {
                                    Log.w(TAG, "Reconnect with vault creds failed: $reconnectResult (non-fatal)")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Reconnect error (non-fatal)", e)
                            }
                        }

                        // Personal-data is now in-memory only (vault is the
                        // source of truth) so we MUST hydrate before we can
                        // read the system name or render any data screen.
                        // Block briefly to populate; refresh kicks in async.
                        try {
                            kotlinx.coroutines.withTimeoutOrNull(6000L) {
                                personalDataStore.hydrate()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "PersonalDataStore hydrate failed (non-fatal)", e)
                        }

                        val firstName = personalDataStore.getSystemFields()?.firstName

                        _state.value = PinUnlockState.Success(firstName = firstName)
                        _effects.emit(PinUnlockEffect.UnlockSuccess)
                        return
                    }
                    PinVerificationResult.VaultNotReady -> {
                        retryCount++
                        if (retryCount >= maxRetries) {
                            Log.e(TAG, "Vault still not ready after $maxRetries retries")
                            _state.value = PinUnlockState.Error(
                                "Vault is taking longer than usual to start. Please try unlocking again in a minute."
                            )
                            return
                        }
                        Log.i(TAG, "Vault not ready, retrying (attempt $retryCount/$maxRetries)")
                        _state.value = PinUnlockState.WarmingUp(
                            "Vault is starting up… this usually takes a few seconds."
                        )
                        kotlinx.coroutines.delay(retryBackoffMs(retryCount))
                        // Continue loop to retry
                    }
                    PinVerificationResult.InvalidPin -> {
                        Log.w(TAG, "PIN verification failed - invalid PIN")
                        _state.value = PinUnlockState.EnteringPin(pin = "", error = "Invalid PIN")
                        return
                    }
                    is PinVerificationResult.Error -> {
                        Log.e(TAG, "PIN verification failed - error: ${result.message}")
                        _state.value = PinUnlockState.Error(result.message)
                        return
                    }
                }
            }
        } catch (e: CancellationException) {
            // Coroutine cancelled - rethrow, don't treat as error
            Log.d(TAG, "PIN submission cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "PIN unlock error", e)
            _state.value = PinUnlockState.Error(e.message ?: "Unknown error")
        } finally {
            submitMutex.unlock()
        }
    }

    /**
     * Result of PIN verification attempt.
     *
     * Success carries the M1 migration_status / migration_version from
     * the vault response so the submit loop can decide whether to
     * retry (pending_new_enclave) or record completion.
     */
    private sealed class PinVerificationResult {
        data class Success(
            val migrationStatus: String = "",
            val migrationVersion: String = ""
        ) : PinVerificationResult()
        data object InvalidPin : PinVerificationResult()
        data object VaultNotReady : PinVerificationResult()
        data class Error(val message: String = "Verification error") : PinVerificationResult()
    }

    private fun retry() {
        _state.value = PinUnlockState.EnteringPin()
    }

    /**
     * Backoff for transient "vault not ready yet" retries. Starts at 2s,
     * grows to 5s, holds there. Total budget across [maxRetries=15] is
     * roughly 60-70s — enough to cover a Phase 4.6 routing-race window
     * or a cold-start probe gap without bailing.
     */
    private fun retryBackoffMs(attempt: Int): Long = when {
        attempt <= 1 -> 2000L
        attempt <= 3 -> 3000L
        else -> 5000L
    }

    /**
     * User approves the new enclave version from the update-required screen.
     * Adds the new PCR0 to the trusted set and auto-resubmits the PIN the
     * user already typed so they don't have to re-enter it.
     */
    fun approveEnclaveUpdate() {
        val pcr0 = _pendingUntrustedPcr0
        if (pcr0 != null) {
            pcrConfigManager.addTrustedPcr0(pcr0)
            Log.i(TAG, "User approved enclave update — PCR0 added to trusted set")
            _pendingUntrustedPcr0 = null
        }
        // _pendingMigrationApproval is consulted by submitPin() to set
        // migrate_consent=true on the next verify. Migration is now
        // resolved inline by that single request (success or surfaced
        // error); the legacy post-PIN VaultUpdateCard was removed
        // 2026-05-11 along with MigrationConsentTracker.
        _pendingMigrationApproval = true
        val pendingPin = _pendingPin
        _pendingPin = null
        if (!pendingPin.isNullOrEmpty()) {
            // Restore the PIN into state and re-run submitPin() directly.
            // Avoids a second keypad round-trip for what is effectively one
            // approval action.
            _state.value = PinUnlockState.EnteringPin(pin = pendingPin)
            viewModelScope.launch { submitPin() }
        } else {
            _state.value = PinUnlockState.EnteringPin()
        }
    }

    /**
     * User chooses to skip the update for now. Reuses the stashed PIN
     * to finish unlock against the old enclave — forcing the user to
     * re-type the same PIN felt like two back-to-back prompts. Also
     * marks the pending vault-update card dismissed so the post-PIN
     * path doesn't ask the same question a second time.
     */
    fun skipEnclaveUpdate() {
        Log.i(TAG, "User deferred enclave update — reusing stashed PIN against old enclave")
        _pendingUntrustedPcr0 = null
        _skipPcr0Check = true

        // (Removed 2026-05-11) migrationConsentTracker.recordSkip().
        // The post-PIN VaultUpdateCard that consumed this signal is
        // gone; users who skip the pre-PIN prompt simply unlock against
        // OLD and re-see the prompt on the next session.

        val pendingPin = _pendingPin
        _pendingPin = null
        if (!pendingPin.isNullOrEmpty()) {
            _state.value = PinUnlockState.EnteringPin(pin = pendingPin)
            viewModelScope.launch { submitPin() }
        } else {
            _state.value = PinUnlockState.EnteringPin()
        }
    }

    private suspend fun connectToNats(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Get user GUID for owner space
            val userGuid = credentialStore.getUserGuid()
            if (userGuid == null) {
                Log.e(TAG, "Missing user GUID")
                return@withContext false
            }

            ownerSpace = "OwnerSpace.$userGuid"

            // Check if already connected
            if (connectionManager.isConnected()) {
                Log.i(TAG, "Using existing NATS connection for PIN verification")
                return@withContext true
            }

            // Trigger NATS auto-connect to establish the shared connection
            // This uses stored credentials and handles all the connection setup
            Log.i(TAG, "Triggering NATS auto-connect for PIN verification")
            val result = natsAutoConnector.autoConnect(autoStartVault = true)

            when (result) {
                is NatsAutoConnector.ConnectionResult.Success -> {
                    Log.i(TAG, "NATS auto-connect successful for PIN verification")
                    return@withContext true
                }
                is NatsAutoConnector.ConnectionResult.NotEnrolled -> {
                    Log.e(TAG, "NATS auto-connect: Not enrolled")
                    return@withContext false
                }
                is NatsAutoConnector.ConnectionResult.CredentialsExpired -> {
                    Log.e(TAG, "NATS auto-connect: Credentials expired")
                    return@withContext false
                }
                is NatsAutoConnector.ConnectionResult.MissingData -> {
                    Log.e(TAG, "NATS auto-connect: Missing ${result.field}")
                    return@withContext false
                }
                is NatsAutoConnector.ConnectionResult.Error -> {
                    Log.e(TAG, "NATS auto-connect failed: ${result.message}")
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "NATS connection error", e)
            return@withContext false
        }
    }

    private suspend fun verifyPinWithEnclave(pin: String, migrateConsent: Boolean = false): PinVerificationResult = withContext(Dispatchers.IO) {
        // Verify owner space is set (connection was established)
        if (ownerSpace == null) {
            Log.e(TAG, "Owner space not set - connection not established")
            return@withContext PinVerificationResult.Error()
        }

        try {
            // Get enclave public key
            val enclavePublicKey = credentialStore.getEnclavePublicKey()
            if (enclavePublicKey == null) {
                Log.e(TAG, "CRITICAL: Enclave public key missing - user must re-enroll")
                return@withContext PinVerificationResult.Error("Security key missing. Please contact support or re-enroll.")
            }

            // Get an available UTK
            val utkPool = credentialStore.getUtkPool()
            val availableUtk = utkPool.firstOrNull()

            if (availableUtk == null) {
                Log.e(TAG, "No available UTKs")
                return@withContext PinVerificationResult.Error()
            }

            Log.d(TAG, "Using UTK: ${availableUtk.keyId}")

            // Encrypt PIN with enclave's public key using X25519 + ChaCha20-Poly1305
            val pinPayload = JsonObject().apply {
                addProperty("pin", pin)
            }
            val encryptedResult = cryptoManager.encryptToPublicKey(
                plaintext = pinPayload.toString().toByteArray(),
                publicKeyBase64 = android.util.Base64.encodeToString(enclavePublicKey, android.util.Base64.NO_WRAP)
            )

            // Combine ephemeral public key + nonce + ciphertext
            val ephemeralPubKeyBytes = android.util.Base64.decode(encryptedResult.ephemeralPublicKey, android.util.Base64.NO_WRAP)
            val nonceBytes = android.util.Base64.decode(encryptedResult.nonce, android.util.Base64.NO_WRAP)
            val ciphertextBytes = android.util.Base64.decode(encryptedResult.ciphertext, android.util.Base64.NO_WRAP)

            val combined = ephemeralPubKeyBytes + nonceBytes + ciphertextBytes
            val encryptedPayloadBase64 = android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)

            // Build request payload for enclave pin-unlock handler
            val requestPayload = JsonObject().apply {
                addProperty("utk_id", availableUtk.keyId)
                addProperty("encrypted_payload", encryptedPayloadBase64)
                if (migrateConsent) {
                    // M1: PIN-coupled migration. The vault re-seals
                    // sealed_material.bin against the running PCR0 only
                    // if running_pcr0 == config.NewPCR0; otherwise it
                    // emits a routing handoff and reports
                    // pending_new_enclave so the app retries.
                    addProperty("migrate_consent", true)
                }
            }

            Log.d(TAG, "Sending PIN unlock request via OwnerSpaceClient (event_id correlation)")

            // Ensure we're subscribed to vault responses
            ownerSpaceClient.subscribeToVault()

            // Use OwnerSpaceClient.sendAndAwaitResponse for proper event_id correlation
            // Use pin-unlock (hyphen) topic to match enclave expectations
            val response = ownerSpaceClient.sendAndAwaitResponse(
                messageType = "pin-unlock",
                payload = requestPayload,
                timeoutMs = RESPONSE_TIMEOUT_MS
            )

            // Remove used UTK immediately - UTKs are single-use tokens
            // Must be removed regardless of success/failure to prevent reuse
            credentialStore.removeUtk(availableUtk.keyId)
            Log.d(TAG, "Removed used UTK: ${availableUtk.keyId}")

            if (response == null) {
                Log.e(TAG, "PIN unlock request timed out")
                return@withContext PinVerificationResult.Error()
            }

            // Parse response based on type
            when (response) {
                is com.vettid.app.core.nats.VaultResponse.HandlerResult -> {
                    if (response.success && response.result != null) {
                        val responseJson = response.result
                        Log.d(TAG, "PIN unlock response: ${responseJson.toString().take(200)}")

                        val status = responseJson.get("status")?.asString
                        Log.d(TAG, "PIN unlock response status: $status")

                        // Vault returns {status: "unlocked", new_utks: [...]} on first unlock,
                        // but returns profile data ({first_name, email, ...}) if already unlocked.
                        // Both indicate successful PIN verification.
                        val isAlreadyUnlocked = status == null &&
                            responseJson.has("first_name") && responseJson.has("email")
                        if (isAlreadyUnlocked) {
                            Log.i(TAG, "Vault already unlocked - PIN verified via profile response")
                        }

                        // Detect "vault not ready" response: when the vault just started,
                        // the pin-unlock handler may not be initialized yet. The vault
                        // returns a generic response (connections list, events list, etc.)
                        // instead of a proper pin-unlock result. Detect this by checking
                        // that NONE of the expected pin-unlock fields are present.
                        val hasExpectedFields = status != null ||
                            responseJson.has("encrypted_credential") ||
                            responseJson.has("first_name") ||
                            responseJson.has("error")
                        if (!hasExpectedFields) {
                            Log.w(TAG, "Vault not ready - got unexpected response: ${responseJson.keySet()}")
                            return@withContext PinVerificationResult.VaultNotReady
                        }

                        // M1: vault echoes migration_status / migration_version
                        // when migrate_consent was set (and even when it
                        // wasn't — fields are simply empty in that case).
                        val migrationStatus = responseJson.get("migration_status")?.asString ?: ""
                        val migrationVersion = responseJson.get("migration_version")?.asString ?: ""

                        if (status == "unlocked" || status == "success" || status == "vault_ready" || isAlreadyUnlocked) {
                            // Store any new UTKs from response (format: "ID:base64PublicKey")
                            val newUtksArray = responseJson.getAsJsonArray("new_utks")
                            if (newUtksArray != null && newUtksArray.size() > 0) {
                                Log.d(TAG, "Received ${newUtksArray.size()} new UTKs")
                                val newKeys = mutableListOf<TransactionKeyInfo>()
                                for (i in 0 until newUtksArray.size()) {
                                    val utkString = newUtksArray.get(i).asString
                                    val parts = utkString.split(":", limit = 2)
                                    if (parts.size == 2) {
                                        newKeys.add(TransactionKeyInfo(
                                            keyId = parts[0],
                                            publicKey = parts[1],
                                            algorithm = "X25519"
                                        ))
                                    }
                                }
                                if (newKeys.isNotEmpty()) {
                                    credentialStore.addUtks(newKeys)
                                    Log.i(TAG, "Added ${newKeys.size} new UTKs to pool. Total: ${credentialStore.getUtkCount()}")
                                }
                            }

                            // Store vault-issued NATS credentials if provided
                            // The vault is the sole authority for full OwnerSpace/MessageSpace access
                            val vaultNatsCreds = responseJson.get("nats_credentials")?.asString
                            if (vaultNatsCreds != null) {
                                val credsTtl = responseJson.get("credentials_ttl_seconds")?.asLong ?: (7 * 24 * 3600L)
                                credentialStore.storeFullNatsCredentials(
                                    credentials = vaultNatsCreds,
                                    ownerSpace = responseJson.get("owner_space")?.asString,
                                    messageSpace = responseJson.get("message_space")?.asString,
                                    credentialId = "vault-issued",
                                    ttlSeconds = credsTtl
                                )
                                Log.i(TAG, "Stored vault-issued NATS credentials (TTL: ${credsTtl}s)")
                            }

                            return@withContext PinVerificationResult.Success(
                                migrationStatus = migrationStatus,
                                migrationVersion = migrationVersion
                            )
                        } else {
                            val error = responseJson.get("error")?.asString
                            Log.w(TAG, "PIN unlock failed: $error")
                            return@withContext PinVerificationResult.InvalidPin
                        }
                    } else {
                        val error = response.error ?: "Unknown error"
                        Log.w(TAG, "PIN unlock request failed: $error")
                        // Check if it's an invalid PIN error vs other errors
                        if (error.contains("invalid", ignoreCase = true) ||
                            error.contains("wrong", ignoreCase = true) ||
                            error.contains("incorrect", ignoreCase = true)) {
                            return@withContext PinVerificationResult.InvalidPin
                        }
                        return@withContext PinVerificationResult.Error()
                    }
                }
                is com.vettid.app.core.nats.VaultResponse.Error -> {
                    Log.e(TAG, "PIN unlock error: ${response.code} - ${response.message}")
                    if (response.message.contains("invalid", ignoreCase = true) ||
                        response.message.contains("wrong", ignoreCase = true) ||
                        response.message.contains("incorrect", ignoreCase = true)) {
                        return@withContext PinVerificationResult.InvalidPin
                    }
                    return@withContext PinVerificationResult.Error()
                }
                else -> {
                    Log.e(TAG, "Unexpected response type for PIN unlock: ${response::class.simpleName}")
                    return@withContext PinVerificationResult.Error()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "PIN verification error", e)
            return@withContext PinVerificationResult.Error()
        }
    }

}
