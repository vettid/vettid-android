package com.vettid.app.features.services

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.vettid.app.features.services.models.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Activity history view showing all user-service interactions.
 *
 * Features:
 * - Timeline view with date grouping
 * - Filter by service
 * - Filter by activity type
 * - Search
 *
 * Issue #42 [AND-050] - Activity History View.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityHistoryScreen(
    viewModel: ActivityHistoryViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val activities by viewModel.activities.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedType by viewModel.selectedType.collectAsState()
    val selectedService by viewModel.selectedService.collectAsState()
    val services by viewModel.services.collectAsState()

    var showFilterSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showFilterSheet = true }) {
                        Badge(
                            modifier = Modifier.offset(x = 8.dp, y = (-8).dp),
                            containerColor = if (selectedType != null || selectedService != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Transparent
                            }
                        ) {}
                        Icon(Icons.Default.FilterList, contentDescription = "Filter")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            SearchBar(
                query = searchQuery,
                onQueryChange = viewModel::setSearchQuery,
                onSearch = {},
                active = false,
                onActiveChange = {},
                placeholder = { Text("Search activities...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {}

            // Active filters
            if (selectedType != null || selectedService != null) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    selectedType?.let { type ->
                        item {
                            FilterChip(
                                selected = true,
                                onClick = { viewModel.setTypeFilter(null) },
                                label = { Text(activityTypeName(type)) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove filter",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                    selectedService?.let { service ->
                        item {
                            FilterChip(
                                selected = true,
                                onClick = { viewModel.setServiceFilter(null) },
                                label = { Text(service) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove filter",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }

            when {
                isLoading -> {
                    LoadingContent()
                }
                activities.isEmpty() -> {
                    EmptyContent()
                }
                else -> {
                    ActivityTimeline(activities = activities)
                }
            }
        }
    }

    // Filter bottom sheet
    if (showFilterSheet) {
        FilterBottomSheet(
            selectedType = selectedType,
            selectedService = selectedService,
            services = services,
            onTypeSelected = viewModel::setTypeFilter,
            onServiceSelected = viewModel::setServiceFilter,
            onDismiss = { showFilterSheet = false }
        )
    }
}

@Composable
private fun ActivityTimeline(activities: List<ActivityEvent>) {
    // Group by date
    val grouped = activities.groupBy { event ->
        val now = Instant.now()
        val eventDay = event.timestamp.atZone(ZoneId.systemDefault()).toLocalDate()
        val today = now.atZone(ZoneId.systemDefault()).toLocalDate()

        when {
            eventDay == today -> "Today"
            eventDay == today.minusDays(1) -> "Yesterday"
            eventDay.isAfter(today.minusWeeks(1)) -> "This Week"
            eventDay.isAfter(today.minusMonths(1)) -> "This Month"
            else -> eventDay.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        grouped.forEach { (dateGroup, groupActivities) ->
            item {
                Text(
                    text = dateGroup,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            items(groupActivities, key = { it.id }) { activity ->
                ActivityEventCard(event = activity)
            }
        }
    }
}

@Composable
private fun ActivityEventCard(event: ActivityEvent) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Service logo
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (event.serviceLogoUrl != null) {
                    AsyncImage(
                        model = event.serviceLogoUrl,
                        contentDescription = event.serviceName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = activityIcon(event),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = activityColor(event)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = event.serviceName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = formatTime(event.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Activity-specific content
                when (event) {
                    is ActivityEvent.Authentication -> {
                        AuthenticationContent(event)
                    }
                    is ActivityEvent.DataRequest -> {
                        DataRequestContent(event)
                    }
                    is ActivityEvent.Payment -> {
                        PaymentContent(event)
                    }
                    is ActivityEvent.Notification -> {
                        NotificationContent(event)
                    }
                    is ActivityEvent.ContractChange -> {
                        ContractChangeContent(event)
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthenticationContent(event: ActivityEvent.Authentication) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (event.success) Icons.Default.CheckCircle else Icons.Default.Cancel,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (event.success) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (event.success) "Authentication successful" else "Authentication failed",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(
            text = "Method: ${event.method}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        event.context?.let { ctx ->
            Text(
                text = ctx,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DataRequestContent(event: ActivityEvent.DataRequest) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (event.approved) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (event.approved) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Data request: ${event.dataType}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (event.approved) {
                    if (event.sharedOnce) "Shared once" else "Added to contract"
                } else {
                    "Denied"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PaymentContent(event: ActivityEvent.Payment) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = event.amount.formatted(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = when (event.status) {
                    PaymentStatus.COMPLETED -> Color(0xFF4CAF50)
                    PaymentStatus.PENDING -> MaterialTheme.colorScheme.tertiary
                    PaymentStatus.FAILED -> MaterialTheme.colorScheme.error
                    PaymentStatus.REFUNDED -> MaterialTheme.colorScheme.secondary
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                color = when (event.status) {
                    PaymentStatus.COMPLETED -> Color(0xFF4CAF50).copy(alpha = 0.1f)
                    PaymentStatus.PENDING -> MaterialTheme.colorScheme.tertiaryContainer
                    PaymentStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                    PaymentStatus.REFUNDED -> MaterialTheme.colorScheme.secondaryContainer
                },
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = event.status.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        event.description?.let { desc ->
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NotificationContent(event: ActivityEvent.Notification) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (event.read) Icons.Outlined.Notifications else Icons.Default.Notifications,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = event.title,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ContractChangeContent(event: ActivityEvent.ContractChange) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.Description,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = event.changeType.displayName,
            style = MaterialTheme.typography.bodyMedium
        )
        if (event.fromVersion != null && event.toVersion != null) {
            Text(
                text = " (v${event.fromVersion} → v${event.toVersion})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun activityIcon(event: ActivityEvent): androidx.compose.ui.graphics.vector.ImageVector {
    return when (event) {
        is ActivityEvent.Authentication -> Icons.Outlined.Security
        is ActivityEvent.DataRequest -> Icons.Outlined.Description
        is ActivityEvent.Payment -> Icons.Outlined.Payment
        is ActivityEvent.Notification -> Icons.Outlined.Notifications
        is ActivityEvent.ContractChange -> Icons.Outlined.Description
    }
}

@Composable
private fun activityColor(event: ActivityEvent): Color {
    return when (event) {
        is ActivityEvent.Authentication -> {
            if (event.success) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
        }
        is ActivityEvent.DataRequest -> MaterialTheme.colorScheme.tertiary
        is ActivityEvent.Payment -> Color(0xFF4CAF50)
        is ActivityEvent.Notification -> MaterialTheme.colorScheme.primary
        is ActivityEvent.ContractChange -> MaterialTheme.colorScheme.secondary
    }
}

private fun formatTime(instant: Instant): String {
    val formatter = DateTimeFormatter.ofPattern("h:mm a")
        .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}

private fun activityTypeName(type: String): String {
    return when (type) {
        "authentication" -> "Authentication"
        "data_request" -> "Data Request"
        "payment" -> "Payment"
        "notification" -> "Notification"
        "contract_change" -> "Contract Change"
        else -> type
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBottomSheet(
    selectedType: String?,
    selectedService: String?,
    services: List<String>,
    onTypeSelected: (String?) -> Unit,
    onServiceSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Filter Activities",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Activity Type",
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val types = listOf("authentication", "data_request", "payment", "notification", "contract_change")
                items(types) { type ->
                    FilterChip(
                        selected = type == selectedType,
                        onClick = {
                            onTypeSelected(if (type == selectedType) null else type)
                        },
                        label = { Text(activityTypeName(type)) }
                    )
                }
            }

            if (services.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Service",
                    style = MaterialTheme.typography.titleSmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(services) { service ->
                        FilterChip(
                            selected = service == selectedService,
                            onClick = {
                                onServiceSelected(if (service == selectedService) null else service)
                            },
                            label = { Text(service) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
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
            Icon(
                imageVector = Icons.Outlined.History,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No activity yet",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Your interactions with connected services will appear here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
