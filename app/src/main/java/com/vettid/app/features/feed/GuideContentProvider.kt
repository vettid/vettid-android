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
            "welcome" -> welcomeGuide(userName)
            "navigation" -> navigationGuide()
            "settings" -> settingsGuide()
            "personal_data" -> personalDataGuide()
            "secrets" -> secretsGuide()
            "critical_secrets" -> criticalSecretsGuide()
            "voting" -> votingGuide()
            "connections" -> connectionsGuide()
            "wallets" -> walletsGuide()
            "calling" -> callingGuide()
            "messaging" -> messagingGuide()
            else -> null
        }
    }

    /** Every guide id the catalog knows, in the presentation order. */
    fun allGuideIds(): List<String> = listOf(
        "welcome",
        "navigation",
        "connections",
        "messaging",
        "calling",
        "personal_data",
        "secrets",
        "critical_secrets",
        "wallets",
        "voting",
        "settings",
    )

    private fun welcomeGuide(userName: String) = GuideContent(
        title = if (userName.isNotEmpty()) "Welcome to VettID, $userName!" else "Welcome to VettID!",
        icon = Icons.Default.Celebration,
        sections = listOf(
            GuideSection.Paragraph(
                "Your digital identity vault is set up and ready to use. Everything in VettID is encrypted and stored securely in your personal vault."
            ),
            GuideSection.Paragraph(
                "Your home screen lists your Connections. The VettID card at the top is your system connection — it surfaces guides, vault security updates, and governance votes. Tap your profile avatar in the top-left to open your vault."
            ),
            GuideSection.Paragraph(
                "Open the VettID card's Guides button any time to browse the rest of these guides and learn about each feature."
            )
        )
    )

    private fun navigationGuide() = GuideContent(
        title = "Getting Around",
        icon = Icons.Default.Explore,
        sections = listOf(
            GuideSection.Paragraph(
                "VettID has two main areas: your Connections home screen and your Vault."
            ),
            GuideSection.Heading("Connections"),
            GuideSection.Paragraph(
                "Your home screen lists every connection as a card, sorted by recent activity. The VettID card is pinned at the top — it's your system connection and carries four actions:"
            ),
            GuideSection.BulletList(
                listOf(
                    "Messages — vault security alerts and platform updates",
                    "Votes — governance proposals open for voting",
                    "Guides — this catalog of how-to guides",
                    "History — the audit trail of system-level events"
                )
            ),
            GuideSection.Paragraph(
                "Each connection card shows unread notifications below the contact row. Tap a card to open its detail screen for messaging, calling, and payments."
            ),
            GuideSection.Heading("Your Vault"),
            GuideSection.Paragraph(
                "Tap your profile avatar in the top-left corner to open your vault. A profile strip shows your photo and name at the top of every vault tab, with a shared \"publish changes\" banner whenever you have unpublished updates. The three vault tabs are:"
            ),
            GuideSection.BulletList(
                listOf(
                    "Data — your personal information and public profile fields",
                    "Secrets — passwords, keys, and sensitive data",
                    "Wallets — your Bitcoin wallets"
                )
            ),
            GuideSection.Heading("Status & Settings"),
            GuideSection.Paragraph(
                "Tap the status icon in the top-right corner to view vault status and open settings."
            )
        )
    )

    private fun settingsGuide() = GuideContent(
        title = "Customize Your Settings",
        icon = Icons.Default.Settings,
        sections = listOf(
            GuideSection.Paragraph(
                "VettID lets you customize several aspects of your experience. Tap the status icon in the top-right corner to open settings."
            ),
            GuideSection.Heading("Account"),
            GuideSection.BulletList(
                listOf(
                    "Change your PIN or password",
                    "Session TTL — how long until you need to re-enter your PIN",
                    "Credential backup toggle"
                )
            ),
            GuideSection.Heading("Privacy, Data & Logging"),
            GuideSection.BulletList(
                listOf(
                    "Location tracking preferences",
                    "Auto-archive and auto-delete timers for per-connection notifications",
                    "View audit logs of vault operations"
                )
            ),
            GuideSection.Heading("Appearance & About"),
            GuideSection.BulletList(
                listOf(
                    "Light, dark, or auto theme",
                    "App details and vault status with enclave attestation"
                )
            )
        )
    )

    private fun personalDataGuide() = GuideContent(
        title = "Your Personal Data",
        icon = Icons.Default.Person,
        sections = listOf(
            GuideSection.Paragraph(
                "Your personal data section stores information about you — name, contact details, addresses, and more."
            ),
            GuideSection.Heading("Profile Strip"),
            GuideSection.Paragraph(
                "The profile strip above the vault tabs shows your avatar and display name on every tab. Tap the photo to edit it, or the name to preview how your profile looks to connections. A banner appears whenever you have unpublished changes."
            ),
            GuideSection.Heading("Templates"),
            GuideSection.Paragraph(
                "Use templates to quickly add common fields like Date of Birth, SSN, Passport, Driver License, Home Address, Emergency Contact, and more. Templates provide the right field types and structure automatically."
            ),
            GuideSection.Heading("Adding Information"),
            GuideSection.Paragraph(
                "Add fields from templates or create custom fields for anything you need. Organize them into categories like Identity, Contact, Address, Financial, and Medical."
            ),
            GuideSection.Heading("Public Profile"),
            GuideSection.Paragraph(
                "Toggle which fields are included in your public profile. Connections see your public profile when they connect with you. Publish from the banner at the top of the vault whenever you change public fields."
            ),
            GuideSection.NavigationLink("Go to Personal Data", NavigationTarget.DrawerNav(DrawerItem.PERSONAL_DATA))
        )
    )

    private fun secretsGuide() = GuideContent(
        title = "Managing Secrets",
        icon = Icons.Default.Lock,
        sections = listOf(
            GuideSection.Paragraph(
                "The Secrets tab lets you securely store sensitive information in your vault."
            ),
            GuideSection.Heading("Types of Secrets"),
            GuideSection.BulletList(
                listOf(
                    "Passwords and PINs",
                    "Bank accounts and credit cards",
                    "Driver's license and passport details",
                    "Cryptocurrency wallets",
                    "API keys and certificates",
                    "WiFi passwords and custom notes"
                )
            ),
            GuideSection.Heading("Templates"),
            GuideSection.Paragraph(
                "Use pre-built templates for common secret types like credit cards, bank accounts, and IDs."
            ),
            GuideSection.Heading("Public Keys"),
            GuideSection.Paragraph(
                "Public keys can be shared on your profile as QR codes for others to scan. Publishing shared keys uses the same banner as the Data tab — one tap updates your public profile."
            ),
            GuideSection.NavigationLink("Go to Secrets", NavigationTarget.DrawerNav(DrawerItem.SECRETS))
        )
    )

    private fun criticalSecretsGuide() = GuideContent(
        title = "Critical Secrets",
        icon = Icons.Default.Security,
        sections = listOf(
            GuideSection.Paragraph(
                "Critical secrets are your most sensitive data — stored inside your credential blob and protected by an additional password verification."
            ),
            GuideSection.Heading("How It Works"),
            GuideSection.BulletList(
                listOf(
                    "Password required to access — every time",
                    "30-second reveal timer — values auto-hide",
                    "No local caching — data only exists in vault memory",
                    "Second password required to reveal individual values"
                )
            ),
            GuideSection.Heading("What to Store"),
            GuideSection.Paragraph(
                "Use critical secrets for seed phrases, private keys, master passwords, and anything that needs maximum protection."
            ),
            GuideSection.NavigationLink("Go to Critical Secrets", NavigationTarget.ScreenNav("critical-secrets"))
        )
    )

    private fun votingGuide() = GuideContent(
        title = "Governance & Voting",
        icon = Icons.Default.HowToVote,
        sections = listOf(
            GuideSection.Paragraph(
                "VettID includes a governance system where you can participate in decisions that affect the platform."
            ),
            GuideSection.Heading("Where to Find Votes"),
            GuideSection.Paragraph(
                "Open the VettID system card on your Connections screen and tap the Votes button. The badge shows how many proposals are open for your vote."
            ),
            GuideSection.Heading("Proposals"),
            GuideSection.Paragraph(
                "Each proposal lists who filed it, what it's asking, and when voting closes. Cast your vote before the deadline — your vote is cryptographically signed by your vault so it can't be tampered with or forged."
            ),
        )
    )

    private fun connectionsGuide() = GuideContent(
        title = "Connections",
        icon = Icons.Default.People,
        sections = listOf(
            GuideSection.Paragraph(
                "Connections let you link your vault with other VettID users for secure communication, calling, and payments."
            ),
            GuideSection.Heading("Adding Connections"),
            GuideSection.Paragraph(
                "Tap the add button on the Connections screen to create an invitation. Share it as a QR code for in-person scanning or as a time-limited link through any messaging app. To accept someone else's invite, scan their QR code or open their link."
            ),
            GuideSection.Heading("The Connection Card"),
            GuideSection.Paragraph(
                "Each connection appears as a card on the Connections screen with their photo, name, and quick-action buttons for messaging, calling, and payments. Unread notifications for that connection appear as rows below the card — the first row is shown with an expandable \"N more\" row if there are several."
            ),
            GuideSection.Heading("Managing Connections"),
            GuideSection.Paragraph(
                "Tap a card to open its detail screen, where you can view the public profile, review the audit trail, and revoke the connection at any time."
            ),
            GuideSection.Heading("Messaging"),
            GuideSection.Paragraph(
                "Send encrypted messages to your connections. Messages are encrypted in your vault before sending — only the recipient's vault can decrypt them."
            ),
            GuideSection.Heading("Voice & Video Calls"),
            GuideSection.Paragraph(
                "Make encrypted voice and video calls to your connections. Calls use end-to-end encryption with keys generated fresh for every call."
            ),
            GuideSection.Heading("Bitcoin Payments"),
            GuideSection.Paragraph(
                "Send and request Bitcoin payments directly through your connections. Transaction signing happens inside your vault's secure enclave."
            ),
        )
    )

    // Archive guide removed — the feed is purely connection-based now,
    // so there is no separate archive concept. Connections archive at
    // the per-connection level via the detail screen.

    private fun walletsGuide() = GuideContent(
        title = "Bitcoin Wallets",
        icon = Icons.Default.AccountBalance,
        sections = listOf(
            GuideSection.Paragraph(
                "VettID includes built-in Bitcoin wallet support. Your private keys are generated and stored inside the secure enclave — they never leave."
            ),
            GuideSection.Heading("Creating a Wallet"),
            GuideSection.Paragraph(
                "Create one or more wallets, each with its own address. Wallets use industry-standard BIP84 HD key derivation from your vault's master secret."
            ),
            GuideSection.Heading("Sending Bitcoin"),
            GuideSection.Paragraph(
                "Send BTC to a connection or any Bitcoin address. Choose your fee priority (fast, standard, or economy) and confirm. The transaction is signed inside the enclave and broadcast to the Bitcoin network."
            ),
            GuideSection.Heading("Receiving Bitcoin"),
            GuideSection.Paragraph(
                "Share your wallet address via QR code or copy it to receive payments."
            ),
            GuideSection.Heading("Privacy"),
            GuideSection.Paragraph(
                "Your wallet addresses are private by default. You can optionally make an address public on your profile if you choose."
            ),
            GuideSection.NavigationLink("Go to Wallets", NavigationTarget.DrawerNav(DrawerItem.WALLETS))
        )
    )

    private fun callingGuide() = GuideContent(
        title = "Voice & Video Calls",
        icon = Icons.Default.VideoCall,
        sections = listOf(
            GuideSection.Paragraph(
                "Make secure voice and video calls to anyone in your connections."
            ),
            GuideSection.Heading("Making a Call"),
            GuideSection.Paragraph(
                "Tap a connection card to open its detail page, then tap the call or video call button. The app will request microphone (and camera for video) permission if needed."
            ),
            GuideSection.Heading("Receiving Calls"),
            GuideSection.Paragraph(
                "Incoming calls appear as full-screen notifications, even when the app is in the background. Tap Answer or Decline."
            ),
            GuideSection.Heading("End-to-End Encryption"),
            GuideSection.Paragraph(
                "Every call generates fresh encryption keys. Your audio and video are encrypted before leaving your device — not even the relay server can see or hear the content."
            ),
            GuideSection.Heading("Call History"),
            GuideSection.Paragraph(
                "View your past calls, including missed calls, from the connection detail screen's audit trail."
            )
        )
    )

    private fun messagingGuide() = GuideContent(
        title = "Encrypted Messaging",
        icon = Icons.Default.Chat,
        sections = listOf(
            GuideSection.Paragraph(
                "Send encrypted messages to your connections. Every message is encrypted inside your vault before it leaves."
            ),
            GuideSection.Heading("Sending Messages"),
            GuideSection.Paragraph(
                "Tap a connection card's message button to open a conversation, or open the card's detail screen and pick Messages. Type your message and hit send."
            ),
            GuideSection.Heading("Encryption"),
            GuideSection.Paragraph(
                "Messages are encrypted with keys unique to each connection. Your vault encrypts outgoing messages and decrypts incoming ones — the server only sees encrypted data."
            ),
            GuideSection.Heading("Payment Messages"),
            GuideSection.Paragraph(
                "You can send and receive Bitcoin payment requests directly in your conversations. Payment request cards appear inline with options to pay or view transaction details."
            ),
        )
    )
}
