package com.vettid.app.features.feed

/**
 * Guide definitions that the app owns. The vault is a dumb storage layer —
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
            guideId = "getting_started",
            title = "Getting Started",
            message = "Welcome to VettID. Learn how to move between the Connections screen, your Vault, and Settings.",
            order = 1,
            priority = 1, // HIGH
            version = 1
        ),
        GuideDef(
            guideId = "vettid_system_card",
            title = "The VettID System Card",
            message = "Your home for platform updates, governance votes, and these guides.",
            order = 2,
            priority = 0,
            version = 1
        ),
        GuideDef(
            guideId = "your_vault",
            title = "Your Vault",
            message = "Set your profile photo, manage personal data, secrets, critical secrets, and Bitcoin wallets.",
            order = 3,
            priority = 0,
            version = 1
        ),
        GuideDef(
            guideId = "public_profile",
            title = "Your Public Profile",
            message = "Choose what to share, preview how connections see it, and publish your public profile.",
            order = 4,
            priority = 0,
            version = 1
        ),
        GuideDef(
            guideId = "connecting_users",
            title = "Connecting with People",
            message = "Invite, accept, and then message, call, or send Bitcoin — end-to-end encrypted throughout.",
            order = 5,
            priority = 0,
            version = 1
        ),
        GuideDef(
            guideId = "connecting_things",
            title = "Connecting with Agents & Services",
            message = "Pair the desktop client, agent connector, and other vault-to-vault integrations.",
            order = 6,
            priority = 0,
            version = 1
        ),
        GuideDef(
            guideId = "settings",
            title = "Settings",
            message = "Session TTL, backup toggle, theme, location sharing, audit logs, and more.",
            order = 7,
            priority = 0,
            version = 2
        ),
    )
}
