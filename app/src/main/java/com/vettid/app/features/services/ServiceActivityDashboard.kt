package com.vettid.app.features.services

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vettid.app.features.services.models.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Activity dashboard for a service connection.
 *
 * Shows all interactions between the user and service:
 * - Data requests and responses
 * - Authentication requests
 * - Payment requests
 * - Messages
 * - Data storage operations
 *
 * Provides filtering by activity type, date range, and status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceActivityDashboard(
    serviceName: String,
    activities: List<ServiceActivity>,
    summary: ActivitySummary?,
    isLoading: Boolean = false,
    error: String? = null,
    onBackClick: () -> Unit = {},
    onActivityClick: (ServiceActivity) -> Unit = {},
    onRefresh: () -> Unit = {}
) {
    var selectedFilter by remember { mutableStateOf<ServiceActivityType?>(null) }
    var selectedDateRange by remember { mutableStateOf(DateRange.ALL_TIME) }

    val filteredActivities = activities.filter { activity ->
        (selectedFilter == null || activity.activityType == selectedFilter) &&
        matchesDateRange(activity.timestamp, selectedDateRange)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Activity Dashboard")
                        Text(
                            text = serviceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                LoadingContent(modifier = Modifier.padding(padding))
            }
            error != null -> {
                ErrorContent(
                    message = error,
                    onRetry = onRefresh,
                    modifier = Modifier.padding(padding)
                )
            }
            activities.isEmpty() -> {
                EmptyActivityContent(modifier = Modifier.padding(padding))
            }
            else -> {
                ActivityDashboardContent(
                    activities = filteredActivities,
                    allActivities = activities,
                    summary = summary,
                    selectedFilter = selectedFilter,
                    onFilterSelect = { selectedFilter = if (selectedFilter == it) null else it },
                    selectedDateRange = selectedDateRange,
                    onDateRangeSelect = { selectedDateRange = it },
                    onActivityClick = onActivityClick,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
private fun ActivityDashboardContent(
    activities: List<ServiceActivity>,
    allActivities: List<ServiceActivity>,
    summary: ActivitySummary?,
    selectedFilter: ServiceActivityType?,
    onFilterSelect: (ServiceActivityType) -> Unit,
    selectedDateRange: DateRange,
    onDateRangeSelect: (DateRange) -> Unit,
    onActivityClick: (ServiceActivity) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Summary stats
        summary?.let {
            item {
                ActivitySummaryCard(summary = it)
            }
        }

        // Activity type filters
        item {
            ActivityTypeFilterRow(
                allActivities = allActivities,
                selectedFilter = selectedFilter,
                onFilterSelect = onFilterSelect
            )
        }

        // Date range filter
        item {
            DateRangeFilterRow(
                selectedRange = selectedDateRange,
                onRangeSelect = onDateRangeSelect
            )
        }

        // Activity timeline
        item {
            Text(
                text = "${activities.size} activities",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Group by date
        val groupedActivities = activities.groupBy { activity ->
            activity.timestamp.atZone(ZoneId.systemDefault()).toLocalDate()
        }.toSortedMap(reverseOrder())

        groupedActivities.forEach { (date, dateActivities) ->
            item {
                Text(
                    text = formatDateHeader(date),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(dateActivities) { activity ->
                ActivityCard(
                    activity = activity,
                    onClick = { onActivityClick(activity) }
                )
            }
        }
    }
}

// MARK: - Activity Summary Card

@Composable
private fun ActivitySummaryCard(summary: ActivitySummary) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Activity Overview",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SummaryStatItem(
                    icon = Icons.Outlined.Description,
                    value = "${summary.totalDataRequests}",
                    label = "Data Requests",
                    color = Color(0xFF2196F3)
                )
                SummaryStatItem(
                    icon = Icons.Outlined.Save,
                    value = "${summary.totalDataStored}",
                    label = "Data Stored",
                    color = Color(0xFF4CAF50)
                )
                SummaryStatItem(
                    icon = Icons.Outlined.VpnKey,
                    value = "${summary.totalAuthRequests}",
                    label = "Auth Requests",
                    color = Color(0xFF9C27B0)
                )
                SummaryStatItem(
                    icon = Icons.Outlined.Payment,
                    value = "${summary.totalPayments}",
                    label = "Payments",
                    color = Color(0xFFFF9800)
                )
            }

            summary.totalPaymentAmount?.let { amount ->
                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Total Payments",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = amount.formatted(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "This month: ${summary.activityThisMonth} activities",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                summary.lastActivityAt?.let {
                    Text(
                        text = "Last: ${formatRelativeTime(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryStatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = color.copy(alpha = 0.1f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = color
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// MARK: - Activity Type Filter Row

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityTypeFilterRow(
    allActivities: List<ServiceActivity>,
    selectedFilter: ServiceActivityType?,
    onFilterSelect: (ServiceActivityType) -> Unit
) {
    val typeCounts = allActivities.groupBy { it.activityType }
        .mapValues { it.value.size }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(ServiceActivityType.entries) { type ->
            val count = typeCounts[type] ?: 0
            if (count > 0) {
                FilterChip(
                    selected = selectedFilter == type,
                    onClick = { onFilterSelect(type) },
                    label = {
                        Text("${type.displayName} ($count)")
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = getActivityTypeIcon(type),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }
    }
}

// MARK: - Date Range Filter Row

enum class DateRange(val displayName: String) {
    TODAY("Today"),
    WEEK("This Week"),
    MONTH("This Month"),
    THREE_MONTHS("3 Months"),
    YEAR("This Year"),
    ALL_TIME("All Time")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeFilterRow(
    selectedRange: DateRange,
    onRangeSelect: (DateRange) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(DateRange.entries) { range ->
            FilterChip(
                selected = selectedRange == range,
                onClick = { onRangeSelect(range) },
                label = { Text(range.displayName) }
            )
        }
    }
}

private fun matchesDateRange(timestamp: Instant, range: DateRange): Boolean {
    val now = Instant.now()
    return when (range) {
        DateRange.TODAY -> ChronoUnit.DAYS.between(timestamp, now) < 1
        DateRange.WEEK -> ChronoUnit.DAYS.between(timestamp, now) < 7
        DateRange.MONTH -> ChronoUnit.DAYS.between(timestamp, now) < 30
        DateRange.THREE_MONTHS -> ChronoUnit.DAYS.between(timestamp, now) < 90
        DateRange.YEAR -> ChronoUnit.DAYS.between(timestamp, now) < 365
        DateRange.ALL_TIME -> true
    }
}

// MARK: - Activity Card

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityCard(
    activity: ServiceActivity,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Activity type icon
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = getActivityTypeColor(activity.activityType).copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = getActivityTypeIcon(activity.activityType),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = getActivityTypeColor(activity.activityType)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = activity.activityType.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = getActivityTypeColor(activity.activityType)
                    )
                    Text(
                        text = formatTime(activity.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = activity.description,
                    style = MaterialTheme.typography.bodyMedium
                )

                // Fields involved
                if (activity.fields.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(activity.fields.take(4)) { field ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = field,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (activity.fields.size > 4) {
                            item {
                                Text(
                                    text = "+${activity.fields.size - 4}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Payment amount
                activity.amount?.let { amount ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = amount.formatted(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Status badge
                Spacer(modifier = Modifier.height(4.dp))
                StatusBadge(status = activity.status)
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val (color, icon) = when (status.lowercase()) {
        "approved", "completed", "success" -> Color(0xFF4CAF50) to Icons.Default.CheckCircle
        "denied", "failed", "error" -> Color(0xFFF44336) to Icons.Default.Cancel
        "pending" -> Color(0xFFFF9800) to Icons.Default.Schedule
        else -> MaterialTheme.colorScheme.onSurfaceVariant to Icons.Default.Info
    }

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = color
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = status.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

// MARK: - State Composables

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
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
                imageVector = Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
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

@Composable
private fun EmptyActivityContent(modifier: Modifier = Modifier) {
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
                imageVector = Icons.Outlined.Timeline,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No Activity Yet",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Activity from this service will appear here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// MARK: - Utility Functions

private fun getActivityTypeIcon(type: ServiceActivityType) = when (type) {
    ServiceActivityType.DATA_REQUEST -> Icons.Outlined.Description
    ServiceActivityType.DATA_STORE -> Icons.Outlined.Save
    ServiceActivityType.DATA_UPDATE -> Icons.Outlined.Edit
    ServiceActivityType.DATA_DELETE -> Icons.Outlined.Delete
    ServiceActivityType.AUTH_REQUEST -> Icons.Outlined.VpnKey
    ServiceActivityType.PAYMENT_REQUEST -> Icons.Outlined.Payment
    ServiceActivityType.MESSAGE -> Icons.Outlined.Message
}

private fun getActivityTypeColor(type: ServiceActivityType) = when (type) {
    ServiceActivityType.DATA_REQUEST -> Color(0xFF2196F3)
    ServiceActivityType.DATA_STORE -> Color(0xFF4CAF50)
    ServiceActivityType.DATA_UPDATE -> Color(0xFFFF9800)
    ServiceActivityType.DATA_DELETE -> Color(0xFFF44336)
    ServiceActivityType.AUTH_REQUEST -> Color(0xFF9C27B0)
    ServiceActivityType.PAYMENT_REQUEST -> Color(0xFF009688)
    ServiceActivityType.MESSAGE -> Color(0xFF607D8B)
}

private fun formatDateHeader(date: java.time.LocalDate): String {
    val today = java.time.LocalDate.now()
    val yesterday = today.minusDays(1)

    return when (date) {
        today -> "Today"
        yesterday -> "Yesterday"
        else -> {
            val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")
            date.format(formatter)
        }
    }
}

private fun formatTime(instant: Instant): String {
    val formatter = DateTimeFormatter.ofPattern("h:mm a")
        .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}

private fun formatRelativeTime(instant: Instant): String {
    val now = Instant.now()
    val minutes = ChronoUnit.MINUTES.between(instant, now)
    val hours = ChronoUnit.HOURS.between(instant, now)
    val days = ChronoUnit.DAYS.between(instant, now)

    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> {
            val formatter = DateTimeFormatter.ofPattern("MMM d")
                .withZone(ZoneId.systemDefault())
            formatter.format(instant)
        }
    }
}
