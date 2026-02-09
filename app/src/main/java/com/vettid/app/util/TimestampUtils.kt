package com.vettid.app.util

import java.time.Instant

/**
 * Convert a vault timestamp to epoch milliseconds.
 *
 * The vault (Go) uses time.Now().Unix() which returns Unix seconds,
 * but Android APIs (Date, Instant.ofEpochMilli) expect milliseconds.
 *
 * Heuristic: timestamps < 10 billion are in seconds (covers dates through year 2286).
 * Values >= 10 billion are already in milliseconds.
 */
fun toEpochMillis(timestamp: Long): Long {
    return if (timestamp in 1..9_999_999_999L) {
        timestamp * 1000L
    } else {
        timestamp
    }
}

/**
 * Convert a vault timestamp to an Instant.
 * Handles both seconds (from Go vault) and milliseconds (from Android).
 */
fun toInstant(timestamp: Long): Instant {
    return Instant.ofEpochMilli(toEpochMillis(timestamp))
}
