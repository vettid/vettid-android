package com.vettid.core.data.social

import java.time.Duration

/**
 * The batches of a connection's asks (VAULT-MESSAGING 0.23.0 §10.4.1 Batching): asks from one connection that reached the
 * member within 10 minutes of the first of them are one batch. Shared by the Approvals list (one row per batch) and by the
 * withdrawal of one ask of a batch (0.80.0 §10.4: the batch's entry and notification stay while any ask of it is open).
 */
object AskBatching {
    /** §10.4.1: 10 minutes from the first ask of a batch. */
    val WINDOW: Duration = Duration.ofMinutes(10)

    /** The connection an ask comes from; null for an approval that is not an ask. */
    fun connectionOf(a: Approval): String? = when (a) {
        is Approval.Authentication -> a.connectionId
        is Approval.GrantRequest -> a.connectionId
        is Approval.CriticalUse -> a.connectionId
        else -> null
    }

    /** One connection's [asks] split into batches, each oldest first. */
    fun split(asks: List<Approval>): List<List<Approval>> {
        val out = mutableListOf<List<Approval>>()
        var current = mutableListOf<Approval>()
        asks.sortedBy { it.receivedAt }.forEach { a ->
            val start = current.firstOrNull()?.receivedAt
            if (start != null && !a.receivedAt.isBefore(start.plus(WINDOW))) {
                out += current
                current = mutableListOf()
            }
            current += a
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /** The asks of the batch that holds [key] (itself included), oldest first; empty when [key] is not an ask of [approvals]. */
    fun batchOf(approvals: List<Approval>, key: String): List<Approval> {
        val conn = approvals.firstOrNull { it.key == key }?.let(::connectionOf) ?: return emptyList()
        return split(approvals.filter { connectionOf(it) == conn }).firstOrNull { b -> b.any { it.key == key } } ?: emptyList()
    }
}
