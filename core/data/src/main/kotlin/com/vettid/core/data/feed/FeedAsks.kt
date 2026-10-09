package com.vettid.core.data.feed

/**
 * The feed item of an ask (ANDROID-PLAN 0.1.23, §9 question 2): deciding it in Approvals marks its item read, matched
 * by kind and `ref` (VAULT-MESSAGING §10.9 names each kind's `ref`).
 */
object FeedAsks {
    /** The kinds and `ref` of the feed item for the Approvals entry [key] (`Approval.key`); null for one without an item. */
    fun of(key: String): Pair<Set<String>, String>? {
        val type = key.substringBefore(':')
        val rest = key.substringAfter(':', "")
        if (rest.isEmpty()) return null
        return when (type) {
            "connection" -> setOf("connection.request") to rest
            "auth" -> setOf("connection.authenticate.requested") to rest
            "grant" -> setOf("grant.request") to rest
            "critical" -> setOf("critical-secret.use.request") to rest
            "share" -> setOf("share.pending") to rest
            // `device:<type>:<id>`, the type being the feed kind (`approval.pending`, `device.session.pending`).
            "device" -> rest.substringBeforeLast(':', "").takeIf { it in DEVICE_KINDS }?.let { setOf(it) to rest.substringAfterLast(':') }
            else -> null
        }
    }

    /** The approval key of the Approvals entry a feed item of [kind] with [ref] stands for; null for other kinds. */
    fun approvalKey(kind: String, ref: String?): String? {
        if (ref.isNullOrEmpty()) return null
        return when (kind) {
            "connection.request" -> "connection:$ref"
            "connection.authenticate.requested" -> "auth:$ref"
            "grant.request" -> "grant:$ref"
            "critical-secret.use.request" -> "critical:$ref"
            "share.pending" -> "share:$ref"
            in DEVICE_KINDS -> "device:$kind:$ref"
            else -> null
        }
    }

    /** `message.received` items: read when their conversation opens (0.1.23). */
    const val MESSAGE_RECEIVED = "message.received"

    private val DEVICE_KINDS = setOf("approval.pending", "device.session.pending")
}
