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
            "archive" -> archiveGuide()
            else -> null
        }
    }

    private fun welcomeGuide(userName: String) = GuideContent(
        title = if (userName.isNotEmpty()) "Welcome to VettID, $userName!" else "Welcome to VettID!",
        icon = Icons.Default.Celebration,
        sections = listOf(
            GuideSection.Paragraph(
                "Your digital identity vault is set up and ready to use. Everything in VettID is encrypted and stored securely in your personal vault."
            ),
            GuideSection.Heading("Your Feed"),
            GuideSection.Paragraph(
                "This is your feed. All your vault activity appears here \u2014 connections, messages, security alerts, and these guides."
            ),
            GuideSection.Paragraph(
                "Tap events to see details. Use the overflow menu on any event to archive or delete it."
            ),
            GuideSection.Heading("What\u2019s Next"),
            GuideSection.Paragraph(
                "Browse the other guides in your feed to learn about each feature. You can dismiss them as you go."
            )
        )
    )

    private fun navigationGuide() = GuideContent(
        title = "Getting Around",
        icon = Icons.Default.Explore,
        sections = listOf(
            GuideSection.Paragraph(
                "VettID has several screens you can navigate between."
            ),
            GuideSection.Heading("Swipe Navigation"),
            GuideSection.Paragraph(
                "Swipe left or right anywhere on the screen to move between sections: Feed, Connections, Personal Data, Secrets, Archive, and Voting."
            ),
            GuideSection.Heading("The Drawer"),
            GuideSection.Paragraph(
                "Tap your profile icon in the top-left corner to open the navigation drawer. From there you can jump directly to any section."
            ),
            GuideSection.Heading("Settings"),
            GuideSection.Paragraph(
                "Tap the cloud/gear icon in the top-right corner to access your settings."
            ),
            GuideSection.NavigationLink("Open Settings", NavigationTarget.ScreenNav("settings"))
        )
    )

    private fun settingsGuide() = GuideContent(
        title = "Customize Your Settings",
        icon = Icons.Default.Settings,
        sections = listOf(
            GuideSection.Paragraph(
                "VettID lets you customize several aspects of your experience."
            ),
            GuideSection.Heading("Available Settings"),
            GuideSection.BulletList(
                listOf(
                    "Change your PIN",
                    "Change your password",
                    "Toggle biometric authentication",
                    "Theme and appearance",
                    "Notification preferences",
                    "Backup settings"
                )
            ),
            GuideSection.NavigationLink("Open Settings", NavigationTarget.ScreenNav("settings"))
        )
    )

    private fun personalDataGuide() = GuideContent(
        title = "Your Personal Data",
        icon = Icons.Default.Person,
        sections = listOf(
            GuideSection.Paragraph(
                "Your personal data section stores information about you \u2014 name, contact details, addresses, and more."
            ),
            GuideSection.Heading("Adding Information"),
            GuideSection.Paragraph(
                "Add and edit fields, organize them into categories, and set up custom fields for anything you need."
            ),
            GuideSection.Heading("Profile Photo"),
            GuideSection.Paragraph(
                "Add a profile photo that connections can see. Your photo is stored encrypted in your vault."
            ),
            GuideSection.Heading("Public Profile"),
            GuideSection.Paragraph(
                "Choose which fields to include in your public profile. Connections see your public profile when they connect with you."
            ),
            GuideSection.NavigationLink("Go to Personal Data", NavigationTarget.DrawerNav(DrawerItem.PERSONAL_DATA))
        )
    )

    private fun secretsGuide() = GuideContent(
        title = "Managing Secrets",
        icon = Icons.Default.Lock,
        sections = listOf(
            GuideSection.Paragraph(
                "The Secrets section lets you securely store sensitive information in your vault."
            ),
            GuideSection.Heading("Types of Secrets"),
            GuideSection.BulletList(
                listOf(
                    "Passwords and PINs",
                    "Bank accounts and credit cards",
                    "Driver\u2019s license and passport details",
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
                "Public keys can be shared on your profile as QR codes for others to scan."
            ),
            GuideSection.NavigationLink("Go to Secrets", NavigationTarget.DrawerNav(DrawerItem.SECRETS))
        )
    )

    private fun criticalSecretsGuide() = GuideContent(
        title = "Critical Secrets",
        icon = Icons.Default.Security,
        sections = listOf(
            GuideSection.Paragraph(
                "Critical secrets are your most sensitive data \u2014 stored inside your credential blob and protected by an additional password verification."
            ),
            GuideSection.Heading("How It Works"),
            GuideSection.BulletList(
                listOf(
                    "Password required to access \u2014 every time",
                    "30-second reveal timer \u2014 values auto-hide",
                    "No local caching \u2014 data only exists in vault memory",
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
            GuideSection.Heading("Proposals"),
            GuideSection.Paragraph(
                "Browse active proposals and view their details, including who proposed them and the voting deadline."
            ),
            GuideSection.Heading("Casting Votes"),
            GuideSection.Paragraph(
                "Your vote is cryptographically signed by your vault, ensuring it can\u2019t be tampered with or forged."
            ),
            GuideSection.NavigationLink("Go to Voting", NavigationTarget.DrawerNav(DrawerItem.VOTING))
        )
    )

    private fun connectionsGuide() = GuideContent(
        title = "Connections",
        icon = Icons.Default.People,
        sections = listOf(
            GuideSection.Paragraph(
                "Connections let you link your vault with other VettID users for secure communication and data sharing."
            ),
            GuideSection.Heading("Adding Connections"),
            GuideSection.Paragraph(
                "Create an invitation and share it via QR code, or scan someone else\u2019s invitation to connect."
            ),
            GuideSection.Heading("Managing Connections"),
            GuideSection.Paragraph(
                "View connection details, send messages, and manage access. You can revoke a connection at any time."
            ),
            GuideSection.NavigationLink("Go to Connections", NavigationTarget.DrawerNav(DrawerItem.CONNECTIONS))
        )
    )

    private fun archiveGuide() = GuideContent(
        title = "The Archive",
        icon = Icons.Default.Archive,
        sections = listOf(
            GuideSection.Paragraph(
                "The archive is where dismissed and old items go. Nothing is truly lost \u2014 you can always find it here."
            ),
            GuideSection.Heading("What Gets Archived"),
            GuideSection.BulletList(
                listOf(
                    "Feed events you archive manually",
                    "Auto-archived items based on your settings",
                    "Old notifications and completed actions"
                )
            ),
            GuideSection.Heading("Restoring Items"),
            GuideSection.Paragraph(
                "Browse the archive and restore anything you need back to your active feed."
            ),
            GuideSection.NavigationLink("Go to Archive", NavigationTarget.DrawerNav(DrawerItem.ARCHIVE))
        )
    )
}
