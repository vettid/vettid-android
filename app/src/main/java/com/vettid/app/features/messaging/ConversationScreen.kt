package com.vettid.app.features.messaging

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.gson.Gson
import com.vettid.app.core.network.Message
import com.vettid.app.core.network.MessageContentType
import com.vettid.app.core.network.MessageStatus
import com.vettid.app.features.wallet.BtcAddress
import com.vettid.app.features.wallet.BtcPaymentReceipt
import com.vettid.app.features.wallet.PaymentRequest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Screen for viewing and sending messages in a conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    viewModel: ConversationViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onConnectionDetail: () -> Unit = {},
    onPaymentRequest: (connectionId: String) -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val connection by viewModel.connection.collectAsState()
    val messageText by viewModel.messageText.collectAsState()
    val isSending by viewModel.isSending.collectAsState()

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val snackbarHostState = remember { SnackbarHostState() }

    // Show scroll-to-bottom FAB when not at bottom
    val showScrollToBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 3
        }
    }

    // Handle effects
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ConversationEffect.ShowError -> {
                    snackbarHostState.showSnackbar(
                        message = effect.message,
                        duration = SnackbarDuration.Short
                    )
                }
                is ConversationEffect.NavigateToConnectionDetail -> {
                    onConnectionDetail()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                title = {
                    Row(
                        modifier = Modifier.clickable { viewModel.onConnectionDetailClick() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Avatar
                        Surface(
                            modifier = Modifier.size(36.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = (connection?.peerDisplayName ?: "?").take(2).uppercase(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = connection?.peerDisplayName ?: "Loading...",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
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
            MessageInput(
                text = messageText,
                onTextChanged = { viewModel.onMessageTextChanged(it) },
                onSend = { viewModel.sendMessage() },
                isSending = isSending
            )
        },
        floatingActionButton = {
            if (showScrollToBottom) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(0)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Scroll to bottom"
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val currentState = state) {
                is ConversationState.Loading -> {
                    LoadingContent()
                }

                is ConversationState.Empty -> {
                    EmptyContent()
                }

                is ConversationState.Loaded -> {
                    MessageList(
                        messages = currentState.messages,
                        hasMore = currentState.hasMore,
                        isLoadingMore = currentState.isLoadingMore,
                        listState = listState,
                        isFromCurrentUser = { viewModel.isFromCurrentUser(it) },
                        onLoadMore = { viewModel.loadMoreMessages() },
                        onPaymentRequest = { onPaymentRequest(connection?.connectionId ?: "") }
                    )
                }

                is ConversationState.Error -> {
                    ErrorContent(
                        message = currentState.message,
                        onRetry = { viewModel.loadMessages() }
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<Message>,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    listState: LazyListState,
    isFromCurrentUser: (Message) -> Boolean,
    onLoadMore: () -> Unit,
    onPaymentRequest: () -> Unit = {}
) {
    // Load more when reaching the end
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisibleItem >= messages.size - 5 && hasMore && !isLoadingMore
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            onLoadMore()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp)
    ) {
        itemsIndexed(
            items = messages,
            key = { index, msg -> "${msg.messageId}-$index" }
        ) { _, message ->
            val isSent = isFromCurrentUser(message)
            MessageBubble(
                message = message,
                isSent = isSent,
                onPaymentRequest = onPaymentRequest
            )
        }

        if (isLoadingMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: Message,
    isSent: Boolean,
    onPaymentRequest: () -> Unit = {}
) {
    val alignment = if (isSent) Alignment.End else Alignment.Start
    val backgroundColor = if (isSent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isSent) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = alignment
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isSent) 16.dp else 4.dp,
                bottomEnd = if (isSent) 4.dp else 16.dp
            ),
            color = backgroundColor
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // Render content based on content type
                when (message.contentType) {
                    MessageContentType.BTC_PAYMENT_REQUEST -> {
                        BtcPaymentRequestContent(
                            content = message.content,
                            isSent = isSent,
                            textColor = textColor,
                            onPay = onPaymentRequest
                        )
                    }
                    MessageContentType.BTC_PAYMENT_RECEIPT -> {
                        BtcPaymentReceiptContent(
                            content = message.content,
                            isSent = isSent,
                            textColor = textColor
                        )
                    }
                    MessageContentType.BTC_ADDRESS -> {
                        BtcAddressContent(
                            content = message.content,
                            isSent = isSent,
                            textColor = textColor
                        )
                    }
                    else -> {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatMessageTime(message.sentAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f)
                    )

                    if (isSent) {
                        Spacer(modifier = Modifier.width(4.dp))
                        MessageStatusIcon(
                            status = message.status,
                            tint = textColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Bitcoin Content Type Composables

@Composable
private fun BtcPaymentRequestContent(
    content: String,
    isSent: Boolean,
    textColor: androidx.compose.ui.graphics.Color,
    onPay: () -> Unit
) {
    val request = remember(content) {
        try {
            Gson().fromJson(content, PaymentRequest::class.java)
        } catch (e: Exception) {
            null
        }
    }

    if (request == null) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor
        )
        return
    }

    val btcAmount = String.format("%.8f BTC", request.amountSats / 100_000_000.0)

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.AccountBalance,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = textColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Payment Request",
                style = MaterialTheme.typography.labelMedium,
                color = textColor.copy(alpha = 0.8f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = btcAmount,
            style = MaterialTheme.typography.titleMedium,
            color = textColor
        )

        if (!request.memo.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = request.memo,
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.8f)
            )
        }

        if (!isSent) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onPay,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Pay")
            }
        }
    }
}

@Composable
private fun BtcPaymentReceiptContent(
    content: String,
    isSent: Boolean,
    textColor: androidx.compose.ui.graphics.Color
) {
    val receipt = remember(content) {
        try {
            Gson().fromJson(content, BtcPaymentReceipt::class.java)
        } catch (e: Exception) {
            null
        }
    }

    if (receipt == null) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor
        )
        return
    }

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val btcAmount = String.format("%.8f BTC", receipt.amountSats / 100_000_000.0)
    val btcFee = String.format("%.8f BTC", receipt.feeSats / 100_000_000.0)
    val truncatedTxid = if (receipt.txid.length > 16) {
        "${receipt.txid.take(8)}...${receipt.txid.takeLast(8)}"
    } else {
        receipt.txid
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = textColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isSent) "Payment Sent" else "Payment Received",
                style = MaterialTheme.typography.labelMedium,
                color = textColor.copy(alpha = 0.8f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = btcAmount,
            style = MaterialTheme.typography.titleMedium,
            color = textColor
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Fee: $btcFee",
            style = MaterialTheme.typography.bodySmall,
            color = textColor.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable {
                clipboardManager.setText(AnnotatedString(receipt.txid))
                Toast.makeText(context, "Transaction ID copied", Toast.LENGTH_SHORT).show()
            }
        ) {
            Text(
                text = truncatedTxid,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = textColor.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy transaction ID",
                modifier = Modifier.size(14.dp),
                tint = textColor.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun BtcAddressContent(
    content: String,
    isSent: Boolean,
    textColor: androidx.compose.ui.graphics.Color
) {
    val btcAddress = remember(content) {
        try {
            Gson().fromJson(content, BtcAddress::class.java)
        } catch (e: Exception) {
            null
        }
    }

    if (btcAddress == null) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor
        )
        return
    }

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val truncatedAddress = if (btcAddress.address.length > 20) {
        "${btcAddress.address.take(10)}...${btcAddress.address.takeLast(10)}"
    } else {
        btcAddress.address
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.AccountBalance,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = textColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = btcAddress.label ?: "Bitcoin Address",
                style = MaterialTheme.typography.labelMedium,
                color = textColor.copy(alpha = 0.8f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = truncatedAddress,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(btcAddress.address))
                    Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy address",
                    modifier = Modifier.size(16.dp),
                    tint = textColor
                )
            }
        }
    }
}

@Composable
private fun MessageStatusIcon(
    status: MessageStatus,
    tint: androidx.compose.ui.graphics.Color
) {
    val iconText = when (status) {
        MessageStatus.SENDING -> "..."
        MessageStatus.SENT -> "✓"
        MessageStatus.DELIVERED -> "✓✓"
        MessageStatus.READ -> "✓✓"
        MessageStatus.FAILED -> "!"
    }

    Text(
        text = iconText,
        style = MaterialTheme.typography.labelSmall,
        color = if (status == MessageStatus.READ) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
        } else {
            tint
        }
    )
}

@Composable
private fun MessageInput(
    text: String,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChanged,
                placeholder = { Text("Message...") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                shape = RoundedCornerShape(24.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            FilledIconButton(
                onClick = onSend,
                enabled = text.isNotBlank() && !isSending,
                modifier = Modifier.size(48.dp)
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send"
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "No messages yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Send a message to start the conversation",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Error",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

// MARK: - Utility Functions

private fun formatMessageTime(timestamp: Long): String {
    val timestampMillis = if (timestamp < 10000000000L) timestamp * 1000 else timestamp
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestampMillis))
}
