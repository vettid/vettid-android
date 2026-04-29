package com.vettid.app.features.wallet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Full-screen send Bitcoin flow.
 *
 * Steps:
 * 1. Select wallet (if multiple)
 * 2. Enter recipient address
 * 3. Enter amount
 * 4. Select fee tier
 * 5. Review and confirm
 *
 * Transaction signing happens in the enclave; the app never touches private keys.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendBtcScreen(
    viewModel: SendBtcViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onSuccess: () -> Unit = {}
) {
    val wallets by viewModel.wallets.collectAsState()
    val selectedWalletId by viewModel.selectedWalletId.collectAsState()
    val feeEstimates by viewModel.feeEstimates.collectAsState()
    val sendState by viewModel.sendState.collectAsState()

    var recipientAddress by remember { mutableStateOf("") }
    var amountBtc by remember { mutableStateOf("") }
    var selectedFeeTier by remember { mutableStateOf(FeeTier.STANDARD) }
    var showConfirmation by remember { mutableStateOf(false) }
    var passwordPrompt by remember { mutableStateOf<String?>(null) }
    var passwordInput by remember { mutableStateOf("") }

    val selectedWallet = wallets.find { it.walletId == selectedWalletId }
    val amountSats = btcToSats(amountBtc)
    val feeRate = selectedFeeTier.getRate(feeEstimates)
    val seedInCredential = selectedWallet?.seedBackupSecretId != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send Bitcoin") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        when (val currentSendState = sendState) {
            is SendState.Success -> {
                SuccessContent(
                    txid = currentSendState.txid,
                    onDone = {
                        viewModel.resetSendState()
                        onSuccess()
                    },
                    modifier = Modifier.padding(padding)
                )
            }

            else -> {
                if (showConfirmation && selectedWallet != null) {
                    ConfirmationContent(
                        wallet = selectedWallet,
                        recipientAddress = recipientAddress,
                        amountSats = amountSats,
                        feeTier = selectedFeeTier,
                        feeRate = feeRate,
                        isLoading = sendState is SendState.Loading,
                        error = (sendState as? SendState.Error)?.message,
                        onConfirm = {
                            if (seedInCredential) {
                                // Seed lives in the credential — every send needs
                                // the password so the enclave can decrypt it.
                                passwordPrompt = "send"
                            } else {
                                viewModel.send(
                                    walletId = selectedWalletId,
                                    toAddress = recipientAddress,
                                    amountSats = amountSats,
                                    feeRate = feeRate,
                                )
                            }
                        },
                        onBack = { showConfirmation = false },
                        modifier = Modifier.padding(padding)
                    )
                } else {
                    SendFormContent(
                        wallets = wallets,
                        selectedWalletId = selectedWalletId,
                        recipientAddress = recipientAddress,
                        amountBtc = amountBtc,
                        selectedFeeTier = selectedFeeTier,
                        feeEstimates = feeEstimates,
                        onWalletSelected = { viewModel.selectWallet(it) },
                        onRecipientChanged = { recipientAddress = it },
                        onAmountChanged = { amountBtc = it },
                        onFeeTierSelected = { selectedFeeTier = it },
                        onReview = { showConfirmation = true },
                        canReview = recipientAddress.isNotBlank() &&
                            amountSats > 0 &&
                            selectedWalletId.isNotBlank(),
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }
    }

    if (passwordPrompt != null) {
        AlertDialog(
            onDismissRequest = {
                passwordPrompt = null
                passwordInput = ""
            },
            icon = { Icon(Icons.Default.Lock, contentDescription = null) },
            title = { Text("Confirm with password") },
            text = {
                Column {
                    Text(
                        "This wallet's seed lives in your credential. Enter your password so the enclave can sign this transaction.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = passwordInput.isNotBlank(),
                    onClick = {
                        val pwd = passwordInput
                        passwordInput = ""
                        passwordPrompt = null
                        viewModel.send(
                            walletId = selectedWalletId,
                            toAddress = recipientAddress,
                            amountSats = amountSats,
                            feeRate = feeRate,
                            password = pwd,
                        )
                    },
                ) { Text("Sign and send") }
            },
            dismissButton = {
                TextButton(onClick = {
                    passwordPrompt = null
                    passwordInput = ""
                }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendFormContent(
    wallets: List<WalletInfo>,
    selectedWalletId: String,
    recipientAddress: String,
    amountBtc: String,
    selectedFeeTier: FeeTier,
    feeEstimates: FeeEstimate?,
    onWalletSelected: (String) -> Unit,
    onRecipientChanged: (String) -> Unit,
    onAmountChanged: (String) -> Unit,
    onFeeTierSelected: (FeeTier) -> Unit,
    onReview: () -> Unit,
    canReview: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Wallet selector (if multiple wallets)
        if (wallets.size > 1) {
            WalletSelector(
                wallets = wallets,
                selectedWalletId = selectedWalletId,
                onWalletSelected = onWalletSelected
            )
        } else if (wallets.size == 1) {
            // Show the single wallet info
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = wallets.first().label,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = formatBtcBalance(wallets.first().cachedBalanceSats) + " available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Recipient address
        OutlinedTextField(
            value = recipientAddress,
            onValueChange = onRecipientChanged,
            label = { Text("Recipient Address") },
            placeholder = { Text("bc1q...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            textStyle = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace
            )
        )

        // Amount input
        OutlinedTextField(
            value = amountBtc,
            onValueChange = { value ->
                // Only allow valid decimal input
                if (value.isEmpty() || value.matches(Regex("^\\d*\\.?\\d{0,8}$"))) {
                    onAmountChanged(value)
                }
            },
            label = { Text("Amount (BTC)") },
            placeholder = { Text("0.0") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            leadingIcon = {
                Icon(
                    Icons.Default.CurrencyBitcoin,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            supportingText = {
                val sats = btcToSats(amountBtc)
                if (sats > 0) {
                    Text("$sats sats")
                }
            }
        )

        // Fee tier selector
        Text(
            text = "Transaction Speed",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FeeTier.entries.forEach { tier ->
                FeeTierOption(
                    tier = tier,
                    feeEstimates = feeEstimates,
                    selected = selectedFeeTier == tier,
                    onClick = { onFeeTierSelected(tier) }
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Review button
        Button(
            onClick = onReview,
            enabled = canReview,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Review Transaction")
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletSelector(
    wallets: List<WalletInfo>,
    selectedWalletId: String,
    onWalletSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedWallet = wallets.find { it.walletId == selectedWalletId }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedWallet?.let {
                "${it.label} (${formatBtcBalance(it.cachedBalanceSats)})"
            } ?: "Select wallet",
            onValueChange = {},
            readOnly = true,
            label = { Text("From Wallet") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            leadingIcon = {
                Icon(
                    Icons.Default.AccountBalanceWallet,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            wallets.forEach { wallet ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = wallet.label,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = formatBtcBalance(wallet.cachedBalanceSats),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onWalletSelected(wallet.walletId)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun FeeTierOption(
    tier: FeeTier,
    feeEstimates: FeeEstimate?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val rate = tier.getRate(feeEstimates)

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tier.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
                Text(
                    text = tier.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "$rate sat/vB",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ConfirmationContent(
    wallet: WalletInfo,
    recipientAddress: String,
    amountSats: Long,
    feeTier: FeeTier,
    feeRate: Int,
    isLoading: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Text(
            text = "Confirm Transaction",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )

        // Summary card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                SummaryRow(label = "From", value = wallet.label)
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
                SummaryRow(
                    label = "To",
                    value = truncateAddress(recipientAddress),
                    monospace = true
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
                SummaryRow(label = "Amount", value = formatBtcBalance(amountSats))
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
                SummaryRow(label = "Speed", value = "${feeTier.label} ($feeRate sat/vB)")
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )

                // Estimated fee (approximate, actual fee depends on tx size)
                val estimatedFeeSats = feeRate.toLong() * 140 // ~140 vbytes for a typical P2WPKH tx
                SummaryRow(
                    label = "Est. Fee",
                    value = "~${formatBtcBalance(estimatedFeeSats)}"
                )
            }
        }

        // Error message
        error?.let { msg ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Action buttons
        Button(
            onClick = onConfirm,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sending...")
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Confirm & Send")
            }
        }

        OutlinedButton(
            onClick = onBack,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Go Back")
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    monospace: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
        )
    }
}

@Composable
private fun SuccessContent(
    txid: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Transaction Sent",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Your transaction has been broadcast to the network.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Transaction ID
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Transaction ID",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = txid,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Done")
            }
        }
    }
}

// MARK: - Fee Tier Model

enum class FeeTier(val label: String, val description: String) {
    FAST("Fast", "~10 minutes"),
    STANDARD("Standard", "~30 minutes"),
    ECONOMY("Economy", "~1 hour");

    fun getRate(estimates: FeeEstimate?): Int {
        if (estimates == null) return when (this) {
            FAST -> 10
            STANDARD -> 5
            ECONOMY -> 2
        }
        return when (this) {
            FAST -> estimates.fastestFee
            STANDARD -> estimates.halfHourFee
            ECONOMY -> estimates.hourFee
        }
    }
}

// MARK: - Utility

/**
 * Convert a BTC string to satoshis.
 */
private fun btcToSats(btcString: String): Long {
    if (btcString.isBlank()) return 0
    return try {
        (btcString.toDouble() * 100_000_000).toLong()
    } catch (e: NumberFormatException) {
        0
    }
}
