package com.vettid.app.features.feed

/**
 * Guide definitions that the app owns. The vault is a dumb storage layer -
 * it creates feed events from this catalog and tracks what's been created.
 *
 * To add/update a guide: modify this list and bump the version.
 * No enclave redeployment needed.
 */
data class GuideDef(
    val guideId: String,
    val title: String,
    val message: String,
    val order: Int,
    val priority: Int,  // -1=low, 0=normal, 1=high, 2=urgent
    val version: Int
)

object GuideCatalog {

    val guides = listOf(
        GuideDef(
            guideId = "welcome",
            title = "Start Here - Welcome to VettID!",
            message = "Tap here to get started with your new digital identity.",
            order = 1,
            priority = 1, // HIGH
            version = 1
        ),
        GuideDef(
            guideId = "navigation",
            title = "Getting Around",
            message = "Learn how to navigate between screens and use the drawer.",
            order = 2,
            priority = 0,
            version = 1
        ),
        GuideDef(
            guideId = "settings",
            title = "Customize Your Settings",
            message = "Theme (light/dark/auto), session TTL, archive timing, event handlers, and password — find them all in Preferences.",
            order = 3,
            priority = 0,
            version = 2
        ),
        GuideDef(
            guideId = "personal_data",
            title = "Your Personal Data",
            message = "Add and manage your personal information, photo, and public profile.",
            order = 4,
            priority = 0,
            version = 1
        ),
        GuideDef(
            guideId = "secrets",
            title = "Managing Secrets",
            message = "Store and organize sensitive information securely in your vault.",
            order = 5,
            priority = 0,
            version = 1
        ),
        GuideDef(
            guideId = "critical_secrets",
            title = "Critical Secrets",
            message = "Protect your most sensitive data with password-verified, time-limited access.",
            order = 6,
            priority = 0,
            version = 1
        ),
        GuideDef(
            guideId = "voting",
            title = "Governance & Voting",
            message = "Participate in governance decisions and cast your votes.",
            order = 7,
            priority = 0,
            version = 1
        ),
        GuideDef(
            guideId = "connections",
            title = "Connections",
            message = "Connect with others and manage your relationships.",
            order = 8,
            priority = 0,
            version = 1
        ),
        GuideDef(
            guideId = "archive",
            title = "The Archive",
            message = "Find and restore archived items.",
            order = 9,
            priority = 0,
            version = 1
        )
    )
}
