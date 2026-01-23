package com.vettid.app.features.services.models

import java.time.Instant

/**
 * Service in the public directory (not yet connected).
 *
 * Issue #33 [AND-010] - Service directory browser.
 */
data class DirectoryService(
    val serviceId: String,
    val name: String,
    val description: String,
    val logoUrl: String? = null,
    val category: ServiceCategory,
    val organization: DirectoryOrganization,
    val offerings: List<ServiceOffering> = emptyList(),
    val attestations: List<ServiceAttestation> = emptyList(),
    val rating: ServiceRating? = null,
    val connectionCount: Int = 0,
    val domainVerified: Boolean = false,
    val featured: Boolean = false,
    val tags: List<String> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * Organization info in directory listing.
 */
data class DirectoryOrganization(
    val name: String,
    val verified: Boolean,
    val verificationType: VerificationType? = null,
    val country: String? = null,
    val website: String? = null
)

/**
 * Service offering/plan.
 *
 * Issue #34 [AND-011] - Service detail view.
 */
data class ServiceOffering(
    val id: String,
    val name: String,
    val description: String,
    val price: OfferingPrice? = null,
    val capabilities: List<OfferingCapability> = emptyList(),
    val dataRequirements: DataRequirements,
    val recommended: Boolean = false,
    val popular: Boolean = false
)

/**
 * Offering price information.
 */
data class OfferingPrice(
    val type: PriceType,
    val amount: Money? = null,
    val period: BillingPeriod? = null,
    val description: String? = null
)

enum class PriceType {
    FREE,
    ONE_TIME,
    SUBSCRIPTION,
    USAGE_BASED
}

enum class BillingPeriod(val displayName: String) {
    MONTHLY("month"),
    QUARTERLY("quarter"),
    YEARLY("year")
}

/**
 * Capability included in an offering.
 */
data class OfferingCapability(
    val id: String,
    val name: String,
    val description: String,
    val category: CapabilityCategory
)

enum class CapabilityCategory(val displayName: String) {
    IDENTITY("Identity"),
    DATA("Data Access"),
    COMMUNICATION("Communication"),
    FINANCIAL("Financial")
}

/**
 * Data requirements for an offering.
 */
data class DataRequirements(
    val required: List<DataField> = emptyList(),
    val optional: List<DataField> = emptyList(),
    val onDemand: List<String> = emptyList()
)

data class DataField(
    val field: String,
    val purpose: String,
    val retention: String
)

/**
 * Service attestation/certification.
 */
data class ServiceAttestation(
    val id: String,
    val type: AttestationType,
    val name: String,
    val issuedBy: String,
    val issuedAt: Instant,
    val expiresAt: Instant? = null,
    val verificationUrl: String? = null
)

enum class AttestationType(val displayName: String, val icon: String) {
    SOC2("SOC 2", "security"),
    ISO27001("ISO 27001", "verified_user"),
    GDPR("GDPR Compliant", "gavel"),
    HIPAA("HIPAA Compliant", "local_hospital"),
    PCI_DSS("PCI DSS", "credit_card"),
    DOMAIN_VERIFIED("Domain Verified", "language"),
    BUSINESS_VERIFIED("Business Verified", "business")
}

/**
 * Service rating/reviews summary.
 */
data class ServiceRating(
    val average: Float,
    val count: Int,
    val distribution: Map<Int, Int> = emptyMap() // 1-5 stars -> count
)

/**
 * Data access request from a service.
 *
 * Issue #35 [AND-022] - Data request prompt.
 */
data class DataAccessRequest(
    val requestId: String,
    val serviceId: String,
    val serviceName: String,
    val serviceLogoUrl: String?,
    val dataType: DataType,
    val purpose: String,
    val retention: String,
    val urgency: RequestUrgency = RequestUrgency.NORMAL,
    val expiresAt: Instant,
    val createdAt: Instant
)

enum class DataType(val displayName: String, val icon: String) {
    FULL_NAME("Full Name", "person"),
    EMAIL("Email Address", "email"),
    PHONE("Phone Number", "phone"),
    ADDRESS("Physical Address", "location_on"),
    DATE_OF_BIRTH("Date of Birth", "cake"),
    GOVERNMENT_ID("Government ID", "badge"),
    PAYMENT_INFO("Payment Information", "credit_card"),
    HEALTH_DATA("Health Data", "favorite"),
    LOCATION("Location", "my_location"),
    PHOTO("Photo", "photo_camera"),
    CUSTOM("Custom Data", "description")
}

enum class RequestUrgency {
    LOW,
    NORMAL,
    HIGH
}

/**
 * Service notification.
 *
 * Issue #36 [AND-023] - Notification display.
 */
data class ServiceNotification(
    val id: String,
    val serviceId: String,
    val serviceName: String,
    val serviceLogoUrl: String?,
    val type: NotificationType,
    val title: String,
    val body: String,
    val priority: NotificationPriority = NotificationPriority.DEFAULT,
    val actionUrl: String? = null,
    val actionType: NotificationActionType? = null,
    val isRead: Boolean = false,
    val createdAt: Instant
) {
    val timeAgo: String
        get() {
            val now = Instant.now()
            val diff = now.toEpochMilli() - createdAt.toEpochMilli()
            val minutes = diff / (60 * 1000)
            val hours = diff / (60 * 60 * 1000)
            val days = diff / (24 * 60 * 60 * 1000)

            return when {
                minutes < 1 -> "now"
                minutes < 60 -> "${minutes}m"
                hours < 24 -> "${hours}h"
                days < 7 -> "${days}d"
                else -> "${days / 7}w"
            }
        }
}

enum class NotificationType {
    INFO,
    AUTH_REQUEST,
    DATA_REQUEST,
    PAYMENT_REQUEST,
    CONTRACT_UPDATE,
    MESSAGE,
    ALERT
}

enum class NotificationPriority(val androidPriority: Int) {
    LOW(android.app.NotificationManager.IMPORTANCE_LOW),
    DEFAULT(android.app.NotificationManager.IMPORTANCE_DEFAULT),
    HIGH(android.app.NotificationManager.IMPORTANCE_HIGH)
}

enum class NotificationActionType {
    OPEN_SERVICE,
    APPROVE_REQUEST,
    VIEW_CONTRACT,
    VIEW_MESSAGE
}

/**
 * Granted capability in a contract.
 *
 * Issue #41 [AND-032] - Capability Management UI.
 */
data class GrantedCapability(
    val id: String,
    val name: String,
    val description: String,
    val category: CapabilityCategory,
    val enabled: Boolean,
    val required: Boolean,
    val lastUsed: Instant? = null,
    val usageCount: Int = 0
)

/**
 * Activity event for history view.
 *
 * Issue #42 [AND-050] - Activity History View.
 */
sealed class ActivityEvent {
    abstract val id: String
    abstract val timestamp: Instant
    abstract val serviceId: String
    abstract val serviceName: String
    abstract val serviceLogoUrl: String?

    data class Authentication(
        override val id: String,
        override val timestamp: Instant,
        override val serviceId: String,
        override val serviceName: String,
        override val serviceLogoUrl: String?,
        val success: Boolean,
        val method: String,
        val context: String? = null
    ) : ActivityEvent()

    data class DataRequest(
        override val id: String,
        override val timestamp: Instant,
        override val serviceId: String,
        override val serviceName: String,
        override val serviceLogoUrl: String?,
        val dataType: String,
        val approved: Boolean,
        val sharedOnce: Boolean = false
    ) : ActivityEvent()

    data class Payment(
        override val id: String,
        override val timestamp: Instant,
        override val serviceId: String,
        override val serviceName: String,
        override val serviceLogoUrl: String?,
        val amount: Money,
        val status: PaymentStatus,
        val description: String? = null
    ) : ActivityEvent()

    data class Notification(
        override val id: String,
        override val timestamp: Instant,
        override val serviceId: String,
        override val serviceName: String,
        override val serviceLogoUrl: String?,
        val title: String,
        val read: Boolean
    ) : ActivityEvent()

    data class ContractChange(
        override val id: String,
        override val timestamp: Instant,
        override val serviceId: String,
        override val serviceName: String,
        override val serviceLogoUrl: String?,
        val changeType: ContractChangeType,
        val fromVersion: Int? = null,
        val toVersion: Int? = null
    ) : ActivityEvent()
}

enum class PaymentStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REFUNDED
}

enum class ContractChangeType(val displayName: String) {
    SIGNED("Contract Signed"),
    UPDATED("Contract Updated"),
    CANCELLED("Contract Cancelled")
}

/**
 * Audit log entry.
 *
 * Issue #43 [AND-051] - Audit Log Viewer.
 */
data class AuditLogEntry(
    val id: String,
    val timestamp: Instant,
    val eventType: AuditEventType,
    val details: Map<String, String>,
    val serviceId: String?,
    val serviceName: String?,
    val result: AuditResult,
    val hash: String? = null // For tamper-evident chain
)

enum class AuditEventType(val displayName: String, val category: AuditCategory) {
    // Key operations
    KEY_GENERATED("Key Generated", AuditCategory.KEYS),
    KEY_ROTATED("Key Rotated", AuditCategory.KEYS),
    KEY_USED_FOR_SIGNING("Key Used for Signing", AuditCategory.KEYS),
    KEY_USED_FOR_ENCRYPTION("Key Used for Encryption", AuditCategory.KEYS),

    // Contract operations
    CONTRACT_SIGNED("Contract Signed", AuditCategory.CONTRACTS),
    CONTRACT_UPDATED("Contract Updated", AuditCategory.CONTRACTS),
    CONTRACT_CANCELLED("Contract Cancelled", AuditCategory.CONTRACTS),

    // Authentication
    AUTHENTICATION_REQUESTED("Auth Requested", AuditCategory.AUTH),
    AUTHENTICATION_COMPLETED("Auth Completed", AuditCategory.AUTH),
    AUTHENTICATION_FAILED("Auth Failed", AuditCategory.AUTH),

    // Data operations
    DATA_REQUEST_RECEIVED("Data Request Received", AuditCategory.DATA),
    DATA_REQUEST_APPROVED("Data Request Approved", AuditCategory.DATA),
    DATA_REQUEST_DENIED("Data Request Denied", AuditCategory.DATA),
    DATA_SENT("Data Sent", AuditCategory.DATA),

    // Security events
    PIN_VERIFIED("PIN Verified", AuditCategory.SECURITY),
    PIN_FAILED("PIN Failed", AuditCategory.SECURITY),
    BIOMETRIC_VERIFIED("Biometric Verified", AuditCategory.SECURITY),
    BIOMETRIC_FAILED("Biometric Failed", AuditCategory.SECURITY),
    SESSION_STARTED("Session Started", AuditCategory.SECURITY),
    SESSION_ENDED("Session Ended", AuditCategory.SECURITY)
}

enum class AuditCategory(val displayName: String) {
    KEYS("Key Operations"),
    CONTRACTS("Contracts"),
    AUTH("Authentication"),
    DATA("Data Access"),
    SECURITY("Security")
}

enum class AuditResult {
    SUCCESS,
    FAILURE,
    PENDING
}

/**
 * Cancellation request for a contract.
 *
 * Issue #40 [AND-031] - Contract Cancellation Flow.
 */
data class CancellationRequest(
    val contractId: String,
    val reason: CancellationReason?,
    val effectiveDate: Instant,
    val deleteData: Boolean,
    val feedback: String?
)

enum class CancellationReason(val displayName: String) {
    NO_LONGER_NEEDED("No longer needed"),
    PRIVACY_CONCERNS("Privacy concerns"),
    SWITCHING_SERVICE("Switching to another service"),
    POOR_EXPERIENCE("Poor experience"),
    OTHER("Other")
}

// ============================================================================
// Payment Models - Issue #37 [AND-024] Payment request prompt
// ============================================================================

/**
 * Payment request from a service.
 */
data class PaymentRequest(
    val requestId: String,
    val serviceId: String,
    val serviceName: String,
    val serviceLogoUrl: String?,
    val amount: Money,
    val description: String,
    val reference: String? = null,
    val subscriptionDetails: SubscriptionDetails? = null,
    val expiresAt: Instant,
    val createdAt: Instant
) {
    val formattedAmount: String
        get() = amount.formatted()
}

/**
 * Subscription details for recurring payments.
 */
data class SubscriptionDetails(
    val billingCycle: BillingPeriod,
    val autoRenew: Boolean = true,
    val trialDays: Int = 0,
    val nextBillingDate: Instant? = null,
    val cancellationPolicy: String? = null
)

/**
 * Payment method available to user.
 */
data class PaymentMethod(
    val id: String,
    val type: PaymentMethodType,
    val displayName: String,
    val lastFour: String? = null,
    val expiryMonth: Int? = null,
    val expiryYear: Int? = null,
    val network: CardNetwork? = null,
    val cryptoAddress: String? = null,
    val cryptoCurrency: CryptoCurrency? = null,
    val isDefault: Boolean = false
) {
    val maskedNumber: String?
        get() = lastFour?.let { "•••• $it" }

    val expiryDisplay: String?
        get() = if (expiryMonth != null && expiryYear != null) {
            String.format("%02d/%02d", expiryMonth, expiryYear % 100)
        } else null
}

enum class PaymentMethodType(val displayName: String) {
    CARD("Card"),
    BANK_ACCOUNT("Bank Account"),
    CRYPTO("Cryptocurrency"),
    APPLE_PAY("Apple Pay"),
    GOOGLE_PAY("Google Pay")
}

enum class CardNetwork(val displayName: String) {
    VISA("Visa"),
    MASTERCARD("Mastercard"),
    AMEX("American Express"),
    DISCOVER("Discover")
}

enum class CryptoCurrency(val symbol: String, val displayName: String) {
    BTC("BTC", "Bitcoin"),
    ETH("ETH", "Ethereum"),
    USDC("USDC", "USD Coin"),
    USDT("USDT", "Tether")
}

/**
 * Result of a payment attempt.
 */
data class PaymentResult(
    val requestId: String,
    val status: PaymentResultStatus,
    val transactionId: String? = null,
    val errorMessage: String? = null,
    val confirmedAt: Instant? = null
)

enum class PaymentResultStatus {
    SUCCESS,
    PENDING,
    FAILED,
    CANCELLED,
    REQUIRES_ACTION
}

// ============================================================================
// Call Models - Issue #38 [AND-025] Call UI (voice/video)
// ============================================================================

/**
 * Incoming call from a service.
 */
data class IncomingServiceCall(
    val callId: String,
    val serviceId: String,
    val serviceName: String,
    val serviceLogoUrl: String?,
    val serviceVerified: Boolean = false,
    val callType: CallType,
    val purpose: String,
    val agentInfo: AgentInfo? = null,
    val createdAt: Instant
)

/**
 * Type of call.
 */
enum class CallType(val displayName: String) {
    VOICE("Voice Call"),
    VIDEO("Video Call")
}

/**
 * Information about the service agent making the call.
 */
data class AgentInfo(
    val id: String,
    val name: String,
    val role: String,
    val avatarUrl: String? = null
)

/**
 * State of an active call.
 */
data class ServiceCallState(
    val callId: String,
    val serviceId: String,
    val serviceName: String,
    val serviceLogoUrl: String?,
    val serviceVerified: Boolean = false,
    val callType: CallType,
    val status: CallStatus,
    val isMuted: Boolean = false,
    val isVideoEnabled: Boolean = true,
    val isSpeakerOn: Boolean = false,
    val duration: Long = 0, // in seconds
    val agentInfo: AgentInfo? = null,
    val quality: CallQuality = CallQuality.GOOD
) {
    val durationFormatted: String
        get() {
            val minutes = duration / 60
            val seconds = duration % 60
            return String.format("%02d:%02d", minutes, seconds)
        }
}

enum class CallStatus {
    CONNECTING,
    RINGING,
    CONNECTED,
    ON_HOLD,
    RECONNECTING,
    ENDED
}

enum class CallQuality {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR
}
