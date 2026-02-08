package com.vettid.app.features.services

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.vettid.app.features.services.models.*

/**
 * Payment request approval bottom sheet.
 *
 * Displays payment details with method selection and biometric confirmation.
 *
 * Issue #37 [AND-024] - Payment request prompt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentRequestSheet(
    requestId: String,
    viewModel: PaymentViewModel = hiltViewModel(),
    onApproved: (PaymentResult) -> Unit = {},
    onDenied: () -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    val request by viewModel.request.collectAsState()
    val paymentMethods by viewModel.paymentMethods.collectAsState()
    val selectedMethod by viewModel.selectedMethod.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val requiresBiometric by viewModel.requiresBiometric.collectAsState()

    // Load request on first composition
    LaunchedEffect(requestId) {
        viewModel.loadRequest(requestId)
    }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PaymentEffect.PaymentCompleted -> onApproved(effect.result)
                is PaymentEffect.PaymentCancelled -> onDenied()
            }
        }
    }

    val currentRequest = request
    if (currentRequest == null) {
        LoadingContent()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Handle indicator
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Service header
        ServicePaymentHeader(
            serviceName = currentRequest.serviceName,
            serviceLogoUrl = currentRequest.serviceLogoUrl
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Amount display
        Text(
            text = currentRequest.formattedAmount,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = currentRequest.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        // Subscription info
        currentRequest.subscriptionDetails?.let { details ->
            Spacer(modifier = Modifier.height(16.dp))
            SubscriptionInfoCard(details = details)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        // Payment methods section
        Text(
            text = "Pay with",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        )

        if (paymentMethods.isEmpty()) {
            NoPaymentMethodsCard(onAddMethod = { /* TODO: Navigate to add payment method */ })
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(paymentMethods) { method ->
                    PaymentMethodOption(
                        method = method,
                        isSelected = method.id == selectedMethod?.id,
                        onSelect = { viewModel.selectMethod(method) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Security notice
        if (requiresBiometric) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Biometric confirmation required",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = {
                    viewModel.deny()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
                enabled = !isProcessing
            ) {
                Text("Cancel")
            }

            Button(
                onClick = { viewModel.approve() },
                modifier = Modifier.weight(1f),
                enabled = selectedMethod != null && !isProcessing
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Pay ${currentRequest.formattedAmount}")
                }
            }
        }
    }
}

@Composable
private fun ServicePaymentHeader(
    serviceName: String,
    serviceLogoUrl: String?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (serviceLogoUrl != null) {
                AsyncImage(
                    model = serviceLogoUrl,
                    contentDescription = serviceName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Business,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column {
            Text(
                text = serviceName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Payment Request",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SubscriptionInfoCard(details: SubscriptionDetails) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Repeat,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Subscription",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.secondary,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = details.billingCycle.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (details.trialDays > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.CardGiftcard,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFF4CAF50)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${details.trialDays}-day free trial",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4CAF50)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (details.autoRenew) {
                Text(
                    text = "Auto-renews until cancelled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            details.cancellationPolicy?.let { policy ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = policy,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PaymentMethodOption(
    method: PaymentMethod,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp)
                    )
                } else Modifier
            )
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Payment method icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (method.type) {
                        PaymentMethodType.CARD -> Icons.Default.CreditCard
                        PaymentMethodType.BANK_ACCOUNT -> Icons.Default.AccountBalance
                        PaymentMethodType.CRYPTO -> Icons.Default.CurrencyBitcoin
                        PaymentMethodType.APPLE_PAY -> Icons.Default.Smartphone
                        PaymentMethodType.GOOGLE_PAY -> Icons.Default.Smartphone
                    },
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = when (method.network) {
                        CardNetwork.VISA -> Color(0xFF1A1F71)
                        CardNetwork.MASTERCARD -> Color(0xFFEB001B)
                        CardNetwork.AMEX -> Color(0xFF006FCF)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = method.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                when (method.type) {
                    PaymentMethodType.CARD -> {
                        Row {
                            method.maskedNumber?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            method.expiryDisplay?.let {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Exp: $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    PaymentMethodType.CRYPTO -> {
                        method.cryptoCurrency?.let { currency ->
                            Text(
                                text = currency.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {}
                }
            }

            if (method.isDefault) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "Default",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (isSelected) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun NoPaymentMethodsCard(onAddMethod: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.CreditCardOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "No payment methods",
                style = MaterialTheme.typography.titleSmall
            )

            Text(
                text = "Add a payment method to continue",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(onClick = onAddMethod) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Payment Method")
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}
