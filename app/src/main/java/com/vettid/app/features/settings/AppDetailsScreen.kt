package com.vettid.app.features.settings

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Full-screen app details screen with version info and searchable logs.
 * Navigated to by tapping version number in About section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // App info
    val packageInfo = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            null
        }
    }
    val versionName = packageInfo?.versionName ?: "Unknown"
    val versionCode = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode?.toString() ?: "Unknown"
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode?.toString() ?: "Unknown"
        }
    }
    val packageName = context.packageName
    val minSdk = context.applicationInfo.minSdkVersion
    val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
    val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    // Log state
    var showVaultLogs by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }

    var appLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    var vaultLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingLogs by remember { mutableStateOf(true) }

    // Load logs
    LaunchedEffect(showVaultLogs) {
        isLoadingLogs = true
        try {
            if (showVaultLogs) {
                val process = Runtime.getRuntime().exec(
                    arrayOf(
                        "logcat", "-d", "-t", "200", "-s",
                        "NitroEnrollmentClient:*",
                        "NitroAttestation:*",
                        "NitroWebSocket:*",
                        "VaultManager:*",
                        "CredentialStore:*",
                        "CryptoManager:*",
                        "PinUnlockViewModel:*",
                        "EnrollmentWizardVM:*",
                        "NatsAutoConnector:*",
                        "FeedClient:*",
                        "FeedRepository:*"
                    )
                )
                vaultLogs = process.inputStream.bufferedReader().readLines()
            } else {
                val process = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-d", "-t", "200", "--pid=${android.os.Process.myPid()}")
                )
                appLogs = process.inputStream.bufferedReader().readLines()
            }
        } catch (e: Exception) {
            val errorLine = "Failed to load logs: ${e.message}"
            if (showVaultLogs) vaultLogs = listOf(errorLine)
            else appLogs = listOf(errorLine)
        }
        isLoadingLogs = false
    }

    val displayLogs = if (showVaultLogs) vaultLogs else appLogs
    val filteredLogs = if (searchQuery.isBlank()) displayLogs else {
        displayLogs.filter { it.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searchExpanded = !searchExpanded
                        if (!searchExpanded) searchQuery = ""
                    }) {
                        Icon(
                            imageVector = if (searchExpanded) Icons.Default.SearchOff else Icons.Default.Search,
                            contentDescription = "Search logs"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            AnimatedVisibility(visible = searchExpanded) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter logs...") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    }
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // App info card
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "VettID",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            AppInfoRow("Version", "$versionName ($versionCode)")
                            AppInfoRow("Package", packageName)
                            AppInfoRow("Min SDK", minSdk.toString())
                            AppInfoRow("Device", deviceModel)
                            AppInfoRow("OS", androidVersion)
                        }
                    }
                }

                // Log type toggle
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "LOGS",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        FilterChip(
                            selected = showVaultLogs,
                            onClick = { showVaultLogs = !showVaultLogs },
                            label = { Text(if (showVaultLogs) "Vault" else "App") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (showVaultLogs) Icons.Default.Cloud else Icons.Default.PhoneAndroid,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                }

                // Loading indicator
                if (isLoadingLogs) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (filteredLogs.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (searchQuery.isNotEmpty()) "No matching log lines"
                                else "No logs available",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // Log lines
                    items(filteredLogs) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}
