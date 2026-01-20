package com.vettid.app.features.services.models

import java.time.Instant

/**
 * Service profile - rich profile for businesses/services.
 */
data class ServiceProfile(
    val serviceGuid: String,
    val serviceName: String,
    val serviceDescription: String,
    val serviceLogoUrl: String? = null,
    val serviceCategory: ServiceCategory,
    val organization: OrganizationInfo,
    val contactInfo: ServiceContactInfo,
    val trustedResources: List<TrustedResource> = emptyList(),
    val currentContract: ServiceDataContract,
    val profileVersion: Int,
    val updatedAt: Instant
)

enum class ServiceCategory(val displayName: String) {
    RETAIL("Retail"),
    HEALTHCARE("Healthcare"),
    FINANCE("Finance"),
    GOVERNMENT("Government"),
    EDUCATION("Education"),
    ENTERTAINMENT("Entertainment"),
    TRAVEL("Travel"),
    FOOD_DELIVERY("Food & Delivery"),
    UTILITIES("Utilities"),
    INSURANCE("Insurance"),
    OTHER("Other")
}

data class OrganizationInfo(
    val name: String,
    val verified: Boolean,
    val verificationType: VerificationType? = null,
    val verifiedAt: Instant? = null,
    val registrationId: String? = null,
    val country: String? = null
)

enum class VerificationType(val displayName: String, val badgeText: String) {
    BUSINESS("Business", "Verified Business"),
    NONPROFIT("Nonprofit", "Verified Nonprofit"),
    GOVERNMENT("Government", "Verified Government")
}

data class ServiceContactInfo(
    val emails: List<VerifiedContact> = emptyList(),
    val phoneNumbers: List<VerifiedContact> = emptyList(),
    val address: PhysicalAddress? = null,
    val supportUrl: String? = null,
    val supportEmail: String? = null,
    val supportPhone: String? = null
)

data class VerifiedContact(
    val value: String,
    val label: String,
    val verified: Boolean,
    val verifiedAt: Instant? = null,
    val primary: Boolean = false
)

data class PhysicalAddress(
    val street: String,
    val city: String,
    val state: String? = null,
    val postalCode: String,
    val country: String
)

/**
 * Trusted resources - verified URLs and signed downloads.
 */
data class TrustedResource(
    val resourceId: String,
    val type: ResourceType,
    val label: String,
    val description: String? = null,
    val url: String,
    val download: DownloadInfo? = null,
    val addedAt: Instant,
    val updatedAt: Instant
)

enum class ResourceType(val displayName: String, val icon: String) {
    WEBSITE("Website", "language"),
    APP_DOWNLOAD("App Download", "download"),
    DOCUMENT("Document", "description"),
    API("API", "api")
}

data class DownloadInfo(
    val platform: Platform,
    val version: String,
    val versionCode: Int? = null,
    val minOsVersion: String? = null,
    val fileSize: Long,
    val fileName: String,
    val signatures: List<DownloadSignature>
)

enum class Platform {
    ANDROID,
    IOS,
    WINDOWS,
    MACOS,
    LINUX
}

data class DownloadSignature(
    val algorithm: String,  // "sha256", "sha512"
    val hash: String,
    val signedBy: String,
    val signature: String  // Ed25519 signature
)

/**
 * Service data contract - defines what the service can access.
 */
data class ServiceDataContract(
    val contractId: String,
    val serviceGuid: String,
    val version: Int,
    val title: String,
    val description: String,
    val termsUrl: String? = null,
    val privacyUrl: String? = null,
    val requiredFields: List<FieldSpec> = emptyList(),
    val optionalFields: List<FieldSpec> = emptyList(),
    val onDemandFields: List<String> = emptyList(),
    val consentFields: List<String> = emptyList(),
    val canStoreData: Boolean = false,
    val storageCategories: List<String> = emptyList(),
    val canSendMessages: Boolean = false,
    val canRequestAuth: Boolean = false,
    val canRequestPayment: Boolean = false,
    val maxRequestsPerHour: Int? = null,
    val maxStorageMb: Int? = null,
    val createdAt: Instant,
    val expiresAt: Instant? = null
)

data class FieldSpec(
    val field: String,
    val purpose: String,
    val retention: String  // "session", "until_revoked", "30_days", etc.
)

/**
 * Service connection record - stored in user's vault.
 */
data class ServiceConnectionRecord(
    val connectionId: String,
    val serviceGuid: String,
    val serviceProfile: ServiceProfile,
    val contractId: String,
    val contractVersion: Int,
    val contractAcceptedAt: Instant,
    val pendingContractVersion: Int? = null,
    val status: ServiceConnectionStatus,
    val createdAt: Instant,
    val lastActiveAt: Instant? = null,
    // Usability fields
    val tags: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    val isArchived: Boolean = false,
    val isMuted: Boolean = false
)

enum class ServiceConnectionStatus {
    ACTIVE,
    PENDING_CONTRACT_UPDATE,
    SUSPENDED,
    REVOKED
}

/**
 * Contract update notification.
 */
data class ContractUpdate(
    val previousVersion: Int,
    val newVersion: Int,
    val changes: ContractChanges,
    val reason: String,
    val publishedAt: Instant,
    val requiredBy: Instant? = null
)

data class ContractChanges(
    val addedFields: List<FieldSpec> = emptyList(),
    val removedFields: List<String> = emptyList(),
    val changedFields: List<FieldSpec> = emptyList(),
    val permissionChanges: List<String> = emptyList(),
    val rateLimitChanges: String? = null
)

/**
 * Service request from service to user.
 */
data class ServiceRequest(
    val requestId: String,
    val connectionId: String,
    val requestType: ServiceRequestType,
    val requestedFields: List<String> = emptyList(),
    val requestedAction: String? = null,
    val purpose: String? = null,
    val amount: Money? = null,
    val status: RequestStatus,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val respondedAt: Instant? = null
)

enum class ServiceRequestType {
    DATA,
    AUTH,
    CONSENT,
    PAYMENT
}

enum class RequestStatus {
    PENDING,
    APPROVED,
    DENIED,
    EXPIRED
}

data class Money(
    val amount: Long,  // In cents/smallest unit
    val currency: String
) {
    fun formatted(): String {
        val dollars = amount / 100.0
        return when (currency) {
            "USD" -> "$${String.format("%.2f", dollars)}"
            "EUR" -> "€${String.format("%.2f", dollars)}"
            "GBP" -> "£${String.format("%.2f", dollars)}"
            else -> "${String.format("%.2f", dollars)} $currency"
        }
    }
}

/**
 * Service storage record - data stored by service in user's sandbox.
 */
data class ServiceStorageRecord(
    val key: String,
    val connectionId: String,
    val category: String,
    val visibilityLevel: VisibilityLevel,
    val encryptedValue: ByteArray,
    val label: String? = null,
    val description: String? = null,
    val dataType: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val expiresAt: Instant? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ServiceStorageRecord
        return key == other.key && connectionId == other.connectionId
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + connectionId.hashCode()
        return result
    }
}

enum class VisibilityLevel {
    HIDDEN,     // User can see exists but not content
    METADATA,   // User can see label/description
    VIEWABLE    // User can see full content
}

/**
 * Service activity tracking.
 */
data class ServiceActivity(
    val activityId: String,
    val connectionId: String,
    val activityType: ServiceActivityType,
    val description: String,
    val fields: List<String> = emptyList(),
    val amount: Money? = null,
    val status: String,
    val timestamp: Instant
)

enum class ServiceActivityType(val displayName: String, val icon: String) {
    DATA_REQUEST("Data Request", "description"),
    DATA_STORE("Data Stored", "save"),
    DATA_UPDATE("Data Updated", "edit"),
    DATA_DELETE("Data Deleted", "delete"),
    AUTH_REQUEST("Auth Request", "security"),
    PAYMENT_REQUEST("Payment Request", "payment"),
    MESSAGE("Message", "message")
}

data class ActivitySummary(
    val connectionId: String,
    val totalDataRequests: Int,
    val totalDataStored: Int,
    val totalAuthRequests: Int,
    val totalPayments: Int,
    val totalPaymentAmount: Money?,
    val lastActivityAt: Instant?,
    val activityThisMonth: Int
)

/**
 * Service notification settings.
 */
data class ServiceNotificationSettings(
    val connectionId: String,
    val level: NotificationLevel,
    val allowDataRequests: Boolean = true,
    val allowAuthRequests: Boolean = true,
    val allowPaymentRequests: Boolean = true,
    val allowMessages: Boolean = true,
    val bypassQuietHours: Boolean = false
)

enum class NotificationLevel {
    ALL,
    IMPORTANT,
    MUTED
}

/**
 * Service data summary.
 */
data class ServiceDataSummary(
    val connectionId: String,
    val totalItems: Int,
    val totalSizeBytes: Long,
    val categories: Map<String, Int>,
    val oldestItem: Instant?,
    val newestItem: Instant?
)

/**
 * Offline service action.
 */
data class OfflineServiceAction(
    val actionId: String,
    val connectionId: String,
    val actionType: OfflineActionType,
    val payload: ByteArray,
    val createdAt: Instant,
    val syncStatus: SyncStatus,
    val syncedAt: Instant? = null,
    val error: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as OfflineServiceAction
        return actionId == other.actionId
    }

    override fun hashCode(): Int = actionId.hashCode()
}

enum class OfflineActionType {
    REQUEST_RESPONSE,
    REVOKE,
    CONTRACT_ACCEPT,
    CONTRACT_REJECT
}

enum class SyncStatus {
    PENDING,
    SYNCED,
    FAILED
}

/**
 * Trust indicators for a service.
 */
data class ServiceTrustIndicators(
    val organizationVerified: Boolean,
    val verificationType: VerificationType?,
    val connectionAge: Long,  // Days
    val totalInteractions: Int,
    val lastActivity: Instant?,
    val contractVersion: Int,
    val pendingContractUpdate: Boolean,
    val rateLimitViolations: Int,
    val contractViolations: Int,
    val hasExcessiveRequests: Boolean
)

/**
 * Connection health status.
 */
data class ServiceConnectionHealth(
    val connectionId: String,
    val status: HealthStatus,
    val lastActiveAt: Instant?,
    val contractStatus: ContractHealthStatus,
    val dataStorageUsed: Long,
    val dataStorageLimit: Long,
    val requestsThisHour: Int,
    val requestLimit: Int,
    val issues: List<String> = emptyList()
)

enum class HealthStatus(val displayName: String, val color: Long) {
    HEALTHY("Healthy", 0xFF4CAF50),
    WARNING("Warning", 0xFFFF9800),
    CRITICAL("Critical", 0xFFF44336)
}

enum class ContractHealthStatus {
    CURRENT,
    UPDATE_AVAILABLE,
    EXPIRED
}
