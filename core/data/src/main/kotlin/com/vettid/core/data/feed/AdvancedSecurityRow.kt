package com.vettid.core.data.feed

/**
 * (ANDROID-PLAN 0.1.124 §4 "Advanced security") The rows of Settings → Security → Advanced security that a notification or a
 * link opens the screen at.
 */
enum class AdvancedSecurityRow {
    /** The owner check's interval ("Settings → Security (the check)"). */
    INTERVAL,

    /** The hold switch ("Settings → Security (the hold)"). */
    HOLD,

    /** The idle lock. */
    IDLE_LOCK,

    /** New phone secret (when it applies). */
    NEW_PHONE_SECRET,
}
