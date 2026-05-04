package com.vettid.app.features.feed

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.vettid.app.ui.navigation.DrawerItem

/**
 * Structured content for guide detail screens.
 * Maps guide_id to rich content with navigation links.
 */
data class GuideContent(
    val title: String,
    val icon: ImageVector,
    val sections: List<GuideSection>
)

sealed class GuideSection {
    data class Paragraph(val text: String) : GuideSection()
    data class NavigationLink(val label: String, val target: NavigationTarget) : GuideSection()
    data class BulletList(val items: List<String>) : GuideSection()
    data class Heading(val text: String) : GuideSection()
}

sealed class NavigationTarget {
    data class DrawerNav(val item: DrawerItem) : NavigationTarget()
    data class ScreenNav(val route: String) : NavigationTarget()
}

object GuideContentProvider {

    fun getContent(guideId: String, userName: String = ""): GuideContent? {
        return when (guideId) {
            "getting_started" -> gettingStartedGuide(userName)
            "vettid_system_card" -> vettidSystemCardGuide()
            "your_vault" -> yourVaultGuide()
            "public_profile" -> publicProfileGuide()
            "connecting_users" -> connectingUsersGuide()
            "connecting_things" -> connectingThingsGuide()
            "settings" -> settingsGuide()
            else -> null
        }
    }

    /** Every guide id the catalog knows, in the presentation order. */
    fun allGuideIds(): List<String> = listOf(
        "getting_started",
        "vettid_system_card",
        "your_vault",
        "public_profile",
        "connecting_users",
        "connecting_things",
        "settings",
    )

    private fun gettingStartedGuide(userName: String) = GuideContent(
        title = if (userName.isNotEmpty()) "Getting Started, $userName" else "Getting Started",
        icon = Icons.Default.Explore,
        sections = listOf(
            GuideSection.Paragraph(
                "Welcome to VettID. Your digital identity vault is live and everything in it is encrypted. Here's how to get around."
            ),
            GuideSection.Heading("Connections"),
            GuideSection.Paragraph(
                "Your home screen lists your connections. The VettID card is your system connection — everything that comes from the platform (rather than from another person) lives there. The + button creates an invitation for a new connection."
            ),
            GuideSection.Heading("Your Vault"),
            GuideSection.Paragraph(
                "Tap your profile avatar in the top-left to open your vault. A profile strip showing your photo and name sits above the three vault tabs: Data, Secrets, and Wallets. Tap the avatar again to return to Connections."
            ),
            GuideSection.Heading("Settings"),
            GuideSection.Paragraph(
                "Tap the cloud icon in the top-right — it shows your live connection status — to open settings. PIN and password, session timeout, appearance, location sharing, backup status, capabilities, and audit logs all live there. Tap the icon again to close settings and go back to what you were looking at."
            ),
            GuideSection.Paragraph(
                "When you're ready, open the VettID card's Guides button to read the rest of these guides in order."
            )
        )
    )

    private fun vettidSystemCardGuide() = GuideContent(
        title = "The VettID System Card",
        icon = Icons.Default.Campaign,
        sections = listOf(
            GuideSection.Paragraph(
                "The VettID card sits at the top of your Connections screen. It's your system connection — everything that comes from the platform (rather than from another person) lives here."
            ),
            GuideSection.Heading("Four actions"),
            GuideSection.BulletList(
                listOf(
                    "Messages — platform announcements and vault security alerts",
                    "Votes — governance proposals that are open for your vote (the badge shows how many)",
                    "Guides — this catalog of how-to guides (the badge shows unread)",
                    "History — the audit trail of system-level events"
                )
            ),
            GuideSection.Heading("Notifications"),
            GuideSection.Paragraph(
                "Unread guide notifications appear as rows below the card. Tap one to open that guide; tap the \"N more notifications\" row to expand the full list."
            ),
            GuideSection.Paragraph(
                "Tapping the card body opens the system connection's history — the same audit trail you get from the History action."
            )
        )
    )

    private fun yourVaultGuide() = GuideContent(
        title = "Your Vault",
        icon = Icons.Default.Lock,
        sections = listOf(
            GuideSection.Paragraph(
                "Your vault is where everything sensitive lives. The app can't read it directly — all reads and writes go through your personal vault manager running inside a hardware-attested enclave, which only answers after the attestation passes."
            ),
            GuideSection.Heading("Getting there"),
            GuideSection.Paragraph(
                "Tap your profile avatar in the top-left of the Connections screen. A profile strip sits above the tabs on every vault view, showing your photo and name. Tap your name to preview exactly what connections see."
            ),
            GuideSection.Heading("Profile Photo"),
            GuideSection.Paragraph(
                "Tap the avatar in the strip to take a photo with your camera. It's stored encrypted in your vault and shown to connections when you share your public profile."
            ),
            GuideSection.Heading("Data tab"),
            GuideSection.Paragraph(
                "Personal information — name, contact details, addresses, IDs, medical info, and more. Use templates for common groups (family contact, government ID, home address) or add custom fields."
            ),
            GuideSection.Paragraph(
                "Each item has a discoverability setting that controls how it appears to connections:\n" +
                "• Public — shown on your public profile with the value visible. Use for the calling-card stuff: a friendly name, a public email.\n" +
                "• Cataloged (default) — connections can see that you have an item by name (e.g. \"Mobile Phone\"), but the value is private until they ask and you approve.\n" +
                "• Private — connections don't see it at all; the vault holds it for you alone. Use for genuinely sensitive items you don't want a connection to know exist."
            ),
            GuideSection.Heading("Secrets tab"),
            GuideSection.Paragraph(
                "Passwords, PINs, bank accounts, API keys, public keys, and anything else you need to keep safe. Templates cover common secret types. The same Public / Cataloged / Private discoverability applies — by default a connection sees that you have a Visa card on file (and can request to use it for a transaction), but never sees the number until you grant access."
            ),
            GuideSection.Heading("Critical Secrets"),
            GuideSection.Paragraph(
                "Critical Secrets are your most sensitive data — seed phrases, master passwords, recovery keys. They live inside your credential blob and require an extra password every time you unlock them. Values auto-hide after 30 seconds and are never cached outside the enclave."
            ),
            GuideSection.Heading("Wallets tab"),
            GuideSection.Paragraph(
                "Bitcoin wallets with BIP84 HD key derivation. The seed and signing key live inside your credential — wallet creation asks for your password, and every send asks again. Private keys never leave the enclave. Create multiple wallets, send BTC to any address or connection, and optionally mark a wallet address as public so it appears on your profile. In the Available Secrets catalog, a wallet shows as one card containing the address, signing key, and seed phrase grouped together."
            ),
            GuideSection.Heading("Reordering items"),
            GuideSection.Paragraph(
                "Each row on the Data and Secrets tabs has a drag handle (≡) on the left. Long-press the handle, then drag up or down — the item walks past its neighbors as you go. Lift your finger to drop. The new order is saved automatically and connections see it immediately."
            ),
            GuideSection.NavigationLink("Open Personal Data", NavigationTarget.DrawerNav(DrawerItem.PERSONAL_DATA)),
            GuideSection.NavigationLink("Open Secrets", NavigationTarget.DrawerNav(DrawerItem.SECRETS)),
            GuideSection.NavigationLink("Open Wallets", NavigationTarget.DrawerNav(DrawerItem.WALLETS))
        )
    )

    private fun publicProfileGuide() = GuideContent(
        title = "Your Public Profile",
        icon = Icons.Default.Person,
        sections = listOf(
            GuideSection.Paragraph(
                "Your public profile is what connections see when they connect with you. Think of it as four layers stacked together:"
            ),
            GuideSection.BulletList(
                listOf(
                    "Calling card — the data fields you've marked Public. Values are visible: name, email, anything you want to volunteer.",
                    "Data catalog — every other personal-data item you have, by name only. A connection can see you have a Mobile Phone or a Home Address without seeing the values, then ask for them when they need to.",
                    "Secret catalog — same idea for credential secrets. A connection can see you have a Visa on file and ask to charge it.",
                    "Handler catalog — the shareable capabilities your vault supports (messaging, calls, BTC, etc.) so connections know what they can do with you."
                )
            ),
            GuideSection.Heading("Picking what's where"),
            GuideSection.Paragraph(
                "Each data and secret item has a discoverability setting:\n" +
                "• Public — value goes on the calling card.\n" +
                "• Cataloged (default) — only the name appears in the catalog. The value stays private until a connection requests it and you approve.\n" +
                "• Private — invisible to connections. The vault holds it for you alone."
            ),
            GuideSection.Heading("Ordering"),
            GuideSection.Paragraph(
                "The order items appear on your calling card and in the catalogs is the order you see them on the Data and Secrets tabs. To reorder, long-press the drag handle (≡) on the left of any row and drag up or down. Useful for putting your most important fields at the top."
            ),
            GuideSection.Heading("Auto-publish"),
            GuideSection.Paragraph(
                "Every change you make — toggling visibility, editing a value, adding a field — is published to your connections immediately. There's no manual \"Publish\" step. The catalog badges and calling card update on each side within a few seconds."
            ),
            GuideSection.Heading("Previewing"),
            GuideSection.Paragraph(
                "Tap your name in the vault profile strip (\"Tap to preview\") to see exactly what your connections see — calling card up top, the three catalog badges (Data, Secrets, Handlers) right below it. Tap any badge to inspect the catalog. Each catalog row groups by alias when you've set one (a wallet shows up as a single bundle: address, signing key, and seed phrase together)."
            ),
            GuideSection.NavigationLink("Go to Personal Data", NavigationTarget.DrawerNav(DrawerItem.PERSONAL_DATA))
        )
    )

    private fun connectingUsersGuide() = GuideContent(
        title = "Connecting with People",
        icon = Icons.Default.People,
        sections = listOf(
            GuideSection.Paragraph(
                "Connecting links your vault with another VettID user's vault so you can message, call, and send Bitcoin with one end-to-end encrypted session."
            ),
            GuideSection.Heading("Making a connection"),
            GuideSection.BulletList(
                listOf(
                    "Tap the + button on the Connections screen to create an invitation. Share it as a QR code for in-person scanning or as a short link through any messenger.",
                    "To accept an incoming invitation, scan the QR code or open the link. Your app shows a preview of the inviter's public profile.",
                    "Either side can decline or accept on the review screen. Once both sides accept, the connection is live."
                )
            ),
            GuideSection.Heading("The connection card"),
            GuideSection.Paragraph(
                "Each connection appears as a card on your Connections screen with quick buttons for messaging, calling, payments, and history. Unread notifications for that connection show up below the card."
            ),
            GuideSection.Heading("Messaging"),
            GuideSection.Paragraph(
                "Messages are end-to-end encrypted. Your app encrypts before sending to your vault, your vault re-encrypts to the peer vault, the peer vault decrypts and delivers to the peer app. The server only ever sees ciphertext."
            ),
            GuideSection.Heading("Voice & Video Calls"),
            GuideSection.Paragraph(
                "Calls use WebRTC with an ephemeral key per call. Audio and video are encrypted before leaving your device; not even the relay server can see or hear the content. Open a connection card and tap the phone or video icon."
            ),
            GuideSection.Heading("Bitcoin payments"),
            GuideSection.Paragraph(
                "Send BTC directly to a connection — the payment request appears inline in your conversation. Transaction signing happens inside your vault's enclave, and you choose a fee priority before confirming."
            ),
            GuideSection.Heading("Managing connections"),
            GuideSection.Paragraph(
                "Tap any card to open the peer's public profile and the connection detail screen. From there you can review the audit trail, rotate session keys, toggle location sharing, or revoke the connection."
            ),
            GuideSection.Heading("Connection History"),
            GuideSection.Paragraph(
                "Declined, revoked, and expired connections leave the live feed but aren't deleted — your vault keeps the record. A \"Connection History\" card appears at the bottom of the feed when there's at least one archived connection. Tap it to see who you declined and when."
            )
        )
    )

    private fun connectingThingsGuide() = GuideContent(
        title = "Connecting with Agents & Services",
        icon = Icons.Default.Hub,
        sections = listOf(
            GuideSection.Paragraph(
                "VettID connections aren't just for people. You can pair your vault with agents, your desktop client, and vault-to-vault service integrations. Every integration goes through the same enclave-gated key exchange as a peer connection."
            ),
            GuideSection.Heading("Desktop client"),
            GuideSection.Paragraph(
                "Pair the VettID desktop app with your phone. The desktop generates a short code; you scan it from the phone to authorize. After pairing, both endpoints share a session with your vault — messaging, calling, and BTC all work from the desktop."
            ),
            GuideSection.Heading("Agent connector"),
            GuideSection.Paragraph(
                "Run an AI agent alongside your vault. The agent connects with scoped capabilities you approve and never has plaintext access to data you haven't exposed. You can revoke an agent at any time from the connection detail screen."
            ),
            GuideSection.Heading("Service vaults"),
            GuideSection.Paragraph(
                "Some services (healthcare, identity verification, payment processors) operate their own VettID vaults. You can connect to them with the same invitation flow — the difference is you review the service's scope and capabilities before accepting."
            ),
            GuideSection.Paragraph(
                "All of these appear on the Connections screen alongside your human connections. The card layout tells you which type (person, desktop, agent, or service) and gives you the same revoke, rotate-keys, and audit-trail controls."
            )
        )
    )

    private fun settingsGuide() = GuideContent(
        title = "Settings",
        icon = Icons.Default.Settings,
        sections = listOf(
            GuideSection.Paragraph(
                "Tap the cloud icon in the top-right — it shows your live connection status — to open settings."
            ),
            GuideSection.Heading("Account"),
            GuideSection.BulletList(
                listOf(
                    "Change your PIN or password",
                    "Session TTL — how long until you need to re-unlock",
                    "Credential backup toggle"
                )
            ),
            GuideSection.Heading("Privacy, Data & Logging"),
            GuideSection.BulletList(
                listOf(
                    "Location sharing preferences (per-connection)",
                    "Auto-archive and auto-delete timers for per-connection notifications",
                    "Audit log of vault operations"
                )
            ),
            GuideSection.Heading("Appearance & About"),
            GuideSection.BulletList(
                listOf(
                    "Light, dark, or auto theme",
                    "App details and vault status with live enclave PCR attestation"
                )
            )
        )
    )
}
