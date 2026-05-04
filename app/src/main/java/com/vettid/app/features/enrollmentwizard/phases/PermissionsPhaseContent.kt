package com.vettid.app.features.enrollmentwizard.phases

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Permissions phase. Collects everything VettID needs to function the
 * first time:
 *   - Notifications (so we can reach the user about calls/messages).
 *   - Microphone (so the first incoming call doesn't connect with a
 *     dead mic — the OS prompt that fires on Answer often arrives
 *     too late for the WebRTC track to attach).
 *   - Camera (for video calls + QR scanning + profile photos).
 *   - Battery-optimization exemption (keeps notifications alive in
 *     the background).
 *
 * Each is optional — the user can continue past any of them — but
 * we make the request once here so the runtime UX after enrollment
 * doesn't surprise them.
 */
@Composable
fun PermissionsPhaseContent(
    notificationsGranted: Boolean?,
    onNotificationsResult: (Boolean) -> Unit,
    onContinue: () -> Unit
) {
    val context = LocalContext.current

    val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    fun isGranted(perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    var micGranted by remember { mutableStateOf(isGranted(Manifest.permission.RECORD_AUDIO)) }
    var cameraGranted by remember { mutableStateOf(isGranted(Manifest.permission.CAMERA)) }
    var locationGranted by remember {
        mutableStateOf(
            isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> onNotificationsResult(granted) }

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> cameraGranted = granted }

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> locationGranted = granted }

    LaunchedEffect(hasNotificationPermission) {
        if (hasNotificationPermission && notificationsGranted == null) {
            onNotificationsResult(true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Make VettID work first time",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "We'll set up the permissions VettID needs now so calls, messages, and notifications work the moment your enrollment finishes. Each is optional — you can continue without any of them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Notifications
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionCard(
                    icon = Icons.Default.Notifications,
                    title = "Notifications",
                    description = "Incoming calls, messages, connection requests, and security alerts.",
                    isGranted = notificationsGranted == true || hasNotificationPermission,
                    onRequest = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Microphone
            PermissionCard(
                icon = Icons.Default.Mic,
                title = "Microphone",
                description = "Voice and video calls. Granting now means the first call connects with audio working from the start — without this, the OS prompt fires too late for the call to attach your mic.",
                isGranted = micGranted,
                onRequest = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Camera
            PermissionCard(
                icon = Icons.Default.Videocam,
                title = "Camera",
                description = "Video calls, QR-code scanning to add connections, and your profile photo.",
                isGranted = cameraGranted,
                onRequest = { cameraLauncher.launch(Manifest.permission.CAMERA) }
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Location (optional — only used when you opt in to share
            // your location with a specific connection)
            PermissionCard(
                icon = Icons.Default.LocationOn,
                title = "Location",
                description = "Optional. Lets you share your location with a connection — only when you turn the toggle on for that connection. The OS won't prompt mid-flow if you grant it here.",
                isGranted = locationGranted,
                onRequest = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Battery optimization exemption
            val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
            var isBatteryExempt by remember {
                mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
            }
            var batteryRequestMade by remember { mutableStateOf(false) }
            if (batteryRequestMade && !isBatteryExempt) {
                LaunchedEffect(batteryRequestMade) {
                    while (true) {
                        kotlinx.coroutines.delay(500)
                        val exempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                        if (exempt) {
                            isBatteryExempt = true
                            break
                        }
                    }
                }
            }
            if (!isBatteryExempt) {
                PermissionCard(
                    icon = Icons.Default.BatteryChargingFull,
                    title = "Background activity",
                    description = "Keeps notifications alive when VettID is closed.",
                    isGranted = false,
                    onRequest = {
                        batteryRequestMade = true
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) { }
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Continue")
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isGranted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (isGranted) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Granted",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(24.dp)
                )
            } else {
                OutlinedButton(onClick = onRequest) { Text("Allow") }
            }
        }
    }
}
