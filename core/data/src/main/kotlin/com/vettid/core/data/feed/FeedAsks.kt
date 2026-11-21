package com.vettid.core.data.feed

import com.vettid.core.data.social.AskBatching
import com.vettid.core.vault.FeedItem
import java.time.Duration
import java.time.Instant

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
            "location" -> setOf("location.request") to rest
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
            "location.request" -> "location:$ref"
            in DEVICE_KINDS -> "device:$kind:$ref"
            else -> null
        }
    }

    /** The kinds of the feed items of a connection's asks (a batch's item has the kind of its first ask). */
    val ASK_KINDS = setOf("connection.authenticate.requested", "grant.request", "critical-secret.use.request")

    /** `message.received` items: read when their conversation opens (0.1.23). */
    const val MESSAGE_RECEIVED = "message.received"

    private val DEVICE_KINDS = setOf("approval.pending", "device.session.pending")

    /**
     * The batch item of [listed] that stands for [b] ([FeedManager.askWithdrawn]): the ask-kind item of the connection whose
     * `ref` is one of the batch's asks; if the first ask of the batch is gone already, the newest unread batch item of the
     * connection that began within the window before the ask was created, by the vault's own times where the app has the
     * ask's (else the phone's).
     */
    fun batchItem(listed: Collection<FeedItem>, b: WithdrawnBatch): FeedItem? {
        val items = listed.filter { it.connectionId == b.connectionId && it.kind in ASK_KINDS }
        items.firstOrNull { approvalKey(it.kind, it.ref) in b.askKeys }?.let { return it }
        val at = b.askTs ?: b.receivedAt
        val from = at.minus(BATCH_WINDOW).minus(BATCH_SKEW)
        val to = at.plus(BATCH_SKEW)
        return items.filter { it.count != null && it.status == FeedManager.STATUS_ACTIVE }
            .mapNotNull { i -> runCatching { Instant.parse(i.at) }.getOrNull()?.let { i to it } }
            .filter { (_, t) -> !t.isBefore(from) && !t.isAfter(to) }
            .maxByOrNull { it.second }?.first
    }

    private val BATCH_WINDOW: Duration = AskBatching.WINDOW
    private val BATCH_SKEW: Duration = Duration.ofMinutes(2)
}
