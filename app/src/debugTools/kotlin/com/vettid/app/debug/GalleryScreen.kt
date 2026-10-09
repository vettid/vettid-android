package com.vettid.app.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vettid.core.ui.components.AvatarSheetContent
import com.vettid.core.ui.components.BottomFloatingControls
import com.vettid.core.ui.components.CenteredTitle
import com.vettid.core.ui.components.ConfirmDialog
import com.vettid.core.ui.components.ConnectionRow
import com.vettid.core.ui.components.CountBadge
import com.vettid.core.ui.components.DetailCard
import com.vettid.core.ui.components.DrawerItem
import com.vettid.core.ui.components.EmptyState
import com.vettid.core.ui.components.FloatingFilterChip
import com.vettid.core.ui.components.FloatingPillActionBar
import com.vettid.core.ui.components.InitialTile
import com.vettid.core.ui.components.LargeTitle
import com.vettid.core.ui.components.PillAction
import com.vettid.core.ui.components.RookLogo
import com.vettid.core.ui.components.SettingsAccountRow
import com.vettid.core.ui.components.SettingsDivider
import com.vettid.core.ui.components.SettingsGroup
import com.vettid.core.ui.components.SettingsRow
import com.vettid.core.ui.components.SettingsSectionHeader
import com.vettid.core.ui.components.SettingsSwitchRow
import com.vettid.core.ui.components.TagLabel
import com.vettid.core.ui.components.TileStyle
import com.vettid.core.ui.components.VettIdBackTopBar
import com.vettid.core.ui.components.VettIdDrawerSheet
import com.vettid.core.ui.components.VettIdFab
import com.vettid.core.ui.components.VettIdListRow
import com.vettid.core.ui.components.VettIdTopAppBar
import com.vettid.core.ui.components.AvatarSheet
import com.vettid.core.ui.components.DetailScaffold
import com.vettid.core.ui.theme.Spacing
import com.vettid.core.ui.theme.ThemeMode
import com.vettid.core.ui.theme.VettIdTheme

/**
 * Debug-only component gallery: every :core:ui component with sample data, in the
 * current theme. Sample text is hard-coded on purpose (never shipped).
 */
@Suppress("LongMethod")
@Composable
fun GalleryScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var showDialog by rememberSaveable { mutableStateOf(false) }

    DetailScaffold(onBackClick = onBack) {
        // Samples below draw their own bars: stop them from re-applying the system insets.
        Column(Modifier.consumeWindowInsets(WindowInsets.systemBars).verticalScroll(rememberScrollState())) {
            LargeTitle("Component gallery")
            Row(
                Modifier.padding(horizontal = Spacing.gutter),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = mode == themeMode,
                        onClick = { onThemeModeChange(mode) },
                        label = { Text(mode.name) },
                    )
                }
            }

            Section("Brand and colour")
            Row(
                Modifier.padding(horizontal = Spacing.gutter),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                RookLogo(height = 48.dp)
                Spacer(Modifier.width(Spacing.l))
                Text("VettID", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(Spacing.l))
            Swatches()

            Section("Typography")
            Column(Modifier.padding(horizontal = Spacing.gutter)) {
                Text("Headline · Plus Jakarta Sans", style = MaterialTheme.typography.headlineMedium)
                Text("Title · Messages", style = MaterialTheme.typography.titleLarge)
                Text("Body large · Inter, list title 16sp", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Body medium · preview line, 14sp",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Label · chips and buttons", style = MaterialTheme.typography.labelLarge)
            }

            Section("Top app bar")
            Frame {
                VettIdTopAppBar(
                    title = "Messages",
                    onMenuClick = {},
                    accountName = "Mesmer",
                    onAvatarClick = {},
                    onSearchClick = {},
                )
            }

            Section("Top bar search")
            Frame {
                com.vettid.core.ui.components.SearchTopBar(
                    com.vettid.core.ui.components.rememberTopBarSearch("pass", {}, "Search your vault", "Search by name or tag"),
                    autoFocus = false,
                )
            }

            Section("Navigation drawer")
            Box(Modifier.height(560.dp).padding(horizontal = Spacing.gutter).clip(RoundedCornerShape(16.dp))) {
                VettIdDrawerSheet(
                    sections = listOf(
                        listOf(
                            DrawerItem("m", "Messages", Icons.Outlined.ChatBubbleOutline),
                            DrawerItem("c", "Connections", Icons.Outlined.People),
                            DrawerItem("a", "Approvals", Icons.Outlined.TaskAlt, badge = 3),
                        ),
                        listOf(
                            DrawerItem("i", "Vault", Icons.Outlined.Inventory2),
                            DrawerItem("h", "History", Icons.Outlined.History),
                        ),
                        listOf(DrawerItem("s", "Settings", Icons.Outlined.Settings)),
                    ),
                    selectedKey = "m",
                    onItemClick = {},
                    versionLabel = "VettID 2.0.0-a0 (100)",
                )
            }

            Section("List rows")
            SampleRows.forEachIndexed { i, row ->
                VettIdListRow(
                    title = row.first,
                    supporting = row.second,
                    meta = row.third,
                    emphasized = i == 0,
                    onClick = {},
                )
            }

            Section("Connection rows (star = favourite)")
            var favorites by remember { mutableStateOf(setOf("Bob Okafor")) }
            SampleConnections.forEach { (name, status) ->
                ConnectionRow(
                    name = name,
                    supporting = status,
                    favorite = name in favorites,
                    onFavoriteChange = { fav -> favorites = if (fav) favorites + name else favorites - name },
                    onClick = {},
                )
            }

            Section("Empty state + floating controls")
            var unread by remember { mutableStateOf(false) }
            Frame(height = 460) {
                EmptyState(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    title = "No messages",
                    body = "Conversations with your connections appear here",
                )
                BottomFloatingControls(
                    start = { FloatingFilterChip("Unread", unread, { unread = it }) },
                    end = { VettIdFab(Icons.Outlined.Edit, "New message", {}) },
                )
            }

            Section("Detail screen")
            Frame(height = 520) {
                Column {
                    VettIdBackTopBar(onBackClick = {}, actions = {
                        androidx.compose.material3.IconButton(onClick = {}) {
                            androidx.compose.material3.Icon(Icons.Outlined.StarOutline, contentDescription = "Star")
                        }
                    })
                    CenteredTitle("Dinner on Friday")
                    DetailCard {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            InitialTile("Alice Moreau", size = 40)
                            Spacer(Modifier.width(Spacing.l))
                            Column(Modifier.weight(1f)) {
                                Text("Alice Moreau", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Connection · verified",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "Oct 3",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(Spacing.l))
                        Text("Are we still on for Friday? I booked a table for eight.", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                FloatingPillActionBar(
                    actions = listOf(
                        PillAction(Icons.AutoMirrored.Outlined.Reply, "Reply", {}),
                        PillAction(Icons.Outlined.QrCode2, "Show safety code", {}),
                        PillAction(Icons.Outlined.Delete, "Delete", { showDialog = true }),
                        PillAction(Icons.Outlined.MoreHoriz, "More", {}),
                    ),
                )
            }

            Section("Settings groups")
            Column(Modifier.background(VettIdTheme.colors.groupedBackground).padding(vertical = Spacing.s)) {
                SettingsGroup {
                    SettingsAccountRow(name = "Mesmer", detail = "mesmer@vettid.org", onClick = {})
                    SettingsDivider()
                    SettingsRow("Pair a device", {}, icon = Icons.Outlined.QrCode2)
                    SettingsDivider()
                    SettingsRow("Credential password", {}, icon = Icons.Outlined.Key)
                }
                SettingsSectionHeader("Security")
                SettingsGroup {
                    SettingsRow("Attestation", {}, icon = Icons.Outlined.VerifiedUser, tag = "New")
                    SettingsDivider()
                    var lockOn by remember { mutableStateOf(true) }
                    SettingsSwitchRow("Biometric app lock", lockOn, { lockOn = it }, icon = Icons.Outlined.Lock)
                    SettingsDivider()
                    SettingsRow("Vault status", {}, icon = Icons.Outlined.Storage, supporting = "Running · release 2026.10.1")
                }
            }

            Section("Avatar sheet")
            Column(
                Modifier
                    .padding(horizontal = Spacing.gutter)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(VettIdTheme.colors.groupedBackground)
                    .padding(top = Spacing.xl),
            ) {
                AvatarSheetContent(name = "Mesmer", detail = "mesmer@vettid.org", optionsHeader = "Options") {
                    SettingsGroup {
                        SettingsRow("Lock vault", {}, icon = Icons.Outlined.Lock, showChevron = false)
                        SettingsDivider()
                        SettingsRow("Open account portal", {}, icon = Icons.Outlined.Settings, showChevron = false)
                    }
                }
            }

            Section("Forms (A3): secret field, strength meter, buttons")
            FormsGallery()

            Section("Notices, steps and the urgent banner (A3)")
            NoticesGallery()

            Section("Conversation (A4): day divider, bubbles")
            ConversationGallery()

            Section("Invitation QR code and safety code (A4)")
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                com.vettid.core.ui.components.QrCode(GALLERY_QR, "Sample QR code", size = 200.dp)
                com.vettid.core.ui.components.SafetyCode("042817")
            }

            Section("Badges, tags, tiles, buttons")
            Row(
                Modifier.padding(horizontal = Spacing.gutter),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                CountBadge(3)
                CountBadge(120)
                TagLabel("New")
                InitialTile("Alice", size = 40)
                InitialTile("Bob", size = 40, style = TileStyle.Favorite)
                InitialTile("Mesmer", size = 40, style = TileStyle.Self)
                // A non-person row (History) and a shared profile photo (§10.8; an unreadable one shows the initial).
                InitialTile("History", size = 40, icon = Icons.Outlined.History)
                InitialTile(
                    "Photo", size = 40, style = TileStyle.Self,
                    photo = com.vettid.core.ui.components.rememberProfilePhoto(GALLERY_PHOTO),
                )
                InitialTile("Broken", size = 40, photo = com.vettid.core.ui.components.rememberProfilePhoto("bm90IGFuIGltYWdl"))
            }
            Spacer(Modifier.height(Spacing.l))
            Row(Modifier.padding(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Button(
                    onClick = { showSheet = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) { Text("Open sheet") }
                OutlinedButton(onClick = { showDialog = true }) { Text("Confirm dialog") }
            }
            Spacer(Modifier.height(Spacing.xxxl * 2))
        }
    }

    if (showSheet) {
        AvatarSheet(name = "Mesmer", detail = "mesmer@vettid.org", onDismiss = { showSheet = false }, optionsHeader = "Options") {
            SettingsGroup {
                SettingsRow("Lock vault", {}, icon = Icons.Outlined.Lock, showChevron = false)
            }
        }
    }
    if (showDialog) {
        ConfirmDialog(
            title = "Remove Alice?",
            text = "Alice will no longer be able to message you or read what you share.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = { showDialog = false },
            onDismiss = { showDialog = false },
        )
    }
}

private val SampleConnections = listOf(
    "Alice Moreau" to "Active · verified",
    "Bob Okafor" to "Active",
    "Clinic — Dr. Chen" to "Pending",
)

private val SampleRows = listOf(
    Triple("Alice Moreau", "Are we still on for Friday?", "10:42"),
    Triple("Bob Okafor", "Shared: Insurance card (expires 2027)", "Oct 2"),
    Triple("Clinic — Dr. Chen", "Requests: allergies, blood type", "Sep 28"),
)

@Composable
private fun Section(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Spacing.gutter + Spacing.xs, top = Spacing.xxl, bottom = Spacing.m),
    )
}

@Composable
private fun Frame(height: Int? = null, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        Modifier
            .padding(horizontal = Spacing.gutter)
            .fillMaxWidth()
            .then(if (height != null) Modifier.height(height.dp) else Modifier)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.background),
        content = content,
    )
}

@Composable
private fun Swatches() {
    val c = MaterialTheme.colorScheme
    val x = VettIdTheme.colors
    val swatches = listOf(
        "screen" to c.background,
        "drawer" to c.surfaceContainerLow,
        "grouped" to x.groupedBackground,
        "card" to x.card,
        "container" to c.surfaceContainer,
        "high" to c.surfaceContainerHigh,
        "gold fill" to c.primaryContainer,
        "gold content" to c.primary,
        "tile" to x.tile,
        "favourite" to x.favoriteTile,
        "error" to c.error,
        "outline" to c.outline,
    )
    Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        swatches.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                row.forEach { (name, color) -> Swatch(name, color, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Swatch(name: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                .background(color),
        )
        Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FormsGallery() {
    var pin by rememberSaveable { mutableStateOf("402816") }
    Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        com.vettid.core.ui.components.SecretField(pin, { pin = it }, "Vault PIN", isPin = true)
        com.vettid.core.ui.components.SecretField("weak", {}, "Credential password", error = "Use at least 8 characters.")
        com.vettid.core.ui.components.StrengthMeter(level = 1, label = "Weak")
        com.vettid.core.ui.components.StrengthMeter(level = 3, label = "Good")
        com.vettid.core.ui.components.PrimaryButton("Primary action", {})
        com.vettid.core.ui.components.PrimaryButton("Busy", {}, busy = true)
        com.vettid.core.ui.components.PrimaryButton("Delete my vault", {}, destructive = true)
        com.vettid.core.ui.components.SecondaryButton("Secondary action", {})
    }
}

// A made-up invitation payload (VAULT-MESSAGING §6.4 shape; not a real claim).
private const val GALLERY_QR =
    """{"v":2,"t":"c","r":"https://relay.vettid.test","c":"abcdefghijklmnopqrstuvwxyz","h":"x","k":"y","e":1791100000}"""

@Composable
private fun ConversationGallery() {
    Column {
        com.vettid.core.ui.components.DayDivider("4 Oct 2026")
        com.vettid.core.ui.components.MessageBubble(
            "Are we still on for Saturday?",
            outgoing = false,
            meta = "09:12",
            accessibilityLabel = "Sam: Are we still on for Saturday? 09:12",
        )
        com.vettid.core.ui.components.MessageBubble(
            "Yes, 10am at the market.",
            outgoing = true,
            meta = "09:14 · Read",
            accessibilityLabel = "You: Yes, 10am at the market. 09:14, Read",
        )
    }
}

@Composable
private fun NoticesGallery() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        com.vettid.core.ui.components.UrgentBanner("Your credential was presented by another device", "Review", {})
        com.vettid.core.ui.components.InfoBanner("The vault service is paused for maintenance. Try again later.")
        com.vettid.core.ui.components.InfoBanner(
            "A new vault release is available (release 5).",
            actionLabel = "Update now",
            onDismiss = {},
        )
        Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            com.vettid.core.ui.components.NoticeKind.entries.forEach { k ->
                com.vettid.core.ui.components.NoticeCard(k, "Notice: ${k.name.lowercase()}", "One or two lines of explanation.")
            }
            com.vettid.core.ui.components.StepList(
                listOf(
                    "Verifying the vault service" to com.vettid.core.ui.components.StepState.DONE,
                    "Creating your vault" to com.vettid.core.ui.components.StepState.ACTIVE,
                    "Connecting securely" to com.vettid.core.ui.components.StepState.PENDING,
                    "Confirming" to com.vettid.core.ui.components.StepState.FAILED,
                ),
            )
        }
        com.vettid.core.ui.components.SettingsGroup {
            com.vettid.core.ui.components.SettingsInfoRow("Info row", "A read-only value")
        }
    }
}

/** An 8×8 PNG in four colour blocks. */
private const val GALLERY_PHOTO =
    "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAH0lEQVR4nGP4f1AVjrz9TsARAxUl5Of1wdF/JEBFCQCiLXjhwCs13wAAAABJRU5ErkJggg=="
