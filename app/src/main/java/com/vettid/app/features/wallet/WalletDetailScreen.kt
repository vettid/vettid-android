package com.vettid.app.features.wallet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.vettid.app.core.crypto.CryptoManager
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import com.vettid.app.core.network.TransactionKeyInfo
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

private const val TAG = "WalletDetailVM"

// MARK: - ViewModel

@HiltViewModel
class WalletDetailViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val natsAutoConnector: NatsAutoConnector,
    private val credentialStore: CredentialStore,
    private val cryptoManager: CryptoManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val walletId: String = savedStateHandle["walletId"]
        ?: throw IllegalArgumentException("walletId is required")

    private val gson = Gson()

    private val _state = MutableStateFlow<WalletDetailState>(WalletDetailState.Loading)
    val state: StateFlow<WalletDetailState> = _state.asStateFlow()

    private val _balance = MutableStateFlow<BalanceInfo?>(null)
    val balance: StateFlow<BalanceInfo?> = _balance.asStateFlow()

    private val _transactions = MutableStateFlow<List<TxHistoryEntry>>(emptyList())
    val transactions: StateFlow<List<TxHistoryEntry>> = _transactions.asStateFlow()

    private val _isRefreshingBalance = MutableStateFlow(false)
    val isRefreshingBalance: StateFlow<Boolean> = _isRefreshingBalance.asStateFlow()

    private val _showPublicWarning = MutableStateFlow(false)
    val showPublicWarning: StateFlow<Boolean> = _showPublicWarning.asStateFlow()

    /**
     * Tri-state for the backup-seed flow:
     *   null = no prompt
     *   "enable" = user toggled ON, prompt for password to add to Critical Secrets
     *   "disable" = user toggled OFF, prompt for password to revoke
     */
    private val _backupPasswordPrompt = MutableStateFlow<String?>(null)
    val backupPasswordPrompt: StateFlow<String?> = _backupPasswordPrompt.asStateFlow()

    private val _backupInFlight = MutableStateFlow(false)
    val backupInFlight: StateFlow<Boolean> = _backupInFlight.asStateFlow()

    private val _effects = MutableSharedFlow<WalletDetailEffect>()
    val effects: SharedFlow<WalletDetailEffect> = _effects.asSharedFlow()

    init {
        loadWalletDetail()
    }

    fun loadWalletDetail() {
        viewModelScope.launch {
            try {
                _state.value = WalletDetailState.Loading

                val payload = JsonObject().apply {
                    addProperty("wallet_id", walletId)
                }

                val result = sendAndAwait("wallet.detail", payload) { json ->
                    WalletInfo(
                        walletId = json.get("wallet_id")?.asString ?: walletId,
                        label = json.get("label")?.asString ?: "",
                        address = json.get("address")?.asString ?: "",
                        network = json.get("network")?.asString ?: "mainnet",
                        cachedBalanceSats = json.get("cached_balance_sats")?.asLong ?: 0L,
                        balanceUpdatedAt = json.get("balance_updated_at")?.asLong ?: 0L,
                        isPublic = json.get("is_public")?.asBoolean ?: false,
                        seedBackedUpAt = json.get("seed_backed_up_at")?.asLong ?: 0L,
                        seedBackupSecretId = json.get("seed_backup_secret_id")?.takeIf { !it.isJsonNull }?.asString,
                    )
                }

                result.onSuccess { wallet ->
                    _state.value = WalletDetailState.Loaded(wallet)
                    refreshBalance()
                    loadTransactions()
                }.onFailure { e ->
                    _state.value = WalletDetailState.Error(e.message ?: "Failed to load wallet")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading wallet detail", e)
                _state.value = WalletDetailState.Error("Error: ${e.message}")
            }
        }
    }

    fun refreshBalance() {
        viewModelScope.launch {
            try {
                _isRefreshingBalance.value = true

                val payload = JsonObject().apply {
                    addProperty("wallet_id", walletId)
                }

                val result = sendAndAwait("wallet.balance", payload) { json ->
                    BalanceInfo(
                        walletId = walletId,
                        confirmedSats = json.get("confirmed_sats")?.asLong ?: 0L,
                        unconfirmedSats = json.get("unconfirmed_sats")?.asLong ?: 0L,
                        totalSats = json.get("total_sats")?.asLong ?: 0L
                    )
                }

                result.onSuccess { balanceInfo ->
                    _balance.value = balanceInfo

                    val currentState = _state.value
                    if (currentState is WalletDetailState.Loaded) {
                        _state.value = currentState.copy(
                            wallet = currentState.wallet.copy(
                                cachedBalanceSats = balanceInfo.totalSats,
                                balanceUpdatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing balance", e)
            } finally {
                _isRefreshingBalance.value = false
            }
        }
    }

    private fun loadTransactions() {
        viewModelScope.launch {
            try {
                val payload = JsonObject().apply {
                    addProperty("wallet_id", walletId)
                    addProperty("limit", 50)
                }

                val result = sendAndAwait("wallet.transactions", payload) { json ->
                    json.getAsJsonArray("transactions")?.map { item ->
                        val obj = item.asJsonObject
                        TxHistoryEntry(
                            txid = obj.get("txid")?.asString ?: "",
                            direction = obj.get("direction")?.asString ?: "received",
                            amountSats = obj.get("amount_sats")?.asLong ?: 0L,
                            feeSats = obj.get("fee_sats")?.asLong ?: 0L,
                            confirmed = obj.get("confirmed")?.asBoolean ?: false,
                            blockHeight = obj.get("block_height")?.asLong,
                            blockTime = obj.get("block_time")?.asLong
                        )
                    } ?: emptyList()
                }

                result.onSuccess { txList ->
                    _transactions.value = txList
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading transactions", e)
            }
        }
    }

    fun requestTogglePublic(isPublic: Boolean) {
        if (isPublic) {
            _showPublicWarning.value = true
        } else {
            setPublicVisibility(false)
        }
    }

    fun confirmMakePublic() {
        _showPublicWarning.value = false
        setPublicVisibility(true)
    }

    fun dismissPublicWarning() {
        _showPublicWarning.value = false
    }

    private fun setPublicVisibility(isPublic: Boolean) {
        viewModelScope.launch {
            try {
                val payload = JsonObject().apply {
                    addProperty("wallet_id", walletId)
                    addProperty("is_public", isPublic)
                }

                val result = sendAndAwait("wallet.set-visibility", payload) { json ->
                    json.get("success")?.asBoolean ?: true
                }

                result.onSuccess {
                    val currentState = _state.value
                    if (currentState is WalletDetailState.Loaded) {
                        _state.value = currentState.copy(
                            wallet = currentState.wallet.copy(isPublic = isPublic)
                        )
                    }
                    _effects.emit(
                        WalletDetailEffect.ShowSuccess(
                            if (isPublic) "Wallet address is now public"
                            else "Wallet address is now private"
                        )
                    )
                }.onFailure {
                    _effects.emit(WalletDetailEffect.ShowError("Failed to update visibility"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting visibility", e)
                _effects.emit(WalletDetailEffect.ShowError("Error: ${e.message}"))
            }
        }
    }

    // MARK: - Seed-phrase backup

    /**
     * The user tapped the "Back up seed phrase" toggle. Always show
     * the password prompt — the seed leaving the vault DEK envelope
     * for the credential blob is a meaningful escalation, so we
     * re-auth even if the session is unlocked.
     */
    fun requestBackupToggle(enable: Boolean) {
        val current = _state.value as? WalletDetailState.Loaded ?: return
        val alreadyBackedUp = current.wallet.seedBackedUpAt > 0
        if (enable == alreadyBackedUp) return // no-op
        _backupPasswordPrompt.value = if (enable) "enable" else "disable"
    }

    fun cancelBackupPrompt() {
        _backupPasswordPrompt.value = null
    }

    fun confirmBackupPrompt(password: String) {
        val mode = _backupPasswordPrompt.value ?: return
        _backupPasswordPrompt.value = null
        viewModelScope.launch {
            _backupInFlight.value = true
            try {
                if (mode == "enable") backupSeed(password) else revokeSeedBackup(password)
            } finally {
                _backupInFlight.value = false
            }
        }
    }

    private suspend fun backupSeed(password: String) {
        val payload = buildCredentialAuthPayload(password) ?: return
        payload.addProperty("wallet_id", walletId)
        val response = ownerSpaceClient.sendAndAwaitResponse("wallet.backup-seed", payload)
        when (response) {
            is VaultResponse.HandlerResult -> {
                if (!response.success) {
                    _effects.emit(WalletDetailEffect.ShowError(response.error ?: "Backup failed"))
                    return
                }
                applyBackupResponse(response.result, backedUp = true)
                _effects.emit(WalletDetailEffect.ShowSuccess("Seed phrase backed up to Critical Secrets"))
            }
            is VaultResponse.Error -> _effects.emit(WalletDetailEffect.ShowError(response.message))
            else -> _effects.emit(WalletDetailEffect.ShowError("Unexpected response from vault"))
        }
    }

    private suspend fun revokeSeedBackup(password: String) {
        val payload = buildCredentialAuthPayload(password) ?: return
        payload.addProperty("wallet_id", walletId)
        val response = ownerSpaceClient.sendAndAwaitResponse("wallet.revoke-backup", payload)
        when (response) {
            is VaultResponse.HandlerResult -> {
                if (!response.success) {
                    _effects.emit(WalletDetailEffect.ShowError(response.error ?: "Revoke failed"))
                    return
                }
                applyBackupResponse(response.result, backedUp = false)
                _effects.emit(WalletDetailEffect.ShowSuccess("Seed phrase removed from Critical Secrets"))
            }
            is VaultResponse.Error -> _effects.emit(WalletDetailEffect.ShowError(response.message))
            else -> _effects.emit(WalletDetailEffect.ShowError("Unexpected response from vault"))
        }
    }

    /**
     * Build the {encrypted_credential, encrypted_password_hash, ...}
     * envelope every credential.secret.* mutation needs. Returns null
     * + emits a user-visible error if any prerequisite is missing
     * (no salt, no UTKs, etc.) — caller bails out.
     */
    private suspend fun buildCredentialAuthPayload(password: String): JsonObject? {
        val saltBytes = credentialStore.getPasswordSaltBytes()
        if (saltBytes == null) {
            _effects.emit(WalletDetailEffect.ShowError("No password salt available"))
            return null
        }
        val utkPool = credentialStore.getUtkPool()
        if (utkPool.isEmpty()) {
            _effects.emit(WalletDetailEffect.ShowError("No transaction keys available"))
            return null
        }
        val utk = utkPool.first()
        val encryptedBlob = credentialStore.getEncryptedBlob()
        if (encryptedBlob == null) {
            _effects.emit(WalletDetailEffect.ShowError("Vault credential not loaded"))
            return null
        }
        val encryptedPassword = cryptoManager.encryptPasswordForServer(password, saltBytes, utk.publicKey)
        return JsonObject().apply {
            addProperty("encrypted_credential", encryptedBlob)
            addProperty("encrypted_password_hash", encryptedPassword.encryptedPasswordHash)
            addProperty("ephemeral_public_key", encryptedPassword.ephemeralPublicKey)
            addProperty("nonce", encryptedPassword.nonce)
            addProperty("key_id", utk.keyId)
        }
    }

    /**
     * Common post-mutation handling: persist the updated credential
     * blob, fold new UTKs into the local pool, refresh the wallet
     * record so the toggle reflects the new state.
     */
    private fun applyBackupResponse(result: JsonObject?, backedUp: Boolean) {
        if (result == null) return
        // Persist the updated credential blob so the next mutation
        // doesn't get rejected with a stale-blob error.
        result.get("encrypted_credential")?.takeIf { !it.isJsonNull }?.asString?.let { newBlob ->
            try {
                val decoded = android.util.Base64.decode(newBlob, android.util.Base64.NO_WRAP)
                credentialStore.storeCredentialBlob(decoded)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to store updated credential blob", e)
            }
        }
        // Add any returned UTKs to the pool (vault rotates them on every mutation).
        result.getAsJsonArray("new_utks")?.let { arr ->
            try {
                val newKeys = arr.map { utkObj ->
                    val obj = utkObj.asJsonObject
                    TransactionKeyInfo(
                        keyId = obj.get("id").asString,
                        publicKey = obj.get("public_key").asString,
                        algorithm = "X25519",
                    )
                }
                if (newKeys.isNotEmpty()) credentialStore.addUtks(newKeys)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to absorb UTKs", e)
            }
        }
        val current = _state.value as? WalletDetailState.Loaded ?: return
        val secretId = result.get("secret_id")?.takeIf { !it.isJsonNull }?.asString
        val backedUpAt = result.get("backed_up_at")?.asLong ?: System.currentTimeMillis() / 1000
        _state.value = current.copy(
            wallet = current.wallet.copy(
                seedBackedUpAt = if (backedUp) backedUpAt else 0L,
                seedBackupSecretId = if (backedUp) secretId else null,
            )
        )
    }

    /**
     * Send a request to the vault via OwnerSpaceClient and await the response.
     */
    private suspend fun <T> sendAndAwait(
        messageType: String,
        payload: JsonObject,
        timeoutMs: Long = 30_000,
        transform: (JsonObject) -> T
    ): Result<T> {
        Log.d(TAG, "Sending $messageType request via OwnerSpaceClient")

        return try {
            val response = ownerSpaceClient.sendAndAwaitResponse(messageType, payload, timeoutMs)

            when (response) {
                is VaultResponse.HandlerResult -> {
                    if (response.success && response.result != null) {
                        try {
                            val parsed = transform(response.result)
                            Result.success(parsed)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse $messageType response", e)
                            Result.failure(e)
                        }
                    } else {
                        val error = response.error ?: "Request failed"
                        Log.w(TAG, "$messageType failed: $error")
                        Result.failure(Exception(error))
                    }
                }
                is VaultResponse.Error -> {
                    Log.e(TAG, "$messageType error: ${response.code} - ${response.message}")
                    Result.failure(Exception(response.message))
                }
                null -> {
                    Log.e(TAG, "$messageType request timed out")
                    Result.failure(Exception("Request timed out"))
                }
                else -> {
                    Log.w(TAG, "$messageType unexpected response: ${response::class.simpleName}")
                    Result.failure(Exception("Unexpected response type"))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$messageType failed: ${e.message}", e)
            Result.failure(e)
        }
    }
}

sealed interface WalletDetailState {
    data object Loading : WalletDetailState
    data class Loaded(val wallet: WalletInfo) : WalletDetailState
    data class Error(val message: String) : WalletDetailState
}

sealed interface WalletDetailEffect {
    data class ShowError(val message: String) : WalletDetailEffect
    data class ShowSuccess(val message: String) : WalletDetailEffect
}

// MARK: - Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletDetailScreen(
    viewModel: WalletDetailViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onSend: (String) -> Unit = {},
    onReceive: (String) -> Unit = {},
    onTransactionClick: (String, String) -> Unit = { _, _ -> }
) {
    val state by viewModel.state.collectAsState()
    val balance by viewModel.balance.collectAsState()
    val transactions by viewModel.transactions.collectAsState()
    val isRefreshingBalance by viewModel.isRefreshingBalance.collectAsState()
    val showPublicWarning by viewModel.showPublicWarning.collectAsState()
    val backupPasswordPrompt by viewModel.backupPasswordPrompt.collectAsState()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is WalletDetailEffect.ShowError -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is WalletDetailEffect.ShowSuccess -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
            }
        }
    }

    // Seed-backup password prompt
    if (backupPasswordPrompt != null) {
        SeedBackupPasswordDialog(
            mode = backupPasswordPrompt!!,
            onConfirm = { password -> viewModel.confirmBackupPrompt(password) },
            onCancel = { viewModel.cancelBackupPrompt() },
        )
    }

    // Public visibility warning dialog
    if (showPublicWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPublicWarning() },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Make Wallet Public?") },
            text = {
                Text("Making your wallet address public allows anyone with your profile to see your Bitcoin address and transaction history. This cannot be undone without changing your address.")
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmMakePublic() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Make Public")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPublicWarning() }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val walletLabel = (state as? WalletDetailState.Loaded)?.wallet?.label ?: "Wallet"
                    Text(walletLabel)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        bottomBar = {
            val loaded = state as? WalletDetailState.Loaded
            if (loaded != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { onSend(loaded.wallet.walletId) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Send")
                        }

                        OutlinedButton(
                            onClick = { onReceive(loaded.wallet.walletId) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.CallReceived,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Receive")
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when (val currentState = state) {
            is WalletDetailState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is WalletDetailState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Error",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = currentState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { viewModel.loadWalletDetail() }) {
                            Text("Retry")
                        }
                    }
                }
            }

            is WalletDetailState.Loaded -> {
                val backupInFlight by viewModel.backupInFlight.collectAsState()
                WalletDetailContent(
                    wallet = currentState.wallet,
                    balance = balance,
                    transactions = transactions,
                    isRefreshingBalance = isRefreshingBalance,
                    seedBackupInFlight = backupInFlight,
                    onRefreshBalance = { viewModel.refreshBalance() },
                    onTogglePublic = { viewModel.requestTogglePublic(it) },
                    onToggleSeedBackup = { viewModel.requestBackupToggle(it) },
                    onCopyAddress = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Bitcoin Address", currentState.wallet.address))
                    },
                    onTransactionClick = { txid -> onTransactionClick(currentState.wallet.walletId, txid) },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
private fun WalletDetailContent(
    wallet: WalletInfo,
    balance: BalanceInfo?,
    transactions: List<TxHistoryEntry>,
    isRefreshingBalance: Boolean,
    seedBackupInFlight: Boolean,
    onRefreshBalance: () -> Unit,
    onTogglePublic: (Boolean) -> Unit,
    onToggleSeedBackup: (Boolean) -> Unit,
    onCopyAddress: () -> Unit,
    onTransactionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Address section with QR code
        item {
            AddressCard(
                address = wallet.address,
                onCopy = onCopyAddress
            )
        }

        // Balance section
        item {
            BalanceCard(
                balance = balance,
                cachedBalanceSats = wallet.cachedBalanceSats,
                isRefreshing = isRefreshingBalance,
                onRefresh = onRefreshBalance
            )
        }

        // Public/Private toggle
        item {
            VisibilityCard(
                isPublic = wallet.isPublic,
                onToggle = onTogglePublic
            )
        }

        // Seed-phrase backup toggle. Off by default; flipping on
        // copies the wallet's BIP39 mnemonic into the user's
        // Critical Secrets list (gated by password re-auth).
        item {
            SeedBackupCard(
                backedUp = wallet.seedBackedUpAt > 0,
                inFlight = seedBackupInFlight,
                onToggle = onToggleSeedBackup,
            )
        }

        // Transaction history header
        if (transactions.isNotEmpty()) {
            item {
                Text(
                    text = "TRANSACTION HISTORY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(
                items = transactions,
                key = { it.txid }
            ) { tx ->
                TransactionRow(
                    tx = tx,
                    onClick = { onTransactionClick(tx.txid) }
                )
            }
        } else {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No transactions yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Bottom spacer for bottom bar
        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AddressCard(
    address: String,
    onCopy: () -> Unit
) {
    val qrBitmap = remember(address) {
        generateQrCode(address, 400)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Address",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // QR Code
            qrBitmap?.let { bitmap ->
                Card(
                    modifier = Modifier.size(200.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Bitcoin address QR code",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Address text
            Text(
                text = address,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Copy button
            OutlinedButton(onClick = onCopy) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy Address")
            }
        }
    }
}

@Composable
private fun BalanceCard(
    balance: BalanceInfo?,
    cachedBalanceSats: Long,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Balance",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                IconButton(
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                    modifier = Modifier.size(32.dp)
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh balance",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (balance != null) {
                // Confirmed balance
                Text(
                    text = formatBtcBalance(balance.confirmedSats),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                // Unconfirmed balance (if any)
                if (balance.unconfirmedSats != 0L) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${formatBtcBalance(balance.unconfirmedSats)} unconfirmed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Show cached balance
                Text(
                    text = formatBtcBalance(cachedBalanceSats),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Cached balance",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun VisibilityCard(
    isPublic: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isPublic) Icons.Default.Public else Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isPublic) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isPublic) "Public Wallet" else "Private Wallet",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = if (isPublic) "Address visible to your connections"
                    else "Address only visible to you",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isPublic,
                onCheckedChange = { onToggle(it) }
            )
        }
    }
}

/**
 * Seed-phrase backup card. Off by default; flipping on (with
 * password re-auth) copies the wallet's BIP39 mnemonic into Critical
 * Secrets so the user can view, screenshot, or transcribe it. The
 * mnemonic always lives in the vault — this toggle just controls
 * whether it's surfaced through the credential blob.
 */
@Composable
private fun SeedBackupCard(
    backedUp: Boolean,
    inFlight: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (backedUp) Icons.Default.VpnKey else Icons.Default.Backup,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (backedUp) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Back up seed phrase",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = if (backedUp)
                        "Visible in Critical Secrets. Tap to remove."
                    else
                        "Add the wallet's 12-word recovery phrase to your Critical Secrets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (inFlight) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Switch(
                    checked = backedUp,
                    onCheckedChange = { onToggle(it) },
                )
            }
        }
    }
}

@Composable
private fun SeedBackupPasswordDialog(
    mode: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val title = if (mode == "enable") "Add Seed to Critical Secrets" else "Remove Seed from Critical Secrets"
    val body = if (mode == "enable")
        "Enter your account password to copy this wallet's 12-word seed phrase into your Critical Secrets list. The seed never leaves your enclave; this only changes whether you can reveal it."
    else
        "Enter your account password to remove the seed phrase from Critical Secrets. The wallet keeps working — only the user-visible Critical Secret entry is removed."
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column {
                Text(body)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Account password") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password) },
                enabled = password.isNotBlank(),
            ) {
                Text(if (mode == "enable") "Back up" else "Remove")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}

@Composable
private fun TransactionRow(
    tx: TxHistoryEntry,
    onClick: () -> Unit
) {
    val isSent = tx.direction == "sent"

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Direction icon
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(8.dp),
                color = if (isSent) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isSent) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                        contentDescription = if (isSent) "Sent" else "Received",
                        tint = if (isSent) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isSent) "Sent" else "Received",
                    style = MaterialTheme.typography.titleSmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!tx.confirmed) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = "Unconfirmed",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Pending",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        tx.blockTime?.let { timestamp ->
                            Text(
                                text = formatTxDate(timestamp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Amount
            Text(
                text = "${if (isSent) "-" else "+"}${formatBtcBalance(tx.amountSats)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (isSent) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        }
    }
}

// MARK: - Utility Functions

private fun generateQrCode(content: String, size: Int): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(
                    x, y,
                    if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                )
            }
        }
        bitmap
    } catch (e: Exception) {
        Log.e(TAG, "QR code generation failed", e)
        null
    }
}

private fun formatTxDate(timestamp: Long): String {
    val timestampMillis = if (timestamp < 10000000000L) timestamp * 1000 else timestamp
    val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return sdf.format(Date(timestampMillis))
}
