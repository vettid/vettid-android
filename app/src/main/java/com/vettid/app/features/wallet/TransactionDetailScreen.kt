package com.vettid.app.features.wallet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vettid.app.core.nats.NatsAutoConnector
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

private const val TAG = "TxDetailVM"

// MARK: - ViewModel

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val natsAutoConnector: NatsAutoConnector,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val walletId: String = savedStateHandle["walletId"]
        ?: throw IllegalArgumentException("walletId is required")
    private val txid: String = savedStateHandle["txid"]
        ?: throw IllegalArgumentException("txid is required")

    private val gson = Gson()

    private val _state = MutableStateFlow<TxDetailState>(TxDetailState.Loading)
    val state: StateFlow<TxDetailState> = _state.asStateFlow()

    init {
        loadTransactionDetail()
    }

    fun loadTransactionDetail() {
        viewModelScope.launch {
            try {
                _state.value = TxDetailState.Loading

                val payload = JsonObject().apply {
                    addProperty("wallet_id", walletId)
                    addProperty("txid", txid)
                }

                val result = sendAndAwait("wallet.transaction-detail", payload) { json ->
                    val tx = TxHistoryEntry(
                        txid = json.get("txid")?.asString ?: txid,
                        direction = json.get("direction")?.asString ?: "received",
                        amountSats = json.get("amount_sats")?.asLong ?: 0L,
                        feeSats = json.get("fee_sats")?.asLong ?: 0L,
                        confirmed = json.get("confirmed")?.asBoolean ?: false,
                        blockHeight = json.get("block_height")?.asLong,
                        blockTime = json.get("block_time")?.asLong
                    )
                    val confirmations = json.get("confirmations")?.asInt ?: 0
                    Pair(tx, confirmations)
                }

                result.onSuccess { (tx, confirmations) ->
                    _state.value = TxDetailState.Loaded(tx, confirmations)
                }.onFailure { e ->
                    _state.value = TxDetailState.Error(e.message ?: "Failed to load transaction")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading transaction detail", e)
                _state.value = TxDetailState.Error("Error: ${e.message}")
            }
        }
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

sealed interface TxDetailState {
    data object Loading : TxDetailState
    data class Loaded(val tx: TxHistoryEntry, val confirmations: Int = 0) : TxDetailState
    data class Error(val message: String) : TxDetailState
}

// MARK: - Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    viewModel: TransactionDetailViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transaction") },
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
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when (val currentState = state) {
            is TxDetailState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is TxDetailState.Error -> {
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
                        Button(onClick = { viewModel.loadTransactionDetail() }) {
                            Text("Retry")
                        }
                    }
                }
            }

            is TxDetailState.Loaded -> {
                TransactionDetailContent(
                    tx = currentState.tx,
                    confirmations = currentState.confirmations,
                    onCopyTxid = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Transaction ID", currentState.tx.txid))
                    },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
private fun TransactionDetailContent(
    tx: TxHistoryEntry,
    confirmations: Int,
    onCopyTxid: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSent = tx.direction == "sent"

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Direction + Amount header
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Direction icon
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSent) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isSent) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                            contentDescription = if (isSent) "Sent" else "Received",
                            tint = if (isSent) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (isSent) "Sent" else "Received",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "${if (isSent) "-" else "+"}${formatBtcBalance(tx.amountSats)}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isSent) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "${tx.amountSats} sats",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Details card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Status
                DetailRow(
                    icon = if (tx.confirmed) Icons.Default.CheckCircle else Icons.Default.Schedule,
                    label = "Status",
                    value = if (tx.confirmed) "Confirmed" else "Pending",
                    valueColor = if (tx.confirmed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )

                // Confirmations
                DetailRow(
                    icon = Icons.Default.Layers,
                    label = "Confirmations",
                    value = if (tx.confirmed) "$confirmations" else "0"
                )

                // Fee
                if (tx.feeSats > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                    DetailRow(
                        icon = Icons.Default.LocalGasStation,
                        label = "Fee",
                        value = "${formatBtcBalance(tx.feeSats)} (${tx.feeSats} sats)"
                    )
                }

                // Block height
                tx.blockHeight?.let { height ->
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                    DetailRow(
                        icon = Icons.Default.ViewInAr,
                        label = "Block Height",
                        value = "%,d".format(height)
                    )
                }

                // Block time
                tx.blockTime?.let { timestamp ->
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                    DetailRow(
                        icon = Icons.Default.AccessTime,
                        label = "Time",
                        value = formatTxDateTime(timestamp)
                    )
                }
            }
        }

        // Transaction ID card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Transaction ID",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = tx.txid,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onCopyTxid,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Transaction ID")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun DetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = valueColor
            )
        }
    }
}

// MARK: - Utility

private fun formatTxDateTime(timestamp: Long): String {
    val timestampMillis = if (timestamp < 10000000000L) timestamp * 1000 else timestamp
    val sdf = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
    return sdf.format(Date(timestampMillis))
}
