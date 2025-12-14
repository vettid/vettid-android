package com.vettid.app.features.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vettid.app.R

/**
 * About screen showing app info and legal links.
 * Per mobile-ui-plan.md Section 3.3.3
 */
@Composable
fun AboutSettingsContent() {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App logo and version
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Logo
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "VettID Logo",
                    modifier = Modifier.size(80.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "VettID",
                    style = MaterialTheme.typography.headlineMedium
                )

                Text(
                    text = "Version 1.0.0",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Your secure personal vault for digital identity and private communications.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Legal Section
        Text(
            text = "LEGAL",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                AboutListItem(
                    icon = Icons.Default.Description,
                    title = "Terms of Service",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://vettid.dev/terms"))
                        context.startActivity(intent)
                    }
                )

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                AboutListItem(
                    icon = Icons.Default.PrivacyTip,
                    title = "Privacy Policy",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://vettid.dev/privacy"))
                        context.startActivity(intent)
                    }
                )

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                AboutListItem(
                    icon = Icons.Default.Code,
                    title = "Open Source Licenses",
                    onClick = {
                        // Would open licenses screen
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Support Section
        Text(
            text = "SUPPORT",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                AboutListItem(
                    icon = Icons.Default.Help,
                    title = "Help Center",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://vettid.dev/help"))
                        context.startActivity(intent)
                    }
                )

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                AboutListItem(
                    icon = Icons.Default.Email,
                    title = "Contact Support",
                    onClick = {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:support@vettid.dev")
                            putExtra(Intent.EXTRA_SUBJECT, "VettID Support Request")
                        }
                        context.startActivity(intent)
                    }
                )

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                AboutListItem(
                    icon = Icons.Default.BugReport,
                    title = "Report a Bug",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mesmerverse/vettid-android/issues"))
                        context.startActivity(intent)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Social Section
        Text(
            text = "CONNECT",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                AboutListItem(
                    icon = Icons.Default.Language,
                    title = "Website",
                    subtitle = "vettid.dev",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://vettid.dev"))
                        context.startActivity(intent)
                    }
                )

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                AboutListItem(
                    icon = Icons.Default.Code,
                    title = "GitHub",
                    subtitle = "github.com/mesmerverse",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mesmerverse"))
                        context.startActivity(intent)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Copyright
        Text(
            text = "© 2025 Mesmerverse, Inc.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "All rights reserved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun AboutListItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Default.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    )
}
