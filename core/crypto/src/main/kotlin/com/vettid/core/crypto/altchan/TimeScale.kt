package com.vettid.core.crypto.altchan

import com.vettid.core.crypto.json.JsonBuilder
import com.vettid.core.crypto.json.JsonObject

/**
 * A release's time scale (STAGING-TIME-SCALE §5.2; VAULT-MESSAGING 0.43.0 §11.10.8, §11.10.9): the measured release
 * constant by which the vault divides its waits of 1 hour or more, carried in the attested ETK descriptor and in a
 * transfer's bundle. A missing member is a release before the scale (1). A value that is not an integer from 1 to
 * [MAX] is refused like any malformed descriptor or bundle; which values a build accepts is the build's own rule
 * (`AltTrust`): production 1 only, staging 1 and 24.
 */
object TimeScale {
    const val MEMBER = "time_scale"

    /** Real time: production, and every release before the scale. */
    const val REAL = 1L

    /** Staging's value: 1 day = 1 hour. */
    const val STAGING = 24L

    /** The largest value any channel file may state (the development build's bound). */
    const val MAX = 86_400L

    /** `time_scale` of [o]; [REAL] when absent. */
    fun parse(o: JsonObject): Long = o.optUint(MEMBER, REAL, MAX) ?: REAL

    /** Writes `time_scale` (tests and tooling). */
    fun write(b: JsonBuilder, scale: Long): JsonBuilder = b.uint(MEMBER, scale)
}

/**
 * What a verified descriptor or a transfer bundle says of its release, for the app to choose the request's shape and
 * its own waits (§11.10.9): its [capabilities] and its [timeScale].
 */
data class ReleaseTraits(val capabilities: List<String> = emptyList(), val timeScale: Long = TimeScale.REAL)
