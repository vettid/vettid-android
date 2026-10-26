package com.vettid.core.altchan

import com.vettid.core.crypto.Base64s
import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.JsonObject
import com.vettid.core.crypto.json.JsonNumber
import com.vettid.core.crypto.json.StrictJson

/**
 * What the app keeps per vault for the alternate channel (§11.3 "App
 * state", §11.10.6, §13.2): the release it last unlocked into, the
 * previous release of an unconfirmed move, the highest manifest serial,
 * `state_seq` and the highest `header_seq` per release (rollback floors),
 * and a pending enrollment's nonce and measurements. Immutable; the device
 * replaces it under its lock.
 *
 * (ANDROID-PLAN 0.1.54) The marker `move_unconfirmed {from, to, failed}` of "The way back to release N" is part of it:
 * [previousRelease] and [previousReleaseNumber] are `from` (release N), [release] and [releaseNumber] are `to` (N+1, the
 * release recorded with it in the same write) and [moveFailed] is `failed`. Release numbers and PCR0s are public, so
 * nothing of it is secret; N's `header_seq` stays in [headerSeq].
 */
data class AltState(
    val release: String = "",
    val releaseNumber: Long = 0,
    val previousRelease: String = "",
    val previousReleaseNumber: Long = 0,
    val manifestSerial: Long = 0,
    val stateSeq: Long = 0,
    val headerSeq: Map<String, Long> = emptyMap(),
    val enrollNonce: ByteArray? = null,
    /** PCR0 || PCR1 || PCR2 of the enclave the enrollment was sealed to. */
    val enrollPcrs: String = "",
    val enrollNumber: Long = 0,
    /**
     * (A6 audit N3) The `request_id` of the enrollment request whose [enrollNonce] is kept: stored before the request is
     * posted, so that a retry or a restart looks at that request's slot instead of posting a second enrollment (which the
     * enclave would answer `vault_exists` for the provisional vault the first one made, §11.3). Empty: none outstanding.
     */
    val enrollRequestId: String = "",
    /** (N3) When that request was built (epoch ms, the phone's clock): its slot exists for 15 minutes after it is posted. */
    val enrollPostedMs: Long = 0,
    /** (N3) Its `vault.enroll.result` was `ok`: the vault exists, and only its `vault.enrolled` is awaited. */
    val enrollAnswered: Boolean = false,
    /** (ANDROID-PLAN 0.1.54) An unlock after the move failed to reach or open the vault on [release] for a reason not the member's. */
    val moveFailed: Boolean = false,
) {
    fun marshal(): String {
        val hs = JsonBuilder()
        headerSeq.toSortedMap().forEach { (k, v) -> hs.uint(k, v) }
        val b = JsonBuilder().string("release", release).uint("release_number", releaseNumber)
            .string("previous_release", previousRelease).uint("previous_release_number", previousReleaseNumber)
            .bool("move_failed", moveFailed)
            .uint("manifest_serial", manifestSerial).uint("state_seq", stateSeq).raw("header_seq", hs.build())
        enrollNonce?.let { b.base64("enroll_nonce", it) }
        b.string("enroll_pcrs", enrollPcrs).uint("enroll_number", enrollNumber)
        if (enrollRequestId.isNotEmpty()) {
            b.string("enroll_request_id", enrollRequestId).uint("enroll_posted_ms", enrollPostedMs).bool("enroll_answered", enrollAnswered)
        }
        return b.build()
    }

    /** The release an unconfirmed move left (`from`, §11.10.4); null when no move is unconfirmed. */
    val movedFrom: MovedFrom? get() = previousRelease.takeIf { it.isNotEmpty() }?.let { MovedFrom(it, previousReleaseNumber) }

    /** (N3) The enrollment request outstanding (built, maybe posted, its `vault.enrolled` not applied yet), or null. */
    val pendingEnroll: PendingEnroll?
        get() = enrollRequestId.takeIf { it.isNotEmpty() && enrollNonce != null }?.let { PendingEnroll(it, enrollPostedMs, enrollAnswered) }

    /** (N3) The outstanding enrollment is over without a vault (refused, never processed): its nonce goes with it. */
    fun withoutEnroll(): AltState = copy(enrollNonce = null, enrollPcrs = "", enrollNumber = 0, enrollRequestId = "", enrollPostedMs = 0,
        enrollAnswered = false)

    /** No move is unconfirmed any more: the marker goes (the release recorded stays). */
    fun withoutMove(): AltState = copy(previousRelease = "", previousReleaseNumber = 0, moveFailed = false)

    override fun equals(other: Any?): Boolean = other is AltState && marshal() == other.marshal()

    override fun hashCode(): Int = marshal().hashCode()

    override fun toString(): String = "AltState(release=$releaseNumber, stateSeq=$stateSeq, manifestSerial=$manifestSerial)"

    companion object {
        private const val MAX = StrictJson.MAX_SAFE_INTEGER

        fun parse(o: JsonObject): AltState = AltState(
            release = o.optString("release") ?: "",
            releaseNumber = o.optUint("release_number", 0, MAX) ?: 0,
            previousRelease = o.optString("previous_release") ?: "",
            previousReleaseNumber = o.optUint("previous_release_number", 0, MAX) ?: 0,
            moveFailed = optBool(o, "move_failed"),
            manifestSerial = o.optUint("manifest_serial", 0, MAX) ?: 0,
            stateSeq = o.optUint("state_seq", 0, MAX) ?: 0,
            headerSeq = o.optObj("header_seq")?.members?.mapValues { (_, v) -> (v as JsonNumber).raw.toLong() } ?: emptyMap(),
            enrollNonce = o.optString("enroll_nonce")?.let { Base64s.decodeStd(it) },
            enrollPcrs = o.optString("enroll_pcrs") ?: "",
            enrollNumber = o.optUint("enroll_number", 0, MAX) ?: 0,
            enrollRequestId = o.optString("enroll_request_id") ?: "",
            enrollPostedMs = o.optUint("enroll_posted_ms", 0, MAX) ?: 0,
            enrollAnswered = optBool(o, "enroll_answered"),
        )

        private fun optBool(o: JsonObject, key: String): Boolean = if (o.has(key)) o.bool(key) else false
    }
}

/** (A6 audit N3) An enrollment request this app built: its id, when it was built, and whether its result was `ok`. */
data class PendingEnroll(val requestId: String, val postedMs: Long, val answered: Boolean)

/** A release by its PCR0 and number: the one an unconfirmed move left ([AltState.movedFrom]). */
data class MovedFrom(val pcr0: String, val number: Long)
