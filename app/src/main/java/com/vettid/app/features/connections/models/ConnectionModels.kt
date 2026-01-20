package com.vettid.app.features.connections.models

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Enhanced connection models for usability features.
 */

// MARK: - Connection Status

/**
 * Extended connection status for bidirectional consent flow.
 */
enum class ConnectionStatus {
    PENDING_THEIR_REVIEW,   // Waiting for peer to review our profile
    PENDING_OUR_REVIEW,     // We need to review their profile
    PENDING_THEIR_ACCEPT,   // They reviewed, waiting for their accept
    PENDING_OUR_ACCEPT,     // We reviewed, need to accept
    ACTIVE,                 // Both accepted, connection established
    REVOKED,                // Connection terminated
    EXPIRED,                // Invitation expired
    BLOCKED;                // Peer blocked

    val isPending: Boolean
        get() = this in listOf(
            PENDING_THEIR_REVIEW,
            PENDING_OUR_REVIEW,
            PENDING_THEIR_ACCEPT,
            PENDING_OUR_ACCEPT
        )

    val displayName: String
        get() = when (this) {
            PENDING_THEIR_REVIEW -> "Awaiting their review"
            PENDING_OUR_REVIEW -> "Awaiting your review"
            PENDING_THEIR_ACCEPT -> "Awaiting their acceptance"
            PENDING_OUR_ACCEPT -> "Awaiting your acceptance"
            ACTIVE -> "Active"
            REVOKED -> "Revoked"
            EXPIRED -> "Expired"
            BLOCKED -> "Blocked"
        }
}

// MARK: - Sort Order

enum class SortOrder {
    RECENT_ACTIVITY,
    ALPHABETICAL,
    CONNECTION_DATE;

    val displayName: String
        get() = when (this) {
            RECENT_ACTIVITY -> "Recent Activity"
            ALPHABETICAL -> "Alphabetical"
            CONNECTION_DATE -> "Connection Date"
        }
}

// MARK: - Trust Level

enum class TrustLevel {
    NEW,
    ESTABLISHED,
    TRUSTED,
    VERIFIED;

    val displayName: String
        get() = when (this) {
            NEW -> "New"
            ESTABLISHED -> "Established"
            TRUSTED -> "Trusted"
            VERIFIED -> "Verified"
        }
}

// MARK: - Connection List Item

/**
 * Enhanced connection item for list display with all usability features.
 */
data class ConnectionListItem(
    val connectionId: String,
    val peerGuid: String,
    val peerName: String,
    val peerEmail: String? = null,
    val peerAvatarUrl: String? = null,
    val status: ConnectionStatus,
    val direction: ConnectionDirection,

    // Activity tracking
    val lastActiveAt: Instant? = null,
    val unreadCount: Int = 0,
    val hasUnreadActivity: Boolean = false,

    // Organization
    val tags: List<ConnectionTag> = emptyList(),
    val isFavorite: Boolean = false,
    val isArchived: Boolean = false,

    // Trust & verification
    val trustLevel: TrustLevel = TrustLevel.NEW,
    val emailVerified: Boolean = false,
    val identityVerified: Boolean = false,

    // Health indicators
    val credentialsExpireAt: Instant? = null,
    val profileVersion: Int = 1,
    val cachedProfileVersion: Int = 1,

    // Timestamps
    val createdAt: Instant,
    val acceptedAt: Instant? = null
) {
    val needsProfileSync: Boolean
        get() = profileVersion > cachedProfileVersion

    val credentialsExpiringSoon: Boolean
        get() = credentialsExpireAt?.let {
            it.isBefore(Instant.now().plus(7, ChronoUnit.DAYS))
        } ?: false

    val isStale: Boolean
        get() = lastActiveAt?.let {
            it.isBefore(Instant.now().minus(30, ChronoUnit.DAYS))
        } ?: true

    val connectionAgeText: String
        get() {
            val months = ChronoUnit.MONTHS.between(createdAt, Instant.now())
            return when {
                months >= 24 -> "Connected for ${months / 12} years"
                months >= 12 -> "Connected for 1 year"
                months >= 1 -> "Connected for $months months"
                else -> {
                    val days = ChronoUnit.DAYS.between(createdAt, Instant.now())
                    when {
                        days >= 7 -> "Connected for ${days / 7} weeks"
                        days >= 1 -> "Connected for $days days"
                        else -> "Connected today"
                    }
                }
            }
        }

    val lastActiveText: String
        get() = lastActiveAt?.let { formatRelativeTime(it) } ?: "Never"

    companion object {
        fun calculateTrustLevel(
            createdAt: Instant,
            emailVerified: Boolean,
            identityVerified: Boolean,
            activityCount: Int
        ): TrustLevel {
            val ageMonths = ChronoUnit.MONTHS.between(createdAt, Instant.now())

            return when {
                identityVerified && ageMonths >= 12 -> TrustLevel.VERIFIED
                emailVerified && ageMonths >= 6 && activityCount >= 10 -> TrustLevel.TRUSTED
                ageMonths >= 1 || activityCount >= 3 -> TrustLevel.ESTABLISHED
                else -> TrustLevel.NEW
            }
        }
    }
}

enum class ConnectionDirection {
    OUTBOUND,  // We invited them
    INBOUND;   // They invited us

    val displayName: String
        get() = when (this) {
            OUTBOUND -> "You invited"
            INBOUND -> "Invited you"
        }
}

// MARK: - Connection Tag

data class ConnectionTag(
    val id: String,
    val name: String,
    val color: Long  // ARGB color
) {
    companion object {
        val FAMILY = ConnectionTag("family", "Family", 0xFF4CAF50)
        val WORK = ConnectionTag("work", "Work", 0xFF2196F3)
        val FRIENDS = ConnectionTag("friends", "Friends", 0xFFFF9800)
        val MERCHANTS = ConnectionTag("merchants", "Merchants", 0xFF9C27B0)

        val DEFAULTS = listOf(FAMILY, WORK, FRIENDS, MERCHANTS)
    }
}

// MARK: - Connection Filter

data class ConnectionFilter(
    val searchQuery: String = "",
    val tags: Set<String> = emptySet(),
    val statuses: Set<ConnectionStatus> = emptySet(),
    val verificationLevel: TrustLevel? = null,
    val showArchived: Boolean = false,
    val favoritesOnly: Boolean = false,
    val sortOrder: SortOrder = SortOrder.RECENT_ACTIVITY
) {
    val hasActiveFilters: Boolean
        get() = searchQuery.isNotBlank() ||
                tags.isNotEmpty() ||
                statuses.isNotEmpty() ||
                verificationLevel != null ||
                showArchived ||
                favoritesOnly

    fun matches(connection: ConnectionListItem): Boolean {
        // Search query
        if (searchQuery.isNotBlank()) {
            val query = searchQuery.lowercase()
            val matchesName = connection.peerName.lowercase().contains(query)
            val matchesEmail = connection.peerEmail?.lowercase()?.contains(query) == true
            val matchesTag = connection.tags.any { it.name.lowercase().contains(query) }
            if (!matchesName && !matchesEmail && !matchesTag) return false
        }

        // Tags filter
        if (tags.isNotEmpty()) {
            if (connection.tags.none { it.id in tags }) return false
        }

        // Status filter
        if (statuses.isNotEmpty()) {
            if (connection.status !in statuses) return false
        }

        // Verification level
        if (verificationLevel != null) {
            if (connection.trustLevel.ordinal < verificationLevel.ordinal) return false
        }

        // Archived
        if (!showArchived && connection.isArchived) return false

        // Favorites only
        if (favoritesOnly && !connection.isFavorite) return false

        return true
    }
}

// MARK: - Connection Health

data class ConnectionHealth(
    val lastActiveAt: Instant?,
    val credentialsExpireAt: Instant,
    val profileVersion: Int,
    val cachedProfileVersion: Int,
    val trustLevel: TrustLevel,
    val activityCount: Int
) {
    val needsProfileSync: Boolean
        get() = profileVersion > cachedProfileVersion

    val credentialsExpiringSoon: Boolean
        get() = credentialsExpireAt.isBefore(Instant.now().plus(7, ChronoUnit.DAYS))

    val isStale: Boolean
        get() = lastActiveAt?.isBefore(Instant.now().minus(30, ChronoUnit.DAYS)) ?: true

    val healthStatus: HealthStatus
        get() = when {
            credentialsExpiringSoon -> HealthStatus.WARNING
            needsProfileSync -> HealthStatus.INFO
            isStale -> HealthStatus.STALE
            else -> HealthStatus.GOOD
        }
}

enum class HealthStatus {
    GOOD,
    INFO,
    WARNING,
    STALE
}

// MARK: - Utility Functions

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
        days < 30 -> "${days / 7}w ago"
        else -> "${days / 30}mo ago"
    }
}
