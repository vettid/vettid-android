package com.vettid.feature.approvals

import com.vettid.core.data.social.Approval
import java.time.Duration
import java.time.Instant

/** A row of the Approvals list: one approval, or a batch of one connection's asks (VAULT-MESSAGING 0.23.0 §10.4.1). */
sealed interface ApprovalEntry {
    val key: String
    val receivedAt: Instant

    data class One(val approval: Approval) : ApprovalEntry {
        override val key: String get() = approval.key
        override val receivedAt: Instant get() = approval.receivedAt
    }

    /** "Dr Lee asks for 3 things": [asks] oldest first, all from [connectionId]. */
    data class Batch(val connectionId: String, val connectionName: String?, val asks: List<Approval>) : ApprovalEntry {
        override val key: String get() = "batch:${asks.first().key}"
        override val receivedAt: Instant get() = asks.maxOf { it.receivedAt }
    }
}

/**
 * Batches of a connection's asks (§10.4.1 Batching): asks from one connection that reached the member within 10
 * minutes of the first of them are one batch, and the app MUST present a batch as one approval entry listing its
 * asks. The vault batches the same way (its feed item's `count`); the app groups the per-type lists it shows, so that a
 * batch reads the same whether its asks came as events or from the lists. Asks are the connection's grant requests,
 * critical-item uses and authentication challenges the app shows (share questions are the member's own rules, and
 * connection requests come from not-yet-connections: neither is an ask).
 */
object AskBatches {
    /** §10.4.1: 10 minutes from the first ask of a batch. */
    val WINDOW: Duration = Duration.ofMinutes(10)

    /** The connection an ask comes from; null for an approval that is not an ask. */
    fun connectionOf(a: Approval): String? = when (a) {
        is Approval.Authentication -> a.connectionId
        is Approval.GrantRequest -> a.connectionId
        is Approval.CriticalUse -> a.connectionId
        else -> null
    }

    /** [approvals] (newest first) as rows, newest first: a batch of two or more asks takes the place of its newest. */
    fun group(approvals: List<Approval>): List<ApprovalEntry> {
        val batchOf = mutableMapOf<String, ApprovalEntry.Batch>()
        approvals.filter { connectionOf(it) != null }.groupBy { connectionOf(it)!! }.forEach { (conn, asks) ->
            var current = mutableListOf<Approval>()
            fun close() {
                if (current.size > 1) {
                    val b = ApprovalEntry.Batch(conn, current.firstNotNullOfOrNull { it.connectionName }, current.toList())
                    current.forEach { batchOf[it.key] = b }
                }
                current = mutableListOf()
            }
            asks.sortedBy { it.receivedAt }.forEach { a ->
                val start = current.firstOrNull()?.receivedAt
                if (start != null && !a.receivedAt.isBefore(start.plus(WINDOW))) close()
                current += a
            }
            close()
        }
        val out = mutableListOf<ApprovalEntry>()
        val shown = mutableSetOf<String>()
        approvals.sortedByDescending { it.receivedAt }.forEach { a ->
            val b = batchOf[a.key]
            when {
                b == null -> out += ApprovalEntry.One(a)
                shown.add(b.key) -> out += b
            }
        }
        return out
    }
}
